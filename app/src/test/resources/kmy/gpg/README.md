# GPG-Prüflinge

- `testschluessel.asc` – privater Testschlüssel, **nur für Tests**, Passphrase `michael`.
  Hauptschlüssel `E77040F3ADA7FB54`, Unterschlüssel zum Verschlüsseln `B06AB31369FE6554`.
- `edited.kmy` – `../edited.xml`, mit GnuPG 2.4.4 an diesen Schlüssel verschlüsselt:

  ```bash
  gpg --armor -r B06AB31369FE6554 -o edited.kmy -e ../edited.xml
  ```

  Gleicher Aufbau wie eine von KMyMoney 5.2.2 verschlüsselte .kmy: Text („ASCII-armored“),
  AEAD-Paket (AES-256, OCB), ZLIB, darin das XML.

Die echte, von KMyMoney gespeicherte Datei ist zu groß fürs Repo; `KmyGpgTest` prüft sie mit
`./gradlew … -Dkmy.gpg=/pfad/test.verschluesselt.kmy`.
