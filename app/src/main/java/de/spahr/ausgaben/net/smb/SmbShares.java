package de.spahr.ausgaben.net.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Listet die Freigaben eines SMB-Servers auf. SMB2 selbst kennt dafür keinen Aufruf; benutzt wird der
 * RPC-Dienst {@code srvsvc} (Operation {@code NetShareEnumAll}) über die Named Pipe der
 * {@code IPC$}-Freigabe. smbj transportiert die Pipe, das DCERPC/NDR-Format kodiert und liest diese
 * Klasse selbst – deshalb sind Aufbau und Auswertung als statische, Android-freie Methoden testbar.
 */
public final class SmbShares {

    /** srvsvc: 4b324fc8-1670-01d3-1278-5a47bf6ee188, Version 3.0. */
    private static final byte[] SRVSVC_UUID = {
            (byte) 0xc8, 0x4f, 0x32, 0x4b, 0x70, 0x16, (byte) 0xd3, 0x01,
            0x12, 0x78, 0x5a, 0x47, (byte) 0xbf, 0x6e, (byte) 0xe1, (byte) 0x88};
    /** NDR-Transfersyntax: 8a885d04-1ceb-11c9-9fe8-08002b104860, Version 2.0. */
    private static final byte[] NDR_UUID = {
            0x04, 0x5d, (byte) 0x88, (byte) 0x8a, (byte) 0xeb, 0x1c, (byte) 0xc9, 0x11,
            (byte) 0x9f, (byte) 0xe8, 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60};

    private static final byte PTYPE_REQUEST = 0x00;
    private static final byte PTYPE_BIND = 0x0b;
    private static final byte PTYPE_RESPONSE = 0x02;
    private static final byte PTYPE_BIND_ACK = 0x0c;
    private static final int OPNUM_NET_SHARE_ENUM_ALL = 15;
    /** Erstes und letztes Fragment gesetzt. */
    private static final byte PFC_FIRST_LAST = 0x03;
    private static final byte PFC_LAST_FRAG = 0x02;

    /** Freigabetypen laut MS-SRVS: nur Plattenfreigaben interessieren; oberstes Bit = versteckt. */
    private static final int STYPE_MASK = 0x00FFFFFF;
    private static final int STYPE_DISKTREE = 0;
    private static final int STYPE_SPECIAL = 0x80000000;

    private SmbShares() {
    }

    /**
     * Alle sichtbaren Plattenfreigaben des Servers, alphabetisch. Wirft, wenn der Server nicht
     * erreichbar ist, die Anmeldung scheitert oder der RPC-Dienst die Auskunft verweigert.
     */
    public static List<String> list(String host, String user, String password) throws IOException {
        return list(host, 0, user, password);
    }

