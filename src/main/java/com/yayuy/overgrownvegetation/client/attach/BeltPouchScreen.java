package com.yayuy.overgrownvegetation.client.attach;

import com.yayuy.overgrownvegetation.gear.attach.BeltPouchMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 収納付きベルトの小物入れの画面(2026-11-21 夜。絵は仮)。 */
public class BeltPouchScreen extends AbstractContainerScreen<BeltPouchMenu> {

    public BeltPouchScreen(BeltPouchMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageHeight = 132;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int n = menu.size();
        int first = (9 - n) / 2;
        AttachBenchScreen.drawRowBackground(g, leftPos, topPos, imageWidth, c -> c >= first && c < first + n);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g); // 1.20.1: 入れ物の画面は背景の暗幕を自分で描く(1.21 は Screen#render が描く)
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
