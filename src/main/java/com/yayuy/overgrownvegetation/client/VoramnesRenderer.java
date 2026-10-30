package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.VoramnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * 「ヴォラムネス」の描画。光({@code voramnes_glowmask.png}: 目と喉の底)を重ねる。
 * 描画倍率 2 は mobs.json の {@code scale}(SCALE 属性)で掛かる。原点は地表の中心(モデルの root)なので平行移動はしない。
 *
 * <p>描画の打ち切りの箱は実体の {@link VoramnesEntity#getBoundingBoxForCulling}(水平 8・上 1.5 広げる)、
 * 骨の隠し方({@code tail1}・{@code burrow}・遠くの LOD)は {@link VoramnesModel}、呑まれた相手を描かない・呑まれたプレイヤーの霧は
 * {@link VoramnesClientEvents}。
 */
public class VoramnesRenderer extends GeoEntityRenderer<VoramnesEntity> {

    public VoramnesRenderer(EntityRendererProvider.Context context) {
        super(context, new VoramnesModel());
        this.shadowRadius = 1.4F;
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    /** 死の動き(death 系 5 本)はアニメが持っているので、バニラの横転を切る(ほかの G5 と同じ)。 */
    @Override
    protected float getDeathMaxRotation(VoramnesEntity animatable) {
        return 0.0F;
    }

    /** 影は体の場所で変える: 地中は蕾の先だけ、地面の花は花の広さ、地上は胴の太さ(仮)。 */
    @Override
    protected float getShadowRadius(VoramnesEntity entity) {
        return switch (entity.posture()) {
            case BURIED -> 0.35F;
            case FLOWER -> 1.6F;
            case SURFACED -> this.shadowRadius;
        };
    }
}
