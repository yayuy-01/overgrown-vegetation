package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

/**
 * 遠距離用インポスタ({@link TitanicaImpostor})を GeckoLib で描く描画器。
 *
 * <p>{@link GeoObjectRenderer} はブロック的な用途を想定して {@code preRender} でモデルを
 * {@code (0.5, 0.51, 0.5)} だけ平行移動するので、それを外している(位置合わせは呼び出し側の
 * {@link SiegeBossRenderer} が PoseStack で行う)。距離に応じた霞の色は {@link #setColor} で受け取り、
 * 頂点色として乗算する。
 */
public final class TitanicaImpostorRenderer extends GeoObjectRenderer<TitanicaImpostor> {

    private Color color = Color.ofOpaque(0xFFFFFFFF);

    public TitanicaImpostorRenderer(GeoModel<TitanicaImpostor> model) {
        super(model);
        // 全身を描き直す AutoGlowingGeoLayer ではなく、光る骨だけを重ねる版(TitanicaGlowLayer の javadoc)。
        addRenderLayer(new TitanicaGlowLayer<>(this));
    }

    /** 裏面を描かない(実エンティティ側と同じ理由。{@code siegeCullBackFaces})。 */
    @Override
    public RenderType getRenderType(TitanicaImpostor animatable, ResourceLocation texture,
                                    @Nullable MultiBufferSource bufferSource, float partialTick) {
        return OvergrownVegetationConfig.COMMON.siegeCullBackFaces.get()
                ? RenderType.entityCutout(texture)
                : super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    public void setColor(int argb) {
        this.color = Color.ofOpaque(argb);
    }

    @Override
    public Color getRenderColor(TitanicaImpostor animatable, float partialTick, int packedLight) {
        return color;
    }

    @Override
    public void preRender(PoseStack poseStack, TitanicaImpostor animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        this.objectRenderTranslations = new Matrix4f(poseStack.last().pose());
        scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);
    }
}
