package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.organ.OrganInfluence;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 「胞子濃度」— チャンクごとに決まる 0〜100(%)の値。過繁茂バイオーム
 * ({@link ModBiomes#OVERGROWN})にどれだけ近い/囲まれているかを表す。
 *
 * <h2>計算式</h2>
 * <ol>
 *     <li>そのチャンク自身が過繁茂バイオームなら、割合を一切計算せず必ず <b>100%</b>。</li>
 *     <li>そうでなければ以下の合計(上限 100%):
 *     <ul>
 *         <li><b>割合成分</b> — 半径 {@code sporeConcentrationRadiusChunks}(既定 3)チャンクの正方領域
 *         (未ロードのチャンクは分母・分子どちらからも除外)のうち過繁茂バイオームが占める割合 {@code r}。
 *         {@code r} が {@code sporeConcentrationFullRatio}(既定 0.8 = 8 割)に達した時点で 100% とし、
 *         それ以上は上がらない({@code min(1, r / fullRatio) * 100})。</li>
 *         <li><b>距離成分</b> — 最寄りの過繁茂バイオームチャンクとの「間に挟まるチャンク数」で決まる
 *         (チェビシェフ距離 - 1。斜め隣接も密接扱い)。割合に関係なく必ず加算される。
 *         密接(0)= {@code sporeConcentrationBiomeAdjacentPercent}(既定 50%)、
 *         1 チャンク離れ = {@code sporeConcentrationBiomeGap1Percent}(既定 25%)、
 *         2 チャンク離れ = {@code sporeConcentrationBiomeGap2Percent}(既定 5%)、それ以上 = 0。
 *         判定は割合成分と同じ半径 {@code sporeConcentrationRadiusChunks}(既定 3)の範囲内で行う。</li>
 *         <li><b>汚染ブロック成分</b> — {@code sporeConcentrationPerCorruptedBlockPercent} 参照。</li>
 *     </ul>
 *     </li>
 * </ol>
 *
 * <h2>負荷対策</h2>
 * <p>{@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker} から高頻度で呼ばれるため:
 * <ul>
 *     <li>結果は次元ごと・チャンクごとに {@code sporeConcentrationCacheTtlTicks}(既定 200)tick キャッシュ。
 *     バイオームが書き換わったとき({@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter})は
 *     {@link #invalidateAround} で周辺のキャッシュを捨てる。</li>
 *     <li>汚染ブロック成分の 256 列走査は {@code sporeConcentrationCorruptedScanStride}(既定 2)間隔で
 *     間引きサンプリングし、結果を全体へ外挿する。さらに「もう 100% に届くと確定した」時点で打ち切る。</li>
 * </ul>
 */
public final class SporeConcentration {

    /**
     * 計算結果の内訳。{@code percent} が最終的な胞子濃度(0〜100)。
     *
     * @param biomeGap          最寄りの過繁茂バイオームチャンクとの「間に挟まるチャンク数」。
     *                          0 = 密接(隣接/斜め隣接)、1 = 1 チャンク離れ、2 = 2 チャンク離れ、
     *                          -1 = 走査範囲内に過繁茂バイオームなし。
     * @param distanceComponent {@code biomeGap} から決まる加算値(%)。
     * @param corruptedColumns  汚染ブロックだった列数の推定値(間引きサンプリングからの外挿。0〜256)。
     * @param bonusComponent    感染加算成分(%)。プレイヤーがそのチャンクで胞子感染にかかるたびに積まれる
     *                          ({@link SporeChunkBonus}。胞子感染1 = +1%、胞子感染2 = +5% …)。チャンクと一緒にセーブされる。
     * @param deviceComponent   稼働中の胞子装置による変化量 %(除去装置は負、拡散装置は正。{@link SporeDeviceData#deltaAt})。
     *                          過繫茂バイオームのチャンク(必ず 100%)には効かない(常に 0)。
     * @param percentBeforeDevices 装置の影響を除いた胞子濃度(0〜100)。除去装置の「胞子」産出量の基準。
     * @param organLocalComponent  器官({@link com.yayuy.overgrownvegetation.organ.OrganInfluence})の
     *                             <b>まわりだけ</b>の上乗せ(%)。器官に近いほど大きい。
     * @param organFloorComponent  器官が世界に作っている<b>下限</b>(%)。次元のどこに居ても同じ値。
     *                             {@code percent} がこれを下回ることはない。
     *                             <b>効いているとは限らない</b> —— 元の濃度が既に上ならそのまま。
     */
    public record Result(double percent, boolean selfOvergrown, int biomeGap, double distanceComponent,
                         double ratioComponent, double corruptedComponent, int corruptedColumns,
                         double bonusComponent, double deviceComponent, double percentBeforeDevices,
                         double organLocalComponent, double organFloorComponent) {

        /** 装置の影響を {@code delta} だけ加えた結果(最終値は 0〜100 に丸める)。 */
        /** 寄生植物装備のプレイヤーぶん {@code delta} % を最終値に足した結果(0〜100 に丸める)。 */
        Result withHostBonus(double delta) {
            double clamped = Math.max(0.0D, Math.min(100.0D, percent + delta));
            return new Result(clamped, selfOvergrown, biomeGap, distanceComponent, ratioComponent,
                    corruptedComponent, corruptedColumns, bonusComponent, deviceComponent, percentBeforeDevices,
                    organLocalComponent, organFloorComponent);
        }

        Result withDevices(double delta) {
            if (delta == 0.0D) {
                return this;
            }
            double clamped = Math.max(0.0D, Math.min(100.0D, percentBeforeDevices + delta));
            return new Result(clamped, selfOvergrown, biomeGap, distanceComponent, ratioComponent,
                    corruptedComponent, corruptedColumns, bonusComponent, clamped - percentBeforeDevices,
                    percentBeforeDevices, organLocalComponent, organFloorComponent);
        }

        /**
         * 器官のぶんを足した結果。<b>上乗せは足し算・下限は下支え</b>。
         *
         * <p>下限を足し算にすると器官が増えるたびに世界中が 100% へ張り付き、
         * 濃度の差が意味を失う。「ここから下には行かない」だけにしておくと、
         * 拠点の手入れ(除去装置・汚染ブロックの除去)は<b>下限までは効く</b>ので無駄にならない。
         */
        Result withOrgans(double local, double floor) {
            if (local <= 0.0D && floor <= 0.0D) {
                return this;
            }
            double raised = Math.min(100.0D, percent + local);
            double clamped = Math.max(raised, Math.min(100.0D, floor));
            return new Result(clamped, selfOvergrown, biomeGap, distanceComponent, ratioComponent,
                    corruptedComponent, corruptedColumns, bonusComponent, deviceComponent, percentBeforeDevices,
                    local, floor);
        }
    }

    private record Cached(Result result, long computedGameTime) {
    }

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Cached>> CACHE = new HashMap<>();
    /** 1 次元あたりのキャッシュ上限。超えたら丸ごと捨てる(TTL 併用なので単純で十分)。 */
    private static final int MAX_CACHE_PER_DIM = 20_000;
    /** 1 列あたり、一番上のブロックから下方向へ何ブロックまで潜って地面を探すか(木の幹の下など)。 */
    private static final int GROUND_SCAN_DEPTH = 32;

    /** {@code chunk} の胞子濃度の内訳を返す(キャッシュ付き)。 */
    public static Result at(ServerLevel level, ChunkPos chunk) {
        return at(level, chunk.toLong(), chunk);
    }

    /**
     * {@link #at(ServerLevel, ChunkPos)} の本体。{@code chunk} はキャッシュミス時にだけ必要なので、
     * ブロック座標から呼ぶ側({@link #percentAt(ServerLevel, BlockPos)})は {@code null} を渡して
     * ヒット時の {@link ChunkPos} 生成を省く。
     */
    private static Result at(ServerLevel level, long key, @Nullable ChunkPos chunk) {
        Result base = atCached(level, key, chunk);
        // 「寄生植物装備」を着けたプレイヤーが隣接チャンク以内に居れば、その段階ぶん(+15% / +30%)を上乗せする
        // (キャッシュには入れない: プレイヤーは動くので)。
        double host = com.yayuy.overgrownvegetation.item.PlantArmor.concentrationBonus(level, ChunkPos.getX(key), ChunkPos.getZ(key));
        Result withHost = host > 0.0D ? base.withHostBonus(host) : base;
        // 器官(2026-09-23)。台帳が変わると即座に効き方が変わるので、こちらもキャッシュの外で足す。
        // 器官が 1 つも無い世界では両方とも 0 を返して終わる(ChunkPos も作らない)。
        return withHost.withOrgans(OrganInfluence.localPercentAt(level, key),
                OrganInfluence.worldFloorPercent(level));
    }

    private static Result atCached(ServerLevel level, long key, @Nullable ChunkPos chunk) {
        long ttl = OvergrownVegetationConfig.COMMON.sporeConcentrationCacheTtlTicks.get();
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        long now = level.getGameTime();

        Cached cached = perDim.get(key);
        if (cached != null) {
            long age = now - cached.computedGameTime();
            if (age >= 0 && age < ttl) {
                return cached.result();
            }
        }

        Result result = compute(level, chunk != null ? chunk : new ChunkPos(key));
        if (perDim.size() >= MAX_CACHE_PER_DIM) {
            perDim.clear();
        }
        perDim.put(key, new Cached(result, now));
        return result;
    }

    /** {@code pos} を含むチャンクの胞子濃度(0〜100)。 */
    public static double percentAt(ServerLevel level, BlockPos pos) {
        // 全 Mob から 20 tick ごとに呼ばれる。キャッシュヒット時は ChunkPos を作らない。
        return withCarrier(level, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** {@code chunk} の胞子濃度(0〜100)。 */
    public static double percentAt(ServerLevel level, ChunkPos chunk) {
        return withCarrier(level, chunk.toLong());
    }

    /**
     * チャンクの胞子濃度に、浸食度を持ち出しているプレイヤーぶんの上乗せを足す(2026-09-22)。
     *
     * <p>上乗せを<b>キャッシュの外で</b>足しているのは、濃度がチャンクごとにキャッシュされているから ——
     * 中へ混ぜると、プレイヤーが立ち去ったあとも上乗せが残る。
     */
    private static double withCarrier(ServerLevel level, long chunkKey) {
        double base = at(level, chunkKey, null).percent();
        double bonus = ErosionCarrier.concentrationBonusAt(level, chunkKey);
        return bonus <= 0.0D ? base : Math.min(100.0D, base + bonus);
    }

    /**
     * バイオームが書き換わったチャンクの周囲(半径 + 1 チャンク。距離成分・割合成分の両方に効く範囲)の
     * キャッシュを捨てる。{@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter} から呼ばれる。
     */
    public static void invalidateAround(ServerLevel level, ChunkPos chunk) {
        invalidateAround(level, chunk, OvergrownVegetationConfig.COMMON.sporeConcentrationRadiusChunks.get() + 1);
    }

    /**
     * {@code chunk} の周囲 {@code r} チャンク(チェビシェフ)のキャッシュを捨てる。胞子装置の設置/撤去/稼働切替は
     * 装置の効果半径({@link com.yayuy.overgrownvegetation.data.EffectTable.DeviceSpec#reachChunks})で呼ぶ。
     */
    public static void invalidateAround(ServerLevel level, ChunkPos chunk, int r) {
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.get(level.dimension());
        if (perDim == null || perDim.isEmpty()) {
            return;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                perDim.remove(ChunkPos.asLong(chunk.x + dx, chunk.z + dz));
            }
        }
    }

    /** 1 チャンクぶんのキャッシュだけ捨てる({@link SporeChunkBonus#add} から)。 */
    public static void invalidate(ServerLevel level, ChunkPos chunk) {
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.get(level.dimension());
        if (perDim != null) {
            perDim.remove(chunk.toLong());
        }
    }

    /**
     * 装置の影響を除いた基礎値を求め、稼働中の胞子装置({@link SporeDeviceData})の変化量を最後に足す。
     * 過繫茂バイオームのチャンク(必ず 100%)には装置は一切効かない — 装置で変わるのは過繫茂バイオーム以外のすべて
     * (ユーザー仕様 2026-09-10)。
     */
    private static Result compute(ServerLevel level, ChunkPos chunk) {
        Result base = computeBase(level, chunk);
        if (base.selfOvergrown()) {
            return base;
        }
        // 胞子装置(除去/拡散)と、生きているスポロフィルの傘は同じ扱いで足し合わせる。
        return base.withDevices(SporeDeviceData.deltaAt(level, chunk)
                + com.yayuy.overgrownvegetation.entity.SporophyllAura.concentrationBoostAt(level, chunk));
    }

    private static Result computeBase(ServerLevel level, ChunkPos chunk) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;

        if (overgrownState(level, chunk.x, chunk.z) == 1) {
            return new Result(100.0, true, 0, 0.0, 0.0, 0.0, 256, 0.0, 0.0, 100.0, 0.0, 0.0);
        }
        // 繁茂バイオーム(overgrown_vegetation:overgrowth 系)は「胞子濃度 100%」に達した時点で
        // 割り当てられるが、その後もチャンク自身の胞子濃度は下の通常計算で毎回求め直す
        // (固定 100% にはしない)。発生源の過繁茂バイオームが遠ざかる・汚染ブロックが減るなどで
        // 実際の濃度が下がれば、繁茂バイオームのチャンクでも胞子濃度は下がる。
        // なお overgrownState は過繁茂バイオームしか 1 を返さないので、繁茂バイオームは
        // 近隣チャンクの胞子発生源には元々数えられていない(繁茂 → 繁茂 の際限ない伝播は起きない)。

        int radius = cfg.sporeConcentrationRadiusChunks.get();
        int overgrown = 0;
        int total = 0;
        int minChebyshev = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int state = overgrownState(level, chunk.x + dx, chunk.z + dz, probe);
                if (state < 0) {
                    // 未ロード。分母にも入れない(「見えている範囲での割合」)。
                    continue;
                }
                total++;
                if (state == 1) {
                    overgrown++;
                    int cheby = Math.max(Math.abs(dx), Math.abs(dz));
                    if (cheby > 0 && cheby < minChebyshev) {
                        minChebyshev = cheby;
                    }
                }
            }
        }

        double ratioComponent = 0.0;
        if (total > 0) {
            double ratio = (double) overgrown / total;
            double fullRatio = cfg.sporeConcentrationFullRatio.get();
            double scaled = fullRatio <= 0.0 ? 1.0 : ratio / fullRatio;
            ratioComponent = Math.min(1.0, scaled) * 100.0;
        }

        // 最寄りの過繁茂バイオームチャンクとの距離(間に挟まるチャンク数)で決まる加算成分。
        int biomeGap = minChebyshev == Integer.MAX_VALUE ? -1 : minChebyshev - 1;
        double distanceComponent = switch (biomeGap) {
            case 0 -> cfg.sporeConcentrationBiomeAdjacentPercent.get();
            case 1 -> cfg.sporeConcentrationBiomeGap1Percent.get();
            case 2 -> cfg.sporeConcentrationBiomeGap2Percent.get();
            default -> 0.0;
        };

        // プレイヤーの感染で積まれたチャンク固有の加算(SporeChunkBonus)。
        double bonusComponent = SporeChunkBonus.get(level, chunk);

        double base = distanceComponent + ratioComponent + bonusComponent;
        double perCol = cfg.sporeConcentrationPerCorruptedBlockPercent.get();

        int corruptedColumns = 0;
        if (perCol > 0.0 && base < 100.0) {
            int stride = Mth.clamp(cfg.sporeConcentrationCorruptedScanStride.get(), 1, 8);
            // これだけの列が汚染なら base と合わせて 100% に届く、という打ち切り閾値。
            int maxUsefulColumns = (int) Math.ceil((100.0 - base) / perCol);
            corruptedColumns = countCorruptedSurface(level, chunk, stride, maxUsefulColumns);
        }
        double corruptedComponent = Math.min(Math.max(0.0, 100.0 - base), corruptedColumns * perCol);

        double percent = Math.min(100.0, base + corruptedComponent);
        return new Result(percent, false, biomeGap, distanceComponent, ratioComponent, corruptedComponent,
                corruptedColumns, bonusComponent, 0.0, percent, 0.0, 0.0);
    }

    /**
     * チャンクの列のうち「地表(またはその上に生えた植物)が汚染ブロック」な列の数(0〜256 に外挿)。
     *
     * <p>{@code stride} 間隔のグリッド上の列だけを実際に調べ、その割合を 256 列全体へ外挿する。
     * {@code maxUsefulColumns} 相当に達した時点で打ち切る(それ以上調べても最終結果は変わらない)。
     *
     * <p>各列は一番上の非空気ブロック({@link Heightmap.Types#WORLD_SURFACE})から下方向へ走査し、
     * 汚染ブロックが見つかればカウント。汚染されていない葉・原木・蔦・下草・水などは貫通して地面を
     * 探し続け、汚染されていないバニラの地面に突き当たったらノーカウント。呼び出し元でチャンクが
     * ロード済みなのは確認済み。
     */
    private static int countCorruptedSurface(ServerLevel level, ChunkPos chunkPos, int stride, int maxUsefulColumns) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minY = level.getMinBuildHeight();

        int perAxis = (16 + stride - 1) / stride;
        double scale = 256.0 / (perAxis * perAxis);

        int sampled = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x += stride) {
            for (int z = 0; z < 16; z += stride) {
                if (columnGroundIsCorrupted(level, chunk, chunkPos.getMinBlockX() + x, chunkPos.getMinBlockZ() + z,
                        x, z, minY, cursor)) {
                    sampled++;
                    if ((int) Math.round(sampled * scale) >= maxUsefulColumns) {
                        return maxUsefulColumns;
                    }
                }
            }
        }
        return Math.min(256, (int) Math.round(sampled * scale));
    }

    /** 1 列の地面(または上に生えた植物)が汚染ブロックか。 */
    private static boolean columnGroundIsCorrupted(ServerLevel level, LevelChunk chunk, int wx, int wz,
                                                  int localX, int localZ, int minY, BlockPos.MutableBlockPos cursor) {
        int startY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
        for (int dy = 0; dy < GROUND_SCAN_DEPTH && startY - dy > minY; dy++) {
            cursor.set(wx, startY - dy, wz);
            BlockState state = level.getBlockState(cursor);
            if (state.is(ModTags.CORRUPTED)) {
                return true;
            }
            if (state.isAir() || isTreePart(state) || !state.blocksMotion()) {
                // 汚染されていない葉・原木・蔦・下草・水などは貫通して地面を探し続ける。
                continue;
            }
            // 汚染されていない地面(バニラの土・石など)に突き当たった。
            return false;
        }
        return false;
    }

    /** 原木・葉(バニラ / この MOD の巨大樹系を含む)。地面探索の際に貫通する対象。 */
    private static boolean isTreePart(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        Block block = state.getBlock();
        return block == ModBlocks.OVERGROWN_LOG.get()
                || block == ModBlocks.GIANT_OVERGROWN_LOG.get()
                || block == ModBlocks.GIANT_OVERGROWN_CAMBIUM.get()
                || block == ModBlocks.OVERGROWN_LEAVES.get();
    }

    private static int overgrownState(ServerLevel level, int chunkX, int chunkZ) {
        return overgrownState(level, chunkX, chunkZ, new BlockPos.MutableBlockPos());
    }

    /** {@code 1}=過繁茂バイオーム(陸 or 海)、{@code 0}=それ以外、{@code -1}=チャンク未ロード(判定不能)。 */
    private static int overgrownState(ServerLevel level, int chunkX, int chunkZ, BlockPos.MutableBlockPos scratch) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return -1;
        }
        scratch.set((chunkX << 4) + 8, level.getSeaLevel(), (chunkZ << 4) + 8);
        return ModBiomes.isFullyOvergrown(level.getBiome(scratch)) ? 1 : 0;
    }

    private SporeConcentration() {
    }
}
