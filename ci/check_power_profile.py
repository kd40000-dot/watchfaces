#!/usr/bin/env python3
"""Fail CI if the wearable live compass renderer regresses to busy polling.

This is a static lifecycle/power safeguard; real power savings need on-watch
measurements and the OnePlus RTOS warning remains a vendor policy limitation.
"""
from pathlib import Path
import re
s=Path("legacy/src/main/java/dev/kd40000/arcscompasslive/ArcsCompassService.kt").read_text()
requirements={
    "non-animated renderer": r"override\s+fun\s+shouldAnimate\(\):\s+Boolean\s*=\s*false",
    "minute rather than 1s automatic frames": r"CanvasType\.HARDWARE,\s*60000L",
    "no UI-speed sensor listener": r"SensorManager\.SENSOR_DELAY_NORMAL",
    "prefer no-gyro geomagnetic source": r"getDefaultSensor\(Sensor\.TYPE_GEOMAGNETIC_ROTATION_VECTOR\)",
    "ambient or invisible sensor gate": r"state\.isAmbient\.value\s*==\s*true\s*\|\|\s*state\.isVisible\.value\s*!=\s*true",
    "on-hide sensor teardown": r"if\s*\(on\)\s*startCompass\(\)\s*else\s*stopCompass\(\)",
    "minimum redraw gap": r"minCompassRedrawIntervalMs\s*=\s*500L",
    "redraw throttle": r"now\s*-\s*lastCompassRedrawAtMs\s*>=\s*minCompassRedrawIntervalMs",
    "cached arc shapes": r"private\s+val\s+progressRect\s*=\s*RectF\(",
    "OLED black ambient": r"canvas\.drawColor\(if\s*\(ambient\)\s*Color\.BLACK\s*else\s*bg\)",
}
for name,regex in requirements.items():
    assert re.search(regex,s,re.MULTILINE),f"Missing power safeguard: {name}"
    print("PASS:",name)
assert "SensorManager.SENSOR_DELAY_UI" not in s, "Sensor delay regressed"
assert "1000L" not in s, "1Hz busy redraw regressed"
assert s.count("sensorManager.unregisterListener(this)")>=1,"Sensors aren't released"
print("Battery lifecycle static checks passed; actual draw rate and consumption require device testing.")
