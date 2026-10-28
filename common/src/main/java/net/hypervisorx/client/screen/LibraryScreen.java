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
import java.util.Locale;

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
    private final UiKit.Scroll scroll = new UiKit.Scroll();
    private int lastMax;
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
        w = Math.min(width - 16, Mth.clamp((int) (width * 0.82), 320, 640));
        h = Math.min(height - 16, Mth.clamp((int) (height * 0.86), 220, 380));
        left = (width - w) / 2;
        top = (height - h) / 2;
        int x = left + 6;
        int tabW = Math.min(110, (w - 12 - 70) / 3 - 3);
        UiKit.Icon[] icons = {UiKit.Icon.CHIP, UiKit.Icon.DISC, UiKit.Icon.DOWNLOAD};
        for (Tab t : Tab.values()) {
            addRenderableWidget(new UiKit.FlatButton(x, top + 24, tabW, 16, Component.translatable("hypervisorx.library.tab." + t.name().toLowerCase(Locale.ROOT)),
                    () -> {
                        tab = t;
                        scroll.reset();
                    }).icon(icons[t.ordinal()]).style(UiKit.Style.GHOST).selectedWhen(() -> tab == t)
                    .accent(t == Tab.CATALOG ? UiKit.ACCENT_2 : UiKit.ACCENT));
            x += tabW + 3;
        }
        addRenderableWidget(new UiKit.FlatButton(left + w - 66, top + 24, 60, 16, Component.translatable("gui.back"), this::onClose).icon(UiKit.Icon.BACK));
        // three equal buttons across the full width, 6 px margins and 4 px gaps
        int bw = (w - 12 - 8) / 3;
        addRenderableWidget(new UiKit.FlatButton(left + 6, top + h - 22, bw, 16, Component.translatable("hypervisorx.library.import"),
                () -> minecraft.setScreen(new FileBrowserScreen(this))).icon(UiKit.Icon.FOLDER)
                .enabledWhen(() -> ClientVmCache.library() != null && ClientVmCache.library().singleplayerHost()).accent(UiKit.OK));
        addRenderableWidget(new UiKit.FlatButton(left + 6 + bw + 4, top + h - 22, bw, 16, Component.translatable("hypervisorx.library.rescan"),
                () -> send(C2S.LibraryAction.RESCAN, "")).icon(UiKit.Icon.RESET)
                .enabledWhen(() -> ClientVmCache.library() != null && ClientVmCache.library().canEdit()));
        addRenderableWidget(new UiKit.FlatButton(left + 6 + 2 * (bw + 4), top + h - 22, w - 12 - 2 * (bw + 4), 16,
                Component.translatable("hypervisorx.library.settings_short"),
                () -> minecraft.setScreen(new SettingsScreen(this))).icon(UiKit.Icon.GEAR).accent(UiKit.ACCENT_2));
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
        UiKit.window(g, font, left, top, w, h, UiKit.Icon.LIBRARY, title, null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        rows.clear();
        S2C.Library lib = ClientVmCache.library();
        int listTop = top + 45;
        int listBottom = top + h - 27;
        g.enableScissor(left + 4, listTop, left + w - 4, listBottom);
        scroll.update(lastMax);
        int y = listTop - scroll.px();
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
        int content = y + scroll.px() - listTop;
        int max = Math.max(0, content - (listBottom - listTop));
        lastMax = max;
        UiKit.scrollbar(g, left + w - 6, listTop, listBottom - listTop, Math.min(scroll.px(), max), max, listBottom - listTop, content);
        // row buttons get drawn outside the scissor so hover behaves the same everywhere
        for (Row r : rows) {
            if (r.y < listTop || r.y + 14 > listBottom) continue;
            drawRowButton(g, left + w - 76, r.y, 68, r.primaryLabel, r.primaryEnabled, mouseX, mouseY);
            if (r.secondary != null) drawRowButton(g, left + w - 148, r.y, 68, r.secondaryLabel, true, mouseX, mouseY);
        }
        UiKit.renderOverlays(g, font, mouseX, mouseY);
    }

    private void drawRowButton(GuiGraphics g, int x, int y, int bw, Component label, boolean enabled, int mx, int my) {
        boolean hover = enabled && mx >= x && mx < x + bw && my >= y && my < y + 14;
        UiKit.round(g, x, y, bw, 14, !enabled ? UiKit.STROKE_SOFT : hover ? UiKit.ACCENT : UiKit.STROKE);
        UiKit.round(g, x + 1, y + 1, bw - 2, 12, !enabled ? 0xFF14171C : hover ? UiKit.CARD_HOVER : UiKit.CARD);
        float sm = UiKit.small();
        String s = UiKit.clip(font, label.getString(), (int) ((bw - 6) / sm));
        UiKit.text(g, font, s, x + bw / 2f - font.width(s) * sm / 2f, y + (14 - 8 * sm) / 2f, enabled ? UiKit.TEXT : UiKit.DIM, 0.75f);
    }

    private int drawBackends(GuiGraphics g, S2C.Library lib, int y) {
        if (lib.hostCpus() > 0) {
            UiKit.icon(g, UiKit.Icon.SERVER, left + 10, y, UiKit.INFO);
            UiKit.textFit(g, font, Component.translatable("hypervisorx.library.host", lib.hostCpus(), lib.hostRamMb() / 1024).getString(),
                    left + 26, y + 3, w - 40, UiKit.MUTED, 0.75f);
            y += 15;
        }
        for (S2C.BackendEntry b : lib.backends()) {
            UiKit.card(g, left + 8, y, w - 16, 32, false);
            int c = b.available() ? UiKit.OK : UiKit.ERROR;
            g.fill(left + 9, y + 2, left + 11, y + 30, c);
            UiKit.icon(g, b.available() ? UiKit.Icon.CHECK : UiKit.Icon.CROSS, left + 15, y + 4, c);
            String name = b.name() + (b.version().isEmpty() ? "" : "  " + b.version());
            UiKit.textFit(g, font, name, left + 31, y + 5, w - 110, UiKit.TEXT, 1f);
            if (b.accelerated()) UiKit.pill(g, font, left + w - 14, y + 4, "HW accel", UiKit.ACCENT_2);
            UiKit.paragraph(g, font, b.message(), left + 31, y + 16, w - 48, 2, UiKit.MUTED, 0.75f);
            y += 35;
        }
        return y;
    }

    private int drawImages(GuiGraphics g, S2C.Library lib, int y) {
        if (lib.images().isEmpty()) {
            // empty state gets an actual button (jumps to the catalog), not just one lonely line
            UiKit.card(g, left + 8, y, w - 16, 44, false);
            UiKit.icon(g, UiKit.Icon.DISC, left + 16, y + 8, UiKit.withAlpha(UiKit.ACCENT_2, 0xA0));
            UiKit.textFit(g, font, Component.translatable("hypervisorx.library.empty").getString(), left + 34, y + 7, w - 130, UiKit.TEXT, 1f);
            UiKit.paragraph(g, font, Component.translatable("hypervisorx.library.empty_hint").getString(), left + 34, y + 19, w - 130, 2, UiKit.INFO, 0.75f);
            rows.add(new Row(y + 15, 14, () -> {
                tab = Tab.CATALOG;
                scroll.reset();
            }, Component.translatable("hypervisorx.library.empty_cta"), true, null, null));
            return y + 48;
        }
        for (S2C.ImageEntry e : lib.images()) {
            UiKit.card(g, left + 8, y, w - 16, 22, false);
            UiKit.icon(g, e.kind().equalsIgnoreCase("iso") ? UiKit.Icon.DISC : UiKit.Icon.DRIVE, left + 14, y + 5, UiKit.ACCENT_2);
            UiKit.textFit(g, font, e.name(), left + 30, y + 3, w - 120, UiKit.TEXT, 1f);
            UiKit.textFit(g, font, UiKit.bytes(e.size()) + "  \u00b7  " + e.kind().toUpperCase(Locale.ROOT) + (e.linked() ? "  \u00b7  linked" : ""), left + 30, y + 13,
                    w - 120, UiKit.INFO, 0.75f);
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
            UiKit.icon(g, UiKit.Icon.DISC, left + 14, y + 4, UiKit.ACCENT);
            UiKit.textFit(g, font, e.name(), left + 30, y + 5, w - 200, UiKit.TEXT, 1f);
            UiKit.text(g, font, e.size(), left + 30 + Math.min(w - 200, font.width(e.name())) + 6, y + 6, UiKit.INFO, 0.75f);
            UiKit.paragraph(g, font, e.description(), left + 30, y + 16, w - 200, 2, UiKit.MUTED, 0.75f);
            if (job != null) {
                double frac = job.total() > 0 ? job.done() / (double) job.total() : 0;
                int color = job.state().startsWith("failed") ? UiKit.ERROR : job.state().startsWith("done") ? UiKit.OK : UiKit.ACCENT_2;
                UiKit.progress(g, left + w - 160, y + 33, 150, 3, job.state().startsWith("done") ? 1 : frac, color);
                UiKit.textFit(g, font, job.state() + (job.total() > 0 ? "  " + UiKit.bytes(job.done()) + " / " + UiKit.bytes(job.total()) : ""),
                        left + w - 160, y + 24, 150, color, 0.75f);
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
        scroll.scrollBy(sy, 32);
        return true;
    }

    @Override
    public void removed() {
        UiKit.closePopover();
        super.removed();
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
