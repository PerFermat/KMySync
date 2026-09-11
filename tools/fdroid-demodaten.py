#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Erzeugt aus dem anonymisierten Bestand eine übersetzte Kopie je Sprache.

    tools/fdroid-demodaten.py en demo-en.db

Übersetzt werden Kontonamen, Kategorien und die Empfänger, die in den Store-Bildern zu sehen sind.
Die Kategorien sind hierarchisch (»Fahrtkosten:Auto:Benzin«); übersetzt wird deshalb Segment für
Segment – so bleibt jede Kombination von selbst stimmig, und ein neues Segment fällt beim Prüfen auf,
statt still deutsch zu bleiben.

Die Wertpapiernamen sind im Bestand bereits englisch (»Pioneer World Equity Fund«) und bleiben, wie
sie sind.
"""
import os
import re
import shutil
import sqlite3
import sys

# ---------------------------------------------------------------- Kontonamen
KONTEN = {
    "Bargeld":                             ("Cash", "Efectivo"),
    "Bausparkonto":                        ("Home savings account", "Cuenta ahorro vivienda"),
    "Bausparvertrag":                      ("Home savings plan", "Plan ahorro vivienda"),
    "Depot":                               ("Portfolio", "Cartera"),
    "Festgeld":                            ("Fixed deposit", "Depósito a plazo"),
    "Geschäftsanteile Bank West":          ("Shares West Bank", "Participaciones Banco Oeste"),
    "Girokonto":                           ("Current account", "Cuenta corriente"),
    "Girokonto Alt":                       ("Current account (old)", "Cuenta corriente antigua"),
    "Kredit Rolf":                         ("Loan to Rolf", "Préstamo a Rolf"),
    "Kreditkarte 1":                       ("Credit card 1", "Tarjeta de crédito 1"),
    "Kreditkarte 2":                       ("Credit card 2", "Tarjeta de crédito 2"),
    "S-Cash":                              ("S-Cash", "S-Cash"),
    "Scala Plus":                          ("Scala Plus", "Scala Plus"),
    "Sparkonto Bank Ost":                  ("Savings East Bank", "Ahorro Banco Este"),
    "Sparplan Bank West":                  ("Savings plan West Bank", "Plan ahorro Banco Oeste"),
    "Tagesgeld Bank Nord":                 ("Call money North Bank", "Cuenta remunerada Banco Norte"),
    "Tagesgeld Bank Ost":                  ("Call money East Bank", "Cuenta remunerada Banco Este"),
    "Tagesgeld Bank Süd":                  ("Call money South Bank", "Cuenta remunerada Banco Sur"),
    "Wohnungsbaufinanzierung 15000":       ("Home loan 15000", "Préstamo vivienda 15000"),
    "Wohnungsbaufinanzierung 25000":       ("Home loan 25000", "Préstamo vivienda 25000"),
    "Zwischenfinanzierung Bausparvertrag": ("Bridge loan home savings", "Financiación puente vivienda"),
}

# ------------------------------------------------- Segmente der Kategoriebäume
SEGMENTE = {
    "ADAC":                          ("Auto club", "Club automovilístico"),
    "Abfall":                        ("Waste", "Basuras"),
    "Abgaben":                       ("Duties", "Tasas"),
    "Abheben":                       ("Cash withdrawal", "Retirada de efectivo"),
    "Ausgehen":                      ("Going out", "Salidas"),
    "Auto":                          ("Car", "Coche"),
    "Bahn":                          ("Rail", "Tren"),
    "Bankgebühren":                  ("Bank fees", "Comisiones bancarias"),
    "Bauspardarlehen":               ("Home savings loan", "Préstamo ahorro vivienda"),
    "Bausparvertrag":                ("Home savings plan", "Plan ahorro vivienda"),
    "Benzin":                        ("Fuel", "Combustible"),
    "Berufsunfähigkeitsversicherung": ("Disability insurance", "Seguro de incapacidad"),
    "Bildung":                       ("Education", "Educación"),
    "Bücher":                        ("Books", "Libros"),
    "Büroartikel":                   ("Office supplies", "Material de oficina"),
    "Computer":                      ("Computer", "Informática"),
    "DVD":                           ("DVD", "DVD"),
    "Darlehen 15000":                ("Loan 15000", "Préstamo 15000"),
    "Darlehen 25000":                ("Loan 25000", "Préstamo 25000"),
    "Darlehen 75000":                ("Loan 75000", "Préstamo 75000"),
    "Dividende":                     ("Dividend", "Dividendo"),
    "Elektronik":                    ("Electronics", "Electrónica"),
    "Eltern":                        ("Parents", "Padres"),
    "Fahrtkosten":                   ("Travel", "Transporte"),
    "Fernsehen":                     ("Television", "Televisión"),
    "Festgeld":                      ("Fixed deposit", "Depósito a plazo"),
    "Gehalt":                        ("Salary", "Salario"),
    "Geschenke":                     ("Gifts", "Regalos"),
    "Geschäft":                      ("Business", "Negocio"),
    "Gesundheit":                    ("Health", "Salud"),
    "Gewinnspiel":                   ("Lottery", "Sorteos"),
    "Girokonto":                     ("Current account", "Cuenta corriente"),
    "Grundschuld":                   ("Mortgage charge", "Carga hipotecaria"),
    "Grundsteuer":                   ("Property tax", "Impuesto sobre bienes"),
    "Haare":                         ("Hair", "Peluquería"),
    "Haftpflicht":                   ("Liability", "Responsabilidad civil"),
    "Handy":                         ("Mobile", "Móvil"),
    "Hausgeld":                      ("Service charge", "Gastos de comunidad"),
    "Kantine":                       ("Canteen", "Comedor"),
    "Kapitalertragssteuer":          ("Capital gains tax", "Impuesto plusvalías"),
    "Kauf":                          ("Purchase", "Compra"),
    "Kleidung":                      ("Clothing", "Ropa"),
    "Kosten":                        ("Costs", "Costes"),
    "Krankenzusatz":                 ("Supplementary health", "Salud complementaria"),
    "Kredit":                        ("Loan", "Préstamo"),
    "Körperpflege":                  ("Personal care", "Cuidado personal"),
    "Lebensmittel":                  ("Groceries", "Alimentación"),
    "Lose":                          ("Tickets", "Boletos"),
    "Medikamente":                   ("Medication", "Medicamentos"),
    "Musik/Kino":                    ("Music/Cinema", "Música/Cine"),
    "Möbel":                         ("Furniture", "Muebles"),
    "Münzen":                        ("Coins", "Monedas"),
    "Nebenkosten":                   ("Utilities", "Suministros"),
    "Parkgebühren":                  ("Parking", "Aparcamiento"),
    "Praxisgebühr":                  ("Practice fee", "Tasa médica"),
    "Reifenwechsel":                 ("Tyre change", "Cambio de neumáticos"),
    "Reinigung":                     ("Cleaning", "Limpieza"),
    "Rente":                         ("Pension", "Pensión"),
    "Reparaturen":                   ("Repairs", "Reparaciones"),
    "Selbstbeteiligung":             ("Excess", "Franquicia"),
    "Solidarzuschlag":               ("Solidarity surcharge", "Recargo solidario"),
    "Sondertilgung":                 ("Extra repayment", "Amortización extra"),
    "Sonderzahlungen":               ("Bonus payments", "Pagas extra"),
    "Sonstige":                      ("Other", "Otros"),
    "Sonstiges":                     ("Other", "Otros"),
    "Sonstiges Bar":                 ("Other cash", "Otros en efectivo"),
    "Sparen":                        ("Savings", "Ahorro"),
    "Sparkonto":                     ("Savings account", "Cuenta de ahorro"),
    "Spenden":                       ("Donations", "Donativos"),
    "Spiele":                        ("Games", "Juegos"),
    "Sport":                         ("Sport", "Deporte"),
    "Steuer":                        ("Tax", "Impuesto"),
    "Steuern":                       ("Taxes", "Impuestos"),
    "Strafen":                       ("Fines", "Multas"),
    "Strom":                         ("Electricity", "Electricidad"),
    "Tagesgeld":                     ("Call money", "Cuenta remunerada"),
    "Telefon/Internet":              ("Phone/Internet", "Teléfono/Internet"),
    "Telekommunikation":             ("Telecoms", "Telecomunicaciones"),
    "Tilgung":                       ("Repayment", "Amortización"),
    "Unterhaltung":                  ("Entertainment", "Ocio"),
    "VL":                            ("Employer savings", "Ahorro de empresa"),
    "Versicherungen":                ("Insurance", "Seguros"),
    "Wohnen":                        ("Housing", "Vivienda"),
    "Zeitungen":                     ("Newspapers", "Periódicos"),
    "Zinsen":                        ("Interest", "Intereses"),
}

# ------------------------------- Empfänger, die in den Bildern wirklich stehen
# Personennamen bleiben, wie sie sind – die gibt es in jeder Sprache. Übersetzt werden die deutschen
# Gattungswörter und Rechtsformen, an denen ein fremdsprachiger Betrachter sofort hängenbleibt.
EMPFAENGER = {
    "Frau Sabine Rosemann":  ("Ms Sabine Rosemann", "Sra. Sabine Rosemann"),
    "Bäckerei Krustengold":  ("Krustengold Bakery", "Panadería Krustengold"),
    "GrünMarkt":             ("GreenMarket", "MercadoVerde"),
    "Pruschke e.G.":         ("Pruschke Co-op", "Pruschke Coop."),
    "Oderwald KG":           ("Oderwald Ltd", "Oderwald S.L."),
    "NahKauf":               ("NearBuy", "CompraCerca"),
    "Stroh AG & Co. KGaA":   ("Stroh PLC", "Stroh S.A."),
    "Metzgerei Grillhelden": ("Grillhelden Butchers", "Carnicería Grillhelden"),
    "Pizzeria Pizzeria Roma Mia": ("Pizzeria Roma Mia", "Pizzería Roma Mia"),
    "Gehringer GmbH":        ("Gehringer Ltd", "Gehringer S.L."),
    "Freudenberger AG":      ("Freudenberger PLC", "Freudenberger S.A."),
    "Faust GmbH & Co. KG":   ("Faust Ltd", "Faust S.L."),
}

# Wortanfänge in Buchungsnotizen. Sie stehen in der Liste unter dem Empfängernamen und sind das
# Letzte, was noch deutsch wirkt.
NOTIZEN = {
    "WP.ABRECHNUNG":    ("SEC.STATEMENT", "LIQUIDACIÓN VAL."),
    "Zins/Dividende":   ("Interest/Dividend", "Interés/Dividendo"),
    "Rateneinzug":      ("Instalment", "Cuota"),
    "LOHN/GEHALT":      ("SALARY", "NÓMINA"),
    "ABSCHLUSS":        ("STATEMENT", "CIERRE"),
    "Firmenticket":     ("Company travel pass", "Abono de empresa"),
    "Sparen":           ("Savings", "Ahorro"),
    "Kauf":             ("Buy", "Compra"),
    "Verkauf":          ("Sell", "Venta"),
}

SPALTE = 0  # wird aus der Sprache gesetzt: 0 = en, 1 = es


def wort(tabelle, wert):
    """Übersetzt einen Einzelwert; unbekannte bleiben unverändert stehen."""
    if SPALTE is None:                      # »de«: es gibt nichts zu übersetzen
        return wert
    eintrag = tabelle.get(wert)
    return eintrag[SPALTE] if eintrag else wert


def kategorie(wert):
    """»Fahrtkosten:Auto:Benzin« → »Travel:Car:Fuel«, Segment für Segment."""
    if not wert:
        return wert
    return ":".join(wort(SEGMENTE, teil) for teil in wert.split(":"))


def notiz(wert):
    """Bereinigt eine Buchungsnotiz.

    <b>Ortsangaben fliegen in jeder Sprache heraus</b> – auch in der deutschen. Im anonymisierten
    Bestand stehen in einigen Notizen noch echte Koordinaten auf zehn Meter genau; in einem Bild, das
    weltweit ausgeliefert wird, hat das nichts zu suchen. Danach die deutschen Bankbegriffe.
    """
    if not wert:
        return wert
    # Nicht nur zeilenweise: In einem Fall steht die Koordinate mitten im Text (»KI GPS: 48.66…«).
    # Deshalb zusätzlich jedes »GPS: Zahl, Zahl« herausschneiden, wo immer es steht.
    zeilen = [z for z in wert.split("\n") if not z.strip().startswith("GPS:")]
    text = "\n".join(zeilen)
    text = re.sub(r"\s*GPS:\s*-?\d+[.,]\d+\s*,\s*-?\d+[.,]\d+", "", text).strip()
    if SPALTE is not None:
        for deutsch, paar in NOTIZEN.items():
            text = text.replace(deutsch, paar[SPALTE])
    return text


def main():
    global SPALTE
    sprache, ziel = sys.argv[1], sys.argv[2]
    # »de« heißt: nichts übersetzen, aber die Ortsangaben trotzdem entfernen.
    SPALTE = {"en": 0, "es": 1, "de": None}[sprache]
    quelle = os.path.expanduser("~/Nextcloud/ausgaben-anonymisiert.db")
    shutil.copy(quelle, ziel)

    db = sqlite3.connect(ziel)
    offen = set()

    def ersetze(tabelle, spalte, umrechnen, sammeln=None):
        werte = [r[0] for r in db.execute(
            f"select distinct {spalte} from {tabelle} where {spalte} is not null and {spalte} <> ''")]
        for alt in werte:
            neu = umrechnen(alt)
            if neu != alt:
                db.execute(f"update {tabelle} set {spalte} = ? where {spalte} = ?", (neu, alt))
            elif sammeln is not None and alt in sammeln:
                offen.add(f"{tabelle}.{spalte}: {alt}")

    # Konten – überall dort, wo ein Kontoname als Text steht.
    for t, s in (("account", "name"), ("booking", "account"), ("booking", "transfer_account"),
                 ("place_entry", "account"), ("scheduled_transaction", "account"),
                 ("security", "depot"), ("security_tx", "depot")):
        ersetze(t, s, lambda v: wort(KONTEN, v), sammeln=set(KONTEN))

    # Kategorien
    for t, s in (("booking", "category"), ("booking_split", "category"), ("budget", "category"),
                 ("scheduled_split", "category"), ("category_type", "category")):
        ersetze(t, s, kategorie)

    # Empfänger
    for t, s in (("payee", "name"), ("booking", "payee"), ("scheduled_transaction", "payee")):
        ersetze(t, s, lambda v: wort(EMPFAENGER, v))

    # Notizen: erst die Ortsangaben heraus, dann die deutschen Bankbegriffe.
    ersetze("booking", "note", notiz)

    # Auch die gelernten Empfänger-Orte tragen Koordinaten – sie stehen in der Alias-Verwaltung und
    # gehören ebenso wenig in etwas, das veröffentlicht wird.
    # lat/lon sind NOT NULL – deshalb auf 0 setzen; die App wertet 0 als »kein Ort«.
    db.execute("update payee_correction set lat = 0, lon = 0, gps_list = ''")

    db.commit()

    # Gemeldet wird, was das Wörterbuch gar nicht kennt – nicht, was gleich geblieben ist: »Computer«
    # und »Sport« heißen auf Englisch nun einmal genauso, und ein Treffer darauf wäre ein Fehlalarm,
    # der die echten Lücken zudeckt.
    fehlend = set()
    if SPALTE is None:
        db.close()
        print(f"  {ziel}: fertig (nur Ortsangaben entfernt)")
        return
    for t, s in (("booking", "category"), ("budget", "category"), ("booking_split", "category")):
        for r in db.execute(f"select distinct {s} from {t} where {s} is not null and {s} <> ''"):
            for teil in r[0].split(":"):
                if teil not in SEGMENTE and teil not in {v[SPALTE] for v in SEGMENTE.values()}:
                    fehlend.add(teil)
    db.close()
    print(f"  {ziel}: fertig")
    if fehlend:
        print(f"  ACHTUNG, im Wörterbuch fehlen: {sorted(fehlend)}")


if __name__ == "__main__":
    main()
