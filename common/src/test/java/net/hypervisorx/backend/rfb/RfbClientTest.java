package net.hypervisorx.backend.rfb;

import net.hypervisorx.vm.display.Framebuffer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VNC client vs a tiny in-process RFB 3.8 server (Raw, CopyRect, resize, QEMU audio). */
class RfbClientTest {
    @Test
    void handshakeRawCopyRectResizeAndAudio() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            AtomicInteger keysymSeen = new AtomicInteger();
            CountDownLatch keyLatch = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] ver = new byte[12];
                    in.readFully(ver);
                    out.writeByte(1); // one security type
                    out.writeByte(1); // None
                    if (in.readUnsignedByte() != 1) throw new IllegalStateException("client chose wrong security");
                    out.writeInt(0); // SecurityResult OK
                    in.readUnsignedByte(); // ClientInit shared flag
                    out.writeShort(64);
                    out.writeShort(48);
                    out.write(new byte[16]);
                    byte[] name = "test-vm".getBytes(StandardCharsets.UTF_8);
                    out.writeInt(name.length);
                    out.write(name);
                    out.flush();
                    // client sends SetPixelFormat (20 bytes), SetEncodings, FramebufferUpdateRequest
                    if (in.readUnsignedByte() != 0) throw new IllegalStateException("expected SetPixelFormat");
                    in.skipNBytes(19);
                    if (in.readUnsignedByte() != 2) throw new IllegalStateException("expected SetEncodings");
                    in.readUnsignedByte();
                    int n = in.readUnsignedShort();
                    in.skipNBytes(n * 4L);
                    if (in.readUnsignedByte() != 3) throw new IllegalStateException("expected update request");
                    in.skipNBytes(9);

                    // FramebufferUpdate: raw 2x2 at (1,1), copyrect of it to (10,10), audio ack
                    out.writeByte(0);
                    out.writeByte(0);
                    out.writeShort(3);
                    out.writeShort(1);
                    out.writeShort(1);
                    out.writeShort(2);
                    out.writeShort(2);
                    out.writeInt(0);
                    int[] px = {0x112233, 0x445566, 0x778899, 0xAABBCC};
                    for (int p : px) {
                        out.writeByte(p & 0xFF);
                        out.writeByte(p >> 8 & 0xFF);
                        out.writeByte(p >> 16 & 0xFF);
                        out.writeByte(0);
                    }
                    out.writeShort(10);
                    out.writeShort(10);
                    out.writeShort(2);
                    out.writeShort(2);
                    out.writeInt(1);
                    out.writeShort(1);
                    out.writeShort(1);
                    out.writeShort(0);
                    out.writeShort(0);
                    out.writeShort(0);
                    out.writeShort(0);
                    out.writeInt(-259); // QEMU audio supported
                    // audio data message
                    out.writeByte(255);
                    out.writeByte(1);
                    out.writeShort(2);
                    out.writeInt(8);
                    out.write(new byte[]{1, 0, 2, 0, 3, 0, 4, 0});
                    // DesktopSize to 80x60
                    out.writeByte(0);
                    out.writeByte(0);
                    out.writeShort(1);
                    out.writeShort(0);
                    out.writeShort(0);
                    out.writeShort(80);
                    out.writeShort(60);
                    out.writeInt(-223);
                    out.flush();
                    // eat client messages until a KeyEvent shows up
                    while (true) {
                        int type = in.readUnsignedByte();
                        if (type == 3) {
                            in.skipNBytes(9);
                        } else if (type == 255) {
                            int sub = in.readUnsignedByte();
                            int op = in.readUnsignedShort();
                            if (sub == 1 && op == 2) in.skipNBytes(6);
                        } else if (type == 4) {
                            in.readUnsignedByte();
                            in.readUnsignedShort();
                            keysymSeen.set(in.readInt());
                            keyLatch.countDown();
                            break;
                        } else {
                            throw new IllegalStateException("unexpected client message " + type);
                        }
                    }
                } catch (Throwable e) {
                    serverError.set(e);
                    keyLatch.countDown();
                }
            });
            t.setDaemon(true);
            t.start();

            Framebuffer fb = new Framebuffer(10, 10);
            AtomicInteger audioBytes = new AtomicInteger();
            CountDownLatch connected = new CountDownLatch(1);
            RfbClient client = new RfbClient("127.0.0.1", port, null, fb, 1000, new RfbClient.Listener() {
                @Override
                public void connected(String desktopName, int width, int height) {
                    assertEquals("test-vm", desktopName);
                    connected.countDown();
                }

                @Override
                public void disconnected(String reason) {
                }

                @Override
                public void audio(byte[] pcm, int length) {
                    audioBytes.addAndGet(length);
                }
            });
            client.requestAudio(44100, 2);
            client.connect(5000);
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            long end = System.currentTimeMillis() + 5000;
            while (fb.width() != 80 && System.currentTimeMillis() < end) Thread.sleep(10);
            assertEquals(80, fb.width(), "DesktopSize resize applied");
            assertEquals(8, audioBytes.get(), "QEMU audio delivered");
            client.sendKeysym(0xff0d, true);
            assertTrue(keyLatch.await(5, TimeUnit.SECONDS));
            if (serverError.get() != null) throw new AssertionError(serverError.get());
            assertEquals(0xff0d, keysymSeen.get());
            client.close();
        }
    }

    @Test
    void rawAndCopyRectPixelsLandInFramebuffer() throws Exception {
        Framebuffer fb = new Framebuffer(64, 48);
        int[] px = {0x112233, 0x445566, 0x778899, 0xAABBCC};
        fb.writeRgb(1, 1, 2, 2, px, 0, 2);
        fb.copyRect(1, 1, 10, 10, 2, 2);
        assertEquals(0x112233, fb.pixel(10, 10));
        assertEquals(0xAABBCC, fb.pixel(11, 11));
    }

    @Test
    void vncAuthUsesBitReversedKey() throws Exception {
        byte[] challenge = new byte[16];
        byte[] a = RfbClient.vncAuthResponse("secret", challenge);
        byte[] b = RfbClient.vncAuthResponse("secret", challenge);
        assertEquals(16, a.length);
        assertTrue(java.util.Arrays.equals(a, b));
        assertTrue(!java.util.Arrays.equals(a, RfbClient.vncAuthResponse("other", challenge)));
    }
}
