package app.internettile;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private TelephonyManager baseTelephonyManager;
    private TelephonyManager activeTelephonyManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager.NetworkCallback cellularNetworkCallback;
    private TelephonyCallback telephonyCallback;
    private boolean receiverRegistered;

    private volatile NetworkCapabilities lastDefaultCapabilities;
    private volatile Network lastDefaultNetwork;
    private volatile NetworkCapabilities lastCellularCapabilities;
    private volatile WifiInfo lastWifiInfo;
    private volatile String lastKnownSsid;
    private volatile Network lastWifiNetwork;
    private volatile Integer lastCellularLevel;
    private volatile TelephonyDisplayInfo lastDisplayInfo;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (isDataSubscriptionChangeAction(action)) {
                unregisterTelephonyListener();
                registerTelephonyListener();
            }
            refreshDefaultNetworkSnapshot();
            updateTile();
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
        // Do not retain a previously exposed SSID after location permission is revoked.
        if (!hasFineLocationPermission()) {
            lastWifiInfo = null;
            lastKnownSsid = null;
            lastWifiNetwork = null;
            Log.d(TAG, "Cleared cached Wi-Fi identity: fine location permission unavailable");
        }
        registerListeners();
        refreshDefaultNetworkSnapshot();
        updateTile();
    }

    @Override public void onStopListening() {
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
        boolean includeLocationInfo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && hasFineLocationPermission();
        Log.d(TAG, "Creating default-network callback; fineLocation="
                + hasFineLocationPermission() + ", includeLocationInfo=" + includeLocationInfo);

        ConnectivityManager.NetworkCallback callback;
        if (includeLocationInfo) {
            callback = new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onAvailable(Network network) {
                    Log.d(TAG, "Default network available");
                }

                @Override public void onLost(Network network) {
                    if (network.equals(lastDefaultNetwork)) {
                        lastDefaultNetwork = null;
                        lastDefaultCapabilities = null;
                    }
                    clearWifiIdentityForNetwork(network);
                    updateTile();
                }

                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    lastDefaultNetwork = network;
                    lastDefaultCapabilities = capabilities;
                    cacheWifiSsidFromCallback(network, capabilities);
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
                    Log.d(TAG, "Default network available (without location info)");
                }

                @Override public void onLost(Network network) {
                    if (network.equals(lastDefaultNetwork)) {
                        lastDefaultNetwork = null;
                        lastDefaultCapabilities = null;
                    }
                    clearWifiIdentityForNetwork(network);
                    updateTile();
                }

                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    lastDefaultNetwork = network;
                    lastDefaultCapabilities = capabilities;
                    // This callback intentionally has no location flag. Do not use its
                    // potentially redacted WifiInfo to replace the SSID cache.
                    logWifiCallbackAvailability(capabilities, false);
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
            } else {
                lastDefaultNetwork = null;
                lastDefaultCapabilities = null;
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Unable to refresh default network", e);
        }
    }

    private void cacheWifiSsidFromCallback(Network network, NetworkCapabilities capabilities) {
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            clearWifiIdentityIfNetworkChanged(network);
            return;
        }

        Network previousNetwork = lastWifiNetwork;
        if (previousNetwork != null && !previousNetwork.equals(network)) {
            lastKnownSsid = null;
            lastWifiInfo = null;
            Log.d(TAG, "Wi-Fi network identity changed; discarded previous SSID");
        }
        lastWifiNetwork = network;

        Object transportInfo = capabilities.getTransportInfo();
        if (!(transportInfo instanceof WifiInfo)) {
            Log.d(TAG, "Location-aware callback supplied no WifiInfo transport data");
            return;
        }

        WifiInfo candidate = (WifiInfo) transportInfo;
        String ssid = normalizeSsid(safeSsid(candidate));
        if (isUsableSsid(ssid)) {
            lastKnownSsid = ssid;
            lastWifiInfo = candidate;
            Log.d(TAG, "Cached SSID string from location-aware network callback");
        } else {
            Log.d(TAG, "Location-aware callback returned redacted/unknown SSID; cache preserved");
        }
    }

    private void logWifiCallbackAvailability(NetworkCapabilities capabilities,
                                             boolean includesLocationInfo) {
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;
        Object info = capabilities.getTransportInfo();
        Log.d(TAG, "Wi-Fi callback: includeLocationInfo=" + includesLocationInfo
                + ", hasWifiInfo=" + (info instanceof WifiInfo));
    }

    private void clearWifiIdentityForNetwork(Network network) {
        Network cachedNetwork = lastWifiNetwork;
        if (cachedNetwork != null && cachedNetwork.equals(network)) {
            lastWifiNetwork = null;
            lastKnownSsid = null;
            lastWifiInfo = null;
            Log.d(TAG, "Cleared cached SSID because its network was lost");
        }
    }

    private void clearWifiIdentityIfNetworkChanged(Network currentNetwork) {
        Network cachedNetwork = lastWifiNetwork;
        if (cachedNetwork != null && !cachedNetwork.equals(currentNetwork)) {
            lastWifiNetwork = null;
            lastKnownSsid = null;
            lastWifiInfo = null;
            Log.d(TAG, "Cleared cached SSID after default network changed away from Wi-Fi");
        }
    }

    private String normalizeSsid(String ssid) {
        if (ssid == null) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private String safeSsid(WifiInfo info) {
        if (info == null) return null;
        try { return info.getSSID(); }
        catch (RuntimeException e) {
            Log.d(TAG, "Could not read SSID from WifiInfo", e);
            return null;
        }
    }

    private boolean isUsableSsid(String ssid) {
        return ssid != null && !ssid.isEmpty()
                && !WifiManager.UNKNOWN_SSID.equals(ssid)
                && !"<unknown ssid>".equalsIgnoreCase(ssid)
                && !"<none>".equalsIgnoreCase(ssid);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean wifiEnabled = isWifiEnabled();
        boolean mobileDataEnabled = isMobileDataEnabled();
        boolean anyRadioEnabled = wifiEnabled || mobileDataEnabled;
        Transport transport = getActiveTransport();

        tile.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getSubtitle(transport));
        }
        tile.setIcon(getLocalSignalIcon(transport, wifiEnabled, mobileDataEnabled));
        tile.setState(anyRadioEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
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
        if (baseTelephonyManager != null) {
            try { return baseTelephonyManager.isDataEnabled(); }
            catch (RuntimeException e) {
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
        if (caps == null) return Transport.NONE;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Transport.WIFI;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return Transport.CELLULAR;
        return Transport.NONE;
    }

    private int getWifiLevel() {
        WifiInfo info = getCurrentWifiInfo();
        if (info == null) return 0;
        int rssi = info.getRssi();
        if (rssi <= -127) return 0;
        return clamp(WifiManager.calculateSignalLevel(rssi, LEVEL_COUNT));
    }

    private WifiInfo getCurrentWifiInfo() {
        WifiInfo cached = lastWifiInfo;
        if (cached != null && isUsableSsid(safeSsid(cached))) return cached;

        if (wifiManager != null) {
            try {
                WifiInfo candidate = wifiManager.getConnectionInfo();
                if (candidate != null && isUsableSsid(safeSsid(candidate))) {
                    lastWifiInfo = candidate;
                    Log.d(TAG, "Cached usable SSID from WifiManager fallback");
                    return candidate;
                }
                Log.d(TAG, "WifiManager fallback returned redacted/unknown SSID; fineLocation="
                        + hasFineLocationPermission());
            } catch (SecurityException e) {
                Log.d(TAG, "Wi-Fi details require location permission", e);
            } catch (RuntimeException e) {
                Log.d(TAG, "WifiInfo unavailable", e);
            }
        }
        // Preserve a previously valid value; never replace it with a redacted WifiInfo.
        return cached;
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
                                    boolean mobileDataEnabled) {
        final int resource;
        if (transport == Transport.CELLULAR) {
            resource = cellularIconForLevel(getCellularLevel());
        } else if (transport == Transport.WIFI) {
            resource = wifiIconForLevel(getWifiLevel());
        } else if (wifiEnabled) {
            resource = R.drawable.ic_wifi_0;
        } else if (mobileDataEnabled) {
            resource = R.drawable.ic_cellular_0;
        } else {
            resource = R.drawable.ic_wifi_off;
        }
        return Icon.createWithResource(this, resource);
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
        // Do not use the permission check as a gate here. It is useful for deciding whether
        // to request location-bearing NetworkCapabilities, but it must not suppress the
        // independent WifiManager retrieval attempt. The platform will enforce access and
        // either return a usable SSID or UNKNOWN_SSID / throw SecurityException.
        String cachedSsid = lastKnownSsid;
        if (isUsableSsid(cachedSsid)) return cachedSsid;

        if (wifiManager != null) {
            try {
                WifiInfo candidate = wifiManager.getConnectionInfo();
                String ssid = normalizeSsid(safeSsid(candidate));
                if (isUsableSsid(ssid)) {
                    lastKnownSsid = ssid;
                    lastWifiInfo = candidate;
                    Log.d(TAG, "Cached SSID string from WifiManager; fineLocation="
                            + hasFineLocationPermission() + ", locationEnabled="
                            + isLocationEnabled());
                    return ssid;
                }
                Log.d(TAG, "WifiManager returned no usable SSID; fineLocation="
                        + hasFineLocationPermission() + ", locationEnabled="
                        + isLocationEnabled() + ", candidateNull=" + (candidate == null));
            } catch (SecurityException e) {
                Log.w(TAG, "WifiManager SSID access denied; fineLocation="
                        + hasFineLocationPermission(), e);
            } catch (RuntimeException e) {
                Log.w(TAG, "WifiManager SSID retrieval failed", e);
            }
        } else {
            Log.w(TAG, "WifiManager service unavailable while retrieving SSID");
        }
        return getString(R.string.connection_wifi_fallback);
    }

    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.location.LocationManager locationManager =
                    getSystemService(android.location.LocationManager.class);
            return locationManager != null && locationManager.isLocationEnabled();
        }
        return true;
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
