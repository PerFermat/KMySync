#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Macht aus dem anonymisierten Bestand die beiden Vorführdatenbanken für die Bildaufnahme.

    tools/bestand_aufbereiten.py ~/Nextcloud/ausgaben-anonymisiert.db

Erzeugt daraus, neben der Vorlage:

    ~/Nextcloud/kmysync-bestand-de.db     nachgeputzt, sonst unverändert
    ~/Nextcloud/kmysync-bestand-en.db     zusätzlich englische Namen

Bewusst <b>nicht</b> im Projektordner: Die Vorlage heißt zwar „anonymisiert", trägt in den
Buchungsnotizen aber noch Klarnamen, Wohnort, Straße und drei echte IBANs mit sich. Im Repo stünde
das öffentlich und wäre aus der Historie nicht mehr herauszubekommen. In der Nextcloud liegt es
privat – und wandert nebenbei auf den zweiten Rechner.

Warum es die englische Fassung braucht: Kontonamen, Empfänger und Kategorien stehen in der
Datenbank, nicht in den Übersetzungen der App. Ein englischer Satz Handbuchbilder mit „Bäckerei
Krustengold" und „Fahrtkosten:Auto:Benzin" fiele sofort auf. Eine wörtliche Übersetzung ist nicht
nötig und wäre auch nicht wünschenswert – gebraucht wird ein Bestand, der englisch *aussieht*.

Warum beide nachgeputzt werden: Die Vorlage heißt „anonymisiert", trägt aber in den Buchungsnotizen
noch den Klarnamen des Besitzers, eine Umsatzsteuer-Identnummer und Kontonummern mit sich. Solange
die Datei privat lag, fiel das nicht ins Gewicht; im Projektordner steht sie öffentlich.

