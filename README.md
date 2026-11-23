# Internet Tile

A lightweight Android Quick Settings tile that opens the device's native Internet panel using `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`.

## Behavior

- The tile does not implement its own network panel; Android Settings owns the UI.
- Tile state is active when Wi-Fi **or** mobile data is enabled.
- The icon follows the active default network (Wi-Fi or cellular), with a Wi-Fi glyph when there is no active Wi-Fi/cellular transport.
- Wi-Fi and cellular glyphs expose five signal levels.
- Network and Wi-Fi state listeners refresh the tile while Quick Settings is listening.

## Build

```bash
gradle assembleDebug
```

Minimum Android version: Android 10 (API 29).
