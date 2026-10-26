package net.hypervisorx.client.screen;

import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.network.C2S;
import net.hypervisorx.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * In-game file browser (singleplayer host) for picking .iso/.img/.vmdk/.vdi/.qcow2/.vhd(x)
 * images off this PC, plus a native OS file dialog. Files get used in place, never copied.
 */
public class FileBrowserScreen extends Screen {
    private final Screen parent;
    private Path dir;
    private final List<Path> entries = new ArrayList<>();
    private double scroll;
    private int selected = -1;
    private EditBox pathBox;
    private int left;
    private int top;
    private int w;
    private int h;
    private String error = "";

    public FileBrowserScreen(Screen parent) {
        super(Component.translatable("hypervisorx.files.title"));
        this.parent = parent;
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        this.dir = Files.isDirectory(downloads) ? downloads : Path.of(System.getProperty("user.home"));
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 420);
        h = Math.min(height - 20, 250);
        left = (width - w) / 2;
        top = (height - h) / 2;
        pathBox = new EditBox(font, left + 6, top + 22, w - 76, 14, Component.translatable("hypervisorx.files.path"));
        pathBox.setMaxLength(1024);
        pathBox.setValue(dir.toString());
        addRenderableWidget(pathBox);
        addRenderableWidget(new UiKit.FlatButton(left + w - 66, top + 22, 60, 14, Component.translatable("hypervisorx.files.go"), () -> {
            Path p = Path.of(pathBox.getValue().trim());
            if (Files.isDirectory(p)) open(p);
            else if (Files.isRegularFile(p)) importFile(p);
        }));
        addRenderableWidget(new UiKit.FlatButton(left + 6, top + h - 21, 60, 15, Component.literal("↑ .."), () -> {
            if (dir.getParent() != null) open(dir.getParent());
        }));
        addRenderableWidget(new UiKit.FlatButton(left + 70, top + h - 21, 80, 15, Component.translatable("hypervisorx.files.drives"), this::showRoots));
        addRenderableWidget(new UiKit.FlatButton(left + 154, top + h - 21, 110, 15, Component.translatable("hypervisorx.files.native"), this::nativeDialog)
                .accent(UiKit.ACCENT_2));
        addRenderableWidget(new UiKit.FlatButton(left + w - 136, top + h - 21, 64, 15, Component.translatable("hypervisorx.files.import"), () -> {
            if (selected >= 0 && selected < entries.size() && Files.isRegularFile(entries.get(selected))) importFile(entries.get(selected));
        }).enabledWhen(() -> selected >= 0 && selected < entries.size() && Files.isRegularFile(entries.get(selected))).accent(UiKit.OK));
        addRenderableWidget(new UiKit.FlatButton(left + w - 68, top + h - 21, 62, 15, Component.translatable("gui.back"), this::onClose));
        open(dir);
    }

    private void open(Path p) {
        dir = p.toAbsolutePath().normalize();
        if (pathBox != null) pathBox.setValue(dir.toString());
        entries.clear();
        selected = -1;
        scroll = 0;
        error = "";
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path e : ds) {
                try {
                    if (Files.isHidden(e)) continue;
                } catch (IOException ignored) {
                }
                if (Files.isDirectory(e)) dirs.add(e);
                else if (ImageLibrary.EXTENSIONS.contains(ImageLibrary.extension(e.getFileName().toString()))) files.add(e);
            }
        } catch (IOException | SecurityException ex) {
            error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
        Comparator<Path> byName = Comparator.comparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT));
        dirs.sort(byName);
        files.sort(byName);
        entries.addAll(dirs);
        entries.addAll(files);
    }

    private void showRoots() {
        entries.clear();
        selected = -1;
        scroll = 0;
        for (File root : File.listRoots()) entries.add(root.toPath());
        pathBox.setValue("");
    }

    private void nativeDialog() {
        Thread t = new Thread(() -> {
            String result;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(ImageLibrary.EXTENSIONS.size());
                for (String ext : ImageLibrary.EXTENSIONS) filters.put(stack.UTF8("*." + ext));
                filters.flip();
                result = TinyFileDialogs.tinyfd_openFileDialog("HyperVisor X - choose an OS image", dir.toString() + File.separator,
                        filters, "Disk & CD images", false);
            }
            if (result != null) {
                String chosen = result;
                minecraft.execute(() -> importFile(Path.of(chosen)));
            }
        }, "HyperVisorX-FileDialog");
        t.setDaemon(true);
        t.start();
    }

    private void importFile(Path p) {
        Services.PLATFORM.sendToServer(new C2S.LibraryAction(C2S.LibraryAction.IMPORT, p.toAbsolutePath().toString()));
        onClose();
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
        int listTop = top + 40;
        int listBottom = top + h - 26;
        int rowH = 12;
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() * rowH - (listBottom - listTop)));
        g.enableScissor(left + 4, listTop, left + w - 4, listBottom);
        for (int i = 0; i < entries.size(); i++) {
            int y = listTop + i * rowH - (int) scroll;
            if (y + rowH < listTop || y > listBottom) continue;
            Path p = entries.get(i);
            boolean isDir = Files.isDirectory(p);
            boolean hover = mouseX >= left + 6 && mouseX < left + w - 6 && mouseY >= y && mouseY < y + rowH && mouseY >= listTop && mouseY < listBottom;
            if (i == selected) g.fill(left + 6, y, left + w - 6, y + rowH, 0xFF243044);
            else if (hover) g.fill(left + 6, y, left + w - 6, y + rowH, 0xFF1C2330);
            String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
            g.drawString(font, (isDir ? "▸ " : "● ") + UiKit.ellipsize(font, name, w - 120), left + 10, y + 2, isDir ? UiKit.ACCENT : UiKit.TEXT, false);
            if (!isDir) {
                try {
                    g.drawString(font, UiKit.bytes(Files.size(p)), left + w - 80, y + 2, UiKit.MUTED, false);
                } catch (IOException ignored) {
                }
            }
        }
        if (entries.isEmpty()) {
            g.drawString(font, error.isEmpty() ? Component.translatable("hypervisorx.files.empty") : Component.literal(error), left + 10, listTop + 4, UiKit.MUTED, false);
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int listTop = top + 40;
        int listBottom = top + h - 26;
        if (mx >= left + 6 && mx < left + w - 6 && my >= listTop && my < listBottom) {
            int i = (int) ((my - listTop + scroll) / 12);
            if (i >= 0 && i < entries.size()) {
                if (i == selected || Files.isDirectory(entries.get(i))) {
                    Path p = entries.get(i);
                    if (Files.isDirectory(p)) open(p);
                    else importFile(p);
                } else {
                    selected = i;
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 24;
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
