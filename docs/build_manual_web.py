# -*- coding: utf-8 -*-
"""Erzeugt das Benutzerhandbuch als statische Webseite (eine Seite pro Sprache).

Quelle sind dieselben JSON-Dateien wie für das PDF (build_manual.py). Aufruf:

    python3 docs/build_manual_web.py                 # Ziel: ~/git/kmysync-handbuch (eigenes Repo)
    python3 docs/build_manual_web.py --ziel /pfad    # anderes Ziel

Screenshots werden als WebP in zwei Größen abgelegt (540 px für die Seite, 1080 px für die
Lightbox); unveränderte Bilder werden beim nächsten Lauf übersprungen.
"""
import argparse
import html
import json
import os
import re
import shutil
import sys

from PIL import Image

DOCS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(DOCS)
SPRACHEN = ["de", "en"]
STANDARD_ZIEL = os.path.expanduser("~/git/kmysync-handbuch")
# Adresse der veröffentlichten Seite (GitHub Pages des Handbuch-Repos), nur für canonical/hreflang.
SITE = "https://perfermat.github.io/kmysync-handbuch/"
APP_URL = "https://github.com/PerFermat/KMySync"
IMPRESSUM = "https://michaelspahr.de/impressum.html"
DATENSCHUTZ = "https://michaelspahr.de/datenschutz.html"
FAVICON = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" '
           'fill="#2e7d32"/><path d="M10 7v18M22 7l-9 9 9 9" fill="none" stroke="#fff" stroke-width="3.4" '
           'stroke-linecap="round" stroke-linejoin="round"/></svg>\n')
PDF = {"de": "Handbuch-KMySync-de.pdf", "en": "Manual-KMySync-en.pdf"}
# Die PDFs (je ~13 MB) liegen im KMySync-Repo; die Webseite verlinkt sie dort, statt sie zu kopieren.
PDF_URL = "https://github.com/PerFermat/KMySync/raw/main/docs/"
KLEIN = 540

UI = {
    "de": {"art": "Handbuch", "suche": "Im Handbuch suchen …", "inhalt": "Inhalt", "pdf": "Als PDF",
           "start": "KMySync auf GitHub", "keine": "Keine Treffer", "thema": "Hell/Dunkel umschalten",
           "impressum": "Impressum", "datenschutz": "Datenschutz", "oben": "Nach oben",
           "beschreibung": "Benutzerhandbuch für KMySync, die quelloffene Android-App zum Erfassen "
                           "von Bargeld-Buchungen für KMyMoney.",
           "unterzeile": "Bargeld unterwegs erfassen – und in KMyMoney weiterverarbeiten."},
    "en": {"art": "Manual", "suche": "Search the manual …", "inhalt": "Contents", "pdf": "As PDF",
           "start": "KMySync on GitHub", "keine": "No results", "thema": "Toggle light/dark",
           "impressum": "Legal notice", "datenschutz": "Privacy", "oben": "Back to top",
           "beschreibung": "User manual for KMySync, the open-source Android app for recording "
                           "cash transactions for KMyMoney.",
           "unterzeile": "Record cash on the go – and process it in KMyMoney."},
}


def json_pfad(lang):
    return os.path.join(DOCS, f"handbuch_{lang}.json")


def lade_sprache(lang):
    with open(json_pfad(lang), "r", encoding="utf-8") as f:
        return json.load(f)


def warnung(text):
    print("WARNUNG:", text, file=sys.stderr)


def slug(name):
    name = os.path.splitext(name)[0].lower()
    for a, b in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss")):
        name = name.replace(a, b)
    return re.sub(r"[^a-z0-9]+", "-", name).strip("-")


def inline(text):
    """Das Inline-HTML aus dem JSON bleibt, nur die festen Farben werden zu Klassen (Dunkelmodus)."""
    text = re.sub(r"<font color=['\"]#b00020['\"]>", '<span class="out">', text)
    text = re.sub(r"<font color=['\"]#2e7d32['\"]>", '<span class="in">', text)
    text = re.sub(r"<font[^>]*>", "<span>", text)
    return text.replace("</font>", "</span>").replace("<br/>", "<br>")


class Bilder:
    """Wandelt verwendete Screenshots nach WebP und merkt sich deren Maße."""

    def __init__(self, ziel):
        self.ziel = ziel

    def hole(self, quelle, lang):
        if not os.path.exists(quelle):
            warnung(f"Bild fehlt: {quelle}")
            return None
        name = slug(os.path.basename(quelle))
        ordner = os.path.join(self.ziel, "img", lang)
        os.makedirs(ordner, exist_ok=True)
        gross, klein = os.path.join(ordner, name + ".webp"), os.path.join(ordner, name + "-540.webp")
        with Image.open(quelle) as im:
            b, h = im.size
            kb, kh = (KLEIN, round(h * KLEIN / b)) if b > KLEIN else (b, h)
            if not (os.path.exists(klein) and os.path.getmtime(klein) >= os.path.getmtime(quelle)):
                im = im.convert("RGB")
                im.save(gross, "WEBP", quality=82, method=6)
                im.resize((kb, kh), Image.LANCZOS).save(klein, "WEBP", quality=80, method=6)
        return {"klein": f"img/{lang}/{name}-540.webp", "gross": f"img/{lang}/{name}.webp", "b": kb, "h": kh}


