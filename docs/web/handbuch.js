/* KMySync-Handbuch (Webseite): Hell/Dunkel, Inhaltsschublade, Scroll-Spy, mitlaufendes Handy,
   Lightbox, Volltextsuche. Wird von docs/build_manual_web.py ins Ziel kopiert. */
(function () {
  'use strict';
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var body = document.body, root = document.documentElement;

  // --- Hell/Dunkel: derselbe Schlüssel wie auf der Homepage ---
  $('#theme').addEventListener('click', function () {
    var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('theme', next); } catch (e) {}
  });

  // --- Sprachwechsel bleibt am selben Abschnitt (Block-IDs sind in allen Sprachen gleich) ---
  $$('.langs a.alt').forEach(function (a) {
    a.addEventListener('click', function () { a.href = a.href.split('#')[0] + location.hash; });
  });

  // --- Inhaltsschublade (schmale Bildschirme) ---
  var tocBtn = $('#tocBtn');
  function toc(open) { body.classList.toggle('toc-open', open); tocBtn.setAttribute('aria-expanded', open); }
  tocBtn.addEventListener('click', function () { toc(!body.classList.contains('toc-open')); });
  $('#toc').addEventListener('click', function (e) { if (e.target.closest('a')) toc(false); });
  document.addEventListener('click', function (e) {
    if (body.classList.contains('toc-open') && !e.target.closest('#toc, #tocBtn')) toc(false);
  });

  // --- Scroll-Spy: aktuelles Kapitel und Unterabschnitt im Inhaltsverzeichnis ---
  var spy = '-70px 0px -70% 0px';
  var tocItems = {};
  $$('#toc li[data-ch]').forEach(function (li) { tocItems[li.getAttribute('data-ch')] = li; });
  var chapObs = new IntersectionObserver(function (es) {
    es.forEach(function (e) {
      if (!e.isIntersecting) return;
      $$('#toc li.cur, #toc ul a.cur').forEach(function (el) { el.classList.remove('cur'); });
      var li = tocItems[e.target.id];
      if (li) {
        li.classList.add('cur');
        // nur das Verzeichnis selbst rollen, nie die Seite
        var nav = $('#toc'), top = li.offsetTop - nav.offsetTop;
        if (top < nav.scrollTop + 30 || top > nav.scrollTop + nav.clientHeight - 80) nav.scrollTop = top - 60;
      }
    });
  }, { rootMargin: spy });
  $$('section.chap').forEach(function (s) { chapObs.observe(s); });
  var subObs = new IntersectionObserver(function (es) {
    es.forEach(function (e) {
      if (!e.isIntersecting) return;
      $$('#toc ul a').forEach(function (a) { a.classList.toggle('cur', a.getAttribute('href') === '#' + e.target.id); });
    });
  }, { rootMargin: spy });
  $$('main h3[id]').forEach(function (h) { subObs.observe(h); });

  // Sprung auf einen Anker beim Laden (z. B. nach dem Sprachwechsel) auch bei spät gesetztem Layout
  if (location.hash.length > 1) {
    var ziel = document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (ziel) requestAnimationFrame(function () { ziel.scrollIntoView({ behavior: 'instant' }); });
  }

  // --- Lightbox ---
  var lb = $('#lb');
  function openLb(src, cap) { $('img', lb).src = src; $('p', lb).textContent = cap || ''; lb.classList.add('open'); }
  lb.addEventListener('click', function () { lb.classList.remove('open'); });
  $$('figure.shot img').forEach(function (img) {
    img.addEventListener('click', function () { openLb(img.getAttribute('data-full') || img.src, img.alt); });
  });

  // --- Mitlaufendes Handy (nur breite Ansicht sichtbar) ---
  var ph = $('#ph'), cap = $('#phcap'), dots = $('#dots'), current = null, full = {};
  $$('figure.shot img').forEach(function (img) { full[img.getAttribute('src')] = img.getAttribute('data-full'); });
  function show(src, text) {
    if (ph.getAttribute('src') === src) return;
    ph.classList.add('fade');
    setTimeout(function () {
      ph.onload = function () { ph.classList.remove('fade'); };
      ph.src = src; cap.textContent = text || '';
    }, 160);
  }
  function pick(el) {
    if (el === current) return;
    var list;
    try { list = JSON.parse(el.getAttribute('data-shots') || '[]'); } catch (e) { list = []; }
    if (!list.length) return;
    if (current) current.classList.remove('active');
    current = el; el.classList.add('active');
    show(list[0][0], list[0][1]);
    dots.innerHTML = '';
    if (list.length > 1) list.forEach(function (s, i) {
      var b = document.createElement('button');
      b.title = s[1] || ''; if (!i) b.className = 'on';
      b.addEventListener('click', function () {
        $$('button', dots).forEach(function (x) { x.classList.remove('on'); });
        b.classList.add('on'); show(s[0], s[1]);
      });
      dots.appendChild(b);
    });
  }
  var shotEls = $$('main [data-shots]').filter(function (el) { return el.getAttribute('data-shots'); });
  var shotObs = new IntersectionObserver(function (es) {
    es.forEach(function (e) { if (e.isIntersecting) pick(e.target); });
  }, { rootMargin: '-35% 0px -55% 0px' });
  shotEls.forEach(function (el) { shotObs.observe(el); });
  if (shotEls[0]) pick(shotEls[0]);
  $('.phone').addEventListener('click', function () {
    var src = ph.getAttribute('src'); if (src) openLb(full[src] || src, cap.textContent);
  });

  // --- Volltextsuche: Index aus dem Text der Seite, je Überschrift ein Eintrag ---
  var index = [], entry = null, chapter = '';
  $$('main h2.ch, main h3, main h4, main p, main li, main td, main pre, main figcaption').forEach(function (el) {
    if (el.matches('h2.ch, h3')) {
      if (el.matches('h2.ch')) chapter = el.textContent;
      entry = { id: el.id || el.closest('section').id, title: el.textContent,
                path: el.matches('h3') ? chapter : '', text: '' };
      index.push(entry);
    } else if (entry) {
      entry.text += ' ' + el.textContent.replace(/\s+/g, ' ');
    }
  });
  var q = $('#q'), res = $('#res'), sel = -1;
  function esc(t) { return t.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function mark(t, v) {
    var i = t.toLowerCase().indexOf(v);
    return i < 0 ? esc(t) : esc(t.slice(0, i)) + '<mark>' + esc(t.slice(i, i + v.length)) + '</mark>' + esc(t.slice(i + v.length));
  }
  function search() {
    var v = q.value.trim().toLowerCase(); sel = -1;
    if (v.length < 2) { res.classList.remove('open'); return; }
    var hits = index.filter(function (e) { return (e.title + ' ' + e.text).toLowerCase().indexOf(v) >= 0; })
      .sort(function (a, b) { return (b.title.toLowerCase().indexOf(v) >= 0) - (a.title.toLowerCase().indexOf(v) >= 0); })
      .slice(0, 14);
    res.innerHTML = hits.length ? hits.map(function (h) {
      var p = h.text.toLowerCase().indexOf(v), snip = p < 0 ? h.text.slice(0, 110)
        : (p > 40 ? '…' : '') + h.text.slice(Math.max(0, p - 40), p + 80) + '…';
      return '<a href="#' + h.id + '">' + mark(h.title, v) + '<small>' + (h.path ? esc(h.path) + ' · ' : '') + mark(snip.trim(), v) + '</small></a>';
    }).join('') : '<div>' + esc(res.getAttribute('data-none')) + '</div>';
    res.classList.add('open');
  }
  function go(a) {
    res.classList.remove('open'); q.blur();
    var t = document.getElementById(a.getAttribute('href').slice(1));
    if (!t) return;
    history.pushState(null, '', a.getAttribute('href'));
    t.scrollIntoView();
    t.classList.remove('flash'); void t.offsetWidth; t.classList.add('flash');
  }
  q.addEventListener('input', search);
  q.addEventListener('focus', search);
  q.addEventListener('keydown', function (e) {
    var links = $$('a', res);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      sel = Math.max(0, Math.min(links.length - 1, sel + (e.key === 'ArrowDown' ? 1 : -1)));
      links.forEach(function (l, i) { l.classList.toggle('sel', i === sel); });
      if (links[sel]) links[sel].scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'Enter' && links.length) {
      e.preventDefault(); go(links[Math.max(sel, 0)]);
    } else if (e.key === 'Escape') {
      res.classList.remove('open'); q.blur();
    }
  });
  res.addEventListener('click', function (e) { var a = e.target.closest('a'); if (a) { e.preventDefault(); go(a); } });
  document.addEventListener('click', function (e) { if (!e.target.closest('.search')) res.classList.remove('open'); });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { lb.classList.remove('open'); toc(false); }
    if (e.key === '/' && document.activeElement !== q) { e.preventDefault(); q.focus(); }
  });
})();
