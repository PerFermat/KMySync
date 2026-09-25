package de.spahr.ausgaben.export;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.net.RemoteStorage;

/** Erkennt die Sperrdatei, die KMyMoney ab 5.2 neben eine geöffnete Datei legt. */
public class KmyLockTest {

    @Test
    public void eigeneSperrdatei_gilt() {
        assertTrue(KmyLock.isLocked(Arrays.asList("michael.kmy", "michael.kmy.lck"), "michael.kmy"));
    }

    @Test
    public void fremdeSperrdateiUndSicherungen_geltenNicht() {
        List<String> names = Arrays.asList("michael.kmy", "Rolf.kmy.lck", "michael.kmy.1~",
                "michael.kmy.20260925-132129.tmp");
        assertFalse(KmyLock.isLocked(names, "michael.kmy"));
    }

    @Test
    public void leererOrdner_istFrei() {
        assertFalse(KmyLock.isLocked(Collections.emptyList(), "michael.kmy"));
        assertFalse(KmyLock.isLocked(null, "michael.kmy"));
    }

    @Test
    public void auflistenScheitert_istFrei() {
        RemoteStorage kaputt = (RemoteStorage) java.lang.reflect.Proxy.newProxyInstance(
                RemoteStorage.class.getClassLoader(), new Class<?>[]{RemoteStorage.class},
                (proxy, method, args) -> {
                    throw new IOException("offline");
                });
        assertFalse(KmyLock.isOpenInKmyMoney(kaputt, "KMyMoney", "michael.kmy"));
    }
}
