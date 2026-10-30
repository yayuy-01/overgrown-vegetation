package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
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

    /*
     * 2026-09-24 軽量化(監査 490)。
     * - 発光の RenderType は骨ごとに AutoGlowingTexture.getRenderType で引き直していた(ResourceLocation の生成と
     *   TextureManager の検索を含む)ので、テクスチャが同じ間は 1 つを使い回す(GeckoLib 側も同じ物を memoize で返す)。
     * - 面を 1 枚も描かない骨(非表示・直方体なし)では getBuffer を呼ばない。呼ぶと本体のバッファが確定して
     *   描画命令が増えるだけで、描く中身も順番も同じ。
     * 全身を 1 回の reRender で重ねる案は入れていない: GeckoLib は発光の画素を本体のテクスチャから抜く(穴になる)ので、
     * 骨ごとに重ねる今の順番だと穴の奥に「後から描く骨」は写らない。まとめて重ねるとそれが写り、見た目が変わる。
     * 描くのはレンダースレッドだけなので、控えは素のフィールドでよい。
     */
    @Nullable
    private static ResourceLocation cachedTexture;
    @Nullable
    private static RenderType cachedEmissive;

    @Override
    public void renderForBone(PoseStack poseStack, SpelaeopleuraEntity animatable, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.getGlow();
        if (glow < MIN_VISIBLE) {
            return;
        }
        if (bone.isHidden() || bone.getCubes().isEmpty()) {
            return; // renderCubesOfBone が何も描かない骨
        }
        ResourceLocation texture = getTextureResource(animatable);
        RenderType emissive = cachedEmissive;
        if (emissive == null || !texture.equals(cachedTexture)) {
            emissive = AutoGlowingTexture.getRenderType(texture);
            cachedTexture = texture;
            cachedEmissive = emissive;
        }
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
