#!/usr/bin/env bash
# Usage: ./install.sh [firetv-ip] [apk]
set -e
IP="${1:-192.168.1.126}"
APK="${2:-CleanLauncher.apk}"
DEV="$IP:5555"
PKG=com.hector.cleanlauncher
SVC="$PKG/.HomeWatcherService"

adb connect "$DEV"
adb -s "$DEV" wait-for-device
adb -s "$DEV" install -r "$APK"

CUR=$(adb -s "$DEV" shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$SVC"
elif echo "$CUR" | grep -q "$SVC"; then NEW="$CUR"
else NEW="$CUR:$SVC"; fi
adb -s "$DEV" shell settings put secure enabled_accessibility_services "$NEW"
adb -s "$DEV" shell settings put secure accessibility_enabled 1
# Optional: lets Clean Launcher see Home presses in the system log (faster, and catches
# Home pressed while Amazon's home is showing). Undo with: pm revoke $PKG android.permission.READ_LOGS
adb -s "$DEV" shell pm grant "$PKG" android.permission.READ_LOGS || echo "READ_LOGS not granted; Home detection stays basic."
adb -s "$DEV" shell pm grant "$PKG" android.permission.DUMP || echo "DUMP not granted; Tasks shows apps without memory use."
adb -s "$DEV" shell pm grant "$PKG" android.permission.PACKAGE_USAGE_STATS || true
adb -s "$DEV" shell appops set "$PKG" GET_USAGE_STATS allow || echo "Usage access not granted; Tasks shows apps without memory use."
# Lets the launcher show the Wi-Fi network's name (Android 9 hides it from apps otherwise).
adb -s "$DEV" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || echo "Location not granted; the Wi-Fi name shows as \"Wi-Fi\"."
adb -s "$DEV" shell am start -n "$PKG/.MainActivity"
echo "Installed. Press Home on the remote to test."
