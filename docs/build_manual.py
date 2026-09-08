# -*- coding: utf-8 -*-
"""Erzeugt das Benutzerhandbuch als PDF basierend auf einer JSON-Sprachdatei.

Von der Kommandozeile:

    python3 docs/build_manual.py Handbuch-KMySync-de de

Der Handbuch-Editor ruft stattdessen erzeuge() auf und übergibt seinen ungesicherten Stand samt
einer Auswahl von Abschnitten – so zeigt die Vorschau dasselbe Layout wie das fertige PDF, ohne
dass die Stile ein zweites Mal beschrieben werden müssten.
"""
import os
import sys
import json
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm, mm
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, PageBreak,
                                Table, TableStyle, Image, KeepTogether)
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.utils import ImageReader

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Diese drei setzt erzeuge(); die Hilfsfunktionen unten greifen darauf zu.
LANG = "de"
SHOTS = os.path.join(REPO, "screenshots", LANG)
I18N = {}


def json_pfad(lang):
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), f"handbuch_{lang}.json")


def lade_sprache(lang):
    with open(json_pfad(lang), "r", encoding="utf-8") as f:
        return json.load(f)

# --- Schriften ---
pdfmetrics.registerFont(TTFont("DejaVu", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("DejaVu-Bold", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
pdfmetrics.registerFont(TTFont("DejaVu-Oblique", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf"))
pdfmetrics.registerFontFamily("DejaVu", normal="DejaVu", bold="DejaVu-Bold", italic="DejaVu-Oblique")

GREEN = colors.HexColor("#2e7d32")
GREY = colors.HexColor("#555555")
LIGHT = colors.HexColor("#eef3ee")

styles = getSampleStyleSheet()
def S(name, **kw):
    base = kw.pop("parent", styles["Normal"])
    kw.setdefault("fontName", "DejaVu")
    return ParagraphStyle(name, parent=base, **kw)

st_title   = S("t",  fontName="DejaVu-Bold", fontSize=26, leading=30, textColor=GREEN, spaceAfter=6)
st_sub     = S("s",  fontSize=13, leading=17, textColor=GREY, spaceAfter=2)
st_h1      = S("h1", fontName="DejaVu-Bold", fontSize=16, leading=20, textColor=GREEN, spaceBefore=16, spaceAfter=6)
# keepWithNext: eine Überschrift allein am Seitenfuß ist schlimmer als eine etwas kürzere Seite.
st_h2      = S("h2", fontName="DejaVu-Bold", fontSize=12.5, leading=16, textColor=colors.HexColor("#1b4d1e"), spaceBefore=10, spaceAfter=3, keepWithNext=1)
# Dritte Ebene: benennt die einzelnen Feinheiten innerhalb eines Abschnitts, damit sie nicht zu einem
# Absatzklotz zusammenwachsen. Bewußt ohne Eintrag im Inhaltsverzeichnis – davon gäbe es zu viele.
st_h3      = S("h3", fontName="DejaVu-Bold", fontSize=10.5, leading=14, textColor=colors.HexColor("#1b4d1e"), spaceBefore=7, spaceAfter=1, keepWithNext=1)
st_body    = S("b",  fontSize=10, leading=14.5, spaceAfter=5, alignment=TA_LEFT)
st_bullet  = S("bu", fontSize=10, leading=14.5, leftIndent=14, bulletIndent=2, spaceAfter=2)
st_cell    = S("c",  fontSize=9, leading=12)
st_cellb   = S("cb", fontName="DejaVu-Bold", fontSize=9, leading=12)
st_sym     = S("sy", fontName="DejaVu-Bold", fontSize=13, leading=14, alignment=TA_CENTER)
st_cap     = S("cap", fontSize=8.5, leading=11, textColor=GREY, alignment=TA_CENTER, spaceBefore=3)
st_note    = S("n",  fontSize=9, leading=13, textColor=GREY)
st_fehlt   = S("f",  fontSize=10, leading=14, textColor=GREY, alignment=TA_CENTER)

story = []
_first_h1 = [True]
_bm = [0]

# Zustandsvariablen für das abwechselnde Alignment (links/rechts)
align_left = [True]


def _zustand_zuruecksetzen():
    """Vor jedem Bau leeren – sonst erbte ein zweiter Lauf die Flowables des ersten."""
    del story[:]
    _first_h1[0] = True
    _bm[0] = 0
    align_left[0] = True

def _heading(t, style, level):
    _bm[0] += 1
    key = f"sec{_bm[0]}"
    para = Paragraph(f'<a name="{key}"/>{t}', style)
    para._toc = (level, t, key)
    return para

def h1(t):
    if _first_h1[0]:
        _first_h1[0] = False
    else:
        story.append(PageBreak())
    story.append(_heading(t, st_h1, 0))

def h2(t):
    story.append(_heading(t, st_h2, 1))

def h3(t):
    # Ohne _heading: die dritte Ebene bleibt aus dem Inhaltsverzeichnis heraus.
    story.append(Paragraph(t, st_h3))

def create_paragraph_elements(content_list):
    """Erzeugt Flowables für Text, Bullets und Überschriften zur Verwendung neben Bildern."""
    elements = []
    for item in content_list:
        itype = item.get("type")
        if itype == "p":
            elements.append(Paragraph(item["text"], st_body))
        elif itype == "h2":
            elements.append(Paragraph(item["text"], st_h2))
        elif itype == "h3":
            elements.append(Paragraph(item["text"], st_h3))
        elif itype == "bullets":
            for bu in item["items"]:
                elements.append(Paragraph(bu, st_bullet, bulletText="•"))
            elements.append(Spacer(1, 4))
        elif itype == "steps":
            for nummer, schritt in enumerate(item["items"], 1):
                elements.append(Paragraph(schritt, st_bullet, bulletText=f"{nummer}."))
            elements.append(Spacer(1, 4))
    return elements


def _bildflaeche(fname, width):
    path = os.path.join(SHOTS, fname)
    if os.path.isfile(path):
        ir = ImageReader(path)
        iw, ih = ir.getSize()
        return Image(path, width=width, height=width*ih/iw)
    kasten = Table([[Paragraph(I18N["placeholder_no_image"], st_fehlt)],
                    [Paragraph(f'<font size="7">{fname}</font>', st_fehlt)]],
                   colWidths=[width], rowHeights=[width*2400/1080 - 0.9*cm, 0.9*cm])
    kasten.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#eeeeee")),
        ("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor("#bbbbbb")),
        ("VALIGN", (0, 0), (0, 0), "MIDDLE"), ("VALIGN", (0, 1), (0, 1), "TOP"),
    ]))
    return kasten

def create_shot_box(fname, caption, width=6.0*cm):
    img = _bildflaeche(fname, width)
    tbl = Table([[img],[Paragraph(caption, st_cap)]], colWidths=[width])
    tbl.setStyle(TableStyle([("ALIGN",(0,0),(-1,-1),"CENTER")]))
    tbl.hAlign = "CENTER"
    return tbl
# Bild-Höhe schätzen (Platzhalter ist ca. 14cm / 390pt hoch)
# Ein echtes Screenshot-Bild mit Caption liegt meist bei ~350–420 pt.
MAX_SIDE_HEIGHT = 380


def _geschaetzte_hoehe(tf):
    """Grobe Höhe eines Flowables aus Typ und Textlänge."""
    name = getattr(getattr(tf, 'style', None), 'name', '')
    if name == 'h2':
        return 35                      # Überschrift hat mehr Abstand
    if name == 'h3':
        return 24                      # dritte Ebene: kleiner, weniger Abstand
    return max(16, (len(getattr(tf, 'text', '')) / 45) * 14) + 4


def _aufteilen(text_flowables):
    """Was passt neben das Bild, was rutscht darunter?

    Der Handbuch-Editor benutzt dieselbe Rechnung, um die Blöcke einzufärben – deshalb steht sie
    hier einzeln und nicht mitten im Seitenaufbau.
    """
    side, bottom, hoehe = [], [], 0
    for tf in text_flowables:
        geschaetzt = _geschaetzte_hoehe(tf)
        if (hoehe + geschaetzt <= MAX_SIDE_HEIGHT) or not side:
            side.append(tf)
            hoehe += geschaetzt
        else:
            bottom.append(tf)
    return side, bottom


def aufteilung_der_inhalte(content_list):
    """Für jeden Eintrag aus «content»: steht er neben dem Bild, darunter oder halb/halb?

    Ergebnis ist eine Liste aus "neben", "darunter" oder "geteilt" – eine je Position in
    content_list. Für die Anzeige im Editor, nicht für den Seitenaufbau.
    """
    herkunft, flowables = [], []
    for nummer, item in enumerate(content_list):
        erzeugt = create_paragraph_elements([item])
        flowables.extend(erzeugt)
        herkunft.extend([nummer] * len(erzeugt))

    side, _bottom = _aufteilen(flowables)
    grenze = len(side)
    ergebnis = []
    for nummer in range(len(content_list)):
        stellen = [i for i, gehoert in enumerate(herkunft) if gehoert == nummer]
        if not stellen:
            ergebnis.append("neben")
        elif all(i < grenze for i in stellen):
            ergebnis.append("neben")
        elif all(i >= grenze for i in stellen):
            ergebnis.append("darunter")
        else:
            ergebnis.append("geteilt")
    return ergebnis


def add_single_shot_section(content_list, shot_data):
    """Platziert das Bild und füllt den Platz daneben optimal mit Text/Überschriften aus.
    Restlicher Inhalt fließt nahtlos darunter weiter."""
    img_width = (shot_data.get("width", 6.0)) * cm
    shot_box = create_shot_box(shot_data["fname"], shot_data["caption"], img_width)
    text_flowables = create_paragraph_elements(content_list)

    if not text_flowables:
        text_flowables = [Paragraph("", st_body)]

    total_w = 17.0 * cm
    gap_w = 0.5 * cm
    text_w = total_w - img_width - gap_w

    side_flowables, bottom_flowables = _aufteilen(text_flowables)

    # 1. Bild & oberer Teil nebeneinander rendern
    if align_left[0]:
        col_widths = [img_width, gap_w, text_w]
        row = [shot_box, "", side_flowables]
    else:
        col_widths = [text_w, gap_w, img_width]
        row = [side_flowables, "", shot_box]

    align_left[0] = not align_left[0]

    t = Table([row], colWidths=col_widths)
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
    ]))

    story.append(t)
    story.append(Spacer(1, 6))

    # 2. Überschüssiger Text geht direkt unter dem Bild in voller Breite weiter
    for tf in bottom_flowables:
        story.append(tf)

    if bottom_flowables:
        story.append(Spacer(1, 6))



