package com.yayuy.overgrownvegetation.client.lizard;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.runner.BodyBendRig;
import com.yayuy.overgrownvegetation.entity.lizard.SimpleLizard;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「トカゲ型」の小さな生き物のモデル(2026-11-16、G3 のトカゲ 2 種のために)。骨の名前を渡すだけで、背骨のくねり({@link SpineWaveRig})・
 * 横へ張った脚の IK({@link SprawlLegRig})・経路に沿った首と尾の曲げ({@link BodyBendRig})をピリムネスと同じ順に足す。
 * 骨の名前を変えるとこれらが黙って効かなくなる(正本は Blockbench の .bbmodel)。
 *
 * @param <T> 種
 */
public class SimpleLizardModel<T extends SimpleLizard> extends GeoModel<T> {

    /** 骨の名前。腰の骨が無い体は {@code hip} を null に(尾と後ろ脚が胴に付いている)。首が 1 本なら {@code neck1}・{@code neck2} は null。 */
    public record Bones(String trunk, String hip, String neck0, String neck1, String neck2, String head, String[] tails,
                        String[] frontLegL, String[] frontLegR, String[] hindLegL, String[] hindLegR) {
    }

    private final ResourceLocation model, texture, animation;
    private final SpineWaveRig spine;
    private final SprawlLegRig legs;
    private final BodyBendRig bend;

    /**
     * @param id          種の id(geo・絵・アニメのファイル名)
     * @param renderScale 描画倍率
     * @param b           骨の名前
     * @param range       脚の IK の変数の範囲(度): 付け根 Y・付け根 Z・2 つ目 X・2 つ目 Z
     */
    public SimpleLizardModel(String id, float renderScale, Bones b, double[] range) {
        this.model = OvergrownVegetation.id("geo/" + id + ".geo.json");
        this.texture = OvergrownVegetation.id("textures/entity/" + id + ".png");
        this.animation = OvergrownVegetation.id("animations/" + id + ".animation.json");
        this.spine = new SpineWaveRig(b.trunk(), b.hip(), b.neck0(), b.neck1(), b.neck2(), b.head(), b.tails());
        String[] front = {b.trunk()};
        String[] hind = b.hip() == null ? new String[]{b.trunk()} : new String[]{b.trunk(), b.hip()};
        this.legs = new SprawlLegRig(new String[][]{b.frontLegL(), b.frontLegR(), b.hindLegL(), b.hindLegR()},
                new String[][]{front, front, hind, hind}, b.trunk(), renderScale, range, new double[]{0.4, 1.0, 1.2, 0.8});
        String[] neck = {b.neck0(), b.head(), null, null};
        String[] tail = new String[4];
        for (int i = 0; i < 4 && i < b.tails().length; i++) tail[i] = b.tails()[i];
        this.bend = new BodyBendRig(neck, new String[4], tail, new String[4]);
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return this.model;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return this.texture;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return this.animation;
    }

    @Override
    public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float pt = animationState.getPartialTick();
        this.spine.apply(this, animatable, pt);   // 脚の IK より先(振った胴から脚を解く)
        this.legs.apply(this, animatable, pt);
        this.bend.apply(this, animatable, pt);
    }
}
