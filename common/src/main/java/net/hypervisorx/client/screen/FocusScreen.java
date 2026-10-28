package net.hypervisorx.client.screen;

import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.client.HvxKeys;
import net.hypervisorx.client.ScreenTracker;
import net.hypervisorx.client.render.ScreenTexture;
import net.hypervisorx.client.render.ScreenTextureManager;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * "Focus Zoom / Fullscreen Lock": the VM fills the window and receives every key (including
 * Escape and Tab) and absolute mouse input. The dedicated release key (Right Ctrl by default)
 * gives control back to Minecraft without any Escape juggling.
 */
public class FocusScreen extends Screen {
    private final UUID vm;
    private final boolean laptop;
    private final long openedAt = System.currentTimeMillis();
    private final Set<Integer> heldKeys = new HashSet<>();
    private int buttons;
    private long lastPointer;
    private int lastVmX = -1;
    private int lastVmY = -1;
    private float rectX0, rectY0, rectX1, rectY1;
    private double mouseY;
    private final UiKit.WheelSteps wheel = new UiKit.WheelSteps();
    /** host cursor is hidden right now (the guest draws its own) */
    private boolean cursorHidden;
    private final java.util.List<UiKit.FlatButton> bar = new java.util.ArrayList<>();
    /** 0 = toolbar tucked away above the window, 1 = fully out. eased every frame */
    private float slide = 1f;
    private long lastFrame = System.currentTimeMillis();
    private int pillX, pillW;
    private static final int PILL_H = 22;

    public FocusScreen(UUID vm, boolean laptop) {
        super(Component.translatable("hypervisorx.focus.title"));
        this.vm = vm;
        this.laptop = laptop;
    }

    public UUID vm() {
        return vm;
    }

    @Override
    protected void init() {
        bar.clear();
        // floating toolbar pill up top: power, stop, reset, Ctrl+Alt+Del, snapshot, release
        UiKit.FlatButton power = new UiKit.FlatButton(0, 0, 22, 16, Component.empty(), () -> action(running()
                ? C2S.HostAction.SHUTDOWN : C2S.HostAction.POWER_ON)).icon(UiKit.Icon.POWER).accent(UiKit.OK);
        power.tip(() -> Component.translatable(running() ? "hypervisorx.focus.shutdown" : "hypervisorx.focus.power_on"));
        bar.add(addRenderableWidget(power));
        addBar(UiKit.Icon.STOP, Component.translatable("hypervisorx.focus.force_off"), () -> action(C2S.HostAction.FORCE_OFF), UiKit.ERROR);
        addBar(UiKit.Icon.RESET, Component.translatable("hypervisorx.focus.reset"), () -> action(C2S.HostAction.RESET), UiKit.WARN);
        addBar(UiKit.Icon.KEYBOARD, Component.literal("Ctrl+Alt+Del"), () -> action(C2S.HostAction.CTRL_ALT_DEL), UiKit.ACCENT_2);
        addBar(UiKit.Icon.CAMERA, Component.translatable("hypervisorx.focus.snapshot"), () -> action(C2S.HostAction.SNAPSHOT), UiKit.ACCENT);
        Component release = Component.translatable("hypervisorx.focus.release", HvxKeys.RELEASE.getTranslatedKeyMessage());
        // sized to its label so "Release (Right Control)" never gets clipped (only capped at half the window)
        int rw = Math.min(width / 2, font.width(release) + 30);
        bar.add(addRenderableWidget(new UiKit.FlatButton(0, 0, rw, 16, release, this::onClose).icon(UiKit.Icon.BACK).style(UiKit.Style.PRIMARY)));
    }

    private void addBar(UiKit.Icon icon, Component tip, Runnable action, int accent) {
        UiKit.FlatButton b = new UiKit.FlatButton(0, 0, 22, 16, Component.empty(), action).icon(icon).accent(accent).tip(tip);
        if (accent == UiKit.ERROR) b.style(UiKit.Style.DANGER);
        bar.add(addRenderableWidget(b));
    }

    private int pillY() {
        return Math.round(4 - (PILL_H + 6) * (1 - slide));
    }

    private void action(int action) {
        Services.PLATFORM.sendToServer(new C2S.VmAction(vm, action));
    }

    private boolean running() {
        S2C.VmStatus s = ClientVmCache.get(vm);
        return s != null && !s.state().equals("STOPPED") && !s.state().equals("ERROR");
    }