def pic(relpath, caption, width=14*cm):
    path = os.path.join(REPO, relpath)
    ir = ImageReader(path); iw, ih = ir.getSize()
    img = Image(path, width=width, height=width*ih/iw)
    img.hAlign = "CENTER"
    box = Table([[img],[Paragraph(caption, st_cap)]], colWidths=[width])
    box.setStyle(TableStyle([("ALIGN",(0,0),(-1,-1),"CENTER")]))
    box.hAlign = "CENTER"
    story.append(KeepTogether([box]))
    story.append(Spacer(1, 6))

def shot_row(items, max_width=6.0*cm, total_width=17*cm, hgap=0.4*cm):
    """2 oder mehr Bilder nebeneinander."""
    n = len(items)
    cell_w = min(max_width, (total_width - hgap * (n - 1)) / n)
    row_cells, col_widths = [], []
    for i, item in enumerate(items):
        img = _bildflaeche(item["fname"], cell_w)
        cell = Table([[img], [Paragraph(item["caption"], st_cap)]], colWidths=[cell_w])
        cell.setStyle(TableStyle([("ALIGN", (0, 0), (-1, -1), "CENTER")]))
        row_cells.append(cell)
        col_widths.append(cell_w)
        if i < n - 1:
            row_cells.append("")
            col_widths.append(hgap)
    row = Table([row_cells], colWidths=col_widths)
    row.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
    ]))
    row.hAlign = "CENTER"
    story.append(KeepTogether([row]))
    story.append(Spacer(1, 6))

