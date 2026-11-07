# Arcs Dracula Compass — Wear OS 5

An independent **Watch Face Format 2** watch face inspired by OnePlus Arcs and colored with the Dracula palette. Package name `dev.kd40000.arcsdraculacompass` is separate from the original OPlus application.

## Current implemented baseline
- Wear OS 5 (API 34+) WFF v2 resource-only APK.
- Dracula background and colorful segmented arc *decoration*.
- Actual live digital time and current date.
- Two configurable `SHORT_TEXT` complications (steps and battery requested as defaults).
- Fixed N/E/S/W compass bezel.
- Simplified ambient appearance.
- GitHub Actions auto-build on each push. Download the debug APK from **Actions → Build Wear OS 5 APK → latest successful run → Artifacts**.

## Not implemented yet
- **Live compass heading:** WFF2 does not expose the magnetometer as a watch-face expression; this static ring is *not* a functional compass.
- Dynamic progress arc fills, full six original complications and exact visual reproduction of proprietary MCU OnePlus components.
- Actual on-device testing/visual validation. This is an initial functional foundation, **not** an exact clone.

## Build
```sh
gradle :watchface:assembleDebug
adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk
```

Use the watch's face picker to select **Arcs Dracula Compass**. Original assets aren't redistributed.

## Sources
- https://developer.android.com/training/wearables/wff
- https://developer.android.com/training/wearables/wff/setup
- https://developer.android.com/training/wearables/wff/complications
