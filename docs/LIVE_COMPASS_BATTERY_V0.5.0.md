# Arcs Dracula Live Compass v0.5.0 — low-power experiment

The v0.5.0 APK improves runtime efficiency of **the existing, installable AndroidX watchface** `dev.kd40000.arcscompasslive`. It does not attempt to access OPlus's privileged BES2700 MCU watchface runtime.

## Changes

- Calls `Renderer.shouldAnimate() = false`: no continuous interactive frame loop. AndroidX still schedules the system minute clock tick (and complication/provider changes can invalidate the face). The sensor listener explicitly invalidates when it has a meaningfully new heading.
- Changes CanvasRenderer's background frame interval from **1,000 ms** to **60,000 ms** as a guard against redundant frames.
- Prefers Android's low-power **TYPE_GEOMAGNETIC_ROTATION_VECTOR** (no gyro) if available; retains the ordinary rotation vector and accelerometer+magnetometer fallback. The lower-power sensor may be noisier, particularly indoors.
- Reduces the active compass sensor delivery request from `SENSOR_DELAY_UI` to `SENSOR_DELAY_NORMAL` (roughly 5 samples/sec as a request, not a hardware guarantee).
- Caps heading-triggered redraws at **2/sec**, and skips changes under ~2°.
- Registers sensor listeners only when the face is **both visible and not ambient**, clears first-reading state on re-entry, unregisters on exit (including partially registered fallbacks), and releases on destroy.
- Moves immutable date formatting, progress arc geometries, and dial layout descriptors out of the paint loop.
- Leaves the Dracula styling, six dynamic complication icons, date, time, compass rim, AOD appearance, and complication editor intact.

## What this does **not** do

It does not bypass the OnePlus vendor watch-face classification warning, prove the BES2700 renders third-party faces, or guarantee stock Arcs battery life. Actual power draw depends on firmware, activity, display use, enabled providers and sensors. It also does not continuously poll in ambient mode; the compass rim is suppressed in AOD to preserve power.

## Reproducible battery comparison

1. Charge the OnePlus Watch 2 fully. Test **stock Arcs** first for 12-24 hours and then **v0.5.0** over an equally long period. Repeat in reverse order on a second pair of days to reduce the effect of differing activity.
2. Keep AOD/raise-to-wake/brightness, notifications, Bluetooth/Wi-Fi connection, and the six complication data sources as similar as possible.
3. Record battery start/end percentages and elapsed hours, then compare average percentage points per hour: `(start% - end%) / hours`. This is not an actual mWh measurement and cannot isolate chipset energy on its own.
4. Test again with **AOD enabled**, separately. Expect system-level dual-engine limitations to matter, even when the face draws fewer frames.
5. Confirm the compass still rotates when the watch wakes, the minutes advance at the correct time, complications update with new data, the sensor stops in ambient mode, and provider choices persist.
6. If the compass is unusually noisy/slow because geomagnetic sensing is preferred, log whether `TYPE_GEOMAGNETIC_ROTATION_VECTOR` is exposed: `adb shell dumpsys sensorservice` and use the previous v0.4.6 APK for comparison.

CI includes `ci/check_power_profile.py` to catch accidental regressions to per-second redraws or high-speed sensing.

Sources:
- https://developer.android.com/reference/androidx/wear/watchface/Renderer
- https://developer.android.com/reference/android/hardware/Sensor#TYPE_GEOMAGNETIC_ROTATION_VECTOR
- https://developer.android.com/develop/sensors-and-location/sensors/sensors_position

The separate OEM experiment remains on `experiment/oem-rtos-arcs` without modification.
