package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})が自然に切れた瞬間の処理。
 * {@code MobEffectEvent.Expired} は持続時間が 0 になって切れたときだけ発火し、
 * ミルク等で治療した場合({@code MobEffectEvent.Remove})では発火しないため、
 * 「満了で死亡するが治療は間に合う」という挙動が自然に成立する。
 *
 * <ul>
 *     <li><b>Mob(非プレイヤー)</b>: その場で死亡させる。死体ブロック/汚染シダの設置は
 *     {@link SporeInfectionDeathHandler}({@code LivingDeathEvent})が死因を問わず一括して行う。</li>
 *     <li><b>プレイヤー</b>: 切れた段階が {@code killPlayerOnExpiry}(既定では胞子感染3 のみ)なら
 *     専用のダメージ({@link ModDamageTypes#SPORE_INFECTION})で死亡させ、死亡地点に汚染シダを生やす。
 *     胞子感染1/2 の継続・引き下げは {@link SporeInfectionTracker} が満了の直前に行うのでここでは何もしない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeInfectionExpiryHandler {

    @SubscribeEvent
    public static void onExpired(MobEffectEvent.Expired event) {
        MobEffectInstance instance = event.getEffectInstance();
        if (instance == null || !instance.is(ModMobEffects.SPORE_INFECTION)) {
            return;
        }

        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        applyExpiryOutcome(level, entity, instance.getAmplifier());
    }

    /**
     * 「その段階の胞子感染が切れたとき」に起きることを実行する。
     *
     * <p>満了イベントからだけでなく、<b>切れるのを待たずに同じ結末を持ち込みたいとき</b>にも呼ぶ ——
     * {@link FruitEffects} が「大きな未発達な果実」を食べたプレイヤーの感染を一段進めようとして、
     * もう胞子感染3 だった場合がそれ({@code amplifier = 2} でここへ来る)。
     *
     * <p>プレイヤーは {@code killPlayerOnExpiry} の段階でだけ死ぬので、設定で切っておけば
     * 果実を食べても死なない —— 判断が 1 箇所にまとまるように、この分岐ごと共有している。
     */
    public static void applyExpiryOutcome(ServerLevel level, LivingEntity entity, int amplifier) {
        if (entity instanceof BlightFernEntity || entity instanceof ParasitizedMob || !entity.isAlive()) {
            return;
        }

        if (entity instanceof Player player) {
            if (!DesignData.effects().level(amplifier).killPlayerOnExpiry()) {
                return;
            }
            BlockPos deathPos = player.blockPosition();
            player.hurt(ModDamageTypes.sporeInfection(level), Float.MAX_VALUE);
            if (player.isDeadOrDying() && level.isLoaded(deathPos)) {
                BlightFernPlacement.spawn(level, deathPos, MobSpawnType.MOB_SUMMONED);
            }
            return;
        }

        entity.kill();
    }

    private SporeInfectionExpiryHandler() {
    }
}
