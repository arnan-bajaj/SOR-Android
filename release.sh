#!/bin/zsh
# Builds a signed release APK into dist/ (APK download only: no Play Store).
# Needs the release key in ~/.sor-android/ (see README "Signing"). The version code is a timestamp,
# so every APK installs over the one before it.
set -e
cd "$(dirname "$0")"
[[ -f ~/.sor-android/keystore.properties ]] || { echo "No release key in ~/.sor-android/ (see README, Signing)"; exit 1; }
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
CODE=$(date +%y%m%d%H)
./gradlew :app:testDebugUnitTest :app:assembleRelease -PversionCode=$CODE -q
VERSION=$(grep -m1 'versionName' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
mkdir -p dist
OUT=dist/sor-team-$VERSION-$CODE.apk
cp app/build/outputs/apk/release/app-release.apk $OUT
cp $OUT dist/sor-team.apk
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs $OUT | grep -m1 SHA-256
echo "Built $OUT (version $VERSION, code $CODE)"
