package net.hypervisorx.client.screen;

import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.images.OsCatalog;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * one-click ISO manager: hypervisor health check, OS library, curated downloads (SHA-256 checked
 * when the project publishes a hash) and importing images from the player's own PC.
 */
public class LibraryScreen extends Screen {
    private enum Tab { HYPERVISORS, LIBRARY, CATALOG }

    private final Screen parent;
    private Tab tab = Tab.HYPERVISORS;
    private int left;
    private int top;
    private int w;
    private int h;
    private double scroll;
    private long lastRefresh;
    private final List<Row> rows = new ArrayList<>();

    private record Row(int y, int height, Runnable primary, Component primaryLabel, boolean primaryEnabled,
                       Runnable secondary, Component secondaryLabel) {
    }

    public LibraryScreen(Screen parent) {
        super(Component.translatable("hypervisorx.library.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 440);
        h = Math.min(height - 20, 260);
        left = (width - w) / 2;
        top = (height - h) / 2;
        int x = left + 6;
        for (Tab t : Tab.values()) {
            addRenderableWidget(new UiKit.FlatButton(x, top + 22, 96, 15, Component.translatable("hypervisorx.library.tab." + t.name().toLowerCase()),
                    () -> {
                        tab = t;
                        scroll = 0;
                    }).accent(t == Tab.CATALOG ? UiKit.ACCENT_2 : UiKit.ACCENT));
            x += 99;
        }
        addRenderableWidget(new UiKit.FlatButton(left + w - 66, top + 22, 60, 15, Component.translatable("gui.back"), this::onClose));
        addRenderableWidget(new UiKit.FlatButton(left + 6, top + h - 21, 110, 15, Component.translatable("hypervisorx.library.import"),
                () -> minecraft.setScreen(new FileBrowserScreen(this)))
                .enabledWhen(() -> ClientVmCache.library() != null && ClientVmCache.library().singleplayerHost()).accent(UiKit.OK));
        addRenderableWidget(new UiKit.FlatButton(left + 120, top + h - 21, 110, 15, Component.translatable("hypervisorx.library.rescan"),
                () -> send(C2S.LibraryAction.RESCAN, ""))
                .enabledWhen(() -> ClientVmCache.library() != null && ClientVmCache.library().canEdit()));
        addRenderableWidget(new UiKit.FlatButton(left + 234, top + h - 21, 110, 15, Component.translatable("hypervisorx.settings.title"),
                () -> minecraft.setScreen(new SettingsScreen(this))).accent(UiKit.ACCENT_2));
        send(C2S.LibraryAction.REFRESH, "");
    }

    private void send(int action, String arg) {
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(action, arg));
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        S2C.Library lib = ClientVmCache.library();
        boolean downloading = lib != null && lib.downloads().stream().anyMatch(d -> !d.state().startsWith("done") && !d.state().startsWith("failed") && !d.state().equals("cancelled"));
        if (now - lastRefresh > (downloading ? 700 : 4000)) {
            lastRefresh = now;
            send(C2S.LibraryAction.REFRESH, "");
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + w, top + h, UiKit.BG);
        UiKit.header(g, font, left, top, w, title);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        rows.clear();
        S2C.Library lib = ClientVmCache.library();
        int listTop = top + 42;
        int listBottom = top + h - 26;
        g.enableScissor(left + 4, listTop, left + w - 4, listBottom);
        int y = listTop - (int) scroll;
        if (lib == null) {
            g.drawString(font, Component.translatable("hypervisorx.library.loading"), left + 10, y + 4, UiKit.MUTED, false);
        } else {
            switch (tab) {
                case HYPERVISORS -> y = drawBackends(g, lib, y);
                case LIBRARY -> y = drawImages(g, lib, y);
                case CATALOG -> y = drawCatalog(g, lib, y, mouseX, mouseY);
            }
        }
        g.disableScissor();
        int content = y + (int) scroll - listTop;
        scroll = Mth.clamp(scroll, 0, Math.max(0, content - (listBottom - listTop)));
        // row buttons get drawn outside the scissor so hover behaves the same everywhere
        for (Row r : rows) {
            if (r.y < listTop || r.y + 14 > listBottom) continue;
            drawRowButton(g, left + w - 76, r.y, 68, r.primaryLabel, r.primaryEnabled, mouseX, mouseY);
            if (r.secondary != null) drawRowButton(g, left + w - 148, r.y, 68, r.secondaryLabel, true, mouseX, mouseY);
        }
    }

    private void drawRowButton(GuiGraphics g, int x, int y, int bw, Component label, boolean enabled, int mx, int my) {
        boolean hover = enabled && mx >= x && mx < x + bw && my >= y && my < y + 14;
        g.fill(x, y, x + bw, y + 14, !enabled ? 0xFF151A22 : hover ? UiKit.CARD_HOVER : UiKit.CARD);
        g.fill(x, y + 13, x + bw, y + 14, enabled ? UiKit.ACCENT : UiKit.BORDER);
        g.drawCenteredString(font, UiKit.ellipsize(font, label.getString(), bw - 4), x + bw / 2, y + 3, enabled ? UiKit.TEXT : UiKit.MUTED);
    }

    private int drawBackends(GuiGraphics g, S2C.Library lib, int y) {
        if (lib.hostCpus() > 0) {
            g.drawString(font, Component.translatable("hypervisorx.library.host", lib.hostCpus(), lib.hostRamMb() / 1024), left + 10, y + 2, UiKit.MUTED, false);
            y += 14;
        }
        for (S2C.BackendEntry b : lib.backends()) {
            UiKit.card(g, left + 8, y, w - 16, 34, false);
            UiKit.dot(g, left + 14, y + 6, b.available() ? UiKit.OK : UiKit.ERROR);
            String name = b.name() + (b.version().isEmpty() ? "" : "  " + b.version()) + (b.accelerated() ? "  ⚡" : "");
            g.drawString(font, name, left + 24, y + 5, UiKit.TEXT, false);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(b.message()), w - 40);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), left + 24, y + 15 + i * 9, UiKit.MUTED, false);
            y += 38;
        }
        return y;
    }

    private int drawImages(GuiGraphics g, S2C.Library lib, int y) {
        if (lib.images().isEmpty()) {
            g.drawString(font, Component.translatable("hypervisorx.library.empty"), left + 10, y + 4, UiKit.MUTED, false);
            return y + 20;
        }
        for (S2C.ImageEntry e : lib.images()) {
            UiKit.card(g, left + 8, y, w - 16, 22, false);
            g.drawString(font, UiKit.ellipsize(font, e.name(), w - 190), left + 14, y + 3, UiKit.TEXT, false);
            g.drawString(font, UiKit.bytes(e.size()) + "  " + e.kind().toUpperCase() + (e.linked() ? "  (linked)" : ""), left + 14, y + 12, UiKit.MUTED, false);
            String name = e.name();
            rows.add(new Row(y + 4, 14, () -> send(C2S.LibraryAction.DELETE, name), Component.translatable("hypervisorx.library.remove"),
                    lib.canEdit(), null, null));
            y += 25;
        }
        return y;
    }

    private int drawCatalog(GuiGraphics g, S2C.Library lib, int y, int mx, int my) {
        for (OsCatalog.Entry e : OsCatalog.ENTRIES) {
            S2C.DownloadEntry job = lib.downloads().stream().filter(d -> d.id().equals(e.id())).findFirst().orElse(null);
            UiKit.card(g, left + 8, y, w - 16, 40, false);
            g.drawString(font, e.name() + "  · " + e.size(), left + 14, y + 4, UiKit.TEXT, false);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(e.description()), w - 180);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), left + 14, y + 15 + i * 9, UiKit.MUTED, false);
            if (job != null) {
                double frac = job.total() > 0 ? job.done() / (double) job.total() : 0;
                int color = job.state().startsWith("failed") ? UiKit.ERROR : job.state().startsWith("done") ? UiKit.OK : UiKit.ACCENT;
                UiKit.progress(g, left + 14, y + 35, w - 180, 3, job.state().startsWith("done") ? 1 : frac, color);
                g.drawString(font, UiKit.ellipsize(font, job.state() + (job.total() > 0 ? "  " + UiKit.bytes(job.done()) + "/" + UiKit.bytes(job.total()) : ""), 150),
                        left + w - 160, y + 26, color, false);
            }
            boolean manual = e.kind() == OsCatalog.Kind.MANUAL;
            boolean busy = job != null && (job.state().startsWith("downloading") || job.state().equals("resolving") || job.state().equals("extracting"));
            String id = e.id();
            String url = e.homepage();
            if (manual) {
                rows.add(new Row(y + 4, 14, () -> openLink(url), Component.translatable("hypervisorx.library.website"), true, null, null));
            } else if (busy) {
                rows.add(new Row(y + 4, 14, () -> send(C2S.LibraryAction.CANCEL, id), Component.translatable("gui.cancel"), lib.canEdit(), null, null));
            } else {
                rows.add(new Row(y + 4, 14, () -> send(C2S.LibraryAction.DOWNLOAD, id), Component.translatable("hypervisorx.library.download"),
                        lib.canEdit(), () -> openLink(url), Component.translatable("hypervisorx.library.website")));
            }
            y += 44;
        }
        return y;
    }

    private void openLink(String url) {
        minecraft.setScreen(new ConfirmLinkScreen(ok -> {
            if (ok) Util.getPlatform().openUri(url);
            minecraft.setScreen(this);
        }, url, true));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Row r : rows) {
            if (my >= r.y && my < r.y + 14) {
                if (mx >= left + w - 76 && mx < left + w - 8 && r.primaryEnabled) {
                    r.primary.run();
                    return true;
                }
                if (r.secondary != null && mx >= left + w - 148 && mx < left + w - 80) {
                    r.secondary.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 24;
        return true;
    }

    public void onLibrary(S2C.Library lib) {
        // nothing to do, render() reads the cache every frame anyway
    }

    @Override
    public void onClose() {
        if (parent instanceof ComputerScreen cs) {
            // the container got closed when this screen opened, so ask the server to reopen it
            minecraft.setScreen(null);
            Services.PLATFORM.sendToServer(new C2S.HostAction(cs.getMenu().pos(), 0, C2S.HostAction.REOPEN_MENU));
        } else {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
