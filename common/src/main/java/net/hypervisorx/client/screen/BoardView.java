package net.hypervisorx.client.screen;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.hardware.HardwareSpec;
import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.item.UsbDriveItem;
import net.hypervisorx.menu.ComputerMenu;
import net.hypervisorx.network.S2C;
import net.hypervisorx.registry.HvxSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The "Motherboard" page of the PC screen. Blocky 3D board, MC style, textures come from
 * tools/gen_board.py (one texel = one board unit). It's a thick slab with the heatsinks, slots and
 * connectors extruded. RAM, GPUs and expansion cards stand up in their slots, the CPU sits under a
 * hinged load plate. Drag to turn it. Click a socket and you get the matching parts from your
 * inventory; installing one plays its own anim + sound for that kind of part. GPU fans spin and the
 * RGB breathes while the machine's running, the Q-LEDs show which required part is missing.
 * <p>
 * Parts move through normal container clicks, so the server checks every change the same way it
 * does for drag-and-drop on the System page. Board units (BU): 180 x 204, z is up.
 */
public final class BoardView {
    public static final int BW = 180;
    public static final int BH = 204;
    private static final float SLAB = 3f;

    private enum Kind { CPU, DIMM, PCIE16, M2, PCIE1, USB, IDE, AGP, PCI }

    /** socket: footprint x0..y1 on the board, ax/ay = where the part's top-left corner goes, slot = menu slot index. */
    private record Socket(int slot, Kind kind, String name, float x0, float y0, float x1, float y1, float ax, float ay) {
        float cx() {
            return (x0 + x1) / 2f;
        }

        float cy() {
            return (y0 + y1) / 2f;
        }
    }

    /** raised bit of the board, its top just shows that patch of the board texture. */
    private record Extrusion(float x0, float y0, float x1, float y1, float h, int side) {
    }

    private static List<Socket> layout(FormFactor form) {
        List<Socket> s = new ArrayList<>();
        if (form == FormFactor.RETRO) {
            s.add(new Socket(0, Kind.CPU, "SOCKET 7", 48, 30, 92, 74, 53, 35));
            for (int i = 0; i < 2; i++) s.add(new Socket(1 + i, Kind.DIMM, "DIMM" + (i + 1), 118 + 8 * i, 17, 123 + 8 * i, 99, 118 + 8 * i, 19));
            s.add(new Socket(3, Kind.AGP, "AGP 2X", 22, 107, 98, 114, 16, 108));
            s.add(new Socket(4, Kind.IDE, "IDE1", 155, 103, 165, 147, 155, 103));
            s.add(new Socket(5, Kind.PCI, "PCI2", 22, 147, 98, 154, 17, 148));
            s.add(new Socket(6, Kind.USB, "USB", 0, 75, 10, 83, -16, 75));
            return s;
        }
        s.add(new Socket(0, Kind.CPU, "CPU_SOCKET (LGA1851)", 56, 26, 100, 70, 61, 31));
        String[] dimm = {"DIMM_A1", "DIMM_A2", "DIMM_B1", "DIMM_B2"};
        for (int i = 0; i < 4; i++) s.add(new Socket(1 + i, Kind.DIMM, dimm[i], 116 + 7 * i, 15, 121 + 7 * i, 101, 116 + 7 * i, 17));
        s.add(new Socket(5, Kind.PCIE16, "PCIEX16_1", 13, 103, 124, 110, 8, 104));
        s.add(new Socket(6, Kind.M2, "M.2_1", 30, 80, 96, 92, 32, 81));
        s.add(new Socket(7, Kind.M2, "M.2_2", 66, 156, 128, 168, 66, 157));
        s.add(new Socket(8, Kind.PCIE1, "PCIEX1_1", 14, 151, 41, 158, 9, 152));
        s.add(new Socket(9, Kind.PCIE1, "PCIEX1_2", 14, 177, 41, 184, 9, 178));
        s.add(new Socket(10, Kind.USB, "USB_1 (rear)", 0, 14, 8, 22, -16, 14));
        s.add(new Socket(11, Kind.USB, "USB_2 (rear)", 0, 24, 8, 32, -16, 24));
        return s;
    }

    private static List<Extrusion> extrusions(boolean retro) {
        List<Extrusion> e = new ArrayList<>();
        if (retro) {
            e.add(new Extrusion(0, 4, 10, 86, 12, 0xFF8E939A));
            e.add(new Extrusion(48, 30, 92, 74, 3, 0xFFCFC7AC));
            e.add(new Extrusion(14, 30, 36, 46, 7, 0xFF8D939B));
            for (int i = 0; i < 2; i++) e.add(new Extrusion(118 + 8 * i, 20, 123 + 8 * i, 96, 3, 0xFF111111));
            e.add(new Extrusion(144, 14, 172, 32, 5, 0xFFD8D4C8));
            e.add(new Extrusion(22, 108, 98, 113, 3, 0xFF5A3519));
            for (int y : new int[]{130, 148, 166}) e.add(new Extrusion(22, y, 98, y + 5, 3, 0xFFD8D6CE));
            e.add(new Extrusion(20, 184, 140, 190, 3, 0xFF151515));
            e.add(new Extrusion(156, 104, 164, 146, 4, 0xFF151515));
            e.add(new Extrusion(167, 104, 175, 146, 4, 0xFF151515));
            e.add(new Extrusion(144, 58, 152, 88, 4, 0xFF151515));
            e.add(new Extrusion(120, 108, 140, 128, 6, 0xFF959BA3));
            e.add(new Extrusion(112, 154, 132, 172, 2, 0xFF141414));
            e.add(new Extrusion(138, 158, 155, 175, 1.5f, 0xFF9AA0A8));
            return e;
        }
        e.add(new Extrusion(0, 2, 8, 80, 12, 0xFF8A8F97));
        e.add(new Extrusion(8, 0, 42, 80, 16, 0xFF24272D));
        e.add(new Extrusion(42, 1, 118, 18, 9, 0xFF2C3037));
        e.add(new Extrusion(122, 2, 140, 11, 4, 0xFF141518));
        e.add(new Extrusion(56, 26, 100, 70, 1, 0xFF2E3238));
        for (int i = 0; i < 4; i++) e.add(new Extrusion(116 + 7 * i, 18, 121 + 7 * i, 98, 3, 0xFF1E2025));
        e.add(new Extrusion(158, 30, 170, 76, 5, 0xFF141518));
        e.add(new Extrusion(13, 103, 121, 110, 3, 0xFF9EA3AA));
        e.add(new Extrusion(14, 152, 40, 157, 3, 0xFF1A1C20));
        e.add(new Extrusion(14, 178, 40, 183, 3, 0xFF1A1C20));
        e.add(new Extrusion(130, 116, 170, 178, 6, 0xFF24272D));
        e.add(new Extrusion(171, 120, 180, 176, 4, 0xFF141518));
        e.add(new Extrusion(76, 178, 93, 195, 1.5f, 0xFF9AA0A8));
        e.add(new Extrusion(14, 186, 30, 198, 1.5f, 0xFFB9BDC3));
        return e;
    }

    // --------------------------------------------------------------------------------------- state

    private final ComputerMenu menu;
    private final Supplier<S2C.HostInfo> info;
    private final boolean retro;
    private final List<Socket> sockets;
    private final List<Extrusion> raised;
    private final ItemStack[] shown;
    private final Anim[] anims;
    private final List<Spark> sparks = new ArrayList<>();
    private final Random random = new Random();
    private boolean initialised;
    private boolean wasComplete;
    private long postStart = -1;
    private boolean postBeeped;
    private float fanAngle;
    private float fanSpeed;
    private long lastFrame = Util.getMillis();

