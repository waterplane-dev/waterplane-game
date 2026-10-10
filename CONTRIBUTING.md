# Contributing

Waterplane: Harbor Run is a small demo game kept by one person in spare time. Issues and small pull requests are welcome. For anything big, open an issue first.

## What fits

- **Bug reports:** always welcome. Say which phone and Android version you used, and what happened just before.
- **Tuning:** if a difficulty feels unfair, say which one and where. The per-difficulty numbers are at the top of `GameView.java` (`SPEED`, `GAP`, `BURN`, `CRASH_V`, ...).
- **New levels:** welcome. Open an issue with the idea first.

Not wanted: ads, analytics, network access or accounts. The game has no internet permission and should keep it that way.

## How it is built

The whole game is three files: `MainActivity.java`, `GameView.java` and `Sfx.java` (all sound, synthesized in code). It draws with a plain `Canvas`: no engine, no libraries, no image assets except the launcher icon. Sizes are in `u`, where 1u is 1% of the screen height, so the game looks the same on every phone.

`build.sh` builds with the Android SDK tools directly; there is no Gradle.

```sh
./build.sh
```

This needs Android SDK build-tools 35, platform 35 and JDK 11 or newer.

To watch the game play itself, launch it with the autopilot extra:

```sh
adb shell am start -n dev.waterplane.game/.MainActivity --ez autopilot true
```

## Licence

Contributions are accepted under the project's licence, MIT. No CLA.
