package net.hypervisorx.client.screen;

import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.network.C2S;
import net.hypervisorx.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final int ROW_H = 14;

    private final Screen parent;
    private Path dir;
    private final List<Path> entries = new ArrayList<>();
    private final List<Long> sizes = new ArrayList<>();
    private final UiKit.Scroll scroll = new UiKit.Scroll();
    private int selected = -1;
    private UiKit.Field pathBox;
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
        w = Math.min(width - 16, Mth.clamp((int) (width * 0.82), 320, 640));
        h = Math.min(height - 16, Mth.clamp((int) (height * 0.86), 220, 380));
        left = (width - w) / 2;
        top = (height - h) / 2;
        pathBox = new UiKit.Field(font, left + 6, top + 25, w - 12 - 64, 16, Component.translatable("hypervisorx.files.path"),
                Component.translatable("hypervisorx.files.path").getString());
        pathBox.setMaxLength(1024);
        pathBox.setValue(dir.toString());
        addRenderableWidget(pathBox);
        addRenderableWidget(new UiKit.FlatButton(left + w - 62, top + 25, 56, 16, Component.translatable("hypervisorx.files.go"), () -> {
            Path p = Path.of(pathBox.getValue().trim());
            if (Files.isDirectory(p)) open(p);
            else if (Files.isRegularFile(p)) importFile(p);
        }).icon(UiKit.Icon.CHEVRON_RIGHT));
        // bottom bar: nav on the left, actions on the right
        int by = top + h - 22;
        int bw = Math.max(52, (w - 12 - 4 * 4) / 5);
        int x = left + 6;
        addRenderableWidget(new UiKit.FlatButton(x, by, bw, 16, Component.translatable("hypervisorx.files.up"), () -> {
            if (dir.getParent() != null) open(dir.getParent());
        }).icon(UiKit.Icon.BACK));
        x += bw + 4;
        addRenderableWidget(new UiKit.FlatButton(x, by, bw, 16, Component.translatable("hypervisorx.files.drives"), this::showRoots).icon(UiKit.Icon.DRIVE));
        x += bw + 4;
        addRenderableWidget(new UiKit.FlatButton(x, by, bw, 16, Component.translatable("hypervisorx.files.native"), this::nativeDialog)
                .icon(UiKit.Icon.FOLDER).accent(UiKit.ACCENT_2));
        x += bw + 4;
        addRenderableWidget(new UiKit.FlatButton(x, by, bw, 16, Component.translatable("hypervisorx.files.import"), () -> {
            if (selected >= 0 && selected < entries.size() && Files.isRegularFile(entries.get(selected))) importFile(entries.get(selected));
        }).enabledWhen(() -> selected >= 0 && selected < entries.size() && Files.isRegularFile(entries.get(selected)))
                .icon(UiKit.Icon.CHECK).style(UiKit.Style.PRIMARY));
        x += bw + 4;
        addRenderableWidget(new UiKit.FlatButton(x, by, left + w - 6 - x, 16, Component.translatable("gui.back"), this::onClose).icon(UiKit.Icon.CLOSE));
        open(dir);
    }

    private void open(Path p) {
        dir = p.toAbsolutePath().normalize();
        if (pathBox != null) pathBox.setValue(dir.toString());
        entries.clear();
        sizes.clear();
        selected = -1;
        scroll.reset();
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
        // grab sizes once per dir, not every frame
        for (Path e : entries) {
            long size = -1;
            if (!Files.isDirectory(e)) {
                try {
                    size = Files.size(e);
                } catch (IOException ignored) {
                }
            }
            sizes.add(size);
        }
    }

    private void showRoots() {
        entries.clear();
        sizes.clear();
        selected = -1;
        scroll.reset();
        for (File root : File.listRoots()) {
            entries.add(root.toPath());
            sizes.add(-1L);
        }
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

    private int listTop() {
        return top + 60;
    }

    private int listBottom() {
        return top + h - 28;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        UiKit.window(g, font, left, top, w, h, UiKit.Icon.FOLDER, title, null);
        UiKit.panel(g, left + 6, top + 45, w - 12, listBottom() - top - 43);
        // column headers
        String name = Component.translatable("hypervisorx.files.name").getString().toUpperCase(Locale.ROOT);
        String size = Component.translatable("hypervisorx.files.size").getString().toUpperCase(Locale.ROOT);
        boolean r = UiKit.rtl();
        UiKit.text(g, font, name, r ? left + w - 28 - UiKit.width(font, name, 0.75f) : left + 28, top + 49, UiKit.MUTED, 0.75f);
        UiKit.text(g, font, size, r ? left + 16 : left + w - 16 - UiKit.width(font, size, 0.75f), top + 49, UiKit.MUTED, 0.75f);
        g.fill(left + 7, top + 58, left + w - 7, top + 59, UiKit.STROKE_SOFT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int lt = listTop(), lb = listBottom();
        int max = Math.max(0, entries.size() * ROW_H - (lb - lt));
        scroll.update(max);
        boolean r = UiKit.rtl();
        g.enableScissor(left + 7, lt, left + w - 7, lb);
        for (int i = 0; i < entries.size(); i++) {
            int y = lt + i * ROW_H - scroll.px();
            if (y + ROW_H < lt || y > lb) continue;
            Path p = entries.get(i);
            boolean isDir = sizes.get(i) < 0;
            boolean hover = mouseX >= left + 8 && mouseX < left + w - 8 && mouseY >= y && mouseY < y + ROW_H && mouseY >= lt && mouseY < lb;
            if (i == selected) UiKit.round(g, left + 8, y, w - 16 - 5, ROW_H, UiKit.lerp(UiKit.CARD, UiKit.ACCENT, 0.3f));
            else if (hover) UiKit.round(g, left + 8, y, w - 16 - 5, ROW_H, UiKit.CARD_HOVER);
            String n = p.getFileName() == null ? p.toString() : p.getFileName().toString();
            UiKit.icon(g, isDir ? UiKit.Icon.FOLDER : UiKit.Icon.DISC, r ? left + w - 26 : left + 12, y + 1, isDir ? UiKit.ACCENT : UiKit.ACCENT_2);
            UiKit.textFit(g, font, n, r ? left + 76 : left + 28, y + 3, w - 110, isDir ? UiKit.TEXT : UiKit.TEXT, 1f);
            if (!isDir) {
                String s = UiKit.bytes(sizes.get(i));
                float sw = UiKit.width(font, s, 0.75f);
                UiKit.text(g, font, s, r ? left + 16 : left + w - 16 - 5 - sw, y + 4, UiKit.INFO, 0.75f);
            }
        }
        if (entries.isEmpty()) {
            UiKit.icon(g, UiKit.Icon.FOLDER, left + w / 2 - 6, lt + 10, UiKit.withAlpha(UiKit.INFO, 0x66));
            String msg = error.isEmpty() ? Component.translatable("hypervisorx.files.empty").getString() : error;
            UiKit.paragraph(g, font, msg, left + 20, lt + 28, w - 40, 3, error.isEmpty() ? UiKit.INFO : UiKit.ERROR, 0.75f);
        }
        g.disableScissor();
        UiKit.scrollbar(g, left + w - 11, lt, lb - lt, scroll.px(), max, lb - lt, entries.size() * ROW_H);
        UiKit.renderOverlays(g, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (UiKit.popoverClicked(mx, my, button)) return true;
        int lt = listTop(), lb = listBottom();
        if (mx >= left + 8 && mx < left + w - 8 && my >= lt && my < lb) {
            int i = (int) ((my - lt + scroll.px()) / ROW_H);
            if (i >= 0 && i < entries.size()) {
                if (i == selected || sizes.get(i) < 0) {
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
        scroll.scrollBy(sy, ROW_H * 3);
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
