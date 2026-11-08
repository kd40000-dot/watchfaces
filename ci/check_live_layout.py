#!/usr/bin/env python3
"""Conservative geometric clearance checks for the 466px live face.

Uses the envelope of a 94x28 rotated label containing the 26px provider icon
and 24px text. Fonts, custom providers and device rendering can vary, so this
is a regression guard, not an on-device screenshot assertion.
"""
import math
import re
from pathlib import Path

source = Path("legacy/src/main/java/dev/kd40000/arcscompasslive/ArcsCompassService.kt").read_text()

def constant(name):
    return float(re.search(rf"private val {name} = ([0-9.]+)f",source).group(1))
arc = constant("progressArcRadius")
compass = constant("compassLabelRadius")
label = constant("outerLabelRadius")
badge_y = constant("centerBadgeY")
badge_r = constant("centerBadgeRadius")
assert 180 <= arc <= 195
assert label < arc < compass
# Arc band uses a 10px pen, cardinal letters 22px high.
compass_gap = (compass - 12) - (arc + 5)
# Largest possible outer label envelope over its full rotated width.
label_outer = math.hypot(label + 14, 94/2)
arc_to_label_gap = (arc - 5) - label_outer

def rect_distance_to_circle(bearing, center_x, center_y):
    angle = math.radians(bearing)
    x = 233 + label * math.sin(angle)
    y = 233 - label * math.cos(angle)
    rotation = math.radians(bearing if bearing < 90 or bearing > 270 else bearing - 180)
    dx, dy = center_x - x, center_y - y
    local_x = math.cos(rotation)*dx + math.sin(rotation)*dy
    local_y = -math.sin(rotation)*dx + math.cos(rotation)*dy
    return math.hypot(max(abs(local_x)-47, 0), max(abs(local_y)-14, 0)) - badge_r

lower_clearance = min(
    rect_distance_to_circle(bearing, circle_x, badge_y)
    for bearing in (137, 223)
    for circle_x in (165, 301)
)
for name,gap in [
    ("compass to arcs",compass_gap),
    ("arcs to outer label envelopes",arc_to_label_gap),
    ("lower labels to center badges",lower_clearance)
]:
    print(f"{name}: {gap:.1f}px clearance")
    assert gap >= 4.0, f"{name} collide ({gap:.1f}px)"

assert "p.textSize = 24f" in source, "Missing enlarged labels"
assert "val iconSize = 26f" in source, "Missing enlarged icons"
assert "TextUtils.ellipsize" in source, "Missing longest-text bounds"
print("Core dial spacing passed; verify real typography on hardware.")
