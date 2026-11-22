package com.yayuy.overgrownvegetation.client.ley;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.ley.LeyPylonItem;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** 地脈の柱のアイテムの描画(2026-11-22)。置いた柱と同じ模型で、電池の箱は隠す。 */
public class LeyPylonItemRenderer extends GeoItemRenderer<LeyPylonItem> {

    public LeyPylonItemRenderer() {
        super(new LeyPylonModel<>());
    }

    @Override
    public void preRender(PoseStack poseStack, LeyPylonItem animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        LeyPylonModel.showBattery(model, false);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
    }
}