Das Skript ist wiederholbar: Ändert sich die Vorlage, läuft es einfach erneut. Deshalb ist die
Zuordnung deutscher zu englischen Namen fest verdrahtet und nicht zufällig – derselbe Empfänger
bekommt bei jedem Lauf denselben englischen Namen, sonst wäre kein Bild mit dem vorigen
vergleichbar.
"""
import os
import re
import shutil
import sqlite3
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIEL = os.path.expanduser("~/Nextcloud")

# ---------------------------------------------------------------- Nachputzen
#
# Die Vorlage ersetzt Empfängernamen und IBAN im Kopf der Buchung durch Platzhalter – in den
# Buchungsnotizen aber nicht. Dort stehen weiterhin Klarname (395 Notizen), Wohnort (388), Straße
# und drei echte IBANs. Die Dateien selbst bleiben privat, die daraus entstehenden Bilder aber
# nicht: Was auf einer Buchungszeile steht, steht später im Handbuch und bei F-Droid.
#
# Reihenfolge zählt: Erst die längeren, zusammengesetzten Muster, sonst zerlegt ein früheres
# Einzelwort die Wortfolge, die das spätere Muster noch treffen wollte.
def lose(wort):
    """Muster, das ein Wort auch dann findet, wenn Leerraum mitten hindurchgeht.

    Nötig, weil die Verwendungszwecke aus dem Kontoauszug zerhackt sind: Dort steht „Michael Spa hr",
    „Spahr, M ichael" und „HATTENHOFENKAUFUMSATZ" ohne Worttrennung. Ein Muster mit Wortgrenzen
    findet davon nichts – und hätte hier still versagt, was der schlimmste Fall wäre."""
    return re.compile(r"\s*".join(re.escape(z) for z in wort), re.I)


# Name, Wohnort, Straße – und die Nachbarorte. Einzeln sagt „Zell" wenig; zusammen mit Göppingen,
# Kirchheim und Aichelberg zeichnen die Kartenzahlungen aber genau nach, wo jemand wohnt und
# einkauft. Deshalb gehören sie alle hierher.
PERSONEN = [("spahr", "Morgan"), ("michael", "Alex"), ("rolf", "Robert"),
            ("hattenhofen", "Newton"), ("steigle", "High Street"),
            ("weilimdorf", "Easton"), ("fasanengarten", "Pheasant Court"),
            ("aichelberg", "Ashby Hill"), ("zell", "Ashby"),
            ("goeppingen", "Crawley"), ("göppingen", "Crawley"),
            ("stuttgart", "Bristol"), ("kirchheim", "Kingsford")]

NACHPUTZEN = [
    # Zuerst die vollständige Anschrift – danach wäre sie in Einzelteile zerfallen.
    (re.compile(r"SPAHR,?\s*ROLF,?\s*STEIGLE\s*\d+,?\s*\d{3}\s*\d{2}\s*HATTENHOFEN", re.I),
     "MORGAN, ROBERT, HIGH STREET 4, 12345 NEWTON"),
    # „ZELL U. AIC" und „ZELL UNTER" als Ganzes, sonst bliebe der abgekürzte Rest stehen.
    (re.compile(r"ZELL\s*U\.?\s*AIC\w*", re.I), "ASHBY"),
    (re.compile(r"ZELL\s*UNTER", re.I), "ASHBY"),
] + [(lose(wort), ersatz) for wort, ersatz in PERSONEN] + [
    # Bankverbindungen: echte IBANs und Kontonummern.
    (re.compile(r"DE\s*\d[\d\s]{18,}\d"), "DE00000000000000000000"),
    (re.compile(r"UST-IDNR\.?\s*DE\s*\d+", re.I), "UST-IDNR. DE 000000000"),
    (re.compile(r"KONTO\s+\d{4,}", re.I), "KONTO 000000000"),
    (re.compile(r"TAN:\s*\d+", re.I), "TAN: 000000"),
    # Vertrags-, Kunden- und Referenznummern: alles ab sechs Stellen wird zu Nullen. Die Notiz
    # behält ihre Gestalt, der Inhalt ist keiner mehr.
    (re.compile(r"\d{6,}"), lambda m: "0" * len(m.group(0))),
]

# Wonach am Ende jedes Laufs gesucht wird – mit denselben toleranten Mustern, sonst prüfte die
# Prüfung schwächer, als das Putzen arbeitet, und bestätigte eine Sauberkeit, die es nicht gibt.
VERBOTEN = [lose(wort) for wort, _ in PERSONEN]

# ---------------------------------------------------------------- Konten

KONTEN = {
    "Bargeld": "Cash",
    "Bausparkonto": "Building Society Account",
    "Bausparvertrag": "Building Society Plan",
    "Depot": "Portfolio",
    "Festgeld": "Fixed Deposit",
    "Geschäftsanteile Bank West": "Shares Westbank",
    "Girokonto": "Current Account",
    "Girokonto Alt": "Current Account (old)",
    # Hieß in der Vorlage „Kredit Rolf"; das Nachputzen läuft zuerst und macht daraus „Kredit
    # Robert" – der Schlüssel muß also den geputzten Namen tragen, sonst greift die Übersetzung ins
    # Leere und das Konto bliebe deutsch.
    "Kredit Robert": "Loan from Robert",
    "Kreditkarte 1": "Credit Card 1",
    "Kreditkarte 2": "Credit Card 2",
    "S-Cash": "S-Cash",
    "Scala Plus": "Scala Plus",
    "Sparkonto Bank Ost": "Savings Eastbank",
    "Sparplan Bank West": "Savings Plan Westbank",
    "Tagesgeld Bank Nord": "Instant Access Northbank",
    "Tagesgeld Bank Ost": "Instant Access Eastbank",
    "Tagesgeld Bank Süd": "Instant Access Southbank",
    "Wohnungsbaufinanzierung 15000": "Home Loan 15000",
    "Wohnungsbaufinanzierung 25000": "Home Loan 25000",
    "Zwischenfinanzierung Bausparvertrag": "Bridging Loan",
}

ORTE = {
    "Geldbeutel": "Wallet",
    "Geldbeutel 2": "Wallet 2",
    "Mein Raisin-Konto": "My Savings Portal",
    "Weltsparen": "SaveGlobal",
    "pbbDirekt": "pbbDirect",
}

# ---------------------------------------------------------------- Kategorien
#
# Übersetzt wird Segment für Segment: Die Kategorien sind Pfade wie
# „Fahrtkosten:Auto:Benzin", und nur so bleiben Ober- und Unterkategorie zueinander passend.
SEGMENTE = {
    "ADAC": "Breakdown Cover", "Abfall": "Waste", "Abgaben": "Duties", "Abheben": "Cash Withdrawal",
    "Ausgehen": "Going Out", "Auto": "Car", "Bahn": "Rail", "Bankgebühren": "Bank Charges",
    "Bauspardarlehen": "Building Society Loan", "Bausparvertrag": "Building Society Plan",
    "Benzin": "Fuel", "Berufsunfähigkeitsversicherung": "Income Protection",
    "Bildung": "Education", "Bücher": "Books", "Büroartikel": "Stationery",
    "Computer": "Computer", "DVD": "DVD",
    "Darlehen 15000": "Loan 15000", "Darlehen 25000": "Loan 25000", "Darlehen 75000": "Loan 75000",
    "Dividende": "Dividends", "Elektronik": "Electronics", "Eltern": "Parents",
    "Fahrtkosten": "Transport", "Fernsehen": "Television", "Festgeld": "Fixed Deposit",
    "Gehalt": "Salary", "Geschenke": "Gifts", "Geschäft": "Business", "Gesundheit": "Health",
    "Gewinnspiel": "Prize Draw", "Girokonto": "Current Account", "Grundschuld": "Mortgage Charge",
    "Grundsteuer": "Council Tax", "Haare": "Hairdresser", "Haftpflicht": "Liability",
    "Handy": "Mobile", "Hausgeld": "Service Charge", "Kantine": "Canteen",
    "Kapitalertragssteuer": "Capital Gains Tax", "Kauf": "Purchase", "Kleidung": "Clothing",
    "Kosten": "Fees", "Krankenzusatz": "Health Top-up", "Kredit": "Loan",
    "Körperpflege": "Personal Care", "Lebensmittel": "Groceries", "Lose": "Tickets",
    "Medikamente": "Medication", "Musik/Kino": "Music/Cinema", "Möbel": "Furniture",
    "Münzen": "Coins", "Nebenkosten": "Utilities", "Parkgebühren": "Parking",
    "Praxisgebühr": "Surgery Fee", "Reifenwechsel": "Tyre Change", "Reinigung": "Cleaning",
    "Rente": "Pension", "Reparaturen": "Repairs", "Selbstbeteiligung": "Excess",
    "Solidarzuschlag": "Solidarity Surcharge", "Sondertilgung": "Overpayment",
    "Sonderzahlungen": "Bonus", "Sonstige": "Other", "Sonstiges": "Other",
    "Sonstiges Bar": "Other (cash)", "Sparen": "Savings", "Sparkonto": "Savings Account",
    "Spenden": "Donations", "Spiele": "Games", "Sport": "Sport", "Steuer": "Tax",
    "Steuern": "Taxes", "Strafen": "Fines", "Strom": "Electricity",
    "Tagesgeld": "Instant Access", "Telefon/Internet": "Phone/Internet",
    "Telekommunikation": "Communications", "Tilgung": "Repayment",
    "Unterhaltung": "Entertainment", "VL": "Employer Savings",
    "Versicherungen": "Insurance", "Wohnen": "Housing", "Zeitungen": "Newspapers",
    "Zinsen": "Interest",
}

# ---------------------------------------------------------------- Empfänger
#
# 625 Empfänger, überwiegend erfundene deutsche Personen- und Firmennamen. Sie werden nicht
# übersetzt, sondern durch englische ersetzt – fest zugeordnet über die Position in der sortierten
# Liste, damit jeder Lauf dasselbe Ergebnis hat.

VORNAMEN = ["Alice", "Andrew", "Beth", "Brian", "Chloe", "Colin", "Diane", "Derek", "Ellie",
            "Edward", "Fiona", "Frank", "Grace", "George", "Hannah", "Henry", "Isla", "Ian",
            "Jenny", "James", "Kate", "Keith", "Laura", "Liam", "Megan", "Martin", "Nina",
            "Neil", "Olive", "Oscar", "Paula", "Peter", "Quinn", "Rachel", "Ryan", "Sarah",
            "Simon", "Tessa", "Tom", "Una", "Victor", "Wendy", "Will", "Yvonne", "Zack"]

NACHNAMEN = ["Abbott", "Bailey", "Carter", "Dawson", "Ellis", "Foster", "Grant", "Harper",
             "Irving", "Jenkins", "Knight", "Lawson", "Mercer", "Norton", "Oakley", "Palmer",
             "Quincey", "Rhodes", "Sutton", "Turner", "Underwood", "Vance", "Whitfield",
             "Yates", "Ashford", "Brooks", "Chandler", "Delaney", "Everett", "Fairbanks",
             "Granger", "Holloway", "Ingram", "Jarvis", "Kendrick", "Lockhart", "Montgomery",
             "Newbury", "Osborne", "Pemberton", "Radcliffe", "Sinclair", "Thornton", "Vaughan",
             "Westbrook"]

# Firmen, erkennbar an Rechtsform oder Branchenwort. Die Branchenwörter stehen vorn, damit
# „Bäckerei Krustengold" zu „Crustloaf Bakery" wird und nicht zu einer Person.
BRANCHEN = [
    ("Apotheke", "Pharmacy"), ("Bäckerei", "Bakery"), ("Baumarkt", "DIY Store"),
    ("Metzgerei", "Butchers"), ("Buchhandlung", "Bookshop"), ("Drogerie", "Chemists"),
    ("Tankstelle", "Petrol Station"), ("Restaurant", "Restaurant"), ("Gasthaus", "Inn"),
    ("Hotel", "Hotel"), ("Werkstatt", "Garage"), ("Markt", "Market"),
]

RECHTSFORMEN = [
    ("GmbH & Co. KGaA", "Holdings plc"), ("GmbH & Co. OHG", "Group Ltd"),
    ("GmbH & Co. KG", "& Partners Ltd"), ("AG & Co. KGaA", "Holdings plc"),
    ("AG & Co. OHG", "Group plc"), ("AG & Co. KG", "& Partners plc"),
    ("GmbH", "Ltd"), ("KGaA", "plc"), ("e.G.", "Co-op"), ("AG", "plc"),
    ("OHG", "& Sons"), ("KG", "LLP"),
]

FIRMENWOERTER = ["Ashgrove", "Blackwood", "Clearwater", "Dunmore", "Eastgate", "Fernhill",
                 "Greystone", "Hartley", "Ironbridge", "Kingsley", "Langford", "Marchmont",
                 "Northfield", "Oakhurst", "Pinecrest", "Ravenswood", "Stonebridge", "Thornbury",
                 "Whitmore", "Yardley", "Amberley", "Bridgeport", "Coldstream", "Denby",
                 "Elmwood", "Foxglove", "Glenmore", "Highbury", "Inglewood", "Junipers"]


def ist_firma(name):
    if any(name.startswith(w + " ") for w, _ in BRANCHEN):
        return True
    return any(re.search(r"\b" + re.escape(form.split()[0]) + r"\b", name)
               for form, _ in RECHTSFORMEN)


def firma_englisch(name, nr):
    """Baut einen englischen Firmennamen im selben Stil: Branche bleibt Branche, Rechtsform wird
    zur englischen Entsprechung."""
    kern = FIRMENWOERTER[nr % len(FIRMENWOERTER)]
    zweit = FIRMENWOERTER[(nr * 7 + 3) % len(FIRMENWOERTER)]
    for wort, englisch in BRANCHEN:
        if name.startswith(wort + " "):
            return f"{kern} {englisch}"
    for form, englisch in RECHTSFORMEN:
        if re.search(r"\b" + re.escape(form.split()[0]) + r"\b", name):
            # Zwei Kerne bei den längeren Formen – das gibt dieselbe Bandbreite wie im Original.
            vorn = f"{kern} {zweit}" if "&" in englisch or "Group" in englisch else kern
            return f"{vorn} {englisch}"
    return f"{kern} Ltd"


def person_englisch(nr):
    """Vorname aus der einen, Nachname aus der anderen Liste – die Indizes laufen unabhängig, damit
    45 × 45 verschiedene Namen herauskommen und nicht nur 45."""
    return (f"{VORNAMEN[nr % len(VORNAMEN)]} "
            f"{NACHNAMEN[(nr // len(VORNAMEN)) % len(NACHNAMEN)]}")


def empfaenger_abbildung(namen):
    """Deutscher Empfänger → englischer, fest über die Position in der sortierten Liste.

    payee.name ist eindeutig; zwei deutsche Empfänger dürfen also nicht auf denselben englischen
    fallen. Was sich trotz getrennter Indizes doch trifft, bekommt eine Nummer angehängt."""
    zuordnung, vergeben = {}, set()
    for nr, name in enumerate(sorted(namen)):
        neu = firma_englisch(name, nr) if ist_firma(name) else person_englisch(nr)
        if neu in vergeben:
            zaehler = 2
            while f"{neu} {zaehler}" in vergeben:
                zaehler += 1
            neu = f"{neu} {zaehler}"
        vergeben.add(neu)
        zuordnung[name] = neu
    return zuordnung


# Gesprochene Kürzel der Spracherkennung – sie stehen in payee_correction und gehören zur Sprache.
GESPROCHEN = {"mama": "mum", "papa": "dad", "bäcker": "baker", "kantine": "canteen",
              "kaffee": "coffee", "mühle": "mill", "tanken": "petrol", "apotheke": "pharmacy",
              "metzger": "butcher", "markt": "market"}

# Namen geplanter Buchungen.
GEPLANT = {"ENBW 2010": "Power Co 2010", "Grundsteuer": "Council Tax",
           "Sonderzahlung Mai": "Bonus May", "Förderverein": "Supporters Club",
           "Autoversicherung": "Car Insurance", "GEZ": "TV Licence", "Tanken": "Fuel",
           "ADAC": "Breakdown Cover", "Haftpflichtversicherung": "Liability Insurance",
           "Brille": "Glasses", "Sonstiges": "Other", "TÜV + Service": "MOT + Service"}

# ---------------------------------------------------------------- Notizen
#
# Die Buchungsnotizen werden nicht bereinigt, sondern <b>ersetzt</b>. Sie sind Verwendungszwecke aus
# echten Kontoauszügen: zerhackt über Zeilenumbrüche, gespickt mit Namen, Anschriften, Vertrags- und
# Kundennummern. Jedes Muster, das dort sucht, ist eine Vermutung – und eine Vermutung ist kein
# Nachweis. Wird der Text stattdessen neu gesetzt, kann nichts aus dem Original übrigbleiben.
#
# Was drinsteht, ist für die Bilder gleichgültig; es muss nur aussehen wie ein Verwendungszweck.
# {n} wird durch eine Zahl ersetzt, damit nicht tausendmal dieselbe Zeile dasteht.
NOTIZEN_DE = [
    "SEPA-Überweisung\nRef. {n}",
    "Kartenzahlung {n}",
    "Dauerauftrag Nr. {n}",
    "Lastschrift Einzug {n}",
    "Gutschrift Rechnung {n}",
    "Rechnung Nr. {n}",
    "Abschlag {n}",
    "Beitrag Nr. {n}",
    "Monatsbeitrag",
    "Barabhebung Automat {n}",
    "Kundennummer {n}",
    "Vertrag {n}",
    "Jahresabrechnung {n}",
    "Zahlung erhalten, vielen Dank",
    "Erstattung Vorjahr",
    "Abrechnung Quartal {n}",
    "Umbuchung Eigenkonto",
    "Sparrate",
    "Zinsgutschrift {n}",
    "Wertpapierabrechnung {n}",
    "Depotgebühr {n}",
    "Ausgleich Saldo",
    "Auslage erstattet",
    "Anteil Nebenkosten",
    "Mitgliedsbeitrag {n}",
]

NOTIZEN_EN = [
    "SEPA transfer\nref. {n}",
    "Card payment {n}",
    "Standing order no. {n}",
    "Direct debit {n}",
    "Credit for invoice {n}",
    "Invoice no. {n}",
    "Instalment {n}",
    "Premium no. {n}",
    "Monthly contribution",
    "Cash withdrawal ATM {n}",
    "Customer no. {n}",
    "Contract {n}",
    "Annual statement {n}",
    "Payment received, thank you",
    "Refund previous year",
    "Quarterly statement {n}",
    "Transfer between own accounts",
    "Savings instalment",
    "Interest credited {n}",
    "Securities statement {n}",
    "Custody fee {n}",
    "Balance settlement",
    "Expenses reimbursed",
    "Share of service charges",
    "Membership fee {n}",
]


# ---------------------------------------------------------------- Arbeit am Bestand
def spalten(c, tabelle):
    return [r[1] for r in c.execute(f"PRAGMA table_info({tabelle})")]


def ersetze_in(c, tabelle, spalte, abbildung):
    """Setzt in einer Textspalte jeden bekannten Wert auf seine Entsprechung."""
    if tabelle not in tabellen(c) or spalte not in spalten(c, tabelle):
        return 0
    geaendert = 0
    for alt, neu in abbildung.items():
        if alt == neu:
            continue
        cur = c.execute(f"UPDATE {tabelle} SET {spalte} = ? WHERE {spalte} = ?", (neu, alt))
        geaendert += cur.rowcount
    return geaendert


def tabellen(c):
    return {r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}


def kategorie_abbildung(c):
    """Sammelt alle Kategoriepfade und übersetzt sie Segment für Segment.

    Nicht jedes Segment ist eine Kategorie: Umbuchungen führen den Kontonamen als Gegenseite
    („Kreditkarte 2"), und Wertpapierbuchungen den Namen des Papiers. Kontonamen kommen deshalb aus
    derselben Liste wie die Konten; Papiernamen sind im Bestand ohnehin schon englisch und bleiben,
    wie sie sind."""
    pfade = set()
    for tabelle, spalte in (("booking", "category"), ("booking_split", "category"),
                            ("budget", "category"), ("scheduled_split", "category"),
                            ("scheduled_transaction", "counterparty")):
        if tabelle in tabellen(c) and spalte in spalten(c, tabelle):
            for (v,) in c.execute(f"SELECT DISTINCT {spalte} FROM {tabelle} "
                                  f"WHERE {spalte} IS NOT NULL AND {spalte} != ''"):
                pfade.add(v)

    papiere = {r[0] for r in c.execute("SELECT name FROM security")} if "security" in tabellen(c) \
        else set()
    bekannt = dict(SEGMENTE)
    bekannt.update(KONTEN)

    fehlend, abbildung = set(), {}
    for pfad in pfade:
        teile = []
        for teil in pfad.split(":"):
            if teil not in bekannt and teil not in papiere:
                fehlend.add(teil)
            teile.append(bekannt.get(teil, teil))
        abbildung[pfad] = ":".join(teile)
    if fehlend:
        print("  Achtung, keine Übersetzung für:", ", ".join(sorted(fehlend)))
    return abbildung


# Spalten, die kein freier Text sind, sondern Kennungen und Schlüssel. Sie enthalten nichts
# Persönliches, und die Ziffernmaskierung machte aus zwei verschiedenen Kennungen dieselbe – die
# Datenbank hat darauf Eindeutigkeitsbedingungen und wehrt sich zu Recht.
KEINE_TEXTSPALTEN = {"id", "kmy_id", "security_kmy_id", "booking_id", "scheduled_id",
                     "transfer_group", "symbol", "currency", "type", "acct_type", "action",
                     "kind", "occurrence", "source", "gps_list"}


def nachputzen(c):
    """Entfernt in <b>jeder</b> Textspalte, was auf einem veröffentlichten Bild nichts zu suchen hat.

    Nicht nur in den Notizen: Ein Name steht in dieser Datenbank an vielen Stellen zugleich, weil
    Empfänger, Konto und Ort als Text und nicht als Verweis geführt werden. Putzte man nur
    payee.name, bliebe derselbe Name in booking.payee stehen – und die beiden gehörten nicht mehr
    zusammen. Deshalb über alles laufen, was Text hält."""
    treffer = 0
    for tabelle in sorted(tabellen(c)):
        if tabelle.startswith("sqlite_") or tabelle in ("android_metadata", "translation"):
            continue
        for spalte in spalten(c, tabelle):
            if spalte in KEINE_TEXTSPALTEN:
                continue
            try:
                zeilen = list(c.execute(
                    f"SELECT rowid, {spalte} FROM {tabelle} WHERE typeof({spalte}) = 'text'"))
            except sqlite3.OperationalError:
                continue
            for rowid, wert in zeilen:
                neu = wert
                for muster, ersatz in NACHPUTZEN:
                    neu = muster.sub(ersatz, neu)
                if neu != wert:
                    c.execute(f"UPDATE {tabelle} SET {spalte} = ? WHERE rowid = ?", (neu, rowid))
                    treffer += 1
    return treffer


def uebersetzen(c):
    """Macht aus dem deutschen Bestand einen englisch aussehenden."""
    namen = [r[0] for r in c.execute("SELECT name FROM payee")]
    empfaenger = empfaenger_abbildung(namen)
    kategorien = kategorie_abbildung(c)

    zahlen = {}
    # Empfänger: eigene Tabelle und jede Stelle, die den Namen als Text mitführt.
    zahlen["Empfänger"] = sum(ersetze_in(c, t, s, empfaenger) for t, s in (
        ("payee", "name"), ("booking", "payee"), ("scheduled_transaction", "payee"),
        ("payee_correction", "corrected")))
    # Konten: ebenso, samt Umbuchungszielen und Depot.
    zahlen["Konten"] = sum(ersetze_in(c, t, s, KONTEN) for t, s in (
        ("account", "name"), ("booking", "account"), ("booking", "transfer_account"),
        ("place_entry", "account"), ("scheduled_transaction", "account"),
        ("payee_correction", "account"), ("payee_correction", "from_account"),
        ("payee_correction", "to_account"), ("security", "depot"), ("security_tx", "depot")))
    zahlen["Kategorien"] = sum(ersetze_in(c, t, s, kategorien) for t, s in (
        ("booking", "category"), ("booking_split", "category"), ("budget", "category"),
        ("scheduled_split", "category"), ("scheduled_transaction", "counterparty"),
        ("payee_correction", "cat_income_1"), ("payee_correction", "cat_income_2"),
        ("payee_correction", "cat_expense_1"), ("payee_correction", "cat_expense_2")))
    zahlen["Orte"] = sum(ersetze_in(c, t, s, ORTE) for t, s in (
        ("place_entry", "place"), ("booking", "place"), ("payee_correction", "place"),
        ("payee_correction", "from_place"), ("payee_correction", "to_place")))
    zahlen["gesprochen"] = ersetze_in(c, "payee_correction", "spoken", GESPROCHEN)
    zahlen["geplant"] = ersetze_in(c, "scheduled_transaction", "name", GEPLANT)

    # Die Notizen setzt notizen_ersetzen – sie werden in beiden Fassungen ohnehin neu geschrieben.
    return zahlen


def notizen_ersetzen(c, deutsch):
    """Setzt jede vorhandene Notiz neu – aus dem Vorrat, deterministisch über die Zeilennummer.

    Wo vorher keine Notiz stand, kommt auch keine hin: Sonst hätte plötzlich jede Buchung eine, und
    die Bilder zeigten etwas, das die App so nie erzeugt."""
    vorrat = NOTIZEN_DE if deutsch else NOTIZEN_EN
    gesetzt = 0
    for tabelle in ("booking", "place_entry"):
        if tabelle not in tabellen(c) or "note" not in spalten(c, tabelle):
            continue
        zeilen = list(c.execute(f"SELECT rowid FROM {tabelle} "
                                f"WHERE note IS NOT NULL AND note != ''"))
        for (rowid,) in zeilen:
            vorlage = vorrat[rowid % len(vorrat)]
            # Eine Zahl, die je Zeile anders und doch bei jedem Lauf dieselbe ist.
            text = vorlage.format(n=100000 + (rowid * 7919) % 900000)
            c.execute(f"UPDATE {tabelle} SET note = ? WHERE rowid = ?", (text, rowid))
            gesetzt += 1
    return gesetzt


def pruefen(c):
    """Sucht in jeder Textspalte jeder Tabelle nach dem, was nicht mehr da sein darf.

    Der teure Weg, aber der einzige, der etwas wert ist: Die Muster oben sind eine Vermutung
    darüber, wo etwas steht – diese Prüfung stellt fest, ob die Vermutung gestimmt hat."""
    treffer = []
    for tabelle in sorted(tabellen(c)):
        if tabelle.startswith("sqlite_") or tabelle in ("android_metadata", "translation"):
            continue
        for spalte in spalten(c, tabelle):
            try:
                werte = [r[0] for r in c.execute(
                    f"SELECT {spalte} FROM {tabelle} WHERE typeof({spalte}) = 'text'")]
            except sqlite3.OperationalError:
                continue
            anzahl = sum(1 for w in werte if any(m.search(w) for m in VERBOTEN))
            if anzahl:
                treffer.append(f"{tabelle}.{spalte}: {anzahl}")
    return treffer


def bauen(vorlage, ziel, englisch):
    shutil.copyfile(vorlage, ziel)
    # -wal/-shm einer früheren Fassung würden den frischen Stand wieder überschreiben.
    for anhang in ("-wal", "-shm"):
        if os.path.exists(ziel + anhang):
            os.remove(ziel + anhang)
    c = sqlite3.connect(ziel)
    try:
        print(f"{os.path.basename(ziel)}:")
        # Erst putzen, dann die Notizen neu setzen: Das Putzen macht jede Ziffernfolge ab sechs
        # Stellen zu Nullen, und die Zahlen in den neuen Notizen sollen stehenbleiben.
        print(f"  nachgeputzt: {nachputzen(c)} Texte")
        print(f"  Notizen neu gesetzt: {notizen_ersetzen(c, deutsch=not englisch)}")
        if englisch:
            for was, zahl in uebersetzen(c).items():
                print(f"  {was}: {zahl}")
        c.commit()
        c.execute("VACUUM")
        offen = pruefen(c)
        if offen:
            print("  NICHT SAUBER – hier steht noch etwas:", "; ".join(offen))
            return False
        print("  geprüft: keine Namen, Orte oder Bankverbindungen mehr gefunden")
        return True
    finally:
        c.close()


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    vorlage = os.path.expanduser(argv[0]) if argv \
        else os.path.expanduser("~/Nextcloud/ausgaben-anonymisiert.db")
    if not os.path.isfile(vorlage):
        print(f"{vorlage} gibt es nicht.", file=sys.stderr)
        return 1
    os.makedirs(ZIEL, exist_ok=True)
    sauber = bauen(vorlage, os.path.join(ZIEL, "kmysync-bestand-de.db"), englisch=False)
    sauber &= bauen(vorlage, os.path.join(ZIEL, "kmysync-bestand-en.db"), englisch=True)
    return 0 if sauber else 1


if __name__ == "__main__":
    sys.exit(main())
