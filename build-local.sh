#!/usr/bin/env bash
# Gradle-free build: aapt2 + javac + d8 + apksigner, signed with debug.keystore so it
# installs over earlier builds. Works on the Mac (Homebrew android-commandlinetools +
# openjdk@17) and on the original Linux box (/tmp/tools). Override any path with
# BT=<build-tools dir> AJ=<android.jar> JDK=<jdk bin dir>.
set -euo pipefail
VERSION_CODE=3
VERSION_NAME=1.2

SDK=/opt/homebrew/share/android-commandlinetools
if [ -d "$SDK/build-tools/34.0.0" ]; then
  : "${BT:=$SDK/build-tools/34.0.0}" "${AJ:=$SDK/platforms/android-34/android.jar}"
  : "${JDK:=/opt/homebrew/opt/openjdk@17/bin}"
else
  : "${BT:=/tmp/tools/build-tools/34.0.4}" "${AJ:=/tmp/tools/android.jar}" "${JDK:=/tmp/jdk/bin}"
fi

P=$(cd "$(dirname "$0")" && pwd); M=$P/app/src/main; O=$P/out
rm -rf "$O"; mkdir -p "$O"/{gen,classes,dex}
sed 's#<manifest #<manifest package="com.hector.cleanlauncher" #' "$M/AndroidManifest.xml" > "$O/AndroidManifest.xml"
"$BT/aapt2" compile --dir "$M/res" -o "$O/res.zip"
"$BT/aapt2" link -o "$O/base.apk" -I "$AJ" --manifest "$O/AndroidManifest.xml" \
  --min-sdk-version 22 --target-sdk-version 34 --version-code $VERSION_CODE --version-name $VERSION_NAME \
  -A "$M/assets" -0 jpg --java "$O/gen" "$O/res.zip"
"$JDK/javac" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -d "$O/classes" \
  $(find "$M/java" "$O/gen" -name '*.java')
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 22 --lib "$AJ" \
  --output "$O/dex" $(find "$O/classes" -name '*.class')
cp "$O/base.apk" "$O/unsigned.apk"; (cd "$O/dex" && zip -q -j "$O/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$O/unsigned.apk" "$O/aligned.apk"
[ -f "$P/debug.keystore" ] || "$JDK/keytool" -genkeypair -keystore "$P/debug.keystore" -storepass android \
  -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
"$JDK/java" -jar "$BT/lib/apksigner.jar" sign --ks "$P/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$P/CleanLauncher.apk" "$O/aligned.apk"
"$JDK/java" -jar "$BT/lib/apksigner.jar" verify "$P/CleanLauncher.apk" && echo "BUILD OK: $(du -h "$P/CleanLauncher.apk" | cut -f1)"
