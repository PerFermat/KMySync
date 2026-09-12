#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Aufnahmehelfer für die Handbuchbilder.

Ein Fenster mit drei Spalten: links das bisherige Handbuchbild, in der Mitte der Emulator
mitlaufend, rechts die frische Aufnahme zum Zuschneiden und Markieren. Ganz links die Liste
aller Bilder, die das Handbuch verlangt.

    tools/screenshots.py                 # deutscher Satz nach screenshots/de/
    tools/screenshots.py --lang en       # englischer Satz nach screenshots/en/
    tools/screenshots.py --rohe-leiste   # Statusleiste unangetastet lassen

Welcher Datenbestand im Emulator steht, wird im Fenster gewählt („Bestand"): Deutsch oder English.
Für den englischen Satz braucht es einen englischen Bestand, denn Kontonamen, Empfänger und
Kategorien stehen in der Datenbank und nicht in den Übersetzungen. Beide erzeugt
tools/bestand_aufbereiten.py; sie liegen bewusst außerhalb des Projektordners.

Das Skript navigiert nicht selbst durch die App – gesteuerte Tipper werden bei jedem Umbau der
Oberfläche brüchig. Sie bedienen den Emulator, das Fenster nimmt Ihnen den Rest ab.

Ein neues Bild einpflegen heißt: im Handbuch-Editor (tools/handbuch-editor.py) einen Bildblock
ergänzen. Beim nächsten Start steht es in der Liste – eine zweite Liste, die veralten könnte,
gibt es bewusst nicht.
"""
import argparse
import atexit
import os
import queue
import shlex
import signal
import struct
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk

from PIL import Image, ImageDraw, ImageTk

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAKET = "de.spahr.ausgaben"
AVD = "Pixel_7_API_35"
SOLL_GROESSE = (1080, 2400)          # Maß der vorhandenen 28 Bilder; Abweichung wird gemeldet
ROH = os.path.join(REPO, "build", "screenshots-roh")
# Wohin die Ausgabe des Emulators geht – siehe emulator_starten().
EMULATOR_LOG = os.path.join(REPO, "build", "emulator.log")

# Die beiden Bestände für die Aufnahme, erzeugt von tools/bestand_aufbereiten.py. Der englische Satz
# braucht einen englischen Bestand: Kontonamen, Empfänger und Kategorien stehen in der Datenbank und
# nicht in den Übersetzungen – ein deutscher Kontoname im englischen Handbuch fiele sofort auf.
#
# Bewusst außerhalb des Projektordners und fest eingetragen statt frei wählbar. Die Vorlage, aus der
# beide entstehen, trägt Klarnamen und Bankverbindungen mit sich; was daraus erzeugt wird, soll
# nicht versehentlich durch eine unaufbereitete Datei ersetzt werden können.
BESTAENDE = [
    ("Deutsch", os.path.expanduser("~/Nextcloud/kmysync-bestand-de.db")),
    ("English", os.path.expanduser("~/Nextcloud/kmysync-bestand-en.db")),
]

# Dasselbe Rot wie in docs/img/export_button.png, damit die Markierungen im Handbuch nicht
# zweierlei Rot zeigen. Aus dem vorhandenen Bild ausgelesen.
ROT = (214, 20, 20)

HANDBUCH = {"de": ("docs/handbuch_de.json", os.path.join("screenshots", "de")),
            "en": ("docs/handbuch_en.json", os.path.join("screenshots", "en"))}

# Bilder, die nicht einfach aus der laufenden App fallen. Sie stehen trotzdem in der Liste – der
# Emulator kann ja auch den Startbildschirm zeigen –, der Vermerk erinnert nur an den Umweg.
VON_HAND = {
    "Promo-UhranlagemitAlias.png": "kommt von der Uhr, nicht aus dem Emulator",
    "Widget.png": "Startbildschirm mit den vier Widget-Größen",
}

ANZEIGE_HOEHE = 620                  # Höhe, auf die jede der drei Spalten skaliert wird


# ------------------------------------------------------------------ Handbuch lesen
def bilder_aus_handbuch(pfad):
    """Liest aus der Handbuch-JSON alle Bilder als (Dateiname, Bildunterschrift).

    Gesammelt wird über die Blöcke hinweg, auch aus dem "content" der Bildblöcke. Ausschnitte
    ("pic") zählen mit, sofern sie unter screenshots/ liegen – das Zuschneiden beherrscht dieses
    Fenster ja. Ein "pic" mit einem Pfad woandershin bleibt außen vor; es kommt nicht aus dem
    Emulator und wäre hier nicht aufzunehmen."""
    import json
    import posixpath

    with open(pfad, encoding="utf-8") as f:
        daten = json.load(f)

    treffer, gesehen = [], set()

    def merken(eintrag, schluessel="fname"):
        name = eintrag.get(schluessel)
        if schluessel == "relpath":
            if not name or not name.startswith("screenshots/"):
                return
            name = posixpath.basename(name)
        if name and name not in gesehen:
            gesehen.add(name)
            treffer.append((name, eintrag.get("caption", "")))

    def block(eintrag):
        if "shot" in eintrag:
            merken(eintrag["shot"])
        if "pic" in eintrag:
            merken(eintrag["pic"], "relpath")
        for weiteres in eintrag.get("shots", []):
            merken(weiteres)
        for kind in eintrag.get("content", []):
            block(kind)

    for eintrag in daten.get("sections", []):
        block(eintrag)
    return treffer


# ------------------------------------------------------------------ adb
class Adb:
    def __init__(self, serial):
        self.serial = serial

    def _befehl(self, *args):
        return ["adb", "-s", self.serial] + list(args)

    def lauf(self, *args, pruefen=True):
        return subprocess.run(self._befehl(*args), capture_output=True, text=True, check=pruefen)

    def shell(self, *args, pruefen=True):
        return self.lauf("shell", *args, pruefen=pruefen)

    def bild(self):
        """Rohe PNG-Daten des Bildschirms. exec-out, damit nichts an Zeilenenden zerbricht."""
        return subprocess.run(self._befehl("exec-out", "screencap", "-p"),
                              capture_output=True, check=True).stdout


def geraete():
    zeilen = subprocess.run(["adb", "devices"], capture_output=True, text=True,
                            check=True).stdout.splitlines()[1:]
    return [z.split()[0] for z in zeilen if z.strip() and z.split()[-1] == "device"]


def emulator_starten(melden=lambda t: None):
    """Startet den Emulator im Hintergrund und wartet, bis Android hochgefahren ist.

    <b>Warum Software-Rendering:</b> Mit der voreingestellten GPU-Durchreichung stürzte der Emulator
    hier wiederholt ab – im Kernelprotokoll steht dann ein Schwung gleichzeitiger Segfaults in
    {@code libgfxstream_backend.so}, der Grafikschicht des Emulators. Die Kombination aus Intel Iris
    Xe und Mesa ist dafür bekannt.

    <p>{@code swiftshader_indirect} schaltet nicht diese Schicht ab – die bleibt geladen –, sondern
    das, worauf sie aufsetzt: Statt des Intel-Treibers rechnet SwiftShader auf der CPU (im Protokoll
    steht dann „ICD set to 'swiftshader'"). Genau das Zusammenspiel, in dem es krachte, findet damit
    nicht mehr statt. Langsamer, aber es läuft durch – und für Bildaufnahmen zählt das. Die
    Aufnahmen sehen gleich aus und hängen sogar weniger vom Treiber des Rechners ab.</p>
    """
    binaer = None
    for wurzel in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                   os.path.expanduser("~/Android/Sdk")):
        if wurzel and os.path.isfile(os.path.join(wurzel, "emulator", "emulator")):
            binaer = os.path.join(wurzel, "emulator", "emulator")
            break
    if binaer is None:
        binaer = "emulator"

    # Die Ausgabe gehörte früher nach /dev/null. Brach der Emulator ab, stand nirgends warum –
    # die eine Auskunft, die man dann braucht, war weggeworfen.
    os.makedirs(os.path.dirname(EMULATOR_LOG), exist_ok=True)
    protokoll = open(EMULATOR_LOG, "w")
    melden(f"{AVD} startet … (Protokoll: {EMULATOR_LOG})")
    subprocess.Popen([binaer, "-avd", AVD, "-gpu", "swiftshader_indirect"],
                     stdout=protokoll, stderr=subprocess.STDOUT)
    for versuch in range(180):
        time.sleep(2)
        for serial in geraete():
            if serial.startswith("emulator-"):
                fertig = subprocess.run(["adb", "-s", serial, "shell", "getprop",
                                         "sys.boot_completed"], capture_output=True, text=True)
                if fertig.stdout.strip() == "1":
                    return serial
        if versuch % 10 == 9:
            melden(f"{AVD} startet … ({versuch * 2} s)")
    return None


# ------------------------------------------------------------------ Datenstand
def aktive_db_datei(adb):
    """Dateiname der Datenbank, die die App gerade wirklich öffnet.

    Nicht zu erraten: Nur das aus einer Altinstallation migrierte Profil heißt „ausgaben.db"; jedes
    später angelegte – und damit auch das einzige eines frisch aufgesetzten Emulators – heißt
    „ausgaben_<uuid>.db" (siehe ProfileManager.dbFileNameFor). Wer fest auf „ausgaben.db" kopiert,
    legt den Bestand neben die Datei, die die App liest, und wundert sich über den alten Stand.

    Gelesen wird dieselbe Auskunft, aus der es auch die App nimmt: das aktive Profil und sein
    Dateiname aus shared_prefs/ausgaben_profiles.xml. Läßt sich das nicht lesen (ganz frische
    Installation, noch keine Einstellungen), bleibt es beim Altnamen – dann gibt es ohnehin nichts
    zu überschreiben.
    """
    import json
    import xml.etree.ElementTree as ET

    lauf = adb.shell("run-as", PAKET, "cat", "shared_prefs/ausgaben_profiles.xml", pruefen=False)
    if lauf.returncode != 0 or not lauf.stdout.strip():
        return "ausgaben.db"
    try:
        wurzel = ET.fromstring(lauf.stdout)
        werte = {k.get("name"): (k.text or "") for k in wurzel}
        aktiv = werte.get("active_profile_id", "")
        for profil in json.loads(werte.get("profiles", "[]")):
            if profil.get("id") == aktiv:
                return profil.get("dbFileName") or "ausgaben.db"
    except Exception:
        pass          # unlesbar ist so gut wie nicht vorhanden – der Altname trägt weiter
    return "ausgaben.db"


def bestand_einspielen(adb, quelle, melden):
    if not os.path.isfile(quelle):
        melden(f"{quelle} gibt es nicht – Datenstand bleibt, wie er ist.")
        return
    # run-as gibt es nur für die Debug-Fassung. Lieber im Klartext sagen als still scheitern.
    if adb.shell("run-as", PAKET, "ls", pruefen=False).returncode != 0:
        melden("run-as schlägt fehl – im Emulator läuft wohl die Release-Fassung. "
               "Erst umstellen:  ./gradlew :app:installFullDebug")
        return

    db = aktive_db_datei(adb)
    ziel = "/data/local/tmp/ausgaben-demo.db"
    adb.lauf("push", quelle, ziel)
    adb.shell("am", "force-stop", PAKET)
    # mkdir, weil es databases/ nach einer frischen Installation noch gar nicht gibt.
    # -wal und -shm müssen weg, sonst mischt Room den alten Schreibpuffer über den neuen Stand.
    # shlex.quote ist nötig, weil adb die Argumente wieder zu einer Zeile fügt und die Shell auf
    # dem Gerät sie erneut zerlegt – ohne Anführungszeichen liefe nur das rm unter run-as.
    auftrag = (f"mkdir -p databases; "
               f"rm -f databases/{db}-wal databases/{db}-shm; "
               f"cp {ziel} databases/{db}")
    kopie = adb.shell("run-as", PAKET, "sh", "-c", shlex.quote(auftrag), pruefen=False)
    adb.shell("rm", "-f", ziel, pruefen=False)
    if kopie.returncode != 0:
        melden(f"Kopieren fehlgeschlagen: {(kopie.stderr or kopie.stdout).strip()}")
        return
    adb.shell("am", "start", "-n", f"{PAKET}/.ui.MainActivity")
    melden(f"{os.path.basename(quelle)} → {db}, App neu gestartet.")


# ------------------------------------------------------------------ Statusleiste
def _demo(adb, *paare):
    adb.shell("am", "broadcast", "-a", "com.android.systemui.demo", *paare, pruefen=False)


def leiste_aufraeumen(adb):
    """Vorführ-Betriebsart: feste Uhr 12:00, volles WLAN, keine fremden Symbole."""
    adb.shell("settings", "put", "global", "sysui_demo_allowed", "1", pruefen=False)
    _demo(adb, "-e", "command", "enter")
    _demo(adb, "-e", "command", "clock", "-e", "hhmm", "1200")
    # „fully true" nimmt dem WLAN-Symbol das Ausrufezeichen (der Emulator hat kein echtes Netz),
    # das Mobilfunk-Symbol bleibt ganz weg – sonst stehen dort zwei Funkzeichen nebeneinander.
    _demo(adb, "-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4",
          "-e", "fully", "true")
    _demo(adb, "-e", "command", "network", "-e", "mobile", "hide")
    # Die Ladeanzeige blendet der Pixel-7-Emulator in dieser Betriebsart ohnehin aus; der Befehl
    # bleibt trotzdem stehen, damit ein Gerät, das sie zeigt, wenigstens immer dasselbe zeigt.
    _demo(adb, "-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false")
    _demo(adb, "-e", "command", "notifications", "-e", "visible", "false")


def leiste_zurueck(adb):
    _demo(adb, "-e", "command", "exit")


def leiste_zurueck_beim_ende(adb):
    """Sorgt dafür, dass die Vorführ-Leiste auch dann verschwindet, wenn das Fenster nicht über
    seinen Schließen-Knopf endet – abgeschossen, abgestürzt oder mit Strg-C im Terminal. Sonst
    steht der Emulator hinterher auf ewig 12:00 und man sucht den Grund woanders."""
    erledigt = threading.Event()

    def aufraeumen(*_):
        if not erledigt.is_set():
            erledigt.set()
            try:
                leiste_zurueck(adb)
            except Exception:
                pass

    atexit.register(aufraeumen)
    for zeichen in (signal.SIGTERM, signal.SIGINT):
        vorher = signal.getsignal(zeichen)
        signal.signal(zeichen, lambda s, r, alt=vorher: (aufraeumen(), os._exit(0)))
    return aufraeumen


# ------------------------------------------------------------------ Bild
def png_groesse(daten):
    """Breite und Höhe aus dem IHDR – ohne das Bild erst zu entpacken."""
    if len(daten) < 24 or daten[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", daten[16:24])


class Bearbeitung:
    """Rohaufnahme plus eine Liste von Schritten.

    Das Rohbild bleibt unangetastet, jeder Zuschnitt und jede Markierung ist nur ein Eintrag in
    der Liste. Dadurch ist Zurücknehmen geschenkt, und ein zu enger Zuschnitt lässt sich
    aufmachen, statt die Ansicht neu herstellen zu müssen."""

    def __init__(self, roh):
        self.roh = roh
        self.schritte = []

    def hinzu(self, art, kasten):
        self.schritte.append((art, kasten))

    def zurueck(self):
        if self.schritte:
            self.schritte.pop()

    def von_vorn(self):
        self.schritte.clear()

    def bild(self):
        werk = self.roh.copy()
        for art, (x0, y0, x1, y1) in self.schritte:
            if art == "zuschneiden":
                werk = werk.crop((int(x0), int(y0), int(x1), int(y1)))
                continue
            # Strichstärke am Bild ausrichten, damit der Kreis auf einem zugeschnittenen
            # Ausschnitt nicht plötzlich klobig wirkt.
            stift = max(3, round(werk.width / 180))
            stift_zeichner = ImageDraw.Draw(werk)
            if art == "kreis":
                stift_zeichner.ellipse((x0, y0, x1, y1), outline=ROT, width=stift)
            else:
                stift_zeichner.rectangle((x0, y0, x1, y1), outline=ROT, width=stift)
        return werk


def einpassen(bild, hoehe=ANZEIGE_HOEHE):
    """Verkleinert auf die Anzeigehöhe, Seitenverhältnis bleibt."""
    faktor = hoehe / bild.height
    return bild.resize((max(1, round(bild.width * faktor)), hoehe), Image.LANCZOS), faktor


# ------------------------------------------------------------------ Fenster
class Fenster:
    def __init__(self, wurzel, args):
        self.wurzel = wurzel
        self.args = args
        self.sprache = tk.StringVar(value=args.lang)
        self.adb = None
        self.bearbeitung = None
        self.werkzeug = tk.StringVar(value="zuschneiden")
        self.live_an = tk.BooleanVar(value=True)
        self.live_bild = None          # letzte Aufnahme des Live-Blicks als PIL-Bild
        self.warteschlange = queue.Queue(maxsize=1)
        self.anzeigen = {}             # Tk-Bilder festhalten, sonst räumt Tk sie weg
        self.faktor_neu = 1.0
        self.zieh_start = None
        self.zieh_form = None
        self.bestaende = []            # Pfade hinter den Namen im Auswahlfeld

        self._aufbauen()
        self._sprache_uebernehmen()
        self.wurzel.after(200, self._geraet_suchen)
        self.wurzel.after(100, self._live_abholen)
        self.wurzel.protocol("WM_DELETE_WINDOW", self._schliessen)

    # -------------------------------------------------- Aufbau
    def _aufbauen(self):
        kopf = ttk.Frame(self.wurzel, padding=(8, 6))
        kopf.pack(fill="x")
        self.geraet_text = ttk.Label(kopf, text="Gerät: –")
        self.geraet_text.pack(side="left")

        # Der Bestand bestimmt, was auf den Bildern steht. Für den englischen und den spanischen Satz
        # braucht es einen Bestand in der jeweiligen Sprache; deshalb ist er hier zu wählen und nicht
        # auf eine Datei festgelegt. Angezeigt wird nur der Dateiname – der ganze Pfad sprengte die
        # Zeile und steht ohnehin im Hinweis unter dem Feld.
        ttk.Separator(kopf, orient="vertical").pack(side="left", fill="y", padx=12)
        ttk.Label(kopf, text="Bestand:").pack(side="left")
        self.bestand_feld = ttk.Combobox(kopf, width=14, state="readonly")
        self.bestand_feld.pack(side="left", padx=4)
        ttk.Button(kopf, text="Einspielen", command=self._bestand).pack(side="left", padx=(6, 12))
        ttk.Checkbutton(kopf, text="Live-Blick", variable=self.live_an).pack(side="left")

        ttk.Separator(kopf, orient="vertical").pack(side="left", fill="y", padx=12)
        for wert, beschriftung in (("de", "Deutsch"), ("en", "English")):
            ttk.Radiobutton(kopf, text=beschriftung, value=wert, variable=self.sprache,
                            command=self._sprache_uebernehmen).pack(side="left", padx=3)
        self.bau_knopf = ttk.Button(kopf, text="Handbuch erstellen", command=self._handbuch_bauen)
        self.bau_knopf.pack(side="left", padx=12)

        mitte = ttk.Frame(self.wurzel, padding=(8, 0))
        mitte.pack(fill="both", expand=True)

        # Spalte 0: die Liste aus dem Handbuch
        links = ttk.Frame(mitte)
        links.pack(side="left", fill="y", padx=(0, 10))
        ttk.Label(links, text="Bilder im Handbuch").pack(anchor="w")
        self.liste = tk.Listbox(links, width=42, height=30, exportselection=False,
                                font=("TkDefaultFont", 9))
        self.liste.pack(side="left", fill="y")
        rolle = ttk.Scrollbar(links, orient="vertical", command=self.liste.yview)
        rolle.pack(side="left", fill="y")
        self.liste.config(yscrollcommand=rolle.set)
        self.liste.bind("<<ListboxSelect>>", self._gewaehlt)

        self.alt_flaeche = self._spalte(mitte, "Alt (im Verzeichnis)")
        self.live_flaeche = self._spalte(mitte, "Live (Emulator)")
        self.neu_flaeche = self._spalte(mitte, "Neu (bearbeitbar)")

        self.neu_flaeche.bind("<ButtonPress-1>", self._zieh_beginn)
        self.neu_flaeche.bind("<B1-Motion>", self._zieh_laeuft)
        self.neu_flaeche.bind("<ButtonRelease-1>", self._zieh_ende)

        werkzeuge = ttk.Frame(self.wurzel, padding=(8, 6))
        werkzeuge.pack(fill="x")
        ttk.Button(werkzeuge, text="Aufnehmen", command=self._aufnehmen).pack(side="left")
        ttk.Separator(werkzeuge, orient="vertical").pack(side="left", fill="y", padx=10)
        for wert, beschriftung in (("zuschneiden", "Ausschnitt"), ("kreis", "Roter Kreis"),
                                   ("rahmen", "Roter Rahmen")):
            ttk.Radiobutton(werkzeuge, text=beschriftung, value=wert,
                            variable=self.werkzeug).pack(side="left", padx=4)
        ttk.Button(werkzeuge, text="Schritt zurück", command=self._zurueck).pack(side="left", padx=(12, 4))
        ttk.Button(werkzeuge, text="Von vorn", command=self._von_vorn).pack(side="left")
        ttk.Button(werkzeuge, text="Speichern", command=self._speichern).pack(side="left", padx=16)

        self.meldung = ttk.Label(self.wurzel, text="", padding=(8, 4), foreground="#2e7d32")
        self.meldung.pack(fill="x")
        self.unterschrift = ttk.Label(self.wurzel, text="", padding=(8, 0), wraplength=1100,
                                      foreground="#555555")
        self.unterschrift.pack(fill="x")

    def _spalte(self, eltern, titel):
        rahmen = ttk.Frame(eltern)
        rahmen.pack(side="left", fill="both", expand=True, padx=4)
        ttk.Label(rahmen, text=titel).pack(anchor="w")
        flaeche = tk.Canvas(rahmen, width=300, height=ANZEIGE_HOEHE, background="#e8e8e8",
                            highlightthickness=1, highlightbackground="#bbbbbb")
        flaeche.pack(fill="both", expand=True)
        return flaeche

    def _liste_fuellen(self):
        blick = self.liste.curselection()
        self.liste.delete(0, tk.END)
        for name, _ in self.bilder:
            da = "✓" if os.path.isfile(os.path.join(self.ablage, name)) else "–"
            hand = " ✋" if name in VON_HAND else ""
            self.liste.insert(tk.END, f"{da} {name}{hand}")
        if blick:
            self.liste.selection_set(blick[0])

    def _melden(self, text):
        self.meldung.config(text=text)

    # -------------------------------------------------- Sprache und Handbuchbau
    def _sprache_uebernehmen(self):
        """Umschalten wechselt alles zugleich: die Liste kommt aus dem anderen Handbuch, abgelegt
        wird im zugehörigen Ordner, und der vorgeschlagene Bestand ist der passende. Beides
        auseinanderlaufen zu lassen wäre die sicherste Art, ein deutsches Bild im englischen Satz zu
        versenken – eingespielt wird der Bestand aber erst auf Knopfdruck, denn das wirft den Stand
        im Emulator weg."""
        self._bestaende_fuellen()
        self.handbuch, self.ablage_rel = HANDBUCH[self.sprache.get()]
        self.ablage = os.path.join(REPO, self.ablage_rel)
        self.bilder = bilder_aus_handbuch(os.path.join(REPO, self.handbuch))
        self.wurzel.title(f"Handbuchbilder – {self.handbuch} → {self.ablage_rel}/")
        self.bearbeitung = None
        self._liste_fuellen()
        self._leeren(self.alt_flaeche, "Links ein Bild wählen.")
        self._leeren(self.neu_flaeche, "Noch nichts aufgenommen.")
        self.unterschrift.config(text="")
        self._melden(f"{self.handbuch} · {len(self.bilder)} Bilder")

    def _handbuch_bauen(self):
        self.bau_knopf.config(state="disabled")
        self._melden(f"{self.handbuch} wird gebaut …")
        threading.Thread(target=self._bau_faden, args=(self.handbuch,), daemon=True).start()

    def _bau_faden(self, handbuch):
        lauf = subprocess.run([sys.executable, os.path.join(REPO, handbuch)],
                              capture_output=True, text=True, cwd=REPO)
        if lauf.returncode == 0:
            text = (lauf.stdout.strip().splitlines() or ["fertig"])[-1]
        else:
            # Nur die letzte Zeile: bei reportlab steht die eigentliche Ursache ganz unten.
            text = "Fehlgeschlagen: " + (lauf.stderr.strip().splitlines() or ["?"])[-1]
        self.wurzel.after(0, self._bau_fertig, text)

    def _bau_fertig(self, text):
        self.bau_knopf.config(state="normal")
        self._melden(text)

    # -------------------------------------------------- Gerät und Live-Blick
    def _geraet_suchen(self):
        if self.args.geraet:
            if self.args.geraet not in geraete():
                self._melden(f"Gerät {self.args.geraet} hängt nicht dran.")
                return
            self._geraet_uebernehmen(self.args.geraet)
            return

        # Ohne Angabe nur Emulatoren: auf einem echten Gerät stehen die eigenen Daten, und die
        # Bildgröße wäre eine andere als bei den vorhandenen Aufnahmen.
        emus = [s for s in geraete() if s.startswith("emulator-")]
        if len(emus) == 1:
            self._geraet_uebernehmen(emus[0])
            return
        if len(emus) > 1:
            self._melden("Mehrere Emulatoren laufen – bitte mit --geraet SERIAL auswählen.")
            return
        if messagebox.askyesno("Kein Emulator", f"Kein Emulator angeschlossen. {AVD} starten?"):
            threading.Thread(target=self._emulator_faden, daemon=True).start()
        else:
            self._melden("Ohne Emulator lässt sich nichts aufnehmen.")

    def _emulator_faden(self):
        serial = emulator_starten(lambda t: self.wurzel.after(0, self._melden, t))
        if serial:
            self.wurzel.after(0, self._geraet_uebernehmen, serial)
        else:
            self.wurzel.after(0, self._melden, "Der Emulator ist nicht rechtzeitig hochgefahren.")

    def _geraet_uebernehmen(self, serial):
        self.adb = Adb(serial)
        self.geraet_text.config(text=f"Gerät: {serial}")
        self._melden("Bereit.")
        if not self.args.rohe_leiste:
            leiste_aufraeumen(self.adb)
            self.leiste_aufraeumen_rueckgaengig = leiste_zurueck_beim_ende(self.adb)
        threading.Thread(target=self._live_faden, daemon=True).start()

    def _live_faden(self):
        """Holt in Ruhe im Hintergrund Bildschirmfotos; das Fenster nimmt sie sich, wenn es mag."""
        while True:
            time.sleep(1.2)
            if self.adb is None or not self.live_an.get():
                continue
            try:
                daten = self.adb.bild()
                if self.warteschlange.full():
                    self.warteschlange.get_nowait()
                self.warteschlange.put_nowait(daten)
            except Exception:
                pass   # ein verpasstes Live-Bild ist belanglos, der nächste Versuch kommt gleich

    def _live_abholen(self):
        try:
            daten = self.warteschlange.get_nowait()
        except queue.Empty:
            daten = None
        if daten:
            import io
            self.live_bild = Image.open(io.BytesIO(daten)).convert("RGB")
            self._zeigen(self.live_flaeche, self.live_bild, "live")
        self.wurzel.after(300, self._live_abholen)

    # -------------------------------------------------- Anzeige
    def _zeigen(self, flaeche, bild, schluessel):
        klein, faktor = einpassen(bild)
        self.anzeigen[schluessel] = ImageTk.PhotoImage(klein)
        flaeche.delete("all")
        breite = max(flaeche.winfo_width(), klein.width)
        flaeche.create_image(breite // 2, 0, anchor="n", image=self.anzeigen[schluessel])
        flaeche.bildbreite = klein.width
        flaeche.rand = (breite - klein.width) // 2
        return faktor

    def _leeren(self, flaeche, hinweis):
        flaeche.delete("all")
        flaeche.create_text(150, 40, text=hinweis, fill="#888888", width=260)

    def _gewaehlt(self, _ereignis=None):
        name, unterschrift = self._aktuelles()
        if name is None:
            return
        self.unterschrift.config(text=unterschrift + (f"   ({VON_HAND[name]})"
                                                      if name in VON_HAND else ""))
        pfad = os.path.join(self.ablage, name)
        if os.path.isfile(pfad):
            self._zeigen(self.alt_flaeche, Image.open(pfad).convert("RGB"), "alt")
        else:
            self._leeren(self.alt_flaeche, "Gibt es noch nicht – dies wird das erste Bild.")
        # Eine begonnene Bearbeitung gehört zum vorigen Namen; beim Wechsel gibt es sie nicht mehr.
        self.bearbeitung = None
        self._leeren(self.neu_flaeche, "Noch nichts aufgenommen.")

    def _aktuelles(self):
        blick = self.liste.curselection()
        if not blick:
            self._melden("Erst links ein Bild wählen.")
            return None, None
        return self.bilder[blick[0]]

    # -------------------------------------------------- Aufnehmen und Bearbeiten
    def _aufnehmen(self):
        if self.adb is None:
            self._melden("Kein Gerät.")
            return
        if self._aktuelles()[0] is None:
            return
        try:
            daten = self.adb.bild()
        except subprocess.CalledProcessError as fehler:
            self._melden(f"Aufnahme fehlgeschlagen: {fehler}")
            return
        masse = png_groesse(daten)
        import io
        self.bearbeitung = Bearbeitung(Image.open(io.BytesIO(daten)).convert("RGB"))
        self._neu_zeichnen()
        if masse and tuple(masse) != SOLL_GROESSE:
            self._melden(f"Aufgenommen – aber {masse[0]}×{masse[1]} statt "
                         f"{SOLL_GROESSE[0]}×{SOLL_GROESSE[1]}. Anderes Gerät?")
        else:
            self._melden("Aufgenommen. Jetzt zuschneiden oder markieren – oder gleich speichern.")

    def _neu_zeichnen(self):
        if self.bearbeitung is None:
            return
        self.faktor_neu = self._zeigen(self.neu_flaeche, self.bearbeitung.bild(), "neu")

    def _zieh_beginn(self, ereignis):
        if self.bearbeitung is None:
            return
        self.zieh_start = (ereignis.x, ereignis.y)

    def _zieh_laeuft(self, ereignis):
        if self.zieh_start is None:
            return
        if self.zieh_form is not None:
            self.neu_flaeche.delete(self.zieh_form)
        x0, y0 = self.zieh_start
        werkzeug = self.werkzeug.get()
        if werkzeug == "kreis":
            self.zieh_form = self.neu_flaeche.create_oval(x0, y0, ereignis.x, ereignis.y,
                                                          outline="#d61414", width=2)
        else:
            # Der Ausschnitt wird gestrichelt vorgezeichnet, der Rahmen durchgezogen – so ist
            # beim Ziehen zu sehen, ob gerade geschnitten oder markiert wird.
            zusatz = {"dash": (4, 3)} if werkzeug == "zuschneiden" else {}
            self.zieh_form = self.neu_flaeche.create_rectangle(x0, y0, ereignis.x, ereignis.y,
                                                               outline="#d61414", width=2,
                                                               **zusatz)

    def _zieh_ende(self, ereignis):
        if self.zieh_start is None or self.bearbeitung is None:
            return
        x0, y0 = self.zieh_start
        x1, y1 = ereignis.x, ereignis.y
        self.zieh_start = None
        if self.zieh_form is not None:
            self.neu_flaeche.delete(self.zieh_form)
            self.zieh_form = None
        if abs(x1 - x0) < 5 or abs(y1 - y0) < 5:
            return

        # Vom Fenster zurück ins Bild rechnen: die Anzeige ist verkleinert und mittig gesetzt.
        rand = getattr(self.neu_flaeche, "rand", 0)
        def zurueck(x, y):
            return ((x - rand) / self.faktor_neu, y / self.faktor_neu)
        (bx0, by0), (bx1, by1) = zurueck(x0, y0), zurueck(x1, y1)
        kasten = (min(bx0, bx1), min(by0, by1), max(bx0, bx1), max(by0, by1))

        bild = self.bearbeitung.bild()
        kasten = (max(0, kasten[0]), max(0, kasten[1]),
                  min(bild.width, kasten[2]), min(bild.height, kasten[3]))
        self.bearbeitung.hinzu(self.werkzeug.get(), kasten)
        self._neu_zeichnen()

    def _zurueck(self):
        if self.bearbeitung:
            self.bearbeitung.zurueck()
            self._neu_zeichnen()

    def _von_vorn(self):
        if self.bearbeitung:
            self.bearbeitung.von_vorn()
            self._neu_zeichnen()

    # -------------------------------------------------- Speichern
    def _speichern(self):
        name, _ = self._aktuelles()
        if name is None:
            return
        if self.bearbeitung is None:
            self._melden("Erst aufnehmen.")
            return
        # Die Rohaufnahmen liegen je Sprache getrennt: die Dateinamen sind in beiden Sätzen
        # dieselben, ein gemeinsamer Ordner würde das deutsche Bild vom englischen überschreiben.
        roh = os.path.join(ROH, self.sprache.get())
        os.makedirs(self.ablage, exist_ok=True)
        os.makedirs(roh, exist_ok=True)
        self.bearbeitung.bild().save(os.path.join(self.ablage, name))
        self.bearbeitung.roh.save(os.path.join(roh, name))
        self._liste_fuellen()
        self._melden(f"Gespeichert: {self.ablage_rel}/{name}   ·   roh in "
                     f"build/screenshots-roh/{self.sprache.get()}/")

    # -------------------------------------------------- Datenstand
    def _bestaende_fuellen(self):
        """Stellt die beiden Bestände ein und wählt den zur Sprache passenden vor."""
        beschriftungen = []
        for name, pfad in BESTAENDE:
            beschriftungen.append(name if os.path.isfile(pfad) else f"{name} (fehlt)")
        self.bestand_feld["values"] = beschriftungen
        self.bestand_feld.current(1 if self.sprache.get() == "en" else 0)

    def _bestand_pfad(self):
        blick = self.bestand_feld.current()
        return BESTAENDE[blick][1] if 0 <= blick < len(BESTAENDE) else ""

    def _bestand(self):
        if self.adb is None:
            self._melden("Kein Gerät.")
            return
        quelle = self._bestand_pfad()
        if not os.path.isfile(quelle):
            self._melden(f"{quelle} gibt es nicht – erst tools/bestand_aufbereiten.py laufen lassen.")
            return
        if not messagebox.askyesno("Datenstand",
                                   f"Diesen Bestand einspielen?\n\n{quelle}\n\n"
                                   "Der bisherige Stand im Emulator geht dabei verloren."):
            return
        bestand_einspielen(self.adb, quelle, self._melden)

    # -------------------------------------------------- Ende

    def _schliessen(self):
        # Die Statusleiste stellt leiste_zurueck_beim_ende zurück – das greift auch dann, wenn
        # dieser Weg gar nicht genommen wird.
        self.wurzel.destroy()


def main():
    p = argparse.ArgumentParser(description="Handbuchbilder am Emulator aufnehmen.")
    p.add_argument("--lang", choices=("de", "en"), default="de",
                   help="welches Handbuch die Liste vorgibt und wohin abgelegt wird")
    p.add_argument("--geraet", help="Seriennummer; ohne Angabe der laufende Emulator")
    p.add_argument("--rohe-leiste", action="store_true",
                   help="Statusleiste unangetastet lassen (echte Uhrzeit, echte Symbole)")
    p.add_argument("--emulator-starten", action="store_true",
                   help="nur den Emulator hochfahren, Seriennummer ausgeben und beenden "
                        "(für screenshots.sh, damit der AVD-Name nur hier steht)")
    args = p.parse_args()

    if args.emulator_starten:
        # Ohne Fenster: Das Shell-Skript braucht vor dem Installieren ein Gerät und soll die
        # Wartelogik nicht ein zweites Mal mitbringen.
        laeuft = [s for s in geraete() if s.startswith("emulator-")]
        serial = laeuft[0] if laeuft else emulator_starten(lambda t: print(t, file=sys.stderr))
        if not serial:
            print(f"{AVD} ist nicht rechtzeitig hochgefahren.", file=sys.stderr)
            return 1
        print(serial)
        return 0

    wurzel = tk.Tk()
    try:
        wurzel.tk.call("tk", "scaling", 1.2)
    except tk.TclError:
        pass
    Fenster(wurzel, args)
    wurzel.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
