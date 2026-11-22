package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔が器官を芽吹かせる判断(2026-11-17。ユーザー決定「司令塔が予算を払って建てる。建てられるのは、境目から奥で、過繁茂になってから
 * 日が経った升だけ」)。{@code BaseWatch.sample} から呼ばれ、{@code planIntervalDays} ごとに 1 回考える。
 *
 * <p>升 = チャンク。条件: 台帳に載っている・載ってから {@code minAgeDays} 日・まわり {@code minDepthChunks} チャンクが全部台帳に載っている
 * (= 境目から奥)・ほかの器官から {@code spacingChunks}・認識した拠点から {@code baseClearChunks}。
 * 候補は台帳全体をなめず、目印(プレイヤー・拠点・ハイヴノード・巨大樹・器官)のまわりをくじで引く(F7 の約 530 万チャンクでも軽い)。
 *
 * <p>種類ごとの好み: 塔 = 風下に森の外がある所(広げたい方角の風上)・根 = 地脈の線の近く(近いほど)・苗床 = 認識した拠点から
 * {@code nurseryMin〜MaxBaseChunks}・フェーズと嚢 = 古い所。種類は「足りない種類」を重みで選ぶ。
 */
public final class OrganPlanner {
    private OrganPlanner() {
    }

    /** {@code BaseWatch.sample} から(20 秒ごと)。 */
    public static void tick(ServerLevel level, double day) {
        OrganTable t = DesignData.organs();
        if (!t.enabled || !DesignData.director().enabled) return;
        if (!com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.on(level.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.ORGANS)) return; // MOD の設定「器官が生えるか」(11-19)。オフでも今ある器官は残す
        ForestOrgans data = ForestOrgans.get(level);
        if (data.planDay() >= 0.0D && day - data.planDay() < t.planIntervalDays) return;
        data.setPlanDay(day);
        String why = tryPlan(level, level.getRandom(), null, false);
        if (why != null) ForestDirector.get(level).note("器官: " + why);
        if (t.smallEnabled) {
            String w2 = tryPlan(level, level.getRandom(), null, true);
            if (w2 != null) ForestDirector.get(level).note("器官(小): " + w2);
        }
    }

    /**
     * 1 回考える。芽吹かせたら説明、見送ったら理由を返す(司令塔の直近の一覧へ)。{@code force} を渡すとその種類で
     * (予算・数の上限・フェーズを見ない。確かめ用)。
     */
    /** 器官の数の上限(11-22 ユーザー決定): (フェーズの数 + 生きている巨大樹 × 大 1・小 2)× MOD の設定「器官の数」の倍率。乗っ取りの器官も数える。 */
    public static long limit(ServerLevel level, boolean small) {
        OrganTable t = DesignData.organs();
        double countFactor = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.of(level.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.ORGAN_COUNT);
        int trees = com.yayuy.overgrownvegetation.front.FrontTrees.livingTreeCount(level);
        return Math.round(t.limit(DirectorGates.phase(level), trees, small) * countFactor);
    }

