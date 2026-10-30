package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「どこがどの環境({@link OvergrowthVariant})に汚染されるか」を決める、ワールドシードだけから
 * 決まる巨大な地域マップ。
 *
 * <p>バニラのバイオーム分布とは無関係な独自のマップなので、山だった場所が湿地に、平原だった場所が
 * 密林に、と元の景色とは無関係に塗り替わる。チャンクごとの乱数ではなく座標の決定的関数なので、
 * 同じ地域のチャンクは(いつ・どこから汚染が届いても)必ず同じ環境になり、繁茂 → 過繁茂の昇格でも
 * 環境が変わらない。
 *
 * <h2>アルゴリズム</h2>
 * <ol>
 *     <li>座標を低周波の 2D シンプレックスノイズで歪める(ドメインワープ)。
 *     地域の境界が直線・多角形にならず、入り江のようにうねる。</li>
 *     <li>歪めた座標で、1 辺 {@code overgrowthRegionSize} ブロックの格子にジッターした母点を置き、
 *     最も近い母点のセル(ボロノイ分割)を「地域」とする。</li>
 *     <li>地域 ID とシードのハッシュから、設定の重み
 *     ({@code overgrowthVariantWeightJungle / Swamp / FungalSwamp})で環境を抽選する。</li>
 * </ol>
 */
public final class OvergrowthRegionMap {

    /** シードごとのワープ用ノイズ(ワールドごとに 1 個)。 */
    private static final Map<Long, SimplexNoise> WARP_NOISE = new ConcurrentHashMap<>();

    /** チャンク単位の結果キャッシュ(シードごと)。汚染 1 ブロックごとに引かれるので、ノイズ計算を省く。 */
    private static final Map<Long, Long2ObjectOpenHashMap<OvergrowthVariant>> CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final int CHUNK_CACHE_LIMIT = 1 << 16;

    /**
     * チャンク中心の環境。バイオーム書き換え・汚染ブロックの変換・苗木・浸水処理はすべてこれを使うので、
     * 1 チャンク内の判定は必ず一致する。
     */
    public static OvergrowthVariant variantAt(ServerLevel level, ChunkPos chunkPos) {
        return variantAtChunk(level.getSeed(), chunkPos.x, chunkPos.z);
    }

    /**
     * ブロック座標 {@code (blockX, blockZ)} を含む<b>チャンクの中心</b>で判定した環境
     * ({@link #variantAt(ServerLevel, ChunkPos)} と同じ結果。キャッシュあり)。
     */
    public static OvergrowthVariant variantAt(ServerLevel level, int blockX, int blockZ) {
        return variantAtChunk(level.getSeed(), blockX >> 4, blockZ >> 4);
    }

    /** 環境が湿地系({@link OvergrowthVariant#isSwamp()})の場所か。 */
    public static boolean isSwampAt(ServerLevel level, int blockX, int blockZ) {
        return variantAt(level, blockX, blockZ).isSwamp();
    }

    private static OvergrowthVariant variantAtChunk(long seed, int cx, int cz) {
        Long2ObjectOpenHashMap<OvergrowthVariant> cache = CHUNK_CACHE.computeIfAbsent(seed, s -> new Long2ObjectOpenHashMap<>());
        long key = ChunkPos.asLong(cx, cz);
        OvergrowthVariant cached;
        synchronized (cache) {
            cached = cache.get(key);
        }
        if (cached != null) {
            return cached;
        }
        OvergrowthVariant v = variantAt(seed, (cx << 4) + 8, (cz << 4) + 8);
        synchronized (cache) {
            if (cache.size() >= CHUNK_CACHE_LIMIT) {
                cache.clear();
            }
            cache.put(key, v);
        }
        return v;
    }

    /** 設定(地域サイズ・重み)を変えたときにキャッシュを捨てる。 */
    public static void invalidateCache() {
        CHUNK_CACHE.clear();
        COLUMN_CACHE_GENERATION++;
    }

    /** {@link OvergrowthVariant#values()}(列キャッシュが ordinal → 環境を引くため)。 */
    private static final OvergrowthVariant[] VARIANTS = OvergrowthVariant.values();

    /** {@link #invalidateCache()} のたびに増え、各スレッドの列キャッシュが自分の世代と比べて捨てる。 */
    private static volatile int COLUMN_CACHE_GENERATION;

