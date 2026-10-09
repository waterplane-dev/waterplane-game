#!/usr/bin/env bash
# Builds a signed APK straight from the Android SDK tools (no Gradle needed).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
VERSION="1.0.0"
KS="$HOME/.android/debug.keystore"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/base.apk build/res.zip \
  --java build/gen --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 1 --version-name "$VERSION"
javac -nowarn --release 11 -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "build/waterplane-game-$VERSION.apk" build/aligned.apk
rm -f build/base.apk build/unsigned.apk build/aligned.apk build/*.idsig
echo "Built build/waterplane-game-$VERSION.apk"
