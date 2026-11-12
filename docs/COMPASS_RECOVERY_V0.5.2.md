# v0.5.2: restore reliably rotating compass

On-device report (12 November 2026): with v0.5.0 and v0.5.1 the north label remained fixed at the watch's 12-o'clock pointer even while rotating a full circle. This demonstrates a visible compass regression in the battery-optimization experiment.

## Corrective change

The last verified moving compass was v0.4.6. Its `ArcsCompassService` implemented:

- AndroidX interactive canvas `1000L` (one-second normal UI cadence)
- default AndroidX `shouldAnimate` policy (no `false` override)
- `Sensor.TYPE_ROTATION_VECTOR` as the preferred sensor
- `SENSOR_DELAY_UI` sample delivery and direct `invalidate()` for meaningful heading changes
- sensor registration while visible and interactive, and unregistering when hidden/ambient or destroyed

v0.5.2 **restores this entire working sensor/renderer block**, while retaining the other changes introduced later:
- Reused `DateTimeFormatter`, `Regex`, paths, progress arc drawing rectangles and arrays
- Dynamic icons and six complication providers
- Unchanged compass/arc/complication appearance
- Black AOD and no sensors registered in ambient mode

**Important:** We intentionally reverted the 60-second interactive frame delay. The previous battery-saving claim for v0.5.0 is therefore not fully retained. Measured energy savings are **unknown**. This is a recovery build prioritizing a functioning compass, not an optimized-BES2700-equivalent build.

## On-device verification

Install v0.5.2 over v0.5.1; signing key and package ID are unchanged.

1. Wake the face while wearing the watch.
2. Slowly rotate 360 degrees, checking the red N moves **relative to the fixed red pointer at 12 o'clock**, while the numeric heading changes.
3. Leave the face awake for 60 seconds, verify clock updates, then enter ambient and confirm sensors stop as intended.
4. If still stationary, capture `adb logcat -d -v time -t 1500` and `adb shell dumpsys sensorservice`. Compare with a v0.4.6 reinstall on the same firmware.

Do not conclude working operation merely from GitHub Actions compilation.