    @Nullable
    public static String tryPlan(ServerLevel level, RandomSource random, @Nullable OrganKind force, boolean small) {
        OrganTable t = DesignData.organs();
        int phase = DirectorGates.phase(level);
        if (force == null) {
            if (phase < t.minPhase) return null; // まだ早い(毎回は書かない)
            if (DirectorGates.peaceful(level)) return null;
            if (ForestDirector.get(level).mood() != ForestDirector.Mood.NORMAL) return "貯めている間は建てない";
        }
        ForestOrgans data = ForestOrgans.get(level);
        if (force == null && !com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.on(level.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.ORGANS)) return null; // 11-19 MOD の設定「器官が生えるか」
        // MOD の設定「器官の数」(11-19): 上限に倍率を掛ける(減らしても今ある器官は消さない)。11-22 から上限は巨大樹の数でも増える(limit)
        if (force == null && data.livingCount(small) >= limit(level, small)) return null;
        double cap = ForestDirector.cap(level);
        OrganKind kind = force != null ? force : pickKind(level, data, random);
        // 11-22 ユーザー決定: 蓄えの嚢は 2 つ目から値段が上がる(今ある嚢 1 つごとに × sacPriceGrowth【仮】)
        double price = kind == OrganKind.SAC ? sacPrice(level, data, small) : price(level, small);
        ForestDirector dir = ForestDirector.get(level);
        if (force == null && dir.available(level) < price) {
            return String.format(Locale.ROOT, "予算が足りない(%s・%.0f / 値段 %.0f・上限 %.0f)", kind.label, dir.available(level), price, cap);
        }
        Site best = findSite(level, data, kind, small, random);
        if (best == null) return kind.label + " を建てる升が見つからない";
        if (force == null && !dir.tryPay(level, price)) return "払えなかった";
        int variant = small ? random.nextInt(OrganShapes.SMALL_VARIANTS) : pickVariant(kind, best.ageDays, random);
        // 小さい版は升の中で少しずらす(升の真ん中に並ばないように)
        BlockPos at = best.chunk.getMiddleBlockPosition(0).offset(small ? random.nextInt(9) - 4 : 0, 0, small ? random.nextInt(9) - 4 : 0);
        ForestOrgans.Organ o = data.plant(level, kind, small, variant, random.nextInt(), random.nextInt(4), at.getX(), at.getZ());
        String line = String.format(Locale.ROOT, "器官 #%d %s%s %s を (%d, %d) に芽吹かせた(%.0f 払った・升の古さ %.1f 日)", o.id, kind.label,
                small ? "(小)" : "", small ? (variant == 0 ? "a" : "b") : OrganShapes.variantName(variant), at.getX(), at.getZ(),
                force == null ? price : 0.0D, best.ageDays);
        dir.note(line);
        OrganNotices.organ(level, o); // 開発用の知らせ(11-20)
        return line;
    }

    /** 値段(2026-11-17 ユーザー: 大きい版 120・小さい版 30)。上限を超える値段は上限の 95% に切る(払えるように)。 */
    public static double price(ServerLevel level, boolean small) {
        return Math.min(DesignData.organs().price(small), ForestDirector.cap(level) * 0.95D);
    }

    /** 蓄えの嚢の値段(11-22 ユーザー決定): 器官の値段 × sacPriceGrowth^(今ある同じ大きさの嚢の数)。上限の 95% で切る。 */
    public static double sacPrice(ServerLevel level, ForestOrgans data, boolean small) {
        double p = DesignData.organs().price(small) * Math.pow(DesignData.director().sacPriceGrowth, data.livingCount(OrganKind.SAC, small));
        return Math.min(p, ForestDirector.cap(level) * 0.95D);
    }

