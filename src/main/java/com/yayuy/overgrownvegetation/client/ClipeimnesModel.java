package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.runner.BodyBendRig;
import com.yayuy.overgrownvegetation.client.runner.SprawlLegRig;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「クリペイムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\clipeimnes.bbmodel}(11-15 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g5_bulwark_r7} を写した。ユーザーが一覧の絵を見て「このままでよい」とした形)。
 *
 * <p>コードが足すもの(Blockbench には出ない): 歩く脚 4 本の IK と根 {@code root} の高さ・傾き({@link SprawlLegRig})、
 * 首 neck・頭 head・尾 tail1〜tail4 の左右の曲げ({@link BodyBendRig})。骨の名前を変えるとこれらが黙って効かなくなる。
 * 盾の腕(uarm・forearm・scale・hook)は手付けのアニメのまま。
 */
public class ClipeimnesModel extends GeoModel<ClipeimnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/clipeimnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/clipeimnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/clipeimnes.animation.json");

    /**
     * 脚の IK【仮】: 変数の範囲(休めの形からの足し分、度)は付け根 Y ±55・付け根 Z ±45・ひざ Z ±60・ひざ X ±30、
     * 引き寄せの重みは 1.0・1.5・1.0・2.0(ひざの X は斜めの軸を少し使うだけ)。並びは 中左・中右・後左・後右(エンティティの置き場と同じ)。
     */
    private final SprawlLegRig legs = new SprawlLegRig(
            new String[][]{{"leg_m_l", "shin_m_l", "foot_m_l"}, {"leg_m_r", "shin_m_r", "foot_m_r"},
                    {"leg_b_l", "shin_b_l", "foot_b_l"}, {"leg_b_r", "shin_b_r", "foot_b_r"}},
            new String[][]{{"root", "thorax"}, {"root", "thorax"}, {"root", "abdomen"}, {"root", "abdomen"}},
            "root", ClipeimnesEntity.RENDER_SCALE,
            new double[]{55, 45, 60, 30}, new double[]{1.0, 1.5, 1.0, 2.0});

    private final BodyBendRig bend = new BodyBendRig(
            new String[]{"neck", "head", null, null}, new String[]{null, null, null, null},
            new String[]{"tail1", "tail2", "tail3", "tail4"}, new String[]{null, null, null, null});

    @Override
    public ResourceLocation getModelResource(ClipeimnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ClipeimnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ClipeimnesEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(ClipeimnesEntity animatable, long instanceId, AnimationState<ClipeimnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float pt = animationState.getPartialTick();
        this.legs.apply(this, animatable, pt);
        this.bend.apply(this, animatable, pt);
    }
}
