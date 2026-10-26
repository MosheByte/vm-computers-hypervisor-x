package net.hypervisorx.client.screen;

import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Display, audio and (for operators / the singleplayer host) server settings: backend priority,
 * hypervisor paths, QEMU accelerator, stream quality and safety switches.
 */
public class SettingsScreen extends Screen {
    private final Screen parent;
    private final HvxClientConfig client = HvxClientConfig.get();
    private HvxConfig server;
    private boolean serverLoaded;
    private int left;
    private int top;
    private int w;
    private int h;
    private EditBox order;
    private EditBox vmwarePath;
    private EditBox vboxPath;
    private EditBox qemuPath;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("hypervisorx.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 460);
        h = Math.min(height - 20, 270);
        left = (width - w) / 2;
        top = (height - h) / 2;
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(C2S.LibraryAction.REFRESH, ""));
        serverLoaded = false;
        buildClient(left + 8, top + 34, (w - 24) / 2);
        S2C.Library lib = ClientVmCache.library();
        if (lib != null && lib.canEdit() && !lib.configJson().isEmpty()) {
            try {
                server = HvxConfig.GSON.fromJson(lib.configJson(), HvxConfig.class);
                server.sanitize();
                serverLoaded = true;
            } catch (RuntimeException e) {
                serverLoaded = false;
            }
        }
        if (serverLoaded) buildServer(left + 16 + (w - 24) / 2, top + 34, (w - 24) / 2);
        addRenderableWidget(new UiKit.FlatButton(left + w - 74, top + h - 21, 66, 15, Component.translatable("gui.done"), this::onClose).accent(UiKit.OK));
    }

    private int row(int y) {
        return y + 17;
    }

    private void buildClient(int x, int y, int cw) {
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("mipmaps"), List.of(true, false), client.mipmaps, this::onOff, v -> {
            client.mipmaps = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("anisotropic"), List.of(1, 2, 4, 8, 16), client.anisotropy, v -> v + "x", v -> {
            client.anisotropy = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("sharp"), List.of(true, false), client.sharpUpscaling, this::onOff, v -> {
            client.sharpUpscaling = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("upload"), List.of(8, 16, 32, 48, 96, 192), client.maxUploadMiBPerFrame,
                v -> v + " MiB", v -> {
            client.maxUploadMiBPerFrame = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("distance"), List.of(32, 64, 96, 128, 192, 256), client.screenRenderDistance,
                v -> v + " blocks", v -> {
            client.screenRenderDistance = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("audio"), List.of(true, false), client.audioEnabled, this::onOff, v -> {
            client.audioEnabled = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("volume"), List.of(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f), client.audioVolume,
                v -> Math.round(v * 100) + "%", v -> {
            client.audioVolume = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("pip"), List.of(true, false), client.laptopPictureInPicture, this::onOff, v -> {
            client.laptopPictureInPicture = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("pip_corner"), List.of(0, 1, 2, 3), client.pipCorner,
                v -> Component.translatable("hypervisorx.settings.corner." + v).getString(), v -> {
            client.pipCorner = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("fps_overlay"), List.of(true, false), client.showFpsOverlay, this::onOff, v -> {
            client.showFpsOverlay = v;
            client.save();
        }));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, cw, 15, t("focus_zoom"), List.of(true, false), client.focusZoomAnimation, this::onOff, v -> {
            client.focusZoomAnimation = v;
            client.save();
        }));
    }

    private void buildServer(int x, int y, int cw) {
        order = box(x, y, cw, String.join(",", server.backendPreference), 200);
        y = row(y);
        vmwarePath = box(x, y, cw, server.executablePaths.getOrDefault("vmware", ""), 400);
        vmwarePath.setHint(Component.translatable("hypervisorx.settings.path_vmware"));
        y = row(y);
        vboxPath = box(x, y, cw, server.executablePaths.getOrDefault("virtualbox", ""), 400);
        vboxPath.setHint(Component.translatable("hypervisorx.settings.path_vbox"));
        y = row(y);
        qemuPath = box(x, y, cw, server.executablePaths.getOrDefault("qemu", ""), 400);
        qemuPath.setHint(Component.translatable("hypervisorx.settings.path_qemu"));
        y = row(y);
        int half = (cw - 3) / 2;
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, t("accel"), List.of("auto", "kvm", "whpx", "hvf", "tcg"), server.qemuAccelerator,
                v -> v, v -> server.qemuAccelerator = v));
        addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, t("stream_fps"), List.of(15, 30, 60, 90, 120), server.streamFps,
                String::valueOf, v -> server.streamFps = v));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, t("bandwidth"), List.of(512, 1024, 2048, 4096, 8192, 16384, 32768), server.maxBandwidthPerViewerKiB,
                v -> v >= 1024 ? v / 1024 + " MiB/s" : v + " KiB/s", v -> server.maxBandwidthPerViewerKiB = v));
        addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, t("color"), List.of(24, 16), server.streamColorDepth,
                v -> v + "-bit", v -> server.streamColorDepth = v));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, t("max_vms"), List.of(1, 2, 4, 8, 12, 16, 32), server.maxRunningVms,
                String::valueOf, v -> server.maxRunningVms = v));
        addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, t("on_exit"), List.of("suspend", "shutdown", "kill"), server.onServerStop,
                v -> v, v -> server.onServerStop = v));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, t("window_capture"), List.of(false, true), server.allowHostWindowCapture,
                this::onOff, v -> server.allowHostWindowCapture = v));
        addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, t("free_store"), List.of(false, true), server.freeStore,
                this::onOff, v -> server.freeStore = v));
        y = row(y);
        addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, t("store"), List.of(true, false), server.storeEnabled,
                this::onOff, v -> server.storeEnabled = v));
        addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, t("vbox_auth"), List.of(true, false), server.allowVirtualBoxAuthChange,
                this::onOff, v -> server.allowVirtualBoxAuthChange = v));
        y = row(y) + 2;
        addRenderableWidget(new UiKit.FlatButton(x, y, cw, 16, Component.translatable("hypervisorx.settings.save_server"), this::saveServer).accent(UiKit.ACCENT_2));
    }

    private EditBox box(int x, int y, int w, String value, int max) {
        EditBox b = new EditBox(font, x + 1, y, w - 2, 14, Component.empty());
        b.setMaxLength(max);
        b.setValue(value);
        addRenderableWidget(b);
        return b;
    }

    private String onOff(boolean v) {
        return Component.translatable(v ? "options.on" : "options.off").getString();
    }

    private static String t(String key) {
        return Component.translatable("hypervisorx.settings." + key).getString() + ": ";
    }

    /** the server's settings showed up after the screen opened */
    public void onLibrary() {
        if (!serverLoaded && ClientVmCache.library() != null && ClientVmCache.library().canEdit()) rebuildWidgets();
    }

    private void saveServer() {
        if (!serverLoaded) return;
        List<String> pref = new ArrayList<>();
        for (String s : Arrays.asList(order.getValue().split(","))) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) pref.add(t);
        }
        server.backendPreference = pref;
        server.executablePaths.put("vmware", vmwarePath.getValue().trim());
        server.executablePaths.put("virtualbox", vboxPath.getValue().trim());
        server.executablePaths.put("qemu", qemuPath.getValue().trim());
        Services.PLATFORM.sendToServer(new C2S.ServerConfig(HvxConfig.GSON.toJson(server)));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + w, top + h, UiKit.BG);
        UiKit.header(g, font, left, top, w, title);
        g.drawString(font, Component.translatable("hypervisorx.settings.client"), left + 8, top + 23, UiKit.ACCENT, false);
        g.drawString(font, serverLoaded ? Component.translatable("hypervisorx.settings.server")
                : Component.translatable("hypervisorx.settings.server_locked"), left + 16 + (w - 24) / 2, top + 23, serverLoaded ? UiKit.ACCENT_2 : UiKit.MUTED, false);
        if (serverLoaded) {
            g.drawString(font, Component.translatable("hypervisorx.settings.order_hint"), left + w - 70, top + 37, 0x60FFFFFF, false);
        }
    }

    @Override
    public void onClose() {
        client.sanitize();
        client.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
