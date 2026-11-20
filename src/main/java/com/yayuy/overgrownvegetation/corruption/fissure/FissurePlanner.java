package com.yayuy.overgrownvegetation.corruption.fissure;

import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔が地下に小さな亀裂を開く判断(2026-11-20 ユーザー決定)。{@code BaseWatch.sample} から呼ばれ、{@code plan.intervalDays} ごとに 1 回考える。
 *
 * <p><b>プレイヤーの位置を基準にしない</b>(ユーザー「逃げた先でまた発生と少し理不尽に、または鬱陶しく感じられる」)。場所は
 * 森が知っている範囲 = 森の目印(F1 の地割れ・ハイヴノード・巨大樹・器官)から {@code reachBlocks} 以内の、地脈の線の下(線から {@code lineBlocks})。
 * 決めるのは x, z だけで、洞窟の床(y)はそのあたりが読み込まれたときに {@link FissureWorld} が探す(読み込まれた所から選ぶと、
 * 結局プレイヤーのそばになるため)。洞窟が無い・汲み上げ器が近ければ選び直す。
 *
 * <p>F4 から。数の上限は {@code plan.maxActive} で一定(フェーズで増やさない。器官の数の倍率も通さない)。
 */
public final class FissurePlanner {
    private FissurePlanner() {
    }

    /** {@code BaseWatch.sample} から(20 秒ごと)。 */
    public static void tick(ServerLevel level, double day) {
        FissureTable t = DesignData.fissure();
        if (!t.enabled || !DesignData.director().enabled) return;
        FissureData data = FissureData.get(level);
        if (data.planDay() >= 0.0D && day - data.planDay() < t.plan.intervalDays) return;
        data.setPlanDay(day);
        String why = tryPlan(level, level.getRandom(), DirectorGates.phase(level), false);
        if (why != null) ForestDirector.get(level).note("亀裂: " + why);
    }

    /**
     * 1 回考える。開く場所を決めたら説明、見送ったら理由(早すぎる・上限は null)。{@code force} はくじ・予算・上限・フェーズを見ない(確かめ用)。
     * フェーズは引数で渡す(テストがワールドのフェーズを変えずに確かめるため)。
     */
    @Nullable
    public static String tryPlan(ServerLevel level, RandomSource random, int phase, boolean force) {
        FissureTable t = DesignData.fissure();
        FissureData data = FissureData.get(level);
        if (!force) {
            if (!phaseOpen(phase)) return null;
            if (DirectorGates.peaceful(level)) return null;
            if (data.aliveCount() >= cap(phase)) return null;
            if (random.nextDouble() >= t.plan.chance) return "くじで見送った";
        }
        ForestDirector dir = ForestDirector.get(level);
        double price = Math.min(t.plan.price, ForestDirector.cap(level) * 0.95D);
        if (!force && dir.available(level) < price) {
            return String.format(Locale.ROOT, "予算が足りない(%.0f / 値段 %.0f)", dir.available(level), price);
        }
        int[] site = chooseSite(level, data, random);
        if (site == null) return "地脈の線の下に場所が見つからない";
        if (!force && !dir.tryPay(level, price)) return "払えなかった";
        FissureData.Fissure f = data.add(site[0], site[1], random.nextInt(), PhaseData.clockNow(level), force ? 0.0D : price);
        String line = String.format(Locale.ROOT, "亀裂 #%d を (%d, %d) の地下に開く(地脈の線まで %.0f・%.0f 払った)", f.id, f.x, f.z,
                LeyLines.distanceToLine(level.getSeed(), f.x, f.z), f.paid);
        dir.note(line);
        return line;
    }

    /** そのフェーズで開くか(F4 から)。 */
    public static boolean phaseOpen(int phase) {
        return phase >= DesignData.fissure().plan.minPhase;
    }

    /** 同時に生きている数の上限。フェーズを受け取るが使わない(ユーザー決定: フェーズが進んでも数は増やさない)。 */
    public static int cap(int phase) {
        return phaseOpen(phase) ? DesignData.fissure().plan.maxActive : 0;
    }

