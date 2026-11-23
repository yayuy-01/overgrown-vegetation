package com.yayuy.overgrownvegetation.client.crawler;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.model.GeoModel;
import com.yayuy.overgrownvegetation.client.crowd.CrowdGeoEntityRenderer;

/**
 * 「小さな地這いの虫」の型の描画(2026-11-15)。バニラの向きに、段の上り下りの<b>前後の傾き</b>と、段を這い上がった 1 tick の跳ねの
 * <b>ならし</b>を足す(どちらも {@link SmallCrawler} がクライアントで出す)。傾きの軸は体の真ん中の高さ。
 */
public class SmallCrawlerRenderer<T extends SmallCrawler & GeoEntity> extends CrowdGeoEntityRenderer<T> {

    public SmallCrawlerRenderer(EntityRendererProvider.Context context, GeoModel<T> model, float scale, float shadow) {
        super(context, model);
        if (scale != 1.0F) this.withScale(scale);
        this.shadowRadius = shadow;
    }

    @Override
    protected void applyRotations(T animatable, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick) {
        float nativeScale = com.yayuy.overgrownvegetation.client.PortRenderScale.of(animatable); // 1.20.1: 4.8.4 は nativeScale を渡さない(PortRenderScale)
        poseStack.translate(0.0F, animatable.lift(partialTick) / nativeScale, 0.0F);
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick);
        float tilt = animatable.tilt(partialTick);
        if (Math.abs(tilt) > 0.05F) {
            float pivot = animatable.getBbHeight() * 0.5F / nativeScale;
            poseStack.translate(0.0F, pivot, 0.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(tilt));   // 正 = 頭(モデルの −Z)が上がる
            poseStack.translate(0.0F, -pivot, 0.0F);
        }
    }

    @Override
    protected float getDeathMaxRotation(T animatable) {
        return 0.0F;   // 死ぬときの倒れ方は段 1 では付けない(演出は後で相談)
    }
}
