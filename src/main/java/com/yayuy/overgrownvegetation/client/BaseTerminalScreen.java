package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.base.build.BaseTrade;
import com.yayuy.overgrownvegetation.base.build.BaseUpgrade;
import com.yayuy.overgrownvegetation.base.BaseTerminalBlock;
import com.yayuy.overgrownvegetation.menu.BaseTerminalMenu;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * 管理端末の画面({@link BaseTerminalMenu}。順 2 の着手順 5、2026-10-07)。テクスチャは持たず、枠・タブ・一覧を {@link GuiGraphics#fill} で描く
 * (除染機などの画面と同じ作り)。タブは状態・寄付・取引(ユーザー指定 10-07)。
 *
 * <p>ボタンと一覧のクリックは {@code handleInventoryButtonClick} の番号で送る(サーバーの {@link BaseTerminalMenu#clickMenuButton} が確かめ直す)。
 * 押せるかどうかの見た目は、送られてきた値(信頼・貯蓄・見込み)とクライアントの持ち物で判定する。
 */
public class BaseTerminalScreen extends AbstractContainerScreen<BaseTerminalMenu> {

    private static final String KEY = "gui.overgrown_vegetation.base_terminal.";
    private static final String[] TAB_NAMES = {"status", "donate", "trade"};

    private static final int PANEL_BG = 0xFFC6C6C6;
    private static final int PANEL_BORDER = 0xFF373737;
    private static final int SLOT_DARK = 0xFF8B8B8B;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int TAB_OFF = 0xFFA4A4A4;
    private static final int TEXT = 0xFF404040;
    private static final int TEXT_DIM = 0xFF707070;
    private static final int GOOD = 0xFF2E7D32;
    private static final int BAD = 0xFFB23A3A;
    private static final int ROW_BG = 0xFFB8B8B8;
    private static final int ROW_HOVER = 0xFFD8D8D8;
    private static final int ROW_LOCKED = 0xFFA9A9A9;

    private static final int TAB_X = 6;
    private static final int TAB_Y = 4;
    private static final int TAB_W = 60;
    private static final int TAB_H = 16;
    private static final int TAB_GAP = 2;
    private static final int CONTENT_Y = 38;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 38;
    private static final int LIST_W = 166;
    private static final int ROW_H = 20;
    private static final int ROWS = 5;
    private static final int RIGHT_X = 176;

    private Button upgradeButton;
    private Button donateButton;
    private Button sellButton;
    private int scroll;
    private int lastSeq = Integer.MIN_VALUE;
    private Component message;
    private boolean messageGood;
    private long messageUntil;

    public BaseTerminalScreen(BaseTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = BaseTerminalMenu.WIDTH;
        this.imageHeight = BaseTerminalMenu.HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 24;
        this.inventoryLabelX = BaseTerminalMenu.INV_X;
        this.inventoryLabelY = BaseTerminalMenu.INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        this.upgradeButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "button.upgrade"),
                b -> press(BaseTerminalMenu.BUTTON_UPGRADE)).bounds(this.leftPos + RIGHT_X, this.topPos + 122, 72, 18).build());
        this.donateButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "button.donate"),
                b -> press(BaseTerminalMenu.BUTTON_DONATE)).bounds(this.leftPos + RIGHT_X, this.topPos + 100, 72, 18).build());
        this.sellButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "button.sell"),
                b -> press(BaseTerminalMenu.BUTTON_SELL)).bounds(this.leftPos + RIGHT_X, this.topPos + 100, 72, 18).build());
        updateButtons();
    }

    /** クライアントの写しで確かめてから、サーバーへボタンの番号を送る(バニラの機織り機・石切台と同じ形)。 */
    private void press(int id) {
        if (this.minecraft != null && this.minecraft.player != null && this.minecraft.gameMode != null
                && this.menu.clickMenuButton(this.minecraft.player, id)) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
        pollMessage();
    }

    private boolean tradeOpen() {
        return this.menu.trust() + 1e-9 >= this.menu.snapshot.trustTrade();
    }

    private void updateButtons() {
        if (this.upgradeButton == null) {
            return;
        }
        int tab = this.menu.tab();
        int lv = this.menu.get(BaseTerminalMenu.D_LEVEL);
        int flags = this.menu.get(BaseTerminalMenu.D_FLAGS);
        this.upgradeButton.visible = tab == BaseTerminalMenu.TAB_STATUS;
        this.upgradeButton.active = lv >= 1 && !BaseUpgrade.isMax(lv)
                && (flags & (BaseTerminalMenu.FLAG_BUILDING | BaseTerminalMenu.FLAG_OUTDATED)) == 0
                && this.minecraft != null && this.minecraft.player != null && BaseUpgrade.canPay(this.minecraft.player, BaseUpgrade.cost(lv + 1));
        boolean hasValue = this.menu.get(BaseTerminalMenu.D_P_VALUE) > 0;
        this.donateButton.visible = tab == BaseTerminalMenu.TAB_DONATE;
        this.donateButton.active = hasValue;
        this.sellButton.visible = tab == BaseTerminalMenu.TAB_TRADE;
        this.sellButton.active = hasValue && tradeOpen();
    }

    // ---------- 結果の一言 ----------

    private void pollMessage() {
        int seq = this.menu.get(BaseTerminalMenu.D_LAST_SEQ);
        if (seq == this.lastSeq) {
            return;
        }
        boolean first = this.lastSeq == Integer.MIN_VALUE;
        this.lastSeq = seq;
        if (first) {
            return; // 開いたときの値は前の結果なので出さない
        }
        int code = this.menu.get(BaseTerminalMenu.D_LAST_CODE);
        int action = code >> 4;
        int r = code & 15;
        if (action == BaseTerminalMenu.ACTION_UPGRADE) {
            BaseTerminalBlock.UpgradeResult[] all = BaseTerminalBlock.UpgradeResult.values();
            BaseTerminalBlock.UpgradeResult u = all[Mth.clamp(r, 0, all.length - 1)];
            this.message = Component.translatable(KEY + "upgrade_result." + u.name().toLowerCase(Locale.ROOT));
            this.messageGood = u == BaseTerminalBlock.UpgradeResult.STARTED;
        } else {
            BaseTrade.Result[] all = BaseTrade.Result.values();
            BaseTrade.Result result = all[Mth.clamp(r, 0, all.length - 1)];
            String value = fmt(this.menu.get(BaseTerminalMenu.D_LAST_VALUE) / 10.0D);
            String trust = fmt(this.menu.get(BaseTerminalMenu.D_LAST_TRUST) / 10.0D);
            int scrip = this.menu.get(BaseTerminalMenu.D_LAST_SCRIP);
            if (result == BaseTrade.Result.OK) {
                this.message = switch (action) {
                    case BaseTerminalMenu.ACTION_DONATE -> Component.translatable(KEY + "result.donated", value, trust, scrip);
                    case BaseTerminalMenu.ACTION_SELL -> Component.translatable(KEY + "result.sold", value, scrip);
                    default -> Component.translatable(KEY + "result.bought", scrip);
                };
            } else {
                this.message = Component.translatable(KEY + "result." + result.name().toLowerCase(Locale.ROOT));
            }
            this.messageGood = result == BaseTrade.Result.OK;
        }
        this.messageUntil = Util.getMillis() + 4000L;
    }

    // ---------- 描く ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (this.menu.tab() == BaseTerminalMenu.TAB_TRADE) {
            int idx = offerAt(mouseX, mouseY);
            if (idx >= 0) {
                g.renderTooltip(this.font, this.menu.snapshot.offers().get(idx).stack(), mouseX, mouseY);
                return;
            }
        }
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        g.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL_BORDER);
        g.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL_BG);
        for (int i = 0; i < BaseTerminalMenu.TAB_COUNT; i++) {
            int tx = x + TAB_X + i * (TAB_W + TAB_GAP);
            int ty = y + TAB_Y;
            boolean on = this.menu.tab() == i;
            g.fill(tx, ty, tx + TAB_W, ty + TAB_H + (on ? 1 : 0), PANEL_BORDER);
            g.fill(tx + 1, ty + 1, tx + TAB_W - 1, ty + TAB_H + (on ? 1 : -1), on ? PANEL_BG : TAB_OFF);
            Component label = Component.translatable(KEY + "tab." + TAB_NAMES[i]);
            g.drawString(this.font, label, tx + (TAB_W - this.font.width(label)) / 2, ty + 4, on ? TEXT : TEXT_DIM, false);
        }
        g.fill(x + 4, y + TAB_Y + TAB_H, x + this.imageWidth - 4, y + TAB_Y + TAB_H + 1, PANEL_BORDER);
        for (Slot slot : this.menu.slots) {
            if (!slot.isActive()) {
                continue;
            }
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, SLOT_DARK);
            g.fill(sx + 1, sy + 1, sx + 18, sy + 18, SLOT_LIGHT);
            g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_DARK);
        }
        if (this.menu.tab() == BaseTerminalMenu.TAB_TRADE) {
            renderOffers(g, mouseX, mouseY);
        }
    }

    /** 品の一覧(村人の画面と同じく 1 行 1 品。押すと 1 回買う)。 */
    private void renderOffers(GuiGraphics g, int mouseX, int mouseY) {
        List<BaseTerminalMenu.OfferView> offers = this.menu.snapshot.offers();
        int x = this.leftPos + LIST_X;
        int y = this.topPos + LIST_Y;
        double trust = this.menu.trust();
        int scripHave = scripHave();
        int stock = this.menu.get(BaseTerminalMenu.D_STOCK);
        boolean open = tradeOpen();
        this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, offers.size() - ROWS));
        for (int row = 0; row < ROWS; row++) {
            int idx = this.scroll + row;
            if (idx >= offers.size()) {
                break;
            }
            BaseTerminalMenu.OfferView o = offers.get(idx);
            int ry = y + row * ROW_H;
            boolean locked = !open || trust + 1e-9 < o.minTrust();
            boolean hover = !locked && mouseX >= x && mouseX < x + LIST_W && mouseY >= ry && mouseY < ry + ROW_H - 2;
            g.fill(x, ry, x + LIST_W, ry + ROW_H - 2, locked ? ROW_LOCKED : hover ? ROW_HOVER : ROW_BG);
            if (!o.stack().isEmpty()) {
                g.renderItem(o.stack(), x + 2, ry + 1);
                g.renderItemDecorations(this.font, o.stack(), x + 2, ry + 1);
            }
            Component price;
            int priceColor;
            if (locked) {
                price = Component.translatable(KEY + "locked_offer", Math.max(o.minTrust(), this.menu.snapshot.trustTrade()));
                priceColor = TEXT_DIM;
            } else {
                int p = this.menu.snapshot.price(o.cost(), trust);
                price = Component.translatable(KEY + "price", p);
                priceColor = p > scripHave || o.cost() > stock ? BAD : TEXT;
            }
            int pw = this.font.width(price);
            String name = this.font.plainSubstrByWidth(o.stack().getHoverName().getString(), LIST_W - 26 - pw - 6);
            g.drawString(this.font, name, x + 22, ry + 5, locked ? TEXT_DIM : TEXT, false);
            g.drawString(this.font, price, x + LIST_W - 3 - pw, ry + 5, priceColor, false);
        }
        if (offers.size() > ROWS) {
            int barX = x + LIST_W + 1;
            int h = ROWS * ROW_H - 2;
            g.fill(barX, y, barX + 3, y + h, SLOT_DARK);
            int knob = Math.max(8, h * ROWS / offers.size());
            int top = y + (h - knob) * this.scroll / Math.max(1, offers.size() - ROWS);
            g.fill(barX, top, barX + 3, top + knob, PANEL_BORDER);
        }
        if (!open) {
            // 品の絵は手前(z)に描かれるので、覆いと文はさらに手前へ(しないと絵が文の上に重なる)
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 300.0F);
            g.fill(x, y, x + LIST_W, y + ROWS * ROW_H - 2, 0xD8C6C6C6);
            Component locked = Component.translatable(KEY + "trade_locked", this.menu.snapshot.trustTrade(), fmt(trust));
            List<FormattedCharSequence> lines = this.font.split(locked, LIST_W - 16);
            int ly = y + (ROWS * ROW_H - 2 - lines.size() * 10) / 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(this.font, line, x + 8, ly, BAD, false);
                ly += 10;
            }
            g.pose().popPose();
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT, false);
        switch (this.menu.tab()) {
            case BaseTerminalMenu.TAB_STATUS -> drawStatus(g);
            case BaseTerminalMenu.TAB_DONATE -> drawDonate(g);
            default -> drawTrade(g);
        }
        if (this.message != null && Util.getMillis() < this.messageUntil) {
            // 結果の一言は 4 秒だけ、題の行に帯で出す(ボタンや枠を隠さない場所。題と信頼はその間だけ隠れる)
            g.fill(4, this.titleLabelY - 3, this.imageWidth - 4, this.titleLabelY + 10, this.messageGood ? 0xFFD7EBD7 : 0xFFF0D2D2);
            String text = this.font.plainSubstrByWidth(this.message.getString(), this.imageWidth - 16);
            g.drawString(this.font, text, (this.imageWidth - this.font.width(text)) / 2, this.titleLabelY, this.messageGood ? GOOD : BAD, false);
        } else {
            g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT, false);
            Component trust = Component.translatable(KEY + "trust", fmt(this.menu.trust()), fmt(this.menu.snapshot.trustMax()));
            g.drawString(this.font, trust, this.imageWidth - 8 - this.font.width(trust), this.titleLabelY, GOOD, false);
        }
    }

    private int drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int color) {
        for (FormattedCharSequence line : this.font.split(text, width)) {
            g.drawString(this.font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    /**
     * 状態のタブ。上から: 信頼の案内 → 基地の貯蓄 → 基地の様子(貯蓄・守備兵・整備の文。長いので 4 分の 3 の大きさで、入る行まで)→ 拡張。
     * 1280 × 720 の窓(GUI 3 倍)では画面の高さが 240 しか無いので、拡張の行(120〜)より下へはみ出さないように切る。
     */
    private void drawStatus(GuiGraphics g) {
        int y = CONTENT_Y;
        Component trust = tradeOpen()
                ? Component.translatable(KEY + "trust_today", fmt(this.menu.get(BaseTerminalMenu.D_TODAY) / 10.0D))
                : Component.translatable(KEY + "trust_next", this.menu.snapshot.trustTrade(),
                fmt(this.menu.get(BaseTerminalMenu.D_TODAY) / 10.0D));
        y = drawWrapped(g, trust, 8, y, this.imageWidth - 16, GOOD);
        g.drawString(this.font, Component.translatable(KEY + "stock", this.menu.get(BaseTerminalMenu.D_STOCK),
                this.menu.get(BaseTerminalMenu.D_CAP)), 8, y, TEXT, false);
        y += 12;
        float s = 0.75F;
        g.pose().pushPose();
        g.pose().translate(8.0F, y, 0.0F);
        g.pose().scale(s, s, 1.0F);
        int ly = 0;
        int limit = (int) ((116 - y) / s);
        outer:
        for (Component line : this.menu.snapshot.status()) {
            for (FormattedCharSequence part : this.font.split(line, (int) ((this.imageWidth - 16) / s))) {
                if (ly + 9 > limit) {
                    break outer;
                }
                g.drawString(this.font, part, 0, ly, TEXT, false);
                ly += 10;
            }
        }
        g.pose().popPose();
        int lv = this.menu.get(BaseTerminalMenu.D_LEVEL);
        int flags = this.menu.get(BaseTerminalMenu.D_FLAGS);
        Component up;
        if ((flags & BaseTerminalMenu.FLAG_OUTDATED) != 0) {
            up = Component.translatable(KEY + "upgrade_outdated");
        } else if ((flags & BaseTerminalMenu.FLAG_BUILDING) != 0) {
            up = Component.translatable(KEY + "upgrade_building");
        } else if (lv < 1 || BaseUpgrade.isMax(lv)) {
            up = Component.translatable(KEY + "upgrade_max");
        } else {
            up = Component.translatable(KEY + "upgrade_next", lv + 1, BaseUpgrade.describe(BaseUpgrade.cost(lv + 1)));
        }
        drawWrapped(g, up, 8, 120, RIGHT_X - 14, TEXT);
    }

    private void drawDonate(GuiGraphics g) {
        int y = drawWrapped(g, Component.translatable(KEY + "donate_help"), 8, CONTENT_Y, RIGHT_X - 16, TEXT_DIM);
        y = Math.max(y + 4, 84);
        double value = this.menu.get(BaseTerminalMenu.D_P_VALUE) / 10.0D;
        g.drawString(this.font, Component.translatable(KEY + "value", fmt(value)), 8, y, TEXT, false);
        g.drawString(this.font, Component.translatable(KEY + "gain", fmt(this.menu.get(BaseTerminalMenu.D_P_TRUST) / 10.0D),
                fmt(this.menu.get(BaseTerminalMenu.D_TODAY) / 10.0D)), 8, y + 12, GOOD, false);
        g.drawString(this.font, Component.translatable(KEY + "thanks", fmt(this.menu.get(BaseTerminalMenu.D_P_THANKS) / 10.0D)),
                8, y + 24, TEXT, false);
    }

    /** 取引のタブの右の列(売る見込み・持っている軍票・基地の貯蓄)。一覧の下は「インベントリ」の見出しと重なるので使わない。 */
    private void drawTrade(GuiGraphics g) {
        int x = RIGHT_X + 2;
        g.drawString(this.font, Component.translatable(KEY + "sell_gain", fmt(this.menu.get(BaseTerminalMenu.D_P_SELL) / 10.0D)),
                x, 121, TEXT, false);
        g.drawString(this.font, Component.translatable(KEY + "scrip_have", scripHave()), x, 131, TEXT, false);
        g.drawString(this.font, Component.translatable(KEY + "stock_short", this.menu.get(BaseTerminalMenu.D_STOCK)), x, 141, TEXT_DIM, false);
    }

    private int scripHave() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return 0;
        }
        return BaseTrade.countScrip(this.minecraft.player);
    }

    private static String fmt(double v) {
        double r = Math.round(v * 10.0D) / 10.0D;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    // ---------- マウス ----------

    private int tabAt(double mx, double my) {
        for (int i = 0; i < BaseTerminalMenu.TAB_COUNT; i++) {
            int tx = this.leftPos + TAB_X + i * (TAB_W + TAB_GAP);
            int ty = this.topPos + TAB_Y;
            if (mx >= tx && mx < tx + TAB_W && my >= ty && my < ty + TAB_H) {
                return i;
            }
        }
        return -1;
    }

    private boolean inList(double mx, double my) {
        int x = this.leftPos + LIST_X;
        int y = this.topPos + LIST_Y;
        return mx >= x && mx < x + LIST_W + 4 && my >= y && my < y + ROWS * ROW_H;
    }

    /** マウスの下の品(買える行だけ。無ければ −1)。 */
    private int offerAt(double mx, double my) {
        if (!inList(mx, my) || !tradeOpen()) {
            return -1;
        }
        int row = (int) ((my - (this.topPos + LIST_Y)) / ROW_H);
        int idx = this.scroll + row;
        List<BaseTerminalMenu.OfferView> offers = this.menu.snapshot.offers();
        if (row < 0 || row >= ROWS || idx >= offers.size() || mx >= this.leftPos + LIST_X + LIST_W) {
            return -1;
        }
        return this.menu.trust() + 1e-9 >= offers.get(idx).minTrust() ? idx : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int tab = tabAt(mouseX, mouseY);
            if (tab >= 0) {
                press(tab);
                return true;
            }
            if (this.menu.tab() == BaseTerminalMenu.TAB_TRADE) {
                int idx = offerAt(mouseX, mouseY);
                if (idx >= 0) {
                    press(BaseTerminalMenu.BUTTON_BUY + idx);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.menu.tab() == BaseTerminalMenu.TAB_TRADE && inList(mouseX, mouseY)) {
            this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, this.menu.snapshot.offers().size() - ROWS));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