    private boolean overlayVisible() {
        return System.currentTimeMillis() - openedAt < 3500 || mouseY < PILL_H + 10 || !running();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF000000);
    }

    /** alt-tab with a key held would leave it stuck down in the guest, so let go of everything when we lose focus */
    private void releaseAll() {
        for (int k : heldKeys) Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.KEY, k, 0, 0, 0));
        heldKeys.clear();
        if (buttons != 0 && lastVmX >= 0) {
            buttons = 0;
            Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.POINTER, lastVmX, lastVmY, 0, 0));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!minecraft.isWindowActive() && (!heldKeys.isEmpty() || buttons != 0)) releaseAll();
        renderBackground(g, mouseX, mouseY, partialTick);
        ScreenTracker.markVisible(vm, null);
        ScreenTextureManager.uploadAll();
        S2C.VmStatus status = ClientVmCache.get(vm);
        ScreenTexture tex = ScreenTextureManager.get(vm);
        float zoom = 1f;
        if (HvxClientConfig.get().focusZoomAnimation) {
            float t = Mth.clamp((System.currentTimeMillis() - openedAt) / 220f, 0f, 1f);
            zoom = 0.86f + 0.14f * (1 - (1 - t) * (1 - t));
        }
        if (tex != null && tex.hasContent() && running()) {
            float aspect = tex.width() / (float) tex.height();
            float w = width;
            float h = width / aspect;
            if (h > height) {
                h = height;
                w = height * aspect;
            }
            w *= zoom;
            h *= zoom;
            rectX0 = (width - w) / 2f;
            rectY0 = (height - h) / 2f;
            rectX1 = rectX0 + w;
            rectY1 = rectY0 + h;
            GuiTex.draw(g, tex.location(), rectX0, rectY0, rectX1, rectY1);
        } else {
            rectX0 = rectX1 = rectY0 = rectY1 = 0;
            int cw = 260;
            int ch = 70;
            int x = (width - cw) / 2;
            int y = (height - ch) / 2;
            UiKit.panel(g, x, y, cw, ch);
            Component title = Component.literal(status == null ? "..." : status.name());
            g.drawCenteredString(font, title, width / 2, y + 12, UiKit.TEXT);
            String state = status == null ? "" : status.state();
            Component line = state.equals("ERROR") ? Component.literal(UiKit.ellipsize(font, status.message(), cw - 12))
                    : Component.translatable(running() ? "hypervisorx.screen.booting" : "hypervisorx.focus.off");
            g.drawCenteredString(font, line, width / 2, y + 30, state.equals("ERROR") ? UiKit.ERROR : UiKit.MUTED);
            g.drawCenteredString(font, Component.translatable("hypervisorx.focus.hint", HvxKeys.RELEASE.getTranslatedKeyMessage()),
                    width / 2, y + 48, UiKit.MUTED);
        }
        // slide the toolbar in and out, ~150 ms
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        slide = Mth.clamp(slide + (overlayVisible() ? 1 : -1) * dt / 0.15f, 0f, 1f);
        if (slide > 0f) {
            String name = status == null ? "" : status.name();
            String info = status == null ? "" : String.format("%s  %dx%d  %.0f fps  [%s]", status.state(), status.width(), status.height(),
                    status.fps(), status.backend());
            int buttonsW = 0;
            for (UiKit.FlatButton b : bar) buttonsW += b.getWidth() + 3;
            boolean battery = laptop && status != null && status.batteryPercent() >= 0;
            int textW = Math.min(180, Math.max(font.width(name), (int) UiKit.width(font, info, 0.75f)));
            textW = Math.max(20, Math.min(textW, width - 40 - buttonsW - (battery ? 52 : 0)));
            pillW = 6 + 16 + textW + (battery ? 52 : 0) + 8 + buttonsW + 3;
            pillX = (width - pillW) / 2;
            int y = pillY();
            int alpha = (int) (0xD9 * slide);
            UiKit.shadow(g, pillX, y, pillW, PILL_H);
            UiKit.box(g, pillX, y, pillW, PILL_H, UiKit.withAlpha(0x171B22, alpha), UiKit.withAlpha(UiKit.STROKE, alpha));
            UiKit.icon(g, laptop ? UiKit.Icon.LAPTOP : UiKit.Icon.MONITOR, pillX + 6, y + 5, UiKit.ACCENT_2);
            g.drawString(font, UiKit.clip(font, name, textW), pillX + 22, y + 3, UiKit.TEXT, false);
            UiKit.textFit(g, font, info, pillX + 22, y + 13, textW, UiKit.MUTED, 0.75f);
            int bx = pillX + 22 + textW + 8;
            if (battery) {
                int pct = status.batteryPercent();
                int color = pct > 50 ? UiKit.OK : pct > 15 ? UiKit.WARN : UiKit.ERROR;
                int by = y + 6;
                g.fill(bx, by, bx + 22, by + 10, 0xFF0B0E13);
                g.fill(bx + 1, by + 1, bx + 1 + pct * 20 / 100, by + 9, color);
                g.fill(bx + 22, by + 3, bx + 24, by + 7, 0xFF0B0E13);
                UiKit.text(g, font, pct + "%", bx + 27, by + 2, color, 0.75f);
                bx += 52;
            }
            for (UiKit.FlatButton b : bar) {
                b.setPosition(bx, y + 3);
                b.render(g, mouseX, mouseY, partialTick);
                bx += b.getWidth() + 3;
            }
        } else if (HvxClientConfig.get().showFpsOverlay && status != null) {
            g.drawString(font, String.format("%.0f fps", status.fps()), width - 40, 4, 0x80FFFFFF, false);
        }
        UiKit.renderOverlays(g, font, mouseX, mouseY);
        // one pointer on screen, not two: over a guest that draws its own cursor, hide Minecraft's
        boolean overBar = slide > 0.5f && mouseX >= pillX && mouseX < pillX + pillW && mouseY >= pillY() && mouseY < pillY() + PILL_H;
        setCursorHidden(insideVm(mouseX, mouseY) && !overBar && guestDrawsCursor(status) && minecraft.isWindowActive());
    }

    /** VNC guests (VMware, QEMU) bake their cursor into the picture. window capture and the MicroVM don't */
    private static boolean guestDrawsCursor(S2C.VmStatus status) {
        if (status == null) return false;
        String b = status.backend().toLowerCase(java.util.Locale.ROOT);
        return b.contains("vmware") || b.contains("qemu");
    }

    private void setCursorHidden(boolean hide) {
        if (hide == cursorHidden) return;
        cursorHidden = hide;
        GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, hide ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
    }

    @Override
    public void removed() {
        setCursorHidden(false);
        super.removed();
    }

    // ------------------------------------------------------------------ input

    private boolean insideVm(double mx, double my) {
        return rectX1 > rectX0 && mx >= rectX0 && mx < rectX1 && my >= rectY0 && my < rectY1;
    }

    private int[] toVm(double mx, double my) {
        S2C.VmStatus s = ClientVmCache.get(vm);
        ScreenTexture tex = ScreenTextureManager.get(vm);
        int w = tex != null ? tex.width() : s != null ? s.width() : 1;
        int h = tex != null ? tex.height() : s != null ? s.height() : 1;
        int x = (int) ((mx - rectX0) / (rectX1 - rectX0) * w);
        int y = (int) ((my - rectY0) / (rectY1 - rectY0) * h);
        return new int[]{Mth.clamp(x, 0, w - 1), Mth.clamp(y, 0, h - 1)};
    }

    private void sendPointer(double mx, double my, boolean force) {
        if (!insideVm(mx, my)) return;
        long now = System.nanoTime();
        int[] p = toVm(mx, my);
        if (!force && (now - lastPointer < 8_000_000L || (p[0] == lastVmX && p[1] == lastVmY))) return;
        lastPointer = now;
        lastVmX = p[0];
        lastVmY = p[1];
        Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.POINTER, p[0], p[1], buttons, 0));
    }

    private static int rfbBit(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 4;
            default -> 0;
        };
    }

    @Override
    public void mouseMoved(double mx, double my) {
        mouseY = my;
        sendPointer(mx, my, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (slide > 0.5f && mx >= pillX && mx < pillX + pillW && my >= pillY() && my < pillY() + PILL_H) return super.mouseClicked(mx, my, button);
        if (!insideVm(mx, my)) return super.mouseClicked(mx, my, button);
        buttons |= rfbBit(button);
        sendPointer(mx, my, true);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if ((buttons & rfbBit(button)) == 0) return super.mouseReleased(mx, my, button);
        buttons &= ~rfbBit(button);
        sendPointer(mx, my, true);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        mouseY = my;
        sendPointer(mx, my, false);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!insideVm(mx, my) || scrollY == 0) return false;
        // touchpads spam tiny fractional deltas, so only whole notches go to the guest (5 per event max)
        int steps = wheel.add(scrollY);
        int[] p = toVm(mx, my);
        for (int i = 0; i < Math.min(5, Math.abs(steps)); i++) {
            Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.WHEEL, p[0], p[1], buttons, steps > 0 ? 1 : -1));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (HvxKeys.isRelease(key, scancode)) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UNKNOWN) return true;
        heldKeys.add(key);
        Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.KEY, key, 1, 0, 0));
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scancode, int modifiers) {
        if (heldKeys.remove(key)) Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.KEY, key, 0, 0, 0));
        return true;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.TEXT, c, 0, 0, 0));
        return true;
    }

    @Override
    public void onClose() {
        for (int k : heldKeys) Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.KEY, k, 0, 0, 0));
        heldKeys.clear();
        if (buttons != 0 && lastVmX >= 0) {
            buttons = 0;
            Services.PLATFORM.sendToServer(new C2S.Input(vm, C2S.Input.POINTER, lastVmX, lastVmY, 0, 0));
        }
        super.onClose();
    }
}
