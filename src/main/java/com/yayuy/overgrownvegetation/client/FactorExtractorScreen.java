package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.block.FactorExtractorBlockEntity;
import com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe;
import com.yayuy.overgrownvegetation.menu.FactorExtractorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * 因子の抽出設備の画面(順 1 の着手順 7、2026-10-03)。<b>除染装置と同じ灰色</b>(ユーザー指定)で、絵を使わずコードで塗る。
 * 入れる枠 → 進捗の矢印 → 出る枠。下に「ドロップ 4 個 → 衝撃の因子(10 秒)」のような 1 行。
 */
public class FactorExtractorScreen extends AbstractContainerScreen<FactorExtractorMenu> {

    // 色は除染装置(DecontaminatorScreen)と同じ
    private static final int PANEL_BG = 0xFFC6C6C6;
    private static final int PANEL_BORDER = 0xFF373737;
    private static final int SLOT_DARK = 0xFF8B8B8B;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int ARROW_BG = 0xFF8B8B8B;
    private static final int ARROW_FILL = 0xFF57C25A;
    private static final int TEXT = 0xFF404040;
    private static final int TEXT_NG = 0xFFA03030;

    private static final int ARROW_X = 79;
    private static final int ARROW_Y = FactorExtractorMenu.SLOT_Y;

    public FactorExtractorScreen(FactorExtractorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL_BG);
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_DARK);
            graphics.fill(sx + 1, sy + 1, sx + 18, sy + 18, SLOT_LIGHT);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_DARK);
        }
        // 進捗の矢印(軸 16 + 先 6)
        int ax = x + ARROW_X;
        int ay = y + ARROW_Y;
        arrow(graphics, ax, ay, 22, ARROW_BG);
        int total = this.menu.total();
        int progress = this.menu.progress();
        if (total > 0 && progress > 0) {
            arrow(graphics, ax, ay, Math.max(1, Math.min(22, 22 * progress / total)), ARROW_FILL);
        }
    }

    /** 左から {@code width} 列だけ描いた右向きの矢印(高さ 16)。 */
    private static void arrow(GuiGraphics g, int x, int y, int width, int color) {
        g.fill(x, y + 6, x + Math.min(width, 16), y + 10, color);
        for (int i = 0; i < 6 && 16 + i < width; i++) {
            g.fill(x + 16 + i, y + 2 + i, x + 17 + i, y + 14 - i, color);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        ItemStack input = this.menu.getSlot(FactorExtractorBlockEntity.INPUT).getItem();
        Component line;
        int color = TEXT;
        if (input.isEmpty()) {
            line = Component.translatable("gui.overgrown_vegetation.factor_extractor.idle");
        } else {
            RecipeHolder<FactorExtractionRecipe> holder = FactorExtractorBlockEntity.recipeFor(this.menu.level(), input);
            if (holder == null) {
                line = Component.translatable("gui.overgrown_vegetation.factor_extractor.no_recipe");
                color = TEXT_NG;
            } else {
                FactorExtractionRecipe r = holder.value();
                line = Component.translatable("gui.overgrown_vegetation.factor_extractor.recipe", r.count(),
                        r.result().getHoverName(), String.format("%.0f", r.time() / 20.0D));
                if (input.getCount() < r.count()) {
                    color = TEXT_NG;
                }
            }
        }
        graphics.drawString(this.font, line, 8, 60, color, false);
    }
}