# Guillemets durch Anführungszeichen ersetzen
_RLParagraph = Paragraph
def Paragraph(_t, *a, **k):
    if isinstance(_t, str):
        _t = _t.replace('\u00ab', '\u201e').replace('\u00bb', '\u201c')
    return _RLParagraph(_t, *a, **k)

# --- Dokumentenaufbau ---
def _vorspann():
    """Titelseite und Inhaltsverzeichnis."""
    story.append(PageBreak())

    toc = TableOfContents()
    toc.levelStyles = [
        S("toc0", fontName="DejaVu-Bold", fontSize=10.5, leading=15, textColor=GREEN, spaceBefore=5, firstLineIndent=0, leftIndent=0, rightIndent=14),
        S("toc1", fontSize=9.5, leading=12.5, textColor=colors.HexColor("#333333"), firstLineIndent=0, leftIndent=16, rightIndent=14),
    ]
    toc.dotsMinLevel = 0
    story.append(Paragraph(I18N["toc_title"], st_h1))
    story.append(toc)
    story.append(PageBreak())


def _abschnitt(sec):
    """Einen Block aus der JSON in Flowables übersetzen."""
    stype = sec["type"]
    if stype == "h1":
        h1(sec["text"])
    elif stype == "h2":
        h2(sec["text"])
    elif stype == "h3":
        h3(sec["text"])
    elif stype == "p":
        story.append(Paragraph(sec["text"], st_body))
    elif stype == "bullets":
        for it in sec["items"]:
            story.append(Paragraph(it, st_bullet, bulletText="•"))
        story.append(Spacer(1, 4))
    elif stype == "steps":
        # Numerierte Handlungsschritte – für die Stellen, an denen die Reihenfolge zählt.
        for nummer, schritt in enumerate(sec["items"], 1):
            story.append(Paragraph(schritt, st_bullet, bulletText=f"{nummer}."))
        story.append(Spacer(1, 4))
    elif stype == "text_with_single_shot":
        add_single_shot_section(sec["content"], sec["shot"])
    elif stype == "shot_row":
        shot_row(sec["shots"], sec.get("width", 6.0) * cm)
    elif stype == "text_with_shot_row":
        for elem in create_paragraph_elements(sec["content"]):
            story.append(elem)
        shot_row(sec["shots"], sec.get("width", 6.0) * cm)
    elif stype == "text_with_pic":
        for elem in create_paragraph_elements(sec["content"]):
            story.append(elem)
        p_data = sec["pic"]
        pic(p_data["relpath"], p_data["caption"], p_data.get("width", 14.0)*cm)
    elif stype == "symbols_table":
        data = [[Paragraph(h, st_cellb) for h in I18N["table_headers"]]]
        for sym, name, desc in I18N["symbols"]:
            data.append([Paragraph(sym, st_sym), Paragraph(name, st_cellb), Paragraph(desc, st_cell)])
        tbl = Table(data, colWidths=[2.1*cm, 3.3*cm, 10.4*cm], repeatRows=1)
        tbl.setStyle(TableStyle([
            ("BACKGROUND",(0,0),(-1,0),GREEN),
            ("TEXTCOLOR",(0,0),(-1,0),colors.white),
            ("FONTNAME",(0,0),(-1,0),"DejaVu-Bold"),
            ("VALIGN",(0,0),(-1,-1),"MIDDLE"),
            ("ALIGN",(0,0),(0,-1),"CENTER"),
            ("ROWBACKGROUNDS",(0,1),(-1,-1),[colors.white, LIGHT]),
            ("GRID",(0,0),(-1,-1),0.4,colors.HexColor("#cccccc")),
            ("TOPPADDING",(0,0),(-1,-1),4),
            ("BOTTOMPADDING",(0,0),(-1,-1),4),
        ]))
        story.append(tbl)
        story.append(Paragraph(I18N["table_note"], st_note))
    elif stype == "pagebreak":
        # Von Hand gesetzter Seitenumbruch. Vor jeder Kapitelüberschrift kommt ohnehin einer;
        # dieser hier ist für die Stellen, an denen der Satz sonst ungünstig trennt.
        story.append(PageBreak())
    elif stype == "code":
        story.append(Paragraph(I18N["code_example"], S("code", fontName="DejaVu", fontSize=8.5, leading=12, backColor=LIGHT, borderPadding=6, textColor=colors.HexColor("#333333"))))

