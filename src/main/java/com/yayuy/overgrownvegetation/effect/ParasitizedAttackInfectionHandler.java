package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * グループ1〜10({@link MobGroup})の Mob に攻撃されたエンティティの「胞子感染」を進行させる。
 * ダメージ源がその Mob であれば攻撃手段(近接・クリーパーの爆発など)を問わない。
 *
 * <ul>
 *     <li><b>プレイヤー</b>: 胞子感染 N を持っていれば、{@code effects.json} の {@code level(N+1).upgradeChanceOnAttack}
 *     (既定 5%)の確率で一段悪化(1 → 2、2 → 3)。未感染のプレイヤーは攻撃では感染しない
 *     (胞子濃度による滞在感染のみ)。</li>
 *     <li><b>Mob(非プレイヤー)</b>: 未感染なら「過繁茂した(MOB名)」({@link ParasitizedMob})に攻撃されたときだけ
 *     胞子感染1 を付与(グループ1 が汚染地形の外で他の Mob を襲い、死体/汚染シダに変える経路)。
 *     Mob は胞子感染1 で 1 分後に死亡するので、それ以上の段階へは進めない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedAttackInfectionHandler {

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel)) {
            return;
        }
        if (!victim.isAlive()) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) {
            return;
        }
        boolean groupMob = attacker instanceof ParasitizedMob || MobGroup.of(attacker) != null;
        if (!groupMob) {
            return;
        }

        MobEffectInstance current = victim.getEffect(ModMobEffects.SPORE_INFECTION);
        if (victim instanceof Player) {
            if (current == null) {
                return;
            }
            int next = current.getAmplifier() + 1;
            if (next > EffectTable.MAX_LEVEL_INDEX) {
                return;
            }
            double chance = DesignData.effects().level(next).upgradeChanceOnAttack();
            if (chance > 0.0D && victim.getRandom().nextDouble() < chance) {
                SporeInfectionEffects.infect(victim, next);
            }
            return;
        }

        // 「寄生植物装備」を着けたプレイヤーの攻撃も同じ(仕様: 攻撃した Mob に胞子感染 1)。
        if (current == null && (attacker instanceof ParasitizedMob
                || com.yayuy.overgrownvegetation.item.PlantArmor.isHost(attacker))) {
            SporeInfectionEffects.infect(victim, 0);
        }
    }

    private ParasitizedAttackInfectionHandler() {
    }
}
