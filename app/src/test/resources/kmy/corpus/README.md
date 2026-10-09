# KMyMoney-Testdateien

Die `*.xml`-Dateien in diesem Ordner sind unveränderte Kopien der Testdaten des KMyMoney-Projekts.
`KmyCorpusTest` lässt Lesen, Schreiben und Löschen gegen jede von ihnen laufen.

## Herkunft

- Projekt: KMyMoney, <https://invent.kde.org/office/kmymoney> (Spiegel: <https://github.com/KDE/kmymoney>)
- Ordner dort: `kmymoney/plugins/views/reports/core/tests/data/`
- Stand: Commit `d99cfc2ab72a0fde5e9ba5e3b5a3bc04cc688a86` vom 22.08.2026

Die Dateien stammen überwiegend aus Fehlerberichten (die Nummer im Namen ist die des Berichts bei
bugs.kde.org) und decken unter anderem mehrere Währungen, Depots, Budgets und Kredite ab.

## Lizenz

KMyMoney steht unter der GNU General Public License, Version 2 oder später (GPL-2.0-or-later). Die
Testdateien tragen dort keinen eigenen Lizenzvermerk und werden hier unter derselben Lizenz
weitergegeben. KMySync steht unter der GPL-3.0; die Klausel „oder später" macht beides vereinbar.

Das Urheberrecht liegt bei den Autoren von KMyMoney.

## Aktualisieren

Die Dateien aus dem genannten Ordner erneut hierher kopieren und oben den Commit nachtragen. Ein
zusätzlicher eigener Ordner lässt sich ohne Kopieren mitprüfen:

    ./gradlew testFossDebugUnitTest --tests '*KmyCorpusTest*' -Dkmy.corpus=/pfad/zum/ordner
