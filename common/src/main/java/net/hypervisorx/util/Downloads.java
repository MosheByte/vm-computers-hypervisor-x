package net.hypervisorx.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP(S) downloads: streamed, with progress, cancel, redirects and an atomic move at the end. */
public final class Downloads {
    public interface Progress {
        void update(long done, long total);

        default boolean cancelled() {
            return false;
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            // NORMAL won't follow an https -> http downgrade
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private Downloads() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    /** Downloads {@code url} to {@code target} through a {@code .part} file, returns the SHA-256 as hex. */
    public static String download(String url, Path target, Progress progress) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "HyperVisorX/1.0 (Minecraft mod; +https://github.com/MosheBarami/vm-computers-hypervisor-x)")
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        HttpResponse<InputStream> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        long done = 0;
        long lastReport = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                if (progress != null) {
                    if (progress.cancelled()) throw new IOException("Download cancelled");
                    long now = System.nanoTime();
                    if (now - lastReport > 200_000_000L) {
                        progress.update(done, total);
                        lastReport = now;
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (progress != null) progress.update(done, total);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return HexFormat.of().formatHex(sha.digest());
    }

    /** for small text files (release manifests, checksum lists). */
    public static String fetchText(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "HyperVisorX/1.0")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        try {
            HttpResponse<String> r = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** basic progress holder you can cancel. */
    public static final class Tracker implements Progress {
        public volatile long done;
        public volatile long total = -1;
        public final AtomicBoolean cancel = new AtomicBoolean();

        @Override
        public void update(long done, long total) {
            this.done = done;
            this.total = total;
        }

        @Override
        public boolean cancelled() {
            return cancel.get();
        }
    }
}