def cover_page(canvas, doc):
    canvas.saveState()
    canvas.drawImage(os.path.join(SHOTS, "Handbuch Titelseite.png"), 0, 0,
                     width=A4[0], height=A4[1])
    box_x = 7.8*mm + 3*mm
    canvas.setFont("DejaVu-Bold", 13)
    canvas.setFillColor(colors.HexColor("#1b1b1b"))
    canvas.drawString(box_x, 34.5*mm - 9*mm, I18N["version_text"])
    canvas.setFont("DejaVu", 10)
    canvas.setFillColor(GREY)
    canvas.drawString(box_x, 34.5*mm - 17*mm, I18N["date_text"])
    canvas.restoreState()

def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.setFillColor(GREY)
    canvas.drawString(2*cm, 1.2*cm, I18N["footer_text"])
    canvas.drawRightString(A4[0]-2*cm, 1.2*cm, I18N["page_text"] % doc.page)
    canvas.restoreState()

def _keep_headings_with_next(flowables):
    out = []
    i, n = 0, len(flowables)
    while i < n:
        f = flowables[i]
        name = getattr(getattr(f, "style", None), "name", "")
        if name == "h2" and i + 1 < n:
            out.append(KeepTogether([f, flowables[i + 1]]))
            i += 2
            continue
        out.append(f)
        i += 1
    return out

