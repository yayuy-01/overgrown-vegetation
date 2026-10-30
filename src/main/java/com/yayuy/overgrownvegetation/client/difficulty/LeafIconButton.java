package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 難易度の画面を開く 20 × 20 の葉っぱのボタン(ワールド作成画面の「難易度」の右隣と、ポーズ画面の「設定」の左隣)。
 *
 * <p>絵はユーザー指定で<b>研究ツリーのいちばん最初のノード({@code plant_research})と同じ葉っぱ</b>。研究台の画面と同じ
 * {@code textures/research/plant_research.png}(16 × 16)をそのまま描く —— ボタン用の絵を別に置かない
 * (素材を既存のフォルダ以外に置くと公開リポジトリに載ってしまう)。バニラの {@code SpriteIconButton} は GUI の
 * スプライトの置き場の絵しか描けないので、ふつうのボタンの枠の上に自分で描く。
 */
public final class LeafIconButton extends Button {

    public static final int SIZE = 20;

    private static final ResourceLocation ICON = OvergrownVegetation.id("textures/research/plant_research.png");
    private static final Component LABEL = Component.translatable("gui.overgrown_vegetation.world_difficulty.button");

    public LeafIconButton(int x, int y, OnPress onPress) {
        super(x, y, SIZE, SIZE, LABEL, onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(LABEL));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick); // 枠(ふつうのボタン)。文字は下で消してある
        graphics.blit(ICON, getX() + (SIZE - 16) / 2, getY() + (SIZE - 16) / 2, 0.0F, 0.0F, 16, 16, 16, 16);
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        // 文字は出さない(名前は読み上げとツールチップのためだけに持つ)。
    }
}