class Seite:
    def __init__(self, lang, daten, bilder):
        self.lang, self.I, self.bilder = lang, daten, bilder
        self.links = True  # abwechselnde Bildseite wie im PDF
        self.wurzel = "" if lang == "de" else "../"  # Seite liegt für en eine Ebene tiefer als img/

    def shot(self, fname):
        return self.bilder.hole(os.path.join(REPO, "screenshots", self.lang, fname), self.lang)

    def figur(self, bild, caption, breit=False):
        cap = inline(caption or "")
        if not bild:
            return (f'<figure class="shot"><div class="missing">{html.escape(self.I["placeholder_no_image"])}</div>'
                    f"<figcaption>{cap}</figcaption></figure>")
        alt = html.escape(re.sub(r"<[^>]+>", "", caption or ""))
        return (f'<figure class="shot{" wide" if breit else ""}"><img src="{self.wurzel}{bild["klein"]}" data-full="{self.wurzel}{bild["gross"]}" '
                f'width="{bild["b"]}" height="{bild["h"]}" alt="{alt}" loading="lazy" decoding="async">'
                f"<figcaption>{cap}</figcaption></figure>")

    def daten_attr(self, paare):
        liste = [[self.wurzel + b["klein"], c] for b, c in paare if b]
        return html.escape(json.dumps(liste, ensure_ascii=False), quote=True) if liste else ""

    def block(self, b):
        t = b["type"]
        if t == "h2":
            return f'<h3 id="{b["id"]}">{inline(b["text"])}</h3>'
        if t == "h3":
            return f'<h4 id="{b["id"]}">{inline(b["text"])}</h4>'
        if t == "p":
            return f"<p>{inline(b['text'])}</p>"
        if t in ("bullets", "steps"):
            tag, cls = ("ul", "") if t == "bullets" else ("ol", ' class="steps"')
            return f"<{tag}{cls}>" + "".join(f"<li>{inline(i)}</li>" for i in b["items"]) + f"</{tag}>"
        if t == "code":
            return f'<pre class="code">{inline(self.I["code_example"]).replace("<br>", chr(10))}</pre>'
        if t == "symbols_table":
            kopf = "".join(f"<th>{h}</th>" for h in self.I["table_headers"])
            zeilen = "".join(f"<tr><td>{s}</td><td><b>{n}</b></td><td>{inline(d)}</td></tr>"
                             for s, n, d in self.I["symbols"])
            return (f'<div class="table-wrap"><table class="sym"><thead><tr>{kopf}</tr></thead>'
                    f'<tbody>{zeilen}</tbody></table></div><p class="note">{inline(self.I["table_note"])}</p>')
        if t == "shot_row":
            paare = [(self.shot(s["fname"]), s["caption"]) for s in b["shots"]]
            return (f'<div class="shot-row has-shot" data-shots="{self.daten_attr(paare)}">'
                    + "".join(self.figur(bi, c) for bi, c in paare) + "</div>")
        if t in ("text_with_single_shot", "text_with_shot_row", "text_with_pic"):
            text = "".join(self.block(c) for c in b["content"])
            if t == "text_with_pic":
                paare = [(self.bilder.hole(os.path.join(REPO, b["pic"]["relpath"]), self.lang), b["pic"]["caption"])]
                return (f'<div class="has-shot" data-shots="{self.daten_attr(paare)}">{text}'
                        f"{self.figur(paare[0][0], paare[0][1], breit=True)}</div>")
            if t == "text_with_shot_row":
                paare = [(self.shot(s["fname"]), s["caption"]) for s in b["shots"]]
                return (f'<div class="has-shot" data-shots="{self.daten_attr(paare)}">{text}<div class="shot-row">'
                        + "".join(self.figur(bi, c) for bi, c in paare) + "</div></div>")
            paare = [(self.shot(b["shot"]["fname"]), b["shot"]["caption"])]
            seite = "" if self.links else " flip"
            self.links = not self.links
            return (f'<div class="pair has-shot{seite}" data-shots="{self.daten_attr(paare)}">'
                    f"<div>{text}</div>{self.figur(*paare[0])}</div>")
        warnung(f"unbekannter Blocktyp {t} ({b.get('id')})")
        return ""

    def kapitel(self):
        kap, cur = [], None
        for s in self.I["sections"]:
            if s["type"] == "h1":
                cur = {"id": s["id"], "titel": s["text"], "bloecke": [], "unter": []}
                kap.append(cur)
            elif cur:
                cur["bloecke"].append(s)
                if s["type"] == "h2":
                    cur["unter"].append(s)
        return kap

    def html(self):
        I, U, lang = self.I, UI[self.lang], self.lang
        wurzel = self.wurzel
        andere = [l for l in SPRACHEN if l != lang][0]
        andere_href = ("en/" if andere == "en" else "../")
        kap = self.kapitel()

        toc, inhalt = [], []
        for k in kap:
            unter = "".join(f'<li><a href="#{u["id"]}">{inline(u["text"])}</a></li>' for u in k["unter"])
            toc.append(f'<li data-ch="{k["id"]}"><a href="#{k["id"]}">{inline(k["titel"])}</a>'
                       + (f"<ul>{unter}</ul>" if unter else "") + "</li>")
            self.links = True
            koerper = "".join(self.block(b) for b in k["bloecke"])
            inhalt.append(f'<section class="chap" id="{k["id"]}"><h2 class="ch">{inline(k["titel"])}</h2>{koerper}</section>')

        sprachen = "".join(
            f'<a href="{andere_href if l != lang else "./"}" class="{"on" if l == lang else "alt"}" hreflang="{l}" '
            f'lang="{l}">{l.upper()}</a>' for l in SPRACHEN)
        pdf = f'<a class="btn" href="{PDF_URL}{PDF[lang]}">↓ {U["pdf"]}</a>'
        return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(I["doc_title"])}</title>
