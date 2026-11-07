# Arcs Dracula Compass — OnePlus Watch 2 / Wear OS 5

Reconstruction of the OnePlus **Arcs** dial using a Dracula color palette and additional compass bezel. The original APK is proprietary OPlus MCU/JavaScript watchface technology, so this is a reimplementation rather than a patch of the OnePlus app.

## Two installable builds

| Module | APK | Heading sensor | Compatibility |
|---|---|---|---|
| `legacy` | `legacy-debug.apk` | **Live** rotation-vector (fallback: accelerometer+magnetometer) rotating compass bezel | **Experimental** on OnePlus Watch 2 upgraded to Wear OS 5; newer Wear OS installs can reject legacy faces |
| `watchface` | `watchface-debug.apk` | **Fixed** N/E/S/W ring, no compass heading | Compliant Watch Face Format 2; Wear OS 5 |

**Choose `legacy-debug.apk` first** to try the live compass. If the system refuses to activate it as a watch face, the WFF build is the supported fallback but **does not offer a functional compass**.

## Live compass (`legacy`)

- Reads `Sensor.TYPE_ROTATION_VECTOR` from the watch, falling back to `TYPE_GEOMAGNETIC_ROTATION_VECTOR`, or accelerometer plus magnetometer.
- Rotates the external 360-degree bezel markings and cardinal directions with heading.
- Uses compass-heading smoothing and only invalidates when direction changes, avoiding 60 fps refresh.
- Registers compass sensors only while visible and active; stops on ambient, hidden, and service destruction.
- Real time, date, two circular central complications, and four additional editable corner complications. Center icons use larger Dracula green/pink accents, and center disks are near-black.
- Four Dracula-colored progress arcs that respond to their own slot's RANGED_VALUE/GOAL_PROGRESS provider. Short text containing an explicit percentage is also supported. Other short-text categories are displayed with unfilled tracks rather than fake progress. Watch battery without an explicit '%' can be resolved against the actual watch battery; a duplicate step count matching the center steps can use an 8000-step reference goal.
- OLED black AOD showing only subdued time.
- Standalone package `dev.kd40000.arcscompasslive` does not replace stock Arcs.

### IMPORTANT compatibility note

The AndroidX Watch Face Service approach is **not Watch Face Format**. Android now generally requires WFF for installing new watch faces. Official documentation on OTA upgraded watches suggests that pre-existing legacy faces may continue to work, but does not guarantee that a *newly sideloaded* legacy APK will be selectable. This implementation compiles and packages successfully on GitHub Actions, but has not been tested on the physical OnePlus Watch 2. It may fail to appear in the picker or be blocked on Wear OS 5.

The compass is a sensor heading, not GPS bearing or true-north corrected heading. Magnetic interference and wrist orientation affect accuracy; don't use this prototype for navigation. The original OPlus complication artwork/icons and vendor data sources are not reproduced 1:1.

### v0.4.0 live version
- Outer label **position and tangent geometry unchanged** from v0.3.0.
- Compass ring retains its full prominence and rotating heading.
- OLED-black background and darker center circles.
- Colored, larger provider icons in the center. Outer icons use matching Dracula colors and context-appropriate vector fallbacks.
- Readable consistent text sizes, constrained by measured length.
- Default providers on **new installations** are: center steps and battery; outside upper left notifications, upper right sunrise/sunset, lower left world clock, lower right date. These provide six distinct information sources; some new defaults are not progress metrics, so their arcs remain unfilled until assigned a ranged/goal data provider. Existing complication selections are persisted by the OS and are not overwritten by updating the app.
- The original proprietary OnePlus assets and exact stock face rendering remain separate.

## WFF v2 fallback (`watchface`)

- Six configurable complications.
- Live clock, date, four dynamic progress arcs, Dracula theme, OLED-black AOD.
- Static directional bezel only; WFF v2 has no magnetometer/azimuth data source and no executable watch-face code.
- Official Google WFF v2 validator run automatically in CI.

## Build & install

Both APKs are built by [GitHub Actions](https://github.com/kd40000-dot/watchfaces/actions). Download the latest successful run's artifacts:
- `Arcs-Dracula-Live-Compass-WearOS5-experimental`
- `Arcs-Dracula-Compass-WearOS5-debug`

To install with ADB:
```bash
adb install -r legacy-debug.apk
# If the live watch face is not selectable, install the WFF fallback:
adb install -r watchface-debug.apk
```

Select **Arcs Dracula Live Compass** or **Arcs Dracula Compass** in the watch's face picker. If legacy selection is rejected, that is a platform limitation, not proof that the magnetic sensor is missing.

## Theme
Active OLED background `#000000` (center disks `#1F202A`), foreground `#F8F8F2`, track `#44475A`, muted `#6272A4`, orange `#FFB86C`, green `#50FA7B`, cyan `#8BE9FD`, purple `#BD93F9`, pink `#FF79C6`, red `#FF5555`.
