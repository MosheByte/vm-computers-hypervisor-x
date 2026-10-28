package net.hypervisorx.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** process stuff: run commands with a timeout, find executables, grab ports, kill children on exit. */
public final class Processes {
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    public static final boolean LINUX = !WINDOWS && !MAC;

    private static final Set<Process> CHILDREN = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RESERVED_PORTS = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : CHILDREN) {
                if (p.isAlive()) p.destroyForcibly();
            }
        }, "HyperVisorX-ChildReaper"));
    }

    private Processes() {
    }

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** runs a command, grabs stdout+stderr, kills it after {@code timeoutSeconds}. */
    public static Result run(List<String> command, long timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process p = pb.start();
        byte[][] holder = new byte[1][];
        Thread drain = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                holder[0] = in.readAllBytes();
            } catch (IOException ignored) {
                holder[0] = new byte[0];
            }
        }, "HyperVisorX-ProcDrain");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Command timed out after " + timeoutSeconds + "s: " + String.join(" ", command));
            }
            drain.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        String output = holder[0] == null ? "" : new String(holder[0], StandardCharsets.UTF_8);
        return new Result(p.exitValue(), output.trim());
    }

    public static Result run(long timeoutSeconds, String... command) throws IOException {
        return run(List.of(command), timeoutSeconds);
    }

    /** starts a long running child that gets killed when the JVM exits. output goes to {@code log}. */
    public static Process spawn(List<String> command, Path workDir, Path log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) pb.directory(workDir.toFile());
        if (log != null) {
            Files.createDirectories(log.toAbsolutePath().getParent());
            pb.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process p = pb.start();
        CHILDREN.add(p);
        p.onExit().thenRun(() -> CHILDREN.remove(p));
        return p;
    }

    /** UNC shares, {@code //host/...} and URL-ish strings. we never run these or use them as program folders. */
    public static boolean isNetworkPath(String s) {
        if (s == null) return false;
        String t = s.strip();
        // "\\server\share", "\\?\UNC\..." and "//server/share" all start with two slashes
        return t.startsWith("\\\\") || t.startsWith("//") || t.contains("://");
    }

    /** finds an executable: the override first, then PATH, then the usual install folders. */
    public static Optional<Path> find(String override, String name, List<String> knownLocations) {
        if (override != null && !override.isBlank() && !isNetworkPath(override)) {
            try {
                Path p = Path.of(override.trim());
                // override has to be a local absolute path to the right program (or the folder it's in)
                if (p.isAbsolute()) {
                    if (Files.isRegularFile(p) && p.getFileName().toString().equalsIgnoreCase(exe(name))) return Optional.of(p);
                    if (Files.isDirectory(p)) {
                        Path inDir = p.resolve(exe(name));
                        if (Files.isRegularFile(inDir)) return Optional.of(inDir);
                    }
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // bad path, just try PATH and the known spots
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                try {
                    Path candidate = Path.of(dir.trim().replace("\"", "")).resolve(exe(name));
                    if (Files.isRegularFile(candidate)) return Optional.of(candidate);
                } catch (RuntimeException ignored) {
                    // junk PATH entry, skip it
                }
            }
        }
        for (String loc : knownLocations) {
            try {
                Path candidate = Path.of(loc);
                if (Files.isDirectory(candidate)) candidate = candidate.resolve(exe(name));
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            } catch (RuntimeException ignored) {
            }
        }
        return Optional.empty();
    }

    public static String exe(String name) {
        return WINDOWS && !name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name + ".exe" : name;
    }

    /** a free localhost TCP port in {@code [from, to]} that no other VM in this JVM already has. */
    public static synchronized int allocatePort(int from, int to) throws IOException {
        for (int port = from; port <= to; port++) {
            // on Windows a bind test isn't enough: a listener with SO_REUSEADDR (VMware's VNC server, for one)
            // still lets us bind, so also check if something answers on the port
            if (RESERVED_PORTS.contains(port) || listening(port)) continue;
            try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                s.setReuseAddress(false);
                RESERVED_PORTS.add(port);
                return port;
            } catch (IOException ignored) {
                // taken, try the next one
            }
        }
        throw new IOException("No free TCP port between " + from + " and " + to);
    }

    /** true if something's accepting connections on this loopback port. */
    public static boolean listening(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 150);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** a resumed VM already has this port, so new allocations skip it. */
    public static void reservePort(int port) {
        RESERVED_PORTS.add(port);
    }

    public static void releasePort(int port) {
        RESERVED_PORTS.remove(port);
    }

    public static List<String> list(String... items) {
        return new ArrayList<>(List.of(items));
    }
}
