package net.hypervisorx.client.screen;

import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * display, audio and (for ops / the singleplayer host) server settings, laid out as cards: MUTED label
 * on the left, 120 px control on the right. two columns on wide windows, one scrolling column on small
 * ones (720p at GUI scale 3).
 */
public class SettingsScreen extends Screen {
    private static final int ROW = 18;
    private static final int HEAD = 16;

    private final Screen parent;
    private final HvxClientConfig client = HvxClientConfig.get();
    private HvxConfig server;
    private boolean serverLoaded;
    private int left;
    private int top;
    private int w;
    private int h;
    private final UiKit.Scroll scroll = new UiKit.Scroll();
    private int contentH;
    private UiKit.Field order;
    private UiKit.Field vmwarePath;
    private UiKit.Field vboxPath;
    private UiKit.Field qemuPath;
    private final List<Section> sections = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private Section building;

    private record Section(Component title, int x, int y, int w, int[] h) {
    }

    private record Row(Component label, AbstractWidget widget, int x, int y, int labelW) {
    }

    public SettingsScreen(Screen parent) {
        super(Component.translatable("hypervisorx.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(width - 16, Mth.clamp((int) (width * 0.82), 320, 640));
        h = Math.min(height - 16, Mth.clamp((int) (height * 0.86), 220, 380));
        left = (width - w) / 2;
        top = (height - h) / 2;
        sections.clear();
        rows.clear();
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(C2S.LibraryAction.REFRESH, ""));
        serverLoaded = false;
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
        boolean two = w >= 520;
        int colW = two ? (w - 18) / 2 : w - 16;
        int x0 = left + 6;
        int x1 = two ? left + 12 + colW : x0;
        int y = 0;
        y = buildClient(x0, y, colW);
        int y2 = two ? 0 : y + 6;
        y2 = serverLoaded ? buildServer(x1, y2, colW) : locked(x1, y2, colW);
        contentH = Math.max(y, y2);

        int by = top + h - 22;
        int doneW = Math.max(60, font.width(Component.translatable("gui.done")) + 32);
        addRenderableWidget(new UiKit.FlatButton(left + w - 6 - doneW, by, doneW, 16, Component.translatable("gui.done"), this::onClose)
                .icon(UiKit.Icon.CHECK).accent(UiKit.OK));
        if (serverLoaded) {
            int sw = Math.min(w - 18 - doneW, font.width(Component.translatable("hypervisorx.settings.save_server")) + 32);
            addRenderableWidget(new UiKit.FlatButton(left + w - 12 - doneW - sw, by, sw, 16, Component.translatable("hypervisorx.settings.save_server"),
                    this::saveServer).icon(UiKit.Icon.SERVER).style(UiKit.Style.PRIMARY));
        }
        layout();
    }

    private int viewTop() {
        return top + 24;
    }

    private int viewBottom() {
        return top + h - 28;
    }

    // ------------------------------------------------------------------------------------- building

    private int section(String key, int x, int y, int cw) {
        building = new Section(Component.translatable("hypervisorx.settings.section." + key), x, y, cw, new int[]{HEAD + 2});
        sections.add(building);
        return y + HEAD;
    }

    private int end(int y) {
        return y + 6 + 4;
    }

    private <W extends AbstractWidget> W row(int y, String key, W widget) {
        Section s = building;
        int ctrlW = Math.min(120, s.w() / 2);
        widget.setWidth(ctrlW);
        rows.add(new Row(Component.translatable("hypervisorx.settings." + key), widget, s.x(), y, s.w() - ctrlW - 16));
        s.h()[0] += ROW;
        // input only, render() draws the rows itself inside the scroll view's scissor
        addWidget(widget);
        return widget;
    }

    private <T> UiKit.ChoiceButton<T> choice(int y, String key, List<T> values, T initial, java.util.function.Function<T, String> names,
                                             java.util.function.Consumer<T> onChange) {
        return row(y, key, new UiKit.ChoiceButton<>(0, 0, 120, 16, "", values, initial, names, onChange));
    }

    private int buildClient(int x, int y, int cw) {
        y = section("display", x, y, cw);
        choice(y, "mipmaps", List.of(true, false), client.mipmaps, this::onOff, v -> {
            client.mipmaps = v;
            client.save();
        });
        y += ROW;
        choice(y, "anisotropic", List.of(1, 2, 4, 8, 16), client.anisotropy, v -> v + "×", v -> {
            client.anisotropy = v;
            client.save();
        });
        y += ROW;
        choice(y, "sharp", List.of(true, false), client.sharpUpscaling, this::onOff, v -> {
            client.sharpUpscaling = v;
            client.save();
        });
        y += ROW;
        choice(y, "upload", List.of(8, 16, 32, 48, 96, 192), client.maxUploadMiBPerFrame, v -> v + " MiB", v -> {
            client.maxUploadMiBPerFrame = v;
            client.save();
        });
        y += ROW;
        choice(y, "distance", List.of(32, 64, 96, 128, 192, 256), client.screenRenderDistance, v -> v + " m", v -> {
            client.screenRenderDistance = v;
            client.save();
        });
        y = end(y + ROW);
        y = section("audio", x, y, cw);
        choice(y, "audio", List.of(true, false), client.audioEnabled, this::onOff, v -> {
            client.audioEnabled = v;
            client.save();
        });
        y += ROW;
        choice(y, "volume", List.of(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f), client.audioVolume, v -> Math.round(v * 100) + "%", v -> {
            client.audioVolume = v;
            client.save();
        });
        y = end(y + ROW);
        y = section("focus", x, y, cw);
        choice(y, "pip", List.of(true, false), client.laptopPictureInPicture, this::onOff, v -> {
            client.laptopPictureInPicture = v;
            client.save();
        });
        y += ROW;
        choice(y, "pip_corner", List.of(0, 1, 2, 3), client.pipCorner, v -> Component.translatable("hypervisorx.settings.corner." + v).getString(), v -> {
            client.pipCorner = v;
            client.save();
        });
        y += ROW;
        choice(y, "fps_overlay", List.of(false, true), client.showFpsOverlay, this::onOff, v -> {
            client.showFpsOverlay = v;
            client.save();
        });
        y += ROW;
        choice(y, "focus_view", List.of(true, false), client.focusInWorld,
                v -> Component.translatable("hypervisorx.settings.focus_view." + (v ? "world" : "full")).getString(), v -> {
            client.focusInWorld = v;
            client.save();
        });
        y += ROW;
        choice(y, "focus_zoom", List.of(true, false), client.focusZoomAnimation, this::onOff, v -> {
            client.focusZoomAnimation = v;
            client.save();
        });
        return end(y + ROW);
    }

    private int buildServer(int x, int y, int cw) {
        y = section("hypervisors", x, y, cw);
        order = field(y, "order", String.join(",", server.backendPreference), 200, "hypervisorx.settings.order_hint");
        y += ROW;
        vmwarePath = field(y, "path_vmware_label", server.executablePaths.getOrDefault("vmware", ""), 400, "hypervisorx.settings.path_vmware");
        y += ROW;
        vboxPath = field(y, "path_vbox_label", server.executablePaths.getOrDefault("virtualbox", ""), 400, "hypervisorx.settings.path_vbox");
        y += ROW;
        qemuPath = field(y, "path_qemu_label", server.executablePaths.getOrDefault("qemu", ""), 400, "hypervisorx.settings.path_qemu");
        y += ROW;
        choice(y, "accel", List.of("auto", "kvm", "whpx", "hvf", "tcg"), server.qemuAccelerator, v -> v, v -> server.qemuAccelerator = v);
        y = end(y + ROW);
        y = section("streaming", x, y, cw);
        choice(y, "stream_fps", List.of(15, 30, 60, 90, 120), server.streamFps, String::valueOf, v -> server.streamFps = v);
        y += ROW;
        choice(y, "bandwidth", List.of(512, 1024, 2048, 4096, 8192, 16384, 32768), server.maxBandwidthPerViewerKiB,
                v -> v >= 1024 ? v / 1024 + " MiB/s" : v + " KiB/s", v -> server.maxBandwidthPerViewerKiB = v);
        y += ROW;
        choice(y, "color", List.of(24, 16), server.streamColorDepth, v -> v + "-bit", v -> server.streamColorDepth = v);
        y += ROW;
        choice(y, "max_vms", List.of(1, 2, 4, 6, 8, 12, 16, 32), server.maxRunningVms, String::valueOf, v -> server.maxRunningVms = v);
        y += ROW;
        choice(y, "on_exit", List.of("suspend", "shutdown", "kill"), server.onServerStop, v -> v, v -> server.onServerStop = v);
        y = end(y + ROW);
        y = section("safety", x, y, cw);
        choice(y, "window_capture", List.of(false, true), server.allowHostWindowCapture, this::onOff, v -> server.allowHostWindowCapture = v);
        y += ROW;
        choice(y, "vbox_auth", List.of(true, false), server.allowVirtualBoxAuthChange, this::onOff, v -> server.allowVirtualBoxAuthChange = v);
        y += ROW;
        choice(y, "store", List.of(true, false), server.storeEnabled, this::onOff, v -> server.storeEnabled = v);
        y += ROW;
        choice(y, "free_store", List.of(false, true), server.freeStore, this::onOff, v -> server.freeStore = v);
        return end(y + ROW);
    }

    private int locked(int x, int y, int cw) {
        building = new Section(Component.translatable("hypervisorx.settings.server"), x, y, cw, new int[]{HEAD + 26});
        sections.add(building);
        return y + HEAD + 30;
    }

    private UiKit.Field field(int y, String key, String value, int max, String hintKey) {
        UiKit.Field f = new UiKit.Field(font, 0, 0, 120, 16, Component.translatable(hintKey));
        f.setMaxLength(max);
        f.setValue(value);
        return row(y, key, f);
    }

    /** puts each widget at its row minus the scroll offset and hides the ones outside the view */
    private void layout() {
        int max = Math.max(0, contentH - (viewBottom() - viewTop()));
        scroll.update(max);
        for (Row r : rows) {
            int ry = viewTop() + r.y() - scroll.px();
            int cx = UiKit.rtl() ? r.x() + 6 : r.x() + (r.labelW() + 10);
            if (r.widget() instanceof UiKit.Field f) f.moveTo(cx, ry + 1);
            else r.widget().setPosition(cx, ry + 1);
            // half-visible rows stay on and get clipped by the scissor, no popping in and out
            r.widget().visible = ry + 17 > viewTop() && ry < viewBottom();
        }
    }

    private String onOff(boolean v) {
        return Component.translatable(v ? "options.on" : "options.off").getString();
    }

    /** the server's settings showed up after the screen opened */
    public void onLibrary() {
        if (!serverLoaded && ClientVmCache.library() != null && ClientVmCache.library().canEdit()) rebuildWidgets();
    }

    private void saveServer() {
        if (!serverLoaded) return;
        List<String> pref = new ArrayList<>();
        for (String s : Arrays.asList(order.getValue().split(","))) {
            String t = s.trim().toLowerCase(Locale.ROOT);
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
        UiKit.window(g, font, left, top, w, h, UiKit.Icon.GEAR, title, null);
        layout();
        g.enableScissor(left + 1, viewTop(), left + w - 1, viewBottom());
        for (Section s : sections) {
            int sy = viewTop() + s.y() - scroll.px();
            UiKit.section(g, font, s.x(), sy, s.w(), s.h()[0] - 4, s.title());
            if (!serverLoaded && s.title().getString().equals(Component.translatable("hypervisorx.settings.server").getString())) {
                UiKit.paragraph(g, font, Component.translatable("hypervisorx.settings.server_locked").getString(), s.x() + 8, sy + HEAD + 2, s.w() - 16, 2,
                        UiKit.INFO, 0.75f);
            }
        }
        for (Row r : rows) {
            int ry = viewTop() + r.y() - scroll.px();
            int lx = UiKit.rtl() ? r.x() + r.widget().getWidth() + 12 : r.x() + 8;
            UiKit.textFit(g, font, r.label().getString(), lx, ry + 5, r.labelW() - 4, UiKit.MUTED, 1f);
        }
        g.disableScissor();
        int max = Math.max(0, contentH - (viewBottom() - viewTop()));
        UiKit.scrollbar(g, left + w - 5, viewTop(), viewBottom() - viewTop(), scroll.px(), max, viewBottom() - viewTop(), contentH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        boolean inView = mouseY >= viewTop() && mouseY < viewBottom();
        g.enableScissor(left + 1, viewTop(), left + w - 1, viewBottom());
        for (Row r : rows) {
            if (r.widget().visible) r.widget().render(g, inView ? mouseX : -1, inView ? mouseY : -1, partialTick);
        }
        g.disableScissor();
        UiKit.renderOverlays(g, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (UiKit.popoverClicked(mx, my, button)) return true;
        if (my < viewTop() || my >= viewBottom()) {
            // a row cut off at the view edge can't eat clicks meant for the bottom bar
            for (Row r : rows) r.widget().visible = false;
            boolean used = super.mouseClicked(mx, my, button);
            layout();
            return used;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (UiKit.popoverScrolled(mx, my, dy)) return true;
        // wheel always scrolls the list, it never flips a dropdown the pointer just happens to pass over
        scroll.scrollBy(dy, 30);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (UiKit.popoverKey(key)) return true;
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public void removed() {
        UiKit.closePopover();
        super.removed();
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
