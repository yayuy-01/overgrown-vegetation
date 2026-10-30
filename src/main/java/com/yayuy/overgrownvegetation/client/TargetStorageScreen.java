package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.menu.TargetStorageMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * ターゲットブロックの収納({@link TargetStorageMenu})の画面。見た目はバニラのラージチェストの画面そのもの
 * (テクスチャもバニラの {@code generic_54.png}。新しい素材は作らない)に、右の外側へページのボタン 1〜4 を足したもの。
 * 見出しの右にページ、インベントリの見出しの右にターゲットの耐久を出す。
 */
public class TargetStorageScreen extends AbstractContainerScreen<TargetStorageMenu> {

    private static final ResourceLocation CHEST = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int TAB_SIZE = 20;
    private static final int TAB_GAP = 2;

    private final Button[] tabs = new Button[TargetStorageMenu.PAGES];

    public TargetStorageScreen(TargetStorageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 114 + TargetStorageMenu.ROWS * 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < this.tabs.length; i++) {
            int page = i;
            this.tabs[i] = addRenderableWidget(Button.builder(Component.literal(Integer.toString(i + 1)), b -> open(page))
                    .bounds(this.leftPos + this.imageWidth + TAB_GAP, this.topPos + 17 + i * (TAB_SIZE + TAB_GAP), TAB_SIZE, TAB_SIZE)
                    .tooltip(Tooltip.create(Component.translatable("gui.overgrown_vegetation.target_block.page_tip", i + 1)))
                    .build());
        }
        updateTabs();
    }

    private void open(int page) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, page);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateTabs();
    }

    /** 今のページのボタンだけ押せなくする(= 選ばれている印)。 */
    private void updateTabs() {
        int page = this.menu.page();
        for (int i = 0; i < this.tabs.length; i++) {
            if (this.tabs[i] != null) {
                this.tabs[i].active = i != page;
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int rows = TargetStorageMenu.ROWS;
        graphics.blit(CHEST, this.leftPos, this.topPos, 0, 0, this.imageWidth, rows * 18 + 17);
        graphics.blit(CHEST, this.leftPos, this.topPos + rows * 18 + 17, 0, 126, this.imageWidth, 96);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        Component page = Component.translatable("gui.overgrown_vegetation.target_block.page",
                this.menu.page() + 1, TargetStorageMenu.PAGES);
        graphics.drawString(this.font, page, this.imageWidth - 8 - this.font.width(page), this.titleLabelY, LABEL_COLOR, false);
        if (this.menu.maxHp() > 0) {
            Component hp = Component.translatable("gui.overgrown_vegetation.target_block.hp", this.menu.hp(), this.menu.maxHp());
            graphics.drawString(this.font, hp, this.imageWidth - 8 - this.font.width(hp), this.inventoryLabelY, LABEL_COLOR, false);
        }
    }
}
