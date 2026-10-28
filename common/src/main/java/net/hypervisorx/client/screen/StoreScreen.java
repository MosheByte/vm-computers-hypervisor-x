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
 * OrbitTech Store 2.0: categories, search, spec cards, ready-made bundles and a cart. orders come
 * down in a supply pod dropped from orbit. columns flip for right-to-left languages.
 */
public class StoreScreen extends Screen {
    private static final int CARD_W = 112;
    private static final int CARD_H = 44;
    private static final int SIDE_W = 96;
    private static final int CART_W = 124;
    private static final int ROW_H = 18;
    private static final int GAP = 6;

    private StoreCatalog.Category category = StoreCatalog.Category.BUNDLES;
    private EditBox search;
    private final Map<String, Integer> cart = new LinkedHashMap<>();
    private final UiKit.Scroll scroll = new UiKit.Scroll();
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
        panelW = Math.min(width - 16, Mth.clamp((int) (width * 0.82), 320, 640));
        panelH = Math.min(height - 16, Mth.clamp((int) (height * 0.86), 220, 380));
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        search = new UiKit.Field(font, gridX(), top + 25, gridW(), 15, Component.translatable("hypervisorx.store.search"));
        search.setResponder(s -> {
            scroll.reset();
            refresh();
        });
        addRenderableWidget(search);
        int y = top + 25;
        int rowH = Math.max(14, Math.min(ROW_H, (panelH - 32) / StoreCatalog.Category.values().length));
        for (StoreCatalog.Category c : StoreCatalog.Category.values()) {
            addRenderableWidget(new UiKit.FlatButton(sideX(), y, SIDE_W - GAP, rowH - 1,
                    Component.translatable("hypervisorx.store.cat." + c.name().toLowerCase(Locale.ROOT)), () -> {
                        category = c;
                        scroll.reset();
                        if (search != null) search.setValue("");
                        refresh();
                    }).icon(categoryIcon(c)).style(UiKit.Style.GHOST).alignStart()
                    .selectedWhen(() -> category == c && (search == null || search.getValue().isEmpty()))
                    .accent(c == StoreCatalog.Category.BUNDLES ? UiKit.ACCENT_2 : UiKit.ACCENT));
            y += rowH;
        }
        int cx = cartX() + 3;
        int cw = CART_W;
        addRenderableWidget(new UiKit.FlatButton(cx, top + panelH - 44, cw, 18, Component.translatable("hypervisorx.store.checkout"),
                this::checkout).enabledWhen(() -> !cart.isEmpty()).icon(UiKit.Icon.ROCKET).style(UiKit.Style.PRIMARY));
        addRenderableWidget(new UiKit.FlatButton(cx, top + panelH - 23, cw / 2 - 2, 16, Component.translatable("hypervisorx.store.clear"),
                cart::clear).enabledWhen(() -> !cart.isEmpty()).icon(UiKit.Icon.TRASH).style(UiKit.Style.DANGER));
        addRenderableWidget(new UiKit.FlatButton(cx + cw / 2 + 2, top + panelH - 23, cw - cw / 2 - 2, 16, Component.translatable("hypervisorx.ui.library"),
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
            case GRAPHICS -> UiKit.Icon.GPU;
            case STORAGE -> UiKit.Icon.DRIVE;
            case EXPANSION -> UiKit.Icon.PCIE;
            case CASES -> UiKit.Icon.SERVER;
            case DISPLAYS -> UiKit.Icon.MONITOR;
            case LAPTOPS -> UiKit.Icon.LAPTOP;
            case PERIPHERALS -> UiKit.Icon.KEYBOARD;
        };
    }

    // columns: [side][grid][cart], mirrored in RTL. 6 px outer margins and gutters
    private int sideX() {
        return UiKit.rtl() ? left + panelW - SIDE_W : left + GAP;
    }

    private int gridX() {
        return UiKit.rtl() ? left + GAP + CART_W + 6 + GAP : left + SIDE_W + GAP;
    }

    private int gridW() {
        return panelW - SIDE_W - CART_W - 24;
    }

    private int cartX() {
        return UiKit.rtl() ? left + GAP : gridX() + gridW() + GAP;
    }

    private int gridY() {
        return top + 45;
    }

    private int gridH() {
        return panelH - 64;
    }

    private int columns() {
        return Math.max(1, (cardsW() + 4) / (CARD_W + 4));
    }