    /** view angles: {@code yaw} spins the board around its normal, {@code pitch} tilts it back. */
    private float yaw = 14f;
    private float pitch = 50f;
    /** scroll-wheel zoom, on top of the fitted size. */
    private float zoom = 1.12f;
    private boolean dragging;
    private double dragX, dragY;
    private Matrix4f view = new Matrix4f();
    private Font font;
    private int panelX, panelY, panelW, panelH;
    private int sheetX, sheetY, sheetW, sheetH;
    private Socket hovered;

    /** the part list ("picker") for one socket. */
    private Socket picker;
    private long pickerOpened;
    private int pickerScroll;
    private final List<Entry> entries = new ArrayList<>();
    private int pkX, pkY, pkW, pkH;

    private record Entry(ItemStack stack, int menuSlot, int count) {
    }

    private static final class Anim {
        final boolean install;
        final ItemStack stack;
        final long start = Util.getMillis();
        final int duration;
        int fired;

        Anim(boolean install, ItemStack stack, int duration) {
            this.install = install;
            this.stack = stack;
            this.duration = duration;
        }

        float progress() {
            return Mth.clamp((Util.getMillis() - start) / (float) duration, 0f, 1f);
        }
    }

    private static final class Spark {
        float x, y, z, vx, vy, vz;
        final long born = Util.getMillis();
        final int color;

        Spark(float x, float y, float z, float vx, float vy, float vz, int color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.color = color;
        }
    }

    public BoardView(ComputerMenu menu, Supplier<S2C.HostInfo> info, Runnable openSystem) {
        this.menu = menu;
        this.info = info;
        this.retro = menu.form() == FormFactor.RETRO;
        this.sockets = layout(menu.form());
        this.raised = extrusions(retro);
        this.shown = new ItemStack[menu.form().slotCount()];
        this.anims = new Anim[menu.form().slotCount()];
        java.util.Arrays.fill(shown, ItemStack.EMPTY);
    }

    // --------------------------------------------------------------------------------------- drawing primitives

    private static final ResourceLocation WHITE = HyperVisorX.id("textures/screen/white.png");

    private static ResourceLocation tex(String name) {
        return HyperVisorX.id("textures/gui/board/" + name + ".png");
    }

    /** texture region for one face. null skin = plain colour. */
    private record Skin(ResourceLocation tex, float u0, float v0, float u1, float v1) {
        static Skin of(String name) {
            return new Skin(BoardView.tex(name), 0, 0, 1, 1);
        }
    }

    private static int argb(float a, int rgb) {
        return Mth.clamp((int) (a * 255), 0, 255) << 24 | (rgb & 0xFFFFFF);
    }

    /** MC-style face shading for a colour (1 = lit top). */
    private static int shadeColor(int argb, float f) {
        int r = (int) (((argb >> 16) & 255) * f), g = (int) (((argb >> 8) & 255) * f), b = (int) ((argb & 255) * f);
        return argb & 0xFF000000 | Mth.clamp(r, 0, 255) << 16 | Mth.clamp(g, 0, 255) << 8 | Mth.clamp(b, 0, 255);
    }

    private static void quad(Matrix4f m, ResourceLocation tex, float[] a, float[] b, float[] c, float[] d,
                             float u0, float v0, float u1, float v1, int color) {
        if ((color >>> 24) == 0) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, a[0], a[1], a[2]).setUv(u0, v0).setColor(color);
        bb.addVertex(m, b[0], b[1], b[2]).setUv(u0, v1).setColor(color);
        bb.addVertex(m, c[0], c[1], c[2]).setUv(u1, v1).setColor(color);
        bb.addVertex(m, d[0], d[1], d[2]).setUv(u1, v0).setColor(color);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    private static void face(Matrix4f m, Skin skin, float[] a, float[] b, float[] c, float[] d, int color, float shade, float alpha) {
        int col = argb(alpha * ((color >>> 24) / 255f), shadeColor(skin == null ? color : 0xFFFFFFFF, shade));
        if (skin == null) quad(m, WHITE, a, b, c, d, 0, 0, 1, 1, col);
        else quad(m, skin.tex, a, b, c, d, skin.u0, skin.v0, skin.u1, skin.v1, col);
    }

