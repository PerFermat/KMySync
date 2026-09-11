#!/bin/bash
# Spielt einen Datenbestand in die profilspezifische Datei ein. Das Handbuch-Werkzeug kopiert nach
# databases/ausgaben.db -- diesen Namen benutzt die App aber nur fuer ein Altprofil; ein frisch
# angelegtes Profil liest ausgaben_<uuid>.db.
set -e
QUELLE=$1
DB=$(adb shell run-as de.spahr.ausgaben ls databases | tr -d '\r' | grep -E '^ausgaben.*\.db$' | head -1)
adb push "$QUELLE" /data/local/tmp/demo.db >/dev/null
adb shell am force-stop de.spahr.ausgaben
adb shell run-as de.spahr.ausgaben sh -c "'rm -f databases/$DB-wal databases/$DB-shm; cp /data/local/tmp/demo.db databases/$DB'"
adb shell rm -f /data/local/tmp/demo.db
echo "  eingespielt: $(basename $QUELLE) -> $DB"
