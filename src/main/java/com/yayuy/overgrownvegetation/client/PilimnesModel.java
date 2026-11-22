package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.lizard.SpineWaveRig;
import com.yayuy.overgrownvegetation.client.lizard.SprawlLegRig;
import com.yayuy.overgrownvegetation.client.runner.BodyBendRig;
import com.yayuy.overgrownvegetation.entity.PilimnesEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ピリムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\pilimnes.bbmodel}(11-15 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g5_lancer_r7} を写した。ユーザーが一覧の絵を見て「このままでよい」とした形)。
 *
 * <p>コードが足すもの(Blockbench には出ない): 背骨のくねりと止まっている間の見回し({@link SpineWaveRig}。胸 chest・腰 hip・尾 tail0〜4・首)、
 * 横へ張った脚の IK と胴の高さ・傾き({@link SprawlLegRig}。前は upf → lof、後ろは uph → loh → foh)、
 * 首 neck0〜head・尾 tail0〜tail3 の経路に沿った曲げ({@link BodyBendRig})。骨の名前を変えるとこれらが黙って効かなくなる。
 */
public class PilimnesModel extends GeoModel<PilimnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/pilimnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/pilimnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/pilimnes.animation.json");

    /**
     * 脚の IK【仮】: 変数の範囲(休めからの足し分、度)は付け根 Y ±75・付け根 Z ±45・2 つ目 X ±50・2 つ目 Z ±60、
     * 休めへ引く重みは 0.4・1.0・1.2・0.8(足を前後へ運ぶのは主に付け根の水平の振り)。
     */
    private final SprawlLegRig legs = new SprawlLegRig(
            new String[][]{{"upf_l", "lof_l"}, {"upf_r", "lof_r"}, {"uph_l", "loh_l", "foh_l"}, {"uph_r", "loh_r", "foh_r"}},
            new String[][]{{"root", "chest"}, {"root", "chest"}, {"root", "chest", "hip"}, {"root", "chest", "hip"}},
            "chest", PilimnesEntity.RENDER_SCALE,
            new double[]{75, 45, 50, 60}, new double[]{0.4, 1.0, 1.2, 0.8});

    private final SpineWaveRig spine = new SpineWaveRig("chest", "hip", "neck0", "neck1", "neck2", "head",
            new String[]{"tail0", "tail1", "tail2", "tail3", "tail4"});

    private final BodyBendRig bend = new BodyBendRig(
            new String[]{"neck0", "neck1", "neck2", "head"}, new String[]{null, null, null, null},
            new String[]{"tail0", "tail1", "tail2", "tail3"}, new String[]{null, null, null, null});

    @Override
    public ResourceLocation getModelResource(PilimnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PilimnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(PilimnesEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(PilimnesEntity animatable, long instanceId, AnimationState<PilimnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float pt = animationState.getPartialTick();
        this.spine.apply(this, animatable, pt);   // 脚の IK より先(振った胴から脚を解く)
        this.legs.apply(this, animatable, pt);
        this.bend.apply(this, animatable, pt);
    }
}
