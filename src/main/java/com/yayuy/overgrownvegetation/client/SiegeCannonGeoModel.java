package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity;
import com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 砲台のモデル定義({@code geo/siege_cannon.geo.json})と、<b>旋回・俯仰・反動</b>のボーン操作。
 *
 * <p>アニメーションファイルは使わない(中身は空)。GeckoLib は {@code handleAnimations} の最後に
 * {@link #setCustomAnimations} を呼ぶので、ここでボーンの回転を直接入れる。{@code preRender} で
 * 入れてはいけない — そのあとに走るアニメーション処理が、動かしていないボーンを初期姿勢へ戻すため。
 *
 * <h2>角度の対応(要注意)</h2>
 * <p>GeckoLib は Bedrock の geo を読むとき X を反転する({@code BakedModelFactory}: origin/pivot/回転の
 * X と Y を符号反転)。この砲台は geo では砲口が −X 側(x = −100)なので、<b>モデル空間では砲口が +X</b>
 * になる。そのため:
 * <ul>
 *   <li>方位 A(0 = 北)に向けるには {@code dodai.setRotY(radians(90 − A))}
 *       — A = 0 で +X(モデル)が −Z(北)を向く。</li>
 *   <li>仰角 E で上を向かせるのは Z 軸まわり: {@code housin.setRotZ(radians(+E))}
 *       (砲身が X 軸方向に伸びているので、X 軸まわりでは上を向かない)。</li>
 *   <li>反動は親(dodai)の座標系での平行移動。{@code translateMatrixToBone} は
 *       {@code translate(−posX/16, posY/16, posZ/16)} なので、砲身方向 (cosE, sinE, 0) の逆へ r ブロック
 *       下げるには {@code posX = +16·r·cosE}, {@code posY = −16·r·sinE}。</li>
 * </ul>
 *
 * <p>旋回の中心は geo の {@code dodai} の pivot ではなく、底板の中心
 * ({@link SiegeCannonGeometry#TURN_AXIS_X})へ実行時に差し替える。Blockbench から書き出し直しても
 * 壊れないように、geo は触らずここで上書きしている。
 */
public class SiegeCannonGeoModel extends GeoModel<SiegeCannonBlockEntity> {

    /** 台座(水平旋回)。 */
    public static final String BONE_BASE = "dodai";
    /** 砲身(俯仰・反動)。 */
    public static final String BONE_BARREL = "housin";

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/siege_cannon.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/siege_cannon.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/siege_cannon.animation.json");

    @Override
    public ResourceLocation getModelResource(SiegeCannonBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SiegeCannonBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SiegeCannonBlockEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(SiegeCannonBlockEntity cannon, long instanceId,
                                    AnimationState<SiegeCannonBlockEntity> animationState) {
        float partialTick = animationState.getPartialTick();
        double azimuth = cannon.aimAzimuth(partialTick);
        double elevation = cannon.aimElevation(partialTick);
        long gameTime = cannon.getLevel() != null ? cannon.getLevel().getGameTime() : 0L;
        float recoil = cannon.recoil(gameTime, partialTick);

        GeoBone base = getAnimationProcessor().getBone(BONE_BASE);
        if (base != null) {
            // geo の pivot は底板の端なので、底板の中心へ差し替える(X はベイク時に反転済み)。
            base.updatePivot((float) -SiegeCannonGeometry.TURN_AXIS_X, 0.0F, (float) SiegeCannonGeometry.TURN_AXIS_Z);
            base.setRotY((float) Math.toRadians(90.0D - azimuth));
        }

        GeoBone barrel = getAnimationProcessor().getBone(BONE_BARREL);
        if (barrel != null) {
            double el = Math.toRadians(elevation);
            barrel.setRotZ((float) el);
            barrel.setPosX((float) (16.0D * recoil * Math.cos(el)));
            barrel.setPosY((float) (-16.0D * recoil * Math.sin(el)));
        }
    }
}