    /**
     * ブロック列 {@code (x, z)} ごとの {@link #blendedVariantAt} の「salt に依らない部分」
     * (最寄りの環境・2 番目に近い環境・隣の環境を返す確率)のダイレクトマップ式キャッシュ。
     *
     * <p>汚染 1 ブロックにつき同じ列で salt 違い(泥化 2 / 茂み 5 / 下草 3 / 木 4 / 洞窟 6)の判定が
     * 4〜5 回走り、そのたびにシンプレックスノイズ 4 回 + ボロノイ 9 セルを計算し直していたのを、
     * 列ごとに 1 回にする。salt で変わるのは最後のハッシュ 1 回だけなので結果は同じ。
     * スレッドごとに 1 つ持つ(競合しないので同期不要)。設定値が変わったら丸ごと捨てる。
     */
    private static final class ColumnCache {
        static final int SIZE = 1 << 14;
        static final int MASK = SIZE - 1;

        final long[] keys = new long[SIZE];
        final long[] seeds = new long[SIZE];
        final byte[] first = new byte[SIZE];
        final byte[] second = new byte[SIZE];
        final double[] otherChance = new double[SIZE];
        final boolean[] valid = new boolean[SIZE];

        int generation = -1;
        int regionSize;
        double warp;
        double blend;
        double weightJungle;
        double weightSwamp;
        double weightFungal;

        /** 今の設定値・世代で作った内容か。違えば捨てて作り直す。 */
        void ensureFresh(OvergrownVegetationConfig.Common cfg, double blend) {
            int regionSize = cfg.overgrowthRegionSize.get();
            double warp = cfg.overgrowthRegionWarp.get();
            double weightJungle = cfg.overgrowthVariantWeightJungle.get();
            double weightSwamp = cfg.overgrowthVariantWeightSwamp.get();
            double weightFungal = cfg.overgrowthVariantWeightFungalSwamp.get();
            int generation = COLUMN_CACHE_GENERATION;
            if (this.generation == generation && this.regionSize == regionSize && this.warp == warp
                    && this.blend == blend && this.weightJungle == weightJungle
                    && this.weightSwamp == weightSwamp && this.weightFungal == weightFungal) {
                return;
            }
            Arrays.fill(valid, false);
            this.generation = generation;
            this.regionSize = regionSize;
            this.warp = warp;
            this.blend = blend;
            this.weightJungle = weightJungle;
            this.weightSwamp = weightSwamp;
            this.weightFungal = weightFungal;
        }
    }

    private static final ThreadLocal<ColumnCache> COLUMN_CACHE = ThreadLocal.withInitial(ColumnCache::new);


    // ------------------------------------------------------------------------------------------------
    // ワールドスポーン周りの密林化(2026-09-22)
    // ------------------------------------------------------------------------------------------------

    /** ワールドスポーンの水平座標。まだ分かっていなければ {@link #spawnKnown} が false。 */
    private static volatile int spawnX;
    private static volatile int spawnZ;
    private static volatile boolean spawnKnown;

    /**
     * ワールドスポーンの位置を覚える({@code ClimaxTicker} と同じくオーバーワールドのみ)。
     * 地域マップは<b>シードと座標だけの決定的な関数</b>なので、スポーンを混ぜるとキャッシュが
     * 食い違う。位置が変わったらキャッシュを捨てる。
     *
     * <p>ワールド生成より前に呼ばれる想定(サーバー起動直後)。呼ばれるまでは従来どおり
     * 地域マップだけで環境が決まる。
     */
    public static void rememberSpawn(ServerLevel overworld) {
        BlockPos spawn = overworld.getSharedSpawnPos();
        if (spawnKnown && spawnX == spawn.getX() && spawnZ == spawn.getZ()) {
            return;
        }
        spawnX = spawn.getX();
        spawnZ = spawn.getZ();
        spawnKnown = true;
        invalidateCache();
    }

