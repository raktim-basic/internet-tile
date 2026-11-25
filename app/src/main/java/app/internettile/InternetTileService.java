package app.internettile;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;


public class InternetTileService extends TileService {
    private enum Transport { NONE, WIFI, CELLULAR }

    private static final String TAG = "InternetTile";
    private static final int LEVEL_COUNT = 5;
    private static final String PREFS_NETWORK_LABELS = "network_labels";
    private static final String PREF_WIFI_SSID = "wifi_ssid";
    private static final String PREF_WIFI_NETWORK_HANDLE = "wifi_network_handle";

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private TelephonyManager baseTelephonyManager;
    private TelephonyManager activeTelephonyManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager.NetworkCallback cellularNetworkCallback;
    private TelephonyCallback telephonyCallback;
    private boolean receiverRegistered;
    private volatile boolean listening;
    private boolean dataEnabledApiDenied;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = this::updateTileNow;

    private volatile NetworkCapabilities lastDefaultCapabilities;
    private volatile Network lastDefaultNetwork;
    private volatile NetworkCapabilities lastCellularCapabilities;
    // WifiInfo is retained only for signal strength. SSID is cached separately because
    // Android may redact it from later WifiInfo instances even while RSSI remains usable.
    private volatile WifiInfo lastWifiInfo;
    private volatile String lastKnownSsid;
    private volatile long lastSsidNetworkHandle = -1L;
    private volatile Integer lastCellularLevel;
    private volatile TelephonyDisplayInfo lastDisplayInfo;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                String action = intent == null ? null : intent.getAction();
                if (isDataSubscriptionChangeAction(action)) {
                    unregisterTelephonyListener();
                    registerTelephonyListener();
                }
                refreshDefaultNetworkSnapshot();
                updateTile();
            } catch (RuntimeException e) {
                Log.e(TAG, "State receiver failed", e);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        connectivityManager = getSystemService(ConnectivityManager.class);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        baseTelephonyManager = getSystemService(TelephonyManager.class);
    }

    @Override public void onStartListening() {
        super.onStartListening();
        listening = true;
        // Do not retain a previously exposed SSID after location permission is revoked.
        if (!hasFineLocationPermission()) lastWifiInfo = null;
        registerListeners();
        refreshDefaultNetworkSnapshot();
        updateTileNow();
    }

    @Override public void onStopListening() {
        listening = false;
        mainHandler.removeCallbacks(updateRunnable);
        unregisterListeners();
        super.onStopListening();
    }

    @Override public void onClick() {
        Intent panel = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this, 0, panel,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(panel);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not open the native Internet panel", e);
        }
    }

    private boolean isDataSubscriptionChangeAction(String action) {
        return SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED.equals(action)
                || "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED".equals(action);
    }

    private void registerListeners() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
            filter.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        }

        if (connectivityManager != null && networkCallback == null) {
            networkCallback = createNetworkCallback();
            try {
                connectivityManager.registerDefaultNetworkCallback(
                        networkCallback, new Handler(Looper.getMainLooper()));
            } catch (RuntimeException e) {
                Log.w(TAG, "Default network callback unavailable", e);
                networkCallback = null;
            }
        }

        // Observe cellular availability independently of the default network. This catches
        // mobile-data enable/disable changes even while Wi-Fi remains the default route.
        if (connectivityManager != null && cellularNetworkCallback == null) {
            cellularNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { updateTile(); }
                @Override public void onLost(Network network) {
                    lastCellularCapabilities = null;
                    updateTile();
                }
                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    lastCellularCapabilities = capabilities;
                    updateTile();
                }
            };
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .build();
                connectivityManager.registerNetworkCallback(
                        request, cellularNetworkCallback, new Handler(Looper.getMainLooper()));
            } catch (RuntimeException e) {
                Log.w(TAG, "Cellular network callback unavailable", e);
                cellularNetworkCallback = null;
            }
        }

        registerTelephonyListener();
    }

    private ConnectivityManager.NetworkCallback createNetworkCallback() {
        ConnectivityManager.NetworkCallback callback;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Register consistently with location-aware delivery enabled. Android still
            // enforces runtime permission and Location Services state when delivering data.
            Log.d(TAG, "Creating location-aware default-network callback; fineLocation="
                    + hasFineLocationPermission());
            callback = new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onAvailable(Network network) {
                    // Wait for onCapabilitiesChanged before reading the new default route.
                }

                @Override public void onLost(Network network) {
                    if (network.equals(lastDefaultNetwork)) {
                        lastDefaultNetwork = null;
                        lastDefaultCapabilities = null;
                        lastWifiInfo = null;
                        clearWifiSsidCache();
                    }
                    updateTile();
                }

                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    lastDefaultNetwork = network;
                    lastDefaultCapabilities = capabilities;
                    cacheWifiInfo(network, capabilities);
                    updateTile();
                }

                @Override public void onLinkPropertiesChanged(
                        Network network, android.net.LinkProperties linkProperties) {
                    updateTile();
                }
            };
        } else {
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    // Wait for onCapabilitiesChanged before reading the new default route.
                }

                @Override public void onLost(Network network) {
                    if (network.equals(lastDefaultNetwork)) {
                        lastDefaultNetwork = null;
                        lastDefaultCapabilities = null;
                        lastWifiInfo = null;
                        clearWifiSsidCache();
                    }
                    updateTile();
                }

                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    lastDefaultNetwork = network;
                    lastDefaultCapabilities = capabilities;
                    cacheWifiInfo(network, capabilities);
                    updateTile();
                }

                @Override public void onLinkPropertiesChanged(
                        Network network, android.net.LinkProperties linkProperties) {
                    updateTile();
                }
            };
        }
        return callback;
    }

    private void registerTelephonyListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || baseTelephonyManager == null || telephonyCallback != null) return;

        activeTelephonyManager = getActiveDataTelephonyManager();
        if (activeTelephonyManager == null) return;
        NetworkTelephonyCallback callback = new NetworkTelephonyCallback(this);
        telephonyCallback = callback;
        try {
            activeTelephonyManager.registerTelephonyCallback(getMainExecutor(), callback);
        } catch (RuntimeException e) {
            Log.d(TAG, "Telephony callbacks unavailable; using network capabilities", e);
            telephonyCallback = null;
            activeTelephonyManager = null;
        }
    }

    private TelephonyManager getActiveDataTelephonyManager() {
        if (baseTelephonyManager == null) return null;
        try {
            int subId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? SubscriptionManager.getActiveDataSubscriptionId()
                    : SubscriptionManager.getDefaultDataSubscriptionId();
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return baseTelephonyManager.createForSubscriptionId(subId);
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Could not resolve active data subscription", e);
        }
        return baseTelephonyManager;
    }

    private void unregisterTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && activeTelephonyManager != null && telephonyCallback != null) {
            try {
                activeTelephonyManager.unregisterTelephonyCallback(telephonyCallback);
            } catch (RuntimeException ignored) { }
        }
        telephonyCallback = null;
        activeTelephonyManager = null;
        lastCellularLevel = null;
        lastDisplayInfo = null;
    }

    private void unregisterListeners() {
        if (receiverRegistered) {
            try { unregisterReceiver(stateReceiver); }
            catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
            networkCallback = null;
        }
        if (connectivityManager != null && cellularNetworkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(cellularNetworkCallback); }
            catch (RuntimeException ignored) { }
            cellularNetworkCallback = null;
        }
        lastCellularCapabilities = null;
        unregisterTelephonyListener();
    }

    private void refreshDefaultNetworkSnapshot() {
        if (connectivityManager == null) return;
        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null
                    ? null : connectivityManager.getNetworkCapabilities(active);
            if (capabilities != null) {
                lastDefaultNetwork = active;
                lastDefaultCapabilities = capabilities;
                // Snapshot capabilities are useful for transport and RSSI, but Android
                // strips location-sensitive WifiInfo fields from this synchronous API.
                Object transportInfo = capabilities.getTransportInfo();
                if (transportInfo instanceof WifiInfo) lastWifiInfo = (WifiInfo) transportInfo;
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    restoreWifiSsidForNetwork(active);
                } else {
                    clearWifiSsidCache();
                }
            } else {
                lastDefaultNetwork = null;
                lastDefaultCapabilities = null;
                lastWifiInfo = null;
                clearWifiSsidCache();
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Unable to refresh default network", e);
        }
    }

    private void cacheWifiInfo(Network network, NetworkCapabilities capabilities) {
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;

        Object transportInfo = capabilities.getTransportInfo();
        if (transportInfo instanceof WifiInfo) {
            WifiInfo candidate = (WifiInfo) transportInfo;
            // Keep WifiInfo for RSSI even when SSID is redacted.
            lastWifiInfo = candidate;
            String ssid = normalizeSsid(safeSsid(candidate));
            if (isUsableSsid(ssid)) {
                long handle = network.getNetworkHandle();
                lastKnownSsid = ssid;
                lastSsidNetworkHandle = handle;
                getSharedPreferences(PREFS_NETWORK_LABELS, MODE_PRIVATE).edit()
                        .putString(PREF_WIFI_SSID, ssid)
                        .putLong(PREF_WIFI_NETWORK_HANDLE, handle)
                        .apply();
                Log.d(TAG, "Cached Wi-Fi SSID for active network");
            } else {
                Log.d(TAG, "Wi-Fi SSID redacted; retaining cache only for matching network");
                restoreWifiSsidForNetwork(network);
            }
        } else {
            Log.d(TAG, "Wi-Fi capabilities contain no WifiInfo transport data");
            restoreWifiSsidForNetwork(network);
        }
    }

    private void restoreWifiSsidForNetwork(Network network) {
        if (!hasFineLocationPermission()) {
            clearWifiSsidCache();
            return;
        }
        long handle = network.getNetworkHandle();
        if (lastKnownSsid != null && lastSsidNetworkHandle == handle) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NETWORK_LABELS, MODE_PRIVATE);
        String cached = prefs.getString(PREF_WIFI_SSID, null);
        long cachedHandle = prefs.getLong(PREF_WIFI_NETWORK_HANDLE, -1L);
        if (isUsableSsid(cached) && cachedHandle == handle) {
            lastKnownSsid = cached;
            lastSsidNetworkHandle = cachedHandle;
        } else {
            clearWifiSsidCache();
        }
    }

    private void clearWifiSsidCache() {
        lastKnownSsid = null;
        lastSsidNetworkHandle = -1L;
        getSharedPreferences(PREFS_NETWORK_LABELS, MODE_PRIVATE).edit()
                .remove(PREF_WIFI_SSID)
                .remove(PREF_WIFI_NETWORK_HANDLE)
                .apply();
    }

    private String safeSsid(WifiInfo info) {
        if (info == null) return null;
        try { return info.getSSID(); }
        catch (RuntimeException e) {
            Log.d(TAG, "Could not read SSID from WifiInfo", e);
            return null;
        }
    }

    private String normalizeSsid(String ssid) {
        if (ssid == null) return null;
        String value = ssid.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private boolean isUsableSsid(String ssid) {
        return ssid != null && !ssid.isEmpty()
                && !WifiManager.UNKNOWN_SSID.equals(ssid)
                && !"<unknown ssid>".equalsIgnoreCase(ssid)
                && !"<none>".equalsIgnoreCase(ssid);
    }

    /** Coalesces bursts of callbacks into one tile update and ignores late events. */
    private void updateTile() {
        if (!listening) return;
        mainHandler.removeCallbacks(updateRunnable);
        mainHandler.postDelayed(updateRunnable, 30);
    }

    /** Never let an exception escape a callback: it would kill the tile's process. */
    private void updateTileNow() {
        if (!listening) return;
        try {
            renderTile();
        } catch (RuntimeException e) {
            Log.e(TAG, "Tile update failed", e);
        }
    }

    private void renderTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean airplaneMode = isAirplaneModeEnabled();
        boolean wifiEnabled = isWifiEnabled();
        boolean mobileDataEnabled = isMobileDataEnabled();
        Transport transport = getActiveTransport();
        boolean noInternet = isActiveTransportUnvalidated(transport);
        // Wi-Fi can stay connected in airplane mode; then show it like any Wi-Fi connection.
        boolean showAirplane = airplaneMode && transport != Transport.WIFI;

        tile.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(showAirplane
                    ? getString(R.string.connection_airplane_mode)
                    : getSubtitle(transport));
        }
        tile.setIcon(getLocalSignalIcon(transport, wifiEnabled, mobileDataEnabled,
                showAirplane, noInternet));
        tile.setState(!showAirplane && (wifiEnabled || mobileDataEnabled)
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private boolean isAirplaneModeEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (RuntimeException e) {
            Log.d(TAG, "Unable to read airplane mode state", e);
            return false;
        }
    }

    private boolean isActiveTransportUnvalidated(Transport transport) {
        if (transport == Transport.NONE) return false;
        NetworkCapabilities caps = lastDefaultCapabilities;
        if (caps == null) return true;
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private boolean isWifiEnabled() {
        if (wifiManager == null) return false;
        try { return wifiManager.isWifiEnabled(); }
        catch (RuntimeException e) {
            Log.w(TAG, "Unable to read Wi-Fi state", e);
            return false;
        }
    }

    private boolean isMobileDataEnabled() {
        if (baseTelephonyManager != null && !dataEnabledApiDenied) {
            try { return baseTelephonyManager.isDataEnabled(); }
            catch (SecurityException e) {
                dataEnabledApiDenied = true;
                Log.d(TAG, "isDataEnabled() denied; using Settings fallback from now on");
            } catch (RuntimeException e) {
                Log.d(TAG, "Telephony data-state API unavailable; trying Settings fallback", e);
            }
        }
        try {
            return Settings.Global.getInt(getContentResolver(), "mobile_data", 0) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read mobile-data state", e);
            return false;
        }
    }

    private Transport getActiveTransport() {
        NetworkCapabilities caps = lastDefaultCapabilities;
        if (caps == null && connectivityManager != null) {
            Network active = connectivityManager.getActiveNetwork();
            caps = active == null ? null : connectivityManager.getNetworkCapabilities(active);
        }
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Transport.WIFI;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return Transport.CELLULAR;
        }

        // Preserve a cellular presentation when mobile data is enabled but there is no
        // default route (for example, no service or no data network).
        if (isMobileDataEnabled()) return Transport.CELLULAR;
        return Transport.NONE;
    }

    private int getWifiLevel() {
        int rssi = currentWifiRssi();
        if (rssi <= -127) return 0;
        return clamp(calculateWifiLevel(rssi));
    }

    /** Freshest RSSI available; RSSI is never location-redacted. */
    private int currentWifiRssi() {
        if (wifiManager != null) {
            try {
                WifiInfo live = wifiManager.getConnectionInfo();
                if (live != null && live.getRssi() > -127) return live.getRssi();
            } catch (RuntimeException e) {
                Log.d(TAG, "Live WifiInfo unavailable for RSSI", e);
            }
        }
        WifiInfo info = getCurrentWifiInfo();
        return info == null ? -127 : info.getRssi();
    }

    /**
     * Uses the same device-specific RSSI thresholds as the status bar. The static
     * calculateSignalLevel(rssi, 5) uses a fixed -100..-55 dBm scale, which can be one level
     * lower than the system's value.
     */
    @SuppressWarnings("deprecation")
    private int calculateWifiLevel(int rssi) {
        if (wifiManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                int level = wifiManager.calculateSignalLevel(rssi);
                int max = wifiManager.getMaxSignalLevel();
                if (max > 0 && max != LEVEL_COUNT - 1) {
                    level = Math.round(level * (LEVEL_COUNT - 1f) / max);
                }
                return level;
            } catch (RuntimeException e) {
                Log.d(TAG, "System Wi-Fi level API unavailable", e);
            }
        }
        return WifiManager.calculateSignalLevel(rssi, LEVEL_COUNT);
    }

    private WifiInfo getCurrentWifiInfo() {
        WifiInfo info = lastWifiInfo;
        if (info != null) return info;
        if (wifiManager != null) {
            try {
                info = wifiManager.getConnectionInfo();
                if (info != null) lastWifiInfo = info;
            } catch (SecurityException e) {
                Log.d(TAG, "Wi-Fi details require location permission", e);
            } catch (RuntimeException e) {
                Log.d(TAG, "WifiInfo unavailable", e);
            }
        }
        return info;
    }

    private int getCellularLevel() {
        Integer callbackLevel = lastCellularLevel;
        if (callbackLevel != null) return clamp(callbackLevel);

        NetworkCapabilities caps = lastDefaultCapabilities;
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            caps = lastCellularCapabilities;
        }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            int strength = caps.getSignalStrength();
            if (strength >= 0 && strength <= 4) return strength;
            int mapped = mapDbm(strength);
            if (mapped >= 0) return mapped;
        }

        if (activeTelephonyManager != null) {
            try {
                SignalStrength signal = activeTelephonyManager.getSignalStrength();
                if (signal != null) return clamp(signal.getLevel());
            } catch (RuntimeException e) {
                Log.d(TAG, "Telephony signal API unavailable", e);
            }
        }
        return 0;
    }

    private int mapDbm(int dbm) {
        if (dbm == Integer.MIN_VALUE || dbm == -1 || dbm >= 0) return -1;
        if (dbm >= -80) return 4;
        if (dbm >= -90) return 3;
        if (dbm >= -100) return 2;
        if (dbm >= -110) return 1;
        return 0;
    }

    private int clamp(int level) { return Math.max(0, Math.min(4, level)); }

    private Icon getLocalSignalIcon(Transport transport,
                                    boolean wifiEnabled,
                                    boolean mobileDataEnabled,
                                    boolean airplaneMode,
                                    boolean noInternet) {
        final int resource;
        if (airplaneMode) {
            resource = R.drawable.ic_airplane_mode;
        } else if (transport == Transport.CELLULAR) {
            int level = getCellularLevel();
            resource = noInternet
                    ? cellularWarningIconForLevel(level)
                    : cellularIconForLevel(level);
        } else if (transport == Transport.WIFI) {
            int level = getWifiLevel();
            resource = noInternet
                    ? wifiWarningIconForLevel(level)
                    : wifiIconForLevel(level);
        } else if (wifiEnabled) {
            resource = R.drawable.ic_wifi_0;
        } else if (mobileDataEnabled) {
            resource = R.drawable.ic_cellular_0;
        } else {
            resource = R.drawable.ic_wifi_off;
        }
        return Icon.createWithResource(this, resource);
    }

    private int wifiWarningIconForLevel(int level) {
        switch (clamp(level)) {
            case 0: return R.drawable.ic_wifi_0_warning;
            case 1: return R.drawable.ic_wifi_1_warning;
            case 2: return R.drawable.ic_wifi_2_warning;
            case 3: return R.drawable.ic_wifi_3_warning;
            default: return R.drawable.ic_wifi_4_warning;
        }
    }

    private int cellularWarningIconForLevel(int level) {
        switch (clamp(level)) {
            case 0: return R.drawable.ic_cellular_0_warning;
            case 1: return R.drawable.ic_cellular_1_warning;
            case 2: return R.drawable.ic_cellular_2_warning;
            case 3: return R.drawable.ic_cellular_3_warning;
            default: return R.drawable.ic_cellular_4_warning;
        }
    }

    private int wifiIconForLevel(int level) {
        switch (clamp(level)) {
            case 0: return R.drawable.ic_wifi_0;
            case 1: return R.drawable.ic_wifi_1;
            case 2: return R.drawable.ic_wifi_2;
            case 3: return R.drawable.ic_wifi_3;
            default: return R.drawable.ic_wifi_4;
        }
    }

    private int cellularIconForLevel(int level) {
        switch (clamp(level)) {
            case 0: return R.drawable.ic_cellular_0;
            case 1: return R.drawable.ic_cellular_1;
            case 2: return R.drawable.ic_cellular_2;
            case 3: return R.drawable.ic_cellular_3;
            default: return R.drawable.ic_cellular_4;
        }
    }

    private String getSubtitle(Transport transport) {
        if (transport == Transport.WIFI) return getWifiNetworkName();
        if (transport == Transport.CELLULAR) return getMobileNetworkName();
        return getString(R.string.connection_idle);
    }

    private String getWifiNetworkName() {
        Network network = lastDefaultNetwork;
        if (network == null || lastDefaultCapabilities == null
                || !lastDefaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return getString(R.string.connection_wifi_fallback);
        }
        if (!hasFineLocationPermission()) {
            clearWifiSsidCache();
            return getString(R.string.connection_wifi_fallback);
        }
        restoreWifiSsidForNetwork(network);
        String ssid = lastKnownSsid;
        return isUsableSsid(ssid) ? ssid : getString(R.string.connection_wifi_fallback);
    }

    private String getMobileNetworkName() {
        TelephonyManager manager = activeTelephonyManager != null
                ? activeTelephonyManager : getActiveDataTelephonyManager();
        if (manager == null) return getString(R.string.connection_mobile_fallback);

        String operator = null;
        try { operator = manager.getNetworkOperatorName(); }
        catch (RuntimeException e) { Log.d(TAG, "Operator name unavailable", e); }
        if (operator == null || operator.trim().isEmpty()) {
            try { operator = manager.getSimOperatorName(); }
            catch (RuntimeException ignored) { }
        }
        if (operator == null || operator.trim().isEmpty()) {
            operator = getString(R.string.connection_mobile_fallback);
        } else {
            operator = operator.trim();
        }

        // Carrier branding may already contain the radio generation (e.g. "Jio True 5G").
        return operator;
    }

    private String getNetworkGeneration(TelephonyManager manager) {
        int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        TelephonyDisplayInfo displayInfo = lastDisplayInfo;
        if (displayInfo != null) {
            int override = displayInfo.getOverrideNetworkType();
            if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                    || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
                return "5G";
            }
            networkType = displayInfo.getNetworkType();
        }
        try {
            int dataType = manager.getDataNetworkType();
            if (dataType != TelephonyManager.NETWORK_TYPE_UNKNOWN) networkType = dataType;
        } catch (RuntimeException e) {
            Log.d(TAG, "Data network type unavailable", e);
        }

        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "3G";
            default:
                return "";
        }
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static class NetworkTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener {
        private final InternetTileService service;

        NetworkTelephonyCallback(InternetTileService service) { this.service = service; }

        @Override public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            service.lastCellularLevel = signalStrength == null
                    ? null : service.clamp(signalStrength.getLevel());
            service.updateTile();
        }

        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
            service.lastDisplayInfo = displayInfo;
            service.updateTile();
        }

    }
}
