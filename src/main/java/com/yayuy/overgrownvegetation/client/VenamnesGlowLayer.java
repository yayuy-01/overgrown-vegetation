package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import javax.annotation.Nullable;

/**
 * ヴェナムネスの口と腱の光(2026-11-15 段 A。決定「光は行動のときだけ」: 腱 = 飛びかかりの溜めと狩りの疾走の間、
 * 口 = 噛みつきの溜めと疾走の叫び)。同じ UV の別の絵 2 枚(透明の上に光る画素だけ。Fable のセッションが bbmodel の骨の UV で分けた)を、
 * 光る描き方でもう一度重ねる。明るさは {@link VenamnesEntity#mouthGlow}・{@link VenamnesEntity#tendonGlow}(0 なら描かない)。
 * GeckoLib の {@code AutoGlowingGeoLayer} は常に光る・光る画素 0 の絵で落ちるので使わない(ファルキムネスの鎌の筋と同じ作り)。
 *
 * <p>絵がまだ MOD に無いときは描かない(無い絵は紫と黒の格子になり、それが全身に光って重なる)。有るかは描画が作り直される
 * (資源の読み直し)ごとに 1 回だけ見る。
 */
public class VenamnesGlowLayer extends GeoRenderLayer<VenamnesEntity> {

    static final ResourceLocation MOUTH = OvergrownVegetation.id("textures/entity/venamnes_glow_mouth.png");
    static final ResourceLocation TENDON = OvergrownVegetation.id("textures/entity/venamnes_glow_tendon.png");

    private Boolean hasMouth, hasTendon;

    public VenamnesGlowLayer(GeoRenderer<VenamnesEntity> renderer) {
        super(renderer);
    }

    private static boolean exists(ResourceLocation rl) {
        boolean ok = Minecraft.getInstance().getResourceManager().getResource(rl).isPresent();
        if (!ok) OvergrownVegetation.LOGGER.info("ヴェナムネスの発光マスクがまだ無いので光らせない: {}", rl);
        return ok;
    }

    @Override
    public void render(PoseStack poseStack, VenamnesEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float mouth = animatable.mouthGlow(partialTick), tendon = animatable.tendonGlow(partialTick);
        if (mouth > 0.01F) {
            if (this.hasMouth == null) this.hasMouth = exists(MOUTH);
            if (this.hasMouth) draw(MOUTH, mouth, poseStack, animatable, bakedModel, bufferSource, partialTick);
        }
        if (tendon > 0.01F) {
            if (this.hasTendon == null) this.hasTendon = exists(TENDON);
            if (this.hasTendon) draw(TENDON, tendon, poseStack, animatable, bakedModel, bufferSource, partialTick);
        }
    }

    private void draw(ResourceLocation tex, float glow, PoseStack poseStack, VenamnesEntity animatable, BakedGeoModel bakedModel,
                      MultiBufferSource bufferSource, float partialTick) {
        RenderType rt = RenderType.entityTranslucentEmissive(tex);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