    /**
     * {@code (blockX, blockZ)} がワールドスポーン周りの「必ず密林」の範囲にあるか。
     *
     * <p>最初の拠点のまわりが湿地や菌湿地帯になると、汚染が来たときに地面が水没して足場が消える。
     * そこだけは必ず密林にしておく({@code spawnJungleRadius}、0 で無効)。
     *
     * <p><b>真円にはしない。</b> 角度に対して 2 つの正弦波を重ねて半径を ±20% ほど歪ませてあるので、
     * 円形に切り替わっては見えない。位相はシードから作るのでワールドごとに形が変わり、
     * 同じワールドでは常に同じ形になる(キャッシュしてよい)。
     */
    private static boolean forcedJungle(long seed, int blockX, int blockZ) {
        if (!spawnKnown) {
            return false;
        }
        double radius = OvergrownVegetationConfig.COMMON.spawnJungleRadius.get();
        if (radius <= 0.0D) {
            return false;
        }
        double dx = blockX - (double) spawnX;
        double dz = blockZ - (double) spawnZ;
        double distSq = dx * dx + dz * dz;
        // 歪みは ±20% までなので、1.2 倍より外は計算するまでもない。
        double outer = radius * 1.2D;
        if (distSq > outer * outer) {
            return false;
        }
        double inner = radius * 0.8D;
        if (distSq < inner * inner) {
            return true;
        }
        double angle = Math.atan2(dz, dx);
        double phaseA = (seed & 0xFFFFL) / 65536.0D * Math.PI * 2.0D;
        double phaseB = ((seed >>> 16) & 0xFFFFL) / 65536.0D * Math.PI * 2.0D;
        double wobble = Math.sin(angle * 3.0D + phaseA) * 0.13D + Math.sin(angle * 5.0D + phaseB) * 0.07D;
        double limit = radius * (1.0D + wobble);
        return distSq <= limit * limit;
    }
    /** 結晶地帯のくじの塩。環境のくじ({@link #pickVariant} の 7)と同じ乱数を使うと、結晶地帯が環境の偏りに引きずられる。 */
    private static final int CRYSTAL_SALT = 11;

    /**
     * そのチャンクが<b>結晶地帯</b>の地域か(順 1、2026-10-03 ユーザー指定「地域ごとの 4 つ目の環境」)。
     * {@link #variantAt(ServerLevel, ChunkPos)} と同じくチャンクの中心で判定する。
     *
     * <h2>環境のくじとは別に引く</h2>
     * <p>結晶地帯を {@link #pickVariant} の 4 つ目の重みにすると、重みの合計が変わって<b>すべての地域のくじがずれ</b>、
     * 既存のワールドの密林・湿地・菌湿地が入れ替わる(過繁茂済みの所と、これから過繁茂になる所で環境が食い違う)。
     * だから環境はそのままに、<b>密林と決まった地域の中から</b>別の乱数で {@code climax.json} の
     * {@code crystal.regionChance} の割合を選ぶ。湿地・菌湿地は対象外(浸水・菌の柱とぶつかるため。Claude 仮決め)。
     * ワールドのスポーン周りの「必ず密林」の円も対象外(始まりの場所は今までどおりの密林にする)。
     *
     * <p>結晶地帯になるのは<b>極相になったときだけ</b>({@code OvergrownBiomeConverter#targetKey})。
     * 繁茂・過繁茂のうちは普通の密林のまま。
     */
    public static boolean isCrystalRegion(ServerLevel level, ChunkPos chunkPos) {
        return isCrystalRegion(level.getSeed(), (chunkPos.x << 4) + 8, (chunkPos.z << 4) + 8);
    }

