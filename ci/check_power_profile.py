#!/usr/bin/env python3
"""Safety regression check for the exact v0.4.6 rollback.

The user reported that experimental power changes broke the compass.
Do not mix those modifications into the recovery release. This computes
the actual historical Git blob SHA-1 of the original source; the hash
must match the known-working pre-optimization commit 63b6d2e76e.
"""
from pathlib import Path
import hashlib
import re

source = Path("legacy/src/main/java/dev/kd40000/arcscompasslive/ArcsCompassService.kt").read_bytes()
blob = b"blob " + str(len(source)).encode() + b"\0" + source
sha = hashlib.sha1(blob).hexdigest()
baseline = "1e78448caf2410980909601717f6aa99a808f382"
assert sha == baseline, (
    f"Renderer differs from original v0.4.6: {sha} != {baseline}. "
    "Do not ship an unverified modified compass under the rollback label."
)
s = source.decode("utf-8")
assert re.search(r"CanvasType\.HARDWARE,\s*1000L", s)
assert "SENSOR_DELAY_UI" in s
assert "shouldAnimate(): Boolean = false" not in s
assert "60000L" not in s
assert "SensorManager.SENSOR_DELAY_NORMAL" not in s
print(f"PASS: exact pre-optimization renderer, Git blob {sha}")
print("PASS: known renderer/sensor code unchanged")
