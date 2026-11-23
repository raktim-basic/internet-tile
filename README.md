# Internet Tile

A lightweight Android Quick Settings tile that opens the device's native Internet connectivity panel.

## Behaviour

- Tap: launches `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`.
- Long-press: Android launches `TilePreferencesActivity`, which opens the device's native Network & Internet settings page.
- Tile state is active when Wi-Fi or mobile data is enabled.
- The icon follows the active default transport and its signal level.
- If no transport is connected, an enabled Wi-Fi radio uses the lowest Wi-Fi level; otherwise enabled mobile data uses the lowest cellular level. If both radios are disabled, the tile uses the slashed Wi-Fi-off icon.
- The subtitle shows the connected Wi-Fi SSID or the mobile carrier plus a normalized radio generation (`5G`, `LTE`, `3G`, or `2G`).

## Live updates

While Quick Settings is listening, the service subscribes to:

- Default-network availability, capability and link-property changes.
- Wi-Fi enabled, connection and RSSI broadcasts.
- Airplane-mode changes.
- Cellular-network availability and capability changes, independently of the default route.
- Active-data-subscription changes.
- Telephony signal-strength and display-info callbacks on Android 12+.

Callbacks are registered in `onStartListening()` and released in `onStopListening()`. The tile also takes a fresh network snapshot whenever listening starts.

## Permissions

- `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are used for network state and transport information.
- `ACCESS_FINE_LOCATION` is requested from the tile's long-press preferences entry because Android redacts the connected Wi-Fi SSID without location permission (and the system Location switch enabled). If unavailable, the subtitle falls back to `Wi-Fi`.
- `READ_BASIC_PHONE_STATE` is declared for the carrier's data-network generation on Android 13+. Older Android versions request `READ_PHONE_STATE` from the same preferences entry; if unavailable, the tile falls back to the carrier name or `Mobile data`.

## Icons

All signal icons are bundled as local VectorDrawables. The service does not look up Motorola/SystemUI drawable resources at runtime. Wi-Fi signal vectors use a small vertical scale adjustment to correct their appearance in the QS tile; source path geometry is otherwise retained.

## Build

Open the project in Android Studio and build the `app` module. The project uses Java 17 and compile SDK 35.
