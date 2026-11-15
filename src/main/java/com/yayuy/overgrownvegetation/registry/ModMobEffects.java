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

    /**
     * 「縫い止め」(アクムネスの特殊、2026-11-15)。重い棘で地面に縫い止められ、動けず跳べない(移動・跳躍の属性を 0 に)。
     * 長さは当てた側が決める(20 tick【仮】)。効果が切れれば属性は戻る。
     */
    public static final DeferredHolder<MobEffect, MobEffect> ACUMNES_PINNED =
            MOB_EFFECTS.register("acumnes_pinned", () -> new MobEffect(MobEffectCategory.HARMFUL, 0xB8D838) {
            }.addAttributeModifier(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED,
                            OvergrownVegetation.id("effect.acumnes_pinned.speed"), -1.0D,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                    .addAttributeModifier(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH,
                            OvergrownVegetation.id("effect.acumnes_pinned.jump"), -1.0D,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
    }

    private ModMobEffects() {
    }
}
