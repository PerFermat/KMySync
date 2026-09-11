#!/bin/bash
# Setzt die App-Sprache direkt in den Einstellungen. Der Umweg ueber das Auswahlfeld scheitert,
# weil Android-Popups in einem eigenen Fenster liegen, das uiautomator nicht mitliefert.
set -e
CODE=$1
adb shell am force-stop de.spahr.ausgaben
sleep 1
adb shell run-as de.spahr.ausgaben sh -c "'sed -i \"s|<string name=\\\"language\\\">[a-z]*</string>|<string name=\\\"language\\\">$CODE</string>|\" shared_prefs/ausgaben_settings.xml'"
adb shell run-as de.spahr.ausgaben cat shared_prefs/ausgaben_settings.xml | tr -d '\r' | grep -o '<string name="language">[a-z]*</string>'
