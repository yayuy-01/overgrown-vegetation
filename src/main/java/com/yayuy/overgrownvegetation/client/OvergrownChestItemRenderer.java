package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.block.OvergrownChestBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 「過繫茂したチェスト」のアイテム(インベントリ/手持ち/額縁/落ちているアイテム)を、ブロックと同じ
 * 立体モデル({@link OvergrownChestRenderer})で描く。バニラのチェストが {@code builtin/entity} モデル +
 * {@code BlockEntityWithoutLevelRenderer} で描かれるのと同じ仕組みで、ワールドに属さないダミーの
 * BlockEntity を 1 つ作って使い回す(描画側は {@code getLevel() == null} を「アイテム描画」とみなす)。
 * 登録は {@link OvergrownVegetationClient#registerClientExtensions}。
 */
public class OvergrownChestItemRenderer extends BlockEntityWithoutLevelRenderer {

    private OvergrownChestBlockEntity dummy;

    public OvergrownChestItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (dummy == null) {
            dummy = new OvergrownChestBlockEntity(BlockPos.ZERO, ModBlocks.OVERGROWN_CHEST.get().defaultBlockState());
        }
        Minecraft.getInstance().getBlockEntityRenderDispatcher().renderItem(dummy, poseStack, buffer, packedLight, packedOverlay);
    }
}
