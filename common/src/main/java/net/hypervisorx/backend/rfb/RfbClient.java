package net.hypervisorx.backend.rfb;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.vm.display.Framebuffer;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.LockSupport;

/**
 * Small, fast RFB 3.3/3.7/3.8 (VNC) client for the QEMU and VMware displays.
 * <ul>
 *   <li>Encodings: Raw, CopyRect, DesktopSize, ExtendedDesktopSize.</li>
 *   <li>QEMU extensions: extended key events (scan codes) and audio capture (PCM over VNC).</li>
 *   <li>Auth: None and classic VNC (DES challenge).</li>
 * </ul>
 * Every rect goes straight into the shared {@link Framebuffer}, which tracks dirty tiles.
 * All the reading happens on its own thread, input methods are fine to call from any thread.
 */
public final class RfbClient implements AutoCloseable {
    public interface Listener {
        void connected(String desktopName, int width, int height);

        void disconnected(String reason);

        /** Interleaved signed 16-bit PCM, in whatever format {@link #requestAudio} asked for. */
        default void audio(byte[] pcm, int length) {
        }
    }

    private static final int ENC_RAW = 0;
    private static final int ENC_COPYRECT = 1;
    private static final int ENC_DESKTOP_SIZE = -223;
    private static final int ENC_EXTENDED_DESKTOP_SIZE = -308;
    private static final int ENC_QEMU_EXT_KEY = -258;
    private static final int ENC_QEMU_AUDIO = -259;

    private final String host;
    private final int port;
    private final String password;
    private final Framebuffer framebuffer;
    private final Listener listener;
    private final int targetFps;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean running;
    private volatile boolean extKeySupported;
    private volatile boolean audioSupported;
    private volatile boolean audioWanted;
    private int audioRate = 44100;
    private int audioChannels = 2;
    private Thread reader;
    private byte[] rowBuffer = new byte[0];
    private int[] pixelBuffer = new int[0];

