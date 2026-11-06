package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, OvergrownVegetation.MODID);

    /** 胞子感染の色(効果の粒の色にもなる。一人称で自分の粒を隠すときの見分けにも使う、client/SelfEffectParticles。2026-11-06)。 */
    public static final int SPORE_INFECTION_COLOR = 0x6C4A82;

    /** 「胞子感染」(プレイヤーの 10 段階。2026-11-04 に parked から戻した。色は前と同じ)。 */
    public static final DeferredHolder<MobEffect, com.yayuy.overgrownvegetation.effect.SporeInfectionMobEffect> SPORE_INFECTION =
            MOB_EFFECTS.register("spore_infection",
                    () -> new com.yayuy.overgrownvegetation.effect.SporeInfectionMobEffect(MobEffectCategory.HARMFUL, SPORE_INFECTION_COLOR));

    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
    }

    private ModMobEffects() {
    }
}
