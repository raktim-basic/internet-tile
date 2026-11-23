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
        // SSID is location-sensitive. Never retain a name across a permission revocation.
        if (!hasFineLocationPermission()) {
            lastWifiInfo = null;
            lastWifiNetwork = null;
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
        ConnectivityManager.NetworkCallback callback;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasFineLocationPermission()) {
            callback = new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onAvailable(Network network) {
                    // Wait for onCapabilitiesChanged before reading the new default route.
                }

                @Override public void onLost(Network network) {
                    if (network.equals(lastDefaultNetwork)) {
                        lastDefaultNetwork = null;
                        lastDefaultCapabilities = null;
                    }
                    if (network.equals(lastWifiNetwork)) {
                        lastWifiInfo = null;
                        lastWifiNetwork = null;
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
                    }
                    if (network.equals(lastWifiNetwork)) {
                        lastWifiInfo = null;
                        lastWifiNetwork = null;
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
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    lastWifiInfo = null;
                    lastWifiNetwork = null;
                } else if (lastWifiNetwork != null && !lastWifiNetwork.equals(active)) {
                    // A different Wi-Fi network is active; don't show the previous SSID.
                    lastWifiInfo = null;
                    lastWifiNetwork = active;
                }
                // Do not cache WifiInfo from getNetworkCapabilities(): on Android 12+
                // this snapshot can redact location-sensitive SSID information.
            } else {
                lastDefaultNetwork = null;
                lastDefaultCapabilities = null;
                lastWifiInfo = null;
                lastWifiNetwork = null;
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Unable to refresh default network", e);
        }
    }

    private void cacheWifiInfo(Network network, NetworkCapabilities capabilities) {
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;

        if (lastWifiNetwork != null && !lastWifiNetwork.equals(network)) {
            // A new Wi-Fi network must not inherit the previous network's cached name.
            lastWifiInfo = null;
        }
        lastWifiNetwork = network;

        if (!hasFineLocationPermission()) return;

        Object transportInfo = capabilities.getTransportInfo();
        if (transportInfo instanceof WifiInfo) {
            WifiInfo candidate = (WifiInfo) transportInfo;
            if (isUsableSsid(candidate.getSSID())) {
                lastWifiInfo = candidate;
            }
            // Unknown/redacted callback data must never overwrite a valid cached SSID.
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
        WifiInfo info = lastWifiInfo;
        if (info != null && !WifiManager.UNKNOWN_SSID.equals(info.getSSID())) return info;
        if (wifiManager != null) {
            try {
                info = wifiManager.getConnectionInfo();
                if (info != null) {
                    if (hasFineLocationPermission() && isUsableSsid(info.getSSID())) {
                        lastWifiInfo = info;
                    }
                    return info;
                }
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
        if (!hasFineLocationPermission()) {
            return getString(R.string.connection_wifi_fallback);
        }
        WifiInfo info = getCurrentWifiInfo();
        if (info == null) return getString(R.string.connection_wifi_fallback);
        String ssid;
        try { ssid = info.getSSID(); }
        catch (RuntimeException e) { return getString(R.string.connection_wifi_fallback); }
        if (!isUsableSsid(ssid)) {
            return getString(R.string.connection_wifi_fallback);
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
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
            operator = operator.trim().replaceAll("(?i)\\s+(5G\\+|4G|5G|LTE|NR)+$", "");
        }

        String generation = getNetworkGeneration(manager);
        return generation.isEmpty() ? operator : operator + " " + generation;
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
