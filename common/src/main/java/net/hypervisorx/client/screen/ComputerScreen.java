package net.hypervisorx.client.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.menu.ComputerMenu;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.registry.HvxBlocks;
import net.hypervisorx.vm.VmRecord;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * PC assembly and system configuration: drag parts into typed slots on the left, choose the
 * hypervisor, OS image and sharing on the right, and power the machine on. Everything sits on a
 * 4-px grid; both slot grids share one origin and an 18-px pitch.
 */
public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
    private static final int H = 236;
    private static final int RIGHT_X = 184;
    private static final int LEFT_W = 174;
    /** shrinks to fit the window instead of getting clipped (MC guarantees at least 320 GUI px). */
    private int W = 356;
    private int RIGHT_W = 166;
    private static final List<String> BACKENDS = List.of("auto", "vmware", "virtualbox", "qemu", "embedded", "hostcapture");

    private S2C.HostInfo info;
    private int powerY;
    private int slot;
    private boolean fieldsDirty;
    private boolean populated;
    private EditBox name;
    private EditBox target;
    private EditBox trustName;
    private UiKit.ChoiceButton<String> backend;
    private UiKit.ChoiceButton<GuestOs> guestOs;
    private UiKit.ChoiceButton<Boolean> efi;
    private UiKit.ChoiceButton<String> image;
    private UiKit.ChoiceButton<VmRecord.Access> access;
    private UiKit.ChoiceButton<Integer> span;
    private UiKit.ChoiceButton<Integer> cores;
    private UiKit.ChoiceButton<Integer> ram;
    private UiKit.ChoiceButton<Integer> disk;

    public ComputerScreen(ComputerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = W;
        imageHeight = H;
    }

    private boolean rack() {
        return menu.form() == FormFactor.RACK;
    }

    private static String label(String key) {
        return Component.translatable("hypervisorx.ui.label." + key).getString();
    }

    @Override
    protected void init() {
        W = Math.max(320, Math.min(356, width - 4));
        RIGHT_W = W - RIGHT_X - 6;
        imageWidth = W;
        super.init();
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(C2S.LibraryAction.REFRESH, ""));
        int x = leftPos + RIGHT_X + 6;
        int fw = RIGHT_W - 12;
        int half = (fw - 4) / 2;
        int y = topPos + 36;
        if (rack()) {
            // VM slot tabs in the title bar
            int tx = leftPos + W - 6 - 5 * 26 - 22;
            for (int i = 0; i < 4; i++) {
                final int s = i;
                addRenderableWidget(new UiKit.FlatButton(tx + i * 26, topPos + 4, 24, 12, Component.literal(String.valueOf(i + 1)), () -> selectSlot(s))
                        .enabledWhen(() -> slotInfo(s) != null).selectedWhen(() -> slot == s)
                        .tip(Component.translatable("hypervisorx.ui.slot", i + 1)));
            }
            addRenderableWidget(new UiKit.FlatButton(tx + 4 * 26, topPos + 4, 20, 12, Component.empty(), () -> act(C2S.HostAction.ADD_VM))
                    .icon(UiKit.Icon.PLUS).accent(UiKit.OK).tip(Component.translatable("hypervisorx.ui.tip.add_vm")));
            addRenderableWidget(new UiKit.FlatButton(tx + 4 * 26 + 22, topPos + 4, 20, 12, Component.empty(), () -> act(C2S.HostAction.DELETE_VM))
                    .icon(UiKit.Icon.TRASH).style(UiKit.Style.DANGER).tip(Component.translatable("hypervisorx.ui.tip.delete_vm"))
                    .enabledWhen(() -> current() != null && current().vm() != null && !running()));
        }
        name = new UiKit.Field(font, x, y, fw, 14, Component.translatable("hypervisorx.ui.name_hint"), label("name"));
        name.setMaxLength(48);
        name.setResponder(s -> fieldsDirty = true);
        addRenderableWidget(name);
        y += 16;
        backend = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, fw, 14, label("engine"), BACKENDS, "auto", this::backendLabel, v -> fieldsDirty = true));
        y += 16;
        guestOs = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 14, label("os"), Arrays.asList(GuestOs.values()), GuestOs.LINUX,
                GuestOs::displayName, v -> fieldsDirty = true));
        efi = addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 4, y, fw - half - 4, 14, label("boot"), List.of(false, true), false,
                v -> v ? "UEFI" : "BIOS", v -> fieldsDirty = true));
        y += 16;
        image = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, fw - 22, 14, label("image"), new ArrayList<>(List.of("")), "",
                v -> v.isEmpty() ? Component.translatable("hypervisorx.ui.no_image").getString() : v, v -> fieldsDirty = true));
        addRenderableWidget(new UiKit.FlatButton(x + fw - 20, y, 20, 14, Component.empty(), () -> minecraft.setScreen(new LibraryScreen(this)))
                .icon(UiKit.Icon.LIBRARY).accent(UiKit.ACCENT_2).tip(Component.translatable("hypervisorx.ui.tip.library")));
        y += 16;
        target = new UiKit.Field(font, x, y, fw, 14, Component.translatable("hypervisorx.ui.target_hint"), label("target"));
        target.setMaxLength(200);
        target.setResponder(s -> fieldsDirty = true);
        addRenderableWidget(target);
        y += 16;
        access = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 14, label("access"), Arrays.asList(VmRecord.Access.values()), VmRecord.Access.PRIVATE,
                v -> Component.translatable("hypervisorx.access." + v.name().toLowerCase(Locale.ROOT) + ".short").getString(), v -> fieldsDirty = true));
        access.tip(() -> Component.translatable("hypervisorx.access." + access.value().name().toLowerCase(Locale.ROOT)));
        span = addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 4, y, fw - half - 4, 14, label("span"), List.of(1, 2, 3), 1,
                v -> v + "×", v -> fieldsDirty = true));
        span.tip(() -> Component.translatable("hypervisorx.ui.span", span.value()));
        y += 16;
        if (rack()) {
            int third = (fw - 8) / 3;
            cores = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, third, 14, "", List.of(1, 2, 4, 6, 8, 12, 16, 24, 32), 2,
                    v -> v + " CPU", v -> fieldsDirty = true));
            ram = addRenderableWidget(new UiKit.ChoiceButton<>(x + third + 4, y, third, 14, "", List.of(1024, 2048, 4096, 8192, 16384, 32768, 65536), 4096,
                    v -> v / 1024 + " GB", v -> fieldsDirty = true));
            disk = addRenderableWidget(new UiKit.ChoiceButton<>(x + 2 * (third + 4), y, fw - 2 * (third + 4), 14, "",
                    List.of(16, 32, 64, 128, 256, 512, 1024, 2048), 64, v -> v + " GB", v -> fieldsDirty = true));
            y += 16;
        }
        trustName = new UiKit.Field(font, x, y, fw - 44, 14, Component.translatable("hypervisorx.ui.trust_hint"), label("trust"));
        trustName.setMaxLength(16);
        addRenderableWidget(trustName);
        addRenderableWidget(new UiKit.FlatButton(x + fw - 42, y, 20, 14, Component.empty(), () -> trust(true))
                .icon(UiKit.Icon.USER_PLUS).accent(UiKit.OK).tip(Component.translatable("hypervisorx.ui.tip.trust_add")));
        addRenderableWidget(new UiKit.FlatButton(x + fw - 20, y, 20, 14, Component.empty(), () -> trust(false))
                .icon(UiKit.Icon.USER_MINUS).style(UiKit.Style.DANGER).tip(Component.translatable("hypervisorx.ui.tip.trust_remove")));
        y += 16;
        addRenderableWidget(new UiKit.FlatButton(x, y, fw, 16, Component.translatable("hypervisorx.ui.save"), this::save)
                .label(() -> Component.translatable(fieldsDirty ? "hypervisorx.ui.save_pending" : "hypervisorx.ui.save"))
                .icon(UiKit.Icon.CHECK).style(UiKit.Style.PRIMARY));
        y += 20;
        powerY = y - topPos;

        // power stuff: Power and Focus get text labels, the rest are icon buttons w/ tooltips
        int rowB = topPos + H - 6 - 3 - 16;
        UiKit.FlatButton power = new UiKit.FlatButton(0, 0, 10, 16, Component.empty(), () -> act(running() ? C2S.HostAction.SHUTDOWN : C2S.HostAction.POWER_ON))
                .label(() -> Component.translatable(running() ? "hypervisorx.focus.shutdown" : "hypervisorx.focus.power_on"))
                .icon(UiKit.Icon.POWER).accent(UiKit.OK);
        UiKit.FlatButton focus = new UiKit.FlatButton(0, 0, 10, 16, Component.translatable("hypervisorx.ui.focus_label"), () -> act(C2S.HostAction.FOCUS))
                .icon(UiKit.Icon.FOCUS).style(UiKit.Style.PRIMARY).enabledWhen(() -> current() != null && current().vm() != null)
                .tip(Component.translatable("hypervisorx.ui.focus"));
        List<UiKit.FlatButton> icons = List.of(
                new UiKit.FlatButton(0, 0, 10, 14, Component.empty(), () -> act(C2S.HostAction.FORCE_OFF)).icon(UiKit.Icon.STOP)
                        .style(UiKit.Style.DANGER).enabledWhen(this::running).tip(Component.translatable("hypervisorx.focus.force_off")),
                new UiKit.FlatButton(0, 0, 10, 14, Component.empty(), () -> act(C2S.HostAction.RESET)).icon(UiKit.Icon.RESET)
                        .accent(UiKit.WARN).enabledWhen(this::running).tip(Component.translatable("hypervisorx.focus.reset")),
                new UiKit.FlatButton(0, 0, 10, 14, Component.empty(), () -> act(C2S.HostAction.SNAPSHOT)).icon(UiKit.Icon.CAMERA)
                        .accent(UiKit.ACCENT).enabledWhen(this::running).tip(Component.translatable("hypervisorx.focus.snapshot")),
                new UiKit.FlatButton(0, 0, 10, 14, Component.empty(), () -> act(C2S.HostAction.INSERT_MEDIA)).icon(UiKit.Icon.DISC)
                        .accent(UiKit.ACCENT_2).enabledWhen(this::running).tip(Component.translatable("hypervisorx.ui.media")));
        if (!rack()) {
            // row A: four icon buttons; row B: Power and Focus with labels
            int rowA = rowB - 3 - 14;
            int iw = (fw - 3 * 4) / 4;
            for (int i = 0; i < icons.size(); i++) {
                UiKit.FlatButton b = icons.get(i);
                b.setRectangle(i == 3 ? fw - 3 * (iw + 4) : iw, 14, x + i * (iw + 4), rowA);
                addRenderableWidget(b);
            }
            power.setRectangle(half, 16, x, rowB);
            focus.setRectangle(fw - half - 4, 16, x + half + 4, rowB);
        } else {
            // racks are tighter: one row, Power and Focus split whatever the icons leave
            int iw = 18;
            int flex = (fw - 4 * iw - 5 * 3) / 2;
            power.setRectangle(flex, 16, x, rowB);
            int bx = x + flex + 3;
            for (UiKit.FlatButton b : icons) {
                b.setRectangle(iw, 16, bx, rowB);
                addRenderableWidget(b);
                bx += iw + 3;
            }
            focus.setRectangle(x + fw - bx, 16, bx, rowB);
        }
        addRenderableWidget(power);
        addRenderableWidget(focus);
        S2C.HostInfo cached = ClientVmCache.hostInfo();
        if (cached != null && cached.pos().equals(menu.pos())) onHostInfo(cached);
    }

    private String backendLabel(String id) {
        if (id.equals("auto")) return Component.translatable("hypervisorx.ui.backend_auto").getString();
        String shortName = switch (id) {
            case "vmware" -> "VMware Workstation";
            case "virtualbox" -> "VirtualBox";
            case "qemu" -> "QEMU / KVM / WHPX";
            case "embedded" -> "Embedded MicroVM";
            case "hostcapture" -> "Host window capture";
            default -> id;
        };
        S2C.Library lib = ClientVmCache.library();
        if (lib != null) {
            for (S2C.BackendEntry e : lib.backends()) {
                if (e.id().equals(id)) return (e.available() ? "✔ " : "✘ ") + shortName;
            }
        }
        return shortName;
    }

    private S2C.VmSlotInfo slotInfo(int s) {
        if (info == null) return null;
        for (S2C.VmSlotInfo v : info.slots()) if (v.slot() == s) return v;
        return null;
    }

    private S2C.VmSlotInfo current() {
        S2C.VmSlotInfo v = slotInfo(slot);
        if (v == null && info != null && !info.slots().isEmpty() && !rack()) v = info.slots().get(0);
        return v;
    }

    private boolean running() {
        S2C.VmSlotInfo v = current();
        return v != null && (v.state().equals("RUNNING") || v.state().equals("STARTING") || v.state().equals("STOPPING"));
    }

    private void selectSlot(int s) {
        slot = s;
        populated = false;
        fieldsDirty = false;
        populate();
    }

    public void onHostInfo(S2C.HostInfo hostInfo) {
        if (!hostInfo.pos().equals(menu.pos())) return;
        this.info = hostInfo;
        if (rack() && slotInfo(slot) == null && !hostInfo.slots().isEmpty()) {
            slot = hostInfo.slots().get(0).slot();
            populated = false;
        }
        populate();
    }

    public void onLibrary(S2C.Library lib) {
        if (image == null) return;
        List<String> names = new ArrayList<>();
        names.add("");
        for (S2C.ImageEntry e : lib.images()) names.add(e.name());
        String selected = image.value();
        if (selected != null && !selected.isEmpty() && !names.contains(selected)) names.add(selected);
        image.setValues(names);
    }

    /** copies the server's settings for the selected VM into the widgets, unless the user's mid-edit. */
    private void populate() {
        S2C.VmSlotInfo v = current();
        if (v == null || name == null || populated) return;
        name.setValue(v.name());
        backend.set(v.backend());
        guestOs.set(GuestOs.parse(v.guestOs()));
        efi.set(v.efi());
        image.add(v.image());
        image.set(v.image());
        target.setValue(v.target());
        access.set(VmRecord.Access.parse(v.access()));
        span.set(v.span());
        if (cores != null) {
            cores.set(v.cores());
            ram.set(v.ramMb());
            disk.set(v.diskGb());
        }
        populated = true;
        fieldsDirty = false;
    }

    private void act(int action) {
        Services.PLATFORM.sendToServer(new C2S.HostAction(menu.pos(), slot, action));
    }

    private void save() {
        if (current() == null && !rack()) return;
        Services.PLATFORM.sendToServer(new C2S.ConfigureVm(menu.pos(), slot, name.getValue(), backend.value(), guestOs.value().name(),
                image.value() == null ? "" : image.value(), target.getValue(), access.value().name(), span.value(),
                cores == null ? 2 : cores.value(), ram == null ? 4096 : ram.value(), disk == null ? 64 : disk.value(), efi.value()));
        fieldsDirty = false;
        populated = false;
    }

    private void trust(boolean add) {
        String n = trustName.getValue().strip();
        if (n.isEmpty()) return;
        Services.PLATFORM.sendToServer(new C2S.Trust(menu.pos(), slot, n, add));
        trustName.setValue("");
    }

    private int partsBottom() {
        return ComputerMenu.PART_Y + ((menu.form().slotCount() + ComputerMenu.PART_COLS - 1) / ComputerMenu.PART_COLS) * ComputerMenu.PART_SPACING_Y;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        UiKit.window(g, font, x, y, W, H, rack() ? UiKit.Icon.SERVER : UiKit.Icon.CHIP, title,
                rack() ? null : Component.translatable("hypervisorx.ui.subtitle"));
        if (!rack()) {
            String state = stateOf(current());
            UiKit.pill(g, font, x + W - 7, y + 4, Component.translatable("hypervisorx.state." + state.toLowerCase(Locale.ROOT)).getString(),
                    stateColor(state));
        }
        // left: hardware + inventory
        UiKit.section(g, font, x + 6, y + 24, LEFT_W, 104, Component.translatable("hypervisorx.ui.hardware"));
        UiKit.section(g, font, x + 6, y + 132, LEFT_W, 98, Component.translatable("hypervisorx.ui.inventory"));
        for (int i = 0; i < menu.form().slotCount(); i++) {
            Slot s = menu.slots.get(i);
            ComponentType type = menu.form().slots().get(i);
            UiKit.slot(g, x + s.x, y + s.y, type.uiColor());
            if (!s.hasItem()) UiKit.slotGhost(g, type, x + s.x, y + s.y);
        }
        for (int i = menu.form().slotCount(); i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            UiKit.slot(g, x + s.x, y + s.y, 0);
        }
        renderSpecs(g, x + 96, y + 38);
        renderBelowParts(g, partialTick);
        // right: VM settings + power
        UiKit.section(g, font, x + RIGHT_X, y + 24, RIGHT_W, powerY - 28, Component.translatable("hypervisorx.ui.vm"));
        UiKit.section(g, font, x + RIGHT_X, y + powerY - 1, RIGHT_W, H - 6 - powerY + 1, Component.translatable("hypervisorx.ui.power"));
        renderStatus(g, x + RIGHT_X + 6, y + powerY + 12, RIGHT_W - 12, mouseX, mouseY);
    }

    private static String stateOf(S2C.VmSlotInfo v) {
        return v == null ? "STOPPED" : v.state();
    }

    private static int stateColor(String state) {
        return switch (state) {
            case "RUNNING" -> UiKit.OK;
            case "STARTING", "STOPPING" -> UiKit.WARN;
            case "ERROR" -> UiKit.ERROR;
            default -> UiKit.MUTED;
        };
    }

    private void renderSpecs(GuiGraphics g, int x, int y) {
        int w = LEFT_W - 96;
        if (info == null) return;
        int row = 0;
        int pitch = 13;
        if (info.rack()) {
            spec(g, UiKit.Icon.CHIP, Component.translatable("hypervisorx.ui.spec.cores", info.cores()).getString(), x, y + row++ * pitch, w, UiKit.TEXT);
            spec(g, UiKit.Icon.MEMORY, Component.translatable("hypervisorx.ui.spec.ram", info.ramMb() / 1024).getString(), x, y + row++ * pitch, w, UiKit.TEXT);
            spec(g, UiKit.Icon.DRIVE, info.diskGb() + " GB", x, y + row++ * pitch, w, UiKit.TEXT);
            spec(g, UiKit.Icon.SERVER, Component.translatable("hypervisorx.ui.pool_free", info.poolCoresFree(), info.poolRamFree() / 1024,
                    info.poolDiskFree()).getString(), x, y + row++ * pitch, w, UiKit.ACCENT_2);
        } else {
            spec(g, UiKit.Icon.CHIP, Component.translatable("hypervisorx.ui.spec.cores", info.cores()).getString(), x, y + row++ * pitch, w, UiKit.TEXT);
            spec(g, UiKit.Icon.MEMORY, Component.translatable("hypervisorx.ui.spec.ram", info.ramMb() / 1024).getString(), x, y + row++ * pitch, w, UiKit.TEXT);
            spec(g, UiKit.Icon.GPU, info.width() > 0
                    ? Component.translatable("hypervisorx.ui.spec.display", info.width(), info.height(), info.displays()).getString()
                    : Component.translatable("hypervisorx.ui.spec.nodisplay").getString(), x, y + row++ * pitch, w, info.width() > 0 ? UiKit.TEXT : UiKit.WARN);
            spec(g, UiKit.Icon.DRIVE, info.diskGb() > 0 ? HardwareItem.formatCapacity(info.diskGb()) + (info.nvme() ? " NVMe" : "")
                    : Component.translatable("hypervisorx.ui.spec.nodisk").getString(), x, y + row++ * pitch, w, info.diskGb() > 0 ? UiKit.TEXT : UiKit.WARN);
            String extras = ((info.gpio() ? "GPIO " : "") + (info.wifi() ? "Wi-Fi" : "")).trim();
            if (!extras.isEmpty()) spec(g, info.wifi() ? UiKit.Icon.WIFI : UiKit.Icon.PCIE, extras, x, y + row++ * pitch, w, UiKit.ACCENT_2);
        }
    }

    /** fills the dead zone under the slots: problems if there are any, else a slowly spinning render of the machine. */
    private void renderBelowParts(GuiGraphics g, float partialTick) {
        int top = topPos + partsBottom() + 3;
        int bottom = topPos + 124;
        int px = leftPos + 12;
        if (info != null && !info.problems().isEmpty()) {
            int py = top;
            int pitch = UiKit.pitch(0.75f);
            for (Component p : info.problems()) {
                if (py + pitch > bottom) break;
                UiKit.icon(g, UiKit.Icon.WARN, px, py - 2, UiKit.WARN);
                py += pitch * UiKit.paragraph(g, font, p.getString(), px + 14, py, 70, Math.max(1, (bottom - py) / pitch), UiKit.WARN, 0.75f) + 3;
            }
            return;
        }
        int size = Math.min(48, bottom - top);
        if (size < 20) return;
        ItemStack stack = new ItemStack(switch (menu.form()) {
            case RETRO -> HvxBlocks.RETRO_PC.get();
            case TOWER -> HvxBlocks.GAMING_TOWER.get();
            case RACK -> HvxBlocks.SERVER_RACK.get();
        });
        float angle = (Util.getMillis() % 12000L) / 12000f * 360f;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(leftPos + 6 + 84 / 2f, top + size / 2f, 150);
        pose.scale(size * 0.8f, -size * 0.8f, size * 0.8f);
        pose.mulPose(Axis.XP.rotationDegrees(20));
        pose.mulPose(Axis.YP.rotationDegrees(angle));
        Lighting.setupFor3DItems();
        minecraft.getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose,
                g.bufferSource(), minecraft.level, 0);
        g.flush();
        pose.popPose();
    }

    private void spec(GuiGraphics g, UiKit.Icon icon, String text, int x, int y, int w, int color) {
        UiKit.icon(g, icon, x, y - 2, UiKit.INFO);
        UiKit.textFit(g, font, text, x + 15, y + 1, w - 17, color, 0.75f);
    }

    private void renderStatus(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        S2C.VmSlotInfo v = current();
        String state = stateOf(v);
        String msg = v == null ? "" : v.message();
        String stateName = Component.translatable("hypervisorx.state." + state.toLowerCase(Locale.ROOT)).getString();
        if (msg.equalsIgnoreCase(state) || msg.equalsIgnoreCase(stateName)) msg = "";
        if (rack()) {
            // racks don't get a state pill in the title bar
            UiKit.dot(g, x, y + 1, stateColor(state));
            String head = stateName + (v != null && !v.name().isEmpty() ? "  ·  " + v.name() : "") + (msg.isEmpty() ? "" : "  ·  " + msg);
            UiKit.textFit(g, font, head, x + 9, y, w - 9, stateColor(state), 0.75f);
        } else {
            String line = msg.isEmpty() ? (v != null && !v.name().isEmpty() ? v.name() : stateName) : msg;
            UiKit.textFit(g, font, line, x, y, w, msg.isEmpty() ? UiKit.INFO : stateColor(state) == UiKit.ERROR ? UiKit.ERROR : UiKit.INFO, 0.75f);
        }
        // full message as a tooltip, for when it doesn't fit
        if (!msg.isEmpty() && mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9) UiKit.queueTip(Component.literal(msg));
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // nothing here, window chrome and section captions get drawn in renderBg
    }

    /** the target field only matters for the embedded MicroVM and host window capture. */
    private boolean targetUsed() {
        String b = backend == null ? "auto" : backend.value();
        return b.equals("embedded") || b.equals("hostcapture") || b.equals("auto") && current() != null && "embedded".equals(current().backend());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (target != null) {
            boolean used = targetUsed();
            target.setEditable(used);
            target.setHint(Component.translatable(used ? "hypervisorx.ui.target_hint" : "hypervisorx.ui.target_unused"));
            target.setTextColor(used ? 0xE0E0E0 : 0x5B6474);
        }
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        if (hoveredSlot != null && !hoveredSlot.hasItem() && hoveredSlot instanceof ComputerMenu.PartSlot ps) {
            UiKit.queueTip(Component.translatable(ps.type().translationKey()));
        }
        UiKit.renderOverlays(g, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (UiKit.popoverClicked(mx, my, button)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (UiKit.popoverScrolled(mx, my, dy)) return true;
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public void removed() {
        UiKit.closePopover();
        super.removed();
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (UiKit.popoverKey(key)) return true;
        // don't let the inventory key close the screen while you're typing in a text field
        if ((name != null && name.isFocused()) || (target != null && target.isFocused()) || (trustName != null && trustName.isFocused())) {
            if (key == 256) return super.keyPressed(key, scancode, modifiers);
            return getFocused() != null && getFocused().keyPressed(key, scancode, modifiers) || true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }
}
