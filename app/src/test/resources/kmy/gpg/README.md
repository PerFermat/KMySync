# GPG-Prüflinge

- `testschluessel.asc` – privater Testschlüssel, **nur für Tests**, Passphrase `michael`.
  Hauptschlüssel `E77040F3ADA7FB54`, Unterschlüssel zum Verschlüsseln `B06AB31369FE6554`.
- `edited.kmy` – `../edited.xml`, mit GnuPG 2.4.4 an diesen Schlüssel verschlüsselt:

  ```bash
  gpg --armor -r B06AB31369FE6554 -o edited.kmy -e ../edited.xml
  ```

  Gleicher Aufbau wie eine von KMyMoney 5.2.2 verschlüsselte .kmy: Text („ASCII-armored“),
  AEAD-Paket (AES-256, OCB), ZLIB, darin das XML.

- `nur-unterschluessel.asc` – derselbe Schlüssel, aber nur der Unterschlüssel zum Verschlüsseln; der
  Hauptschlüssel steht darin als leerer Platzhalter („GNU-dummy“). Das empfohlene Mindestmaß fürs Handy:

  ```bash
  gpg --batch --pinentry-mode loopback --passphrase michael \
      --export-secret-subkeys --armor 'B06AB31369FE6554!' > nur-unterschluessel.asc
  ```

  `edited.kmy` ist mit `-f` eingecheckt, weil `.gitignore` alle `.kmy` ausschließt.

Die echte, von KMyMoney gespeicherte Datei ist zu groß fürs Repo; `KmyGpgTest` prüft sie mit
`./gradlew … -Dkmy.gpg=/pfad/test.verschluesselt.kmy`.
