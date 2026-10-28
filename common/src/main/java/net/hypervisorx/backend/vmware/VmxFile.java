package net.hypervisorx.backend.vmware;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads/writes VMware {@code .vmx} files and keeps the key order. Keys VMware wrote itself (UUIDs,
 * generated MACs, NVRAM paths) survive when we change the hardware.
 */
final class VmxFile {
    private final Map<String, String> entries = new LinkedHashMap<>();

    static VmxFile load(Path path) throws IOException {
        VmxFile f = new VmxFile();
        if (Files.exists(path)) {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.trim().startsWith("#")) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                f.entries.put(key.toLowerCase(Locale.ROOT), key + "\u0000" + value);
            }
        }
        return f;
    }

    void set(String key, String value) {
        entries.put(key.toLowerCase(Locale.ROOT), key + "\u0000" + value);
    }

    void set(String key, boolean value) {
        set(key, value ? "TRUE" : "FALSE");
    }

    void set(String key, long value) {
        set(key, Long.toString(value));
    }

    void remove(String key) {
        entries.remove(key.toLowerCase(Locale.ROOT));
    }

    void removePrefix(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        entries.keySet().removeIf(k -> k.startsWith(p));
    }

    String get(String key) {
        String e = entries.get(key.toLowerCase(Locale.ROOT));
        return e == null ? null : e.substring(e.indexOf('\u0000') + 1);
    }

    void save(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        String enc = get(".encoding");
        sb.append(".encoding = \"").append(enc == null ? "UTF-8" : enc).append("\"\n");
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey().equals(".encoding")) continue;
            String raw = e.getValue();
            int sep = raw.indexOf('\u0000');
            // strip control chars, a CR/LF would end the line and let someone inject new keys
            String value = raw.substring(sep + 1).replaceAll("\\p{Cntrl}", "").replace("\"", "|22");
            sb.append(raw, 0, sep).append(" = \"").append(value).append("\"\n");
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
