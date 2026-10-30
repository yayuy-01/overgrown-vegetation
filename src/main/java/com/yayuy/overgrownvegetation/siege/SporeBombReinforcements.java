package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.EventSwarms;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>反撃の胞子爆弾が着弾した地点に湧く増援</b>(ユーザー要望 2026-09-17)。
 *
 * <p>攻城戦ボスが砲台に撃たれて撃ち返した胞子爆弾({@link SiegeData#onShelled})<b>だけ</b>が
 * この増援を連れてくる。普段の曲射({@code siegeMortarIntervalTicks} の斉射)や落としてくる爆弾は
 * 何も湧かせない —— 区別は {@link ShellTracker.Shell#reinforcements}(0 なら湧かない)が持っている。
 *
 * <h2>編成</h2>
 * 湧くのは<b>グループ4(拡大種)</b>のうち {@link #WEIGHTS} に重みがある種だけ。ユーザー指定は
 * 「8 割がアルボラ」なので、アルボラ 80 に対して残り 4 種を 5 ずつ置いてある。
 * <b>表に載っていない種は湧かない</b>(重み 0) —— 動けない汚染シダ・汚染水草と、
 * グループ4 に属してはいるがボスの増援ではないレイドビーコンがこれで外れる。
 * グループ4 のタグ({@code #overgrown_vegetation:group_4})から外された種も、
 * 重みを持っていても湧かない(判定はタグが先)。
 *
 * <p>レイドのウェーブの編成表({@link com.yayuy.overgrownvegetation.raid.RaidWaveSpawner} の
 * {@code WAVE_WEIGHTS})とは<b>別物</b>。あちらを触ってもここは変わらない。
 *
 * <h2>湧かせ方</h2>
 * 着弾点のまわり {@link #SPREAD} ブロックの円のどこかで、着弾した高さに近い床を探して置く
 * ({@link #groundNear})。高台や足場の上で爆ぜたなら<b>その足場の上</b>に湧いてほしいので、
 * 地表のハイトマップ({@link TreeCanopyGroundFinder})は床が見つからなかったときの逃げ道にしている。
 * 湧いた個体は {@code setPersistenceRequired} で消えないようにし、近くのプレイヤーを初期ターゲットにする
 * (レイドのウェーブと同じ扱い —— 撃ってきた砲台の射手へ向かわせるため)。
 *
 * <h2>後始末と上限(2026-09-24、ユーザー承認)</h2>
 * 永続にするときに攻城戦の印を付け({@link com.yayuy.overgrownvegetation.raid.EventMobs})、攻城戦が止まったら外す。
 * 以前は外さず、撃つたびに 27 体ずつ永続の個体が溜まっていった。あわせて、読み込まれている増援が
 * {@link #cap()} 体に達していれば湧かせない(達しそうなら残りの枠のぶんだけ湧かせる)。
 *
 * <h2>群れで湧かせる(群れ v2、2026-09-25)</h2>
 * 着弾 1 発ぶん(既定 3 体)の G4 を、地上の種で 1 群れ・ヴェロフィルは空の群れにまとめて湧かせる
 * ({@link EventSwarms#spawnPlaced}、出どころ {@code SIEGE}・イベント ID = 攻城戦の開始時刻。所属と攻城戦の印は
 * addFreshEntity の前に書く)。置き場所の決め方(着弾した高さの床)・頭数・上限・編成の重みは今までどおりで、
 * 群れの置き方({@code SwarmSpawner} の地表)を使わないのは、足場の上で爆ぜた増援を足場の上に置くため。
 * 近くのプレイヤーは群れの<b>ふつうの</b>標的として指定する({@code Swarm#offerTarget}。レイドのウェーブと同じ。
 * 仮決め 2026-09-25、レビュー指摘)。
 */
public final class SporeBombReinforcements {

    /**
     * 増援の編成(種 → 相対的な重み)。ユーザー指定 2026-09-17:「8 割がアルボラ」。
     * 残りの 2 割を 4 種で分けるので 5 ずつ。
     */
    private static final Map<String, Double> WEIGHTS = Map.of(
            "arbora", 80.0D,
            "sporophyll", 5.0D,
            "phyllosens", 5.0D,
            "velophyll", 5.0D,
            "overgrown_shielder", 5.0D);

    /** 着弾点から湧く位置を散らす半径(ブロック)。1 体ずつ重ならないように少しだけ広げる。 */
    private static final double SPREAD = 3.0D;
    /** 1 体あたりの置き場所の試行回数。 */
    private static final int ATTEMPTS_PER_MOB = 8;
    /** 床を探すときに着弾点の上下を見る範囲(ブロック)。 */
    private static final int FLOOR_UP = 3;
    private static final int FLOOR_DOWN = 6;
    /** 初期ターゲットにするプレイヤーを探す距離(ブロック)。 */
    private static final double TARGET_RANGE = 48.0D;

    /** 候補プールの1件。{@code cumulative} は先頭からその種までの重みの累計。 */
    private record Candidate(EntityType<?> type, double cumulative) {
    }

    /**
     * {@code at} の着弾地点に増援を {@code count} 体湧かせる。
     *
     * @return 実際に湧いた数(置き場所が見つからなければ {@code count} を下回る)
     */
    public static int spawn(ServerLevel level, Vec3 at, int count) {
        // ピースフル(difficulty/Peaceful、2026-09-26): 攻城戦を止めたあとに着弾した爆弾からも増援(グループ4 = 敵)は出さない。
        if (count <= 0 || com.yayuy.overgrownvegetation.difficulty.Peaceful.is(level)) {
            return 0;
        }
        List<Candidate> pool = pool();
        if (pool.isEmpty()) {
            return 0;
        }
        String origin = siegeOrigin(level);
        int room = cap() - com.yayuy.overgrownvegetation.raid.EventMobs.count(level,
                com.yayuy.overgrownvegetation.raid.EventMobs.Kind.SIEGE, origin);
        if (room <= 0) {
            return 0;
        }
        count = Math.min(count, room);
        RandomSource random = level.random;
        ServerPlayer target = nearestPlayer(level, at);
        // 置き場所を 1 体ずつ決めてから(以前と同じ抽選・試行回数)、まとめて群れとして湧かせる。
        List<EventSwarms.Placement> placements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < ATTEMPTS_PER_MOB; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double radius = Math.sqrt(random.nextDouble()) * SPREAD;
                int x = (int) Math.floor(at.x + Math.cos(angle) * radius);
                int z = (int) Math.floor(at.z + Math.sin(angle) * radius);
                EventSwarms.Placement placement = place(level, pool, x, z, at.y, random);
                if (placement != null) {
                    placements.add(placement);
                    break;
                }
            }
        }
        if (placements.isEmpty()) {
            return 0;
        }
        return EventSwarms.spawnPlaced(level, placements, SwarmOrigin.SIEGE, origin, target);
    }

    /** グループ4 のうち {@link #WEIGHTS} に重みがある種だけを集めた候補プール。 */
    private static List<Candidate> pool() {
        List<Candidate> pool = new ArrayList<>();
        double cumulative = 0.0D;
        for (EntityType<?> type : MobGroup.GROUP_4.members()) {
            // mobs.json で naturalSpawn=false にした種は増援にも出さない(レイドのウェーブと同じ扱い)。
            if (!com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(type)) {
                continue;
            }
            double weight = weight(type);
            if (weight <= 0.0D) {
                continue;
            }
            cumulative += weight;
            pool.add(new Candidate(type, cumulative));
        }
        return pool;
    }

    /** {@code type} の増援としての重み。表に無ければ 0(= 湧かない)。 */
    private static double weight(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (key == null || !OvergrownVegetation.MODID.equals(key.getNamespace())) {
            return 0.0D;
        }
        return WEIGHTS.getOrDefault(key.getPath(), 0.0D);
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

    /**
     * {@code (x, z)} 列に 1 体ぶんの置き場所を決める(床・水・体が入るかを確かめ、種を抽選する)。使えなければ null。
     * 永続と攻城戦の印は湧かせるときに付く(攻城戦が止まったら外れる。EventMobs)。
     */
    @Nullable
    private static EventSwarms.Placement place(ServerLevel level, List<Candidate> pool, int x, int z, double impactY,
                                               RandomSource random) {
        BlockPos spawnPos = groundNear(level, x, z, impactY);
        if (spawnPos == null) {
            return null;
        }
        if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
            return null;
        }
        EntityType<?> type = pick(pool, random);
        // 体がまるごと入るか(幅 1 を超える種・背の高い種が地面や壁へ埋まらないように)。
        if (!SpawnFit.fits(level, type, spawnPos)) {
            return null;
        }
        return new EventSwarms.Placement(type, spawnPos);
    }

    /**
     * {@code (x, z)} 列で、着弾した高さ {@code impactY} のいちばん近くにある床の上を返す。
     * 上下 {@link #FLOOR_UP}/{@link #FLOOR_DOWN} ブロックに床が無ければ、その列の地表
     * ({@link TreeCanopyGroundFinder#findSpawnSurface}) へ落とす。未ロードなら {@code null}。
     */
    @Nullable
    private static BlockPos groundNear(ServerLevel level, int x, int z, double impactY) {
        int top = (int) Math.floor(impactY) + FLOOR_UP;
        if (!level.hasChunkAt(new BlockPos(x, top, z))) {
            return null;
        }
        int bottom = Math.max(level.getMinBuildHeight() + 1, top - FLOOR_UP - FLOOR_DOWN);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= bottom; y--) {
            cursor.setY(y);
            if (level.getBlockState(cursor).blocksMotion()) {
                continue;
            }
            if (level.getBlockState(cursor.below()).blocksMotion()) {
                return cursor.immutable();
            }
        }
        return TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
    }

    /**
     * 読み込まれている反撃の増援の上限(2026-09-24)。<b>仮決め</b>: 専用の設定は作らず、
     * レイドのウェーブの上限 {@code raidWaveMaxNearby}(既定 100)を借りている。
     * 既定の反撃 1 回ぶん(27 体)なら 3 回までは削られずに湧く。
     */
    private static int cap() {
        return com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.raidWaveMaxNearby.get();
    }

    /** いまの攻城戦の印({@link SiegeData#startGameTime})。 */
    private static String siegeOrigin(ServerLevel level) {
        return Long.toString(SiegeData.get(level).startGameTime());
    }

    /** 着弾点から {@link #TARGET_RANGE} 以内でいちばん近いプレイヤー(観戦・クリエイティブは除く)。 */
    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, Vec3 at) {
        ServerPlayer best = null;
        double bestSq = TARGET_RANGE * TARGET_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) {
                continue;
            }
            double sq = player.distanceToSqr(at);
            if (sq <= bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    private SporeBombReinforcements() {
    }
}
