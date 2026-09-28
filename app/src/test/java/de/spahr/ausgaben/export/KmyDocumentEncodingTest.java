package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * {@link KmyDocument#declaredNonUtf8Encoding}: reine String-Logik, kein Android nötig. Die App liest
 * immer als UTF-8 (siehe {@code gunzip}) – eine abweichende Deklaration in der Datei selbst soll
 * auffallen, statt still zu Zeichenmüll zu führen.
 */
public class KmyDocumentEncodingTest {

    @Test
    public void utf8DeclarationIsFine() {
        assertNull(KmyDocument.declaredNonUtf8Encoding(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><KMYMONEY-FILE></KMYMONEY-FILE>"));
    }

    @Test
    public void caseInsensitiveUtf8IsFine() {
        assertNull(KmyDocument.declaredNonUtf8Encoding(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><KMYMONEY-FILE></KMYMONEY-FILE>"));
    }

    @Test
    public void missingDeclarationIsFine() {
        assertNull(KmyDocument.declaredNonUtf8Encoding("<KMYMONEY-FILE></KMYMONEY-FILE>"));
    }

    @Test
    public void nullIsFine() {
        assertNull(KmyDocument.declaredNonUtf8Encoding(null));
    }

    @Test
    public void emptyIsFine() {
        assertNull(KmyDocument.declaredNonUtf8Encoding(""));
    }

    @Test
    public void detectsOtherEncoding() {
        assertEquals("ISO-8859-1", KmyDocument.declaredNonUtf8Encoding(
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><KMYMONEY-FILE></KMYMONEY-FILE>"));
    }
}
