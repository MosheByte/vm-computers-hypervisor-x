package net.hypervisorx.backend.virtualbox;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.util.Processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Runs {@code vboxwebsrv} for us: starts it on demand on localhost, restarts it if it dies, and dumps
 * its output to a log file instead of the render thread (the original mod read it on the render
 * thread every frame, ouch).
 * <p>
 * Auth: the web service never runs without a password. If the user allowed it, we switch it to
 * VirtualBox's {@code VBoxAuthSimple} library with a random per-session account (only its SHA-256
 * goes into VirtualBox's global extra data), then put the old library back and delete the account
 * when the world closes. The old value also goes into {@code vbox-auth-restore.txt} so if the game
 * crashes it gets restored on the next start.
 */
final class VboxWebService {
    private static final Object LOCK = new Object();
    private static final String SIMPLE = "VBoxAuthSimple";
    private static Process process;
    private static int processPort = -1;
    private static String user = "";
    private static String password = "";
    private static Path vboxManageUsed;
    private static Path restoreFile;
    private static String previousLibrary;

    private VboxWebService() {
    }

    /** Login for IWebsessionManager_logon. Empty when we're on the user's own auth setup. */
    static String[] credentials() {
        synchronized (LOCK) {
            return new String[]{user, password};
        }
    }

    static void ensure(Path webSrv, Path vboxManage, int port, boolean allowAuthChange, Path logDir) throws IOException {
        synchronized (LOCK) {
            restoreAfterCrash(vboxManage, logDir.getParent());
            if (canLogon(port)) return;
            if (allowAuthChange && user.isEmpty()) installSessionAccount(vboxManage, logDir.getParent());
            if (process != null && process.isAlive() && processPort == port) {
                restart(webSrv, port, logDir);
            } else {
                start(webSrv, port, logDir);
            }
            if (!waitForLogon(port, 15_000)) {
                throw new IOException("vboxwebsrv on port " + port + " does not accept logons."
                        + (allowAuthChange ? " Check vboxwebsrv.log in the HyperVisor X logs folder."
                        : " Enable 'Allow VirtualBox web service auth change' in HyperVisor X settings, or configure the web service's"
                        + " authentication yourself."));
            }
        }
    }

    /** Makes a random VBoxAuthSimple account and points the web service at that library. */
    private static void installSessionAccount(Path vboxManage, Path dataRoot) throws IOException {
        SecureRandom rnd = new SecureRandom();
        byte[] b = new byte[18];
        rnd.nextBytes(b);
        String pw = HexFormat.of().formatHex(b);
        String name = "hvx-" + HexFormat.of().formatHex(new byte[]{(byte) rnd.nextInt(), (byte) rnd.nextInt(), (byte) rnd.nextInt()});
        previousLibrary = currentLibrary(vboxManage);
        restoreFile = dataRoot.resolve("vbox-auth-restore.txt");
        Files.createDirectories(dataRoot);
        Files.writeString(restoreFile, (previousLibrary == null ? "" : previousLibrary) + "\n" + name + "\n", StandardCharsets.UTF_8);
        Processes.run(30, vboxManage.toString(), "setextradata", "global", "VBoxAuthSimple/users/" + name, sha256(pw));
        Processes.Result r = Processes.run(30, vboxManage.toString(), "setproperty", "websrvauthlibrary", SIMPLE);
        if (!r.ok()) throw new IOException("Could not configure the VirtualBox web service: " + r.output());
        user = name;
        password = pw;
        vboxManageUsed = vboxManage;
        HyperVisorX.LOG.info("VirtualBox web service: using a temporary {} account (previous library: {})", SIMPLE,
                previousLibrary == null ? "default" : previousLibrary);
    }

    private static String currentLibrary(Path vboxManage) {
        try {
            for (String line : Processes.run(30, vboxManage.toString(), "list", "systemproperties").output().split("\\R")) {
                String l = line.trim();
                if (l.toLowerCase().startsWith("webservice auth. library:")) {
                    String v = l.substring(l.indexOf(':') + 1).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Last session didn't exit cleanly? Put its auth library back and drop its account. */
    private static void restoreAfterCrash(Path vboxManage, Path dataRoot) {
        if (!user.isEmpty()) return;
        Path f = dataRoot.resolve("vbox-auth-restore.txt");
        if (!Files.exists(f)) return;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            String prev = lines.isEmpty() ? "" : lines.get(0).trim();
            String name = lines.size() > 1 ? lines.get(1).trim() : "";
            undo(vboxManage, prev.isEmpty() ? null : prev, name);
            Files.deleteIfExists(f);
        } catch (IOException e) {
            HyperVisorX.LOG.warn("Could not restore the VirtualBox web service settings: {}", e.getMessage());
        }
    }

    private static void undo(Path vboxManage, String prev, String name) throws IOException {
        Processes.run(30, vboxManage.toString(), "setproperty", "websrvauthlibrary", prev == null ? "default" : prev);
        if (!name.isEmpty()) Processes.run(30, vboxManage.toString(), "setextradata", "global", "VBoxAuthSimple/users/" + name);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void restart(Path webSrv, int port, Path logDir) throws IOException {
        stopProcess();
        start(webSrv, port, logDir);
    }

    private static void start(Path webSrv, int port, Path logDir) throws IOException {
        killLeftover(logDir);
        List<String> cmd = Processes.list(webSrv.toString(), "--host", "127.0.0.1", "--port", Integer.toString(port), "--timeout", "0");
        process = Processes.spawn(cmd, null, logDir.resolve("vboxwebsrv.log"));
        processPort = port;
        Files.createDirectories(logDir);
        Files.writeString(logDir.resolve("vboxwebsrv.pid"), Long.toString(process.pid()), StandardCharsets.UTF_8);
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** a game that died without stopping its vboxwebsrv leaves the pid file behind, kill that one */
    private static void killLeftover(Path logDir) {
        Path pidFile = logDir.resolve("vboxwebsrv.pid");
        try {
            if (!Files.exists(pidFile)) return;
            long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
            ProcessHandle.of(pid).filter(h -> h.info().command().map(c -> c.toLowerCase().contains("vboxwebsrv")).orElse(false))
                    .ifPresent(ProcessHandle::destroy);
            Files.deleteIfExists(pidFile);
        } catch (IOException | NumberFormatException e) {
            HyperVisorX.LOG.debug("Could not check for a leftover vboxwebsrv: {}", e.getMessage());
        }
    }

    private static void stopProcess() {
        if (process != null && process.isAlive()) process.destroy();
        process = null;
        processPort = -1;
    }

    /** Stops the web service and puts VirtualBox's auth settings back how they were. */
    static void stop() {
        synchronized (LOCK) {
            stopProcess();
            if (!user.isEmpty() && vboxManageUsed != null) {
                try {
                    undo(vboxManageUsed, previousLibrary, user);
                    if (restoreFile != null) Files.deleteIfExists(restoreFile);
                } catch (IOException e) {
                    HyperVisorX.LOG.warn("Could not restore the VirtualBox web service settings: {}", e.getMessage());
                }
            }
            user = "";
            password = "";
        }
    }

    private static boolean waitForLogon(int port, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (canLogon(port)) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static boolean canLogon(int port) {
        try {
            VboxSoap soap = new VboxSoap(port);
            String vbox = soap.call("IWebsessionManager_logon", "username", user, "password", password).ret();
            if (vbox == null) return false;
            soap.call("IWebsessionManager_logoff", "refIVirtualBox", vbox);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
