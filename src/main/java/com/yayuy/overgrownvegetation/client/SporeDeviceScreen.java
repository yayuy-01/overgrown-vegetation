package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.block.SporeDeviceKind;
import com.yayuy.overgrownvegetation.menu.SporeDeviceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 胞子装置({@link SporeDeviceMenu})の画面。テクスチャは持たず、枠・スロット・ゲージを
 * {@link GuiGraphics#fill} で描く仮の見た目(本番のテクスチャは後で差し替え)。
 * <ul>
 *     <li>燃料スロットと胞子スロットの間: 残り稼働時間のゲージ(レッドストーン 1 個ぶんを 100% とする)。</li>
 *     <li>除去装置のみ、その下に「胞子」産出サイクルのゲージ。</li>
 * </ul>
 */
public class SporeDeviceScreen extends AbstractContainerScreen<SporeDeviceMenu> {

    private static final int PANEL_BG = 0xFFC6C6C6;
    private static final int PANEL_BORDER = 0xFF373737;
    private static final int SLOT_DARK = 0xFF8B8B8B;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int GAUGE_BG = 0xFF5A5A5A;

    public SporeDeviceScreen(SporeDeviceMenu menu, Inventory inventory, Component title) {
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
            if (!slot.isActive()) {
                continue;                             // 拡散装置のフィルター枠は描かない
            }
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_DARK);
            graphics.fill(sx + 1, sy + 1, sx + 18, sy + 18, SLOT_LIGHT);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_DARK);
        }

        // 稼働ゲージ(燃料 → 胞子 の間)。
        int gx = x + SporeDeviceMenu.FUEL_X + 22;
        int gy = y + SporeDeviceMenu.FUEL_Y + 4;
        int gw = SporeDeviceMenu.SPORE_X - SporeDeviceMenu.FUEL_X - 26;
        drawGauge(graphics, gx, gy, gw, 8, menu.remainingTicks(), menu.ticksPerFuel(),
                menu.kind() == SporeDeviceKind.PURIFIER ? 0xFF3FB8D8 : 0xFFC050D0);

        if (menu.kind() == SporeDeviceKind.PURIFIER) {
            drawGauge(graphics, gx, gy + 12, gw, 4, menu.cycleTicks(), menu.cycleLength(), 0xFF8C5BB5);
        }
    }

    private static void drawGauge(GuiGraphics graphics, int x, int y, int w, int h, int value, int max, int color) {
        graphics.fill(x, y, x + w, y + h, GAUGE_BG);
        if (max > 0 && value > 0) {
            int filled = Math.max(1, Math.min(w, (int) ((long) w * value / max)));
            graphics.fill(x, y, x + filled, y + h, color);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        int seconds = menu.remainingTicks() / 20;
        // 詰まり(除去装置の取り出し口が満杯)は「停止中」と区別して出す —— レッドストーンを
        // 足しても直らないので、何をすれば動くのかが分からないと詰まる。
        Component status;
        int color = 0xFF404040;
        if (menu.needsFilter()) {
            // フィルター切れは詰まりと分けて出す —— 直し方が違う(胞子を出す/フィルターを入れ替える)。
            status = Component.translatable("gui.overgrown_vegetation.spore_device.no_filter");
            color = 0xFFA03030;
        } else if (menu.isJammed()) {
            status = Component.translatable("gui.overgrown_vegetation.spore_device.jammed");
            color = 0xFFA03030;
        } else if (menu.remainingTicks() > 0) {
            status = Component.translatable("gui.overgrown_vegetation.spore_device.running",
                    seconds / 60, seconds % 60);
        } else {
            status = Component.translatable("gui.overgrown_vegetation.spore_device.idle");
        }
        graphics.drawString(font, status, 8, 58, color, false);
    }
}
