package com.yayuy.overgrownvegetation.client.decon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.survival.decon.WasherBlockItem;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** 地脈の洗い場のアイテムの描画(2026-11-22)。止まった姿で、模型の水の箱を見せる(アイテムには地形の模型の水の面が無いため)。 */
public class WasherItemRenderer extends GeoItemRenderer<WasherBlockItem> {

    public WasherItemRenderer() {
        super(new WasherModel<>());
    }

    @Override
    public void preRender(PoseStack poseStack, WasherBlockItem animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        WasherModel.showParts(model, true, false);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