    /** Wie {@link #list(String, String, String)}, aber mit eigenem Port ({@code 0} = Standard 445). */
    public static List<String> list(String host, int port, String user, String password)
            throws IOException {
        try (SmbSessions.Link link = SmbSessions.open(host, port, true)) {
            Session session = SmbSessions.authenticate(link.connection, user, password);
            return listOn(session, host);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /**
     * Freigaben über eine <b>bestehende</b> Sitzung auflisten – so kann die Diagnose die ganze Kette
     * in einer einzigen Anmeldung durchlaufen.
     *
     * <h2>Warum {@code IPC$} hier <b>nicht</b> geschlossen wird</h2>
     *
     * <p>Das sieht nach einem vergessenen {@code close} aus und ist das Gegenteil. smbj legt jede
     * Freigabe einer Sitzung in seiner {@code TreeConnectTable} ab und gibt sie bei erneutem
     * {@code connectShare} von dort zurück. {@code TreeConnect.close()} schickt zwar das
     * Trennungspaket, <b>nimmt die Freigabe aber nicht aus der Tabelle</b>. Wer sie hier schlösse,
     * hinterließe also einen toten Eintrag unter dem Namen {@code IPC$} – und der nächste, der danach
     * fragt, bekäme ihn.</p>
     *
     * <p>Genau das ist passiert: Die Sitzung läuft mit eingeschaltetem DFS (siehe
     * {@code SmbSessions#configure}), und smbjs {@code DFSPathResolver} ist die einzige Stelle, die
     * von sich aus {@code IPC$} anfordert. Beim ersten {@code list()} auf der eigentlichen Freigabe
     * griff er in die Tabelle und bekam die geschlossene Pipe-Freigabe zurück. In der Diagnose stand
     * danach „Freigabe öffnen ✓" und eine Millisekunde später „Ordner lesen ✗ … has already been
     * closed" – ein Fehler, der wie ein Rechteproblem am Ordner aussah und keines war.</p>
     *
     * <p>Die Freigabe gehört der Sitzung, nicht dieser Methode. Aufgeräumt wird sie, wenn die
     * Verbindung fällt ({@code SmbSessions.Link#close}) – bei beiden Aufrufern der Fall. Die
     * <b>Pipe</b> dagegen wird weiterhin geschlossen: Die ist eine Datei in der Freigabe und gehört
     * wirklich hierher.</p>
     */
    public static List<String> listOn(Session session, String host) throws IOException {
        try {
            PipeShare ipc = (PipeShare) session.connectShare("IPC$");
            NamedPipe pipe = ipc.open("srvsvc", SMB2ImpersonationLevel.Impersonation,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
            try {
                byte[] bindAck = transact(pipe, buildBind(1));
                if (pduType(bindAck) != PTYPE_BIND_ACK) {
                    throw new IOException("srvsvc: unerwartete Antwort auf den Bind: " + hex(bindAck));
                }
                byte[] stub = stubOf(transact(pipe, buildEnumRequest(2, host)));
                List<Entry> entries = parseEnumResponse(stub);
                List<String> names = new ArrayList<>();
                for (Entry e : entries) {
                    if ((e.type & STYPE_SPECIAL) == 0 && (e.type & STYPE_MASK) == STYPE_DISKTREE) {
                        names.add(e.name);
                    }
                }
                java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                return names;
            } finally {
                pipe.close();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /** Eine Zeile aus {@code SHARE_INFO_1}: Name und Typ (Platte/Drucker/IPC, ggf. versteckt). */
    static final class Entry {
        final String name;
        final int type;

        Entry(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * Schickt eine PDU und liest die Antwort vollständig. Geschrieben und gelesen wird direkt auf der
     * Pipe (nicht per {@code FSCTL_PIPE_TRANSCEIVE}) – Samba und die davon abgeleiteten NAS-Systeme
     * beantworten ein Transceive mit einem RPC-Fault. Zerlegt der Server die Antwort in mehrere
     * Fragmente, werden deren Nutzdaten hinter dem Kopf des ersten Fragments zusammengehängt.
     */
    private static byte[] transact(NamedPipe pipe, byte[] request) throws IOException {
        pipe.write(request);
        byte[] head = null;
        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        byte[] pending = new byte[0];
        for (int guard = 0; guard < 64; guard++) {
            while (pending.length >= 16) {
                int frag = ((pending[9] & 0xFF) << 8) | (pending[8] & 0xFF);
                if (frag < 24 || pending.length < frag) {
                    break;   // Fragment noch unvollständig: weiterlesen
                }
                if (head == null) {
                    head = java.util.Arrays.copyOf(pending, 24);
                }
                stub.write(pending, 24, frag - 24);
                boolean last = (pending[3] & PFC_LAST_FRAG) != 0;
                pending = java.util.Arrays.copyOfRange(pending, frag, pending.length);
                if (last) {
                    byte[] body = stub.toByteArray();
                    byte[] out = new byte[24 + body.length];
                    System.arraycopy(head, 0, out, 0, 24);
                    System.arraycopy(body, 0, out, 24, body.length);
                    return out;
                }
            }
            byte[] buf = new byte[65536];
            int n = pipe.read(buf);
            if (n <= 0) {
                break;
            }
            byte[] grown = new byte[pending.length + n];
            System.arraycopy(pending, 0, grown, 0, pending.length);
            System.arraycopy(buf, 0, grown, pending.length, n);
            pending = grown;
        }
        throw new IOException("srvsvc: unvollständige Antwort");
    }

    /** Anfang einer PDU als Hex – nur für Fehlermeldungen im Log. */
    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 32); i++) {
            sb.append(String.format(Locale.ROOT, "%02x", data[i]));
        }
        return sb + " (" + data.length + " Byte)";
    }

    private static byte pduType(byte[] pdu) {
        return pdu.length > 2 ? pdu[2] : -1;
    }

    /** Nutzdaten einer Antwort-PDU (24 Byte Kopf: 16 gemeinsam + alloc_hint, ctx_id, cancel/reserved). */
    private static byte[] stubOf(byte[] pdu) throws IOException {
        if (pduType(pdu) != PTYPE_RESPONSE || pdu.length <= 24) {
            throw new IOException("srvsvc: keine gültige Antwort auf NetShareEnumAll: " + hex(pdu));
        }
        byte[] stub = new byte[pdu.length - 24];
        System.arraycopy(pdu, 24, stub, 0, stub.length);
        return stub;
    }

    /** Bind-PDU auf srvsvc mit NDR-Transfersyntax. */
    static byte[] buildBind(int callId) {
        ByteBuffer b = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        header(b, PTYPE_BIND, callId, 72);
        b.putShort((short) 4280);   // max_xmit_frag
        b.putShort((short) 4280);   // max_recv_frag
        b.putInt(0);                // assoc_group_id
        b.put((byte) 1);            // num_ctx_items
        b.put((byte) 0).putShort((short) 0);
        b.putShort((short) 0);      // context id
        b.put((byte) 1);            // num transfer syntaxes
        b.put((byte) 0);
        b.put(SRVSVC_UUID).putShort((short) 3).putShort((short) 0);
        b.put(NDR_UUID).putShort((short) 2).putShort((short) 0);
        return b.array();
    }

    /** Request-PDU für {@code NetShareEnumAll} (Level 1, leerer Container, alles auf einmal). */
    static byte[] buildEnumRequest(int callId, String host) {
        String server = "\\\\" + (host == null ? "" : host);
        byte[] stub = ndrServerName(server);
        int len = 24 + stub.length + 28;
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        header(b, PTYPE_REQUEST, callId, len);
        b.putInt(stub.length + 28);          // alloc_hint
        b.putShort((short) 0);               // context id
        b.putShort((short) OPNUM_NET_SHARE_ENUM_ALL);
        b.put(stub);
        b.putInt(1);                         // InfoStruct.Level
        b.putInt(1);                         // Union-Auswahl: SHARE_INFO_1_CONTAINER
        b.putInt(0x00020004);                // Zeiger auf den Container
        b.putInt(0);                         // EntriesRead = 0
        b.putInt(0);                         // Buffer = NULL
        b.putInt(0xFFFFFFFF);                // PreferedMaximumLength: alles
        b.putInt(0);                         // ResumeHandle = NULL
        return b.array();
    }

    /** Gemeinsamer 16-Byte-Kopf jeder PDU (Version 5.0, Little Endian/ASCII/IEEE). */
    private static void header(ByteBuffer b, byte type, int callId, int fragLength) {
        b.put((byte) 5).put((byte) 0).put(type).put(PFC_FIRST_LAST);
        b.put((byte) 0x10).put((byte) 0).put((byte) 0).put((byte) 0);
        b.putShort((short) fragLength);
        b.putShort((short) 0);   // auth_length
        b.putInt(callId);
    }

    /** {@code [unique] wchar_t* ServerName} als conformant-varying-String (mit NUL, auf 4 aufgefüllt). */
    private static byte[] ndrServerName(String server) {
        byte[] chars = (server + "\0").getBytes(StandardCharsets.UTF_16LE);
        int pad = (4 - (chars.length % 4)) % 4;
        ByteBuffer b = ByteBuffer.allocate(4 + 12 + chars.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x00020000);            // Referenz-Id (irgendein Wert != 0)
        int count = server.length() + 1;
        b.putInt(count);                 // max_count
        b.putInt(0);                     // offset
        b.putInt(count);                 // actual_count
        b.put(chars);
        for (int i = 0; i < pad; i++) {
            b.put((byte) 0);
        }
        return b.array();
    }

    /**
     * Wertet die Nutzdaten einer {@code NetShareEnumAll}-Antwort aus (Level 1). Reihenfolge laut NDR:
     * erst das Array mit Zeigern und Typen, danach – in derselben Reihenfolge – die Zeichenketten.
     */
    static List<Entry> parseEnumResponse(byte[] stub) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(stub).order(ByteOrder.LITTLE_ENDIAN);
        try {
            b.getInt();                       // Level
            b.getInt();                       // Union-Auswahl
            if (b.getInt() == 0) {            // Zeiger auf den Container
                return new ArrayList<>();
            }
            int entriesRead = b.getInt();
            if (b.getInt() == 0) {            // Zeiger auf das Array
                return new ArrayList<>();
            }
            int maxCount = b.getInt();
            int count = Math.min(entriesRead, maxCount);
            if (count < 0 || count > 4096) {
                throw new IOException("srvsvc: unplausible Anzahl Freigaben (" + count + ")");
            }
            int[] nameRef = new int[count];
            int[] types = new int[count];
            int[] remarkRef = new int[count];
            for (int i = 0; i < count; i++) {
                nameRef[i] = b.getInt();
                types[i] = b.getInt();
                remarkRef[i] = b.getInt();
            }
            List<Entry> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = nameRef[i] == 0 ? "" : readString(b);
                if (remarkRef[i] != 0) {
                    readString(b);            // Beschreibung wird nicht angezeigt
                }
                if (!name.isEmpty()) {
                    out.add(new Entry(name, types[i]));
                }
            }
            return out;
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("srvsvc: Antwort unvollständig", e);
        }
    }

    /** Ein conformant-varying-Unicode-String; abschließendes NUL und Füllbytes werden verworfen. */
    private static String readString(ByteBuffer b) throws IOException {
        int maxCount = b.getInt();
        int offset = b.getInt();
        int actual = b.getInt();
        if (actual < 0 || actual > maxCount || maxCount > 0xFFFF || offset < 0) {
            throw new IOException("srvsvc: fehlerhafte Zeichenkette in der Antwort");
        }
        byte[] chars = new byte[actual * 2];
        b.get(chars);
        int pad = ((4 - ((actual * 2) % 4)) % 4);
        b.position(b.position() + pad);
        String s = new String(chars, StandardCharsets.UTF_16LE);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }
}
