package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Den Dokumentenscanner der Google-Dienste gibt es nur in der Variante „full" – und genau dort, wo
 * seine Bibliothek im Build steckt. In „foss" (F-Droid) darf sie nicht einmal auf dem Klassenpfad
 * liegen; läge die Klasse der einen Variante im Ordner der anderen, fiele es hier auf.
 */
public class DocumentScanTest {

    private static boolean bibliothekImBuild() {
        try {
            Class.forName("com.google.mlkit.vision.documentscanner.GmsDocumentScanning");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Test
    public void scannerGenauDortWoSeineBibliothekIst() {
        assertEquals(bibliothekImBuild(), DocumentScan.available());
    }
}
