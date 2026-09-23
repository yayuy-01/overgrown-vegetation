package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.texture.AutoGlowingTexture;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.util.Set;

/**
 * ティタニカの<b>発光レイヤー</b>。GeckoLib の {@link software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer}
 * の代わりに使う、<b>光る骨だけを描き直す</b>版。
 *
 * <h2>なぜ差し替えたのか(2026-09-13 の軽量化)</h2>
 * <p>{@code AutoGlowingGeoLayer} は {@code reRender} で<b>モデルを丸ごともう一度描く</b> —
 * 骨 259 本・箱 325 個をすべてもう一度歩き、頂点を積み直す。しかも発光用のレンダータイプは
 * {@code RenderType#eyes} 相当(深度書き込みなし・加算)なので、<b>画面を覆うほど大きいこのボスでは
 * 塗りつぶしの負荷がそのまま 2 倍</b>になる。
 *
 * <p>ところがこのモデルで実際に光るのは<b>増幅器({@code zouhukuki} 系 9 本)だけ</b>
 * (glowmask の 4×4 タイルのうち不透明なのは 1 枚で、その UV を使っている骨がこれだけ)。
 * 残り 250 本は「アルファ 0 の板を画面いっぱいにラスタライズしていただけ」だった。
 *
 * <p>そこで全身の描き直しをやめ、{@link #renderForBone}(本体を描くついでに骨ごとに呼ばれる)で
 * <b>光る骨の箱だけ</b>を発光テクスチャで重ねる。<b>見た目はまったく変わらない</b>。
 */
public class TitanicaGlowLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {

    /**
     * 発光テクセルを持つ骨(増幅器)。glowmask で不透明なタイルの UV を使っている骨を
     * geo.json から洗い出したもの。<b>モデルを差し替えたら数え直すこと</b>。
     */
    private static final Set<String> GLOWING_BONES = Set.of(
            "zouhukuki", "zouhukuki2", "zouhukuki3", "zouhukuki4", "zouhukuki5",
            "zouhukuki6", "zouhukuki7", "zouhukuki8", "zouhukuki9");

    public TitanicaGlowLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /**
     * 本体を描き終わったあとの一括処理。<b>ここでは何もしない</b> —— 全身の描き直しこそが
     * 差し替えの動機なので、{@code super}(何もしない)のまま。
     */
    @Override
    public void render(PoseStack poseStack, T animatable, software.bernie.geckolib.cache.object.BakedGeoModel bakedModel,
                       @Nullable RenderType renderType, MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
    }

    @Override
    public void renderForBone(PoseStack poseStack, T animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (!GLOWING_BONES.contains(bone.getName())) {
            return;
        }
        RenderType glow = AutoGlowingTexture.getRenderType(getTextureResource(animatable));
        if (glow == null) {
            return;
        }
        // poseStack はすでにこの骨の位置・向きになっている(renderRecursively が prepMatrixForBone 済み)。
        // 明るさは FULL_SKY = 常に最大(発光なので周りの明るさに左右されない)。
        getRenderer().renderCubesOfBone(poseStack, bone, bufferSource.getBuffer(glow),
                LightTexture.FULL_SKY, packedOverlay,
                getRenderer().getRenderColor(animatable, partialTick, packedLight).argbInt());
    }
}
