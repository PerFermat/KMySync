# Regeln für R8. Jede Zeile hier ist eine Einschränkung – was geschützt wird, kann nicht
# wegoptimiert werden. Deshalb steht hier nur, was nachweislich gebraucht wird, und daneben, woher
# das bekannt ist.
#
# NICHT hier stehen Regeln, die die Bibliotheken selbst mitbringen: room-runtime, okhttp,
# pdfbox-android und play-services-wearable liefern eigene consumer-rules, die AGP von sich aus
# einsammelt. Sie zu wiederholen bringt nichts und verschleiert, was wirklich nötig ist.
#
# Ebenfalls nicht hier: die im Manifest eingetragenen Klassen (Activities, Widgets, Receiver,
# AusgabenApp). Die schützt R8 aus der Manifest-Analyse selbst.
#
# Pauschale Regeln wie `-keep class org.bouncycastle.** { *; }` wären naheliegend und genau falsch:
# BouncyCastle ist der größte Brocken im APK. Ein Pauschalschutz darauf hebt den Sinn der ganzen
# Übung auf. Fehlt etwas, gehört die Regel so eng gefasst, wie der Stapelauszug es zeigt – und der
# Auszug als Begründung daneben.

# ---------------------------------------------------------------------------
# R.string wird reflektiv durchlaufen
# ---------------------------------------------------------------------------
# LocaleManager.seed() iteriert über R.string.class.getFields() und benutzt field.getName() als
# Schlüssel der Übersetzungstabelle in der Datenbank – für alle 867 Texte der App. R8 kann das nicht
# sehen: Für den Optimierer ist jedes R-Feld eine Konstante, die nach dem Einsetzen entfallen darf.
# Ohne diese Regel stünde die Übersetzungstabelle leer, und zwar erst zur Laufzeit auf dem Gerät des
# Nutzers – die App zeigte dann überall nur noch die eingebauten Ressourcentexte.
#
# Das Gegenstück auf der Ressourcenseite steht in res/raw/keep.xml: shrinkResources wirft weg, was
# es für unreferenziert hält, und über diese Reflexion sieht es keine einzige Referenz.
#
# Diese Regel hat zwei engere Fassungen hinter sich, und beide waren wirkungslos – nachgesehen im
# fertigen APK, nicht erschlossen:
#
#   -keepclassmembers class …R$string { public static final int *; }
#       behält Felder nur, *wenn die Klasse erhalten bleibt*. R8 entfernte sie vollständig.
#   -keep class …R$string { public static final int *; }
#       hielt die Klasse, aber keinen einzigen Feldnamen – die Felder passen nicht auf das Muster.
#
# Erst `{ *; }` hält beides. Geprüft wurde, indem alle 867 Namen aus values/strings.xml im
# Release-Dex gesucht wurden: 867 von 867. Wer diese Regel enger fassen will, prüfe genauso nach –
# ein Fehler hier bricht nicht den Bau, sondern lässt die Übersetzungstabelle beim ersten Start des
# Nutzers leer.
-keep class de.spahr.ausgaben.R$string { *; }

# ---------------------------------------------------------------------------
# Klassen, die es auf Android gar nicht gibt
# ---------------------------------------------------------------------------
# Diese beiden Regeln schützen nichts – sie kosten also auch nichts. Sie sagen R8 nur, dass das
# Fehlen dieser Klassen erwartet ist. Ohne sie bricht `minifyFossReleaseWithR8` mit
# „Compilation failed to complete" ab; die Namen unten stammen wörtlich aus dessen Ausgabe.
#
# javax.el – die Expression Language aus Java EE. smbj bringt den Nachrichtenverteiler mbassy mit,
# und der kann Empfänger wahlweise über EL-Ausdrücke filtern (net.engio.mbassy.dispatch.el.ElFilter).
# Auf Android gibt es javax.el nicht, und die App benutzt diese Filter auch nicht.
-dontwarn javax.el.**

# org.ietf.jgss – die GSS-API für Kerberos. smbj bietet damit Kerberos-Anmeldung an
# (com.hierynomus.smbj.auth.SpnegoAuthenticator). Android liefert das Paket nicht mit; die App meldet
# sich per NTLM an.
-dontwarn org.ietf.jgss.**

# com.gemalto.jp2 – ein JPEG-2000-Decoder, den pdfbox optional einbindet
# (com.tom_roush.pdfbox.filter.JPXFilter). Die Abhängigkeit ist nicht mitgezogen: Die App liest aus
# PDFs nur die Textebene, keine Bilder. Ein JPEG-2000-Bild im Beleg bleibt also ungelesen – so war es
# schon vor R8.
-dontwarn com.gemalto.jp2.**
