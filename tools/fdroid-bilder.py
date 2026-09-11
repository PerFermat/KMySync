#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Nimmt je Sprache denselben Satz Bilder für die F-Droid-Beschreibung auf.

    tools/sprache.sh de                       # App-Sprache setzen
    tools/einspielen.sh demo-de.db            # Datenbestand einspielen
    tools/fdroid-bilder.py roh/de de          # sechs Bilder aufnehmen

Den passenden Datenbestand je Sprache macht tools/fdroid-demodaten.py.

Navigiert wird über uiautomator: Elemente am Text oder an der Kennung suchen und deren Mitte
antippen. Feste Koordinaten wären beim ersten Sprachwechsel hinfällig. Der Emulator läuft dafür
kopflos (»-no-window -gpu swiftshader_indirect«); screencap braucht kein Fenster, und mit Fenster
stirbt er an einem X-Fehler.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PAKET = "de.spahr.ausgaben"

# Beschriftungen je Sprache. Steht hier und nicht im Code, damit ein weiterer Satz nur ein
# Wörterbuch mehr ist.
TEXTE = {
    "de": {"kategorien": "Kategorien", "bestaende": "Bestände", "budget": "Budget",
           "einstellungen": "Einstellungen", "profil": "Profil ändern", "sprache": "Deutsch", "jahr": "Jahr"},
    "en": {"kategorien": "Categories", "bestaende": "Balances", "budget": "Budget",
           "einstellungen": "Settings", "profil": "Change profile", "sprache": "English", "jahr": "Year"},
    "es": {"kategorien": "Categorías", "bestaende": "Saldos", "budget": "Presupuesto",
           "einstellungen": "Ajustes", "profil": "Cambiar perfil", "sprache": "Español", "jahr": "Año"},
}


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def shell(*args, check=True):
    return adb("shell", *args, check=check)


def dump():
    for _ in range(4):
        r = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"],
                           capture_output=True, text=True)
        i = r.stdout.find("<?xml")
        e = r.stdout.rfind("</hierarchy>")
        if i >= 0 and e > i:
            try:
                return ET.fromstring(r.stdout[i:e + len("</hierarchy>")])
            except ET.ParseError:
                pass
        time.sleep(1)
    raise RuntimeError("uiautomator liefert keinen Baum")


def mitte(k):
    x1, y1, x2, y2 = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", k.get("bounds")).groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def finde(baum, *, text=None, id_=None, desc=None):
    for k in baum.iter("node"):
        if text is not None and k.get("text") == text:
            return k
        if id_ is not None and (k.get("resource-id") or "").endswith(":id/" + id_):
            return k
        if desc is not None and desc.lower() in (k.get("content-desc") or "").lower():
            return k
    return None


def tippe(*, warte=2.0, **suche):
    k = finde(dump(), **suche)
    if k is None:
        raise LookupError(f"nicht gefunden: {suche}")
    x, y = mitte(k)
    shell("input", "tap", str(x), str(y))
    time.sleep(warte)


def schiess(ziel, name):
    os.makedirs(ziel, exist_ok=True)
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=subprocess.PIPE)
    with open(os.path.join(ziel, name), "wb") as f:
        f.write(r.stdout)
    print(f"    {name}  {len(r.stdout)//1024} KB")


def leiste_demo():
    shell("settings", "put", "global", "sysui_demo_allowed", "1", check=False)
    for p in (["command", "enter"],
              ["command", "clock", "-e", "hhmm", "1200"],
              ["command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"],
              ["command", "network", "-e", "mobile", "hide"],
              ["command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
              ["command", "notifications", "-e", "visible", "false"]):
        shell("am", "broadcast", "-a", "com.android.systemui.demo", "-e", *p, check=False)
    time.sleep(1)


def neustart():
    shell("am", "force-stop", PAKET)
    time.sleep(1)
    shell("am", "start", "-n", f"{PAKET}/.ui.MainActivity")
    time.sleep(6)


def zurueck(n=1):
    for _ in range(n):
        shell("input", "keyevent", "4")
        time.sleep(1.5)


def menue(eintrag):
    """Überlaufmenü öffnen und einen Eintrag wählen.

    Die Beschreibung der drei Punkte kommt aus den appcompat-Ressourcen und richtet sich nach der
    Sprache des Geräts, nicht nach der der App – sie kann also deutsch bleiben, während die App
    englisch spricht. Deshalb beide Schreibweisen versuchen.
    """
    for beschreibung in ("Weitere Optionen", "More options", "Más opciones"):
        try:
            tippe(desc=beschreibung, warte=1.8)
            break
        except LookupError:
            continue
    else:
        raise LookupError("Überlaufmenü nicht gefunden")
    tippe(text=eintrag, warte=3.5)


def aufnehmen(ziel, sprache):
    t = TEXTE[sprache]
    leiste_demo()
    neustart()

    # 1 – Buchungsliste: das Gesicht der App, Saldo oben, Buchungen mit Konto und Betrag.
    schiess(ziel, "01_buchungen.png")

    # 2 – eine bestehende Buchung. Gesucht wird eine <b>Splitbuchung auf einem Bankkonto</b>: Splits
    # sind eine beworbene Stärke, und Bargeldbuchungen führen einen Ort mit sich – dessen Koordinaten
    # haben in einem Bild, das weltweit ausgeliefert wird, nichts zu suchen.
    baum = dump()
    zeilen = [k for k in baum.iter("node")
              if (k.get("resource-id") or "").endswith(":id/textAccount")]
    treffer = next((k for k in zeilen if "split" in (k.get("text") or "").lower()), None)
    if treffer is None:
        treffer = zeilen[1] if len(zeilen) > 1 else zeilen[0]
    x, y = mitte(treffer)
    shell("input", "tap", str(x), str(y))
    time.sleep(3)
    schiess(ziel, "02_buchung.png")
    zurueck()
    time.sleep(1)

    # 3 – Kategorien: das Tortendiagramm sagt auf einen Blick mehr als die Saldenkurve, bei der ein
    # hoher Bestand die Monatsbalken platt drückt.
    menue(t["kategorien"])
    time.sleep(2)
    # Auf Jahr umschalten: Der laufende Monat ist im Demo-Bestand leer, das Jahr ist gefüllt.
    try:
        tippe(text=t["jahr"], warte=3)
    except LookupError:
        pass
    schiess(ziel, "03_kategorien.png")
    zurueck()
    time.sleep(1)

    # 4 – Bestände/Depot
    menue(t["bestaende"])
    time.sleep(2)
    schiess(ziel, "04_bestaende.png")
    zurueck()
    time.sleep(1)

    # 5 – Budget
    menue(t["budget"])
    time.sleep(2)
    schiess(ziel, "05_budget.png")
    zurueck()
    time.sleep(1)

    # 6 – Verbindung zum eigenen Server: der Punkt, der die App von anderen unterscheidet.
    menue(t["einstellungen"])
    tippe(text=t["profil"], warte=3.5)
    schiess(ziel, "06_verbindung.png")
    zurueck(2)


if __name__ == "__main__":
    ziel, sprache = sys.argv[1], sys.argv[2]
    print(f"  Sprache {sprache} → {ziel}")
    aufnehmen(ziel, sprache)
