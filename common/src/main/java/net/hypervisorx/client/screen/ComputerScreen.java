package net.hypervisorx.client.screen;

import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.menu.ComputerMenu;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.vm.VmRecord;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * PC assembly and system configuration: drag parts into typed slots on the left, choose the
 * hypervisor, OS image and sharing on the right, and power the machine on.
 */
public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
    private static final int H = 236;
    private static final int RIGHT_X = 184;
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

    @Override
    protected void init() {
        W = Math.max(320, Math.min(356, width - 4));
        RIGHT_W = W - RIGHT_X - 6;
        imageWidth = W;
        super.init();
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(C2S.LibraryAction.REFRESH, ""));
        int x = leftPos + RIGHT_X + 6;
        int fw = RIGHT_W - 12;
        int half = (fw - 3) / 2;
        int y = topPos + 37;
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
        name = new UiKit.Field(font, x, y, fw, 14, Component.translatable("hypervisorx.ui.name"));
        name.setMaxLength(48);
        name.setHint(Component.translatable("hypervisorx.ui.name"));
        name.setResponder(s -> fieldsDirty = true);
        addRenderableWidget(name);
        y += 16;
        backend = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, fw, 14, "", BACKENDS, "auto", this::backendLabel, v -> fieldsDirty = true));
        y += 16;
        guestOs = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 14, "", Arrays.asList(GuestOs.values()), GuestOs.LINUX,
                GuestOs::displayName, v -> fieldsDirty = true));
        efi = addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, fw - half - 3, 14, "", List.of(false, true), false,
                v -> v ? "UEFI" : "BIOS", v -> fieldsDirty = true));
        y += 16;
        image = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, fw - 22, 14, "", new ArrayList<>(List.of("")), "",
                v -> v.isEmpty() ? Component.translatable("hypervisorx.ui.no_image").getString() : v, v -> fieldsDirty = true));
        addRenderableWidget(new UiKit.FlatButton(x + fw - 20, y, 20, 14, Component.empty(), () -> minecraft.setScreen(new LibraryScreen(this)))
                .icon(UiKit.Icon.LIBRARY).accent(UiKit.ACCENT_2).tip(Component.translatable("hypervisorx.ui.tip.library")));
        y += 16;
        target = new UiKit.Field(font, x, y, fw, 14, Component.translatable("hypervisorx.ui.target"));
        target.setMaxLength(200);
        target.setHint(Component.translatable("hypervisorx.ui.target_hint"));
        target.setResponder(s -> fieldsDirty = true);
        addRenderableWidget(target);
        y += 16;
        access = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 14, "", Arrays.asList(VmRecord.Access.values()), VmRecord.Access.PRIVATE,
                v -> Component.translatable("hypervisorx.access." + v.name().toLowerCase(Locale.ROOT) + ".short").getString(), v -> fieldsDirty = true));
        access.tip(() -> Component.translatable("hypervisorx.access." + access.value().name().toLowerCase(Locale.ROOT)));
        span = addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, fw - half - 3, 14, "", List.of(1, 2, 3), 1,
                v -> Component.translatable("hypervisorx.ui.span", v).getString(), v -> fieldsDirty = true));
        y += 16;
        if (rack()) {
            int third = (fw - 6) / 3;
            cores = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, third, 14, "", List.of(1, 2, 4, 6, 8, 12, 16, 24, 32), 2,
                    v -> v + " CPU", v -> fieldsDirty = true));
            ram = addRenderableWidget(new UiKit.ChoiceButton<>(x + third + 3, y, third, 14, "", List.of(1024, 2048, 4096, 8192, 16384, 32768, 65536), 4096,
                    v -> v / 1024 + " GB", v -> fieldsDirty = true));
            disk = addRenderableWidget(new UiKit.ChoiceButton<>(x + 2 * (third + 3), y, fw - 2 * (third + 3), 14, "",
                    List.of(16, 32, 64, 128, 256, 512, 1024, 2048), 64, v -> v + " GB", v -> fieldsDirty = true));
            y += 16;
        }
        trustName = new UiKit.Field(font, x, y, fw - 44, 14, Component.translatable("hypervisorx.ui.trust"));
        trustName.setMaxLength(16);
        trustName.setHint(Component.translatable("hypervisorx.ui.trust_hint"));
        addRenderableWidget(trustName);
        addRenderableWidget(new UiKit.FlatButton(x + fw - 42, y, 20, 14, Component.empty(), () -> trust(true))
                .icon(UiKit.Icon.USER_PLUS).accent(UiKit.OK).tip(Component.translatable("hypervisorx.ui.tip.trust_add")));
        addRenderableWidget(new UiKit.FlatButton(x + fw - 20, y, 20, 14, Component.empty(), () -> trust(false))
                .icon(UiKit.Icon.USER_MINUS).style(UiKit.Style.DANGER).tip(Component.translatable("hypervisorx.ui.tip.trust_remove")));
        y += 17;
        addRenderableWidget(new UiKit.FlatButton(x, y, fw, 15, Component.translatable("hypervisorx.ui.save"), this::save)
                .label(() -> Component.translatable(fieldsDirty ? "hypervisorx.ui.save_pending" : "hypervisorx.ui.save"))
                .icon(UiKit.Icon.CHECK).style(UiKit.Style.PRIMARY));
        y += 21;
        powerY = y - topPos;

        // power & control: icon buttons with tooltips (no labels that could overflow)
        int by = topPos + H - 26;
        int bw = (fw - 5 * 3) / 6;
        int bx = x;
        addRenderableWidget(new UiKit.FlatButton(bx, by, bw, 18, Component.empty(), () -> act(running() ? C2S.HostAction.SHUTDOWN : C2S.HostAction.POWER_ON))
                .icon(UiKit.Icon.POWER).accent(UiKit.OK)
                .tip(() -> Component.translatable(running() ? "hypervisorx.focus.shutdown" : "hypervisorx.focus.power_on")));
        bx += bw + 3;
        addRenderableWidget(new UiKit.FlatButton(bx, by, bw, 18, Component.empty(), () -> act(C2S.HostAction.FORCE_OFF)).icon(UiKit.Icon.STOP)
                .style(UiKit.Style.DANGER).enabledWhen(this::running).tip(Component.translatable("hypervisorx.focus.force_off")));
        bx += bw + 3;
        addRenderableWidget(new UiKit.FlatButton(bx, by, bw, 18, Component.empty(), () -> act(C2S.HostAction.RESET)).icon(UiKit.Icon.RESET)
                .accent(UiKit.WARN).enabledWhen(this::running).tip(Component.translatable("hypervisorx.focus.reset")));
        bx += bw + 3;
        addRenderableWidget(new UiKit.FlatButton(bx, by, bw, 18, Component.empty(), () -> act(C2S.HostAction.SNAPSHOT)).icon(UiKit.Icon.CAMERA)
                .accent(UiKit.ACCENT).enabledWhen(this::running).tip(Component.translatable("hypervisorx.focus.snapshot")));
        bx += bw + 3;
        addRenderableWidget(new UiKit.FlatButton(bx, by, bw, 18, Component.empty(), () -> act(C2S.HostAction.INSERT_MEDIA)).icon(UiKit.Icon.DISC)
                .accent(UiKit.ACCENT_2).enabledWhen(this::running).tip(Component.translatable("hypervisorx.ui.media")));
        bx += bw + 3;
        addRenderableWidget(new UiKit.FlatButton(bx, by, x + fw - bx, 18, Component.empty(), () -> act(C2S.HostAction.FOCUS)).icon(UiKit.Icon.FOCUS)
                .style(UiKit.Style.PRIMARY).enabledWhen(() -> current() != null && current().vm() != null)
                .tip(Component.translatable("hypervisorx.ui.focus")));
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
                if (e.id().equals(id)) return (e.available() ? "\u2714 " : "\u2718 ") + shortName;
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
        UiKit.section(g, font, x + 6, y + 24, 172, 104, Component.translatable("hypervisorx.ui.hardware"));
        UiKit.section(g, font, x + 6, y + 132, 172, 98, Component.translatable("hypervisorx.ui.inventory"));
        for (int i = 0; i < menu.form().slotCount(); i++) {
            Slot s = menu.slots.get(i);
            ComponentType type = menu.form().slots().get(i);
            int color = 0xFF000000 | (type.color().getColor() == null ? 0xFFFFFF : type.color().getColor());
            UiKit.slot(g, x + s.x, y + s.y, color);
            if (!s.hasItem()) UiKit.slotGhost(g, type, x + s.x, y + s.y);
        }
        for (int i = menu.form().slotCount(); i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            UiKit.slot(g, x + s.x, y + s.y, 0);
        }
        renderSpecs(g, x + 104, y + 38);
        // right: VM settings + power
        UiKit.section(g, font, x + RIGHT_X, y + 24, RIGHT_W, powerY - 26, Component.translatable("hypervisorx.ui.vm"));
        UiKit.section(g, font, x + RIGHT_X, y + powerY, RIGHT_W, H - 6 - powerY, Component.translatable("hypervisorx.ui.power"));
        renderStatus(g, x + RIGHT_X + 6, y + powerY + 14, RIGHT_W - 12, y + H - 30);
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
        int w = 70;
        if (info == null) return;
        int row = 0;
        if (info.rack()) {
            spec(g, UiKit.Icon.CHIP, Component.translatable("hypervisorx.ui.spec.cores", info.cores()).getString(), x, y + row++ * 12, w, UiKit.TEXT);
            spec(g, UiKit.Icon.MEMORY, Component.translatable("hypervisorx.ui.spec.ram", info.ramMb() / 1024).getString(), x, y + row++ * 12, w, UiKit.TEXT);
            spec(g, UiKit.Icon.DRIVE, info.diskGb() + " GB", x, y + row++ * 12, w, UiKit.TEXT);
            spec(g, UiKit.Icon.SERVER, Component.translatable("hypervisorx.ui.pool_free", info.poolCoresFree(), info.poolRamFree() / 1024,
                    info.poolDiskFree()).getString(), x, y + row++ * 12, w, UiKit.ACCENT_2);
        } else {
            spec(g, UiKit.Icon.CHIP, Component.translatable("hypervisorx.ui.spec.cores", info.cores()).getString(), x, y + row++ * 12, w, UiKit.TEXT);
            spec(g, UiKit.Icon.MEMORY, Component.translatable("hypervisorx.ui.spec.ram", info.ramMb() / 1024).getString(), x, y + row++ * 12, w, UiKit.TEXT);
            spec(g, UiKit.Icon.MONITOR, info.width() > 0
                    ? Component.translatable("hypervisorx.ui.spec.display", info.width(), info.height(), info.displays()).getString()
                    : Component.translatable("hypervisorx.ui.spec.nodisplay").getString(), x, y + row++ * 12, w, info.width() > 0 ? UiKit.TEXT : UiKit.WARN);
            spec(g, UiKit.Icon.DRIVE, info.diskGb() > 0 ? HardwareItem.formatCapacity(info.diskGb()) + (info.nvme() ? " NVMe" : "")
                    : Component.translatable("hypervisorx.ui.spec.nodisk").getString(), x, y + row++ * 12, w, info.diskGb() > 0 ? UiKit.TEXT : UiKit.WARN);
            String extras = ((info.gpio() ? "GPIO " : "") + (info.wifi() ? "Wi-Fi" : "")).trim();
            if (!extras.isEmpty()) spec(g, info.wifi() ? UiKit.Icon.WIFI : UiKit.Icon.CHIP, extras, x, y + row++ * 12, w, UiKit.ACCENT_2);
        }
        int py = topPos + 37 + ((menu.form().slotCount() + ComputerMenu.PART_COLS - 1) / ComputerMenu.PART_COLS) * ComputerMenu.PART_SPACING_Y + 2;
        int px = leftPos + 12;
        for (Component p : info.problems()) {
            if (py > topPos + 118) break;
            UiKit.icon(g, UiKit.Icon.WARN, px, py - 2, UiKit.WARN);
            py += 7 * UiKit.paragraph(g, font, p.getString(), px + 14, py, 148, 2, UiKit.WARN, 0.75f) + 3;
        }
    }

    private void spec(GuiGraphics g, UiKit.Icon icon, String text, int x, int y, int w, int color) {
        UiKit.icon(g, icon, x, y - 2, UiKit.DIM);
        UiKit.textFit(g, font, text, x + 15, y + 1, w - 2, color, 0.75f);
    }

    private void renderStatus(GuiGraphics g, int x, int y, int w, int bottom) {
        S2C.VmSlotInfo v = current();
        String state = stateOf(v);
        int color = stateColor(state);
        UiKit.dot(g, x, y + 1, color);
        String head = Component.translatable("hypervisorx.state." + state.toLowerCase(Locale.ROOT)).getString()
                + (v != null && !v.name().isEmpty() ? "  \u00b7  " + v.name() : "");
        UiKit.textFit(g, font, head, x + 9, y, w - 9, color, 1f);
        String msg = v == null ? "" : v.message();
        if (msg.equalsIgnoreCase(state) || msg.equalsIgnoreCase(Component.translatable("hypervisorx.state." + state.toLowerCase(Locale.ROOT)).getString())) msg = "";
        if (!msg.isEmpty() && bottom - (y + 11) >= 7) {
            int lines = Math.max(1, (bottom - (y + 11)) / 7);
            UiKit.paragraph(g, font, msg, x, y + 11, w, lines, UiKit.MUTED, 0.75f);
        }
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
            g.renderTooltip(font, Component.translatable(ps.type().translationKey()), mouseX, mouseY);
        }
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        // don't let the inventory key close the screen while you're typing in a text field
        if ((name != null && name.isFocused()) || (target != null && target.isFocused()) || (trustName != null && trustName.isFocused())) {
            if (key == 256) return super.keyPressed(key, scancode, modifiers);
            return getFocused() != null && getFocused().keyPressed(key, scancode, modifiers) || true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }
}
