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
    private static final int RIGHT_X = 178;
    private static final int RIGHT_W = 156;
    private static final List<String> BACKENDS = List.of("auto", "vmware", "virtualbox", "qemu", "embedded", "hostcapture");

    private S2C.HostInfo info;
    private int statusY = 170;
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
        imageWidth = 340;
        imageHeight = 228;
        inventoryLabelY = ComputerMenu.INV_Y - 11;
        titleLabelY = 5;
    }

    private boolean rack() {
        return menu.form() == FormFactor.RACK;
    }

    @Override
    protected void init() {
        super.init();
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(C2S.LibraryAction.REFRESH, ""));
        int x = leftPos + RIGHT_X;
        int y = topPos + 22;
        int half = (RIGHT_W - 3) / 2;
        if (rack()) {
            // VM slot tabs live in the header next to the "System" label
            for (int i = 0; i < 4; i++) {
                final int s = i;
                addRenderableWidget(new UiKit.FlatButton(x + 44 + i * 22, topPos + 3, 20, 12, Component.literal("" + (i + 1)), () -> selectSlot(s))
                        .enabledWhen(() -> slotInfo(s) != null).accent(UiKit.ACCENT_2));
            }
            addRenderableWidget(new UiKit.FlatButton(x + 44 + 4 * 22, topPos + 3, 24, 12, Component.literal("+"),
                    () -> act(C2S.HostAction.ADD_VM)).accent(UiKit.OK));
        }
        name = new EditBox(font, x + 1, y, RIGHT_W - 2, 14, Component.translatable("hypervisorx.ui.name"));
        name.setMaxLength(48);
        name.setResponder(s -> fieldsDirty = true);
        addRenderableWidget(name);
        y += 16;
        backend = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, RIGHT_W, 15, "", BACKENDS, "auto", this::backendLabel, v -> fieldsDirty = true));
        y += 16;
        guestOs = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, "", Arrays.asList(GuestOs.values()), GuestOs.LINUX,
                GuestOs::displayName, v -> fieldsDirty = true));
        efi = addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, "", List.of(false, true), false,
                v -> v ? "UEFI" : "BIOS", v -> fieldsDirty = true));
        y += 16;
        image = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, RIGHT_W - 44, 15, "", new ArrayList<>(List.of("")), "",
                v -> v.isEmpty() ? Component.translatable("hypervisorx.ui.no_image").getString() : v, v -> fieldsDirty = true));
        addRenderableWidget(new UiKit.FlatButton(x + RIGHT_W - 42, y, 42, 15, Component.translatable("hypervisorx.ui.library"),
                () -> minecraft.setScreen(new LibraryScreen(this))).accent(UiKit.ACCENT_2));
        y += 16;
        target = new EditBox(font, x + 1, y, RIGHT_W - 2, 14, Component.translatable("hypervisorx.ui.target"));
        target.setMaxLength(200);
        target.setHint(Component.translatable("hypervisorx.ui.target_hint"));
        target.setResponder(s -> fieldsDirty = true);
        addRenderableWidget(target);
        y += 16;
        access = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, half, 15, "", Arrays.asList(VmRecord.Access.values()), VmRecord.Access.PRIVATE,
                v -> Component.translatable("hypervisorx.access." + v.name().toLowerCase(Locale.ROOT)).getString(), v -> fieldsDirty = true));
        span = addRenderableWidget(new UiKit.ChoiceButton<>(x + half + 3, y, half, 15, "", List.of(1, 2, 3), 1,
                v -> Component.translatable("hypervisorx.ui.span", v).getString(), v -> fieldsDirty = true));
        y += 16;
        if (rack()) {
            int third = (RIGHT_W - 6) / 3;
            cores = addRenderableWidget(new UiKit.ChoiceButton<>(x, y, third, 15, "", List.of(1, 2, 4, 6, 8, 12, 16, 24, 32), 2,
                    v -> v + " CPU", v -> fieldsDirty = true));
            ram = addRenderableWidget(new UiKit.ChoiceButton<>(x + third + 3, y, third, 15, "", List.of(1024, 2048, 4096, 8192, 16384, 32768, 65536), 4096,
                    v -> v / 1024 + " GB", v -> fieldsDirty = true));
            disk = addRenderableWidget(new UiKit.ChoiceButton<>(x + 2 * (third + 3), y, third, 15, "", List.of(16, 32, 64, 128, 256, 512, 1024, 2048), 64,
                    v -> v + " GB", v -> fieldsDirty = true));
            y += 16;
        }
        trustName = new EditBox(font, x + 1, y, RIGHT_W - 46, 14, Component.translatable("hypervisorx.ui.trust"));
        trustName.setMaxLength(16);
        trustName.setHint(Component.translatable("hypervisorx.ui.trust_hint"));
        addRenderableWidget(trustName);
        addRenderableWidget(new UiKit.FlatButton(x + RIGHT_W - 42, y, 20, 14, Component.literal("+"), () -> trust(true)).accent(UiKit.OK));
        addRenderableWidget(new UiKit.FlatButton(x + RIGHT_W - 20, y, 20, 14, Component.literal("-"), () -> trust(false)).accent(UiKit.ERROR));
        y += 16;
        statusY = y + 19 - topPos;
        addRenderableWidget(new UiKit.FlatButton(x, y, RIGHT_W, 15, Component.translatable("hypervisorx.ui.save"), this::save)
                .label(() -> Component.translatable(fieldsDirty ? "hypervisorx.ui.save_pending" : "hypervisorx.ui.save")).accent(UiKit.ACCENT));

        int by = topPos + imageHeight - 37;
        int third = (RIGHT_W - 6) / 3;
        addRenderableWidget(new UiKit.FlatButton(x, by, third, 16, Component.empty(), () -> act(running() ? C2S.HostAction.SHUTDOWN : C2S.HostAction.POWER_ON))
                .label(() -> Component.translatable(running() ? "hypervisorx.focus.shutdown" : "hypervisorx.focus.power_on")).accent(UiKit.OK));
        addRenderableWidget(new UiKit.FlatButton(x + third + 3, by, third, 16, Component.translatable("hypervisorx.focus.force_off"),
                () -> act(C2S.HostAction.FORCE_OFF)).enabledWhen(this::running).accent(UiKit.ERROR));
        addRenderableWidget(new UiKit.FlatButton(x + 2 * (third + 3), by, third, 16, Component.translatable("hypervisorx.focus.reset"),
                () -> act(C2S.HostAction.RESET)).enabledWhen(this::running).accent(UiKit.WARN));
        by += 19;
        addRenderableWidget(new UiKit.FlatButton(x, by, third, 16, Component.translatable("hypervisorx.ui.focus"), () -> act(C2S.HostAction.FOCUS))
                .enabledWhen(() -> current() != null && current().vm() != null));
        addRenderableWidget(new UiKit.FlatButton(x + third + 3, by, third, 16, Component.translatable("hypervisorx.focus.snapshot"),
                () -> act(C2S.HostAction.SNAPSHOT)).enabledWhen(this::running).accent(UiKit.ACCENT_2));
        addRenderableWidget(new UiKit.FlatButton(x + 2 * (third + 3), by, third, 16, Component.translatable("hypervisorx.ui.media"),
                () -> act(C2S.HostAction.INSERT_MEDIA)).enabledWhen(this::running));
        if (rack()) {
            addRenderableWidget(new UiKit.FlatButton(leftPos + 116, topPos + 4, 56, 12, Component.translatable("hypervisorx.ui.delete_vm"),
                    () -> act(C2S.HostAction.DELETE_VM)).enabledWhen(() -> current() != null && current().vm() != null && !running()).accent(UiKit.ERROR));
        }
        S2C.HostInfo cached = ClientVmCache.hostInfo();
        if (cached != null && cached.pos().equals(menu.pos())) onHostInfo(cached);
    }

    private String backendLabel(String id) {
        if (id.equals("auto")) return Component.translatable("hypervisorx.ui.backend_auto").getString();
        S2C.Library lib = ClientVmCache.library();
        if (lib != null) {
            for (S2C.BackendEntry e : lib.backends()) {
                if (e.id().equals(id)) return (e.available() ? "✔ " : "✘ ") + e.name();
            }
        }
        return id;
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
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, UiKit.BG);
        UiKit.header(g, font, x, y, imageWidth, Component.empty());
        UiKit.panel(g, x + RIGHT_X - 4, y + 19, RIGHT_W + 8, imageHeight - 22);
        // typed slot frames
        for (int i = 0; i < menu.form().slotCount(); i++) {
            Slot s = menu.slots.get(i);
            ComponentType type = menu.form().slots().get(i);
            int sx = x + s.x - 1;
            int sy = y + s.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, 0xFF0B0E13);
            int color = 0xFF000000 | (type.color().getColor() == null ? 0xFFFFFF : type.color().getColor());
            g.fill(sx, sy + 17, sx + 18, sy + 18, color);
            if (!s.hasItem()) {
                String label = switch (type) {
                    case CPU -> "CPU";
                    case RAM -> "RAM";
                    case GPU -> "GPU";
                    case STORAGE -> "DSK";
                    case EXPANSION -> "PCIe";
                    case USB -> "USB";
                    case BLADE -> "BLD";
                };
                g.pose().pushPose();
                g.pose().translate(sx + 9, sy + 6, 0);
                g.pose().scale(0.6f, 0.6f, 1f);
                g.drawCenteredString(font, label, 0, 0, 0xFF5D6778);
                g.pose().popPose();
            }
        }
        // player inventory backdrop
        for (int i = menu.form().slotCount(); i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            g.fill(x + s.x - 1, y + s.y - 1, x + s.x + 17, y + s.y + 17, 0xFF0B0E13);
        }
        renderSpecs(g, x + 10, y + 32 + ((menu.form().slotCount() + ComputerMenu.PART_COLS - 1) / ComputerMenu.PART_COLS) * ComputerMenu.PART_SPACING_Y);
        renderStatus(g, x + RIGHT_X, y + statusY);
    }

    private void renderSpecs(GuiGraphics g, int x, int y) {
        if (info == null) return;
        String l1 = info.rack()
                ? Component.translatable("hypervisorx.ui.pool", info.cores(), info.ramMb() / 1024, info.diskGb()).getString()
                : Component.translatable("hypervisorx.ui.specs", info.cores(), info.ramMb() / 1024).getString();
        g.drawString(font, l1, x, y, UiKit.TEXT, false);
        String l2 = info.width() > 0 ? info.width() + "x" + info.height() + "  ×" + info.displays() : "-";
        l2 += "  " + (info.diskGb() > 0 ? HardwareItem.formatCapacity(info.diskGb()) + (info.nvme() ? " NVMe" : "") : "no disk");
        g.drawString(font, l2, x, y + 10, UiKit.MUTED, false);
        String l3 = (info.gpio() ? "GPIO  " : "") + (info.wifi() ? "Wi-Fi" : "");
        if (info.rack()) l3 = Component.translatable("hypervisorx.ui.pool_free", info.poolCoresFree(), info.poolRamFree() / 1024, info.poolDiskFree()).getString();
        g.drawString(font, l3, x, y + 20, UiKit.ACCENT, false);
        int py = y + 30;
        for (Component p : info.problems()) {
            if (py > topPos + ComputerMenu.INV_Y - 14) break;
            g.drawString(font, "⚠ " + p.getString(), x, py, UiKit.WARN, false);
            py += 10;
        }
    }

    private void renderStatus(GuiGraphics g, int x, int y) {
        S2C.VmSlotInfo v = current();
        String state = v == null ? "STOPPED" : v.state();
        int color = switch (state) {
            case "RUNNING" -> UiKit.OK;
            case "STARTING", "STOPPING" -> UiKit.WARN;
            case "ERROR" -> UiKit.ERROR;
            default -> UiKit.MUTED;
        };
        UiKit.dot(g, x, y + 2, color);
        g.drawString(font, Component.translatable("hypervisorx.state." + state.toLowerCase(Locale.ROOT)), x + 8, y, color, false);
        if (v != null && !v.message().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(v.message()), RIGHT_W);
            int maxLines = rack() ? 1 : 2;
            for (int i = 0; i < Math.min(maxLines, lines.size()); i++) g.drawString(font, lines.get(i), x, y + 10 + i * 9, UiKit.MUTED, false);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 6, 5, UiKit.TEXT, false);
        g.drawString(font, playerInventoryTitle, ComputerMenu.INV_X, inventoryLabelY, UiKit.MUTED, false);
        g.drawString(font, Component.translatable("hypervisorx.ui.system"), RIGHT_X, 5, UiKit.ACCENT, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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
