package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.texture.AutoGlowingTexture;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * スペラエオプレウラの<b>発光レイヤー</b>。背を走る血管状の筋を光らせる。
 *
 * <h2>{@code AutoGlowingGeoLayer} を使わない理由</h2>
 * <p>GeckoLib 既定の {@code AutoGlowingGeoLayer} は発光を<b>常に全開</b>で描く。この種は
 * 「発光菌糸を食べると光り、時間をかけて暗くなる」ので、<b>明るさを途中の値で出せないと成立しない</b>。
 * ここでは {@link SpelaeopleuraEntity#getGlow()}(0〜1)をそのまま<b>頂点色のアルファ</b>に入れて、
 * 同じ発光テクスチャを薄く重ねる。
 *
 * <p>ハイヴノードのように mcmeta の駒送りで明滅させる手もあったが、それだと<b>何も食べていなくても
 * 決まった周期で光る</b>(ユーザー指定 2026-09-21 の「ゆっくり切り替わる」は満たすが、
 * 「食べたから光る」が画面に出ない)。
 *
 * <h2>消えているときは 1 枚も描かない</h2>
 * <p>洞窟には同時に何匹も居る。光っていない個体まで毎フレーム重ね描きすると、
 * <b>アルファ 0 の板をラスタライズするだけの無駄</b>になる(ティタニカで実際に効いた話 ——
 * {@link TitanicaGlowLayer} 参照)。{@link #MIN_VISIBLE} 未満なら早々に諦める。
 */
public class SpelaeopleuraGlowLayer extends GeoRenderLayer<SpelaeopleuraEntity> {

    /** これ未満の明るさは描かない(見えないものを描く意味がない)。 */
    private static final float MIN_VISIBLE = 0.02F;

    public SpelaeopleuraGlowLayer(GeoRenderer<SpelaeopleuraEntity> renderer) {
        super(renderer);
    }

    /** 本体を描き終わったあとの一括処理。骨ごとに重ねるので、ここでは何もしない。 */
    @Override
    public void render(PoseStack poseStack, SpelaeopleuraEntity animatable, BakedGeoModel bakedModel,
                       @Nullable RenderType renderType, MultiBufferSource bufferSource,
                       @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
    }

    @Override
    public void renderForBone(PoseStack poseStack, SpelaeopleuraEntity animatable, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.getGlow();
        if (glow < MIN_VISIBLE) {
            return;
        }
        RenderType emissive = AutoGlowingTexture.getRenderType(getTextureResource(animatable));
        if (emissive == null) {
            return;
        }
        // 明るさはアルファで作る。RGB は白のまま(色はテクスチャ側が持っている)。
        int argb = (Math.round(Math.min(1.0F, glow) * 255.0F) << 24) | 0x00FFFFFF;
        // poseStack はすでにこの骨の位置・向き(renderRecursively が prepMatrixForBone 済み)。
        // 明るさは FULL_SKY = 周りの暗さに左右されない。洞窟で光らないと意味がないので。
        getRenderer().renderCubesOfBone(poseStack, bone, bufferSource.getBuffer(emissive),
                LightTexture.FULL_SKY, packedOverlay, argb);
    }
}