class ManualDoc(SimpleDocTemplate):
    def beforeDocument(self):
        self._seen_toc = set()

    def afterFlowable(self, flowable):
        entries = getattr(flowable, "_content", None) or [flowable]
        for f in entries:
            entry = getattr(f, "_toc", None)
            if entry is None or entry[2] in self._seen_toc:
                continue
            self._seen_toc.add(entry[2])
            level, text, key = entry
            text = text.replace('«', '„').replace('»', '“')
            self.notify("TOCEntry", (level, text, self.page, key))

def erzeuge(daten, lang, ausgabe, abschnitte=None, vorschau=False):
    """Baut das PDF und legt es unter «ausgabe» ab.

    daten       – der Inhalt einer Sprachdatei (Dict), nicht der Pfad. So kann der Editor seinen
                  ungesicherten Stand vorführen.
    abschnitte  – Auswahl aus daten["sections"]; None heißt: das ganze Handbuch.
    vorschau    – ohne Titelseite und Inhaltsverzeichnis, für die Kapitelvorschau im Editor.
    """
    global I18N, LANG, SHOTS
    I18N = daten
    LANG = lang
    SHOTS = os.path.join(REPO, "screenshots", lang)

    _zustand_zuruecksetzen()
    if not vorschau:
        _vorspann()
    for sec in (daten["sections"] if abschnitte is None else abschnitte):
        _abschnitt(sec)

    flowables = _keep_headings_with_next(list(story))
    doc = ManualDoc(ausgabe, pagesize=A4, leftMargin=2*cm, rightMargin=2*cm,
                    topMargin=1.8*cm, bottomMargin=1.8*cm,
                    title=I18N["doc_title"], author=I18N["doc_author"])
    if vorschau:
        doc.multiBuild(flowables, onFirstPage=footer, onLaterPages=footer)
    else:
        doc.multiBuild(flowables, onFirstPage=cover_page, onLaterPages=footer)
    return ausgabe


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    datei = argv[0] if argv else "handbuch-ausgaben-de"
    lang = argv[1] if len(argv) > 1 else "de"
    ausgabe = os.path.join(REPO, "docs", f"{datei}.pdf")
    os.makedirs(os.path.dirname(ausgabe), exist_ok=True)
    erzeuge(lade_sprache(lang), lang, ausgabe)
    print("PDF erzeugt:", ausgabe)
    return 0


if __name__ == "__main__":
    sys.exit(main())