    /**
     * box in board space. faces: top (+z, u along x, v along y), front (+y, facing the viewer,
     * u along x, v top to bottom), back (-y), and the two ends/sides (u along y).
     */
    private static void box(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                            Skin top, Skin front, Skin back, Skin sides, int color, float alpha) {
        face(m, top, new float[]{x0, y0, z1}, new float[]{x0, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y0, z1}, color, 1f, alpha);
        face(m, front, new float[]{x0, y1, z1}, new float[]{x0, y1, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, color, 0.8f, alpha);
        face(m, back, new float[]{x1, y0, z1}, new float[]{x1, y0, z0}, new float[]{x0, y0, z0}, new float[]{x0, y0, z1}, color, 0.8f, alpha);
        face(m, sides, new float[]{x1, y0, z1}, new float[]{x1, y0, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, color, 0.64f, alpha);
        face(m, sides, new float[]{x0, y1, z1}, new float[]{x0, y1, z0}, new float[]{x0, y0, z0}, new float[]{x0, y0, z1}, color, 0.64f, alpha);
    }

    private static void plain(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int color, float alpha) {
        box(m, x0, y0, z0, x1, y1, z1, null, null, null, null, color, alpha);
    }

    /** additive glow on a flat rect (RGB zones, LEDs, highlights). */
    private static void glowTop(Matrix4f m, ResourceLocation tex, float x0, float y0, float x1, float y1, float z,
                                float u0, float v0, float u1, float v1, int argb) {
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        quad(m, tex, new float[]{x0, y0, z}, new float[]{x0, y1, z}, new float[]{x1, y1, z}, new float[]{x1, y0, z}, u0, v0, u1, v1, argb);
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
    }

    // --------------------------------------------------------------------------------------- parts

    private static HardwareSpec spec(ItemStack s) {
        return s.getItem() instanceof HardwareItem h ? h.spec() : null;
    }

    private static int tier(ItemStack s) {
        HardwareSpec sp = spec(s);
        return sp == null ? 1 : Mth.clamp(sp.tier(), 1, 4);
    }

    private static boolean isHdd(ItemStack s) {
        HardwareSpec sp = spec(s);
        return sp != null && !sp.nvme() && !BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains("ssd");
    }

    private static int ramTier(ItemStack s) {
        HardwareSpec sp = spec(s);
        if (sp == null) return 1;
        int gb = sp.ramMb() / 1024;
        return gb <= 2 ? 1 : gb <= 4 ? 2 : gb <= 16 ? 3 : 4;
    }

    private static final int[][] FANS = {{46}, {40, 89}, {32, 65, 98}, {32, 65, 98}};

    /** the part's box when it's seated: x0, y0, z0, x1, y1, z1. */
    private float[] partBox(Socket k, ItemStack s) {
        return switch (k.kind) {
            case CPU -> new float[]{k.ax, k.ay, 0.5f, k.ax + 34, k.ay + 34, 2.5f};
            case DIMM -> new float[]{k.ax + 1.5f, k.ay, 1.5f, k.ax + 3.5f, k.ay + (retro ? 78 : 82), retro ? 17.5f : 19.5f};
            case PCIE16 -> tier(s) == 1 ? new float[]{k.ax, k.ay + 1, 1, k.ax + 86, k.ay + 11, 35}
                    : new float[]{k.ax, k.ay + 1, 1, k.ax + 124, k.ay + 15, 41};
            case AGP -> new float[]{k.ax, k.ay + 1, 1, k.ax + 84, k.ay + 9, 31};
            case PCIE1, PCI -> new float[]{k.ax, k.ay + 1, 1, k.ax + 52, k.ay + 3, 17};
            case M2 -> isHdd(s) ? new float[]{k.ax, k.ay - 2, 0.3f, k.ax + 62, k.ay + 12, 5}
                    : new float[]{k.ax, k.ay, 0.3f, k.ax + 62, k.ay + 10, spec(s) != null && spec(s).nvme() ? 3 : 1.5f};
            case IDE -> new float[]{k.ax, k.ay, 0, k.ax + 10, k.ay + 44, 7};
            case USB -> new float[]{k.ax, k.ay + 1, 3, k.ax + 17, k.ay + 7, 7};
        };
    }

    /** draws a seated part in the current pose. */
    private void drawPart(PoseStack pose, Socket k, ItemStack s, float alpha, float hue, boolean on, long now) {
        Matrix4f m = pose.last().pose();
        float[] b = partBox(k, s);
        switch (k.kind) {
            case CPU -> {
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], Skin.of(retro ? "cpu_retro" : "cpu_" + tier(s)), null, null, null, 0xFF23784A, alpha);
                if (!retro && alpha > 0.5f) {
                    String label = new String[]{"D2", "Q4", "X8", "16"}[tier(s) - 1];
                    int color = new int[]{0x6A7079, 0x2BA9D6, 0x6A4DE0, 0xE0405C}[tier(s) - 1];
                    pose.pushPose();
                    pose.translate(k.ax + 17, k.ay + 15, b[5] + 0.06f);
                    g().drawString(font, label, -font.width(label) / 2, -4, 0xFF000000 | color, false);
                    g().flush();
                    pose.popPose();
                }
            }
            case DIMM -> {
                String side = retro ? "ram_side_retro" : "ram_side_" + ramTier(s);
                int t = retro ? 1 : ramTier(s);
                int top = t == 4 ? 0xFFE9EDF3 : t == 1 ? 0xFF23784A : 0xFF2A2D33;
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], null, null, null, Skin.of(side), top, alpha);
                if (!retro && t >= 3) {
                    int rgb = Mth.hsvToRgb((hue + k.slot * 0.08f) % 1f, 0.8f, 1f);
                    float a = alpha * (on ? 0.9f : 0.25f);
                    glowTop(m, WHITE, b[0], b[1], b[3], b[4], b[5] + 0.03f, 0, 0, 1, 1, argb(a, rgb));
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                    RenderSystem.depthMask(false);
                    float z0 = b[5] - 3, z1 = b[5];
                    quad(m, tex("ram_side_rgb"), new float[]{b[3] + 0.03f, b[1], z1}, new float[]{b[3] + 0.03f, b[1], z0},
                            new float[]{b[3] + 0.03f, b[4], z0}, new float[]{b[3] + 0.03f, b[4], z1}, 0, 0, 1, 3 / 18f, argb(a, rgb));
                    RenderSystem.depthMask(true);
                    RenderSystem.defaultBlendFunc();
                }
            }
            case PCIE16 -> {
                int t = tier(s);
                int body = new int[]{0xFF3A3E46, 0xFF30343B, 0xFF262A30, 0xFF1D2025}[t - 1];
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], Skin.of("gpu_top_" + t), Skin.of("gpu_" + t), null, null, body, alpha);
                // fans on the front face, 4 anim frames
                int frame = (int) (fanAngle / 10f) & 3;
                float h = b[5] - b[2];
                float cz = b[5] - ((h - 4) / 2f);
                int[] fans = FANS[t - 1];
                for (int i = 0; i < fans.length; i++) {
                    float cx = k.ax + fans[i];
                    float r = t == 1 ? 13f : 14f;
                    float fy = b[4] + 0.05f;
                    quad(m, tex("fan_" + (i % 2 == 0 ? frame : 3 - frame)), new float[]{cx - r, fy, cz + r}, new float[]{cx - r, fy, cz - r},
                            new float[]{cx + r, fy, cz - r}, new float[]{cx + r, fy, cz + r}, 0, 0, 1, 1, argb(alpha, 0xFFFFFF));
                }
            }
            case AGP -> box(m, b[0], b[1], b[2], b[3], b[4], b[5], null, Skin.of("gpu_retro"), null, null, 0xFF23784A, alpha);
            case PCIE1, PCI -> {
                String card = retro ? "card_retro" : spec(s) != null && spec(s).feature().equals("gpio") ? "card_gpio" : "card_wifi";
                int pcb = card.equals("card_gpio") ? 0xFF6A2125 : card.equals("card_retro") ? 0xFF23784A : 0xFF1F2227;
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], null, Skin.of(card), null, null, pcb, alpha);
            }
            case M2 -> {
                String name = spec(s) != null && spec(s).nvme() ? "m2_nvme" : isHdd(s) ? "hdd" : "m2_sata";
                int side = name.equals("hdd") ? 0xFF9AA0A8 : name.equals("m2_nvme") ? 0xFF2A2E35 : 0xFF23784A;
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], Skin.of(name), null, null, null, side, alpha);
            }
            case IDE -> {
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], new Skin(tex("ide"), 0, 0, 10 / 30f, 1), null, null, null, 0xFF2D2E32, alpha);
                // grey ribbon cable runs off the right edge of the board
                Skin ribbon = new Skin(tex("ide"), 10 / 30f, 0, 1, 1);
                face(m, ribbon, new float[]{b[3], b[1], 6.5f}, new float[]{b[3], b[4], 6.5f}, new float[]{b[3] + 22, b[4], 5f},
                        new float[]{b[3] + 22, b[1], 5f}, 0xFFFFFFFF, 1f, alpha);
            }
            case USB -> {
                box(m, b[0], b[1], b[2], b[3], b[4], b[5], new Skin(tex("usb"), 0, 0, 17 / 24f, 1), null, null, null, 0xFF2F63E0, alpha);
                boolean blink = on && (now / 120) % 7 < 2;
                if (blink) glowTop(m, WHITE, b[0] + 1, b[1] + 2, b[0] + 2, b[1] + 3, b[5] + 0.04f, 0, 0, 1, 1, argb(alpha, 0x9FF0B0));
            }
        }
    }

    /** CPU load plate (hinged on the socket's top edge) + the retention lever. */
    private void cpuHardware(PoseStack pose, Socket k, float plateOpen, float leverOpen) {
        if (!retro) {
            pose.pushPose();
            pose.translate(0, k.y0, 3f);
            pose.mulPose(Axis.XP.rotationDegrees(115f * plateOpen));
            pose.translate(0, -k.y0, -3f);
            Matrix4f m = pose.last().pose();
            Skin plate = Skin.of("plate");
            // draw both sides of the thin steel frame
            face(m, plate, new float[]{k.x0, k.y0, 3f}, new float[]{k.x0, k.y1, 3f}, new float[]{k.x1, k.y1, 3f}, new float[]{k.x1, k.y0, 3f},
                    0xFFFFFFFF, 1f, 1f);
            face(m, plate, new float[]{k.x1, k.y0, 2.9f}, new float[]{k.x1, k.y1, 2.9f}, new float[]{k.x0, k.y1, 2.9f}, new float[]{k.x0, k.y0, 2.9f},
                    0xFFFFFFFF, 0.6f, 1f);
            pose.popPose();
        }
        float lx = k.x1 + 1;
        float py = k.y1 - 1;
        pose.pushPose();
        pose.translate(0, py, 1.5f);
        pose.mulPose(Axis.XP.rotationDegrees(-(retro ? 85f : 65f) * leverOpen));
        pose.translate(0, -py, -1.5f);
        Matrix4f m = pose.last().pose();
        plain(m, lx, k.y0 + 2, 0.5f, lx + 2, py, 2.5f, 0xFFD4D8DE, 1f);
        plain(m, lx - 0.5f, k.y0 + 1, 0.5f, lx + 2.5f, k.y0 + 4, 3f, 0xFFA4AAB2, 1f);
        pose.popPose();
    }

    // --------------------------------------------------------------------------------------- tracking

    private ItemStack item(Socket k) {
        return menu.slots.get(k.slot).getItem();
    }

    private boolean has(ComponentType type) {
        for (Socket k : sockets) if (menu.form().slots().get(k.slot) == type && !item(k).isEmpty()) return true;
        return false;
    }

    /** CPU + RAM + a drive = enough to boot. */
    private boolean complete() {
        return has(ComponentType.CPU) && has(ComponentType.RAM) && has(ComponentType.STORAGE);
    }

    private boolean running() {
        S2C.HostInfo i = info.get();
        if (i == null) return false;
        for (S2C.VmSlotInfo v : i.slots()) if (v.state().equals("RUNNING") || v.state().equals("STARTING")) return true;
        return false;
    }

    private int duration(Socket k) {
        return switch (k.kind) {
            case CPU -> 1000;
            case DIMM -> 600;
            case PCIE16, AGP -> 760;
            case M2 -> 1000;
            case PCIE1, PCI, IDE -> 560;
            case USB -> 480;
        };
    }

    /** install sounds as (anim fraction, sound). the first one also throws sparks. */
    private Object[][] schedule(Socket k) {
        return switch (k.kind) {
            case CPU -> retro ? new Object[][]{{0.42f, HvxSounds.ASSEMBLY_CPU_DROP}, {0.88f, HvxSounds.ASSEMBLY_CARD_SEAT}}
                    : new Object[][]{{0.42f, HvxSounds.ASSEMBLY_CPU_DROP}, {0.88f, HvxSounds.ASSEMBLY_CPU_LEVER}};
            case DIMM -> new Object[][]{{0.62f, HvxSounds.ASSEMBLY_RAM_CLICK}};
            case PCIE16, AGP -> new Object[][]{{0.64f, HvxSounds.ASSEMBLY_GPU_SEAT}};
            case M2 -> new Object[][]{{0.46f, HvxSounds.ASSEMBLY_CARD_SEAT}, {0.64f, HvxSounds.ASSEMBLY_SCREW}};
            case PCIE1, PCI, IDE -> new Object[][]{{0.62f, HvxSounds.ASSEMBLY_CARD_SEAT}};
            case USB -> new Object[][]{{0.5f, HvxSounds.ASSEMBLY_USB_PLUG}};
        };
    }

    private static void play(Supplier<SoundEvent> sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.get(), pitch, 0.9f));
    }

    /** kicks off anims for parts that showed up or vanished since last frame, and fires their sounds. */
    private void track() {
        boolean first = !initialised;
        for (Socket k : sockets) {
            ItemStack now = item(k);
            ItemStack was = shown[k.slot];
            if (ItemStack.isSameItemSameComponents(now, was) && now.isEmpty() == was.isEmpty()) continue;
            if (!first) anims[k.slot] = !now.isEmpty() ? new Anim(true, now.copy(), duration(k)) : new Anim(false, was.copy(), 360);
            shown[k.slot] = now.copy();
        }
        initialised = true;
        boolean done = complete();
        if (!first && done && !wasComplete) {
            postStart = Util.getMillis();
            postBeeped = false;
        }
        wasComplete = done;
        for (Socket k : sockets) {
            Anim a = anims[k.slot];
            if (a == null) continue;
            float p = a.progress();
            if (!a.install) {
                if (a.fired == 0) {
                    play(HvxSounds.ASSEMBLY_PART_REMOVE, 0.95f + random.nextFloat() * 0.1f);
                    a.fired = 1;
                }
            } else {
                Object[][] sched = schedule(k);
                for (int i = 0; i < sched.length; i++) {
                    if ((a.fired & 1 << i) != 0 || p < (float) sched[i][0]) continue;
                    a.fired |= 1 << i;
                    @SuppressWarnings("unchecked")
                    Supplier<SoundEvent> snd = (Supplier<SoundEvent>) sched[i][1];
                    play(snd, 0.96f + random.nextFloat() * 0.08f);
                    if (i == 0) burst(k, a.stack);
                }
            }
            if (p >= 1f) anims[k.slot] = null;
        }
        if (postStart >= 0 && !postBeeped && Util.getMillis() - postStart > 1250) {
            postBeeped = true;
            play(HvxSounds.ASSEMBLY_POST_BEEP, 1f);
        }
    }

    private void burst(Socket k, ItemStack s) {
        float[] b = partBox(k, s);
        int color = menu.form().slots().get(k.slot).uiColor();
        for (int i = 0; i < 16; i++) {
            float x = Mth.lerp(random.nextFloat(), b[0], b[3]);
            float y = Mth.lerp(random.nextFloat(), b[1], b[4]);
            double a = random.nextDouble() * Math.PI * 2;
            float v = 12 + random.nextFloat() * 22;
            sparks.add(new Spark(x, y, b[2] + 1, (float) Math.cos(a) * v, (float) Math.sin(a) * v, 30 + random.nextFloat() * 30,
                    i % 3 == 0 ? 0xFFFFFF : color));
        }
    }

    // --------------------------------------------------------------------------------------- render

    private GuiGraphics graphics;

    private GuiGraphics g() {
        return graphics;
    }

    public void render(GuiGraphics g, Font font, int x, int y, int w, int h, int sx, int sw, int mx, int my) {
        this.graphics = g;
        this.font = font;
        panelX = x;
        panelY = y;
        panelW = w;
        panelH = h;
        sheetX = sx;
        sheetY = y;
        sheetW = sw;
        sheetH = h;
        track();
        long now = Util.getMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean on = running();
        fanSpeed = Mth.lerp(Math.min(1f, dt * 1.5f), fanSpeed, on ? 520f : 0f);
        fanAngle = (fanAngle + fanSpeed * dt) % 360f;
        float hue = (now % 6000L) / 6000f;

        g.flush();
        UiKit.box(g, x, y, w, h, 0xFF0B0D11, UiKit.STROKE_SOFT);
        // fit the rotated board (tallest parts included) into the panel
        Matrix4f rot = new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(yaw)).translate(-BW / 2f, -BH / 2f, 0);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] c : new float[][]{{-17, 0, 0}, {BW + 22, 0, 0}, {-17, BH, 0}, {BW + 22, BH, 0}, {-17, 0, 20}, {BW, 0, 20},
                {0, BH, 0}, {120, 110, 44}, {10, 110, 44}, {BW, BH, 0}}) {
            Vector3f v = rot.transformPosition(c[0], c[1], c[2], new Vector3f());
            minX = Math.min(minX, v.x);
            maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
        }
        float scale = Math.min((w - 8) / (maxX - minX), (h - 8) / (maxY - minY)) * zoom;
        float cx = x + w / 2f - (minX + maxX) / 2f * scale;
        float cy = y + h / 2f - (minY + maxY) / 2f * scale;

        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 160);
        pose.scale(scale, scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(yaw));
        pose.translate(-BW / 2f, -BH / 2f, 0);
        view = new Matrix4f(pose.last().pose());
        hovered = picker == null && !dragging ? socketAt(mx, my) : null;

        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f m = pose.last().pose();
        String boardTex = retro ? "retro" : "tower";
        // the board itself: a slab with the pixel-art texture on top, plus the raised bits
        box(m, 0, 0, -SLAB, BW, BH, 0, Skin.of(boardTex), null, null, null, retro ? 0xFF1F5530 : 0xFF17191D, 1f);
        for (Extrusion e : raised) {
            Skin top = new Skin(tex(boardTex), e.x0 / BW, e.y0 / BH, e.x1 / BW, e.y1 / BH);
            box(m, e.x0, e.y0, 0, e.x1, e.y1, e.h, top, null, null, null, e.side, 1f);
        }
        // RGB zones, on the board and on the raised parts that carry them
        float rgb = on ? 0.95f : complete() ? 0.35f : 0.12f;
        if (postStart >= 0) {
            float t = (now - postStart) / 1000f;
            if (t < 2.2f) rgb = Math.max(rgb, t < 1.25f ? 0.2f : Mth.clamp(1f - (t - 1.25f) / 0.95f, 0f, 1f));
        }
        int rgbColor = argb(rgb, Mth.hsvToRgb(hue, 0.75f, 1f));
        ResourceLocation mask = tex(retro ? "retro_rgb" : "tower_rgb");
        glowTop(m, mask, 0, 0, BW, BH, 0.02f, 0, 0, 1, 1, rgbColor);
        for (Extrusion e : raised) {
            if (e.h >= 5) glowTop(m, mask, e.x0, e.y0, e.x1, e.y1, e.h + 0.02f, e.x0 / BW, e.y0 / BH, e.x1 / BW, e.y1 / BH, rgbColor);
        }
        if (!retro) boardLabels(pose, on, hue);
        for (Socket k : sockets) drawSocket(pose, k, now, hue, on);
        if (!retro) qLeds(m, now);
        sparks(m, dt, now);
        if (hovered != null) highlight(m, hovered, 0.5f + 0.5f * (float) Math.sin(now / 150.0), item(hovered).isEmpty() ? UiKit.ACCENT_2 : UiKit.ACCENT);
        if (picker != null) highlight(m, picker, 1f, UiKit.ACCENT_2);
        RenderSystem.enableCull();
        pose.popPose();
        g.disableScissor();
        sheet(g, font, mx, my);
        if (hovered != null) tip(hovered);
    }

    /** model name in the MC font on the chipset and the I/O shroud. */
    private void boardLabels(PoseStack pose, boolean on, float hue) {
        int color = on ? 0xFF000000 | Mth.hsvToRgb(hue, 0.45f, 1f) : 0xFFD5DAE2;
        pose.pushPose();
        pose.translate(150, 148, 6.05f);
        g().drawString(font, "ORBIT", -font.width("ORBIT") / 2, -4, color, false);
        pose.popPose();
        pose.pushPose();
        pose.translate(24.5f, 62, 16.05f);
        pose.mulPose(Axis.ZP.rotationDegrees(-90));
        g().drawString(font, "ORBIT", 0, -4, color, false);
        pose.popPose();
        g().flush();
    }

    /** empty socket hardware or the installed part, with anims. */
    private void drawSocket(PoseStack pose, Socket k, long now, float hue, boolean on) {
        Anim a = anims[k.slot];
        ItemStack s = a != null ? a.stack : item(k);
        float p = a == null ? 1f : a.progress();
        boolean present = a != null || !s.isEmpty();
        if (k.kind == Kind.CPU) {
            float plateOpen;
            float leverOpen;
            if (!present) {
                plateOpen = 1f;
                leverOpen = 1f;
            } else if (a == null) {
                plateOpen = 0f;
                leverOpen = 0f;
            } else if (a.install) {
                plateOpen = 1f - ease(Mth.clamp((p - 0.5f) / 0.3f, 0f, 1f));
                leverOpen = 1f - ease(Mth.clamp((p - 0.76f) / 0.14f, 0f, 1f));
            } else {
                plateOpen = 1f;
                leverOpen = 1f;
            }
            cpuHardware(pose, k, plateOpen, leverOpen);
        }
        if (!present) return;
        pose.pushPose();
        float alpha = 1f;
        if (a != null && !a.install) {
            float e = ease(p);
            pose.translate(0, 0, 18 * e);
            alpha = 1 - e;
        } else if (a != null) {
            alpha = installTransform(pose, k, s, p);
        }
        drawPart(pose, k, s, alpha, hue, on, now);
        pose.popPose();
        Matrix4f m = pose.last().pose();
        if (k.kind == Kind.DIMM) latches(pose, k, a == null ? (s.isEmpty() ? 1f : 0f) : a.install ? (p >= 0.62f ? 0f : 1f) : 1f, a, p);
        if (k.kind == Kind.M2 && (a == null || a.install && p >= 0.64f)) {
            float[] b = partBox(k, s);
            float turn = a == null ? 0f : (p - 0.64f) / 0.36f * 720f;
            pose.pushPose();
            pose.translate(b[3] - 2.5f, (b[1] + b[4]) / 2f, b[5]);
            pose.mulPose(Axis.ZP.rotationDegrees(turn));
            box(pose.last().pose(), -1.5f, -1.5f, 0, 1.5f, 1.5f, 0.8f, Skin.of("screw"), null, null, null, 0xFF8F959D, 1f);
            pose.popPose();
        }
    }

    /** DIMM latches on both ends. tilted out when open, they snap upright when the stick seats. */
    private void latches(PoseStack pose, Socket k, float open, Anim a, float p) {
        float len = retro ? 78 : 82;
        float pop = a != null && a.install && p >= 0.62f ? 1f + 0.25f * (1f - Mth.clamp((p - 0.62f) / 0.12f, 0f, 1f)) : 1f;
        for (int end = 0; end < 2; end++) {
            float y = end == 0 ? k.ay - 2.5f : k.ay + len + 0.5f;
            float hinge = end == 0 ? y + 2 : y;
            pose.pushPose();
            pose.translate(0, hinge, 0);
            pose.mulPose(Axis.XP.rotationDegrees((end == 0 ? -1 : 1) * 32f * open));
            pose.translate(k.ax + 2.5f, -hinge, 0);
            pose.scale(pop, pop, pop);
            pose.translate(-(k.ax + 2.5f), 0, 0);
            box(pose.last().pose(), k.ax, y, 0, k.ax + 5, y + 2, 5.5f, Skin.of("latch"), null, null, null, 0xFFCDD1D7, 1f);
            pose.popPose();
        }
    }

    /** moves a part that's being installed into place, returns its alpha. */
    private float installTransform(PoseStack pose, Socket k, ItemStack s, float p) {
        switch (k.kind) {
            case CPU -> {
                float a = Mth.clamp(p / 0.42f, 0f, 1f);
                pose.translate(0, 0, 30 * (1 - ease(a)));
                return Mth.clamp(a * 3f, 0f, 1f);
            }
            case DIMM -> {
                float a = Mth.clamp(p / 0.62f, 0f, 1f);
                pose.translate(0, 0, 36 * (1 - back(a)));
                return Mth.clamp(a * 4f, 0f, 1f);
            }
            case PCIE16, AGP, PCIE1, PCI, IDE -> {
                float a = Mth.clamp(p / 0.64f, 0f, 1f);
                float e = ease(a);
                float[] b = partBox(k, s);
                pose.translate(0, 0, (k.kind == Kind.PCIE16 || k.kind == Kind.AGP ? 34 : 24) * (1 - e));
                // bracket end goes in last, so a small tilt around the far end
                pose.translate(b[3], 0, b[2]);
                pose.mulPose(Axis.YP.rotationDegrees(-6f * (1 - e)));
                pose.translate(-b[3], 0, -b[2]);
                return Mth.clamp(a * 3f, 0f, 1f);
            }
            case M2 -> {
                // slides into the key at an angle, gets pressed flat, then screwed down
                float a1 = ease(Mth.clamp(p / 0.46f, 0f, 1f));
                float a2 = ease(Mth.clamp((p - 0.46f) / 0.16f, 0f, 1f));
                pose.translate(22 * (1 - a1), 0, 0);
                pose.translate(k.ax, 0, 0.3f);
                pose.mulPose(Axis.YP.rotationDegrees(-16f * (1 - a2)));
                pose.translate(-k.ax, 0, -0.3f);
                return Mth.clamp(p * 6f, 0f, 1f);
            }
            case USB -> {
                pose.translate(-16 * (1 - ease(Mth.clamp(p / 0.5f, 0f, 1f))), 0, 0);
                return Mth.clamp(p * 5f, 0f, 1f);
            }
        }
        return 1f;
    }

    private void qLeds(Matrix4f m, long now) {
        boolean[] missing = {!has(ComponentType.CPU), !has(ComponentType.RAM), !has(ComponentType.GPU), !has(ComponentType.STORAGE)};
        int[] colors = {0xFF3B3B, 0xFFB020, 0xFFFFFF, 0x3DDC84};
        float post = postStart >= 0 ? (now - postStart) / 1000f : 99f;
        for (int i = 0; i < 4; i++) {
            float lit;
            if (post < 1.25f) lit = post >= i * 0.3f && post < i * 0.3f + 0.34f ? 1f : 0f;
            else lit = missing[i] ? 0.55f + 0.45f * (float) Math.sin(now / 260.0) : 0f;
            if (lit <= 0.01f) continue;
            float y = 82 + i * 5;
            plain(m, 150, y, 0, 153, y + 2, 0.8f, 0xFF000000 | colors[i], lit);
            glowTop(m, WHITE, 148.5f, y - 1.5f, 154.5f, y + 3.5f, 0.85f, 0, 0, 1, 1, argb(lit * 0.35f, colors[i]));
        }
    }

    private void sparks(Matrix4f m, float dt, long now) {
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            float age = (now - s.born) / 1000f;
            if (age > 0.7f) {
                sparks.remove(i);
                continue;
            }
            s.vz -= 120 * dt;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.z = Math.max(0.3f, s.z + s.vz * dt);
            plain(m, s.x - 0.5f, s.y - 0.5f, s.z, s.x + 0.5f, s.y + 0.5f, s.z + 1, 0xFF000000 | s.color, 1 - age / 0.7f);
        }
    }

    /** glowy frame around a socket or the part in it. */
    private void highlight(Matrix4f m, Socket k, float a, int color) {
        float[] b = pickBox(k);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        box(m, b[0] - 0.6f, b[1] - 0.6f, b[2], b[3] + 0.6f, b[4] + 0.6f, b[5] + 0.6f, null, null, null, null, 0xFF000000 | color, a * 0.32f);
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
    }

    private void tip(Socket k) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(k.name).withStyle(s -> s.withColor(UiKit.TEXT & 0xFFFFFF)));
        lines.add(Component.translatable("hypervisorx.board.kind." + k.kind.name().toLowerCase(java.util.Locale.ROOT)));
        ItemStack s = item(k);
        if (!s.isEmpty()) {
            lines.add(s.getHoverName());
            lines.add(Component.translatable("hypervisorx.board.click_manage").withStyle(st -> st.withColor(UiKit.ACCENT_2 & 0xFFFFFF)));
        } else {
            lines.add(Component.translatable("hypervisorx.board.click_install").withStyle(st -> st.withColor(UiKit.ACCENT_2 & 0xFFFFFF)));
        }
        UiKit.queueTip(lines);
    }

    private static float ease(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static float back(float t) {
        float c1 = 1.4f;
        float c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    // --------------------------------------------------------------------------------------- picking

    /** what the mouse can hit: the installed part, or the footprint if the socket's empty. */
    private float[] pickBox(Socket k) {
        ItemStack s = item(k);
        if (!s.isEmpty()) return partBox(k, s);
        return new float[]{k.x0, k.y0, 0, k.x1, k.y1, k.kind == Kind.CPU ? 3 : 4};
    }

    /** socket under the mouse. projects every face of every pick box, nearest hit wins. */
    private Socket socketAt(double mx, double my) {
        if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + panelH) return null;
        Socket best = null;
        float bestZ = -Float.MAX_VALUE;
        for (Socket k : sockets) {
            float[] b = pickBox(k);
            float[][] c = new float[8][];
            for (int i = 0; i < 8; i++) {
                Vector3f v = view.transformPosition((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5], new Vector3f());
                c[i] = new float[]{v.x, v.y, v.z};
            }
            int[][] faces = {{4, 5, 7, 6}, {2, 3, 7, 6}, {0, 1, 5, 4}, {1, 3, 7, 5}, {0, 2, 6, 4}};
            for (int[] f : faces) {
                if (!inside(mx, my, c[f[0]], c[f[1]], c[f[2]], c[f[3]])) continue;
                float z = (c[f[0]][2] + c[f[1]][2] + c[f[2]][2] + c[f[3]][2]) / 4f;
                if (z > bestZ) {
                    bestZ = z;
                    best = k;
                }
            }
        }
        return best;
    }

    private static boolean inside(double x, double y, float[]... q) {
        boolean pos = false, neg = false;
        for (int i = 0; i < q.length; i++) {
            float[] a = q[i], b = q[(i + 1) % q.length];
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (cross > 0) pos = true;
            if (cross < 0) neg = true;
            if (pos && neg) return false;
        }
        return true;
    }

    // --------------------------------------------------------------------------------------- build sheet

    private static final ComponentType[] GROUPS = {ComponentType.CPU, ComponentType.RAM, ComponentType.GPU, ComponentType.STORAGE,
            ComponentType.EXPANSION, ComponentType.USB};
    private static final UiKit.Icon[] GROUP_ICONS = {UiKit.Icon.CHIP, UiKit.Icon.MEMORY, UiKit.Icon.GPU, UiKit.Icon.DRIVE, UiKit.Icon.PCIE, UiKit.Icon.USB};
    private static final int ROW = 22;

    private int rowsTop() {
        return sheetY + 30;
    }

    private void sheet(GuiGraphics g, Font font, int mx, int my) {
        int x = sheetX, y = sheetY, w = sheetW;
        UiKit.section(g, font, x, y, w, sheetH, Component.translatable("hypervisorx.board.build"));
        int total = 0, filled = 0;
        for (Socket k : sockets) {
            total++;
            if (!item(k).isEmpty()) filled++;
        }
        String count = filled + "/" + total;
        UiKit.text(g, font, count, x + w - 6 - UiKit.width(font, count, 0.75f), y + 5, UiKit.ACCENT_2, 0.75f);
        UiKit.progress(g, x + 6, y + 15, w - 12, 3, total == 0 ? 0 : filled / (double) total, complete() ? UiKit.OK : UiKit.ACCENT);
        int ry = rowsTop();
        for (int i = 0; i < GROUPS.length; i++) {
            ComponentType type = GROUPS[i];
            List<Socket> group = group(type);
            if (group.isEmpty()) continue;
            boolean hover = picker == null && mx >= x + 3 && mx < x + w - 3 && my >= ry && my < ry + ROW - 2;
            UiKit.card(g, x + 3, ry, w - 6, ROW - 2, hover);
            UiKit.icon(g, GROUP_ICONS[i], x + 6, ry + 4, type.uiColor());
            int got = 0;
            for (Socket k : group) if (!item(k).isEmpty()) got++;
            boolean required = type == ComponentType.CPU || type == ComponentType.RAM || type == ComponentType.STORAGE;
            int dot = got > 0 ? UiKit.OK : required ? UiKit.ERROR : type == ComponentType.GPU ? UiKit.WARN : UiKit.DIM;
            UiKit.dot(g, x + w - 11, ry + 8, dot);
            int tx = x + 21;
            int tw = w - 21 - 14;
            UiKit.textFit(g, font, Component.translatable(type.translationKey()).getString(), tx, ry + 3, tw, UiKit.TEXT, 0.75f);
            UiKit.textFit(g, font, summary(type, group, got), tx, ry + 11, tw, got > 0 ? UiKit.MUTED : required ? UiKit.ERROR : UiKit.DIM, 0.75f);
            if (hover) UiKit.queueTip(Component.translatable("hypervisorx.board.click_install"));
            ry += ROW;
        }
        int by = sheetY + sheetH - 6;
        S2C.HostInfo hi = info.get();
        int sy = ry + 2;
        if (hi != null && !hi.problems().isEmpty()) {
            UiKit.icon(g, UiKit.Icon.WARN, x + 5, sy - 2, UiKit.WARN);
            UiKit.paragraph(g, font, hi.problems().get(0).getString(), x + 19, sy, w - 24, Math.max(1, (by - sy - 12) / UiKit.pitch(0.75f)), UiKit.WARN, 0.75f);
        } else if (complete()) {
            UiKit.icon(g, UiKit.Icon.CHECK, x + 5, sy - 2, UiKit.OK);
            UiKit.textFit(g, font, Component.translatable("hypervisorx.board.ready").getString(), x + 19, sy, w - 24, UiKit.OK, 0.75f);
        } else {
            UiKit.paragraph(g, font, Component.translatable("hypervisorx.board.hint").getString(), x + 6, sy, w - 12, 3, UiKit.DIM, 0.75f);
        }
        UiKit.textFit(g, font, Component.translatable("hypervisorx.board.rotate").getString(), x + 6, by - 8, w - 12, UiKit.DIM, 0.75f);
    }

    private List<Socket> group(ComponentType type) {
        List<Socket> out = new ArrayList<>();
        for (Socket k : sockets) if (menu.form().slots().get(k.slot) == type) out.add(k);
        return out;
    }

    private String summary(ComponentType type, List<Socket> group, int got) {
        if (got == 0) return Component.translatable("hypervisorx.board.empty").getString();
        if (group.size() == 1) return item(group.get(0)).getHoverName().getString();
        String base = Component.translatable("hypervisorx.board.count", got, group.size()).getString();
        int sum = 0;
        for (Socket k : group) {
            HardwareSpec sp = spec(item(k));
            if (sp == null) continue;
            sum += type == ComponentType.RAM ? sp.ramMb() / 1024 : type == ComponentType.STORAGE ? sp.diskGb() : 0;
        }
        if (type == ComponentType.RAM) return base + "  ·  " + sum + " GB";
        if (type == ComponentType.STORAGE) return base + "  ·  " + HardwareItem.formatCapacity(sum);
        return base;
    }

    // --------------------------------------------------------------------------------------- input

    public boolean mouseClicked(double mx, double my, int button) {
        if (picker != null) {
            pickerClick(mx, my);
            return true;
        }
        Socket k = socketAt(mx, my);
        if (k != null) {
            if (button == 1 && !item(k).isEmpty()) {
                remove(k);
                return true;
            }
            open(k);
            return true;
        }
        int ry = rowsTop();
        for (ComponentType type : GROUPS) {
            List<Socket> group = group(type);
            if (group.isEmpty()) continue;
            if (mx >= sheetX + 3 && mx < sheetX + sheetW - 3 && my >= ry && my < ry + ROW - 2) {
                Socket target = group.get(0);
                for (Socket s : group) {
                    if (item(s).isEmpty()) {
                        target = s;
                        break;
                    }
                }
                open(target);
                return true;
            }
            ry += ROW;
        }
        if (mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH) {
            // empty board space: drag turns it, double-click resets the view
            dragging = true;
            dragX = mx;
            dragY = my;
            if (Util.getMillis() - lastClick < 300) {
                yaw = 14f;
                pitch = 50f;
                zoom = 1.12f;
            }
            lastClick = Util.getMillis();
            return true;
        }
        return mx >= sheetX && mx < sheetX + sheetW && my >= panelY && my < panelY + panelH;
    }

    private long lastClick;

    public boolean mouseDragged(double mx, double my) {
        if (!dragging) return false;
        yaw = Mth.clamp(yaw + (float) (mx - dragX) * 0.6f, -70f, 70f);
        pitch = Mth.clamp(pitch - (float) (my - dragY) * 0.5f, 18f, 82f);
        dragX = mx;
        dragY = my;
        return true;
    }

    public boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    public boolean mouseScrolled(double mx, double my, double dy) {
        if (picker == null) {
            if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + panelH || dy == 0) return false;
            zoom = Mth.clamp(zoom * (dy > 0 ? 1.1f : 1 / 1.1f), 0.8f, 2.6f);
            return true;
        }
        pickerScroll = Mth.clamp(pickerScroll - (int) Math.signum(dy), 0, Math.max(0, entries.size() - PICK_ROWS));
        return true;
    }

    public boolean keyPressed(int key) {
        if (picker != null && key == 256) {
            picker = null;
            return true;
        }
        return false;
    }

    public boolean pickerOpen() {
        return picker != null;
    }

    public void close() {
        picker = null;
        dragging = false;
    }

    private void open(Socket k) {
        picker = k;
        pickerOpened = Util.getMillis();
        pickerScroll = 0;
        entries.clear();
        ComponentType type = menu.form().slots().get(k.slot);
        ItemStack current = item(k);
        int partEnd = menu.form().slotCount();
        for (int i = partEnd; i < menu.slots.size(); i++) {
            Slot sl = menu.slots.get(i);
            ItemStack st = sl.getItem();
            if (st.isEmpty() || !fits(type, st) || ItemStack.isSameItemSameComponents(st, current)) continue;
            boolean merged = false;
            for (int j = 0; j < entries.size(); j++) {
                Entry e = entries.get(j);
                if (ItemStack.isSameItemSameComponents(e.stack, st)) {
                    entries.set(j, new Entry(e.stack, e.menuSlot, e.count + st.getCount()));
                    merged = true;
                    break;
                }
            }
            if (!merged) entries.add(new Entry(st.copyWithCount(1), i, st.getCount()));
        }
        play(HvxSounds.ASSEMBLY_PART_PICK, 1f);
    }

    private static boolean fits(ComponentType type, ItemStack st) {
        if (type == ComponentType.USB) return st.getItem() instanceof UsbDriveItem;
        return st.getItem() instanceof HardwareItem h && !(st.getItem() instanceof UsbDriveItem) && h.spec().type() == type;
    }

    private void click(int slot, int button, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && mc.player != null) mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
    }

    private void remove(Socket k) {
        click(k.slot, 0, ClickType.QUICK_MOVE);
        if (!item(k).isEmpty()) message("hypervisorx.board.full");
    }

    private void install(Socket k, Entry e) {
        if (!menu.getCarried().isEmpty()) {
            message("hypervisorx.board.carrying");
            return;
        }
        if (!item(k).isEmpty()) remove(k);
        if (!item(k).isEmpty()) return;
        click(e.menuSlot, 0, ClickType.PICKUP);
        click(k.slot, 1, ClickType.PICKUP);
        if (!menu.getCarried().isEmpty()) click(e.menuSlot, 0, ClickType.PICKUP);
    }

    private static void message(String key) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setOverlayMessage(Component.translatable(key).withStyle(s -> s.withColor(UiKit.WARN & 0xFFFFFF)), false);
    }

    /** hooks for the automated tests: open a socket's part list, install the first entry, or pull the part. */
    public void testOpen(int slot) {
        for (Socket k : sockets) if (k.slot == slot) open(k);
    }

    public void testInstall(int slot) {
        for (Socket k : sockets) {
            if (k.slot != slot) continue;
            open(k);
            if (!entries.isEmpty()) install(k, entries.get(0));
            picker = null;
        }
    }

    public void testRemove(int slot) {
        for (Socket k : sockets) if (k.slot == slot) remove(k);
    }

    public void testView(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    // --------------------------------------------------------------------------------------- picker

    private static final int PICK_ROWS = 5;
    private static final int PICK_ROW = 20;

    /** screen-space bounds of a socket's pick box. */
    private int[] screenRect(Socket k) {
        float[] b = pickBox(k);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vector3f v = view.transformPosition((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5], new Vector3f());
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        return new int[]{(int) minX, (int) minY, (int) Math.ceil(maxX), (int) Math.ceil(maxY)};
    }

    public void renderPicker(GuiGraphics g, Font font, int mx, int my, int screenW, int screenH) {
        if (picker == null) return;
        Socket k = picker;
        ItemStack current = item(k);
        int rows = Math.min(PICK_ROWS, entries.size());
        pkW = 168;
        int header = 26;
        int installed = current.isEmpty() ? 0 : PICK_ROW + 4;
        int body = entries.isEmpty() ? 30 : rows * PICK_ROW;
        pkH = header + installed + body + 4;
        int[] r = screenRect(k);
        pkX = r[2] + 8;
        if (pkX + pkW > screenW - 4) pkX = r[0] - 8 - pkW;
        pkX = Mth.clamp(pkX, 4, screenW - 4 - pkW);
        pkY = Mth.clamp((r[1] + r[3]) / 2 - pkH / 2, 4, screenH - 4 - pkH);
        float t = Mth.clamp((Util.getMillis() - pickerOpened) / 120f, 0f, 1f);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 400);
        pose.translate(pkX + pkW / 2f, pkY + pkH / 2f, 0);
        float sc = 0.94f + 0.06f * ease(t);
        pose.scale(sc, sc, 1);
        pose.translate(-(pkX + pkW / 2f), -(pkY + pkH / 2f), 0);
        UiKit.shadow(g, pkX, pkY, pkW, pkH);
        UiKit.box(g, pkX, pkY, pkW, pkH, 0xF5171B22, UiKit.lerp(UiKit.STROKE, UiKit.ACCENT_2, 0.5f));
        UiKit.accentLine(g, pkX + 1, pkY, pkW - 2);
        ComponentType type = menu.form().slots().get(k.slot);
        UiKit.icon(g, GROUP_ICONS[java.util.Arrays.asList(GROUPS).indexOf(type)], pkX + 5, pkY + 5, type.uiColor());
        g.drawString(font, UiKit.clip(font, k.name, pkW - 40), pkX + 20, pkY + 5, UiKit.TEXT, false);
        UiKit.textFit(g, font, Component.translatable("hypervisorx.board.kind." + k.kind.name().toLowerCase(java.util.Locale.ROOT)).getString(),
                pkX + 20, pkY + 15, pkW - 26, UiKit.MUTED, 0.75f);
        int y = pkY + header;
        if (!current.isEmpty()) {
            g.renderItem(current, pkX + 5, y + 2);
            UiKit.textFit(g, font, current.getHoverName().getString(), pkX + 25, y + 3, pkW - 25 - 52, UiKit.TEXT, 0.75f);
            UiKit.textFit(g, font, Component.translatable("hypervisorx.board.installed").getString(), pkX + 25, y + 11, pkW - 25 - 52, UiKit.OK, 0.75f);
            boolean hov = mx >= pkX + pkW - 50 && mx < pkX + pkW - 5 && my >= y + 3 && my < y + 17;
            UiKit.box(g, pkX + pkW - 50, y + 3, 45, 14, hov ? 0xFF3A1820 : 0xFF2A141A, UiKit.ERROR);
            UiKit.fitLabel(g, font, Component.translatable("hypervisorx.board.remove").getString(), pkX + pkW - 50, y + 3, 45, 14, UiKit.ERROR, true);
            y += PICK_ROW + 4;
            g.fill(pkX + 4, y - 3, pkX + pkW - 4, y - 2, UiKit.STROKE_SOFT);
        }
        if (entries.isEmpty()) {
            UiKit.icon(g, UiKit.Icon.CART, pkX + 5, y + 2, UiKit.DIM);
            UiKit.textFit(g, font, Component.translatable("hypervisorx.board.none").getString(), pkX + 20, y + 3, pkW - 26, UiKit.MUTED, 0.75f);
            UiKit.textFit(g, font, Component.translatable("hypervisorx.board.none_hint").getString(), pkX + 20, y + 13, pkW - 26, UiKit.DIM, 0.75f);
        }
        for (int i = 0; i < rows; i++) {
            Entry e = entries.get(pickerScroll + i);
            int ry = y + i * PICK_ROW;
            boolean hov = mx >= pkX + 2 && mx < pkX + pkW - 2 && my >= ry && my < ry + PICK_ROW;
            if (hov) g.fill(pkX + 2, ry, pkX + pkW - 2, ry + PICK_ROW, UiKit.CARD_HOVER);
            g.renderItem(e.stack, pkX + 5, ry + 2);
            if (e.count > 1) UiKit.text(g, font, "×" + e.count, pkX + 14, ry + 13, UiKit.TEXT, 0.75f);
            int tw = pkW - 25 - 8;
            UiKit.textFit(g, font, e.stack.getHoverName().getString(), pkX + 25, ry + 3, tw, UiKit.TEXT, 0.75f);
            UiKit.textFit(g, font, specLine(e.stack), pkX + 25, ry + 11, tw, hov ? UiKit.ACCENT_2 : UiKit.MUTED, 0.75f);
        }
        if (entries.size() > PICK_ROWS) {
            UiKit.scrollbar(g, pkX + pkW - 4, y + 1, rows * PICK_ROW - 2, pickerScroll, entries.size() - PICK_ROWS, PICK_ROWS, entries.size());
        }
        pose.popPose();
    }

    private static String specLine(ItemStack s) {
        HardwareSpec sp = spec(s);
        if (sp == null) return "";
        return switch (sp.type()) {
            case CPU -> Component.translatable("hypervisorx.spec.cores", sp.cores()).getString();
            case RAM -> Component.translatable("hypervisorx.spec.ram", sp.ramMb() / 1024).getString();
            case GPU -> Component.translatable("hypervisorx.spec.resolution", sp.width(), sp.height()).getString();
            case STORAGE -> Component.translatable(sp.nvme() ? "hypervisorx.spec.disk_nvme" : "hypervisorx.spec.disk_sata",
                    HardwareItem.formatCapacity(sp.diskGb())).getString();
            case EXPANSION -> Component.translatable("hypervisorx.spec.feature." + sp.feature()).getString();
            case USB -> HardwareItem.formatCapacity(sp.diskGb());
            default -> "";
        };
    }

    private void pickerClick(double mx, double my) {
        Socket k = picker;
        if (mx < pkX || mx >= pkX + pkW || my < pkY || my >= pkY + pkH) {
            picker = null;
            Socket other = socketAt(mx, my);
            if (other != null && other != k) open(other);
            return;
        }
        int y = pkY + 26;
        if (!item(k).isEmpty()) {
            if (mx >= pkX + pkW - 50 && mx < pkX + pkW - 5 && my >= y + 3 && my < y + 17) {
                remove(k);
                picker = null;
                return;
            }
            y += PICK_ROW + 4;
        }
        int idx = (int) ((my - y) / PICK_ROW);
        if (my >= y && idx >= 0 && idx < Math.min(PICK_ROWS, entries.size())) {
            install(k, entries.get(pickerScroll + idx));
            picker = null;
        }
    }
}
