package net.hypervisorx.images;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.util.Downloads;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-click operating systems. We only auto-download stuff that's freely redistributable. The
 * Windows entries are just presets that open Microsoft's official download page.
 */
public final class OsCatalog {
    public enum Kind {
        /** direct ISO download */
        ISO,
        /** zip with an ISO inside */
        ZIP_ISO,
        /** user grabs it by hand from {@code homepage} (because of the license or archive format) */
        MANUAL,
        /** kernel for the embedded MicroVM */
        EMBEDDED
    }

    public interface Resolver {
        /** @return {url, sha256 or ""} */
        String[] resolve() throws IOException;
    }

    public record Entry(String id, String name, String description, GuestOs guestOs, Kind kind, String size,
                        String homepage, int ramMb, int cores, int diskGb, boolean efi, Resolver resolver, String fileName) {
    }

    private static final Pattern DEBIAN = Pattern.compile("([0-9a-f]{64})\\s+(debian-[0-9.]+-amd64-netinst\\.iso)");

    public static final List<Entry> ENTRIES = List.of(
            new Entry("embedded-linux", "Instant Linux (embedded)", "Linux 6.1 + BusyBox on the built-in RISC-V MicroVM. Boots in about a second, no hypervisor needed.",
                    GuestOs.LINUX, Kind.EMBEDDED, "1.5 MB", "https://github.com/cnlohr/mini-rv32ima", 64, 1, 0, false, null, ""),
            new Entry("embedded-doom", "DOOM (embedded, terminal edition)", "Shareware DOOM E1M1 rendered as colour text on the embedded MicroVM. Experimental.",
                    GuestOs.LINUX, Kind.EMBEDDED, "3.3 MB", "https://github.com/cnlohr/mini-rv32ima", 64, 1, 0, false, null, ""),
            new Entry("alpine", "Alpine Linux (virt)", "Tiny, fast, security-oriented Linux. Log in as root, run setup-alpine.",
                    GuestOs.LINUX, Kind.ISO, "~70 MB", "https://alpinelinux.org/downloads/", 1024, 2, 8, false, OsCatalog::resolveAlpine, "alpine-virt-x86_64.iso"),
            new Entry("tinycore", "Tiny Core Linux 16", "A full graphical desktop in 25 MB that runs entirely in RAM.",
                    GuestOs.LINUX, Kind.ISO, "25 MB", "http://tinycorelinux.net/", 512, 1, 2, false,
                    () -> new String[]{"http://tinycorelinux.net/16.x/x86/release/TinyCore-current.iso", ""}, "TinyCore-16.iso"),
            new Entry("debian", "Debian (netinst)", "The universal operating system. Installs over the network.",
                    GuestOs.LINUX, Kind.ISO, "~750 MB", "https://www.debian.org/distrib/", 2048, 2, 32, false, OsCatalog::resolveDebian, "debian-netinst-amd64.iso"),
            new Entry("freedos", "FreeDOS 1.4", "A complete, free, DOS-compatible operating system. Play classic DOS games.",
                    GuestOs.DOS, Kind.ZIP_ISO, "294 MB", "https://www.freedos.org/download/", 64, 1, 2, false,
                    () -> new String[]{"https://download.freedos.org/1.4/FD14-LiveCD.zip", ""}, "FreeDOS-1.4-LiveCD.iso"),
            new Entry("reactos", "ReactOS (latest release)", "Open-source operating system binary-compatible with Windows apps and drivers.",
                    GuestOs.REACTOS, Kind.ZIP_ISO, "~200 MB", "https://reactos.org/download/", 1024, 1, 8, false, OsCatalog::resolveReactos, "ReactOS-latest.iso"),
            new Entry("kolibrios", "KolibriOS", "A complete graphical OS written in assembly that fits on a floppy. Download the ISO (.7z), extract it and import it.",
                    GuestOs.OTHER, Kind.MANUAL, "45 MB", "https://kolibrios.org/en/download", 128, 1, 0, false, null, ""),
            new Entry("windows11", "Windows 11 (template)", "Download the official ISO from Microsoft, import it, and this preset sets UEFI, TPM 2.0 and 8 GB RAM.",
                    GuestOs.WINDOWS_11, Kind.MANUAL, "~6 GB", "https://www.microsoft.com/software-download/windows11", 8192, 4, 80, true, null, ""),
            new Entry("windows10", "Windows 10 (template)", "Download the official ISO from Microsoft, import it, and this preset configures the VM.",
                    GuestOs.WINDOWS_10, Kind.MANUAL, "~5.5 GB", "https://www.microsoft.com/software-download/windows10ISO", 4096, 2, 64, false, null, "")
    );

    private OsCatalog() {
    }

    public static Optional<Entry> get(String id) {
        return ENTRIES.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    private static String[] resolveAlpine() throws IOException {
        String base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/";
        String yaml = Downloads.fetchText(base + "latest-releases.yaml");
        String[] blocks = yaml.split("\n-");
        for (String block : blocks) {
            if (!block.contains("flavor: alpine-virt")) continue;
            String iso = field(block, "iso");
            String sha = field(block, "sha256");
            if (iso != null) return new String[]{base + iso, sha == null ? "" : sha};
        }
        throw new IOException("alpine-virt not found in release manifest");
    }

    private static String field(String block, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*" + name + ":\\s*(\\S+)").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private static String[] resolveDebian() throws IOException {
        String base = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/";
        Matcher m = DEBIAN.matcher(Downloads.fetchText(base + "SHA256SUMS"));
        if (!m.find()) throw new IOException("netinst ISO not listed in SHA256SUMS");
        return new String[]{base + m.group(2), m.group(1)};
    }

    private static String[] resolveReactos() throws IOException {
        JsonObject rel = JsonParser.parseString(Downloads.fetchText("https://api.github.com/repos/reactos/reactos/releases/latest")).getAsJsonObject();
        JsonArray assets = rel.getAsJsonArray("assets");
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String name = a.get("name").getAsString();
            if (name.endsWith(".zip") && (name.contains("i386") || name.contains("x86")) && !name.toLowerCase().contains("live")) {
                return new String[]{a.get("browser_download_url").getAsString(), ""};
            }
        }
        throw new IOException("no ReactOS ISO archive in the latest release");
    }
}
