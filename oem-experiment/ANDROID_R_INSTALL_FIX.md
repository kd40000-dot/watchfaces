# OEM Arcs Android R+ install fix — 2026-11-12

The first independently signed OEM test package was rejected by Wear OS with installer error `-124`:

`Targeting R+ (version 30 and above) requires the resources.arsc of installed APKs to be stored uncompressed and aligned on a 4-byte boundary`

## Root cause and corrected package
The original trial's `resources.arsc` entry was `ZIP_STORED` (uncompressed), but its raw data began at offset **3,138,461** (mod 4 = 1). Repacking the APK removed the original ZIP alignment padding.

The replacement APK uses a ZIP extra-field padding record and has the raw resource data at offset **3,138,468** (mod 4 = 0), still `ZIP_STORED`.

Repacking invalidates APK signatures, so the corrected test APK was re-signed with its **own new development test key** using APK Signature Scheme v2. The corrected package passed:
- ZIP integrity check.
- Cryptographic v2 signature verification and APK content digest.
- Comparison of all 1,085 uncompressed ZIP entry contents against the first test APK; no watch-face contents changed.

The rebuilt APK is provided **only** in the ChatGPT conversation (not published on GitHub). An updated local source-tools ZIP contains `fix_oem_apk_alignment.py`, the initial patcher, and `verify_v2.py`.

The signing key is **not included** in the public repository or tools bundle. Keep the regenerated key private for future update installs.

## Remaining risks

This addresses the Android package parser, **not** OEM host acceptance or the OPlus signature restriction. Installation and appearance in the watch-face picker have not yet been verified on the physical Watch 2. The fixed compass bezel is decorative; sensor heading support in the BES2700 runtime remains unproven.

The vendor-signed system watch face remains untouched. Only install or uninstall the package `dev.kd40000.wearos.oemface.arcs`.

## On-device result (2026-11-12)

The alignment-corrected OEM-derived APK **installs** on the OnePlus Watch 2, but selecting it still raises the firmware's **“Incompatible watch face / only available in Smart mode / reduce battery life by 1 to 2 days”** warning.

This establishes that an independently signed, separately named APK retaining the original MCU scripts/assets **does not gain the first-party classification through its resource format alone**. This is consistent with OnePlus's published dual-engine distinction between first-party and third-party watch faces. It does **not** establish the exact proprietary trust/allowlist rule; possible factors include vendor signing identity, system privilege, package identity, and host-managed enrollment.

### Do not mistake this for the earlier parser failure
- Android APK parser and alignment **pass**.
- OEM first-party/RTOS eligibility **does not pass**.
- The warning is a system-level classification and is not fixed by further RGB565 changes or ZIP-signing alignment.
- We should **not** recommend removing the warning by UI patching: suppressing it would not grant power savings.

### Safe next investigation

Compare the installed original and experimental packages' permissions/flags and obtain any native-host reason from Logcat, without changing the stock face:

```sh
adb shell dumpsys package com.oplus.wearos.watchface.arcs > stock_arcs_pkg.txt
adb shell dumpsys package dev.kd40000.wearos.oemface.arcs > trial_arcs_pkg.txt
adb logcat -c
# Trigger the warning on the watch, then:
adb logcat -d -v time -t 2000 > face_switch_log.txt
```

Inspect the records for `com.oplus.wearos.watchface.INVOKE_WATCH_FACE_API`, signature/privileged grants, `WatchFace`, `MCU`, `RTOS`, `lowpower`, `whitelist` and `incompatible`.

**Do not** overwrite the stock `com.oplus.wearos.watchface.arcs` package or modify vendor services just to remove a warning. The warning does not itself quantify battery-life change for this specific APK.
