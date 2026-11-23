package app.internettile;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final int LEVEL_COUNT = 5;

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private TelephonyManager telephonyManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateTile();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        connectivityManager = getSystemService(ConnectivityManager.class);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        telephonyManager = getSystemService(TelephonyManager.class);
    }

    @Override public void onStartListening() {
        super.onStartListening();
        registerListeners();
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

    private void registerListeners() {
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
                @Override public void onAvailable(Network network) { updateTile(); }
                @Override public void onLost(Network network) { updateTile(); }
                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) { updateTile(); }
            };
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Default network callback unavailable", e);
                networkCallback = null;
            }
        }
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
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean anyRadioEnabled = isWifiEnabled() || isMobileDataEnabled();
        Transport transport = getActiveTransport();
        tile.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getSubtitle(transport));
        }
        tile.setIcon(getLocalSignalIcon(transport));
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
        if (telephonyManager != null) {
            try { return telephonyManager.isDataEnabled(); }
            catch (RuntimeException e) {
                Log.d(TAG, "Telephony data-state API unavailable; trying Settings fallback", e);
            }
        }
        // Read-only fallback used by Android builds which restrict isDataEnabled() to
        // phone-state/carrier privileges. OEMs may expose per-SIM variants instead.
        try {
            return Settings.Global.getInt(getContentResolver(), "mobile_data", 0) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read mobile-data state", e);
            return false;
        }
    }

    private Transport getActiveTransport() {
        if (connectivityManager == null) return Transport.NONE;
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = active == null
                ? null : connectivityManager.getNetworkCapabilities(active);
        if (caps == null) return Transport.NONE;
        // The active default network determines the icon. Do not infer the icon from
        // radio-enabled state: both radios can be enabled while only one is in use.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Transport.WIFI;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return Transport.CELLULAR;
        return Transport.NONE;
    }

    private int getWifiLevel() {
        WifiInfo info = null;
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities caps = active == null
                    ? null : connectivityManager.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                Object transportInfo = caps.getTransportInfo();
                if (transportInfo instanceof WifiInfo) info = (WifiInfo) transportInfo;
            }
        }
        if (info == null && wifiManager != null) {
            try { info = wifiManager.getConnectionInfo(); }
            catch (RuntimeException e) { Log.d(TAG, "WifiInfo unavailable", e); }
        }
        if (info == null) return 0;
        int rssi = info.getRssi();
        if (rssi <= -127) return 0;
        return clamp(WifiManager.calculateSignalLevel(rssi, LEVEL_COUNT));
    }

    private int getCellularLevel() {
        // NetworkCapabilities is attempted first because it does not require the
        // dangerous READ_PHONE_STATE runtime permission.
        if (connectivityManager != null) {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities caps = active == null
                    ? null : connectivityManager.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                int dbm = caps.getSignalStrength();
                int mapped = mapDbm(dbm);
                if (mapped >= 0) return mapped;
            }
        }
        // Best effort fallback. Some devices restrict this API unless READ_PHONE_STATE
        // has been granted; that case deliberately resolves to the lowest level.
        if (telephonyManager != null) {
            try {
                SignalStrength signal = telephonyManager.getSignalStrength();
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

    private Icon getLocalSignalIcon(Transport transport) {
        final int resource;
        if (transport == Transport.CELLULAR) {
            resource = cellularIconForLevel(getCellularLevel());
        } else if (transport == Transport.WIFI) {
            resource = wifiIconForLevel(getWifiLevel());
        } else {
            // Required idle/off fallback: show the Wi-Fi glyph, not a generic icon.
            resource = R.drawable.ic_wifi_4;
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
        if (transport == Transport.WIFI) return getString(R.string.connection_wifi);
        if (transport == Transport.CELLULAR) return getString(R.string.connection_mobile);
        return getString(R.string.connection_idle);
    }

    private enum Transport { NONE, WIFI, CELLULAR }
}