    /**
     * 場所(x, z)を選ぶ。プレイヤーを見ない。同じワールドの記録と同じくじなら同じ答え(テスト「プレイヤーを動かしても場所の選びが変わらない」)。
     * 見つからなければ null。
     */
    @Nullable
    public static int[] chooseSite(ServerLevel level, FissureData data, RandomSource random) {
        return chooseSite(level, data, random, -1);
    }

    @Nullable
    static int[] chooseSite(ServerLevel level, FissureData data, RandomSource random, int except) {
        FissureTable.Plan t = DesignData.fissure().plan;
        List<long[]> anchors = anchors(level);
        if (anchors.isEmpty()) return null;
        PhaseData pd = PhaseData.get(level);
        long seed = level.getSeed();
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < t.samples; i++) {
            long[] a = anchors.get(random.nextInt(anchors.size()));
            double ang = random.nextDouble() * Math.PI * 2.0D;
            double dist = Math.sqrt(random.nextDouble()) * t.reachBlocks;
            double jitter = random.nextDouble();
            int x = (int) Math.floor(a[0] + Math.cos(ang) * dist), z = (int) Math.floor(a[1] + Math.sin(ang) * dist);
            double d = LeyLines.distanceToLine(seed, x, z);
            if (d > t.lineBlocks) continue;
            if (!allowed(level, data, pd, x, z, except)) continue;
            double s = -d + jitter * 4.0D; // 線に近いほど(少し揺らす)
            if (s > bestScore) {
                bestScore = s;
                best = new int[]{x, z};
            }
        }
        return best;
    }

    /** その列に開いてよいか(過繁茂の台帳・ほかの亀裂・知っている汲み上げ器)。洞窟と押し返す道具は開くときに見る。 */
    static boolean allowed(ServerLevel level, FissureData data, PhaseData pd, int x, int z, int except) {
        FissureTable.Plan t = DesignData.fissure().plan;
        if (t.avoidOvergrown && pd.isOvergrownKey(ChunkPos.asLong(x >> 4, z >> 4))) return false;
        for (FissureData.Fissure f : data.fissures()) {
            if (!f.alive() || f.id == except) continue;
            double dx = f.x - x, dz = f.z - z;
            if (dx * dx + dz * dz < (double) t.spacingBlocks * t.spacingBlocks) return false;
        }
        return !nearKnownPump(data, x, z);
    }

    static boolean nearKnownPump(FissureData data, int x, int z) {
        int r = DesignData.fissure().plan.pumpClearBlocks;
        for (long p : data.pumps()) {
            double dx = BlockPos.getX(p) - x, dz = BlockPos.getZ(p) - z;
            if (dx * dx + dz * dz <= (double) r * r) return true;
        }
        return false;
    }

    /** テスト用の目印(null = ワールドの記録から)。 */
    @Nullable
    static List<long[]> testAnchors;

    /**
     * 森の目印(ブロックの x, z): F1 の地割れ・ハイヴノード・巨大樹・器官。プレイヤーと拠点は入れない(器官の目印とはここが違う)。
     */
    static List<long[]> anchors(ServerLevel level) {
        if (testAnchors != null) return testAnchors;
        List<long[]> l = new ArrayList<>();
        for (long k : com.yayuy.overgrownvegetation.quake.EarthquakeData.riftCenters()) l.add(new long[]{BlockPos.getX(k), BlockPos.getZ(k)});
        PhaseData pd = PhaseData.get(level);
        for (long k : pd.hiveNodeSnapshot()) l.add(new long[]{BlockPos.getX(k), BlockPos.getZ(k)});
        // 巨大樹の記録は (x << 32) | z
        for (long k : pd.giantTreeOriginSnapshot()) l.add(new long[]{(int) (k >> 32), (int) k});
        for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
            if (o.state != ForestOrgans.State.WITHERED) l.add(new long[]{o.x, o.z});
        }
        return l;
    }
}
