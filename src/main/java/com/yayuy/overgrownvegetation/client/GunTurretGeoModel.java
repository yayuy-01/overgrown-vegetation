package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 機関砲台のモデル定義({@code geo/gun_turret.geo.json})と、<b>旋回・俯仰・反動</b>のボーン操作。
 *
 * <p>アニメーションファイルは使わない(中身は空)。GeckoLib は {@code handleAnimations} の最後に
 * {@link #setCustomAnimations} を呼ぶので、ここでボーンの回転を直接入れる。{@code preRender} で
 * 入れてはいけない — そのあとに走るアニメーション処理が、動かしていないボーンを初期姿勢へ戻すため。
 *
 * <h2>角度の対応(要注意)</h2>
 * <p>この砲台は geo で<b>銃口が −Z 側</b>(銃身の先端が z = −21.6)を向いている。GeckoLib はベイク時に
 * X を反転するが Z はそのままなので、モデル空間でも銃口は −Z のまま。そのため:
 * <ul>
 *   <li>方位 A(0 = 北)に向けるには {@code base.setRotY(radians(−A))}
 *       — A = 0 で −Z(モデル)がそのまま北を向く。</li>
 *   <li>仰角 E で上を向かせるのは X 軸まわり: {@code gun.setRotX(radians(+E))}
 *       (−Z を向いたベクトルを {@code Axis.XP} で回すと上を向く)。</li>
 *   <li>反動は親({@code base})の座標系での平行移動。{@code translateMatrixToBone} は
 *       {@code translate(−posX/16, posY/16, posZ/16)} なので、銃身方向 (0, sinE, −cosE) の逆へ
 *       r ブロック下げるには {@code posY = −16·r·sinE}, {@code posZ = +16·r·cosE}。</li>
 * </ul>
 *
 * <p>過繫茂に覆われた砲台({@link GunTurretBlock#OVERGROWN})はテクスチャだけが差し替わる
 * (ユーザー指定 2026-09-21)。形も動きも同じ。
 */
public class GunTurretGeoModel extends GeoModel<GunTurretBlockEntity> {

    /** 台座(水平旋回)。 */
    public static final String BONE_BASE = "base";
    /** 銃身(俯仰・反動)。 */
    public static final String BONE_GUN = "gun";

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/gun_turret.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/gun_turret.png");
    private static final ResourceLocation TEXTURE_OVERGROWN =
            OvergrownVegetation.id("textures/block/overgrown_gun_turret.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/gun_turret.animation.json");

    @Override
    public ResourceLocation getModelResource(GunTurretBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(GunTurretBlockEntity animatable) {
        return animatable.getBlockState().hasProperty(GunTurretBlock.OVERGROWN)
                && animatable.getBlockState().getValue(GunTurretBlock.OVERGROWN)
                ? TEXTURE_OVERGROWN : TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(GunTurretBlockEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(GunTurretBlockEntity turret, long instanceId,
                                    AnimationState<GunTurretBlockEntity> animationState) {
        float partialTick = animationState.getPartialTick();
        double azimuth = turret.aimAzimuth(partialTick);
        double elevation = turret.aimElevation(partialTick);
        long gameTime = turret.getLevel() != null ? turret.getLevel().getGameTime() : 0L;
        float recoil = turret.recoil(gameTime, partialTick);

        GeoBone base = getAnimationProcessor().getBone(BONE_BASE);
        if (base != null) {
            base.setRotY((float) Math.toRadians(-azimuth));
        }

        GeoBone gun = getAnimationProcessor().getBone(BONE_GUN);
        if (gun != null) {
            double el = Math.toRadians(elevation);
            gun.setRotX((float) el);
            gun.setPosY((float) (-16.0D * recoil * Math.sin(el)));
            gun.setPosZ((float) (16.0D * recoil * Math.cos(el)));
        }
    }
}
