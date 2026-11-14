package com.sabag.aura;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

/**
 * A minimal adb client that talks to this TV's own adbd on 127.0.0.1:5555 (network debugging).
 * Android 14 gives apps no way to see or stop other apps; the shell user can, so the task manager
 * runs `am force-stop` and `dumpsys` through it. The first connection shows the system
 * "Allow debugging?" dialog on the TV; after "Always allow" it reconnects silently.
 */
final class Adb {
    interface Callback { void done(String output, Exception error); }
    interface StateListener { void onState(int state); }

    static final int UNKNOWN = 0, WAITING_APPROVAL = 1, READY = 2, NO_PORT = 3, REFUSED = 4;

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static Adb instance;

    static synchronized Adb get(Context c) {
        if (instance == null) instance = new Adb(c.getApplicationContext());
        return instance;
    }

    private final File dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleClose = () -> worker.execute(this::close);
    private KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private boolean connected;
    private int nextId = 1;
    private volatile int state = UNKNOWN;
    private StateListener listener;

    private Adb(Context c) {
        dir = c.getFilesDir();
    }

    int state() { return state; }

    void setStateListener(StateListener l) { listener = l; }

    /** Runs a shell command off the main thread; the callback runs on the main thread. */
    void run(String command, Callback cb) {
        main.removeCallbacks(idleClose);
        worker.execute(() -> {
            String output = null;
            Exception error = null;
            try {
                ensureConnected();
                output = shell(command);
            } catch (Exception e) {
                error = e;
                close();
            }
            final String o = output;
            final Exception e = error;
            main.post(() -> {
                if (cb != null) cb.done(o, e);
            });
            main.removeCallbacks(idleClose);
            main.postDelayed(idleClose, 90_000);
        });
    }

    private void setState(int s) {
        if (state == s) return;
        state = s;
        main.post(() -> {
            if (listener != null) listener.onState(s);
        });
    }

    // ------------------------------------------------------------------ connection

    private void ensureConnected() throws Exception {
        if (connected) return;
        loadKeys();
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
        } catch (ConnectException e) {
            setState(NO_PORT);
            throw e;
        }
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(8000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(CNXN, VERSION, MAX_DATA, "host::aura\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, offeredKey = false;
        while (true) {
            Message m = read();
            if (m.cmd == CNXN) {
                connected = true;
                setState(READY);
                return;
            }
            if (m.cmd != AUTH || m.arg0 != 1) continue;
            if (!signed) {
                send(AUTH, 2, 0, sign(m.data));
                signed = true;
            } else if (!offeredKey) {
                send(AUTH, 3, 0, publicKey());
                offeredKey = true;
                setState(WAITING_APPROVAL);
                socket.setSoTimeout(120_000); // the person has two minutes to answer the TV dialog
            } else {
                setState(REFUSED);
                throw new IOException("adb authorization refused");
            }
        }
    }

    private void close() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private String shell(String command) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        socket.setSoTimeout(20_000);
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == WRTE) {
                buf.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.cmd == CLSE) {
                send(CLSE, local, m.arg0, null);
                return buf.toString("UTF-8");
            }
        }
    }

    // ------------------------------------------------------------------ wire format

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > MAX_DATA * 4) throw new IOException("bad adb packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------ keys

    private void loadKeys() throws Exception {
        if (keys != null) return;
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            keys = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        try (FileOutputStream f = new FileOutputStream(priv)) { f.write(keys.getPrivate().getEncoded()); }
        try (FileOutputStream f = new FileOutputStream(pub)) { f.write(keys.getPublic().getEncoded()); }
    }

    /** adbd checks RSA_verify(SHA-1, token): PKCS#1 v1.5 type 1 padding over SHA-1 DigestInfo + token. */
    private byte[] sign(byte[] token) throws Exception {
        byte[] em = new byte[256];
        int t = SHA1_PREFIX.length + token.length;
        em[0] = 0;
        em[1] = 1;
        for (int i = 2; i < 256 - t - 1; i++) em[i] = (byte) 0xff;
        em[256 - t - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, em, 256 - t, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, 256 - token.length, token.length);
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
            return c.doFinal(em);
        } catch (Exception e) {
            RSAPrivateKey k = (RSAPrivateKey) keys.getPrivate();
            byte[] s = new BigInteger(1, em).modPow(k.getPrivateExponent(), k.getModulus()).toByteArray();
            byte[] fixed = new byte[256];
            System.arraycopy(s, Math.max(0, s.length - 256), fixed, Math.max(0, 256 - s.length), Math.min(256, s.length));
            return fixed;
        }
    }

    /** Android's RSAPublicKey struct (n0inv, little-endian modulus and R^2 mod n), base64 + name. */
    private byte[] publicKey() {
        RSAPublicKey k = (RSAPublicKey) keys.getPublic();
        int words = 64;
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = k.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(words * 32).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.negate().intValue());
        BigInteger nn = n;
        int[] nWords = new int[words], rrWords = new int[words];
        for (int i = 0; i < words; i++) {
            BigInteger[] q = rr.divideAndRemainder(r32);
            rr = q[0];
            rrWords[i] = q[1].intValue();
            q = nn.divideAndRemainder(r32);
            nn = q[0];
            nWords[i] = q[1].intValue();
        }
        for (int w : nWords) b.putInt(w);
        for (int w : rrWords) b.putInt(w);
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Aura@TCL-TV\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