    static OrganKind pickKind(ServerLevel level, ForestOrgans data, RandomSource random) {
        OrganTable t = DesignData.organs();
        boolean anyBase = false;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() == BaseMemory.Stage.RECOGNIZED) anyBase = true;
        }
        OrganKind[] ks = OrganKind.values();
        double[] w = new double[ks.length];
        double total = 0.0D;
        for (int i = 0; i < ks.length; i++) {
            if (ks[i] == OrganKind.NURSERY && !anyBase) continue;
            if (ks[i] == OrganKind.SAC) {
                // 11-22 ユーザー決定: 蓄えの嚢の重みは直近に上限からあふれた量で決める(あふれていなければ建てない。数の上限は付けない。高くなるのは値段で)
                double cap = Math.max(1.0D, ForestDirector.cap(level));
                w[i] = t.kindWeights[i] * DesignData.director().sacOverflowWeight * com.yayuy.overgrownvegetation.director.StorageOverflow.recent() / cap;
            } else {
                w[i] = t.kindWeights[i] / (1.0D + data.count(ks[i], false)); // 足りない種類ほど
            }
            total += w[i];
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < ks.length; i++) {
            r -= w[i];
            if (r <= 0.0D && w[i] > 0.0D) return ks[i];
        }
        return OrganKind.TOWER;
    }

    /** 形を選ぶ: 崩れた版は升が古いほど出やすい(ruinAgeDays で 5 割まで)。 */
    static int pickVariant(OrganKind kind, double ageDays, RandomSource random) {
        double ruin = Math.max(0.1D, Math.min(0.5D, 0.5D * ageDays / DesignData.organs().ruinAgeDays));
        int n = OrganShapes.variants(kind);
        if (random.nextDouble() < ruin) return n - 1;
        return random.nextInt(n - 1);
    }

    record Site(ChunkPos chunk, double score, double ageDays) {
    }

    /**
     * 巨大樹のそばか(11-19 ユーザー「巨大樹は避けて」)。巨大樹は払わないので、器官は巨大樹の根元から {@code clearing.giantAvoidBlocks}
     * (小さい版 {@code giantAvoidSmallBlocks})より外の升にだけ建てる。升の中で真ん中からずれる分(8)を足して見る。
     */
    static boolean nearGiantTree(PhaseData pd, int cx, int cz, boolean small, OrganTable t) {
        double avoid = (small ? t.giantAvoidSmall : t.giantAvoid) + 8.0D;
        double mx = cx * 16 + 8, mz = cz * 16 + 8;
        for (long k : pd.giantTreeOriginSnapshot()) {
            double dx = (int) (k >> 32) - mx, dz = (int) k - mz;
            if (dx * dx + dz * dz < avoid * avoid) return true;
        }
        return false;
    }

    @Nullable
    static Site findSite(ServerLevel level, ForestOrgans data, OrganKind kind, boolean small, RandomSource random) {
        OrganTable t = DesignData.organs();
        PhaseData pd = PhaseData.get(level);
        long now = pd.clock(level.getServer());
        List<long[]> anchors = anchors(level, data, pd);
        if (anchors.isEmpty()) return null;
        PrevailingWind.Sample wind = SporeFieldServer.windNow(level);
        Site best = null;
        for (int i = 0; i < t.samples; i++) {
            long[] a = anchors.get(random.nextInt(anchors.size()));
            double ang = random.nextDouble() * Math.PI * 2.0D;
            double dist = t.anchorMinChunks + random.nextDouble() * (t.anchorMaxChunks - t.anchorMinChunks);
            int cx = (int) Math.floor(a[0] + Math.cos(ang) * dist), cz = (int) Math.floor(a[1] + Math.sin(ang) * dist);
            double s = score(level, pd, data, kind, small, cx, cz, now, wind, random);
            if (s == Double.NEGATIVE_INFINITY) continue;
            if (best == null || s > best.score) {
                long since = pd.overgrownSinceTick(new ChunkPos(cx, cz));
                best = new Site(new ChunkPos(cx, cz), s, (now - since) / 24000.0D);
            }
        }
        return best;
    }

    /** 目印(チャンクの座標): サバイバルのプレイヤー・認識した拠点・ハイヴノード・巨大樹・器官。 */
    static List<long[]> anchors(ServerLevel level, ForestOrgans data, PhaseData pd) {
        List<long[]> l = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) l.add(new long[]{p.getBlockX() >> 4, p.getBlockZ() >> 4});
        }
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            l.add(new long[]{(long) Math.floor(b.centerX()) >> 4, (long) Math.floor(b.centerZ()) >> 4});
        }
        for (long k : pd.hiveNodeSnapshot()) l.add(new long[]{BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4});
        // 巨大樹の記録は (x << 32) | z で詰めてある(BlockPos の詰め方ではない。11-19 に読み違いを直した)
        for (long k : pd.giantTreeOriginSnapshot()) l.add(new long[]{(int) (k >> 32) >> 4, (int) k >> 4});
        for (ForestOrgans.Organ o : data.organs()) l.add(new long[]{o.x >> 4, o.z >> 4});
        return l;
    }

    /** 升の点(建てられなければ負の無限大)。 */
    static double score(ServerLevel level, PhaseData pd, ForestOrgans data, OrganKind kind, boolean small, int cx, int cz, long now,
                        @Nullable PrevailingWind.Sample wind, RandomSource random) {
        OrganTable t = DesignData.organs();
        if (!buildable(pd, data, cx, cz, now, t, small)) return Double.NEGATIVE_INFINITY;
        if (nearGiantTree(pd, cx, cz, small, t)) return Double.NEGATIVE_INFINITY;
        // 構造物(地表に出ている物)のそば(11-19 融合 段 1): 大きい版は建てない、小さい版は全体の大きさの段に応じて少し好む(遠征の目的地)。
        // 読み込まれていない升は分からないので何もしない(建つ前に OrganFusion がもう一度見る)
        double structBonus = 0.0D;
        com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable ft = com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.table();
        if (ft.enabled && OrganWorld.ready(level, cx, cz)) {
            int pad = small ? 8 : OrganFusion.LARGE_REACH - 8;
            net.minecraft.world.level.levelgen.structure.BoundingBox box = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                    cx * 16 - pad, level.getMinBuildHeight(), cz * 16 - pad, cx * 16 + 15 + pad, level.getMaxBuildHeight(), cz * 16 + 15 + pad);
            com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.Query q = com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.near(level, box);
            if (!q.unknown() && !q.hits().isEmpty()) {
                if (!small) return Double.NEGATIVE_INFINITY;
                int cls = 0;
                for (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.Hit h : q.hits()) cls = Math.max(cls, h.wholeClass().ordinal());
                structBonus = ft.structureWeight[cls];
            }
        }
        // 認識した拠点から離す(苗床は近すぎず遠すぎず)
        double baseDist = Double.POSITIVE_INFINITY;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            double dx = b.centerX() / 16.0D - (cx + 0.5D), dz = b.centerZ() / 16.0D - (cz + 0.5D);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < t.baseClearChunks) return Double.NEGATIVE_INFINITY;
            if (b.stage() == BaseMemory.Stage.RECOGNIZED) baseDist = Math.min(baseDist, d);
        }
        double ageDays = (now - pd.overgrownSinceTick(new ChunkPos(cx, cz))) / 24000.0D;
        double s = random.nextDouble() * 0.2D + structBonus;
        switch (kind) {
            case TOWER -> {
                // 風下に森の外があるほど(近いほど)よい = 広げたい方角の風上
                if (wind == null) {
                    s += 0.5D;
                } else {
                    double hit = 0.0D;
                    for (int k = t.minDepthChunks + 1; k <= t.minDepthChunks + 16; k++) {
                        int x = cx + (int) Math.round(wind.dirX * k), z = cz + (int) Math.round(wind.dirZ * k);
                        if (!pd.isOvergrownKey(ChunkPos.asLong(x, z))) {
                            hit = 1.0D - (k - t.minDepthChunks - 1) / 16.0D;
                            break;
                        }
                    }
                    s += hit * 2.0D;
                }
            }
            case LEY -> {
                double d = LeyLines.distanceToLine(level.getSeed(), cx * 16 + 8, cz * 16 + 8);
                if (d > t.leyLineBlocks) return Double.NEGATIVE_INFINITY;
                s += 2.0D * (1.0D - d / t.leyLineBlocks);
            }
            case NURSERY -> {
                if (baseDist < t.nurseryMinBaseChunks || baseDist > t.nurseryMaxBaseChunks) return Double.NEGATIVE_INFINITY;
                s += 2.0D * (1.0D - (baseDist - t.nurseryMinBaseChunks) / (t.nurseryMaxBaseChunks - t.nurseryMinBaseChunks));
            }
            case PHASE, SAC -> s += Math.min(1.0D, ageDays / t.ruinAgeDays);
        }
        return s;
    }

    /** 建てられる升か(台帳・古さ・奥行き・ほかの器官からの間隔)。テストからも。 */
    static boolean buildable(PhaseData pd, ForestOrgans data, int cx, int cz, long now, OrganTable t, boolean small) {
        long since = pd.overgrownSinceTick(new ChunkPos(cx, cz));
        if (since < 0L) return false;
        if (now - since < (long) ((small ? t.smallMinAgeDays : t.minAgeDays) * 24000.0D)) return false;
        int r = small ? t.smallMinDepthChunks : t.minDepthChunks;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!pd.isOvergrownKey(ChunkPos.asLong(cx + dx, cz + dz))) return false;
            }
        }
        for (ForestOrgans.Organ o : data.organs()) {
            int ox = (o.x >> 4) - cx, oz = (o.z >> 4) - cz;
            int need = !small && !o.small ? t.spacingChunks : t.smallSpacingChunks; // 大きい版どうしは遠く、小さい版は数チャンクおき
            if (ox * ox + oz * oz < need * need) return false;
        }
        return true;
    }
}
