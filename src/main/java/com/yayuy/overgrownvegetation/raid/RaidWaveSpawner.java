package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * レイド中、レイドビーコン({@link OvergrownNexusEntity})の周囲へ敵対 Mob(過繫茂した Mob)の群れを
 * 一定間隔で湧かせる。フェーズが高いほど1ウェーブの数が増える。
 *
 * <p>湧ける種族は {@code phases.json} の {@code raidWaveGroups} が決めるが、<b>実際にどの種が
 * どれくらいの割合で出るかは {@link #WAVE_WEIGHTS} の表</b>が決める。表に載っていない種は
 * 重み 0 = ウェーブには出ない。過繫茂バイオームの内外を問わずビーコンの周囲に直接湧かせる点が
 * 生態系スポーン({@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner})との違いで、
 * 湧いた Mob には近くのプレイヤーを初期ターゲットに設定して、ビーコンへ向かってくるプレイヤーへ差し向ける。
 */
public final class RaidWaveSpawner {

    private static final double TAU = Math.PI * 2.0D;

    /**
     * ウェーブの<b>編成</b>(種 → 相対的な重み)。ユーザー指定 2026-09-17:
     * 「アルボラ 6 に対して、スポロフィル・フィロセンス・ヴェロフィル・スカトゥリウムを 1 ずつ」。
     * それまではグループ4・グループ3 の全種から等確率で選んでいたため、フィロセンス・ヴェロフィル・
     * スポロフィルのような「1体で効く支援/索敵/爆撃役」が出過ぎていた。
     *
     * <p><b>この表に載っていない種はウェーブに出ない</b>(重み 0)。つまり汚染シダ・汚染水草のような
     * 動けない種も、{@code raidWaveGroups} に入っているグループ3(原生消費種)も現在は出ない。
     * 出したくなったらここへ 1 行足すだけでよい。
     *
     * <p>生態系スポーンの表({@link com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile})とは
     * <b>別物</b> — あちらを触ってもウェーブの編成は変わらないし、その逆も同じ。
     */
    private static final Map<String, Double> WAVE_WEIGHTS = Map.of(
            "arbora", 6.0D,
            "sporophyll", 1.0D,
            "phyllosens", 1.0D,
            "velophyll", 1.0D,
            "overgrown_shielder", 1.0D);

    /** 候補プールの1件。{@code cumulative} は先頭からその種までの重みの累計(抽選を1回の二分探索なしで済ませる)。 */
    private record Candidate(EntityType<?> type, double cumulative) {
    }

    /** {@link OvergrownNexusEntity} の serverTick から、{@code raidWaveIntervalTicks} 間隔で呼ばれる。 */
    public static void tryWave(ServerLevel level, OvergrownNexusEntity nexus) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;

        int phase = PhaseData.get(level).getPhase();
        List<Candidate> pool = poolFor(phase);
        if (pool.isEmpty()) {
            return;
        }

        BlockPos origin = nexus.blockPosition();
        int spawnRadius = cfg.raidWaveSpawnRadius.get();
        int maxNearby = cfg.raidWaveMaxNearby.get();

        AABB countArea = new AABB(origin).inflate(spawnRadius * 2.0D + 16.0D);
        int nearby = level.getEntitiesOfClass(Mob.class, countArea, m -> MobGroup.of(m) != null).size();
        if (nearby > maxNearby) {
            return;
        }

        RandomSource random = level.random;
        int min = cfg.raidWaveMinSize.get();
        int max = Math.max(min, cfg.raidWaveMaxSize.get());
        int size = min + (max > min ? random.nextInt(max - min + 1) : 0);
        if (size <= 0) {
            return;
        }

        ServerPlayer targetPlayer = nearestPlayer(level, origin, cfg.raidBossBarRadius.get());

        int spawned = 0;
        int attempts = size * 4;
        for (int i = 0; i < attempts && spawned < size; i++) {
            double angle = random.nextDouble() * TAU;
            int dist = 8 + random.nextInt(Math.max(1, spawnRadius - 7));
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (spawnOne(level, pool, x, z, targetPlayer, random)) {
                spawned++;
            }
        }

        if (spawned > 0) {
            level.playSound(null, origin, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 3.0F, 0.5F);
        }
    }

    /**
     * 現在のフェーズで湧けるグループのうち、{@link #WAVE_WEIGHTS} に重みがある種だけを集めた候補プール。
     * 各要素は先頭からの重みの累計を持つので、{@link #pick} は 1 回の乱数で引ける。
     */
    private static List<Candidate> poolFor(int phase) {
        List<Candidate> pool = new ArrayList<>();
        double cumulative = 0.0D;
        for (int groupNumber : com.yayuy.overgrownvegetation.data.DesignData.phases().raidWaveGroups(phase)) {
            MobGroup group = MobGroup.byNumber(groupNumber);
            if (group == null) {
                continue;
            }
            for (EntityType<?> type : group.members()) {
                // mobs.json で naturalSpawn=false にした種は増援ウェーブにも出さない。
                if (!com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(type)) {
                    continue;
                }
                double weight = waveWeight(type);
                if (weight <= 0.0D) {
                    continue;
                }
                cumulative += weight;
                pool.add(new Candidate(type, cumulative));
            }
        }
        return pool;
    }

    /** {@code type} のウェーブ編成上の重み。表に無ければ 0(= ウェーブには出ない)。 */
    private static double waveWeight(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (key == null || !OvergrownVegetation.MODID.equals(key.getNamespace())) {
            return 0.0D;
        }
        return WAVE_WEIGHTS.getOrDefault(key.getPath(), 0.0D);
    }

    /** 重み付き抽選。{@code pool} は空でないこと。 */
    private static EntityType<?> pick(List<Candidate> pool, RandomSource random) {
        double total = pool.get(pool.size() - 1).cumulative();
        double roll = random.nextDouble() * total;
        for (Candidate candidate : pool) {
            if (roll < candidate.cumulative()) {
                return candidate.type();
            }
        }
        return pool.get(pool.size() - 1).type();
    }

    private static boolean spawnOne(ServerLevel level, List<Candidate> pool, int x, int z,
                                    @Nullable ServerPlayer targetPlayer, RandomSource random) {
        // 苗床は巨大樹の近くに立つことが多く、地表が木の天蓋・幹ならその下の本物の地面まで潜る
        // (詳細は TreeCanopyGroundFinder 参照。これが無いと Mob が木の上に湧いてしまっていた)。
        BlockPos spawnPos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (spawnPos == null) {
            return false;
        }
        if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
            return false;
        }
        EntityType<?> type = pick(pool, random);
        // 体がまるごと入るか(幅 1 を超える種・背の高い種が地面や壁へ埋まらないように。SpawnFit 参照)。
        if (!SpawnFit.fits(level, type, spawnPos)) {
            return false;
        }
        Entity spawned = type.create(level, null, spawnPos, MobSpawnType.EVENT, false, false);
        if (spawned == null) {
            return false;
        }
        level.addFreshEntity(spawned);
        if (spawned instanceof Mob mob) {
            // レイドのウェーブは、プレイヤーが少し離れても消えないよう永続化する
            // (生態系スポーンの個体と違い、レイド中は苗床の周りに留まらせたい)。
            mob.setPersistenceRequired();
            if (targetPlayer != null) {
                mob.setTarget(targetPlayer);
            }
        }
        return true;
    }

    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos origin, double maxDistance) {
        ServerPlayer best = null;
        double bestSq = maxDistance * maxDistance;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) {
                continue;
            }
            double sq = player.distanceToSqr(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
            if (sq <= bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    private RaidWaveSpawner() {
    }
}