    /** grid width left for the cards (scrollbar takes 5 px once the list scrolls) */
    private int cardsW() {
        return gridW() - (maxScroll(Math.max(1, (gridW() + 4) / (CARD_W + 4))) > 0 ? 5 : 0);
    }

    private int maxScroll(int cols) {
        int rows = (visible.size() + cols - 1) / cols;
        return Math.max(0, rows * (CARD_H + 4) - 4 - gridH());
    }

    private int cardW() {
        int cols = columns();
        return (cardsW() - (cols - 1) * 4) / cols;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        UiKit.window(g, font, left, top, panelW, panelH, UiKit.Icon.CART, title, null);
        long t = System.currentTimeMillis();
        // little orbiting satellite up in the corner
        int cx = UiKit.rtl() ? left + 16 : left + panelW - 16;
        int cy = top + 10;
        double a = (t % 6000) / 6000.0 * Math.PI * 2;
        UiKit.dot(g, cx - 2, cy - 2, UiKit.ACCENT_2);
        int sx = cx + (int) (Math.cos(a) * 8);
        int sy = cy + (int) (Math.sin(a) * 4);
        g.fill(sx - 1, sy, sx + 2, sy + 1, UiKit.WARN);
        String sat = Component.translatable("hypervisorx.store.satellite").getString();
        float sw = UiKit.width(font, sat, 0.75f);
        UiKit.text(g, font, sat, UiKit.rtl() ? cx + 14 : cx - 14 - sw, top + 7, UiKit.MUTED, 0.75f);
        UiKit.section(g, font, cartX(), top + 24, CART_W + 6, panelH - 74, Component.translatable("hypervisorx.store.cart"));
        UiKit.icon(g, UiKit.Icon.SEARCH, UiKit.rtl() ? gridX() + 4 : gridX() + gridW() - 16, top + 26, UiKit.INFO);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderGrid(g, mouseX, mouseY);
        renderCart(g);
        if (System.currentTimeMillis() - statusTime < 8000) {
            UiKit.textFit(g, font, status.getString(), gridX(), top + panelH - 14, gridW(), statusColor, 0.75f);
        }
        StoreCatalog.Product hovered = productAt(mouseX, mouseY);
        if (hovered != null && stepperAt(mouseX, mouseY) == 0) {
            List<Component> tip = new ArrayList<>();
            tip.add(displayName(hovered));
            if (hovered.isBundle()) {
                tip.add(Component.translatable(hovered.descriptionKey()));
                for (StoreCatalog.Line l : hovered.contents()) {
                    tip.add(Component.literal(" " + l.count() + "× ").append(new ItemStack(l.item().get()).getHoverName()));
                }
            } else {
                List<Component> lines = hovered.icon().getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL);
                if (lines.size() > 1) tip.addAll(lines.subList(1, lines.size()));
            }
            tip.add(Component.translatable("hypervisorx.store.price", hovered.price()).withStyle(s -> s.withColor(UiKit.WARN)));
            tip.add(Component.translatable("hypervisorx.store.click_hint"));
            UiKit.queueTip(tip);
        }
        UiKit.renderOverlays(g, font, mouseX, mouseY);
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        int cols = columns();
        int max = maxScroll(cols);
        scroll.update(max);
        if (visible.isEmpty()) {
            UiKit.icon(g, UiKit.Icon.SEARCH, gridX() + gridW() / 2 - 6, gridY() + 20, UiKit.withAlpha(UiKit.INFO, 0x66));
            String s = Component.translatable("hypervisorx.store.no_results").getString();
            UiKit.text(g, font, s, gridX() + (gridW() - UiKit.width(font, s, 0.75f)) / 2f, gridY() + 36, UiKit.INFO, 0.75f);
            return;
        }
        g.enableScissor(gridX(), gridY(), gridX() + gridW(), gridY() + gridH());
        int cw = cardW();
        float sm = UiKit.small();
        for (int i = 0; i < visible.size(); i++) {
            StoreCatalog.Product p = visible.get(i);
            int x = cardX(i % cols, cw);
            int y = gridY() + (i / cols) * (CARD_H + 4) - scroll.px();
            if (y + CARD_H < gridY() || y > gridY() + gridH()) continue;
            boolean hover = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + CARD_H && mouseY >= gridY() && mouseY < gridY() + gridH();
            Integer q = cart.get(p.id());
            UiKit.card(g, x, y, cw, CARD_H, hover);
            if (q != null) UiKit.stroke(g, x, y, cw, CARD_H, UiKit.withAlpha(UiKit.OK, 0xB0));
            boolean r = UiKit.rtl();
            int wellX = r ? x + cw - 26 : x + 4;
            // lighter well behind the icon so dark stuff (tower, rack, laptop) doesn't vanish
            UiKit.round(g, wellX, y + 4, 22, 22, 0xFF3A414D);
            UiKit.round(g, wellX + 1, y + 5, 20, 20, 0xFF2A303A);
            g.renderItem(p.icon(), wellX + 3, y + 7);
            int tx = r ? x + 4 : x + 30;
            // product name first at full size, details small
            UiKit.textFit(g, font, displayName(p).getString(), tx, y + 6, cw - 34, UiKit.TEXT, 1f);
            String sub = p.isBundle() ? Component.translatable("hypervisorx.store.items", p.contents().stream().mapToInt(StoreCatalog.Line::count).sum()).getString()
                    : specLine(p.icon());
            UiKit.textFit(g, font, sub, tx, y + 17, cw - 34, UiKit.INFO, 0.75f);
            int by = y + CARD_H - 13;
            String price = String.valueOf(p.price());
            float pw = font.width(price) * sm;
            if (hover) {
                // on hover: − qty + stepper, price jumps to the other side
                int sx = r ? x + 4 : x + cw - 46;
                stepper(g, sx, by, q == null ? 0 : q, mouseX, mouseY);
                int px = r ? x + cw - 16 - (int) pw : x + 6;
                UiKit.icon(g, UiKit.Icon.COIN, r ? x + cw - 14 : px, by - 1, UiKit.WARN);
                UiKit.text(g, font, price, r ? px : px + 13, by + 2, UiKit.WARN, 0.75f);
            } else {
                int px = r ? x + 6 : (int) (x + cw - 6 - pw);
                UiKit.text(g, font, price, px, by + 2, UiKit.WARN, 0.75f);
                UiKit.icon(g, UiKit.Icon.COIN, r ? (int) (px + pw + 1) : px - 13, by - 1, UiKit.WARN);
                if (q != null) {
                    String qs = "×" + q;
                    if (r) UiKit.pill(g, font, x + cw - 4, by - 1, qs, UiKit.OK);
                    else UiKit.pill(g, font, x + 4 + (int) Math.ceil(font.width(qs) * sm) + 16, by - 1, qs, UiKit.OK);
                }
            }
        }
        g.disableScissor();
        if (max > 0) {
            int sbx = UiKit.rtl() ? gridX() : gridX() + gridW() - 3;
            UiKit.scrollbar(g, sbx, gridY(), gridH(), scroll.px(), max, gridH(), gridH() + max);
        }
    }

    private int cardX(int col, int cw) {
        int x = gridX() + (UiKit.rtl() && maxScroll(columns()) > 0 ? 5 : 0);
        return UiKit.rtl() ? x + cardsW() - cw - col * (cw + 4) : x + col * (cw + 4);
    }

    /** the − qty + stepper, 42x11 px */
    private void stepper(GuiGraphics g, int x, int y, int q, int mx, int my) {
        UiKit.box(g, x, y, 42, 11, UiKit.FIELD, UiKit.STROKE);
        boolean hm = mx >= x && mx < x + 11 && my >= y && my < y + 11;
        boolean hp = mx >= x + 31 && mx < x + 42 && my >= y && my < y + 11;
        if (hm) UiKit.round(g, x + 1, y + 1, 10, 9, UiKit.CARD_HOVER);
        if (hp) UiKit.round(g, x + 31, y + 1, 10, 9, UiKit.CARD_HOVER);
        g.fill(x + 3, y + 5, x + 8, y + 6, q > 0 ? UiKit.TEXT : UiKit.DIM);
        g.fill(x + 34, y + 5, x + 39, y + 6, UiKit.OK);
        g.fill(x + 36, y + 3, x + 37, y + 8, UiKit.OK);
        String s = String.valueOf(q);
        float sw = UiKit.width(font, s, 0.75f);
        UiKit.text(g, font, s, x + 21 - sw / 2f, y + 2, UiKit.TEXT, 0.75f);
    }

    /** -1 / +1 if the mouse is on a hovered card's stepper buttons, otherwise 0 */
    private int stepperAt(double mx, double my) {
        StoreCatalog.Product p = productAt(mx, my);
        if (p == null) return 0;
        int i = visible.indexOf(p);
        int cols = columns();
        int cw = cardW();
        int x = cardX(i % cols, cw);
        int y = gridY() + (i / cols) * (CARD_H + 4) - scroll.px();
        int sx = UiKit.rtl() ? x + 4 : x + cw - 46;
        int by = y + CARD_H - 13;
        if (my < by || my >= by + 11) return 0;
        if (mx >= sx && mx < sx + 11) return -1;
        if (mx >= sx + 31 && mx < sx + 42) return 1;
        return 0;
    }

    private String specLine(ItemStack stack) {
        List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL);
        return lines.size() > 2 ? lines.get(2).getString() : lines.size() > 1 ? lines.get(1).getString() : "";
    }

    private void renderCart(GuiGraphics g) {
        int x = cartX() + 4;
        int w = CART_W - 2;
        int y = top + 24;
        int ly = y + 17;
        long total = 0;
        int hidden = 0;
        int by = top + panelH - 72;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            StoreCatalog.Product p = StoreCatalog.get(e.getKey());
            if (p == null) continue;
            total += (long) p.price() * e.getValue();
            if (ly + 18 < by - 10) {
                boolean r = UiKit.rtl();
                g.renderItem(p.icon(), r ? x + w - 17 : x, ly - 3);
                int tx = r ? x : x + 19;
                UiKit.textFit(g, font, displayName(p).getString(), tx, ly - 1, w - 21, UiKit.TEXT, 0.75f);
                UiKit.textFit(g, font, "×" + e.getValue() + "  ·  " + (long) p.price() * e.getValue() + " cr", tx, ly + 7, w - 21, UiKit.INFO, 0.75f);
                ly += 19;
            } else {
                hidden++;
            }
        }
        if (hidden > 0) UiKit.textFit(g, font, Component.translatable("hypervisorx.store.more", hidden).getString(), x, ly, w, UiKit.INFO, 0.75f);
        if (cart.isEmpty()) {
            int cy = y + (by - y) / 2 - 14;
            UiKit.icon(g, UiKit.Icon.BOX, x + w / 2 - 6, cy, UiKit.withAlpha(UiKit.INFO, 0x66));
            UiKit.paragraph(g, font, Component.translatable("hypervisorx.store.cart_empty").getString(), x + 4, cy + 16, w - 8, 3, UiKit.INFO, 0.75f);
        }
        long credits = minecraft.player == null ? 0 : net.hypervisorx.store.StoreService.credits(minecraft.player.getInventory());
        boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
        g.fill(x, by - 4, x + w, by - 3, UiKit.STROKE_SOFT);
        UiKit.textFit(g, font, Component.translatable("hypervisorx.store.total", total).getString(), x + 1, by, w - 2, UiKit.WARN, 1f);
        UiKit.textFit(g, font, (creative ? Component.translatable("hypervisorx.store.free_creative")
                : Component.translatable("hypervisorx.store.wallet", credits)).getString(), x + 1, by + 12, w - 2,
                credits >= total || creative ? UiKit.OK : UiKit.ERROR, 0.75f);
    }

    private StoreCatalog.Product productAt(double mx, double my) {
        if (mx < gridX() || mx >= gridX() + gridW() || my < gridY() || my >= gridY() + gridH()) return null;
        int cols = columns();
        int cw = cardW();
        for (int col = 0; col < cols; col++) {
            int x = cardX(col, cw);
            if (mx < x || mx >= x + cw) continue;
            int row = (int) ((my - gridY() + scroll.px()) / (CARD_H + 4));
            int ly = (int) ((my - gridY() + scroll.px()) % (CARD_H + 4));
            if (ly >= CARD_H) return null;
            int idx = row * cols + col;
            return idx >= 0 && idx < visible.size() ? visible.get(idx) : null;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (UiKit.popoverClicked(mx, my, button)) return true;
        StoreCatalog.Product p = productAt(mx, my);
        if (p != null) {
            int q = cart.getOrDefault(p.id(), 0);
            int step = stepperAt(mx, my);
            if (step != 0) q = Math.max(0, Math.min(64, q + step));
            else if (button == 1) q = Math.max(0, q - 1);
            else q = Math.min(64, q + (hasShiftDown() ? 8 : 1));
            if (q == 0) cart.remove(p.id());
            else cart.put(p.id(), q);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll.scrollBy(sy, 32);
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
    public void removed() {
        UiKit.closePopover();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
