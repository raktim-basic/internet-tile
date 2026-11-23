package app.internettile;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class InternetTileService extends TileService {

    private static final String INTERNET_PANEL =
            "android.settings.panel.action.INTERNET_CONNECTIVITY";

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.tile_label));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_internet));
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        if (!launch(new Intent(INTERNET_PANEL))) {
            // Fallback: normal Wi-Fi settings page
            launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private boolean launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pi = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
