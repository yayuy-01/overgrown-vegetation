package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.ParasitizedCreeper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「過繁茂したクリーパー」のレンダラー。{@link ParasitizedZombieRenderer} などと同じ GeckoLib レンダラーだが、
 * バニラの「爆発直前に膨張して白く光る」演出だけは踏襲している({@link #preRender} で膨張スケール、
 * {@link #getPackedOverlay} で白フラッシュ)。爆発の予兆が見た目で分からないと理不尽なので、
 * GeckoLib 化しても省略しなかった。チャージクリーパーの発光レイヤーは省略(見た目上の些末な差異)。
 */
public class ParasitizedCreeperRenderer extends GeoEntityRenderer<ParasitizedCreeper> {

    public ParasitizedCreeperRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedCreeperModel());
        this.shadowRadius = 0.5F;
    }

    @Override
    public void preRender(PoseStack poseStack, ParasitizedCreeper animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
        if (!isReRender) {
            // バニラ CreeperRenderer#scale と同じ膨張演出。
            float f = animatable.getSwelling(partialTick);
            float f1 = 1.0F + Mth.sin(f * 100.0F) * f * 0.01F;
            f = Mth.clamp(f, 0.0F, 1.0F);
            f *= f;
            f *= f;
            float f2 = (1.0F + f * 0.4F) * f1;
            float f3 = (1.0F + f * 0.1F) / f1;
            poseStack.scale(f2, f3, f2);
        }
    }

    @Override
    public int getPackedOverlay(ParasitizedCreeper animatable, float u, float partialTick) {
        // バニラ CreeperRenderer#getWhiteOverlayProgress と同じ白フラッシュ。
        float swelling = animatable.getSwelling(partialTick);
        float white = (int) (swelling * 10.0F) % 2 == 0 ? 0.0F : Mth.clamp(swelling, 0.5F, 1.0F);
        return OverlayTexture.pack(OverlayTexture.u(white),
                OverlayTexture.v(animatable.hurtTime > 0 || animatable.deathTime > 0));
    }
}
