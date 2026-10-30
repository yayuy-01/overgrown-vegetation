package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

/**
 * 空の巨大生物({@link SkyBossImpostor})を GeckoLib で描く。攻城戦のボスの {@code TitanicaImpostorRenderer} と同じく、{@link GeoObjectRenderer} が
 * {@code preRender} でモデルを (0.5, 0.51, 0.5) ずらすのを外す(位置合わせは {@link SkyBossRenderer} の PoseStack)。
 * 描く型は自前のシェーダー({@link SkyBossShaders}。遠い所だけ寄せる・霞)。垂れ草が厚みゼロの板なので裏も描く。
 * シェーダーが無いときはふつうの型で、霞は頂点の色で掛ける。
 */
public final class SkyBossImpostorRenderer extends GeoObjectRenderer<SkyBossImpostor> {

    private Color color = Color.ofOpaque(0xFFFFFFFF);

    public SkyBossImpostorRenderer() {
        super(new SkyBossModel());
        // 10-19: 電紋・避雷針・扇の先が光る層(放電の溜め・予告・放電中)
        addRenderLayer(new SkyBossGlowLayer(this));
    }

    public void setColor(int argb) {
        this.color = Color.ofOpaque(argb);
    }

    @Override
    public Color getRenderColor(SkyBossImpostor animatable, float partialTick, int packedLight) {
        return color;
    }

    @Override
    public RenderType getRenderType(SkyBossImpostor animatable, ResourceLocation texture,
                                    @Nullable MultiBufferSource bufferSource, float partialTick) {
        RenderType own = SkyBossShaders.renderType(texture);
        return own != null ? own : super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    @Override
    public void preRender(PoseStack poseStack, SkyBossImpostor animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        this.objectRenderTranslations = new Matrix4f(poseStack.last().pose());
        scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);
    }
}
