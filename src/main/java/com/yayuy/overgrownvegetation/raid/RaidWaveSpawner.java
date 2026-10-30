package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmSpawner;
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
import net.minecraft.world.phys.Vec3;

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
 *
 * <h2>群れで湧かせる(群れ v2、2026-09-25)</h2>
 * 1 ウェーブの頭数と編成の抽選は今までどおり。抽選した G4 は、地上の種・ヴェロフィル(空)ごとに上限
 * ({@code swarm.json} の {@code maxSize}、地上 12 / 空 6)以下の<b>群れ</b>にまとめ、群れごとにビーコンの輪
 * (8〜{@code raidWaveSpawnRadius})の 1 点を中心に湧かせる({@link SwarmSpawner}、出どころ {@code RAID}、
 * イベント ID = ビーコンの UUID で {@link EventMobs} の NEXUS の印も addFreshEntity の前に付く)。
 * 以前は 1 体ずつ輪のあちこちに散らしていた —— 群れにすると後続は経路を引かずに先導役の足跡を追うので、
 * 最初から固まって湧かせる。近くのプレイヤーは群れの<b>ふつうの</b>標的として指定する({@code Swarm#offerTarget}:
 * 追う距離はふつうのプレイヤーと同じ。狙い {@code commandTarget} にすると、永続の群れが苗床を離れて 1 人をどこまでも追う。
 * 仮決め 2026-09-25、レビュー指摘)。
 * 群れに入らない種(表に足した場合)は今までどおり 1 体ずつ散らして湧かせる。
 */
public final class RaidWaveSpawner {

    private static final double TAU = Math.PI * 2.0D;
    /** 1 つの群れの中心を探す回数(水・未ロードで空振りするため)。置ききれなかった残りは次の中心で別の群れになる。 */
    private static final int CENTER_ATTEMPTS = 4;

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

    /**
     * ウェーブの時計を進めてよいか = {@code pos}(苗床)から {@code raidWavePlayerRadius} 以内に、生きている
     * サバイバル / アドベンチャーのプレイヤーが 1 人でも居るか(2026-09-25、ユーザー承認「レイドの見直し」、報告 410)。
     *
     * <p>苗床は強制ロードの中で誰も居なくても動くので、以前は 1 分ごとのウェーブが誰にも向かわずに湧き続け、
     * 永続の個体が上限({@code raidWaveMaxNearby})まで溜まっていた。居ない間は時計ごと止め、戻ってきたら続きから数える
     * ({@link OvergrownNexusEntity} の {@code waveClock})。止まっていた間のウェーブは出さない。
     */
    public static boolean hasWavePlayerNearby(ServerLevel level, Vec3 pos) {
        double radius = OvergrownVegetationConfig.COMMON.raidWavePlayerRadius.get();
        double radiusSq = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative() || !player.isAlive()) {
                continue;
            }
            if (player.distanceToSqr(pos) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link OvergrownNexusEntity} の serverTick から、ウェーブの時計が {@code raidWaveIntervalTicks} に達するたびに呼ばれる
     * (時計は {@link #hasWavePlayerNearby} の間だけ進む)。
     */
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

        // 数えるのは「このビーコンのウェーブで湧いて、いま読み込まれている個体」(2026-09-24、ユーザー承認)。
        // 以前は苗床のまわりの箱(約 64 ブロック)の過繫茂 Mob を数えていたので、プレイヤーを追って
        // 箱の外へ出たウェーブの個体が数に入らず、上限を超えて湧き足されていた。
        String nexusId = nexus.getUUID().toString();
        int nearby = EventMobs.count(level, EventMobs.Kind.NEXUS, nexusId);
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

        // 編成は今までどおり 1 体ずつ重みで抽選し、群れに入る種は群れにまとめる。
        List<EntityType<?>> planned = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            planned.add(pick(pool, random));
        }
        EventSwarms.Split split = EventSwarms.split(planned);
        int spawned = 0;
        for (List<EntityType<?>> batch : EventSwarms.batches(split.ground(), Swarm.Kind.GROUND)) {
            spawned += spawnSwarm(level, origin, spawnRadius, batch, targetPlayer, random, nexusId);
        }
        for (List<EntityType<?>> batch : EventSwarms.batches(split.air(), Swarm.Kind.AIR)) {
            spawned += spawnSwarm(level, origin, spawnRadius, batch, targetPlayer, random, nexusId);
        }
        // 群れに入らない種は今までどおり 1 体ずつ散らす(1 体あたり 4 回まで)。
        for (EntityType<?> type : split.individuals()) {
            for (int attempt = 0; attempt < 4; attempt++) {
                int[] xz = ringPoint(origin, spawnRadius, random);
                if (spawnOne(level, type, xz[0], xz[1], targetPlayer, nexusId)) {
                    spawned++;
                    break;
                }
            }
        }

        if (spawned > 0) {
            level.playSound(null, origin, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 3.0F, 0.5F);
        }
    }

    /** ビーコンから 8〜{@code spawnRadius} の輪の 1 点(以前の散らし方と同じ分布)。 */
    private static int[] ringPoint(BlockPos origin, int spawnRadius, RandomSource random) {
        double angle = random.nextDouble() * TAU;
        int dist = 8 + random.nextInt(Math.max(1, spawnRadius - 7));
        return new int[] {origin.getX() + (int) Math.round(Math.cos(angle) * dist),
                origin.getZ() + (int) Math.round(Math.sin(angle) * dist)};
    }

    /**
     * {@code batch}(同じ種類・上限以下)を 1 つの群れとして、輪の 1 点を中心に湧かせる。中心が水・未ロードなら別の点を試し
     * ({@link #CENTER_ATTEMPTS} 回まで)、置ききれなかった残りは次の点で別の群れにする(頭数を減らさない)。
     *
     * @return 湧いた頭数
     */
    private static int spawnSwarm(ServerLevel level, BlockPos origin, int spawnRadius, List<EntityType<?>> batch,
                                  @Nullable ServerPlayer targetPlayer, RandomSource random, String nexusId) {
        List<EntityType<?>> remaining = new ArrayList<>(batch);
        int spawned = 0;
        for (int attempt = 0; attempt < CENTER_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            int[] xz = ringPoint(origin, spawnRadius, random);
            BlockPos center = swarmCenter(level, xz[0], xz[1]);
            if (center == null) {
                continue;
            }
            SwarmSpawner.Result result = SwarmSpawner.spawn(level, center, remaining, SwarmOrigin.RAID, nexusId,
                    targetPlayer);
            for (Mob mob : result.members()) {
                remaining.remove(mob.getType());
                spawned++;
            }
        }
        return spawned;
    }

    /**
     * 群れの中心にする地表(読み込み済みのチャンク・木の天蓋の下の地面・足元と頭が乾いている)。使えなければ null。
     * 体が入るかは群れを置くときに 1 体ずつ見る({@link SwarmSpawner})。
     */
    @Nullable
    private static BlockPos swarmCenter(ServerLevel level, int x, int z) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            return null;
        }
        BlockPos pos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (pos == null || !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
            return null;
        }
        return pos;
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

    /** 群れに入らない種を {@code (x, z)} 列に 1 体湧かせる(以前の 1 体ずつの湧かせ方。印は addFreshEntity の前に付ける)。 */
    private static boolean spawnOne(ServerLevel level, EntityType<?> type, int x, int z,
                                    @Nullable ServerPlayer targetPlayer, String nexusId) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            return false;
        }
        // 苗床は巨大樹の近くに立つことが多く、地表が木の天蓋・幹ならその下の本物の地面まで潜る
        // (詳細は TreeCanopyGroundFinder 参照。これが無いと Mob が木の上に湧いてしまっていた)。
        BlockPos spawnPos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (spawnPos == null) {
            return false;
        }
        if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
            return false;
        }
        // 体がまるごと入るか(幅 1 を超える種・背の高い種が地面や壁へ埋まらないように。SpawnFit 参照)。
        if (!SpawnFit.fits(level, type, spawnPos)) {
            return false;
        }
        Entity spawned = type.create(level, null, spawnPos, MobSpawnType.EVENT, false, false);
        if (!(spawned instanceof Mob mob)) {
            if (spawned != null) {
                spawned.discard();
            }
            return false;
        }
        // レイドのウェーブは、プレイヤーが少し離れても消えないよう永続化する
        // (生態系スポーンの個体と違い、レイド中は苗床の周りに留まらせたい)。
        // ビーコンの印も付け、レイドが終わったら永続を外す(2026-09-24、EventMobs)。
        EventMobs.tag(mob, EventMobs.Kind.NEXUS, nexusId);
        if (!level.addFreshEntity(mob)) {
            return false;
        }
        if (targetPlayer != null) {
            mob.setTarget(targetPlayer);
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
