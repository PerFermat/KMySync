# -*- coding: utf-8 -*-
"""Erzeugt das Benutzerhandbuch als statische Webseite (eine Seite pro Sprache).

Quelle sind dieselben JSON-Dateien wie für das PDF (build_manual.py). Aufruf:

    python3 docs/build_manual_web.py                 # Ziel: ~/git/kmysync-handbuch (eigenes Repo)
    python3 docs/build_manual_web.py --ziel /pfad    # anderes Ziel

Screenshots werden als WebP in zwei Größen abgelegt (540 px für die Seite, 1080 px für die
Lightbox); unveränderte Bilder werden beim nächsten Lauf übersprungen.
"""
import argparse
import datetime
import html
import json
import os
import re
import shutil
import sys
import urllib.request

from PIL import Image, ImageDraw, ImageFont

DOCS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(DOCS)
SPRACHEN = ["de", "en"]
STANDARD_ZIEL = os.path.expanduser("~/git/kmysync-handbuch")
# Adresse der veröffentlichten Seite (Upload mit deploy-server.sh im Handbuch-Repo), für canonical/hreflang.
SITE = "https://kmysync.michaelspahr.de/"
APP_URL = "https://github.com/PerFermat/KMySync"
IMPRESSUM = "https://michaelspahr.de/impressum.html"
DATENSCHUTZ = "https://michaelspahr.de/datenschutz.html"
FAVICON = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" '
           'fill="#2e7d32"/><path d="M10 7v18M22 7l-9 9 9 9" fill="none" stroke="#fff" stroke-width="3.4" '
           'stroke-linecap="round" stroke-linejoin="round"/></svg>\n')
# Zeigt immer auf das neueste Release, auch wenn die Seite noch nicht neu erzeugt ist.
RELEASE_URL = APP_URL + "/releases/latest"
RELEASE_API = "https://api.github.com/repos/PerFermat/KMySync/releases/latest"
AUTOR = {"@type": "Person", "name": "Michael Spahr", "url": "https://michaelspahr.de/"}
SCHRIFT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SCHRIFT_FETT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
PDF = {"de": "Handbuch-KMySync-de.pdf", "en": "Manual-KMySync-en.pdf"}
# Die PDFs (je ~13 MB) liegen im KMySync-Repo; die Webseite verlinkt sie dort, statt sie zu kopieren.
PDF_URL = "https://github.com/PerFermat/KMySync/raw/main/docs/"
KLEIN = 540

UI = {
    "de": {"art": "Handbuch", "suche": "Im Handbuch suchen …", "inhalt": "Inhalt", "pdf": "Als PDF",
           "start": "KMySync auf GitHub", "keine": "Keine Treffer", "thema": "Hell/Dunkel umschalten",
           "impressum": "Impressum", "datenschutz": "Datenschutz", "oben": "Nach oben",
           "titel": "KMySync Handbuch – Bargeld für KMyMoney erfassen (Android-App)",
           "beschreibung": "Handbuch zu KMySync, der kostenlosen Open-Source-App nur für Android und Wear OS "
                           "(kein iPhone): Bargeld und Depot erfassen, mit KMyMoney synchronisieren.",
           "plattform": "<b>Nur für Android</b> (ab 8.0) und Wear OS (ab 3) – für iPhone/iOS gibt es KMySync nicht.",
           "og_plattform": "Nur für Android & Wear OS – nicht für iPhone",
           "release": "Neueste Version", "release_titel": "Neueste Version von KMySync auf GitHub herunterladen",
           "og_unter": "Benutzerhandbuch", "og_zeile": "Bargeld unterwegs erfassen –\nund in KMyMoney weiterverarbeiten.",
           "locale": "de_DE",
           "unterzeile": "Bargeld unterwegs erfassen – und in KMyMoney weiterverarbeiten."},
    "en": {"art": "Manual", "suche": "Search the manual …", "inhalt": "Contents", "pdf": "As PDF",
           "start": "KMySync on GitHub", "keine": "No results", "thema": "Toggle light/dark",
           "impressum": "Legal notice", "datenschutz": "Privacy", "oben": "Back to top",
           "titel": "KMySync Manual – record cash for KMyMoney (Android app)",
           "beschreibung": "Manual for KMySync, the free open-source app for Android and Wear OS only "
                           "(no iPhone): record cash and securities and sync them with KMyMoney.",
           "plattform": "<b>Android only</b> (8.0 and later) and Wear OS (3 and later) – there is no KMySync for iPhone/iOS.",
           "og_plattform": "Android & Wear OS only – not for iPhone",
           "release": "Latest version", "release_titel": "Download the latest KMySync version from GitHub",
           "og_unter": "User Manual", "og_zeile": "Record cash on the go –\nand process it in KMyMoney.",
           "locale": "en_US",
           "unterzeile": "Record cash on the go – and process it in KMyMoney."},
}


