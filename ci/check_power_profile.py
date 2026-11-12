#!/usr/bin/env python3
"""Regression checks for the last verified working live compass plus retained AOD safety.

The original 0.5.0 power experiment stopped compass movement on the OnePlus
Watch 2. Prioritize proven compass functionality. We preserve allocation caches
and suspend sensors in ambient, but the active 1s frame rate is intentionally
back until an adaptive mode has been tested on actual hardware.
"""
from pathlib import Path
import re

s=Path("legacy/src/main/java/dev/kd40000/arcscompasslive/ArcsCompassService.kt").read_text()
checks={
    "verified working interactive frame cadence": r"CanvasType\.HARDWARE,\s*1000L",
    "normal AndroidX interactive scheduling": r"Renderer\.CanvasRenderer\(",
    "verified rotation vector first": r"getDefaultSensor\(Sensor\.TYPE_ROTATION_VECTOR\)",
    "verified UI-speed sensor update": r"SENSOR_DELAY_UI",
    "compass invalidation on movement": r"heading = azimuth[\s\S]*?invalidate\(\)",
    "stop sensors during ambient/invisibility": r"if\s*\(on\)\s*startCompass\(\)\s*else\s*stopCompass\(\)",
    "stop sensors on destroy": r"override fun onDestroy\([\s\S]*?stopCompass\(\)",
    "cached arc graphics": r"private\s+val\s+progressRect\s*=\s*RectF\(",
    "true black ambient": r"canvas\.drawColor\(if\s*\(ambient\)\s*Color\.BLACK\s*else\s*bg\)",
}
for label,regex in checks.items():
    assert re.search(regex,s,re.MULTILINE),f"FAIL {label}"
    print("PASS:",label)
assert "override fun shouldAnimate(): Boolean = false" not in s, (
    "Do not disable AndroidX scheduled interactive draws without watch test"
)
assert "60000L" not in s, "Minute-only automatic frame rate broke compass on device"
assert "SensorManager.SENSOR_DELAY_NORMAL" not in s, "Sensor delay override broke compass"
print("PASS: confirmed working sensor + rendering behavior restored")
