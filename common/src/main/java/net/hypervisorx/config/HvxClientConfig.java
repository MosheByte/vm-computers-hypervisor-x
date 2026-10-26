package net.hypervisorx.config;

import com.google.gson.JsonParseException;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.platform.Services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Per-player look and sound prefs, saved in {@code config/hypervisorx-client.json}. */
public final class HvxClientConfig {
    private static volatile HvxClientConfig instance;

    /** Trilinear mipmaps so screen text stays readable from far away. */
    public boolean mipmaps = true;
    /** Anisotropic filtering (1 = off, max 16) for screens you see at a steep angle. */
    public int anisotropy = 8;
    /** Sharp-bilinear upscaling shader: crisp pixels up close, no shimmering. */
    public boolean sharpUpscaling = true;
    /** Max texture upload per frame in MiB. Leftover dirty tiles wait for the next frame. */
    public int maxUploadMiBPerFrame = 48;
    /** Screens further away than this many blocks don't get streamed. */
    public int screenRenderDistance = 96;
    public boolean audioEnabled = true;
    public float audioVolume = 1.0f;
    public boolean showFpsOverlay = true;
    /** Picture-in-picture HUD while you hold a running laptop. */
    public boolean laptopPictureInPicture = true;
    /** 0 top-right, 1 top-left, 2 bottom-right, 3 bottom-left */
    public int pipCorner = 0;
    public float pipScale = 0.30f;
    public boolean focusZoomAnimation = true;

    public static HvxClientConfig get() {
        HvxClientConfig c = instance;
        if (c == null) {
            synchronized (HvxClientConfig.class) {
                c = instance;
                if (c == null) {
                    c = load();
                    instance = c;
                }
            }
        }
        return c;
    }

    private static Path file() {
        return Services.PLATFORM.configDirectory().resolve("hypervisorx-client.json");
    }

    private static HvxClientConfig load() {
        Path f = file();
        HvxClientConfig c = null;
        if (Files.exists(f)) {
            try {
                c = HvxConfig.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), HvxClientConfig.class);
            } catch (IOException | JsonParseException e) {
                HyperVisorX.LOG.error("Invalid {}, using defaults: {}", f, e.getMessage());
            }
        }
        if (c == null) c = new HvxClientConfig();
        c.sanitize();
        c.save();
        return c;
    }

    public void sanitize() {
        anisotropy = Math.max(1, Math.min(16, anisotropy));
        maxUploadMiBPerFrame = Math.max(4, Math.min(512, maxUploadMiBPerFrame));
        screenRenderDistance = Math.max(16, Math.min(512, screenRenderDistance));
        audioVolume = Math.max(0f, Math.min(2f, audioVolume));
        pipCorner = Math.max(0, Math.min(3, pipCorner));
        pipScale = Math.max(0.15f, Math.min(0.6f, pipScale));
    }

    public void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, HvxConfig.GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            HyperVisorX.LOG.error("Could not save HyperVisor X client config", e);
        }
    }
}
