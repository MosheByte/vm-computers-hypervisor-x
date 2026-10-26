package net.hypervisorx.images;

import com.google.gson.reflect.TypeToken;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.embedded.EmbeddedBackend;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.network.S2C;
import net.hypervisorx.util.Downloads;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The world host's OS library in {@code hypervisorx/images}: downloaded ISOs, files the admin dropped
 * in, and (singleplayer only) links to images sitting elsewhere on the player's PC.
 */
public final class ImageLibrary {
    public static final Set<String> EXTENSIONS = Set.of("iso", "img", "qcow2", "vmdk", "vdi", "vhd", "vhdx");
    public static final Set<String> DISK_EXTENSIONS = Set.of("qcow2", "vmdk", "vdi", "vhd", "vhdx");

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    public static final class Job {
        public final String id;
        public final String name;
        public final Downloads.Tracker tracker = new Downloads.Tracker();
        public volatile String state = "queued";

        Job(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private ImageLibrary() {
    }

    public static Path dir() {
        Path d = HvxConfig.dataRoot().resolve("images");
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
        }
        return d;
    }

    private static Path linkFile() {
        return HvxConfig.dataRoot().resolve("linked-images.json");
    }

    private static Map<String, String> linked() {
        try {
            if (Files.exists(linkFile())) {
                Map<String, String> m = HvxConfig.GSON.fromJson(Files.readString(linkFile(), StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, String>>() { }.getType());
                if (m != null) return m;
            }
        } catch (IOException | RuntimeException e) {
            HyperVisorX.LOG.warn("Could not read linked image list: {}", e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private static void saveLinked(Map<String, String> m) throws IOException {
        Files.createDirectories(linkFile().getParent());
        Files.writeString(linkFile(), HvxConfig.GSON.toJson(m), StandardCharsets.UTF_8);
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isDiskImage(String name) {
        return DISK_EXTENSIONS.contains(extension(name));
    }

    public static List<S2C.ImageEntry> list() {
        List<S2C.ImageEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir())) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && EXTENSIONS.contains(extension(n))) {
                    out.add(new S2C.ImageEntry(n, Files.size(p), isDiskImage(n) ? "disk" : "iso", false));
                }
            }
        } catch (IOException e) {
            HyperVisorX.LOG.warn("Could not list images: {}", e.getMessage());
        }
        for (Map.Entry<String, String> e : linked().entrySet()) {
            Path p = Path.of(e.getValue());
            long size = 0;
            try {
                size = Files.size(p);
            } catch (IOException ignored) {
            }
            out.add(new S2C.ImageEntry(e.getKey(), size, isDiskImage(e.getKey()) ? "disk" : "iso", true));
        }
        out.sort(Comparator.comparing(S2C.ImageEntry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Library name to a file on the host, or null. Names can't escape the images folder. */
    public static Path resolve(String name) {
        if (name == null || name.isBlank()) return null;
        String linkedPath = linked().get(name);
        if (linkedPath != null) {
            Path p = Path.of(linkedPath);
            return Files.isRegularFile(p) ? p : null;
        }
        Path p = dir().resolve(name).normalize();
        if (!p.startsWith(dir().normalize())) return null;
        return Files.isRegularFile(p) ? p : null;
    }

    /** Registers a file that's already on the singleplayer host's PC. No copy, multi-GB ISOs stay where they are. */
    public static String importPath(String absolute) throws IOException {
        Path p = Path.of(absolute).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IOException("Not a file: " + p);
        String n = p.getFileName().toString();
        if (!EXTENSIONS.contains(extension(n))) throw new IOException("Unsupported image type ." + extension(n));
        Map<String, String> m = linked();
        String name = n;
        int i = 2;
        while ((m.containsKey(name) && !m.get(name).equals(p.toString())) || Files.exists(dir().resolve(name))) {
            name = n.substring(0, n.lastIndexOf('.')) + " (" + i++ + ")." + extension(n);
        }
        m.put(name, p.toString());
        saveLinked(m);
        return name;
    }

    public static void delete(String name) throws IOException {
        Map<String, String> m = linked();
        if (m.remove(name) != null) {
            saveLinked(m);
            return;
        }
        Path p = dir().resolve(name).normalize();
        if (p.startsWith(dir().normalize())) Files.deleteIfExists(p);
    }

    public static List<S2C.DownloadEntry> jobs() {
        List<S2C.DownloadEntry> out = new ArrayList<>();
        for (Job j : JOBS.values()) out.add(new S2C.DownloadEntry(j.id, j.name, j.tracker.done, j.tracker.total, j.state));
        return out;
    }

    public static void cancel(String id) {
        Job j = JOBS.get(id);
        if (j != null) j.tracker.cancel.set(true);
    }

    /** Kicks off a background download for a catalog entry. */
    public static Job download(String catalogId, BackendContext ctx) {
        OsCatalog.Entry e = OsCatalog.get(catalogId).orElseThrow(() -> new IllegalArgumentException("unknown catalog id " + catalogId));
        Job existing = JOBS.get(catalogId);
        if (existing != null && (existing.state.equals("queued") || existing.state.startsWith("downloading") || existing.state.equals("extracting"))) {
            return existing;
        }
        Job job = new Job(catalogId, e.name());
        JOBS.put(catalogId, job);
        Thread t = new Thread(() -> run(job, e, ctx), "HyperVisorX-Download-" + catalogId);
        t.setDaemon(true);
        t.start();
        return job;
    }

    private static void run(Job job, OsCatalog.Entry e, BackendContext ctx) {
        try {
            if (e.kind() == OsCatalog.Kind.EMBEDDED) {
                job.state = "downloading";
                EmbeddedBackend.ensureKernel(ctx, e.id().equals("embedded-doom") ? EmbeddedBackend.KernelImage.DOOM : EmbeddedBackend.KernelImage.LINUX, job.tracker);
                job.state = "done";
                return;
            }
            if (e.resolver() == null) throw new IOException("This entry must be downloaded manually from " + e.homepage());
            job.state = "resolving";
            String[] src = e.resolver().resolve();
            Path target = dir().resolve(e.fileName());
            Path tmp = HvxConfig.dataRoot().resolve("downloads").resolve(e.id() + (e.kind() == OsCatalog.Kind.ZIP_ISO ? ".zip" : ".iso"));
            job.state = "downloading";
            String sha = Downloads.download(src[0], tmp, job.tracker);
            if (!src[1].isEmpty() && !src[1].equalsIgnoreCase(sha)) {
                Files.deleteIfExists(tmp);
                throw new IOException("SHA-256 mismatch (expected " + src[1] + ", got " + sha + ")");
            }
            if (e.kind() == OsCatalog.Kind.ZIP_ISO) {
                job.state = "extracting";
                extractIso(tmp, target);
                Files.deleteIfExists(tmp);
            } else {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            job.state = src[1].isEmpty() ? "done" : "done (SHA-256 verified)";
        } catch (IOException | BackendException | RuntimeException ex) {
            job.state = job.tracker.cancelled() ? "cancelled" : "failed: " + ex.getMessage();
            HyperVisorX.LOG.warn("Download of {} failed: {}", e.id(), ex.getMessage());
        }
    }

    private static void extractIso(Path zip, Path target) throws IOException {
        Path best = null;
        String bestName = null;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = z.getNextEntry()) != null) {
                String n = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !n.endsWith(".iso")) continue;
                // several ISOs in the zip? prefer the installer/boot one over live/legacy variants
                boolean preferred = n.contains("boot") || n.contains("live") && bestName == null;
                if (best == null || preferred) {
                    Path tmp = target.resolveSibling(target.getFileName() + ".part");
                    Files.copy(z, tmp, StandardCopyOption.REPLACE_EXISTING);
                    best = tmp;
                    bestName = n;
                    if (n.contains("boot")) break;
                }
            }
        }
        if (best == null) throw new IOException("Archive contains no ISO image");
        Files.move(best, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
