package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.GeoCorpseBlockItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * 死体アイテム({@link GeoCorpseBlockItem})を、ブロックと同じ GeckoLib 立体モデルで
 * 手持ち/インベントリ/落下アイテムとして描画する。
 *
 * <p>GUI/手持ちでの向き・位置・大きさは、アイテムモデル JSON
 * ({@code assets/overgrown_vegetation/models/item/overgrown_(種族)_corpse.json})の
 * {@code display} 部分で調整する({@code ClientHooks.handleCameraTransforms} が
 * この BEWLR に渡す前に適用するため)。インベントリでの照明は 3D モデル向けに切り替える。
 */
public class CorruptedCorpseItemRenderer extends GeoItemRenderer<GeoCorpseBlockItem> {

    public CorruptedCorpseItemRenderer() {
        super(new CorruptedCorpseItemModel());
        useAlternateGuiLighting();
    }

    /**
     * 崩れ落ちに対応した死体(2026-10-09)は、倒れた軸(足元)を原点に前へ 2〜3 ブロック伸びているので、
     * 外接箱の真ん中を原点へ寄せてからアイコンにする(geo の x は描画で反転する)。
     */
    @Override
    public void preRender(com.mojang.blaze3d.vertex.PoseStack poseStack, com.yayuy.overgrownvegetation.item.GeoCorpseBlockItem animatable,
                          software.bernie.geckolib.cache.object.BakedGeoModel model, net.minecraft.client.renderer.MultiBufferSource bufferSource,
                          com.mojang.blaze3d.vertex.VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
                          int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
        if (!isReRender) {
            String species = com.yayuy.overgrownvegetation.corpse.CorpseSpecies.speciesOf(animatable.getBlock());
            var pose = species == null ? null : com.yayuy.overgrownvegetation.client.corpse.CorpsePoses.get(species);
            if (pose != null) {
                poseStack.translate(pose.center()[0] / 16.0F, 0.0F, -pose.center()[1] / 16.0F);
            }
        }
    }
}
