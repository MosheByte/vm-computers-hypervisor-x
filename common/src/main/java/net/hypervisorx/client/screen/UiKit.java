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
    public static final int OK = 0xFF3DDC84;
    public static final int WARN = 0xFFFFB020;
    public static final int ERROR = 0xFFFF5470;

    private static final ResourceLocation ICONS = HyperVisorX.id("textures/gui/icons.png");

    private UiKit() {
    }

    /** Glyphs in textures/gui/icons.png (12x12 on a 16px grid, 8 per row), made by tools/gen_icons.py. */
    public enum Icon {
        POWER, STOP, PLAY, RESET, CAMERA, DISC, USB, LIBRARY, FOCUS, GEAR, EYE, LOCK, USER_PLUS, USER_MINUS, CART, SEARCH, DOWNLOAD,
        FOLDER, CHECK, CROSS, WARN, MONITOR, BACK, TRASH, CHIP, MEMORY, DRIVE, WIFI, PLUS, MINUS, BOX, ROCKET, INFO, CLOSE, KEYBOARD, SERVER
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
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x55000000);
        box(g, x, y, w, h, BG, STROKE);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 19, 0xFF171B22, 0xFF111419);
        accentLine(g, x + 1, y + 19, w - 2);
        int tx = x + 7;
        if (icon != null) {
            icon(g, icon, x + 6, y + 4, ACCENT_2);
            tx = x + 22;
        }
        g.drawString(font, title, tx, y + 6, TEXT, false);
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            int sx = tx + font.width(title) + 6;
            text(g, font, clip(font, subtitle.getString(), (int) ((x + w - 8 - sx) / 0.75f)), sx, y + 7, MUTED, 0.75f);
        }
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
        if (label != null) text(g, font, label.getString().toUpperCase(Locale.ROOT), x + 6, y + 5, DIM, 0.75f);
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
        int tw = (int) Math.ceil(font.width(label) * 0.75f);
        int w = tw + 16;
        int x = right - w;
        box(g, x, y, w, 11, withAlpha(color, 0x26), withAlpha(color, 0x90));
        dot(g, x + 4, y + 3, color);
        text(g, font, label, x + 12, y + 2, color, 0.75f);
        return x;
    }

    // ------------------------------------------------------------------------------------- text & icons

    public static void text(GuiGraphics g, Font font, String s, float x, float y, int color, float scale) {
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

    /** Draws text clipped with an ellipsis to {@code width} GUI pixels at the given scale. */
    public static void textFit(GuiGraphics g, Font font, String s, int x, int y, int width, int color, float scale) {
        text(g, font, clip(font, s, (int) (width / scale)), x, y, color, scale);
    }

    /** Wrapped paragraph, max {@code maxLines} lines (the last one gets the ellipsis). Returns how many lines it drew. */
    public static int paragraph(GuiGraphics g, Font font, String s, int x, int y, int width, int maxLines, int color, float scale) {
        List<FormattedCharSequence> lines = font.split(Component.literal(s), (int) (width / scale));
        int n = Math.min(maxLines, lines.size());
        for (int i = 0; i < n; i++) {
            float ly = y + i * 9 * scale + (scale < 1 ? i : 0);
            if (i == n - 1 && lines.size() > n) {
                // rebuild the last line's text so we can stick an ellipsis on it
                StringBuilder sb = new StringBuilder();
                lines.get(i).accept((idx, style, cp) -> {
                    sb.appendCodePoint(cp);
                    return true;
                });
                text(g, font, clip(font, sb + "…", (int) (width / scale)), x, ly, color, scale);
            } else {
                g.pose().pushPose();
                g.pose().translate(x, ly, 0);
                g.pose().scale(scale, scale, 1f);
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
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF2A303A);
        g.fill(x, y, x + 16, y + 16, 0xFF0B0D11);
        g.fill(x, y, x + 16, y + 1, 0xFF07080B);
        if (accent != 0) g.fill(x - 1, y + 16, x + 17, y + 17, withAlpha(accent, 0xD0));
    }

    /** Label at full size, drops to 3/4 size if it doesn't fit, clips as a last resort. */
    public static void fitLabel(GuiGraphics g, Font font, String s, int x, int y, int width, int height, int color, boolean center) {
        if (font.width(s) <= width) {
            int tx = center ? x + (width - font.width(s)) / 2 : x;
            g.drawString(font, s, tx, y + (height - 8) / 2, color, false);
            return;
        }
        String c = clip(font, s, (int) (width / 0.75f));
        float tw = font.width(c) * 0.75f;
        float tx = center ? x + (width - tw) / 2f : x;
        text(g, font, c, tx, y + (height - 6) / 2f, color, 0.75f);
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
        private Supplier<Component> tipWhen;
        private String lastTip;

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
            setTooltip(Tooltip.create(tip));
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
            if (tipWhen != null) {
                Component t = tipWhen.get();
                if (!t.getString().equals(lastTip)) {
                    lastTip = t.getString();
                    setTooltip(Tooltip.create(t));
                }
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
            Font font = Minecraft.getInstance().font;
            Icon ic = icon.get();
            String text = getMessage().getString();
            int iconColor = !active ? DIM : style == Style.PRIMARY ? 0xFFFFFFFF : style == Style.DANGER ? ERROR : accent;
            if (ic != null && text.isEmpty()) {
                UiKit.icon(g, ic, x + (w - 12) / 2, y + (h - 12) / 2, iconColor);
                return;
            }
            int avail = w - 8 - (ic != null ? 15 : 0);
            boolean small = font.width(text) > avail;
            String shown = small ? clip(font, text, (int) (avail / 0.75f)) : text;
            float textW = small ? font.width(shown) * 0.75f : font.width(shown);
            float contentW = (ic != null ? 15 : 0) + textW;
            float cx = x + Math.max(4, (w - contentW) / 2f);
            if (ic != null) {
                UiKit.icon(g, ic, (int) cx, y + (h - 12) / 2, iconColor);
                cx += 15;
            }
            if (small) text(g, font, shown, cx, y + (h - 6) / 2f, fg, 0.75f);
            else g.drawString(font, shown, (int) cx, y + (h - 8) / 2, fg, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Dropdown-like button cycling through values (left click forward, shift-click back). */
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

        @Override
        public void onPress() {
            if (!active || values.isEmpty()) return;
            index = (index + (net.minecraft.client.gui.screens.Screen.hasShiftDown() ? values.size() - 1 : 1)) % values.size();
            onChange.accept(value());
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            active = true;
            boolean hover = isHoveredOrFocused();
            int x = getX(), y = getY(), w = width, h = height;
            round(g, x, y, w, h, hover ? lerp(STROKE, ACCENT, 0.75f) : STROKE);
            round(g, x + 1, y + 1, w - 2, h - 2, hover ? CARD_HOVER : FIELD);
            Font font = Minecraft.getInstance().font;
            String text = prefix + names.apply(value());
            fitLabel(g, font, text, x + 5, y, w - 16, h, TEXT, false);
            // chevrons
            int cx = x + w - 8, cy = y + h / 2;
            g.fill(cx - 2, cy - 2, cx + 1, cy - 1, MUTED);
            g.fill(cx - 1, cy - 3, cx, cy - 2, MUTED);
            g.fill(cx - 2, cy + 1, cx + 1, cy + 2, MUTED);
            g.fill(cx - 1, cy + 2, cx, cy + 3, MUTED);
        }
    }

    /** Text field in our style (vanilla EditBox with its own border turned off). */
    public static class Field extends EditBox {
        private final int ox, oy, ow, oh;

        public Field(Font font, int x, int y, int w, int h, Component name) {
            super(font, x + 5, y + (h - 8) / 2, w - 10, 9, name);
            this.ox = x;
            this.oy = y;
            this.ow = w;
            this.oh = h;
            setBordered(false);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hot = isFocused() || (mouseX >= ox && mouseX < ox + ow && mouseY >= oy && mouseY < oy + oh);
            round(g, ox, oy, ow, oh, isFocused() ? ACCENT : hot ? lerp(STROKE, ACCENT, 0.5f) : STROKE);
            round(g, ox + 1, oy + 1, ow - 2, oh - 2, FIELD);
            super.renderWidget(g, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return visible && mx >= ox && mx < ox + ow && my >= oy && my < oy + oh;
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
