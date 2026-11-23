package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;

/**
 * 一人称のとき、自分に付いた胞子感染の効果の粒を出さない(2026-11-06 ユーザー「やりましょう」)。
 * 粒が目の前を横切って視界を邪魔し、紫の四角が描画の不具合にも見えた。感染していることは効果のアイコンと画面の演出で分かる。
 *
 * <p>バニラは {@code LivingEntity#tickEffects} で、サーバーから同期された効果の粒の一覧(見える効果ごとに 1 つ、色付きの
 * {@code entity_effect})から毎回 1 つ選んで、自分のまわりにも出す(一人称でも)。一覧はサーバーで作るのでカメラの向きは分からない。
 * そこで、クライアントで粒を出す所({@code mixin/client/LivingEntitySelfEffectParticlesMixin})で、
 * カメラの持ち主が一人称のときに、胞子感染の色の効果の粒だけを止める。三人称・ほかのプレイヤーから見た自分・Mob の粒、ほかの効果の粒はそのまま。
 */
public final class SelfEffectParticles {

    /** 確かめ用: 真なら前の形(一人称でも出す)。devbridge の wind cavedev selfparticles on。 */
    public static boolean devShowAll;

    private SelfEffectParticles() {
    }

    /** {@code entity} が出そうとしている効果の粒 {@code particle} を出すか。 */
    public static boolean shouldSpawn(Entity entity, ParticleOptions particle) {
        Minecraft mc = Minecraft.getInstance();
        if (devShowAll || entity != mc.getCameraEntity() || !mc.options.getCameraType().isFirstPerson()) {
            return true;
        }
        return !(isEffectParticle(particle) && entity instanceof net.minecraft.world.entity.LivingEntity living
                && living.hasEffect(ModMobEffects.SPORE_INFECTION.get()));
    }

    /**
     * 1.20.1: 効果の粒は色を持たない({@code entity_effect} / {@code ambient_entity_effect} の 1 種で、色は速さの欄に入る)。しかも色は
     * 見える効果を全部混ぜた 1 色で、効果ごとの粒の一覧は無い(1.20.5 から)。なので「胞子感染に掛かっている間は、自分の効果の粒を全部出さない」にする
     * (ほかの効果と重なっているときは、その効果の粒も一人称では出なくなる)。
     */
    static boolean isEffectParticle(ParticleOptions particle) {
        return particle.getType() == ParticleTypes.ENTITY_EFFECT || particle.getType() == ParticleTypes.AMBIENT_ENTITY_EFFECT;
    }
}
