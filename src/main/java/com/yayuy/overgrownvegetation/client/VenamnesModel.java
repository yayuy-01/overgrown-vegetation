package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.runner.BodyBendRig;
import com.yayuy.overgrownvegetation.client.runner.LegChainRig;
import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.GaitSway;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ヴェナムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\venamnes.bbmodel}(11-14 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g5_pursuer_r9} を写した。ユーザーが一覧の絵を見て「このままでよい」とした形)。
 *
 * <p>コードが足すもの(Blockbench には出ない): 脚の IK と胴 {@code hips} の高さ・傾き({@link LegChainRig})、
 * 首 neck0〜head・尾 tail0〜tail3 の左右の曲げ(継ぎ目の箱 j_* は半分。{@link BodyBendRig})、足の運びから出す腰の上下とひねり・
 * 胸と首と尾の遅れ({@link GaitSway}。11-14 段 1b)。骨の名前を変えるとこれらが黙って効かなくなる。
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
        applySway(animatable, pt);   // 脚の IK より先(腰の高さ・ひねりを足した姿から脚を解く)
        this.legs.apply(this, animatable, pt);
        this.bend.apply(this, animatable, pt);
    }

    private static final String[] FRONT = {"neck0", "neck1", "neck2", "head"}, TAIL = {"tail0", "tail1", "tail2", "tail3"};

    /**
     * 足の運びから出した胴・首・尾の動き({@link GaitSway})を骨に足す。足の置き場が効いている間だけ(効き = 足の置き場の重み)。
     * 向き(GeckoLib の中の値): 骨の rotX + で前(−Z)側が上がる、rotY + で前側が左へ、rotZ − で左が上がる。尾は +Z へ伸びるので上下は逆。
     */
    private void applySway(VenamnesEntity e, float pt) {
        FootPlanter fp = e.footPlanter();
        if (fp == null || !fp.ready()) return;
        float w = fp.weight(pt);
        if (w <= 0.001F) return;
        GaitSway s = e.sway();
        float k = Mth.DEG_TO_RAD * w;
        GeoBone hips = getAnimationProcessor().getBone("hips");
        if (hips != null) {
            hips.setPosY(hips.getPosY() + (float) (s.get(GaitSway.LIFT, pt) * 16.0D / VenamnesEntity.RENDER_SCALE) * w);
            hips.setRotY(hips.getRotY() + (float) s.get(GaitSway.YAW, pt) * k);
            hips.setRotZ(hips.getRotZ() - (float) s.get(GaitSway.ROLL, pt) * k);
            hips.resetStateChanges();
        }
        add("body", (float) s.get(GaitSway.FRONT_PITCH, pt) * k, 0.0F);
        float frontYaw = (float) s.get(GaitSway.FRONT_YAW, pt) * k / FRONT.length;
        for (int i = 0; i < FRONT.length; i++) {
            add(FRONT[i], i == 0 ? (float) s.get(GaitSway.NECK_PITCH, pt) * k : 0.0F, frontYaw);
        }
        for (int i = 0; i < TAIL.length; i++) {
            float pitch = i == 0 ? (float) s.get(GaitSway.TAIL_PITCH0, pt) : i == 2 ? (float) s.get(GaitSway.TAIL_PITCH2, pt) : 0.0F;
            add(TAIL[i], -pitch * k, (float) s.get(GaitSway.TAIL_YAW0 + i, pt) * k);
        }
    }

    private void add(String bone, float rotX, float rotY) {
        GeoBone b = getAnimationProcessor().getBone(bone);
        if (b == null) return;
        b.setRotX(b.getRotX() + rotX);
        b.setRotY(b.getRotY() + rotY);
        b.resetStateChanges();   // キーの無い骨は、下ろさないと足した値が次のフレームにも残って積み上がる
    }
}
