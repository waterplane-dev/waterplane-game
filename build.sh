#!/usr/bin/env bash
# Builds a signed APK (to install directly) and a signed AAB (to upload to Google Play)
# straight from the Android SDK tools (no Gradle needed).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
VERSION="1.0.0"
VERSION_CODE=1

# Play only accepts bundles; bundletool assembles one. Pinned by version and hash.
BUNDLETOOL_VERSION="1.18.1"
BUNDLETOOL_SHA256="675786493983787ffa11550bdb7c0715679a44e1643f3ff980a529e9c822595c"
BUNDLETOOL="${BUNDLETOOL:-$HOME/.cache/bundletool/bundletool-$BUNDLETOOL_VERSION.jar}"
if [ ! -f "$BUNDLETOOL" ]; then
  mkdir -p "$(dirname "$BUNDLETOOL")"
  curl -fsSL -o "$BUNDLETOOL.part" \
    "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar"
  mv "$BUNDLETOOL.part" "$BUNDLETOOL"
fi
echo "$BUNDLETOOL_SHA256  $BUNDLETOOL" | shasum -a 256 -c --quiet

rm -rf build && mkdir -p build/gen build/classes build/dex build/base
"$BT/aapt2" compile --dir res -o build/res.zip
LINK=(-I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 36
  --version-code "$VERSION_CODE" --version-name "$VERSION")
"$BT/aapt2" link "${LINK[@]}" -o build/base.apk build/res.zip --java build/gen
"$BT/aapt2" link "${LINK[@]}" -o build/proto.apk build/res.zip --proto-format
javac -nowarn --release 11 -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

# Release signing: the UPLOAD_* environment first (a machine-local signed.local.sh may fill it),
# else an untracked keystore.properties (see keystore.properties.example). Without either
# (a fresh clone) both are signed with the local debug key, which Play refuses.
# Passwords reach the signers through the environment, never as arguments (visible in `ps`).
prop() { grep -E "^$1=" keystore.properties | cut -d= -f2-; }
if [ -n "${UPLOAD_STORE_FILE:-}" ]; then
  KS="$UPLOAD_STORE_FILE"; ALIAS="${UPLOAD_KEY_ALIAS:?UPLOAD_KEY_ALIAS is not set}"
  KS_PASS="${UPLOAD_STORE_PASSWORD:?UPLOAD_STORE_PASSWORD is not set}"; KEY_PASS="${UPLOAD_KEY_PASSWORD:-$KS_PASS}"
elif [ -f keystore.properties ]; then
  KS="$(prop storeFile)"; KS_PASS="$(prop storePassword)"; ALIAS="$(prop keyAlias)"; KEY_PASS="$(prop keyPassword)"
else
  KS="$HOME/.android/debug.keystore"; KS_PASS=android; ALIAS=androiddebugkey; KEY_PASS=android
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  fi
  echo "No upload key (UPLOAD_* or keystore.properties): signing with the debug key (not uploadable to Play)"
fi
export KS_PASS KEY_PASS

# APK
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass env:KS_PASS --ks-key-alias "$ALIAS" --key-pass env:KEY_PASS \
  --out "build/waterplane-game-$VERSION.apk" build/aligned.apk

# AAB: the proto-format resources, rearranged into bundletool's module layout
(cd build/base && unzip -q ../proto.apk && mkdir manifest dex \
  && mv AndroidManifest.xml manifest/ && cp ../dex/classes.dex dex/ && zip -qr ../base.zip .)
java -jar "$BUNDLETOOL" build-bundle --modules=build/base.zip --output=build/unsigned.aab
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore "$KS" -storepass:env KS_PASS -keypass:env KEY_PASS \
  -signedjar "build/waterplane-game-$VERSION.aab" build/unsigned.aab "$ALIAS" >/dev/null

rm -rf build/base build/base.apk build/proto.apk build/base.zip build/unsigned.* build/aligned.apk build/*.idsig
echo "Built build/waterplane-game-$VERSION.apk and build/waterplane-game-$VERSION.aab"
