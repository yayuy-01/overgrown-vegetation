package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ColorParticleOption;
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
        return !isSporeInfection(particle);
    }

    /** 胞子感染の効果の粒か(色付きの entity_effect で、色が胞子感染の色。濃さ(アルファ)は ambient で変わるので見ない)。 */
    static boolean isSporeInfection(ParticleOptions particle) {
        return particle instanceof ColorParticleOption color && color.getType() == ParticleTypes.ENTITY_EFFECT
                && (rgb(color) & 0xFFFFFF) == ModMobEffects.SPORE_INFECTION_COLOR;
    }

    private static int rgb(ColorParticleOption c) {
        return Math.round(c.getRed() * 255.0F) << 16 | Math.round(c.getGreen() * 255.0F) << 8 | Math.round(c.getBlue() * 255.0F);
    }
}
