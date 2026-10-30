package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * 電紋・避雷針・扇の先を光らせる層(2026-10-19)。同じ骨の形のまま体をもう一度描く({@code reRender}。アニメは進めない)が、型は光る層
 * ({@link SkyBossShaders#glowType})で、絵は電紋の型。光り方は描く前に {@link SkyBossRenderer} が {@link SkyBossGlow} から渡す。
 * 何も光らないフレームは描かない。
 */
final class SkyBossGlowLayer extends GeoRenderLayer<SkyBossImpostor> {

    /** このフレームに光らせるか({@link SkyBossRenderer} が決める)。 */
    static boolean on;

    SkyBossGlowLayer(GeoRenderer<SkyBossImpostor> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, SkyBossImpostor animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        RenderType glow = SkyBossShaders.glowType();
        if (!on || glow == null) {
            return;
        }
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, glow, bufferSource.getBuffer(glow), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
    }
}
