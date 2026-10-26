package net.hypervisorx.backend.embedded;

import net.hypervisorx.vm.display.Framebuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the stock mini-rv32ima Linux image on the Java core and runs a shell command.
 * Off by default, turn it on with {@code ./gradlew :common:test -Phvx.bootTest=true -Phvx.testImage=/path/to/Image}.
 */
class EmbeddedLinuxBootTest {
    @Test
    void bootsLinuxToShell() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("hvx.bootTest"), "boot test disabled");
        Path image = Path.of(System.getProperty("hvx.testImage"));
        Assumptions.assumeTrue(Files.isRegularFile(image), "kernel image not found: " + image);

        AtomicBoolean poweredOff = new AtomicBoolean();
        StringBuilder log = new StringBuilder();
        final Rv32Machine[] holder = new Rv32Machine[1];
        VtTerminal terminal = new VtTerminal(120, 34, new VtTerminal.Host() {
            @Override
            public void reply(byte[] data) {
                holder[0].sendInput(data);
            }

            @Override
            public void osc(String payload) {
            }

            @Override
            public void bell() {
            }
        });
        Rv32Machine machine = new Rv32Machine(64 * 1024 * 1024, Files.readAllBytes(image), Rv32Machine.loadDefaultDtb(), b -> {
            synchronized (log) {
                log.append((char) b);
            }
            terminal.write(b);
        }, new Rv32Machine.Listener() {
            @Override
            public void onPowerOff() {
                poweredOff.set(true);
            }

            @Override
            public void onFault(String message) {
                synchronized (log) {
                    log.append("\n[FAULT] ").append(message);
                }
            }
        });
        holder[0] = machine;
        long t0 = System.currentTimeMillis();
        machine.start();
        try {
            assertTrue(waitFor(log, "login:", 180_000), () -> "no login prompt, log:\n" + tail(log));
            machine.sendInput("root\r".getBytes(StandardCharsets.US_ASCII));
            assertTrue(waitFor(log, "# ", 30_000), () -> "no shell prompt, log:\n" + tail(log));
            long bootMs = System.currentTimeMillis() - t0;
            System.out.println("[HVX] Linux reached the shell in " + bootMs + " ms, MIPS so far ~"
                    + (machine.instructionsRetired() / Math.max(1, bootMs) / 1000));
            machine.sendInput("uname -a; echo HVX_$((6*7))_OK\r".getBytes(StandardCharsets.US_ASCII));
            assertTrue(waitFor(log, "HVX_42_OK", 30_000), () -> "command did not run, log:\n" + tail(log));

            Framebuffer fb = new Framebuffer(960, 544);
            terminal.render(fb, GlyphFont.defaultFont(), 0, 0);
            int lit = 0;
            for (int y = 0; y < 544; y += 2) {
                for (int x = 0; x < 960; x += 2) {
                    if (fb.pixel(x, y) != 0x0B0F17) lit++;
                }
            }
            assertTrue(lit > 500, "terminal rendered almost nothing");
            System.out.println(terminal.screenText());
        } finally {
            machine.stop();
        }
    }

    private static boolean waitFor(StringBuilder log, String needle, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            synchronized (log) {
                if (log.indexOf(needle) >= 0) return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static String tail(StringBuilder log) {
        synchronized (log) {
            return log.substring(Math.max(0, log.length() - 3000));
        }
    }
}
