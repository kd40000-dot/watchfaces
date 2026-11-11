# OEM Arcs low-power experiment (separate branch)

This branch investigates the *original* OnePlus Watch 2 `WatchFaceArcs.apk` rather than replacing the working AndroidX Dracula Compass watch face.

## Verified findings

- The OEM package contains **466×466 MCU scripts/resources**, with separate active and always-on draw paths.
- Its six complication slots are rendered by native `EditWidget()` calls, unlike our custom AndroidX face.
- `assets/mcu-466x466/res/drawable/pic.bin` is a sprite table with 13 entries; entry 10 is a 480×468 RGB565 background graphic. It can be recolored and edited without converting it to an AndroidX canvas renderer.
- The stock APK is signed by **OPlus WearTeam**. Changing the APK and applying our signature cannot preserve the OEM signing identity or guarantee BES2700 execution.
- The inspected JavaScript has no confirmed magnetic compass/heading API. A static N/E/S/W bezel is **not** a working compass.

## First isolated trial

A local test APK was built from the user's original APK, without committing any original vendor binary, font, or proprietary asset to GitHub.

The trial:
1. Preserves the native `main.js`, `aod/main.js`, `setting.bin`, `widget.bin`, six complication providers and OPlus Java classes.
2. Recolors the original RGB565 graphic and color palette to Dracula and overlays a **fixed decorative** compass bezel.
3. Rewrites only the manifest application ID, own permission and startup provider authority to `dev.kd40000.wearos.oemface.arcs`, so it cannot replace `com.oplus.wearos.watchface.arcs`.
4. Re-signs with an independent, temporary RSA SHA-256 APK Signature Scheme v2 test certificate.
5. Verifies ZIP integrity, cryptographic signing, and the APK content digest offline.

**Not yet verified:** installation, watch-face picker listing, OEM MCU renderer acceptance, sensor access, or battery improvements on the actual OnePlus Watch 2.

## On-watch test

Install the separately named trial APK (provided in the ChatGPT conversation) using GeminiMan or ADB:

```sh
adb install -r Arcs_OEM_Dracula_Experimental.apk
adb shell pm path dev.kd40000.wearos.oemface.arcs
```

Look for a second **Arcs** face in the picker. It may not appear or may be rejected by OEM identity checks. Do **not** uninstall or replace the stock watch face.

If rejected:

```sh
adb logcat -d -t 1500 | grep -Ei 'watchface|mcu|oemface|signature|permission|RTOS'
```

Uninstall just the trial:

```sh
adb shell pm uninstall dev.kd40000.wearos.oemface.arcs
```

If the OEM renderer accepts it, compare power usage against stock Arcs before claiming energy savings. A genuinely rotating compass is a separate research task and requires a proven heading API inside OnePlus's RTOS watch-face runtime.

## Repository and artifact hygiene

No OPlus APKs, proprietary binary resources, or font files belong in this repository. Keep the original APK and generated test APK private. The complete local patcher and independent signature verifier were provided as a conversation artifact, not published here.

**This trial is experimental.** A valid Android package signature only proves package integrity. It does not grant manufacturer low-power privileges.
