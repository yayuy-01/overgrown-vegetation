package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 植物側の死の破裂(2026-11-15 ユーザー決定。正本は Vault「構想と進捗/植物側の死の破裂と攻撃の感染(11-15)」)。
 *
 * <p>G1(段階 0 も)・G4〜G10・汎用の感染体({@link PlantSideRules#bursts})は、死ぬと {@link PlantSideRules#burstTick}(15〜18 tick)のあいだ
 * 震えながら膨らみ(描画 = {@code client/PlantDeathSwell})、そこで {@link G1DeathBurst} の破裂を起こして消える。バニラの 20 tick 目の白い煙は出ない。
 * ドロップと経験値はバニラどおり死んだ瞬間に出る。アニメは作らない(どの Mob にも効くように、膨らみは描画のコードだけ)。
 *
 * <p>自前の {@code tickDeath} を持たない Mob にも効くよう、エンティティの tick の後で deathTime を見る(死んでいない Mob は deathTime 0 で
 * すぐ返る)。自前の死に方を持つ Mob はタグ {@link PlantSideRules#DEATH_BURST_EXEMPT} で外す。
 *
 * <p>重さ(大の襲撃 = 約 600 体が一斉に死ぬ): 破裂の tick を個体ごとに 4 通りへずらし、1 tick・1 次元あたり {@link #FULL_PER_TICK} 回までを
 * 全部の粒で、それより多い分は粒を {@link #REDUCED_DETAIL} 倍に減らす。音は {@link #SOUNDS_PER_TICK} 回まで。汚染の球は
 * {@code NoisyCorruptionBurst} の 1 tick 3 個の上限で順に作る(残りは次の tick へ回る)。升の浮遊はどれも同じ量を上げる。数はすべて【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PlantSideBurst {

    /** 1 tick・1 次元あたり、全部の粒で破裂させる回数。【仮】 */
    static final int FULL_PER_TICK = 8;
    /** それより多い分の粒の倍率。【仮】 */
    static final double REDUCED_DETAIL = 0.12D;
    /** 1 tick・1 次元あたりの破裂音の数。【仮】 */
    static final int SOUNDS_PER_TICK = 6;

    private static final Map<ResourceKey<Level>, int[]> BUDGET = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> BUDGET_TICK = new HashMap<>();

    /** 数え(/ov perf・テスト)。 */
    public static int burstCount, reducedCount;
    /** 最後の破裂で上げた升の浮遊(%。テスト用)。 */
    public static volatile double lastAirPercent;

    private PlantSideBurst() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living) || living.deathTime < PlantSideRules.BURST_TICK_MIN) return;
        if (living.isRemoved() || !(living.level() instanceof ServerLevel level)) return;
        if (living.deathTime < PlantSideRules.burstTick(living) || !PlantSideRules.bursts(living)) return;
        burstNow(level, living);
    }

    /** 今すぐ破裂させて消す(テストからも)。 */
    public static void burstNow(ServerLevel level, LivingEntity living) {
        int[] used = budget(level);
        boolean full = used[0] < FULL_PER_TICK;
        boolean sound = used[1] < SOUNDS_PER_TICK;
        used[0]++;
        if (sound) used[1]++;
        lastAirPercent = G1DeathBurst.burst(level, living.position(), living.getBbWidth(), living.getBbHeight(), full ? 1.0D : REDUCED_DETAIL, sound);
        burstCount++;
        if (!full) reducedCount++;
        living.ejectPassengers();
        living.remove(Entity.RemovalReason.KILLED);
    }

    private static int[] budget(ServerLevel level) {
        long now = level.getGameTime();
        ResourceKey<Level> dim = level.dimension();
        int[] used = BUDGET.computeIfAbsent(dim, k -> new int[2]);
        Long last = BUDGET_TICK.get(dim);
        if (last == null || last != now) {
            used[0] = 0;
            used[1] = 0;
            BUDGET_TICK.put(dim, now);
        }
        return used;
    }
}
