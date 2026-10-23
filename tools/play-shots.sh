#!/usr/bin/env bash
# Google Play phone screenshots: `tools/play-shots.sh` composes tools/play-shots/raw/*.png (real
# emulator frames, 2992x1344) into fastlane/metadata/android/en-US/images/phoneScreenshots/N.jpg.
# Play rules: long side at most 2x the short side, no alpha channel (so JPEG). The game is landscape,
# so each picture is 2160x1080 landscape: a caption band on top (one short sentence, present tense),
# the game frame below with the emulator's 160 px camera-cutout strip cropped off.
#
# Refresh the raw frames (only when the game's look changes):
#   ./build.sh && adb -s emulator-5580 install -r build/waterplane-game-*.apk
#   adb -s emulator-5580 shell pm clear dev.waterplane.game
#   adb -s emulator-5580 shell am start -n dev.waterplane.game/.MainActivity --ez autopilot true
#   then `adb -s emulator-5580 exec-out screencap -p > tools/play-shots/raw/<n>-<name>.png` at the right
#   moment (menu; after tapping FLY at 1735 907 and tapping once more to launch: flying frames; pick
#   Easy at 1316 600 first for a run that reaches the level-clear card). Autopilot crashes on Normal
#   near 1000 m, so the finish needs Easy. Use the debug key only (plain ./build.sh).
set -euo pipefail
cd "$(dirname "$0")"
OUT=../fastlane/metadata/android/en-US/images/phoneScreenshots
FONT="/System/Library/Fonts/Supplemental/Arial Bold.ttf"
[ -f "$FONT" ] || FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
BG='#091823'
W=2160; H=1080; BAND=150
CAPTIONS=(
  "1-menu|Pick a difficulty and tap FLY"
  "2-climb|Hold to climb, let go to glide"
  "3-lighthouse|Thread the gap past the lighthouse"
  "4-skim|Climb over rock spires and reefs"
  "5-cursors|Grab the amber cursors for stars"
  "6-clear|Finish Harbor Run, earn your stars"
)
mkdir -p "$OUT"
rm -f "$OUT"/*.jpg
n=0
for row in "${CAPTIONS[@]}"; do
  n=$((n+1)); name="${row%%|*}"; cap="${row#*|}"
  magick -size ${W}x${H} "xc:$BG" \
    \( "play-shots/raw/$name.png" -crop +160+0 +repage -resize x$((H-BAND)) \) -gravity south -composite \
    -gravity north -font "$FONT" -fill '#f4f1ea' -pointsize 64 -annotate +0+40 "$cap" \
    -alpha off -strip -quality 92 "$OUT/$n.jpg"
done
echo "wrote $n screenshots to $OUT"
