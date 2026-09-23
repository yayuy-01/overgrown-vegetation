package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BeamShieldBlockEntity;
import com.yayuy.overgrownvegetation.siege.BeamShieldGeometry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 遮蔽盾のモデル定義({@code geo/beam_shield.geo.json})と、<b>旋回・展開・ハンドル</b>のボーン操作。
 *
 * <ul>
 *   <li>{@code base} — 方位。砲台と同じ規約(geo では表が −X、GeckoLib が X を反転するので
 *       モデル空間では表が +X)なので {@code setRotY(radians(90 − A))}。</li>
 *   <li>{@code panel2} / {@code panel3} — 展開でせり上がる 2 段目・3 段目。
 *       {@code translateMatrixToBone} は {@code posY/16} ぶん上げるので、
 *       {@code setPosY(STAGE_RISE × 進み)}(と 2 倍)でよい。</li>
 *   <li>{@code handle} — 回した直後だけくるっと回す(演出)。</li>
 * </ul>
 *
 * <p>ボーンの操作は {@code preRender} ではなく {@link #setCustomAnimations} で行うこと
 * — {@code preRender} のあとに走るアニメーション処理が、動かしていないボーンを初期姿勢へ戻すため。
 */
public class BeamShieldGeoModel extends GeoModel<BeamShieldBlockEntity> {

    public static final String BONE_BASE = "base";
    public static final String BONE_HANDLE = "handle";
    public static final String BONE_PANEL2 = "panel2";
    public static final String BONE_PANEL3 = "panel3";

    /** ハンドルを回したあと、この tick だけ演出でくるっと回す。 */
    private static final float HANDLE_SPIN_TICKS = 10.0F;

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/beam_shield.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/beam_shield.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/beam_shield.animation.json");

    @Override
    public ResourceLocation getModelResource(BeamShieldBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(BeamShieldBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(BeamShieldBlockEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(BeamShieldBlockEntity shield, long instanceId,
                                    AnimationState<BeamShieldBlockEntity> animationState) {
        float partialTick = animationState.getPartialTick();
        float progress = shield.deployProgress();

        GeoBone base = getAnimationProcessor().getBone(BONE_BASE);
        if (base != null) {
            base.setRotY((float) Math.toRadians(90.0D - shield.azimuth()));
        }

        GeoBone panel2 = getAnimationProcessor().getBone(BONE_PANEL2);
        if (panel2 != null) {
            panel2.setPosY((float) (BeamShieldGeometry.STAGE_RISE * progress));
        }
        GeoBone panel3 = getAnimationProcessor().getBone(BONE_PANEL3);
        if (panel3 != null) {
            panel3.setPosY((float) (BeamShieldGeometry.STAGE_RISE * 2.0D * progress));
        }

        GeoBone handle = getAnimationProcessor().getBone(BONE_HANDLE);
        if (handle != null && shield.getLevel() != null && shield.lastHandleGameTime() != Long.MIN_VALUE) {
            float age = (float) (shield.getLevel().getGameTime() - shield.lastHandleGameTime()) + partialTick;
            if (age >= 0.0F && age < HANDLE_SPIN_TICKS) {
                float spin = (1.0F - age / HANDLE_SPIN_TICKS) * Mth.TWO_PI;
                handle.setRotZ(spin);
            }
        }
    }
}
