package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.menu.SiegeCannonMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.Locale;

/**
 * 砲台の弾薬庫({@link SiegeCannonMenu})の画面。テクスチャは持たず {@link GuiGraphics#fill} で描く仮の見た目。
 *
 * <p>照準はここでは行わない — 砲台に乗ると一人称が照準器になる({@link CannonSight})。この画面は
 * 砲弾・火薬の出し入れと装薬(1 発に使う火薬の数 = 初速)の設定、それと現在の向き・装填状況の確認だけ。
 */
public class SiegeCannonScreen extends AbstractContainerScreen<SiegeCannonMenu> {

    private static final int PANEL_BG = 0xFFC6C6C6;
    private static final int PANEL_BORDER = 0xFF373737;
    private static final int SLOT_DARK = 0xFF8B8B8B;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int TEXT = 0xFF404040;
    private static final int TEXT_BAD = 0xFF9A3030;
    private static final int GAUGE_BG = 0xFF5A5A5A;
    private static final int GAUGE_FG = 0xFF6FBF4F;

    private static final int ROW_AIM = 20;
    private static final int ROW_CHARGE = 56;
    private static final int ROW_STATUS = 74;

    public SiegeCannonScreen(SiegeCannonMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = SiegeCannonMenu.WIDTH;
        this.imageHeight = SiegeCannonMenu.HEIGHT;
        this.titleLabelY = 6;
        this.inventoryLabelX = SiegeCannonMenu.INV_X;
        this.inventoryLabelY = SiegeCannonMenu.INV_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("-"), b -> click(SiegeCannonMenu.BUTTON_CHARGE_DOWN))
                .bounds(leftPos + 54, topPos + ROW_CHARGE - 4, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> click(SiegeCannonMenu.BUTTON_CHARGE_UP))
                .bounds(leftPos + 106, topPos + ROW_CHARGE - 4, 16, 16).build());
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, PANEL_BG);

        for (Slot slot : menu.slots) {
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_DARK);
            graphics.fill(sx + 1, sy + 1, sx + 18, sy + 18, SLOT_LIGHT);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_DARK);
        }

        // 装填ゲージ。
        int gx = x + 20;
        int gy = y + ROW_STATUS + 11;
        int gw = imageWidth - 40;
        graphics.fill(gx, gy, gx + gw, gy + 4, GAUGE_BG);
        int total = menu.reloadTotal();
        int remaining = menu.reloadTicks();
        int filled = total > 0 && remaining > 0
                ? Math.max(1, Math.min(gw, (int) ((long) gw * (total - remaining) / total)))
                : gw;
        graphics.fill(gx, gy, gx + filled, gy + 4, GAUGE_FG);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        Component aim = Component.translatable("gui.overgrown_vegetation.cannon.aim",
                fmt1(menu.elevationTenths() / 10.0D), fmt1(menu.azimuthTenths() / 10.0D));
        graphics.drawCenteredString(font, aim, imageWidth / 2, ROW_AIM, TEXT);

        Component charge = Component.translatable("gui.overgrown_vegetation.cannon.charge",
                menu.charge(), menu.maxCharge());
        graphics.drawCenteredString(font, charge, imageWidth / 2, ROW_CHARGE, TEXT);

        Component status;
        int color = TEXT;
        if (menu.reloadTicks() > 0) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.reloading",
                    (int) Math.ceil(menu.reloadTicks() / 20.0D));
        } else if (!menu.hasShell()) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.no_shell");
            color = TEXT_BAD;
        } else if (menu.powderCount() < menu.charge()) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.no_powder", menu.charge());
            color = TEXT_BAD;
        } else {
            status = Component.translatable("gui.overgrown_vegetation.cannon.ready");
        }
        graphics.drawCenteredString(font, status, imageWidth / 2, ROW_STATUS, color);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
