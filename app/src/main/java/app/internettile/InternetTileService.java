package app.internettile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.telephony.TelephonyManager;
import android.graphics.drawable.Icon;

public class InternetTileService extends TileService {

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
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
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
        // Open the platform-owned Internet panel. No custom activity or panel is created.
        Intent intent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE
                );
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception ignored) {
            // Do not silently open a different settings screen: this tile specifically
            // represents the native Internet panel.
        }
    }

    private void registerStateListeners() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
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
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    updateTile();
                }
            };
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                networkCallback = null;
            }
        }
    }

    private void unregisterStateListeners() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // Callback may already have been removed by the framework.
            }
            networkCallback = null;
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean wifiEnabled = isWifiEnabled();
        boolean mobileDataEnabled = isMobileDataEnabled();
        boolean anyInternetRadioEnabled = wifiEnabled || mobileDataEnabled;

        int iconResource = getConnectionIcon();
        tile.setLabel(getString(R.string.tile_label));
        tile.setIcon(Icon.createWithResource(this, iconResource));
        tile.setState(anyInternetRadioEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getConnectionSubtitle());
        }
        tile.updateTile();
    }

    private boolean isWifiEnabled() {
        if (wifiManager == null) return false;
        try {
            return wifiManager.isWifiEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isMobileDataEnabled() {
        if (telephonyManager == null) return false;
        try {
            // ACCESS_NETWORK_STATE is sufficient for isDataEnabled().
            return telephonyManager.isDataEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    private int getConnectionIcon() {
        Network activeNetwork = connectivityManager == null
                ? null : connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = activeNetwork == null || connectivityManager == null
                ? null : connectivityManager.getNetworkCapabilities(activeNetwork);

        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return wifiIcon(getWifiLevel());
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return cellularIcon(getCellularLevel(capabilities));
            }
        }

        // No active Internet transport: retain the Wi-Fi glyph as the idle/default icon.
        return wifiIcon(0);
    }

    private int getWifiLevel() {
        if (wifiManager == null) return 0;
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return 0;
            return Math.max(0, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int getCellularLevel(NetworkCapabilities capabilities) {
        int dbm = capabilities.getSignalStrength();
        if (dbm == Integer.MIN_VALUE) return 0;
        if (dbm >= -80) return 4;
        if (dbm >= -90) return 3;
        if (dbm >= -100) return 2;
        if (dbm >= -110) return 1;
        return 0;
    }

    private String getConnectionSubtitle() {
        Network activeNetwork = connectivityManager == null
                ? null : connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = activeNetwork == null || connectivityManager == null
                ? null : connectivityManager.getNetworkCapabilities(activeNetwork);
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return getString(R.string.connection_wifi);
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return getString(R.string.connection_mobile);
            }
        }
        return getString(R.string.connection_idle);
    }

    private int wifiIcon(int level) {
        switch (level) {
            case 4: return R.drawable.ic_wifi_4;
            case 3: return R.drawable.ic_wifi_3;
            case 2: return R.drawable.ic_wifi_2;
            case 1: return R.drawable.ic_wifi_1;
            default: return R.drawable.ic_wifi_0;
        }
    }

    private int cellularIcon(int level) {
        switch (level) {
            case 4: return R.drawable.ic_cellular_4;
            case 3: return R.drawable.ic_cellular_3;
            case 2: return R.drawable.ic_cellular_2;
            case 1: return R.drawable.ic_cellular_1;
            default: return R.drawable.ic_cellular_0;
        }
    }
}
