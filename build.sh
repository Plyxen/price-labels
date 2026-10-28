#!/usr/bin/env bash
# Builds Price-Labels.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: bash build.sh   (bump VERSION_CODE for every update you install over an old one)
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=8
VERSION_NAME=1.6
MIN_SDK=29
TARGET_SDK=35

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
JDK="${JAVA_HOME:-/c/Program Files/Java/jdk-17}"
KEYSTORE=keystore/arcimke.jks
# The signing password is never committed: it comes from $ARCIMKE_KEYPASS or keystore/password.txt
# (keystore/ is git-ignored). A first build without a key creates both.
PASSFILE=keystore/password.txt
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" keystore

echo "> resources"
"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest app/AndroidManifest.xml -A app/assets \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  "$OUT/res.zip"

echo "> java"
"$JDK/bin/javac" -nowarn --release 11 -encoding UTF-8 \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" $(find app/src -name '*.java')

echo "> dex"
"$BT/d8.bat" --release --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "> package"
python -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
  "$OUT/base.apk" "$OUT/dex/classes.dex"
"$BT/zipalign" -p -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

KEYPASS="${ARCIMKE_KEYPASS:-}"
if [ -z "$KEYPASS" ] && [ -f "$PASSFILE" ]; then KEYPASS="$(tr -d '\r\n' < "$PASSFILE")"; fi
if [ ! -f "$KEYSTORE" ]; then
  echo "> creating signing key (back up keystore/ - updates must be signed with the same key)"
  if [ -z "$KEYPASS" ]; then
    KEYPASS="$(python -c "import secrets; print(secrets.token_urlsafe(18))")"
    printf '%s\n' "$KEYPASS" > "$PASSFILE"
  fi
  "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias arcimke -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=Arcimke, C=HU"
fi
[ -n "$KEYPASS" ] || { echo "No signing password: set ARCIMKE_KEYPASS or put it in $PASSFILE" >&2; exit 1; }

echo "> sign"
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out Price-Labels.apk "$OUT/aligned.apk"
"$BT/apksigner.bat" verify Price-Labels.apk
echo "Built $(pwd)/Price-Labels.apk ($(du -k Price-Labels.apk | cut -f1) KB)"
