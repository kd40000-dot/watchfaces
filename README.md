# Arcs Dracula Compass — Wear OS 5

A Watch Face Format v2 reconstruction of the **OnePlus Watch 2 Arcs** layout in Dracula colors, with an additional outer compass-themed bezel.

## Face
- 466 × 466 logical coordinate system, matching the original Arcs assets.
- Digital clock, weekday/month/date, four quadrant progress arcs.
- **Six editable complications**: two circular center slots, four quadrant slots.
- Four progress arcs: each follows its own slot's RANGED_VALUE / GOAL_PROGRESS provider when those types are selected. With SHORT_TEXT providers, the arcs fall back to builtin steps, battery or heart-rate metrics. Default lower-left duplicates steps.
- Four cardinal labels and 15° bezel markings (compass-like but currently **north stays at 12 o'clock**).
- Dedicated OLED ambient rendering: black background, thin muted time, all six complication visuals and colored ornamentation hidden.
- Theme: Dracula background `#282A36`, foreground `#F8F8F2`, muted `#6272A4`, orange `#FFB86C`, green `#50FA7B`, cyan `#8BE9FD`, purple `#BD93F9`, pink `#FF79C6`, red `#FF5555`.

## IMPORTANT: No actual heading
The magnetic compass **cannot be implemented directly inside WFF v2**. WFF offers accelerometer angle but not a magnetic azimuth / compass heading source. It also requires the watch-face APK to contain *no executable code*. A sensor-reading companion application and a separate supported mechanism to update the bezel are needed; no such reliable real-time interface has been validated on OnePlus Watch 2. The bezel is fixed and must **not** be used for navigation.

All four arcs use their assigned provider's numeric progress if it supplies RANGED_VALUE or GOAL_PROGRESS; for SHORT_TEXT and images with no progress, the colorful arc falls back to a built-in metric or to its muted track. Six complication slots remain selectable; readings depend on the providers installed on the watch.

## Build
Pushes run the official Google WFF2 XML validator before Gradle and then build an APK on GitHub Actions.

1. Open [Actions](https://github.com/kd40000-dot/watchfaces/actions).
2. Open the most recent successful "Validate and build Arcs Dracula for Wear OS 5".
3. Download artifact `Arcs-Dracula-Compass-WearOS5-debug`.
4. Extract `watchface-debug.apk` and install through ADB on the watch.

Note: build & WFF XML validation on GitHub does not substitute for visual and sensor verification on physical OnePlus Watch 2 hardware.
