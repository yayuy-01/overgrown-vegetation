package com.yayuy.overgrownvegetation.client;

import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 遠距離用のボス「インポスタ」— エンティティではない、クライアント側だけの GeckoLib アニマタブル。
 *
 * <p>ボスはチャンク読み込み範囲の外を飛ぶため、遠くに居るあいだはクライアントにエンティティが
 * 存在しない。それでも {@code GeoEntityRenderer} と同じモデル・同じアニメーションで描きたいので、
 * この「中身の無い物体」を 1 つ用意して {@link SiegeBossRenderer} が
 * {@code GeoObjectRenderer} で描く。状態はすべて {@link ClientSiegeBoss} から読む。
 */
public final class TitanicaImpostor implements TitanicaAnimatable {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        TitanicaAnimations.register(this, controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public double getTick(Object object) {
        return ClientSiegeBoss.elapsed(0.0F);
    }

    @Override
    public double titanicaAge(float partialTick) {
        return ClientSiegeBoss.elapsed(partialTick);
    }
}
