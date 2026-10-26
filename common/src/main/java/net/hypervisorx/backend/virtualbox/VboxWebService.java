package net.hypervisorx.backend.virtualbox;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.util.Processes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Owns the {@code vboxwebsrv} process: starts it on demand bound to localhost, verifies a logon
 * works (switching the auth library to "null" when the user allowed it), restarts it when it dies
 * and drains its output to a log file instead of the render thread (the original mod read it on
 * the render thread every frame).
 */
final class VboxWebService {
    private static final Object LOCK = new Object();
    private static Process process;
    private static int processPort = -1;

    private VboxWebService() {
    }

    static void ensure(Path webSrv, Path vboxManage, int port, boolean allowAuthChange, Path logDir) throws IOException {
        synchronized (LOCK) {
            if (canLogon(port)) return;
            if (process != null && process.isAlive() && processPort == port) {
                // running but logons fail: authentication library problem
                fixAuth(vboxManage, allowAuthChange);
                restart(webSrv, port, logDir);
            } else {
                start(webSrv, port, logDir, true);
            }
            if (!waitForLogon(port, 15_000)) {
                fixAuth(vboxManage, allowAuthChange);
                restart(webSrv, port, logDir);
                if (!waitForLogon(port, 15_000)) {
                    throw new IOException("vboxwebsrv on port " + port + " does not accept logons. Run: VBoxManage setproperty websrvauthlibrary null"
                            + (allowAuthChange ? "" : " (or enable 'Allow VirtualBox web service auth change' in HyperVisor X settings)"));
                }
            }
        }
    }

    private static void fixAuth(Path vboxManage, boolean allow) throws IOException {
        if (!allow) return;
        HyperVisorX.LOG.info("Setting VirtualBox web service authentication library to 'null' (localhost only)");
        Processes.run(30, vboxManage.toString(), "setproperty", "websrvauthlibrary", "null");
    }

    private static void restart(Path webSrv, int port, Path logDir) throws IOException {
        stop();
        start(webSrv, port, logDir, true);
    }

    private static void start(Path webSrv, int port, Path logDir, boolean withAuthFlag) throws IOException {
        List<String> cmd = Processes.list(webSrv.toString(), "--host", "127.0.0.1", "--port", Integer.toString(port), "--timeout", "0");
        if (withAuthFlag) {
            cmd.add("--authentication");
            cmd.add("null");
        }
        process = Processes.spawn(cmd, null, logDir.resolve("vboxwebsrv.log"));
        processPort = port;
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!process.isAlive() && withAuthFlag) {
            // Older builds without --authentication: start plainly and rely on websrvauthlibrary.
            start(webSrv, port, logDir, false);
        }
    }

    static void stop() {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) process.destroy();
            process = null;
            processPort = -1;
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
            String vbox = soap.call("IWebsessionManager_logon", "username", "", "password", "").ret();
            if (vbox == null) return false;
            soap.call("IWebsessionManager_logoff", "refIVirtualBox", vbox);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
