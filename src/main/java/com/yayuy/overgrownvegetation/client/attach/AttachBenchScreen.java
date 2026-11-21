package com.yayuy.overgrownvegetation.client.attach;

import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import com.yayuy.overgrownvegetation.gear.attach.AttachBenchMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 付け物の台の画面(2026-11-21 夜。絵は仮: バニラの 1 行の箱の絵の上に、使う枠だけを残す)。
 * 左 = 防具、右の 3 枠 = その防具の付け物。防具を置くまで付け物の枠は暗い。
 */
public class AttachBenchScreen extends AbstractContainerScreen<AttachBenchMenu> {

    static final ResourceLocation BG = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    static final int PANEL = 0xFFC6C6C6;

    public AttachBenchScreen(AttachBenchMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageHeight = 132;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    /** 1 行の箱の背景を描き、{@code keep} に無い枠を塗りつぶす。 */
    static void drawRowBackground(GuiGraphics g, int x, int y, int w, java.util.function.IntPredicate keep) {
        g.blit(BG, x, y, 0, 0, w, 35);
        g.blit(BG, x, y + 35, 0, 126, w, 96);
        for (int c = 0; c < 9; c++) {
            if (!keep.test(c)) g.fill(x + 7 + c * 18, y + 17, x + 7 + c * 18 + 18, y + 17 + 18, PANEL);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        int n = ArmorAttachments.slotCount();
        drawRowBackground(g, x, y, imageWidth, c -> c == 1 || (c >= 4 && c < 4 + n));
        // 防具と付け物のあいだの矢印(仮)
        g.drawString(font, "→", x + 54, y + 22, 0xFF404040, false);
        if (menu.armor().isEmpty()) {
            for (int i = 0; i < n; i++) {
                int sx = x + AttachBenchMenu.ATTACH_X + i * 18, sy = y + AttachBenchMenu.ROW_Y;
                g.fill(sx, sy, sx + 16, sy + 16, 0x80000000);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        if (menu.armor().isEmpty() && hoveredSlot == null && mouseX >= leftPos + AttachBenchMenu.ATTACH_X && mouseX < leftPos + AttachBenchMenu.ATTACH_X + 54
                && mouseY >= topPos + AttachBenchMenu.ROW_Y && mouseY < topPos + AttachBenchMenu.ROW_Y + 16) {
            g.renderTooltip(font, Component.translatable("container.overgrown_vegetation.attachment_bench.place_armor"), mouseX, mouseY);
        }
    }
}
