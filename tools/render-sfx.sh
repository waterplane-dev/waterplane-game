#!/usr/bin/env bash
# Plays a scripted flight through the real Sfx mixer on the desktop JVM and writes
# build/sfx.wav, so sounds can be checked without a phone.
set -euo pipefail
cd "$(dirname "$0")/.."
JAR="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar"
mkdir -p build/sfx
javac -nowarn -cp "$JAR" -d build/sfx src/dev/waterplane/game/Sfx.java tools/dev/waterplane/game/RenderSfx.java
java -cp "build/sfx:$JAR" dev.waterplane.game.RenderSfx build/sfx.wav
echo "Wrote build/sfx.wav"
