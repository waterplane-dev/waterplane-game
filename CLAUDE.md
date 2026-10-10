# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Waterplane: Harbor Run is a small Android side-scroller in which you fly the waterplane logo. It is one of the two demo apps for waterplane's open-source launch; the other is `~/dev/child-paint`, which sets the bar for what "publishable" means here. Read README.md for the gameplay rules.

## Build and run

```sh
./build.sh   # → build/waterplane-game-<VERSION>.apk
```

- There is no Gradle. `build.sh` calls aapt2, javac (`--release 11`), d8, zipalign and apksigner from Android SDK build-tools 35 and platform 35 directly. Bump `VERSION` and `VERSION_CODE` at the top of `build.sh`.
- Signing: `build.sh` uses `keystore.properties` when it exists (gitignored; the format is in `keystore.properties.example`) and falls back to `~/.android/debug.keystore` otherwise.
- There are no tests and no lint. To verify a change, run the game on an emulator and look at screenshots.
- Autopilot: `adb shell am start -n dev.waterplane.game/.MainActivity --ez autopilot true` makes the game steer itself through the gaps. `docs/demo.gif` was recorded this way.
- `.waterplane/builds.json` defines the phone's "Game APK" build button, which runs `./build.sh`. Build outputs are collected into `.claude/outbox/` automatically; never copy an APK there by hand.

## Emulator

Other emulators may already be running on this machine for other sessions, and adb can hang on them. Leave them alone. Boot your own headless phone instead:

```sh
emulator -avd wp_p8pro_1 -port 5580 -no-window -no-audio -no-snapshot-save -read-only &
adb -s emulator-5580 ...            # always pass -s
adb -s emulator-5580 emu kill       # when done
```

- The game runs in landscape, 2992×1344 on this AVD. The FLY button is at about `input tap 1735 907`.
- Don't call a bare `wait` in a script that started the emulator in the background; it waits for the emulator too and hangs.
- The black strip on the left of landscape screenshots is the emulated camera cutout, not a game bug.

## Architecture

The whole game is two files in `src/dev/waterplane/game/`. `MainActivity` sets up the fullscreen window and passes back-press and pause to the view. Everything else is in `GameView`:

- **Loop:** `onDraw` measures `dt` (capped at 1/30 s), calls `update(dt)` then `render(c)`, and schedules the next frame with `postInvalidateOnAnimation()`. There is no separate thread or SurfaceView.
- **States:** `MENU → READY → PLAY → DEAD | WIN`. A tap on DEAD or WIN is ignored for the first 0.8 s.
- **Units:** every size is a multiple of `u`, which is 1% of the screen height, so the layout is the same on every phone. The sea is at `seaY = 80u` and the plane's x position is fixed at `PX = 0.24·W`.
- **World:** `cam` is the scroll distance in pixels, and obstacles (`Ob`) store world x, so their screen x is `o.x - cam`. `spawn()` generates gates (a storm cloud from above plus a rock spire or lighthouse from below, around a gap centre stored in the cloud's `y`), reefs and cursor pickups ahead of the camera. It places the finish line at `levelLen`.
- **Difficulty:** the static arrays at the top of `GameView` (`SPEED`, `GAP`, `SPACING`, `BURN`, `CRASH_V`, `SHIFT`) are indexed by `diff` (0 Easy, 1 Normal, 2 Hard). The level ramps up speed and narrows gaps as it progresses.
- **Mechanics:** holding climbs and burns `fuel` (shown as "TOKENS"). Touching the sea faster than `CRASH_V` crashes; otherwise the plane skims and refuels.
- **Rendering:** everything is drawn procedurally with `Canvas` and `Path`; there are no image assets except the launcher icon. The plane is the logo's chevron, drawn in the logo's 512-unit coordinates and scaled by `PLANE * u / 214`. Particles live in a fixed ring buffer of parallel arrays, in screen space, and scroll with the world.
- **Persistence:** SharedPreferences `waterplane` stores `best<diff>`, `stars<diff>` and `diff`.

## Gotchas

- Paints are shared and reused. Calling `setColor` also sets alpha, and a shader is tinted by the paint's alpha. So call `fill.setColor(0xFFFFFFFF)` before setting a gradient shader, and `fill.setShader(null)` before drawing a plain colour. Skipping either has already caused faded buttons.
- Obstacle shapes are built once at spawn time, in local coordinates. Merge overlapping shapes with `Path.op(UNION)` (see `union()`); otherwise their outlines show every internal circle.
- Colours come from the waterplane brand (`NIGHT`, `SEA`, `AMBER`, `CORAL`, `FOAM`); the source is `~/dev/waterplane-public/brand/icon.svg`.
- No network, ads or analytics. CONTRIBUTING.md and SECURITY.md promise that.