    public RfbClient(String host, int port, String password, Framebuffer framebuffer, int targetFps, Listener listener) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.framebuffer = framebuffer;
        this.targetFps = Math.max(1, targetFps);
        this.listener = listener;
    }

    /** Connects (keeps retrying while the hypervisor is still bringing up its VNC server) and starts the reader thread. */
    public void connect(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(4 << 20);
                s.connect(new InetSocketAddress(host, port), 2000);
                socket = s;
                in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 20));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 8192));
                handshake();
                running = true;
                reader = new Thread(this::readLoop, "HyperVisorX-RFB-" + port);
                reader.setDaemon(true);
                reader.start();
                return;
            } catch (IOException e) {
                last = e;
                closeQuietly();
                LockSupport.parkNanos(400_000_000L);
            }
        }
        throw last != null ? last : new IOException("VNC connection timed out");
    }

    private void handshake() throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        String version = new String(ver, StandardCharsets.US_ASCII);
        if (!version.startsWith("RFB ")) throw new IOException("Not an RFB server: " + version.trim());
        int minor = Integer.parseInt(version.substring(8, 11));
        int major = Integer.parseInt(version.substring(4, 7));
        int useMinor = major > 3 ? 8 : Math.min(minor, 8);
        if (useMinor != 3 && useMinor != 7 && useMinor != 8) useMinor = useMinor > 8 ? 8 : 3;
        out.write(String.format("RFB 003.%03d\n", useMinor).getBytes(StandardCharsets.US_ASCII));
        out.flush();

        int security;
        if (useMinor == 3) {
            security = in.readInt();
            if (security == 0) throw new IOException("VNC server refused connection: " + readString());
        } else {
            int count = in.readUnsignedByte();
            if (count == 0) throw new IOException("VNC server refused connection: " + readString());
            byte[] types = new byte[count];
            in.readFully(types);
            security = -1;
            for (byte t : types) {
                if (t == 1 && (password == null || password.isEmpty())) security = 1;
            }
            if (security < 0) {
                for (byte t : types) if (t == 2) security = 2;
            }
            if (security < 0) {
                for (byte t : types) if (t == 1) security = 1;
            }
            if (security < 0) throw new IOException("No supported VNC security type offered");
            out.writeByte(security);
            out.flush();
        }
        if (security == 2) {
            byte[] challenge = new byte[16];
            in.readFully(challenge);
            out.write(vncAuthResponse(password == null ? "" : password, challenge));
            out.flush();
        }
        if (security == 2 || useMinor == 8) {
            int result = in.readInt();
            if (result != 0) {
                String reason = useMinor == 8 ? readString() : "authentication failed";
                throw new IOException("VNC authentication failed: " + reason);
            }
        }
        out.writeByte(1); // ClientInit: shared session
        out.flush();

        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        in.skipNBytes(16); // server pixel format, we ask for our own right after anyway
        String name = readString();
        framebuffer.resize(w, h);

        // SetPixelFormat: 32bpp little-endian true colour, so a pixel read as an LE int is 0x00RRGGBB
        out.writeByte(0);
        out.write(new byte[3]);
        out.writeByte(32);
        out.writeByte(24);
        out.writeByte(0);
        out.writeByte(1);
        out.writeShort(255);
        out.writeShort(255);
        out.writeShort(255);
        out.writeByte(16);
        out.writeByte(8);
        out.writeByte(0);
        out.write(new byte[3]);

        int[] encodings = {ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_EXTENDED_DESKTOP_SIZE, ENC_QEMU_EXT_KEY, ENC_QEMU_AUDIO};
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int e : encodings) out.writeInt(e);
        requestUpdate(false, w, h);
        out.flush();
        listener.connected(name, w, h);
    }

    private String readString() throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad string length");
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    static byte[] vncAuthResponse(String password, byte[] challenge) throws IOException {
        byte[] key = new byte[8];
        byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 8 && i < pw.length; i++) {
            key[i] = (byte) (Integer.reverse(pw[i] & 0xFF) >>> 24); // classic VNC weirdness: DES key bytes are bit-reversed
        }
        try {
            Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
            des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            return des.doFinal(challenge);
        } catch (Exception e) {
            throw new IOException("DES unavailable for VNC authentication", e);
        }
    }

    private void requestUpdate(boolean incremental, int w, int h) throws IOException {
        synchronized (this) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(w);
            out.writeShort(h);
        }
    }

    private void readLoop() {
        String reason = "connection closed";
        long frameNanos = 1_000_000_000L / targetFps;
        long lastRequest = System.nanoTime();
        try {
            while (running) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0 -> {
                        readFramebufferUpdate();
                        framebuffer.frameCompleted();
                        long wait = frameNanos - (System.nanoTime() - lastRequest);
                        if (wait > 0) LockSupport.parkNanos(wait);
                        lastRequest = System.nanoTime();
                        synchronized (this) {
                            requestUpdate(true, framebuffer.width(), framebuffer.height());
                            out.flush();
                        }
                    }
                    case 1 -> { // SetColourMapEntries (never used in true colour, just skip it)
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        int n = in.readUnsignedShort();
                        in.skipNBytes(n * 6L);
                    }
                    case 2 -> { } // Bell
                    case 3 -> { // ServerCutText
                        in.skipNBytes(3);
                        int len = in.readInt();
                        in.skipNBytes(Math.max(0, len));
                    }
                    case 255 -> readQemuServerMessage();
                    default -> throw new IOException("Unknown RFB server message " + type);
                }
            }
        } catch (EOFException e) {
            reason = "VNC server closed the connection";
        } catch (IOException e) {
            if (running) reason = e.getMessage();
        } catch (Throwable t) {
            reason = t.toString();
            HyperVisorX.LOG.error("RFB reader crashed", t);
        }
        running = false;
        closeQuietly();
        listener.disconnected(reason);
    }

    private void readFramebufferUpdate() throws IOException {
        in.readUnsignedByte();
        int rects = in.readUnsignedShort();
        for (int r = 0; r < rects; r++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int enc = in.readInt();
            switch (enc) {
                case ENC_RAW -> readRaw(x, y, w, h);
                case ENC_COPYRECT -> {
                    int sx = in.readUnsignedShort();
                    int sy = in.readUnsignedShort();
                    framebuffer.copyRect(sx, sy, x, y, w, h);
                }
                case ENC_DESKTOP_SIZE -> framebuffer.resize(w, h);
                case ENC_EXTENDED_DESKTOP_SIZE -> {
                    int screens = in.readUnsignedByte();
                    in.skipNBytes(3 + screens * 16L);
                    if (w > 0 && h > 0) framebuffer.resize(w, h);
                }
                case ENC_QEMU_EXT_KEY -> extKeySupported = true;
                case ENC_QEMU_AUDIO -> {
                    audioSupported = true;
                    if (audioWanted) sendAudioEnable();
                }
                default -> throw new IOException("Unsupported RFB encoding " + enc);
            }
        }
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        int rowBytes = w * 4;
        if (rowBuffer.length < rowBytes) rowBuffer = new byte[rowBytes];
        if (pixelBuffer.length < w) pixelBuffer = new int[w];
        int fbW = framebuffer.width();
        int fbH = framebuffer.height();
        for (int row = 0; row < h; row++) {
            in.readFully(rowBuffer, 0, rowBytes);
            for (int i = 0, o = 0; i < w; i++, o += 4) {
                pixelBuffer[i] = (rowBuffer[o] & 0xFF) | (rowBuffer[o + 1] & 0xFF) << 8 | (rowBuffer[o + 2] & 0xFF) << 16;
            }
            if (y + row < fbH && x < fbW) {
                framebuffer.writeRgb(x, y + row, w, 1, pixelBuffer, 0, w);
            }
        }
    }

    private void readQemuServerMessage() throws IOException {
        int sub = in.readUnsignedByte();
        if (sub != 1) throw new IOException("Unknown QEMU server sub-message " + sub);
        int op = in.readUnsignedShort();
        if (op == 2) {
            int len = in.readInt();
            if (len < 0 || len > 1 << 22) throw new IOException("bad audio chunk");
            byte[] pcm = new byte[len];
            in.readFully(pcm);
            listener.audio(pcm, len);
        }
    }

    /** Asks QEMU to stream PCM (signed 16-bit, {@code channels}, {@code rate}). Kicks in once the server says it can do audio. */
    public void requestAudio(int rate, int channels) {
        audioRate = rate;
        audioChannels = channels;
        audioWanted = true;
        if (audioSupported) sendAudioEnable();
    }

    public int audioRate() {
        return audioRate;
    }

    public int audioChannels() {
        return audioChannels;
    }

    private void sendAudioEnable() {
        synchronized (this) {
            try {
                out.writeByte(255);
                out.writeByte(1);
                out.writeShort(2); // set format
                out.writeByte(3); // S16
                out.writeByte(audioChannels);
                out.writeInt(audioRate);
                out.writeByte(255);
                out.writeByte(1);
                out.writeShort(0); // enable
                out.flush();
            } catch (IOException ignored) {
                // reader thread will notice the dead connection
            }
        }
    }

    public boolean supportsScancodes() {
        return extKeySupported;
    }

    public void sendKeysym(int keysym, boolean down) {
        if (!running || keysym == 0) return;
        synchronized (this) {
            try {
                out.writeByte(4);
                out.writeByte(down ? 1 : 0);
                out.writeShort(0);
                out.writeInt(keysym);
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    /** QEMU extended key event: the exact scan code, with the keysym as a fallback. */
    public void sendScancode(int keysym, int qemuKeycode, boolean down) {
        if (!running) return;
        synchronized (this) {
            try {
                out.writeByte(255);
                out.writeByte(0);
                out.writeShort(down ? 1 : 0);
                out.writeInt(keysym);
                out.writeInt(qemuKeycode);
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    public void sendPointer(int x, int y, int buttonMask) {
        if (!running) return;
        synchronized (this) {
            try {
                out.writeByte(5);
                out.writeByte(buttonMask & 0xFF);
                out.writeShort(Math.max(0, x));
                out.writeShort(Math.max(0, y));
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isConnected() {
        return running;
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        closeQuietly();
    }
}
