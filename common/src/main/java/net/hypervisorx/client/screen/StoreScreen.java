package net.hypervisorx.client.screen;

import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.store.StoreCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OrbitTech Store 2.0: categories, search, spec cards, ready-made bundles and a cart. Orders are
 * delivered by a supply pod falling from orbit.
 */
public class StoreScreen extends Screen {
    private static final int CARD_W = 104;
    private static final int CARD_H = 46;

    private StoreCatalog.Category category = StoreCatalog.Category.BUNDLES;
    private EditBox search;
    private final Map<String, Integer> cart = new LinkedHashMap<>();
    private double scroll;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private Component status = Component.empty();
    private int statusColor = UiKit.MUTED;
    private long statusTime;
    private List<StoreCatalog.Product> visible = new ArrayList<>();

    public StoreScreen() {
        super(Component.translatable("hypervisorx.store.title"));
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 20, 470);
        panelH = Math.min(height - 20, 280);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        search = new EditBox(font, left + 92, top + 24, panelW - 92 - 132, 14, Component.translatable("hypervisorx.store.search"));
        search.setHint(Component.translatable("hypervisorx.store.search"));
        search.setResponder(s -> {
            scroll = 0;
            refresh();
        });
        addRenderableWidget(search);
        int y = top + 24;
        for (StoreCatalog.Category c : StoreCatalog.Category.values()) {
            addRenderableWidget(new UiKit.FlatButton(left + 6, y, 80, 15, Component.translatable("hypervisorx.store.cat." + c.name().toLowerCase(Locale.ROOT)),
                    () -> {
                        category = c;
                        scroll = 0;
                        refresh();
                    }).accent(c == StoreCatalog.Category.BUNDLES ? UiKit.ACCENT_2 : UiKit.ACCENT));
            y += 17;
        }
        addRenderableWidget(new UiKit.FlatButton(left + panelW - 126, top + panelH - 40, 120, 16, Component.translatable("hypervisorx.store.checkout"),
                this::checkout).enabledWhen(() -> !cart.isEmpty()).accent(UiKit.OK));
        addRenderableWidget(new UiKit.FlatButton(left + panelW - 126, top + panelH - 21, 58, 15, Component.translatable("hypervisorx.store.clear"),
                cart::clear).enabledWhen(() -> !cart.isEmpty()).accent(UiKit.ERROR));
        addRenderableWidget(new UiKit.FlatButton(left + panelW - 64, top + panelH - 21, 58, 15, Component.translatable("hypervisorx.ui.library"),
                () -> minecraft.setScreen(new LibraryScreen(this))).accent(UiKit.ACCENT_2));
        refresh();
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        visible = new ArrayList<>();
        for (StoreCatalog.Product p : StoreCatalog.all()) {
            if (!q.isEmpty()) {
                if (!displayName(p).getString().toLowerCase(Locale.ROOT).contains(q) && !p.id().contains(q)) continue;
            } else if (p.category() != category) {
                continue;
            }
            visible.add(p);
        }
    }

    private Component displayName(StoreCatalog.Product p) {
        return p.isBundle() ? Component.translatable("store.hypervisorx.bundle_name." + p.id()) : p.icon().getHoverName();
    }

    private int gridX() {
        return left + 92;
    }

    private int gridY() {
        return top + 42;
    }

    private int gridW() {
        return panelW - 92 - 132;
    }

    private int gridH() {
        return panelH - 48;
    }

    private int columns() {
        return Math.max(1, (gridW() + 4) / (CARD_W + 4));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + panelW, top + panelH, UiKit.BG);
        UiKit.header(g, font, left, top, panelW, title);
        long t = System.currentTimeMillis();
        // little orbiting satellite up in the corner
        int cx = left + panelW - 20;
        int cy = top + 9;
        double a = (t % 6000) / 6000.0 * Math.PI * 2;
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, UiKit.ACCENT);
        int sx = cx + (int) (Math.cos(a) * 8);
        int sy = cy + (int) (Math.sin(a) * 4);
        g.fill(sx - 1, sy, sx + 2, sy + 1, UiKit.WARN);
        g.drawString(font, Component.translatable("hypervisorx.store.satellite"), left + panelW - 160, top + 5, UiKit.MUTED, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderGrid(g, mouseX, mouseY);
        renderCart(g);
        if (System.currentTimeMillis() - statusTime < 8000) {
            g.drawString(font, status, left + 92, top + panelH - 12, statusColor, false);
        }
        StoreCatalog.Product hovered = productAt(mouseX, mouseY);
        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(displayName(hovered));
            if (hovered.isBundle()) {
                tip.add(Component.translatable(hovered.descriptionKey()).withStyle(s -> s.withColor(UiKit.MUTED)));
                for (StoreCatalog.Line l : hovered.contents()) {
                    tip.add(Component.literal(" " + l.count() + "× ").append(new ItemStack(l.item().get()).getHoverName()));
                }
            } else {
                List<Component> lines = hovered.icon().getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL);
                if (lines.size() > 1) tip.addAll(lines.subList(1, lines.size()));
            }
            tip.add(Component.translatable("hypervisorx.store.price", hovered.price()).withStyle(s -> s.withColor(UiKit.WARN)));
            tip.add(Component.translatable("hypervisorx.store.click_hint").withStyle(s -> s.withColor(UiKit.MUTED)));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        int cols = columns();
        int rows = (visible.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, rows * (CARD_H + 4) - gridH());
        scroll = Mth.clamp(scroll, 0, maxScroll);
        g.enableScissor(gridX(), gridY(), gridX() + gridW(), gridY() + gridH());
        for (int i = 0; i < visible.size(); i++) {
            StoreCatalog.Product p = visible.get(i);
            int x = gridX() + (i % cols) * (CARD_W + 4);
            int y = gridY() + (i / cols) * (CARD_H + 4) - (int) scroll;
            if (y + CARD_H < gridY() || y > gridY() + gridH()) continue;
            boolean hover = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H && mouseY >= gridY() && mouseY < gridY() + gridH();
            UiKit.card(g, x, y, CARD_W, CARD_H, hover);
            g.renderItem(p.icon(), x + 4, y + 4);
            g.drawString(font, UiKit.ellipsize(font, displayName(p).getString(), CARD_W - 26), x + 23, y + 5, UiKit.TEXT, false);
            String sub = p.isBundle() ? Component.translatable("hypervisorx.store.items", p.contents().stream().mapToInt(StoreCatalog.Line::count).sum()).getString()
                    : specLine(p.icon());
            g.drawString(font, UiKit.ellipsize(font, sub, CARD_W - 26), x + 23, y + 16, UiKit.MUTED, false);
            g.drawString(font, p.price() + " ¢", x + 4, y + CARD_H - 11, UiKit.WARN, false);
            Integer q = cart.get(p.id());
            if (q != null) g.drawString(font, "×" + q, x + CARD_W - 4 - font.width("×" + q), y + CARD_H - 11, UiKit.OK, false);
        }
        g.disableScissor();
    }

    private String specLine(ItemStack stack) {
        List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL);
        return lines.size() > 2 ? lines.get(2).getString() : lines.size() > 1 ? lines.get(1).getString() : "";
    }

    private void renderCart(GuiGraphics g) {
        int x = left + panelW - 128;
        int y = top + 22;
        UiKit.panel(g, x - 2, y, 126, panelH - 66);
        g.drawString(font, Component.translatable("hypervisorx.store.cart"), x + 3, y + 4, UiKit.ACCENT, false);
        int ly = y + 16;
        long total = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            StoreCatalog.Product p = StoreCatalog.get(e.getKey());
            if (p == null) continue;
            total += (long) p.price() * e.getValue();
            if (ly < y + panelH - 90) {
                g.renderItem(p.icon(), x + 2, ly - 4);
                g.drawString(font, UiKit.ellipsize(font, e.getValue() + "× " + displayName(p).getString(), 100), x + 20, ly, UiKit.TEXT, false);
                ly += 14;
            }
        }
        long credits = minecraft.player == null ? 0 : net.hypervisorx.store.StoreService.credits(minecraft.player.getInventory());
        boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
        g.drawString(font, Component.translatable("hypervisorx.store.total", total), x + 3, top + panelH - 64, UiKit.WARN, false);
        g.drawString(font, creative ? Component.translatable("hypervisorx.store.free_creative")
                : Component.translatable("hypervisorx.store.wallet", credits), x + 3, top + panelH - 54, credits >= total || creative ? UiKit.OK : UiKit.ERROR, false);
    }

    private StoreCatalog.Product productAt(double mx, double my) {
        if (mx < gridX() || mx >= gridX() + gridW() || my < gridY() || my >= gridY() + gridH()) return null;
        int cols = columns();
        int col = (int) ((mx - gridX()) / (CARD_W + 4));
        int row = (int) ((my - gridY() + scroll) / (CARD_H + 4));
        if (col >= cols) return null;
        int lx = (int) ((mx - gridX()) % (CARD_W + 4));
        int ly = (int) ((my - gridY() + scroll) % (CARD_H + 4));
        if (lx >= CARD_W || ly >= CARD_H) return null;
        int idx = row * cols + col;
        return idx >= 0 && idx < visible.size() ? visible.get(idx) : null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        StoreCatalog.Product p = productAt(mx, my);
        if (p != null) {
            int q = cart.getOrDefault(p.id(), 0);
            if (button == 1) q = Math.max(0, q - 1);
            else q = Math.min(64, q + (hasShiftDown() ? 8 : 1));
            if (q == 0) cart.remove(p.id());
            else cart.put(p.id(), q);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 20;
        return true;
    }

    private void checkout() {
        if (cart.isEmpty()) return;
        Services.PLATFORM.sendToServer(new C2S.StoreOrder(new ArrayList<>(cart.keySet()), new ArrayList<>(cart.values())));
        setStatus(Component.translatable("hypervisorx.store.sending"), UiKit.MUTED);
    }

    private void setStatus(Component c, int color) {
        status = c;
        statusColor = color;
        statusTime = System.currentTimeMillis();
    }

    public void onOrderResult(S2C.OrderResult r) {
        setStatus(r.message(), r.ok() ? UiKit.OK : UiKit.ERROR);
        if (r.ok()) cart.clear();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
