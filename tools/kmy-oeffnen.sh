#!/usr/bin/env bash
# Prüft, ob KMyMoney die Testdateien dieses Projekts überhaupt liest.
#
#   tools/kmy-oeffnen.sh                 # alle XML-Testdateien unter app/src/test/resources/kmy
#   tools/kmy-oeffnen.sh DATEI …         # bestimmte .xml- oder .kmy-Dateien
#
# Jede Datei wird mit dem installierten KMyMoney geöffnet – ohne Fenster und mit einem eigenen,
# leeren Home-Verzeichnis, damit die eigenen Einstellungen und die Liste der zuletzt geöffneten
# Dateien unberührt bleiben. Ausgewertet wird, was der Leser meldet: kommt er bis zum Ende
# („endDocument") und ohne Ausnahme, gilt die Datei als gelesen.
#
# Das sieht nur den Leser. Ob der Kontenbaum danach stimmt, zeigt erst ein Blick ins Fenster.
# Kein Teil des Gradle-Laufs: auf einem Rechner ohne KMyMoney gäbe es nichts zu prüfen.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v kmymoney > /dev/null || { echo "kmymoney ist nicht installiert" >&2; exit 2; }

if [ $# -gt 0 ]; then
  dateien=("$@")
else
  mapfile -t dateien < <(find "$repo/app/src/test/resources/kmy" -maxdepth 1 -name '*.xml' | sort)
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/home" "$tmp/dateien"

schlecht=0
for quelle in "${dateien[@]}"; do
  name="$(basename "$quelle")"
  ziel="$tmp/dateien/${name%.*}.kmy"
  # KMyMoney liest gepackt wie ungepackt; gepackt entspricht dem, was die App schreibt.
  if [ "$(head -c 2 "$quelle" | xxd -p)" = "1f8b" ]; then
    cp "$quelle" "$ziel"
  else
    gzip -c "$quelle" > "$ziel"
  fi
  log="$tmp/${name}.log"
  HOME="$tmp/home" XDG_RUNTIME_DIR="$tmp/home" QT_QPA_PLATFORM=offscreen LANG=C \
    timeout 20 kmymoney "$ziel" > "$log" 2>&1
  fehler="$(sed -n '/start parsing file/,$p' "$log" \
    | grep -E 'Exception|not found\.|Error|error:' | sort -u | head -5)"
  if grep -q 'endDocument' "$log" && [ -z "$fehler" ]; then
    printf '  gelesen   %s\n' "$name"
  else
    schlecht=$((schlecht + 1))
    printf '  FEHLER    %s\n' "$name"
    if [ -n "$fehler" ]; then
      printf '%s\n' "$fehler" | sed 's/^/              /'
    else
      echo "              der Leser kam nicht bis zum Ende der Datei"
    fi
  fi
done

[ "$schlecht" -eq 0 ] && echo "alle ${#dateien[@]} Dateien gelesen" || echo "$schlecht von ${#dateien[@]} nicht gelesen"
exit "$([ "$schlecht" -eq 0 ] && echo 0 || echo 1)"
