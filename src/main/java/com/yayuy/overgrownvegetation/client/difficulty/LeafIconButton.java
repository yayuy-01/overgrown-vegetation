package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * MOD の設定ページを開く 20 × 20 のボタン(ワールド作成画面の「難易度」の右隣と、ポーズ画面の「設定…」の左隣)。
 *
 * <p>絵は巨大樹の苗木のアイテム(2026-11-19 仮決め。前の版の研究ツリーの葉っぱの絵は研究ツリーの作り直しで無くなった)。
 * ボタン用の絵を別に置かない(素材を既存のフォルダ以外に置くと公開リポジトリに載ってしまう)ので、アイテムの絵をそのまま描く。
 */
public final class LeafIconButton extends Button {

    public static final int SIZE = 20;

    private static final Component LABEL = Component.translatable("gui.overgrown_vegetation.world_difficulty.button");

    public LeafIconButton(int x, int y, OnPress onPress) {
        super(x, y, SIZE, SIZE, LABEL, onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(LABEL));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick); // 枠(ふつうのボタン)。文字は下で消してある
        graphics.renderFakeItem(new ItemStack(ModItems.GIANT_OVERGROWN_SAPLING_ITEM.get()), getX() + 2, getY() + 2);
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        // 文字は出さない(名前は読み上げとツールチップのためだけに持つ)。
    }
}
