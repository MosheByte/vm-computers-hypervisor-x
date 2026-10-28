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
    private static final int CARD_H = 44;
    private static final int SIDE_W = 92;
    private static final int CART_W = 124;

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
        panelW = Math.min(width - 16, 476);
        panelH = Math.min(height - 16, 282);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        search = new UiKit.Field(font, gridX(), top + 25, gridW(), 15, Component.translatable("hypervisorx.store.search"));
        search.setHint(Component.translatable("hypervisorx.store.search"));
        search.setResponder(s -> {
            scroll = 0;
            refresh();
        });
        addRenderableWidget(search);
        int y = top + 25;
        int rowH = Math.max(14, Math.min(19, (panelH - 32) / StoreCatalog.Category.values().length));
        for (StoreCatalog.Category c : StoreCatalog.Category.values()) {
            addRenderableWidget(new UiKit.FlatButton(left + 6, y, SIDE_W - 6, rowH - 2,
                    Component.translatable("hypervisorx.store.cat." + c.name().toLowerCase(Locale.ROOT)), () -> {
                        category = c;
                        scroll = 0;
                        if (search != null) search.setValue("");
                        refresh();
                    }).icon(categoryIcon(c)).style(UiKit.Style.GHOST).selectedWhen(() -> category == c && (search == null || search.getValue().isEmpty()))
                    .accent(c == StoreCatalog.Category.BUNDLES ? UiKit.ACCENT_2 : UiKit.ACCENT));
            y += rowH;
        }
        int cx = left + panelW - CART_W - 6;
        addRenderableWidget(new UiKit.FlatButton(cx, top + panelH - 42, CART_W, 18, Component.translatable("hypervisorx.store.checkout"),
                this::checkout).enabledWhen(() -> !cart.isEmpty()).icon(UiKit.Icon.ROCKET).style(UiKit.Style.PRIMARY));
        addRenderableWidget(new UiKit.FlatButton(cx, top + panelH - 21, CART_W / 2 - 2, 15, Component.translatable("hypervisorx.store.clear"),
                cart::clear).enabledWhen(() -> !cart.isEmpty()).icon(UiKit.Icon.TRASH).style(UiKit.Style.DANGER));
        addRenderableWidget(new UiKit.FlatButton(cx + CART_W / 2 + 1, top + panelH - 21, CART_W - CART_W / 2 - 1, 15, Component.translatable("hypervisorx.ui.library"),
                () -> minecraft.setScreen(new LibraryScreen(this))).icon(UiKit.Icon.LIBRARY).accent(UiKit.ACCENT_2));
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

    private static UiKit.Icon categoryIcon(StoreCatalog.Category c) {
        return switch (c) {
            case BUNDLES -> UiKit.Icon.BOX;
            case CPU -> UiKit.Icon.CHIP;
            case MEMORY -> UiKit.Icon.MEMORY;
            case GRAPHICS -> UiKit.Icon.EYE;
            case STORAGE -> UiKit.Icon.DRIVE;
            case EXPANSION -> UiKit.Icon.WIFI;
            case CASES -> UiKit.Icon.SERVER;
            case DISPLAYS -> UiKit.Icon.MONITOR;
            case LAPTOPS -> UiKit.Icon.FOCUS;
            case PERIPHERALS -> UiKit.Icon.KEYBOARD;
        };
    }

    private int gridX() {
        return left + SIDE_W + 6;
    }

    private int gridY() {
        return top + 45;
    }

    private int gridW() {
        return panelW - SIDE_W - CART_W - 18;
    }

    private int gridH() {
        return panelH - 64;
    }

    private int columns() {
        return Math.max(1, (gridW() + 4) / (CARD_W + 4));
    }

    private int cardW() {
        int cols = columns();
        return (gridW() - (cols - 1) * 4) / cols;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        UiKit.window(g, font, left, top, panelW, panelH, UiKit.Icon.CART, title, null);
        long t = System.currentTimeMillis();
        // little orbiting satellite up in the corner
        int cx = left + panelW - 16;
        int cy = top + 10;
        double a = (t % 6000) / 6000.0 * Math.PI * 2;
        UiKit.dot(g, cx - 2, cy - 2, UiKit.ACCENT_2);
        int sx = cx + (int) (Math.cos(a) * 8);
        int sy = cy + (int) (Math.sin(a) * 4);
        g.fill(sx - 1, sy, sx + 2, sy + 1, UiKit.WARN);
        String sat = Component.translatable("hypervisorx.store.satellite").getString();
        UiKit.text(g, font, sat, cx - 14 - font.width(sat) * 0.75f, top + 7, UiKit.MUTED, 0.75f);
        UiKit.section(g, font, left + panelW - CART_W - 12, top + 24, CART_W + 12, panelH - 72, Component.translatable("hypervisorx.store.cart"));
        UiKit.icon(g, UiKit.Icon.SEARCH, gridX() + gridW() - 16, top + 26, UiKit.DIM);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderGrid(g, mouseX, mouseY);
        renderCart(g);
        if (System.currentTimeMillis() - statusTime < 8000) {
            UiKit.textFit(g, font, status.getString(), gridX(), top + panelH - 13, gridW(), statusColor, 0.75f);
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
        int cw = cardW();
        for (int i = 0; i < visible.size(); i++) {
            StoreCatalog.Product p = visible.get(i);
            int x = gridX() + (i % cols) * (cw + 4);
            int y = gridY() + (i / cols) * (CARD_H + 4) - (int) scroll;
            if (y + CARD_H < gridY() || y > gridY() + gridH()) continue;
            boolean hover = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + CARD_H && mouseY >= gridY() && mouseY < gridY() + gridH();
            Integer q = cart.get(p.id());
            UiKit.card(g, x, y, cw, CARD_H, hover);
            if (q != null) UiKit.stroke(g, x, y, cw, CARD_H, UiKit.withAlpha(UiKit.OK, 0xB0));
            // lighter well behind the icon so dark stuff (tower, rack, laptop) doesn't vanish
            UiKit.round(g, x + 4, y + 4, 22, 22, 0xFF3A414D);
            UiKit.round(g, x + 5, y + 5, 20, 20, 0xFF2A303A);
            g.renderItem(p.icon(), x + 7, y + 7);
            UiKit.paragraph(g, font, displayName(p).getString(), x + 30, y + 5, cw - 34, 2, UiKit.TEXT, 0.75f);
            String sub = p.isBundle() ? Component.translatable("hypervisorx.store.items", p.contents().stream().mapToInt(StoreCatalog.Line::count).sum()).getString()
                    : specLine(p.icon());
            UiKit.textFit(g, font, sub, x + 30, y + 21, cw - 34, UiKit.MUTED, 0.75f);
            UiKit.icon(g, UiKit.Icon.COIN, x + 5, y + CARD_H - 13, UiKit.WARN);
            UiKit.textFit(g, font, String.valueOf(p.price()), x + 19, y + CARD_H - 11, cw / 2, UiKit.WARN, 1f);
            if (q != null) UiKit.pill(g, font, x + cw - 4, y + CARD_H - 14, "\u00d7" + q, UiKit.OK);
        }
        g.disableScissor();
    }

    private String specLine(ItemStack stack) {
        List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL);
        return lines.size() > 2 ? lines.get(2).getString() : lines.size() > 1 ? lines.get(1).getString() : "";
    }

    private void renderCart(GuiGraphics g) {
        int x = left + panelW - CART_W - 6;
        int y = top + 24;
        int ly = y + 16;
        long total = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            StoreCatalog.Product p = StoreCatalog.get(e.getKey());
            if (p == null) continue;
            total += (long) p.price() * e.getValue();
            if (ly < y + panelH - 112) {
                g.renderItem(p.icon(), x + 1, ly - 3);
                UiKit.textFit(g, font, displayName(p).getString(), x + 20, ly - 1, CART_W - 46, UiKit.TEXT, 0.75f);
                UiKit.textFit(g, font, "\u00d7" + e.getValue() + "  \u00b7  " + (long) p.price() * e.getValue() + " cr", x + 20, ly + 6, CART_W - 22, UiKit.MUTED, 0.75f);
                ly += 18;
            }
        }
        long credits = minecraft.player == null ? 0 : net.hypervisorx.store.StoreService.credits(minecraft.player.getInventory());
        boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
        int by = top + panelH - 70;
        g.fill(x, by - 4, x + CART_W, by - 3, UiKit.STROKE_SOFT);
        UiKit.textFit(g, font, Component.translatable("hypervisorx.store.total", total).getString(), x + 2, by, CART_W - 4, UiKit.WARN, 1f);
        UiKit.textFit(g, font, (creative ? Component.translatable("hypervisorx.store.free_creative")
                : Component.translatable("hypervisorx.store.wallet", credits)).getString(), x + 2, by + 11, CART_W - 4,
                credits >= total || creative ? UiKit.OK : UiKit.ERROR, 0.75f);
    }

    private StoreCatalog.Product productAt(double mx, double my) {
        if (mx < gridX() || mx >= gridX() + gridW() || my < gridY() || my >= gridY() + gridH()) return null;
        int cols = columns();
        int cw = cardW();
        int col = (int) ((mx - gridX()) / (cw + 4));
        int row = (int) ((my - gridY() + scroll) / (CARD_H + 4));
        if (col >= cols) return null;
        int lx = (int) ((mx - gridX()) % (cw + 4));
        int ly = (int) ((my - gridY() + scroll) % (CARD_H + 4));
        if (lx >= cw || ly >= CARD_H) return null;
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
