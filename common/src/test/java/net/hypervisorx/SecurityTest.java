package net.hypervisorx;

import net.hypervisorx.backend.qemu.QemuBackendAccess;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.util.Names;
import net.hypervisorx.util.Processes;
import net.hypervisorx.util.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the 1.1.1 server-safety fixes. */
class SecurityTest {
    @Test
    void networkPathsAreNeverExecutables() {
        assertTrue(Processes.isNetworkPath("\\\\attacker\\share"));
        assertTrue(Processes.isNetworkPath("\\\\?\\UNC\\attacker\\share\\vmrun.exe"));
        assertTrue(Processes.isNetworkPath("//attacker/share"));
        assertTrue(Processes.isNetworkPath("smb://attacker/share"));
        assertFalse(Processes.isNetworkPath("C:\\Program Files\\VMware\\VMware Workstation"));
        assertFalse(Processes.isNetworkPath("/usr/bin"));
    }

    @Test
    void overrideMustBeTheExpectedProgram(@TempDir Path dir) throws IOException {
        Path evil = Files.createFile(dir.resolve(Processes.WINDOWS ? "evil.exe" : "evil"));
        Path real = Files.createFile(dir.resolve(Processes.WINDOWS ? "vmrun.exe" : "vmrun"));
        // wrong file name gets ignored, even if it exists
        assertFalse(Processes.find(evil.toString(), "vmrun", List.of()).map(p -> p.equals(evil)).orElse(false));
        assertEquals(real, Processes.find(real.toString(), "vmrun", List.of()).orElseThrow());
        assertEquals(real, Processes.find(dir.toString(), "vmrun", List.of()).orElseThrow());
        // no relative paths as overrides
        assertFalse(Processes.find("vmrun", "definitely-not-a-program-hvx", List.of()).isPresent());
    }

    @Test
    void sanitizeDropsNetworkPathsAndUnknownValues() {
        HvxConfig c = new HvxConfig();
        c.executablePaths.put("vmware", "\\\\attacker\\share");
        c.qemuAccelerator = "kvm,something=evil";
        c.backendPreference = new java.util.ArrayList<>(List.of("vmware", "evil", "embedded"));
        c.sanitize();
        assertEquals("", c.executablePaths.get("vmware"));
        assertEquals("auto", c.qemuAccelerator);
        assertEquals(List.of("vmware", "embedded"), c.backendPreference);
    }

    @Test
    void operatorsOverTheNetworkCannotChangeHostOnlySettings() {
        HvxConfig current = new HvxConfig();
        current.executablePaths.put("vmware", "C:\\VMware");
        HvxConfig edit = current.copy();
        edit.executablePaths.put("vmware", "D:\\other");
        edit.allowHostWindowCapture = true;
        edit.allowVirtualBoxAuthChange = !current.allowVirtualBoxAuthChange;
        edit.streamFps = 30;
        HvxConfig remote = current.merged(edit, false);
        assertEquals("C:\\VMware", remote.executablePaths.get("vmware"));
        assertFalse(remote.allowHostWindowCapture);
        assertEquals(current.allowVirtualBoxAuthChange, remote.allowVirtualBoxAuthChange);
        assertEquals(30, remote.streamFps, "ordinary settings still apply");
        HvxConfig local = current.merged(edit, true);
        assertEquals("D:\\other", local.executablePaths.get("vmware"));
        assertTrue(local.allowHostWindowCapture);
    }

    @Test
    void namesCannotInjectVmxKeys() {
        assertEquals("pwnedevil = \"1\"", Names.clean("pwned\nevil = \"1\"", 48));
        assertEquals("abc", Names.clean("  a\rb\tc  ", 48));
        assertEquals(48, Names.clean("x".repeat(100), 48).length());
    }

    @Test
    void imageNamesStayInsideTheLibrary() {
        assertTrue(ImageLibrary.isValidName("alpine-virt-x86_64.iso"));
        assertFalse(ImageLibrary.isValidName("../../etc/passwd"));
        assertFalse(ImageLibrary.isValidName("C:\\Windows\\win.ini"));
        assertFalse(ImageLibrary.isValidName("a/b.iso"));
        assertFalse(ImageLibrary.isValidName("con<1>.iso"));
        assertFalse(ImageLibrary.isValidName(".hidden"));
    }

    @Test
    void rateLimiterAllowsBurstThenRefills() {
        RateLimiter r = new RateLimiter(10, 5);
        long t = 1_000_000_000L;
        int ok = 0;
        for (int i = 0; i < 20; i++) if (r.tryAcquire(t)) ok++;
        assertEquals(5, ok, "burst");
        assertFalse(r.tryAcquire(t + 50_000_000L), "half a token after 50 ms");
        assertTrue(r.tryAcquire(t + 200_000_000L), "two tokens after 200 ms");
    }

    @Test
    void qemuControlSocketsAvoidTheAddressGuestsCanReach() {
        String host = QemuBackendAccess.privateLoopback();
        if (!Processes.MAC) {
            assertTrue(host.startsWith("127.43."), host);
            assertFalse(host.equals("127.0.0.1"));
        }
        String pw = QemuBackendAccess.randomPassword();
        assertEquals(8, pw.length());
        assertFalse(pw.equals(QemuBackendAccess.randomPassword()));
    }
}
