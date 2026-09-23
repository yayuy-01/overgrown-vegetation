package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 「胞子感染」にかかっているエンティティが与える攻撃ダメージを、段階ごとの
 * {@code effects.json} の {@code levelN.attackDamageReduction} だけ減らす(「攻撃力低下」)。
 * 既定ではすべて 0(無効)— 刷新後の仕様に攻撃力低下は無いが、設計値で有効化できるよう残してある。
 *
 * <p>攻撃力(ATTACK_DAMAGE)の属性そのものを {@link SporeInfectionMobEffect} の属性修飾で下げるのではなく、
 * ダメージ計算に割り込んで段階ごとの倍率を掛ける。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeInfectionCombatModifier {

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        MobEffectInstance infection = attacker.getEffect(ModMobEffects.SPORE_INFECTION);
        if (infection == null) {
            return;
        }

        double reduction = DesignData.effects().level(infection.getAmplifier()).attackDamageReduction();
        if (reduction <= 0.0D) {
            return;
        }
        float factor = (float) Math.max(0.0D, 1.0D - reduction);
        event.setAmount(event.getAmount() * factor);
    }

    private SporeInfectionCombatModifier() {
    }
}
