package net.hypervisorx.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Flat, dark "HyperVisor X" look shared by all screens. */
public final class UiKit {
    public static final int BG = 0xF0101319;
    public static final int PANEL = 0xFF161B24;
    public static final int CARD = 0xFF1C2330;
    public static final int CARD_HOVER = 0xFF243044;
    public static final int BORDER = 0xFF2A3445;
    public static final int ACCENT = 0xFF38C6FF;
    public static final int ACCENT_2 = 0xFFB46CFF;
    public static final int TEXT = 0xFFE8EEF8;
    public static final int MUTED = 0xFF8A94A6;
    public static final int OK = 0xFF4ADE80;
    public static final int WARN = 0xFFFACC15;
    public static final int ERROR = 0xFFF87171;

    private UiKit() {
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, PANEL);
        g.fill(x, y + 1, x + w, y + h - 1, PANEL);
        g.fill(x + 1, y, x + w - 1, y + 1, BORDER);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, BORDER);
        g.fill(x, y + 1, x + 1, y + h - 1, BORDER);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, BORDER);
    }

    public static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        g.fill(x, y, x + w, y + h, hover ? CARD_HOVER : CARD);
        g.fill(x, y, x + w, y + 1, hover ? ACCENT : BORDER);
    }

    /** Header strip with a gradient accent line. */
    public static void header(GuiGraphics g, Font font, int x, int y, int w, Component title) {
        g.fillGradient(x, y, x + w, y + 18, 0xFF1E2A3C, 0xFF161B24);
        g.fill(x, y + 17, x + w / 2, y + 18, ACCENT);
        g.fill(x + w / 2, y + 17, x + w, y + 18, ACCENT_2);
        g.drawString(font, title, x + 6, y + 5, TEXT, false);
    }

    public static void progress(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
        g.fill(x, y, x + w, y + h, 0xFF0B0E13);
        int fw = (int) Math.round(w * Mth.clamp(fraction, 0, 1));
        g.fill(x, y, x + fw, y + h, color);
    }

    public static void dot(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y + 1, x + 5, y + 4, color);
        g.fill(x + 1, y, x + 4, y + 5, color);
    }

    public static String ellipsize(Font font, String s, int width) {
        if (font.width(s) <= width) return s;
        String dots = "...";
        int w = width - font.width(dots);
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (font.width(sb.toString() + c) > w) break;
            sb.append(c);
        }
        return sb + dots;
    }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    /** Flat button with optional accent colour and dynamic label/enabled state. */
    public static class FlatButton extends AbstractButton {
        private final Runnable action;
        private Supplier<Component> label;
        private Supplier<Boolean> enabled = () -> true;
        private int accent = ACCENT;

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

        @Override
        public void onPress() {
            if (active) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            active = enabled.get();
            setMessage(label.get());
            boolean hover = isHoveredOrFocused() && active;
            int bg = !active ? 0xFF151A22 : hover ? CARD_HOVER : CARD;
            g.fill(getX(), getY(), getX() + width, getY() + height, bg);
            g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, active ? accent : BORDER);
            Font font = Minecraft.getInstance().font;
            String text = ellipsize(font, getMessage().getString(), width - 6);
            g.drawCenteredString(font, text, getX() + width / 2, getY() + (height - 8) / 2, active ? TEXT : MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Button cycling through a list of values. */
    public static class ChoiceButton<T> extends FlatButton {
        private final List<T> values;
        private int index;
        private final Consumer<T> onChange;

        public ChoiceButton(int x, int y, int w, int h, String prefix, List<T> values, T initial, Function<T, String> names, Consumer<T> onChange) {
            super(x, y, w, h, Component.empty(), () -> { });
            this.values = new java.util.ArrayList<>(values);
            this.onChange = onChange;
            this.index = Math.max(0, values.indexOf(initial));
            label(() -> Component.literal(prefix + names.apply(value())));
        }

        public T value() {
            return values.isEmpty() ? null : values.get(Math.min(index, values.size() - 1));
        }

        public void set(T v) {
            int i = values.indexOf(v);
            if (i >= 0) index = i;
        }

        /** Replaces the options, keeping the current value selected when it still exists. */
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
    }
}
