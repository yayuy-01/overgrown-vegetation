package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.block.DecontaminatorBlockEntity;
import com.yayuy.overgrownvegetation.menu.DecontaminatorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 除染機({@link DecontaminatorMenu})の画面。テクスチャは持たず、枠・スロット・ゲージを
 * {@link GuiGraphics#fill} で描く仮の見た目(胞子装置の画面と同じ作り)。
 * スロットの下のゲージは「次の 1% を落とすまで」の進行。
 */
public class DecontaminatorScreen extends AbstractContainerScreen<DecontaminatorMenu> {

    private static final int PANEL_BG = 0xFFC6C6C6;
    private static final int PANEL_BORDER = 0xFF373737;
    private static final int SLOT_DARK = 0xFF8B8B8B;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int GAUGE_BG = 0xFF5A5A5A;
    private static final int GAUGE_FILL = 0xFF57C25A;

    public DecontaminatorScreen(DecontaminatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = imageHeight - 94;
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

        int gx = x + DecontaminatorMenu.SLOT_X;
        int gy = y + DecontaminatorMenu.SLOT_Y + 20;
        int gw = DecontaminatorMenu.SLOT_SPACING * DecontaminatorBlockEntity.SLOT_COUNT - 2;
        graphics.fill(gx, gy, gx + gw, gy + 5, GAUGE_BG);
        int max = menu.cycleLength();
        int value = menu.cycleTicks();
        if (max > 0 && value > 0) {
            int filled = Math.max(1, Math.min(gw, (int) ((long) gw * value / max)));
            graphics.fill(gx, gy, gx + filled, gy + 5, GAUGE_FILL);
        }
        // 電池の残り(電池の枠の下。2026-10-24)
        int bx = x + DecontaminatorMenu.BATTERY_X;
        int by = y + DecontaminatorMenu.BATTERY_Y + 20;
        graphics.fill(bx, by, bx + 16, by + 5, GAUGE_BG);
        if (menu.capacitySeconds() > 0 && menu.chargeSeconds() > 0) {
            int bf = Math.max(1, Math.min(16, (int) (16L * menu.chargeSeconds() / menu.capacitySeconds())));
            graphics.fill(bx, by, bx + bf, by + 5, 0xFF3FB8D8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        float worst = menu.worstContamination();
        Component status = worst > 0.0F && menu.chargeSeconds() <= 0
                ? Component.translatable("gui.overgrown_vegetation.decontaminator.no_battery") // 洗う物はあるのに電池が無い
                : worst > 0.0F
                ? Component.translatable("gui.overgrown_vegetation.decontaminator.working", String.format("%.0f", worst))
                : Component.translatable("gui.overgrown_vegetation.decontaminator.idle");
        graphics.drawString(font, status, 8, 62, 0xFF404040, false);
    }

}
