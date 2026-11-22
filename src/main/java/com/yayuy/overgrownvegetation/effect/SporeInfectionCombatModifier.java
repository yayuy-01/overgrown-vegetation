package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * 胞子感染したプレイヤーの攻撃力の低下(感染8〜 −10% / −15%、{@code effects.json} の {@code stages[].attackDamageReduction})。
 * 属性でなくダメージ計算に割り込んで倍率を掛ける。2026-11-04 に parked から戻した(Mob の 3 段階と薬の抑えは外した)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class SporeInfectionCombatModifier {

    /** 1.20.1: 1.21 の LivingIncomingDamageEvent の代わりに LivingHurtEvent(防具の前。無敵の間の判定の後)。 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof Player attacker)) {
            return;
        }
        MobEffectInstance infection = attacker.getEffect(ModMobEffects.SPORE_INFECTION.get());
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