<meta name="description" content="{U["beschreibung"]}">
<link rel="canonical" href="{SITE}{"" if lang == "de" else "en/"}">
<link rel="alternate" hreflang="de" href="{SITE}">
<link rel="alternate" hreflang="en" href="{SITE}en/">
<meta property="og:title" content="{html.escape(I["doc_title"])}">
<meta property="og:description" content="{U["beschreibung"]}">
<meta name="theme-color" content="#2e7d32">
<link rel="icon" href="{wurzel}favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="{wurzel}handbuch.css">
<script>(function(){{var t=null;try{{t=localStorage.getItem('theme')}}catch(e){{}}document.documentElement.setAttribute('data-theme',t||'dark')}})();</script>
</head>
<body>
<header class="top"><div class="top-in">
  <button class="icon-btn toc-btn" id="tocBtn" aria-label="{U["inhalt"]}" aria-controls="toc" aria-expanded="false">☰</button>
  <a class="brand" href="#top"><i>K</i><span>KMySync <small>{U["art"]}</small></span></a>
  <div class="search"><input id="q" type="search" placeholder="{U["suche"]}" aria-label="{U["suche"]}" autocomplete="off"><div class="results" id="res" data-none="{U["keine"]}"></div></div>
  <nav class="langs" aria-label="Sprache / Language">{sprachen}</nav>
  <button class="icon-btn" id="theme" title="{U["thema"]}" aria-label="{U["thema"]}"><svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 3.5a8.5 8.5 0 0 1 0 17z" fill="currentColor"/></svg></button>
</div></header>
<div class="shell" id="top">
  <nav class="toc" id="toc" aria-label="{U["inhalt"]}"><h5>{U["inhalt"]}</h5><ol>{"".join(toc)}</ol></nav>
  <main class="doc">
    <div class="hero"><h1>{html.escape(I["doc_title"])}</h1><p>{U["unterzeile"]}</p>
      <div class="meta"><span class="pill">{I["version_text"]}</span><span class="pill">{I["date_text"]}</span>{pdf}</div></div>
    {"".join(inhalt)}
  </main>
  <aside class="stage" aria-hidden="true"><div class="phone"><img id="ph" alt=""></div><div class="dots" id="dots"></div><div class="cap" id="phcap"></div></aside>
</div>
<footer class="foot"><a href="{APP_URL}">{U["start"]} ↗</a><span>{html.escape(I["footer_text"])}</span>
  <span><a href="{IMPRESSUM}">{U["impressum"]}</a> · <a href="{DATENSCHUTZ}">{U["datenschutz"]}</a></span></footer>
<div class="lb" id="lb" role="dialog" aria-modal="true"><img alt=""><p></p></div>
<script src="{wurzel}handbuch.js"></script>
</body>
</html>
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--ziel", default=STANDARD_ZIEL)
    ziel = os.path.abspath(ap.parse_args().ziel)
    os.makedirs(ziel, exist_ok=True)
    bilder = Bilder(ziel)
    for datei in ("handbuch.css", "handbuch.js"):
        shutil.copy2(os.path.join(DOCS, "web", datei), os.path.join(ziel, datei))
    with open(os.path.join(ziel, "favicon.svg"), "w", encoding="utf-8") as f:
        f.write(FAVICON)
    open(os.path.join(ziel, ".nojekyll"), "w").close()  # GitHub Pages: Dateien unverändert ausliefern
    for lang in SPRACHEN:
        out = ziel if lang == "de" else os.path.join(ziel, lang)
        os.makedirs(out, exist_ok=True)
        text = Seite(lang, lade_sprache(lang), bilder).html()
        with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
            f.write(text)
        print(f"{lang}: {os.path.join(out, 'index.html')}")


if __name__ == "__main__":
    main()
