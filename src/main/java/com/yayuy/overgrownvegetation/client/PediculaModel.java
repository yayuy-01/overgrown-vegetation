package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/**
 * 「ペディキュラ」(グループ4・漂流種)の GeoModel。
 *
 * <h2>sub の出し分け</h2>
 * モデルは本体の {@code main} と、まわりにくっついた塊 {@code sub} / {@code sub2} / {@code sub3} でできている。
 * 塊が<b>個体ごとに付いたり付かなかったり</b>する(ユーザー指定)ので、{@link PediculaEntity#subMask} を見て
 * 骨を隠す。GeckoLib の骨は<b>同じ種類の Mob 全員で使い回し</b>なので({@link LodGeoModel} の説明)、
 * 隠すだけでなく<b>毎回出すほうも書く</b> —— でないと直前に描いた個体の欠け方が移る。
 * {@link LodGeoModel} がアニメを止めて早く戻るフレームでも効くよう、{@code super} の<b>後</b>で書く。
 *
 * <h2>半透明で描く</h2>
 * テクスチャは<b>半透明</b>で描かれている(薄い黄緑のもや α 0.12・暗い緑のまだら α 0.6・芯の粒だけ不透明)。
 * GeckoLib 既定の切り抜き({@code entityCutoutNoCull})は α 0.1 未満を捨てて残りを不透明で塗るので、
 * もやが<b>真っ黄緑の壁</b>になってしまう。{@code entityTranslucent} で描く(裏面も描くので、
 * 手前の面越しに奥の面と内側の小さな立方体が透けて見える)。
 */
public class PediculaModel extends LodGeoModel<PediculaEntity> {

    /** 付いたり付かなかったりする骨。並びは {@link PediculaEntity#subMask} のビットの順。 */
    private static final String[] SUB_BONES = {"sub", "sub2", "sub3"};

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/pedicula.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/pedicula.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/pedicula.animation.json");

    @Override
    public ResourceLocation getModelResource(PediculaEntity animatable) {
        return MODEL;
    }

    /** 亜種の抽選から外れている(歩かない = 移動速度 0)ので、テクスチャは 1 枚だけ。 */
    @Override
    public ResourceLocation getTextureResource(PediculaEntity animatable) {
        return TEXTURE;
    }

    /** アニメーションは無い(中身の空のファイル)。回転は {@link PediculaRenderer} が付ける。 */
    @Override
    public ResourceLocation getAnimationResource(PediculaEntity animatable) {
        return ANIMATION;
    }

    @Override
    public RenderType getRenderType(PediculaEntity animatable, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public void handleAnimations(PediculaEntity animatable, long instanceId,
                                 AnimationState<PediculaEntity> animationState, float partialTick) {
        super.handleAnimations(animatable, instanceId, animationState, partialTick);
        int mask = animatable.subMask();
        for (int i = 0; i < SUB_BONES.length; i++) {
            boolean hidden = (mask & (1 << i)) == 0;
            getBone(SUB_BONES[i]).ifPresent(bone -> bone.setHidden(hidden));
        }
    }
}
