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
    /** 個体ごとの破裂で上げた升の浮遊(%。エンティティの id → 値。テスト用。同じ束のテストが並んで走るので個体で引く)。 */
    public static final java.util.Map<Integer, Double> AIR_BY_ID = new java.util.concurrent.ConcurrentHashMap<>();

    static void record(LivingEntity e, double air) {
        lastAirPercent = air;
        if (AIR_BY_ID.size() > 256) AIR_BY_ID.clear();
        AIR_BY_ID.put(e.getId(), air);
    }

    /** 最後の破裂で上げた升の浮遊(%)。 */
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

    /**
     * 汎用の感染体になったエンダードラゴン(11-15 ユーザー決定「死に際の後に破裂」): バニラの死に際(光って昇る約 10 秒、200 tick)を最後まで流し、
     * 消える tick(ドラゴン自身の数え {@code dragonDeathTime} が 200 になる tick の頭。deathTime ではない)で破裂させて周りを汚す。膨らみの描画は重ねない({@link PlantSideRules#burstsWith} が外す)。
     * 消すのも出口ポータル・卵もバニラのまま。汚染の球は、ポータルと卵が置かれる前のこの tick のうちにすぐ作る(後回しの列に入れると、
     * 後から置かれた卵を書き換えることがあるので)。岩盤・ポータルは壊せないブロックなので球は触らない。
     */
    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon)
                || dragon.dragonDeathTime != DRAGON_DEATH_TICKS - 1 || dragon.isRemoved()
                || !(dragon.level() instanceof ServerLevel level) || !PlantSideRules.isTransformed(dragon)) return;
        burstDragon(level, dragon);
    }

    /** バニラのドラゴンの死に際の長さ(EnderDragon#tickDeath が dragonDeathTime 200 で消す)。 */
    static final int DRAGON_DEATH_TICKS = 200;

    /** ドラゴンの破裂(粒・音は体の位置で、汚染の球は真下の地面に)。テストからも。 */
    public static void burstDragon(ServerLevel level, LivingEntity dragon) {
        record(dragon, G1DeathBurst.burst(level, dragon.position(), dragon.getBbWidth(), dragon.getBbHeight(), 1.0D, true, false));
        burstCount++;
        net.minecraft.core.BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                dragon.blockPosition());
        if (ground.getY() <= level.getMinBuildHeight()) return; // 島の外(下は奈落)
        int radius = G1DeathBurst.sphereRadius(G1DeathBurst.size(dragon.getBbWidth(), dragon.getBbHeight())); // 上限 3
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atBottomCenterOf(ground);
        if (level.dimension() == Level.END) {
            // ジ・エンドは胞子の場が無いので、球の放出は何もしない。卵より先に作るためすぐ作る
            com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.sphere(level, center, radius, G1DeathBurst.SPHERE_NOISE);
        } else {
            com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.deathSphere(level, center, radius, G1DeathBurst.SPHERE_NOISE, false);
        }
    }

    /** 今すぐ破裂させて消す(テストからも)。 */
    public static void burstNow(ServerLevel level, LivingEntity living) {
        burstEffects(level, living);
        living.ejectPassengers();
        living.remove(Entity.RemovalReason.KILLED);
    }

    /** 破裂の中身(粒・音・汚染・浮遊。1 tick の上限つき)。消すのは呼ぶ側。 */
    static void burstEffects(ServerLevel level, LivingEntity living) {
        int[] used = budget(level);
        boolean full = used[0] < FULL_PER_TICK;
        boolean sound = used[1] < SOUNDS_PER_TICK;
        used[0]++;
        if (sound) used[1]++;
        record(living, G1DeathBurst.burst(level, living.position(), living.getBbWidth(), living.getBbHeight(), full ? 1.0D : REDUCED_DETAIL, sound));
        burstCount++;
        if (!full) reducedCount++;
    }

    /**
     * 自前の長い死に際を持つほかの MOD の Mob(2026-11-16 コードの見直し ③14、{@link PlantSideRules#burstsAtRemoval}): 死に際を最後まで流させ、
     * 死んだまま消える瞬間(消える理由が KILLED)に破裂させる。読み込みの外へ出ただけ・コマンドで消した(DISCARDED)のは破裂しない。
     */
    @SubscribeEvent
    public static void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (living.getRemovalReason() != Entity.RemovalReason.KILLED || !living.isDeadOrDying()) return;
        if (!PlantSideRules.burstsAtRemoval(living, PlantSideRules.isTransformed(living))) return;
        burstAtRemoval(level, living);
    }

    /** 消える瞬間の破裂(テストからも)。 */
    public static void burstAtRemoval(ServerLevel level, LivingEntity living) {
        burstEffects(level, living);
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
