package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 胞子感染したプレイヤーの攻撃力の低下(感染8〜 −10% / −15%、{@code effects.json} の {@code stages[].attackDamageReduction})。
 * 属性でなくダメージ計算に割り込んで倍率を掛ける。2026-11-04 に parked から戻した(Mob の 3 段階と薬の抑えは外した)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class SporeInfectionCombatModifier {

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player attacker)) {
            return;
        }
        MobEffectInstance infection = attacker.getEffect(ModMobEffects.SPORE_INFECTION);
        if (infection == null) {
            return;
        }
        double reduction = SporeInfectionMobEffect.playerStage(infection.getAmplifier()).attackDamageReduction();
        if (reduction <= 0.0D) {
            return;
        }
        event.setAmount(event.getAmount() * (float) Math.max(0.0D, 1.0D - reduction));
    }

    private SporeInfectionCombatModifier() {
    }
}
