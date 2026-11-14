package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.runner.BodyBendRig;
import com.yayuy.overgrownvegetation.client.runner.LegChainRig;
import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ヴェナムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\venamnes.bbmodel}(11-14 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g5_pursuer_r9} を写した。ユーザーが一覧の絵を見て「このままでよい」とした形)。
 *
 * <p>コードが足すもの(Blockbench には出ない): 脚の IK と胴 {@code hips} の高さ・傾き({@link LegChainRig})、
 * 首 neck0〜head・尾 tail0〜tail3 の左右の曲げ(継ぎ目の箱 j_* は半分。{@link BodyBendRig})。骨の名前を変えるとこれらが黙って効かなくなる。
 */
public class VenamnesModel extends GeoModel<VenamnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/venamnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/venamnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/venamnes.animation.json");

    /**
     * 脚の IK【仮】: 変数の範囲(休めの形からの足し分、度)は付け根 X ±60・付け根 Z ±20・ひざ ±75・足首 ±75、
     * 引き寄せの重みは付け根 X 1.5・Z 2.0・ひざ 1.0・足首 0.8。基準は休めの形(walk を流したままでも脚だけ IK で置く)。
     */
    private final LegChainRig legs = new LegChainRig(
            new String[][]{{"thigh_l", "shin_l", "tars_l", "foot_l"}, {"thigh_r", "shin_r", "tars_r", "foot_r"}},
            new String[]{"root", "hips"}, VenamnesEntity.RENDER_SCALE,
            new double[]{60, 20, 75, 75}, new double[]{1.5, 2.0, 1.0, 0.8}, true);

    private final BodyBendRig bend = new BodyBendRig(
            new String[]{"neck0", "neck1", "neck2", "head"}, new String[]{"j_neck0", "j_neck1", "j_neck2", "j_head"},
            new String[]{"tail0", "tail1", "tail2", "tail3"}, new String[]{null, null, null, "j_tail3"});

    @Override
    public ResourceLocation getModelResource(VenamnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(VenamnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(VenamnesEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(VenamnesEntity animatable, long instanceId, AnimationState<VenamnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float pt = animationState.getPartialTick();
        this.legs.apply(this, animatable, pt);
        this.bend.apply(this, animatable, pt);
    }
}
