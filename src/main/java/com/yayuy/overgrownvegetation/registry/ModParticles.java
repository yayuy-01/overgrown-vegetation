package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODの独自パーティクル。
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, OvergrownVegetation.MODID);

    /**
     * 「過繁茂の胞子」— 過繁茂バイオームに漂う胞子と同系の緑の粒。ただしバニラの
     * {@code spore_blossom_air} と違い、与えた初速で飛び、毎 tick の摩擦と重力で
     * 徐々に減速・落下する({@link com.yayuy.overgrownvegetation.client.OvergrownSporeParticle})。
     * フェーズ1開始の地割れ演出で、裂け目の底から花火のように噴き上げるのに使う。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> OVERGROWN_SPORE =
            PARTICLE_TYPES.register("overgrown_spore", () -> new SimpleParticleType(false));

    /**
     * 「細かい胞子」— {@link #OVERGROWN_SPORE} と同じ色・同じテクスチャだが、粒が 1/5 ほど小さく
     * 寿命も短い({@link com.yayuy.overgrownvegetation.client.FineSporeParticle})。
     * ヴェロフィルが飛行中に翼の後ろへ曳く航跡用。数を出して「細かい粉が筋を引く」見え方にする。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FINE_SPORE =
            PARTICLE_TYPES.register("fine_spore", () -> new SimpleParticleType(false));

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    private ModParticles() {
    }
}
