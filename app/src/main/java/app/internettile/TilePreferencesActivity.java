package app.internettile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point used by Android when the user long-presses this Quick Settings tile.
 * Requests the permissions needed for meaningful network labels, then opens the
 * device's own Network & Internet settings page.
 */
public class TilePreferencesActivity extends Activity {
    private static final int REQUEST_NETWORK_LABEL_PERMISSIONS = 41;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestMissingPermissionsOrOpenSettings();
    }

    private void requestMissingPermissionsOrOpenSettings() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        // READ_BASIC_PHONE_STATE is a normal permission on Android 13+.
        // Older releases use READ_PHONE_STATE for the network-generation label.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                && checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.READ_PHONE_STATE);
        }

        if (missing.isEmpty()) {
            openNetworkSettings();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQUEST_NETWORK_LABEL_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NETWORK_LABEL_PERMISSIONS) {
            // Settings navigation is the primary purpose of this activity. A denied
            // optional label permission only means the tile will use its generic fallback.
            openNetworkSettings();
        }
    }

    private void openNetworkSettings() {
        Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        try {
            startActivity(intent);
        } catch (RuntimeException firstFailure) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) { }
        } finally {
            finish();
        }
    }
}