    /** {@link #isCrystalRegion(ServerLevel, ChunkPos)} のシードと座標だけの版(決定的)。 */
    public static boolean isCrystalRegion(long seed, int blockX, int blockZ) {
        if (SealedBiomes.swampAndCrystalSealed()) {
            return false; // 結晶地帯は 0.2.0 まで封印(2026-10-23 ユーザー決定)
        }
        double chance = 0.0D; // 結晶地帯は極相と一緒に parked/ へ退避(2026-10-30)。戻すときは climax.json の crystal.regionChance
        if (chance <= 0.0D || forcedJungle(seed, blockX, blockZ)) {
            return false;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double[] warped = warp(seed, blockX, blockZ, cfg);
        double regionSize = warped[2];
        double[] cells = nearestCells(seed, warped[0] / regionSize, warped[1] / regionSize);
        // セル ID は variantAt と同じ取り出し方にする(double を経由するので、同じ式でないと別の ID になる)。
        long cellId = (long) cells[0];
        if (pickVariant(seed, cellId, cfg) != OvergrowthVariant.JUNGLE) {
            return false;
        }
        return unit(hash(seed, (int) (cellId >>> 32), (int) cellId, CRYSTAL_SALT)) < chance;
    }

    /** ワールドシードと座標だけから環境を決める(決定的)。 */
    public static OvergrowthVariant variantAt(long seed, int blockX, int blockZ) {
        if (forcedJungle(seed, blockX, blockZ)) {
            return OvergrowthVariant.JUNGLE;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double[] warped = warp(seed, blockX, blockZ, cfg);
        double regionSize = warped[2];
        double[] cells = nearestCells(seed, warped[0] / regionSize, warped[1] / regionSize);
        return pickVariant(seed, (long) cells[0], cfg);
    }

    /**
     * 地域の境目をぼかした、ブロック単位の環境。境界から {@code overgrowthRegionBlend} ブロック以内の
     * 「移行帯」では、境界に近いほど高い確率(境界上で 50%)で隣の地域の環境を返す。座標と {@code salt} の
     * ハッシュで決まるので同じ場所・同じ用途では常に同じ答え。
     *
     * <p>木(苗木)・泥への変換・下草・茂みなど<b>ブロック単位</b>の判定に使う。バイオームの書き換えや
     * 浸水処理などチャンク単位の判定は {@link #variantAt(ServerLevel, ChunkPos)}(ぼかし無し)のまま。
     * 密林と湿地の境目が一直線に切り替わらず、木や地面が徐々に混ざり合うようにするため(2026-09-11)。
     */
    public static OvergrowthVariant blendedVariantAt(ServerLevel level, int blockX, int blockZ, int salt) {
        return blendedVariantAt(level.getSeed(), blockX, blockZ, salt);
    }

    public static OvergrowthVariant blendedVariantAt(long seed, int blockX, int blockZ, int salt) {
        if (forcedJungle(seed, blockX, blockZ)) {
            return OvergrowthVariant.JUNGLE;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double blend = cfg.overgrowthRegionBlend.get();

        // salt に依らない部分(ノイズ + ボロノイ + 環境の抽選)は列ごとにキャッシュする(ColumnCache 参照)。
        ColumnCache cache = COLUMN_CACHE.get();
        cache.ensureFresh(cfg, blend);
        long key = ChunkPos.asLong(blockX, blockZ);
        int slot = (int) HashCommon.mix(key ^ seed) & ColumnCache.MASK;
        OvergrowthVariant first;
        OvergrowthVariant second;
        double otherChance;
        if (cache.valid[slot] && cache.keys[slot] == key && cache.seeds[slot] == seed) {
            first = VARIANTS[cache.first[slot]];
            second = VARIANTS[cache.second[slot]];
            otherChance = cache.otherChance[slot];
        } else {
            double[] warped = warp(seed, blockX, blockZ, cfg);
            double regionSize = warped[2];
            double[] cells = nearestCells(seed, warped[0] / regionSize, warped[1] / regionSize);
            first = pickVariant(seed, (long) cells[0], cfg);
            second = pickVariant(seed, (long) cells[1], cfg);
            if (blend > 0.0D && first != second) {
                // 最寄り母点と 2 番目の母点の垂直二等分線(= 地域の境界)までの距離(ブロック)。
                double d1sq = cells[2];
                double d2sq = cells[3];
                double siteDist = Math.max(1.0e-6D, cells[4]);
                double toBorder = (d2sq - d1sq) / (2.0D * siteDist) * regionSize;
                double inside = Mth.clamp(toBorder / blend, 0.0D, 1.0D); // 0 = 境界上、1 = 移行帯の外
                otherChance = 0.5D * (1.0D - inside);
            } else {
                otherChance = 0.0D; // 使わない(下で first を返す)
            }
            cache.keys[slot] = key;
            cache.seeds[slot] = seed;
            cache.first[slot] = (byte) first.ordinal();
            cache.second[slot] = (byte) second.ordinal();
            cache.otherChance[slot] = otherChance;
            cache.valid[slot] = true;
        }

        if (blend <= 0.0D || first == second) {
            return first;
        }
        return unit(hash(seed, blockX, blockZ, 100 + salt)) < otherChance ? second : first;
    }

    /** 湿地系かどうかのブロック単位・ぼかしあり版。 */
    public static boolean isSwampAtBlended(ServerLevel level, int blockX, int blockZ, int salt) {
        return blendedVariantAt(level, blockX, blockZ, salt).isSwamp();
    }

    /** ドメインワープ後の座標 {x, z} と regionSize を返す。 */
    private static double[] warp(long seed, int blockX, int blockZ, OvergrownVegetationConfig.Common cfg) {
        double regionSize = Math.max(64, cfg.overgrowthRegionSize.get());
        double warp = cfg.overgrowthRegionWarp.get() * regionSize;

        double wx = blockX;
        double wz = blockZ;
        if (warp > 1.0e-6D) {
            SimplexNoise noise = WARP_NOISE.computeIfAbsent(seed, s -> new SimplexNoise(RandomSource.create(s ^ 0x5EEDC0DEL)));
            double f = 1.7D / regionSize;
            // 2 オクターブ。粗い歪みで境界全体をうねらせ、細かい歪みで縁をざらつかせる。
            double nx = noise.getValue(blockX * f, blockZ * f) + 0.35D * noise.getValue(blockX * f * 3.1D + 91.0D, blockZ * f * 3.1D - 47.0D);
            double nz = noise.getValue(blockX * f + 173.0D, blockZ * f + 59.0D) + 0.35D * noise.getValue(blockX * f * 3.1D - 233.0D, blockZ * f * 3.1D + 127.0D);
            wx += nx * warp;
            wz += nz * warp;
        }
        return new double[]{wx, wz, regionSize};
    }

    /**
     * 単位格子座標 {@code (u, v)} に最も近いジッター母点と 2 番目に近い母点。
     * 戻り値: {最寄りセル ID, 2 番目のセル ID, 最寄り距離², 2 番目の距離², 2 母点間の距離}(セル単位)。
     */
    private static double[] nearestCells(long seed, double u, double v) {
        int cu = Mth.floor(u);
        int cv = Mth.floor(v);
        long best = 0L;
        long second = 0L;
        double bestDist = Double.MAX_VALUE;
        double secondDist = Double.MAX_VALUE;
        double bx = 0.0D;
        double bz = 0.0D;
        double sx = 0.0D;
        double sz = 0.0D;
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                int gu = cu + du;
                int gv = cv + dv;
                long h = hash(seed, gu, gv, 0);
                double px = gu + unit(h);
                double pz = gv + unit(h >>> 24);
                double dx = px - u;
                double dz = pz - v;
                double dist = dx * dx + dz * dz;
                long id = (((long) gu) << 32) ^ (gv & 0xFFFFFFFFL);
                if (dist < bestDist) {
                    secondDist = bestDist;
                    second = best;
                    sx = bx;
                    sz = bz;
                    bestDist = dist;
                    best = id;
                    bx = px;
                    bz = pz;
                } else if (dist < secondDist) {
                    secondDist = dist;
                    second = id;
                    sx = px;
                    sz = pz;
                }
            }
        }
        double siteDist = Math.sqrt((sx - bx) * (sx - bx) + (sz - bz) * (sz - bz));
        return new double[]{(double) best, (double) second, bestDist, secondDist, siteDist};
    }

    private static OvergrowthVariant pickVariant(long seed, long cellId, OvergrownVegetationConfig.Common cfg) {
        if (SealedBiomes.swampAndCrystalSealed()) {
            return OvergrowthVariant.JUNGLE; // 湿地・菌湿地は 0.2.0 まで封印(2026-10-23 ユーザー決定)
        }
        double jungle = Math.max(0.0D, cfg.overgrowthVariantWeightJungle.get());
        double swamp = Math.max(0.0D, cfg.overgrowthVariantWeightSwamp.get());
        double fungal = Math.max(0.0D, cfg.overgrowthVariantWeightFungalSwamp.get());
        double total = jungle + swamp + fungal;
        if (total <= 0.0D) {
            return OvergrowthVariant.JUNGLE;
        }
        double roll = unit(hash(seed, (int) (cellId >>> 32), (int) cellId, 7)) * total;
        if (roll < jungle) {
            return OvergrowthVariant.JUNGLE;
        }
        if (roll < jungle + swamp) {
            return OvergrowthVariant.SWAMP;
        }
        return OvergrowthVariant.FUNGAL_SWAMP;
    }

    /** 64bit ミックスハッシュ。 */
    private static long hash(long seed, int a, int b, int salt) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h ^= (long) a * 0xBF58476D1CE4E5B9L;
        h = Long.rotateLeft(h, 23) * 0x94D049BB133111EBL;
        h ^= (long) b * 0xD6E8FEB86659FD93L;
        h = Long.rotateLeft(h, 31) ^ (h >>> 29);
        h += salt * 0x632BE59BD9B4E019L;
        h ^= h >>> 32;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h;
    }

    /** ハッシュの下位 24bit を [0, 1) に。 */
    private static double unit(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }

    private OvergrowthRegionMap() {
    }
}
