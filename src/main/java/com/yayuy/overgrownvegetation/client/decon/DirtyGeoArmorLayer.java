package com.yayuy.overgrownvegetation.client.decon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.survival.decon.GearDirt;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * GeckoLib で描く防具(防護マスク)の体に着た姿に、汚れの粉を重ねる(2026-11-14 サバイバルの背骨 段 3b、決定 37)。
 * 同じ形をもう一度、元の絵の不透明な所だけに点を置いた絵({@link DirtOverlays#armorOverlay})で描く。
 */
public class DirtyGeoArmorLayer<T extends net.minecraft.world.item.Item & software.bernie.geckolib.animatable.GeoItem> extends GeoRenderLayer<T> {

    public DirtyGeoArmorLayer(GeoArmorRenderer<T> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType, MultiBufferSource bufferSource,
                       com.mojang.blaze3d.vertex.VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (buffer == null || !(getRenderer() instanceof GeoArmorRenderer<?> armor)) return;
        ItemStack stack = armor.getCurrentStack();
        int stage = stack == null ? 0 : GearDirt.stage(stack);
        if (stage == 0) return;
        ResourceLocation dust = DirtOverlays.armorOverlay(getTextureResource(animatable), stage);
        if (dust == null) return;
        RenderType rt = RenderType.armorCutoutNoCull(dust);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}
