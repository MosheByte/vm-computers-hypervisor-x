package net.hypervisorx.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.hardware.ComponentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Our little UI kit, going for that NZXT CAM hardware-dashboard look: near-black window, rounded
 * cards with 1px strokes, small-caps section labels, icon buttons with tooltips, violet/cyan accent
 * gradient. All text gets width-checked (ellipsis or wrap) so nothing spills out of its box at any
 * GUI scale.
 */
public final class UiKit {
    public static final int BG = 0xF60D0F13;
    public static final int SURFACE = 0xFF13161B;
    public static final int PANEL = SURFACE;
    public static final int CARD = 0xFF191D24;
    public static final int CARD_HOVER = 0xFF212632;
    public static final int FIELD = 0xFF0F1216;
    public static final int STROKE = 0xFF2A303A;
    public static final int BORDER = STROKE;
    public static final int STROKE_SOFT = 0xFF20252D;
    public static final int ACCENT = 0xFF7C5CFF;
    public static final int ACCENT_2 = 0xFF3BD1FF;
    public static final int TEXT = 0xFFE9EDF3;
    public static final int MUTED = 0xFF8D96A6;
    public static final int DIM = 0xFF5B6474;
    /** Secondary text that actually means something (5.2:1 on SURFACE). DIM is only for disabled/decoration. */
    public static final int INFO = 0xFF7F8A9C;
    public static final int OK = 0xFF3DDC84;
    public static final int WARN = 0xFFFFB020;
    public static final int ERROR = 0xFFFF5470;

    private static final ResourceLocation ICONS = HyperVisorX.id("textures/gui/icons.png");

    private UiKit() {
    }

    /** Glyphs in textures/gui/icons.png (12x12 on a 16px grid, 8 per row), made by tools/gen_icons.py. */
    public enum Icon {
        POWER, STOP, PLAY, RESET, CAMERA, DISC, USB, LIBRARY, FOCUS, GEAR, EYE, LOCK, USER_PLUS, USER_MINUS, CART, SEARCH, DOWNLOAD,
        FOLDER, CHECK, CROSS, WARN, MONITOR, BACK, TRASH, CHIP, MEMORY, DRIVE, WIFI, PLUS, MINUS, BOX, ROCKET, INFO, CLOSE, KEYBOARD, SERVER, COIN,
        GPU, PCIE, LAPTOP, SORT, CHEVRON_DOWN, CHEVRON_RIGHT, GRID, LIST, WRENCH
    }

    /** True for Hebrew and other RTL languages, where text and icon order get mirrored. */
    public static boolean rtl() {
        return net.minecraft.locale.Language.getInstance().isDefaultRightToLeft();
    }

    /** GUI-pixel width of {@code s} at {@code scale} (0.75 means "small", see {@link #small()}). */
    public static float width(Font font, String s, float scale) {
        return font.width(s) * eff(scale);
    }

    /** The scale we actually draw at for a requested one. */
    public static float eff(float scale) {
        return scale == 0.75f ? small() : scale;
    }

    /** Line pitch for text at this scale. */
    public static int pitch(float scale) {
        return (int) Math.ceil(9 * eff(scale)) + (eff(scale) < 1f ? 1 : 0);
    }

    /**
     * Scale for small secondary text that stays pixel-crisp. The font's 1px strokes have to land on
     * whole screen pixels, so 3/4 turns into 2/3 at GUI scale 3, and full size at GUI scale 1-2.
     */
    public static float small() {
        double g = Minecraft.getInstance().getWindow().getGuiScale();
        int px = (int) Math.floor(g * 0.75);
        return px < 2 ? 1f : (float) (px / g);
    }

    // ------------------------------------------------------------------------------------- shapes

    /** Filled rect with 1px cut corners. */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Same rounded rect, outline only. */
    public static void stroke(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void box(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        round(g, x, y, w, h, border);
        round(g, x + 1, y + 1, w - 2, h - 2, fill);
    }

    /** 1px violet-to-cyan gradient line (the accent strip). */
    public static void accentLine(GuiGraphics g, int x, int y, int w) {
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            int x0 = x + w * i / steps;
            int x1 = x + w * (i + 1) / steps;
            g.fill(x0, y, x1, y + 1, lerp(ACCENT, ACCENT_2, i / (float) (steps - 1)));
        }
    }

