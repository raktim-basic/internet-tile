package app.internettile;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

public class InternetTileService extends TileService {

    private static final String TAG = "InternetTile";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final int SIGNAL_LEVEL_COUNT = 5;

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private TelephonyManager telephonyManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateTile();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        connectivityManager = getSystemService(ConnectivityManager.class);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        telephonyManager = getSystemService(TelephonyManager.class);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        registerStateListeners();
        updateTile();
    }

    @Override
    public void onStopListening() {
        unregisterStateListeners();
        super.onStopListening();
    }

    @Override
    public void onClick() {
        // Launch Android's own Internet panel. This app does not implement a panel.
        Intent intent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to open the system Internet panel", e);
        }
    }

    private void registerStateListeners() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        }

        if (connectivityManager != null && networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateTile();
                }

                @Override
                public void onLost(Network network) {
                    updateTile();
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    updateTile();
                }
            };
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not register default-network callback", e);
                networkCallback = null;
            }
        }
    }

    private void unregisterStateListeners() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver was already unregistered by the framework.
            }
            receiverRegistered = false;
        }

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // The callback may already have been removed by the framework.
            }
            networkCallback = null;
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean wifiEnabled = isWifiEnabled();
        boolean mobileDataEnabled = isMobileDataEnabled();
        boolean anyRadioEnabled = wifiEnabled || mobileDataEnabled;

        ActiveTransport transport = getActiveTransport();
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getConnectionSubtitle(transport));
        tile.setIcon(getSystemSignalIcon(transport));
        tile.setState(anyRadioEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private boolean isWifiEnabled() {
        if (wifiManager == null) return false;
        try {
            return wifiManager.isWifiEnabled();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read Wi-Fi enabled state", e);
            return false;
        }
    }

    private boolean isMobileDataEnabled() {
        if (telephonyManager == null) return false;
        try {
            return telephonyManager.isDataEnabled();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read mobile-data enabled state", e);
            return false;
        }
    }

    private ActiveTransport getActiveTransport() {
        if (connectivityManager == null) return ActiveTransport.NONE;

        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null
                ? null : connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null) return ActiveTransport.NONE;

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return ActiveTransport.WIFI;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return ActiveTransport.CELLULAR;
        }
        return ActiveTransport.NONE;
    }

    private int getWifiSignalLevel() {
        WifiInfo info = null;

        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null
                    ? null : connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Object transportInfo = capabilities.getTransportInfo();
                if (transportInfo instanceof WifiInfo) {
                    info = (WifiInfo) transportInfo;
                }
            }
        }

        if (info == null && wifiManager != null) {
            try {
                info = wifiManager.getConnectionInfo();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read Wi-Fi signal", e);
            }
        }

        if (info == null) return 0;
        int rssi = info.getRssi();
        if (rssi <= -127) return 0;
        int level = WifiManager.calculateSignalLevel(rssi, SIGNAL_LEVEL_COUNT);
        return clampSignalLevel(level);
    }

    private int getCellularSignalLevel() {
        if (telephonyManager != null) {
            try {
                SignalStrength signalStrength = telephonyManager.getSignalStrength();
                if (signalStrength != null) {
                    return clampSignalLevel(signalStrength.getLevel());
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read cellular signal strength", e);
            }
        }

        // Some vendors expose the current cellular level through NetworkCapabilities.
        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null
                    ? null : connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                int dbm = capabilities.getSignalStrength();
                if (dbm != Integer.MIN_VALUE && dbm != -1) {
                    if (dbm >= -80) return 4;
                    if (dbm >= -90) return 3;
                    if (dbm >= -100) return 2;
                    if (dbm >= -110) return 1;
                    return 0;
                }
            }
        }
        return 0;
    }

    private int clampSignalLevel(int level) {
        return Math.max(0, Math.min(SIGNAL_LEVEL_COUNT - 1, level));
    }

    private Icon getSystemSignalIcon(ActiveTransport transport) {
        final String resourcePrefix;
        final int level;

        if (transport == ActiveTransport.CELLULAR) {
            resourcePrefix = "stat_sys_signal_";
            level = getCellularSignalLevel();
        } else {
            // Wi-Fi is also the intentional fallback when neither transport is active.
            resourcePrefix = "stat_sys_wifi_signal_";
            level = transport == ActiveTransport.WIFI ? getWifiSignalLevel() : 0;
        }

        // These are Android SystemUI's own Android 12-15 status-bar signal drawables.
        // Using Icon's package/resource constructor keeps the glyph owned by SystemUI.
        int systemResourceId = findSystemUiDrawable(resourcePrefix + level + "_fully");
        if (systemResourceId == 0) {
            systemResourceId = findSystemUiDrawable(resourcePrefix + level);
        }
        if (systemResourceId != 0) {
            return Icon.createWithResource(SYSTEM_UI_PACKAGE, systemResourceId);
        }

        // Resource names can differ on OEM SystemUI builds. Keep the tile usable rather
        // than showing a fabricated or malformed signal glyph.
        Log.w(TAG, "SystemUI signal drawable not found: " + resourcePrefix + level);
        return Icon.createWithResource(this, R.drawable.ic_internet);
    }

    private int findSystemUiDrawable(String name) {
        try {
            Resources resources = getPackageManager()
                    .getResourcesForApplication(SYSTEM_UI_PACKAGE);
            return resources.getIdentifier(name, "drawable", SYSTEM_UI_PACKAGE);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Unable to access SystemUI drawable resources", e);
            return 0;
        }
    }

    private String getConnectionSubtitle(ActiveTransport transport) {
        if (transport == ActiveTransport.WIFI) {
            return getString(R.string.connection_wifi);
        }
        if (transport == ActiveTransport.CELLULAR) {
            return getString(R.string.connection_mobile);
        }
        return getString(R.string.connection_idle);
    }

    private enum ActiveTransport {
        NONE,
        WIFI,
        CELLULAR
    }
}
