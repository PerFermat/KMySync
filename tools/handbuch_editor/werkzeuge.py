# -*- coding: utf-8 -*-
"""Die vorhandenen Skripte aus dem Editor heraus starten.

Der Editor baut selbst nichts – er ruft die Werkzeuge auf, die es im Projekt schon gibt, und
zeigt deren Ausgabe zeilenweise mit. Deshalb ein Unterprozess und kein Import: build_manual.py
ist ein Skript, das beim Import losbaut, und screenshots.py bringt eine eigene Oberfläche mit.
"""
from __future__ import annotations

import os
import subprocess
import sys

from PySide6.QtCore import QProcess, Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import QDialog, QPlainTextEdit, QPushButton, QVBoxLayout

#: Die beiden PDFs, so wie manuel_de.py / manuel_en.py sie benennen.
PDF_NAME = {"de": "Handbuch-KMySync-de", "en": "Manual-KMySync-en"}


def system_python() -> str:
    """Das Python des Systems, nicht das des Editors.

    Der Editor lebt in einer eigenen Umgebung, in der nur PySide6 steckt. reportlab (für das PDF)
    und PIL/tkinter (für den Aufnahmehelfer) stecken im System-Python – mit sys.executable liefe
    beides in einen ImportError. Den richtigen Weg reicht das Startskript herein.
    """
    return os.environ.get("HANDBUCH_EDITOR_SYSTEM_PYTHON") or "python3"


def oeffne_datei(pfad: str) -> None:
    """Eine Datei dem System zum Anzeigen überlassen."""
    if not os.path.isfile(pfad):
        return
    if sys.platform.startswith("win"):
        os.startfile(pfad)                                    # noqa: S606 – Windows-Weg
    elif sys.platform == "darwin":
        subprocess.Popen(["open", pfad])
    else:
        subprocess.Popen(["xdg-open", pfad])


class Logfenster(QDialog):
    """Zeigt mit, was ein Unterprozess ausgibt, und meldet, wenn er fertig ist."""

    fertig = Signal(int)

    def __init__(self, eltern, titel: str):
        super().__init__(eltern)
        self.setWindowTitle(titel)
        self.resize(760, 420)
        self.ausgabe = QPlainTextEdit()
        self.ausgabe.setReadOnly(True)
        self.ausgabe.setFont(QFont("monospace"))
        self.schliessen = QPushButton("Schließen")
        self.schliessen.clicked.connect(self.accept)
        self.schliessen.setEnabled(False)
        aufbau = QVBoxLayout(self)
        aufbau.addWidget(self.ausgabe)
        aufbau.addWidget(self.schliessen, alignment=Qt.AlignRight)
        self._auftraege: list[tuple[list[str], str]] = []
        self._prozess: QProcess | None = None
        self._letzter_stand = 0

    def starte(self, auftraege: list[tuple[list[str], str]], arbeitsordner: str) -> None:
        """Eine Reihe von Aufrufen nacheinander abarbeiten – jeweils (Befehl, Überschrift)."""
        self._auftraege = list(auftraege)
        self._ordner = arbeitsordner
        self._naechster()

    def _naechster(self) -> None:
        if not self._auftraege:
            self.schliessen.setEnabled(True)
            self.fertig.emit(self._letzter_stand)
            return
        befehl, ueberschrift = self._auftraege.pop(0)
        self.ausgabe.appendPlainText(f"$ {ueberschrift}\n  {' '.join(befehl)}")
        self._prozess = QProcess(self)
        self._prozess.setWorkingDirectory(self._ordner)
        self._prozess.setProcessChannelMode(QProcess.MergedChannels)
        self._prozess.readyReadStandardOutput.connect(self._mitlesen)
        self._prozess.finished.connect(self._beendet)
        self._prozess.errorOccurred.connect(
            lambda _f: self.ausgabe.appendPlainText("  … konnte nicht gestartet werden"))
        self._prozess.start(befehl[0], befehl[1:])

    def _mitlesen(self) -> None:
        roh = bytes(self._prozess.readAllStandardOutput()).decode("utf-8", "replace")
        for zeile in roh.splitlines():
            self.ausgabe.appendPlainText("  " + zeile)

    def _beendet(self, stand: int, _status) -> None:
        self._letzter_stand = stand
        self.ausgabe.appendPlainText(f"  → beendet mit {stand}\n")
        self._naechster()


def handbuch_befehl(repo: str, sprache: str) -> tuple[list[str], str]:
    return ([system_python(), os.path.join("docs", "build_manual.py"), PDF_NAME[sprache], sprache],
            f"Handbuch {sprache.upper()} erzeugen")


def pdf_pfad(repo: str, sprache: str) -> str:
    return os.path.join(repo, "docs", PDF_NAME[sprache] + ".pdf")


def bilder_befehl(repo: str, sprache: str) -> tuple[list[str], str]:
    return ([system_python(), os.path.join("tools", "screenshots.py"), "--lang", sprache],
            f"Bilder erzeugen ({sprache.upper()})")
