#!/bin/bash
#
# Bilder aufnehmen: bauen, auf dem Emulator installieren, Aufnahmefenster öffnen.
#
#   ./screenshots.sh              deutscher Satz
#   ./screenshots.sh --lang en    englischer Satz
#
# Läuft kein Emulator, wird einer gestartet. Der AVD-Name und die Wartelogik stehen dafür nicht
# hier, sondern in tools/screenshots.py – sonst gäbe es zwei Stellen, die auseinanderlaufen können.
#
# Gebaut wird die Debug-Fassung: Nur sie erlaubt „run-as", und ohne das ließe sich kein
# Datenbestand in die App spielen.

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/full/debug/app-full-debug.apk"
PACKAGE_NAME="de.spahr.ausgaben"
SCREENSHOT_SCRIPT="$PROJECT_DIR/tools/screenshots.py"

cd "$PROJECT_DIR"

echo "=== App bauen ==="
./gradlew assembleFullDebug

echo "=== Emulator ==="
DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -n1)

if [ -z "$DEVICE" ]; then
    echo "Kein Gerät – Emulator wird gestartet (das dauert)."
    DEVICE=$(python3 "$SCREENSHOT_SCRIPT" --emulator-starten)
fi

if [ -z "$DEVICE" ]; then
    echo "Es kam kein Gerät hoch." >&2
    exit 1
fi
echo "Gerät: $DEVICE"

echo "=== APK installieren ==="
adb -s "$DEVICE" install -r "$APK_PATH"

echo "=== App starten ==="
adb -s "$DEVICE" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

echo "=== Aufnahmefenster ==="
# Die Seriennummer wird durchgereicht: Hängt neben dem Emulator noch ein echtes Gerät am Rechner,
# nähme das Fenster sonst womöglich das falsche.
python3 "$SCREENSHOT_SCRIPT" --geraet "$DEVICE" "$@"

echo "=== Fertig ==="
