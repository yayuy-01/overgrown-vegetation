package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODの独自パーティクル。2026-10-30 に画面の演出と一緒に 5 種とも parked/ へ退避し、10-31 に萌芽体の噴き出し
 * ({@link #SPORE_JET})だけ戻した(ユーザー「萌芽体の噴き出しは作り直さないので戻す」)。
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, OvergrownVegetation.MODID);

    /**
     * 「噴き出す胞子」— 萌芽体の胞子嚢がしぼむ瞬間に先端の 1 点から上へ勢いよく噴く小さな粒(2026-10-23。
     * {@link com.yayuy.overgrownvegetation.client.SporeJetParticle}・出す側は {@link com.yayuy.overgrownvegetation.client.SporeJets})。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPORE_JET =
            PARTICLE_TYPES.register("spore_jet", () -> new SimpleParticleType(false));

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    private ModParticles() {
    }
}