def neueste_version():
    """Name des neuesten Releases (z. B. "KMySync 2.1.1") – ohne Netz einfach None."""
    try:
        req = urllib.request.Request(RELEASE_API, headers={"Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=8) as r:
            daten = json.load(r)
        return daten.get("name") or daten.get("tag_name")
    except Exception as e:  # Seite trotzdem erzeugen, Knopf dann ohne Nummer
        warnung(f"neueste Version nicht abrufbar ({e})")
        return None


def vorschaubild(ziel, lang, bild_pfad):
    """Vorschaubild 1200×630 für Links in Suchmaschinen, Messengern und sozialen Netzen."""
    U = UI[lang]
    b, h = 1200, 630
    im = Image.new("RGB", (b, h), "#1b4d1e")
    d = ImageDraw.Draw(im)
    for y in range(h):  # sanfter Verlauf von Dunkel- nach PDF-Grün
        t = y / h
        d.line([(0, y), (b, y)], fill=(int(0x1b + t * 0x13), int(0x4d + t * 0x30), int(0x1e + t * 0x14)))
    d.rounded_rectangle((70, 70, 150, 150), 18, fill="#ffffff")
    d.line((96, 88, 96, 132), fill="#2e7d32", width=9)
    d.line((126, 88, 102, 110), fill="#2e7d32", width=9)
    d.line((102, 110, 126, 132), fill="#2e7d32", width=9)
    d.text((70, 190), "KMySync", font=ImageFont.truetype(SCHRIFT_FETT, 92), fill="#ffffff")
    d.text((74, 300), U["og_unter"], font=ImageFont.truetype(SCHRIFT, 50), fill="#d8efd9")
    d.multiline_text((74, 400), U["og_zeile"], font=ImageFont.truetype(SCHRIFT, 32), fill="#ffffff", spacing=12)
    d.text((74, 530), U["og_plattform"], font=ImageFont.truetype(SCHRIFT_FETT, 28), fill="#ffffff")
    if os.path.exists(bild_pfad):
        with Image.open(bild_pfad) as shot:
            sh = 560
            shot = shot.convert("RGB").resize((round(shot.width * sh / shot.height), sh), Image.LANCZOS)
            rahmen = Image.new("RGB", (shot.width + 20, sh + 20), "#111111")
            maske = Image.new("L", rahmen.size, 0)
            ImageDraw.Draw(maske).rounded_rectangle((0, 0, *rahmen.size), 34, fill=255)
            rahmen.paste(shot, (10, 10))
            im.paste(rahmen, (b - rahmen.width - 110, (h - rahmen.height) // 2), maske)
    name = f"og-{lang}.png"
    im.save(os.path.join(ziel, name), optimize=True)
    return name


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
    def __init__(self, lang, daten, bilder, version=None, og=None):
        self.lang, self.I, self.bilder = lang, daten, bilder
        self.version, self.og = version, og
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
        pdf = f'<a class="btn ghost" href="{PDF_URL}{PDF[lang]}">↓ {U["pdf"]}</a>'
        release = (f'<a class="btn" href="{RELEASE_URL}" title="{U["release_titel"]}">'
                   f'<svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>'
                   f'{U["release"]}{": " + html.escape(self.version) if self.version else ""}</a>')
        url = SITE + ("" if lang == "de" else "en/")
        og_bild = SITE + self.og if self.og else ""
        heute = datetime.date.today().isoformat()
        ld = {"@context": "https://schema.org", "@graph": [
            {"@type": "TechArticle", "@id": url + "#handbuch", "url": url, "inLanguage": lang,
             "headline": I["doc_title"], "name": U["titel"], "description": U["beschreibung"],
             "dateModified": heute, "author": AUTOR, "publisher": AUTOR,
             "image": og_bild or None, "about": {"@id": SITE + "#app"},
             "isPartOf": {"@type": "WebSite", "@id": SITE + "#site", "url": SITE, "name": "KMySync"}},
            {"@type": "SoftwareApplication", "@id": SITE + "#app", "name": "KMySync",
             "operatingSystem": "Android 8.0+, Wear OS 3+", "applicationCategory": "FinanceApplication",
             "softwareVersion": (self.version or "").replace("KMySync ", "") or None,
             "downloadUrl": RELEASE_URL, "url": SITE, "sameAs": APP_URL, "author": AUTOR,
             "license": "https://www.gnu.org/licenses/gpl-3.0.html",
             "offers": {"@type": "Offer", "price": "0", "priceCurrency": "EUR"}},
        ]}
        for knoten in ld["@graph"]:  # leere Angaben weglassen
            for k in [k for k, v in knoten.items() if v is None]:
                del knoten[k]
        ld_json = json.dumps(ld, ensure_ascii=False).replace("</", "<\\/")
        return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(U["titel"])}</title>
<meta name="description" content="{html.escape(U["beschreibung"])}">
<meta name="author" content="Michael Spahr">
<link rel="canonical" href="{url}">
<link rel="alternate" hreflang="de" href="{SITE}">
<link rel="alternate" hreflang="en" href="{SITE}en/">
<link rel="alternate" hreflang="x-default" href="{SITE}">
<meta property="og:type" content="article">
<meta property="og:site_name" content="KMySync">
<meta property="og:url" content="{url}">
<meta property="og:locale" content="{U["locale"]}">
<meta property="og:title" content="{html.escape(U["titel"])}">
<meta property="og:description" content="{html.escape(U["beschreibung"])}">
{f'<meta property="og:image" content="{og_bild}"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">' if og_bild else ""}
<meta name="twitter:card" content="summary_large_image">
<script type="application/ld+json">{ld_json}</script>
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
      <p class="plattform"><svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M17.6 9.48l1.84-3.18c.16-.31.04-.69-.26-.85a.64.64 0 00-.83.22l-1.88 3.24a11.43 11.43 0 00-8.94 0L5.65 5.67a.64.64 0 00-.87-.2c-.28.18-.37.54-.22.83L6.4 9.48A10.81 10.81 0 001 18h22a10.81 10.81 0 00-5.4-8.52zM7 15.25a1.25 1.25 0 110-2.5 1.25 1.25 0 010 2.5zm10 0a1.25 1.25 0 110-2.5 1.25 1.25 0 010 2.5z"/></svg><span>{U["plattform"]}</span></p>
      <div class="meta">{release}{pdf}<span class="pill">{I["version_text"]}</span><span class="pill">{I["date_text"]}</span></div></div>
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
    version = neueste_version()
    for lang in SPRACHEN:
        out = ziel if lang == "de" else os.path.join(ziel, lang)
        os.makedirs(out, exist_ok=True)
        og = vorschaubild(ziel, lang, os.path.join(REPO, "screenshots", lang, "Kontobuchungen.png"))
        text = Seite(lang, lade_sprache(lang), bilder, version, og).html()
        with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
            f.write(text)
        print(f"{lang}: {os.path.join(out, 'index.html')}")
    suchmaschinen(ziel)


def suchmaschinen(ziel):
    """robots.txt und sitemap.xml (mit Sprachverweisen), damit Google beide Sprachen findet."""
    heute = datetime.date.today().isoformat()
    alternativen = "".join(f'\n    <xhtml:link rel="alternate" hreflang="{l}" href="{SITE}{"" if l == "de" else l + "/"}"/>'
                           for l in SPRACHEN)
    urls = "".join(f"""
  <url>
    <loc>{SITE}{"" if l == "de" else l + "/"}</loc>
    <lastmod>{heute}</lastmod>{alternativen}
  </url>""" for l in SPRACHEN)
    with open(os.path.join(ziel, "sitemap.xml"), "w", encoding="utf-8") as f:
        f.write(f"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">{urls}
</urlset>
""")
    with open(os.path.join(ziel, "robots.txt"), "w", encoding="utf-8") as f:
        f.write(f"User-agent: *\nAllow: /\n\nSitemap: {SITE}sitemap.xml\n")


if __name__ == "__main__":
    main()
