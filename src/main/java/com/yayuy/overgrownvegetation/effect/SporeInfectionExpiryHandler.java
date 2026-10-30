package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
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
 *     <li><b>プレイヤー</b>: 2026-10-14 から持続が無限(10 段階は {@link SporeInfectionEffects#advance} が進める)なので、
 *     ここへは来ない。来ても何もしない。</li>
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
     * <p>満了イベントからだけでなく、{@link FruitEffects} が「大きな未発達な果実」を食べた Mob の感染を一段進めようとして、
     * もう胞子感染3 だった場合にも呼ぶ。プレイヤーは何もしない(10 段階は {@link SporeInfectionEffects})。
     */
    public static void applyExpiryOutcome(ServerLevel level, LivingEntity entity, int amplifier) {
        if (entity instanceof BlightFernEntity || entity instanceof ParasitizedMob || !entity.isAlive()) {
            return;
        }

        if (entity instanceof Player) {
            return;
        }

        entity.kill();
    }

    private SporeInfectionExpiryHandler() {
    }
}