    public static int lerp(int a, int b, float t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, aa = a >>> 24;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255, ba = b >>> 24;
        return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha & 255) << 24 | (color & 0xFFFFFF);
    }

    /** Main window chrome: dark body, stroke, title bar (icon, title, optional subtitle) and the accent underline. */
    public static void window(GuiGraphics g, Font font, int x, int y, int w, int h, Icon icon, Component title, Component subtitle) {
        shadow(g, x, y, w, h);
        box(g, x, y, w, h, BG, STROKE);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 19, 0xFF171B22, 0xFF111419);
        accentLine(g, x + 1, y + 19, w - 2);
        String t = clip(font, title.getString(), w - 40);
        if (rtl()) {
            int tx = x + w - 7;
            if (icon != null) {
                icon(g, icon, x + w - 18, y + 4, ACCENT_2);
                tx = x + w - 22;
            }
            g.drawString(font, t, tx - font.width(t), y + 6, TEXT, false);
            return;
        }
        int tx = x + 7;
        if (icon != null) {
            icon(g, icon, x + 6, y + 4, ACCENT_2);
            tx = x + 22;
        }
        g.drawString(font, t, tx, y + 6, TEXT, false);
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            int sx = tx + font.width(t) + 6;
            float sm = small();
            int sy = y + 6 + Math.round((8 - 8 * sm) / 2f);
            text(g, font, clip(font, subtitle.getString(), (int) ((x + w - 8 - sx) / sm)), sx, sy, MUTED, 0.75f);
        }
    }

    /** Soft drop shadow: three fading rings plus a darker band underneath. */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h) {
        int[] alphas = {0x40, 0x26, 0x12};
        for (int i = 0; i < alphas.length; i++) {
            int d = i + 1;
            int c = alphas[i] << 24;
            g.fill(x - d, y - d + 1, x, y + h + d - 1, c);
            g.fill(x + w, y - d + 1, x + w + d, y + h + d - 1, c);
            g.fill(x - d + 1, y - d, x + w + d - 1, y, c);
            g.fill(x - d + 1, y + h, x + w + d - 1, y + h + d, c);
        }
        g.fill(x + 2, y + h + 3, x + w - 2, y + h + 4, 0x30000000);
    }

    /** Old header the small screens still use, just the title bar strip. */
    public static void header(GuiGraphics g, Font font, int x, int y, int w, Component title) {
        g.fillGradient(x, y, x + w, y + 19, 0xFF171B22, 0xFF111419);
        accentLine(g, x, y + 19, w);
        g.drawString(font, title, x + 7, y + 6, TEXT, false);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        box(g, x, y, w, h, SURFACE, STROKE_SOFT);
    }

    public static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        box(g, x, y, w, h, hover ? CARD_HOVER : CARD, hover ? lerp(STROKE, ACCENT, 0.6f) : STROKE_SOFT);
    }

    /** Card with a small-caps section label in the top-left corner (top-right in RTL). */
    public static void section(GuiGraphics g, Font font, int x, int y, int w, int h, Component label) {
        box(g, x, y, w, h, SURFACE, STROKE_SOFT);
        if (label != null) {
            String s = label.getString().toUpperCase(Locale.ROOT);
            float tw = width(font, s, 0.75f);
            text(g, font, s, rtl() ? x + w - 6 - tw : x + 6, y + 5, MUTED, 0.75f);
        }
    }

    public static void progress(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
        round(g, x, y, w, h, 0xFF0A0C10);
        int fw = (int) Math.round(w * Mth.clamp(fraction, 0, 1));
        if (fw > 0) round(g, x, y, Math.max(2, fw), h, color);
    }

    public static void dot(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y + 1, x + 5, y + 4, color);
        g.fill(x + 1, y, x + 4, y + 5, color);
    }

    /** Rounded status pill ("● Running"), right-aligned at {@code right}. Returns its left x. */
    public static int pill(GuiGraphics g, Font font, int right, int y, String label, int color) {
        float sm = small();
        int tw = (int) Math.ceil(font.width(label) * sm);
        int w = tw + 16;
        int x = right - w;
        box(g, x, y, w, 11, withAlpha(color, 0x26), withAlpha(color, 0x90));
        dot(g, x + 4, y + 3, color);
        text(g, font, label, x + 12, y + Math.max(1, Math.round((11 - 8 * sm) / 2f)), color, 0.75f);
        return x;
    }

    // ------------------------------------------------------------------------------------- text & icons

    public static void text(GuiGraphics g, Font font, String s, float x, float y, int color, float scale) {
        if (scale == 0.75f) scale = small();
        if (scale == 1f) {
            g.drawString(font, s, (int) x, (int) y, color, false);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    /** Text clipped to {@code width} GUI px with an ellipsis, at the given scale (right-aligned in RTL). */
    public static void textFit(GuiGraphics g, Font font, String s, int x, int y, int width, int color, float scale) {
        float sc = eff(scale);
        String c = clip(font, s, (int) (width / sc));
        float tx = rtl() ? x + width - font.width(c) * sc : x;
        text(g, font, c, tx, y, color, scale);
    }

    /** Wrapped paragraph, max {@code maxLines} lines (the last one gets the ellipsis). Returns how many lines it drew. */
    public static int paragraph(GuiGraphics g, Font font, String s, int x, int y, int width, int maxLines, int color, float scale) {
        float sc = eff(scale);
        List<FormattedCharSequence> lines = font.split(Component.literal(s), (int) (width / sc));
        int n = Math.min(maxLines, lines.size());
        int pitch = pitch(scale);
        for (int i = 0; i < n; i++) {
            float ly = y + i * pitch;
            if (i == n - 1 && lines.size() > n) {
                // rebuild the last line's text so we can stick an ellipsis on it
                StringBuilder sb = new StringBuilder();
                lines.get(i).accept((idx, style, cp) -> {
                    sb.appendCodePoint(cp);
                    return true;
                });
                text(g, font, clip(font, sb + "…", (int) (width / sc)), x, ly, color, scale);
            } else {
                float lx = rtl() ? x + width - font.width(lines.get(i)) * sc : x;
                g.pose().pushPose();
                g.pose().translate(lx, ly, 0);
                g.pose().scale(sc, sc, 1f);
                g.drawString(font, lines.get(i), 0, 0, color, false);
                g.pose().popPose();
            }
        }
        return n;
    }

    public static String clip(Font font, String s, int width) {
        if (font.width(s) <= width) return s;
        String dots = "…";
        int w = width - font.width(dots);
        if (w <= 0) return "";
        return font.plainSubstrByWidth(s, w) + dots;
    }

    public static String ellipsize(Font font, String s, int width) {
        return clip(font, s, width);
    }

    public static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        int i = icon.ordinal();
        float a = (color >>> 24) / 255f;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, a == 0 ? 1f : a);
        g.blit(ICONS, x, y, (i % 8) * 16 + 2, (i / 8) * 16 + 2, 12, 12, 128, 128);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Ghost silhouette for an empty typed slot, 16x16 at x,y. */
    public static void slotGhost(GuiGraphics g, ComponentType type, int x, int y) {
        ResourceLocation tex = HyperVisorX.id("textures/gui/slot_" + type.key() + ".png");
        g.blit(tex, x, y, 0, 0, 16, 16, 16, 16);
    }

    /** Sunken inventory slot frame around a 16x16 item spot. */
    public static void slot(GuiGraphics g, int x, int y, int accent) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF2F3742);
        g.fill(x, y, x + 16, y + 16, 0xFF1A1F27);
        g.fill(x, y, x + 16, y + 1, 0xFF12161C);
        if (accent != 0) g.fill(x - 1, y + 16, x + 17, y + 17, withAlpha(accent, 0x99));
    }

    /** Label at full size, drops to 3/4 size if it doesn't fit, clips as a last resort. */
    public static void fitLabel(GuiGraphics g, Font font, String s, int x, int y, int width, int height, int color, boolean center) {
        if (font.width(s) <= width) {
            int tx = center ? x + (width - font.width(s)) / 2 : rtl() ? x + width - font.width(s) : x;
            g.drawString(font, s, tx, y + (height - 8) / 2, color, false);
            return;
        }
        float sm = small();
        String c = clip(font, s, (int) (width / sm));
        float tw = font.width(c) * sm;
        float tx = center ? x + (width - tw) / 2f : rtl() ? x + width - tw : x;
        text(g, font, c, tx, y + (height - 8 * sm) / 2f, color, 0.75f);
    }

    // ------------------------------------------------------------------------------------- tooltips & popovers

    private static List<Component> pendingTip;

    /** Queues a tooltip for this frame. Screens draw it last via {@link #renderOverlays}. */
    public static void queueTip(Component tip) {
        pendingTip = tip == null || tip.getString().isEmpty() ? null : List.of(tip);
    }

    public static void queueTip(List<Component> lines) {
        pendingTip = lines == null || lines.isEmpty() ? null : lines;
    }

    /** Our tooltip: CARD body, STROKE border, accent line on top. */
    public static void tooltip(GuiGraphics g, Font font, List<Component> lines, int mx, int my) {
        if (lines == null || lines.isEmpty()) return;
        java.util.List<FormattedCharSequence> rows = new java.util.ArrayList<>();
        for (Component c : lines) rows.addAll(font.split(c, 200));
        int w = 0;
        for (FormattedCharSequence r : rows) w = Math.max(w, font.width(r));
        int h = rows.size() * 10 - 1;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int x = mx + 12, y = my - 12;
        if (x + w + 10 > sw) x = Math.max(2, mx - w - 18);
        if (y + h + 10 > sh) y = sh - h - 10;
        y = Math.max(7, y);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        box(g, x - 5, y - 5, w + 10, h + 10, 0xF2191D24, STROKE);
        accentLine(g, x - 4, y - 5, w + 8);
        for (int i = 0; i < rows.size(); i++) {
            int lx = rtl() ? x + w - font.width(rows.get(i)) : x;
            g.drawString(font, rows.get(i), lx, y + i * 10, i == 0 ? TEXT : MUTED, false);
        }
        g.pose().popPose();
    }

    /** Draws the open popover, or the queued tooltip if there isn't one. Call it at the very end of a screen's render. */
    public static void renderOverlays(GuiGraphics g, Font font, int mx, int my) {
        if (popover != null) {
            popover.render(g, font, mx, my);
        } else if (pendingTip != null) {
            tooltip(g, font, pendingTip, mx, my);
        }
        pendingTip = null;
    }

    private static Popover<?> popover;

    /** Sends a click to the open popover. True if it ate the click (clicking outside just closes it). */
    public static boolean popoverClicked(double mx, double my, int button) {
        if (popover == null) return false;
        Popover<?> p = popover;
        popover = null;
        p.click(mx, my);
        return true;
    }

    public static boolean popoverScrolled(double mx, double my, double dy) {
        if (popover == null) return false;
        popover.scroll -= POPOVER_WHEEL.add(dy);
        popover.clampScroll();
        return true;
    }

    /** Esc closes an open popover first. */
    public static boolean popoverKey(int key) {
        if (popover != null && key == 256) {
            popover = null;
            return true;
        }
        return false;
    }

    public static void closePopover() {
        popover = null;
    }

    /** Dropdown list for a {@link ChoiceButton}: 14px CARD rows, a check on the current value, scrolls with the wheel. */
    private static final class Popover<T> {
        static final int ROW = 14;
        static final int MAX_ROWS = 8;
        final ChoiceButton<T> owner;
        int scroll;

        Popover(ChoiceButton<T> owner) {
            this.owner = owner;
            this.scroll = Math.max(0, owner.index - MAX_ROWS / 2);
            clampScroll();
        }

        void clampScroll() {
            scroll = Mth.clamp(scroll, 0, Math.max(0, owner.values.size() - MAX_ROWS));
        }

        int rows() {
            return Math.min(MAX_ROWS, owner.values.size());
        }

        int[] bounds(Font font) {
            int w = owner.getWidth();
            for (T v : owner.values) w = Math.max(w, font.width(owner.names.apply(v)) + 26);
            int h = rows() * ROW + 2;
            int x = owner.getX();
            int y = owner.getY() + owner.getHeight() + 1;
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
            if (y + h > sh - 2) y = owner.getY() - h - 1;
            if (x + w > sw - 2) x = sw - 2 - w;
            return new int[]{x, y, w, h};
        }

        void render(GuiGraphics g, Font font, int mx, int my) {
            int[] b = bounds(font);
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            shadow(g, b[0], b[1], b[2], b[3]);
            box(g, b[0], b[1], b[2], b[3], CARD, lerp(STROKE, ACCENT, 0.6f));
            for (int i = 0; i < rows(); i++) {
                int idx = scroll + i;
                int ry = b[1] + 1 + i * ROW;
                boolean hover = mx >= b[0] && mx < b[0] + b[2] && my >= ry && my < ry + ROW;
                if (hover) g.fill(b[0] + 1, ry, b[0] + b[2] - 1, ry + ROW, CARD_HOVER);
                boolean sel = idx == owner.index;
                String label = clip(font, owner.names.apply(owner.values.get(idx)), b[2] - 22);
                int tx = rtl() ? b[0] + b[2] - 16 - font.width(label) : b[0] + 16;
                if (sel) icon(g, Icon.CHECK, rtl() ? b[0] + b[2] - 14 : b[0] + 2, ry + 1, ACCENT_2);
                g.drawString(font, label, tx, ry + 3, sel ? TEXT : MUTED, false);
            }
            if (owner.values.size() > MAX_ROWS) {
                scrollbar(g, b[0] + b[2] - 4, b[1] + 2, b[3] - 4, scroll, owner.values.size() - MAX_ROWS, MAX_ROWS, owner.values.size());
            }
            g.pose().popPose();
        }

        void click(double mx, double my) {
            int[] b = bounds(Minecraft.getInstance().font);
            if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] + 1 || my >= b[1] + 1 + rows() * ROW) return;
            int idx = scroll + (int) ((my - b[1] - 1) / ROW);
            if (idx >= 0 && idx < owner.values.size()) owner.choose(idx);
        }
    }

    /**
     * Smooth list scrolling. The wheel moves a target (fractional touchpad deltas too) and the drawn
     * offset eases toward it each frame, so lists glide instead of jumping 20px per notch.
     */
    public static final class Scroll {
        private float target;
        private float current;
        private long last = net.minecraft.Util.getMillis();

        public void scrollBy(double wheel, float step) {
            target -= (float) (wheel * step);
        }

        public void reset() {
            target = current = 0;
        }

        /** Clamps to {@code max}, eases toward the target, returns the pixel offset. Call once per frame. */
        public int update(int max) {
            long now = net.minecraft.Util.getMillis();
            float dt = Math.min(0.1f, (now - last) / 1000f);
            last = now;
            max = Math.max(0, max);
            target = Mth.clamp(target, 0, max);
            current = Mth.clamp(current, 0, max);
            current += (target - current) * (1f - (float) Math.exp(-dt * 22f));
            if (Math.abs(target - current) < 0.4f) current = target;
            return px();
        }

        public int px() {
            return Math.round(current);
        }
    }

    /** Turns fractional wheel deltas (touchpads, smooth wheels) into whole notches. */
    public static final class WheelSteps {
        private double acc;

        public int add(double delta) {
            if (Math.signum(delta) != Math.signum(acc)) acc = 0;
            acc += delta;
            int steps = (int) acc;
            acc -= steps;
            return steps;
        }
    }

    private static final WheelSteps POPOVER_WHEEL = new WheelSteps();

    /** 3px scrollbar (track #1A1F27, thumb #3A414D). {@code max} = largest scroll offset. */
    public static void scrollbar(GuiGraphics g, int x, int y, int h, int offset, int max, int visible, int total) {
        if (max <= 0 || total <= 0) return;
        g.fill(x, y, x + 3, y + h, 0xFF1A1F27);
        int th = Math.max(8, h * visible / total);
        int ty = y + (int) ((h - th) * (offset / (float) max));
        g.fill(x, ty, x + 3, ty + th, 0xFF3A414D);
    }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ------------------------------------------------------------------------------------- widgets

    public enum Style { NORMAL, PRIMARY, DANGER, GHOST }

    /** Rounded button. Optional icon and tooltip, label and enabled state can be live. */
    public static class FlatButton extends AbstractButton {
        private final Runnable action;
        private Supplier<Component> label;
        private Supplier<Boolean> enabled = () -> true;
        private Supplier<Icon> icon = () -> null;
        private int accent = ACCENT;
        private Style style = Style.NORMAL;
        private boolean selected;
        private Supplier<Boolean> selectedWhen;
        Supplier<Component> tipWhen;
        Component tip;
        private boolean alignStart;

        public FlatButton(int x, int y, int w, int h, Component label, Runnable action) {
            super(x, y, w, h, label);
            this.action = action;
            this.label = () -> label;
        }

        public FlatButton label(Supplier<Component> label) {
            this.label = label;
            return this;
        }

        public FlatButton enabledWhen(Supplier<Boolean> enabled) {
            this.enabled = enabled;
            return this;
        }

        public FlatButton accent(int color) {
            this.accent = color;
            return this;
        }

        public FlatButton icon(Icon icon) {
            this.icon = () -> icon;
            return this;
        }

        public FlatButton icon(Supplier<Icon> icon) {
            this.icon = icon;
            return this;
        }

        public FlatButton style(Style style) {
            this.style = style;
            return this;
        }

        public FlatButton selectedWhen(Supplier<Boolean> selected) {
            this.selectedWhen = selected;
            return this;
        }

        public FlatButton tip(Component tip) {
            this.tip = tip;
            return this;
        }

        /** Icon + label start at the left (nav lists) instead of sitting centered. */
        public FlatButton alignStart() {
            this.alignStart = true;
            return this;
        }

        /** Tooltip that follows state, like "Power on" / "Shut down". */
        public FlatButton tip(Supplier<Component> tip) {
            this.tipWhen = tip;
            return this;
        }

        @Override
        public void onPress() {
            if (active) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            active = enabled.get();
            selected = selectedWhen != null && selectedWhen.get();
            setMessage(label.get());
            if (isHovered()) {
                Component t = tipWhen != null ? tipWhen.get() : tip;
                if (t != null) queueTip(t);
            }
            boolean hover = isHoveredOrFocused() && active;
            int x = getX(), y = getY(), w = width, h = height;
            int fill, border, fg;
            if (!active) {
                fill = 0xFF14171C;
                border = STROKE_SOFT;
                fg = DIM;
            } else if (style == Style.PRIMARY) {
                fill = hover ? lerp(accent, 0xFFFFFFFF, 0.12f) : accent;
                border = lerp(accent, 0xFFFFFFFF, 0.25f);
                fg = 0xFFFFFFFF;
            } else if (selected && alignStart) {
                fill = CARD;
                border = 0;
                fg = TEXT;
            } else if (selected) {
                fill = lerp(CARD, accent, hover ? 0.32f : 0.22f);
                border = accent;
                fg = TEXT;
            } else if (style == Style.GHOST) {
                fill = hover ? CARD : 0x00000000;
                border = hover ? STROKE : 0x00000000;
                fg = hover ? TEXT : MUTED;
            } else {
                fill = hover ? CARD_HOVER : CARD;
                border = hover ? lerp(STROKE, style == Style.DANGER ? ERROR : accent, 0.75f) : STROKE;
                fg = hover && style == Style.DANGER ? ERROR : TEXT;
            }
            if (border != 0) round(g, x, y, w, h, border);
            if (fill != 0) round(g, x + 1, y + 1, w - 2, h - 2, fill);
            if (selected && alignStart) g.fill(rtl() ? x + w - 2 : x, y + 2, rtl() ? x + w : x + 2, y + h - 2, accent);
            Font font = Minecraft.getInstance().font;
            Icon ic = icon.get();
            String text = getMessage().getString();
            int iconColor = !active ? DIM : style == Style.PRIMARY ? 0xFFFFFFFF : style == Style.DANGER ? ERROR : accent;
            int iy = y + (h - 12) / 2;
            int avail = w - 8 - (ic != null ? 15 : 0);
            float sm = small();
            // too narrow even for the small label, so just the icon (tooltip still says what it is)
            boolean iconOnly = ic != null && (text.isEmpty() || font.width(text) * sm > avail && avail < 24);
            if (iconOnly) {
                if (!text.isEmpty() && isHovered() && tip == null && tipWhen == null) queueTip(getMessage());
                UiKit.icon(g, ic, x + (w - 12) / 2, iy, iconColor);
                return;
            }
            boolean small = font.width(text) > avail;
            String shown = small ? clip(font, text, (int) (avail / sm)) : text;
            float textW = small ? font.width(shown) * sm : font.width(shown);
            float contentW = (ic != null ? 15 : 0) + textW;
            float start = alignStart ? 6 : Math.max(4, (w - contentW) / 2f);
            boolean r = rtl();
            float cx = r ? x + w - start - contentW : x + start;
            if (ic != null && !r) {
                UiKit.icon(g, ic, (int) cx, iy, iconColor);
                cx += alignStart ? 16 : 15;
            }
            if (small) text(g, font, shown, cx, y + (h - 8 * sm) / 2f, fg, 0.75f);
            else g.drawString(font, shown, (int) cx, y + (h - 8) / 2, fg, false);
            if (ic != null && r) UiKit.icon(g, ic, (int) (cx + textW + 3), iy, iconColor);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Dropdown: click opens the list, mouse wheel steps through values. The prefix is the field name. */
    public static class ChoiceButton<T> extends FlatButton {
        private final List<T> values;
        private int index;
        private final Consumer<T> onChange;
        private final String prefix;
        private final Function<T, String> names;

        public ChoiceButton(int x, int y, int w, int h, String prefix, List<T> values, T initial, Function<T, String> names, Consumer<T> onChange) {
            super(x, y, w, h, Component.empty(), () -> { });
            this.values = new java.util.ArrayList<>(values);
            this.onChange = onChange;
            this.index = Math.max(0, values.indexOf(initial));
            this.prefix = prefix;
            this.names = names;
            label(() -> Component.literal(prefix + names.apply(value())));
        }

        public T value() {
            return values.isEmpty() ? null : values.get(Math.min(index, values.size() - 1));
        }

        public void set(T v) {
            int i = values.indexOf(v);
            if (i >= 0) index = i;
        }

        public void setValues(List<T> newValues) {
            T current = value();
            values.clear();
            values.addAll(newValues);
            index = Math.max(0, values.indexOf(current));
        }

        public boolean contains(T v) {
            return values.contains(v);
        }

        public void add(T v) {
            if (!values.contains(v)) values.add(v);
        }

        void choose(int i) {
            if (i < 0 || i >= values.size()) return;
            index = i;
            onChange.accept(value());
        }

        @Override
        public void onPress() {
            if (!active || values.size() < 2) return;
            if (popover != null && popover.owner == this) {
                popover = null;
                return;
            }
            popover = new Popover<>(this);
        }

        private final WheelSteps wheel = new WheelSteps();

        @Override
        public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            if (!active || !isMouseOver(mx, my) || values.size() < 2 || dy == 0) return false;
            int steps = wheel.add(dy);
            if (steps != 0) choose(Mth.clamp(index - steps, 0, values.size() - 1));
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            active = true;
            boolean open = popover != null && popover.owner == this;
            boolean hover = isHoveredOrFocused() || open;
            int x = getX(), y = getY(), w = width, h = height;
            round(g, x, y, w, h, open ? ACCENT : hover ? lerp(STROKE, ACCENT, 0.75f) : STROKE);
            round(g, x + 1, y + 1, w - 2, h - 2, hover ? CARD_HOVER : FIELD);
            if (isHovered()) {
                Component t = tipWhen != null ? tipWhen.get() : tip;
                if (t != null) queueTip(t);
            }
            Font font = Minecraft.getInstance().font;
            String value = names.apply(value());
            int avail = w - 16;
            boolean r = rtl();
            int ty = y + (h - 8) / 2;
            String pre = prefix.isEmpty() ? "" : prefix.strip() + " ";
            if (!pre.isEmpty() && font.width(pre + value) <= avail) {
                int tx = r ? x + w - 5 - font.width(pre + value) : x + 5;
                if (r) {
                    g.drawString(font, value, tx, ty, TEXT, false);
                    g.drawString(font, pre, tx + font.width(value + " "), ty, MUTED, false);
                } else {
                    g.drawString(font, pre, tx, ty, MUTED, false);
                    g.drawString(font, value, tx + font.width(pre), ty, TEXT, false);
                }
            } else {
                fitLabel(g, font, value, r ? x + 11 : x + 5, y, avail, h, TEXT, false);
            }
            // one chevron, flips up while the list is open
            int cx = r ? x + 7 : x + w - 8, cy = y + h / 2;
            int c = open ? ACCENT_2 : MUTED;
            if (open) {
                g.fill(cx - 2, cy + 1, cx + 3, cy + 2, c);
                g.fill(cx - 1, cy, cx + 2, cy + 1, c);
                g.fill(cx, cy - 1, cx + 1, cy, c);
            } else {
                g.fill(cx - 2, cy - 1, cx + 3, cy, c);
                g.fill(cx - 1, cy, cx + 2, cy + 1, c);
                g.fill(cx, cy + 1, cx + 1, cy + 2, c);
            }
        }
    }

    /** Text field in our style (vanilla EditBox with its own border turned off). */
    public static class Field extends EditBox {
        private int ox, oy, ow;
        private final int oh;
        private String label = "";
        private final Font labelFont;

        /** Field with its name drawn in MUTED inside the frame, so you can still tell what it is once it's filled in. */
        public Field(Font font, int x, int y, int w, int h, Component name, String label) {
            this(font, x + (label.isEmpty() ? 0 : font.width(label) + 4), y, w - (label.isEmpty() ? 0 : font.width(label) + 4), h, name);
            this.label = label;
        }

        public Field(Font font, int x, int y, int w, int h, Component name) {
            super(font, x + 5, y + (h - 8) / 2, w - 10, 9, name);
            this.labelFont = font;
            // placeholders should never look like real values
            setHint(name);
            this.ox = x;
            this.oy = y;
            this.ow = w;
            this.oh = h;
            setBordered(false);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hot = isFocused() || (mouseX >= ox && mouseX < ox + ow && mouseY >= oy && mouseY < oy + oh);
            int lw = label.isEmpty() ? 0 : labelFont.width(label) + 4;
            round(g, ox - lw, oy, ow + lw, oh, isFocused() ? ACCENT : hot ? lerp(STROKE, ACCENT, 0.5f) : STROKE);
            round(g, ox - lw + 1, oy + 1, ow + lw - 2, oh - 2, FIELD);
            if (lw > 0) g.drawString(labelFont, label, ox - lw + 5, oy + (oh - 8) / 2, MUTED, false);
            super.renderWidget(g, mouseX, mouseY, partialTick);
        }

        /** Moves the whole thing (frame x/y, label included). */
        public void moveTo(int x, int y) {
            int lw = label.isEmpty() ? 0 : labelFont.width(label) + 4;
            ox = x + lw;
            oy = y;
            setX(ox + 5);
            setY(oy + (oh - 8) / 2);
        }

        @Override
        public void setWidth(int w) {
            int lw = label == null || label.isEmpty() ? 0 : labelFont.width(label) + 4;
            ow = w - lw;
            super.setWidth(Math.max(1, ow - 10));
        }

        @Override
        public void setHint(Component hint) {
            // EditBox doesn't clip its hint, so cut it to the field width or it spills out of the frame
            String text = labelFont == null ? hint.getString() : clip(labelFont, hint.getString(), Math.max(8, getWidth() - 2));
            Component c = Component.literal(text).withStyle(hint.getStyle());
            super.setHint(c.getStyle().getColor() == null ? c.copy().withStyle(st -> st.withColor(DIM & 0xFFFFFF)) : c);
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            int lw = label.isEmpty() ? 0 : labelFont.width(label) + 4;
            return visible && mx >= ox - lw && mx < ox + ow && my >= oy && my < oy + oh;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (isMouseOver(mx, my) && !(mx >= getX() && mx < getX() + getWidth())) {
                setFocused(true);
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }
    }
}
