package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * <b>霧の層の地図</b>(第 3 段「霧」、10-30 夜。元は parked の {@code spore/MorningMist} の格子と {@code MorningMistView} の地図)。
 * カメラのまわり 512 ブロック四方(8 ブロックで 1 画素 = 64×64)に、霧の層の高さと過繁茂の地域の近さを持つ。地形シェーダー
 * ({@code ov_fog.glsl})が読み、{@link MistFog} がカメラの所を引く。
 *
 * <ul>
 *     <li><b>高さは梢から</b>(メモリ morning-mist-layer の 1): 16 ブロック格子の列の高さ地図 MOTION_BLOCKING(梢・地面・水面)。
 *     谷の層の底 = まわり 9×9 格子(144 ブロック四方)の低い方から {@code lowPercentile}。林冠の雲海の床 = まわり 3×3 格子の
 *     {@code canopyPercentile} と 9×9 の {@code highPercentile} の小さい方(谷の底の画素で丘の高さまで霧を張らないように)。</li>
 *     <li><b>カメラの位置で答えが変わらない</b>(罠 5): 列も格子も絶対の座標で覚え、地図の原点は 16 に丸める。重なる画素は前の地図と同じ値。</li>
 *     <li><b>入れ替えは 1 秒で混ぜる</b>: 新しく読めたチャンクの分で値が変わっても跳ねない。</li>
 *     <li>作るのはカメラのまわり(足元と 80・160・240 ブロックの輪)に過繁茂の地域があるときだけ。汚染の外の丘からでも作る(10-16 ユーザー)。
 *     作るのは裏のスレッド({@link ScanThread})。読むだけ。</li>
 * </ul>
 * 画素(RGBA): r = 谷の層の底 − 基準の高さ(ブロック)、g = 雲海の床 − 基準、b = 過繁茂の近さ × 255(読めない画素は 0)、
 * a = 胞子の霧の森の度合い × 255(林冠のある過繁茂の列の割合、10-31)。
 */
final class MistMap {

    static final int SIZE = 64;
    static final int TEXEL = 8;
    static final int GRID = 16;
    private static final int WINDOW = 4;
    /** 霧が過繁茂の地域からはみ出すのを探す窓(格子の数、±)。谷に沿って流れる分まで。 */
    private static final int PROX_WINDOW = 6;
    private static final int MIN_SAMPLES = 12;
    private static final int MISSING = Integer.MIN_VALUE;
    /** 作り直す: カメラが中心からこれだけ離れたら・この tick ごと。入れ替えにかける tick。 */
    private static final double RECENTER = 64.0;
    private static final long REBUILD_TICKS = 100L;
    private static final int BLEND_TICKS = 20;
    /** 覚えた列・格子を読み直す間隔(tick)。読めなかった所はすぐ。 */
    private static final long TTL = 6000L;
    private static final long TTL_MISSING = 100L;
    /** 梢を本物の地面から何ブロック上までで切るか(ふつうの密林の木は 20〜30)。仮決め。 */
    private static final int CANOPY_CAP = 36;
    /** 胞子の霧の森の列: 表面(梢)が本物の地面からこれだけ上(林冠がある)。森の度合いは 3×3 列の割合を FOREST_FROM〜FOREST_FULL で 0 → 1。仮決め。 */
    private static final int FOREST_CANOPY = 4;
    private static final double FOREST_FROM = 0.25;
    private static final double FOREST_FULL = 0.7;

    /** 作った地図(1 枚)。 */
    private static final class Map {
        final int originX;
        final int originZ;
        final float[] low = new float[SIZE * SIZE];
        final float[] sea = new float[SIZE * SIZE];
        final float[] prox = new float[SIZE * SIZE];
        final float[] forest = new float[SIZE * SIZE];
        final boolean[] valid = new boolean[SIZE * SIZE];

        Map(int originX, int originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }
    }

    /** 列と格子の覚え(裏のスレッドだけが触る。ワールドが替わったら取り替える)。 */
    private static final class Cache {
        /** 列: {表面の高さ(MISSING = 読めない), 過繁茂か 0/1, 読んだ時刻, 本物の地面}。 */
        final Long2ObjectOpenHashMap<long[]> columns = new Long2ObjectOpenHashMap<>();
        /** 格子: {谷の底(NaN = 分からない), 近さ, 雲海の床, 読んだ時刻}。 */
        final Long2ObjectOpenHashMap<double[]> cells = new Long2ObjectOpenHashMap<>();
    }

    private static Cache cache = new Cache();
    @Nullable
    private static CompletableFuture<Map> building;
    private static long builtAt = -1_000_000L;
    private static int failures;
    private static boolean broken;

    /** 見せている地図(描画のスレッド)。混ぜている間は from → target。 */
    @Nullable
    private static Map shown;
    @Nullable
    private static Map from;
    @Nullable
    private static Map target;
    private static int blend;
    private static boolean nearOvergrown;

    @Nullable
    private static DynamicTexture texture;
    private static boolean uploadPending;
    /** 今テクスチャに載っている地図の原点と基準の高さ。 */
    private static int texOriginX;
    private static int texOriginZ;
    private static float texBase;
    private static boolean texReady;

    private MistMap() {
    }

    static void clear() {
        building = null;
        shown = null;
        from = null;
        target = null;
        texReady = false;
        cache = new Cache();
        builtAt = -1_000_000L;
    }

    /** 地図があるか(シェーダーへ渡せるか)。 */
    static boolean ready() {
        return texReady && texture != null;
    }

    static int originX() {
        return texOriginX;
    }

    static int originZ() {
        return texOriginZ;
    }

    static float base() {
        return texBase;
    }

    static int textureId() {
        return texture == null ? -1 : texture.getId();
    }

    /** 毎 tick({@link MistFog} から)。{@code wanted} が偽なら作らない(朝でも昼の霞でもない・止めた)。 */
    static void tick(ClientLevel level, Vec3 eye, long ticks, boolean wanted, AtmosphereTable.FogParams p) {
        if (broken) {
            return;
        }
        if (ticks % 20 == 1 || shown == null && building == null) {
            nearOvergrown = probe(level, eye);
        }
        if (!wanted || !nearOvergrown) {
            building = null;
            shown = null;
            from = null;
            target = null;
            texReady = false;
            return;
        }
        if (building != null && building.isDone()) {
            Map done = building.getNow(null);
            building = null;
            if (done != null) {
                failures = 0;
                if (shown == null) {
                    shown = done;
                    uploadPending = true;
                } else {
                    from = shown;
                    target = done;
                    blend = 0;
                }
            }
        }
        if (target != null) {
            blend++;
            shown = mix(from, target, Math.min(1.0F, blend / (float) BLEND_TICKS));
            uploadPending = true;
            if (blend >= BLEND_TICKS) {
                shown = target;
                from = null;
                target = null;
            }
        }
        if (building == null && target == null) {
            Map ref = shown;
            boolean far = ref == null || Math.abs(eye.x - (ref.originX + SIZE * TEXEL / 2.0)) > RECENTER
                    || Math.abs(eye.z - (ref.originZ + SIZE * TEXEL / 2.0)) > RECENTER;
            if (far || ticks - builtAt >= REBUILD_TICKS) {
                builtAt = ticks;
                int ox = Math.floorDiv(Mth.floor(eye.x), GRID) * GRID - SIZE * TEXEL / 2;
                int oz = Math.floorDiv(Mth.floor(eye.z), GRID) * GRID - SIZE * TEXEL / 2;
                Cache c = cache;
                long now = level.getGameTime();
                building = CompletableFuture.supplyAsync(() -> build(level, c, ox, oz, now, p), ScanThread.EXECUTOR).exceptionally(t -> {
                    if (++failures >= 3) {
                        broken = true;
                        OvergrownVegetation.LOGGER.error("霧の層の地図を 3 回続けて作れなかったので、このセッションでは霧を出さない", t);
                    } else {
                        OvergrownVegetation.LOGGER.warn("霧の層の地図を作れなかった(次の回にもう一度): {}", t.toString());
                    }
                    return null;
                });
            }
        }
    }

    /** 足元と 80・160・240 ブロックの輪の 25 か所に過繁茂の地域があるか(parked の MorningMistView と同じ)。 */
    private static boolean probe(ClientLevel level, Vec3 eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 25; i++) {
            double angle = i * Math.PI / 4.0 + (i >= 16 ? Math.PI / 8.0 : 0.0);
            double r = i == 24 ? 0.0 : (i < 8 ? 80.0 : (i < 16 ? 160.0 : 240.0));
            pos.set(eye.x + Math.cos(angle) * r, eye.y, eye.z + Math.sin(angle) * r);
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null && ModBiomes.isOvergrownLike(level.getBiome(pos))) {
                return true;
            }
        }
        return false;
    }

    /** 前の地図から新しい地図へ t だけ寄せた写し(重なる画素だけ。原点のずれは格子の倍数)。 */
    private static Map mix(Map a, Map b, float t) {
        Map m = new Map(b.originX, b.originZ);
        int di = (b.originX - a.originX) / TEXEL;
        int dj = (b.originZ - a.originZ) / TEXEL;
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int k = j * SIZE + i;
                int ai = i + di;
                int aj = j + dj;
                m.valid[k] = b.valid[k];
                if (ai >= 0 && aj >= 0 && ai < SIZE && aj < SIZE && a.valid[aj * SIZE + ai] && b.valid[k]) {
                    int ak = aj * SIZE + ai;
                    m.low[k] = Mth.lerp(t, a.low[ak], b.low[k]);
                    m.sea[k] = Mth.lerp(t, a.sea[ak], b.sea[k]);
                    m.prox[k] = Mth.lerp(t, a.prox[ak], b.prox[k]);
                    m.forest[k] = Mth.lerp(t, a.forest[ak], b.forest[k]);
                } else {
                    m.low[k] = b.low[k];
                    m.sea[k] = b.sea[k];
                    m.prox[k] = b.valid[k] ? b.prox[k] * t : 0.0F;
                    m.forest[k] = b.valid[k] ? b.forest[k] * t : 0.0F;
                }
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ 作る(裏のスレッド)

    private static Map build(ClientLevel level, Cache c, int ox, int oz, long now, AtmosphereTable.FogParams p) {
        Map m = new Map(ox, oz);
        double[] out = new double[4];
        for (int index = 0; index < SIZE * SIZE; index++) {
            double x = ox + (index % SIZE) * TEXEL + TEXEL / 2.0;
            double z = oz + (index / SIZE) * TEXEL + TEXEL / 2.0;
            if (sample(level, c, x, z, now, p, out)) {
                m.low[index] = (float) out[0];
                m.prox[index] = (float) out[1];
                m.sea[index] = (float) out[2];
                m.forest[index] = (float) out[3];
                m.valid[index] = true;
            }
        }
        if (c.cells.size() > 50_000) {
            c.cells.clear();
        }
        if (c.columns.size() > 100_000) {
            c.columns.clear();
        }
        return m;
    }

    /** (x, z) の {谷の底, 近さ, 雲海の床} を格子の中心から 2 次補間する。分からなければ false。 */
    private static boolean sample(ClientLevel level, Cache c, double x, double z, long now, AtmosphereTable.FogParams p, double[] out) {
        double fx = x / GRID - 0.5;
        double fz = z / GRID - 0.5;
        int gx = Mth.floor(fx);
        int gz = Mth.floor(fz);
        double tx = fx - gx;
        double tz = fz - gz;
        double low = 0.0;
        double sea = 0.0;
        double forest = 0.0;
        double prox = 0.0;
        double weight = 0.0;
        for (int i = 0; i < 4; i++) {
            int dx = i & 1;
            int dz = i >> 1;
            double w = (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz);
            if (w <= 0.0) {
                continue;
            }
            double[] cell = cell(level, c, gx + dx, gz + dz, now, p);
            if (Double.isNaN(cell[0])) {
                continue;
            }
            low += w * cell[0];
            prox += w * cell[1];
            sea += w * cell[2];
            forest += w * cell[5];
            weight += w;
        }
        if (weight < 0.25) {
            return false;
        }
        out[0] = low / weight;
        out[1] = prox / weight * Math.min(1.0, weight * 1.5);
        out[2] = sea / weight;
        out[3] = forest / weight * Math.min(1.0, weight * 1.5);
        return true;
    }

    private static double[] cell(ClientLevel level, Cache c, int gx, int gz, long now, AtmosphereTable.FogParams p) {
        long key = ChunkPos.asLong(gx, gz);
        double[] cell = c.cells.get(key);
        if (cell != null && now >= (long) cell[3] && now - (long) cell[3] < (cell[4] > 0.5 ? TTL : TTL_MISSING)) {
            return cell;
        }
        int size = 2 * WINDOW + 1;
        int[] heights = new int[size * size];
        int[] near = new int[9];
        int[] grounds = new int[size * size];
        int n = 0;
        int nn = 0;
        int covered = 0;
        long[] own = column(level, c, gx, gz, now);
        for (int dz = -WINDOW; dz <= WINDOW; dz++) {
            for (int dx = -WINDOW; dx <= WINDOW; dx++) {
                long[] col = column(level, c, gx + dx, gz + dz, now);
                if (col[0] == MISSING) {
                    continue;
                }
                grounds[n] = (int) col[3];
                heights[n++] = (int) col[0];
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    near[nn++] = (int) col[0];
                    if (col[4] != 0L) {
                        covered++;
                    }
                }
            }
        }
        // 霧は低い方へ流れ落ち、谷に沿って流れる: この格子が森の列より低い(drop)か、まわりの地面より低い谷の底(valley)ほど遠くまで
        // (proxCells、谷の出口で途切れないように窓 {@link #PROX_WINDOW} まで)。平地や高い所は短く(proxCellsFlat)。
        double valley = 0.0;
        if (n >= MIN_SAMPLES && own[0] != MISSING) {
            valley = Mth.clamp((percentile(grounds, n, 0.5) - own[3] - 2.0) / 6.0, 0.0, 1.0);
        }
        double prox = 0.0;
        for (int dz = -PROX_WINDOW; dz <= PROX_WINDOW; dz++) {
            for (int dx = -PROX_WINDOW; dx <= PROX_WINDOW; dx++) {
                long[] col = column(level, c, gx + dx, gz + dz, now);
                if (col[0] == MISSING || col[1] == 0L) {
                    continue;
                }
                double drop = own[0] == MISSING ? 0.0 : Mth.clamp((col[3] - own[3]) / 8.0, 0.0, 1.0);
                double reach = Mth.lerp(Math.max(drop, valley), p.proxCellsFlat(), p.proxCells());
                double d = Math.sqrt(dx * dx + dz * dz);
                prox = Math.max(prox, reach <= 0.0 ? (d < 0.5 ? 1.0 : 0.0) : Mth.clamp(1.0 - (d - 0.5) / reach, 0.0, 1.0));
            }
        }
        double low = Double.NaN;
        double sea = Double.NaN;
        if (n >= MIN_SAMPLES) {
            low = percentile(heights, n, p.lowPercentile());
            double high = percentile(heights, n, p.highPercentile());
            double canopy = nn > 0 ? percentile(near, nn, p.canopyPercentile()) : high;
            sea = Math.max(low, Math.min(high, canopy));
        }
        // 胞子の霧の森の度合い: まわり 3×3 列のうち林冠のある過繁茂の列の割合。
        double forest = nn > 0 ? SunLight.smooth((float) Mth.clamp((covered / (double) nn - FOREST_FROM) / (FOREST_FULL - FOREST_FROM), 0.0, 1.0)) : 0.0;
        cell = new double[]{low, prox, sea, now, n == size * size ? 1.0 : 0.0, forest};
        c.cells.put(key, cell);
        return cell;
    }

    private static long[] column(ClientLevel level, Cache c, int gx, int gz, long now) {
        long key = ChunkPos.asLong(gx, gz);
        long[] col = c.columns.get(key);
        if (col != null && now >= col[2] && now - col[2] < (col[0] == MISSING ? TTL_MISSING : TTL)) {
            return col;
        }
        int x = gx * GRID + GRID / 2;
        int z = gz * GRID + GRID / 2;
        long surface = MISSING;
        long ground = MISSING;
        long overgrown = 0L;
        long covered = 0L;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
            ground = groundOf(level, x, z);
            surface = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), ground + CANOPY_CAP);
            if (ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, (int) surface, z)))) {
                overgrown = 1L;
                covered = surface - ground >= FOREST_CANOPY ? 1L : 0L;
            }
        }
        col = new long[]{surface, overgrown, now, ground, covered};
        c.columns.put(key, col);
        return col;
    }

    /**
     * (x, z) の列の本物の地面(木の一部でない固いブロック・水面の 1 つ上)。列の表面 = 高さ地図 MOTION_BLOCKING(梢・地面・水面)は、
     * この地面から {@link #CANOPY_CAP} までで切る:
     * 巨大樹の冠(高さ 200 を超える)が窓の多くを覆う森では、低い方から 4 割の所まで冠の高さになり、層が空に浮いた(10-30 夜の撮影)。
     */
    private static long groundOf(ClientLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bottom = Math.max(level.getMinBuildHeight(), top - 256);
        for (int y = top - 1; y >= bottom; y--) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.getFluidState().isEmpty() || state.blocksMotion() && !TreeCanopyGroundFinder.isTreePart(state)) {
                return y + 1;
            }
        }
        return top;
    }

    private static double percentile(int[] values, int n, double q) {
        Arrays.sort(values, 0, n);
        double at = q * (n - 1);
        int i = (int) Math.floor(at);
        int j = Math.min(n - 1, i + 1);
        return Mth.lerp(at - i, values[i], values[j]);
    }

    // ------------------------------------------------------------------ カメラの所・テクスチャ(描画のスレッド)

    /** カメラの所の {谷の底, 雲海の床, 近さ, 胞子の霧の森の度合い} を {@code out} へ(見せている地図から 2 次補間)。地図の外・分からなければ false。 */
    static boolean at(double x, double z, double[] out) {
        Map m = shown;
        if (m == null) {
            return false;
        }
        double fx = (x - m.originX) / TEXEL - 0.5;
        double fz = (z - m.originZ) / TEXEL - 0.5;
        int i0 = Mth.floor(fx);
        int j0 = Mth.floor(fz);
        if (i0 < 0 || j0 < 0 || i0 + 1 >= SIZE || j0 + 1 >= SIZE) {
            return false;
        }
        double tx = fx - i0;
        double tz = fz - j0;
        double low = 0.0;
        double sea = 0.0;
        double forest = 0.0;
        double prox = 0.0;
        double weight = 0.0;
        for (int q = 0; q < 4; q++) {
            int k = (j0 + (q >> 1)) * SIZE + i0 + (q & 1);
            double w = ((q & 1) == 0 ? 1.0 - tx : tx) * ((q >> 1) == 0 ? 1.0 - tz : tz);
            if (!m.valid[k] || w <= 0.0) {
                continue;
            }
            low += w * m.low[k];
            sea += w * m.sea[k];
            prox += w * m.prox[k];
            forest += w * m.forest[k];
            weight += w;
        }
        if (weight < 0.25) {
            return false;
        }
        out[0] = low / weight;
        out[1] = sea / weight;
        out[2] = prox / weight * Math.min(1.0, weight * 1.5);
        out[3] = forest / weight * Math.min(1.0, weight * 1.5);
        return true;
    }

    /** 地図をテクスチャへ載せる(AFTER_SKY、{@link MistFog#upload} から)。 */
    static void upload() {
        Map m = shown;
        if (m == null) {
            texReady = false;
            return;
        }
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            texture.setFilter(true, false);
            uploadPending = true;
        }
        if (!uploadPending && texReady) {
            return;
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        float base = Float.POSITIVE_INFINITY;
        double sum = 0.0;
        int count = 0;
        for (int k = 0; k < SIZE * SIZE; k++) {
            if (m.valid[k]) {
                base = Math.min(base, m.low[k]);
                sum += m.low[k];
                count++;
            }
        }
        if (count == 0) {
            texReady = false;
            return;
        }
        float mean = (float) (sum / count);
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int k = j * SIZE + i;
                float low = m.valid[k] ? m.low[k] : mean;
                float sea = m.valid[k] ? m.sea[k] : mean;
                int r = Mth.clamp(Math.round(low - base), 0, 255);
                int g = Mth.clamp(Math.round(sea - base), 0, 255);
                int b = m.valid[k] ? Mth.clamp(Math.round(m.prox[k] * 255.0F), 0, 255) : 0;
                // a = 胞子の霧の森の度合い(10-31。前は「読めたか」だったが、読めない画素は b = 0 なので重さは b だけで足りる)。
                int a = m.valid[k] ? Mth.clamp(Math.round(m.forest[k] * 255.0F), 0, 255) : 0;
                // NativeImage の setPixelRGBA は ABGR の並び。
                image.setPixelRGBA(i, j, a << 24 | b << 16 | g << 8 | r);
            }
        }
        texture.upload();
        texOriginX = m.originX;
        texOriginZ = m.originZ;
        texBase = base;
        texReady = true;
        uploadPending = false;
    }

    /** 開発用: 地図の様子。 */
    static String describe() {
        Map m = shown;
        if (m == null) {
            return "地図なし(森の近く " + nearOvergrown + ")";
        }
        int valid = 0;
        float lo = Float.POSITIVE_INFINITY;
        float hi = Float.NEGATIVE_INFINITY;
        float seaHi = Float.NEGATIVE_INFINITY;
        double prox = 0.0;
        for (int k = 0; k < SIZE * SIZE; k++) {
            if (m.valid[k]) {
                valid++;
                lo = Math.min(lo, m.low[k]);
                hi = Math.max(hi, m.low[k]);
                seaHi = Math.max(seaHi, m.sea[k]);
                prox += m.prox[k];
            }
        }
        // 見本の撮り場所探し: 過繁茂の外(近さ 0)でいちばん高い底の所。
        int best = -1;
        for (int k = 0; k < SIZE * SIZE; k++) {
            if (m.valid[k] && m.prox[k] <= 0.0F && (best < 0 || m.low[k] > m.low[best])) {
                best = k;
            }
        }
        String outside = best < 0 ? "外なし" : String.format("外の高所 %d %.0f %d", m.originX + (best % SIZE) * TEXEL + TEXEL / 2,
                m.low[best], m.originZ + (best / SIZE) * TEXEL + TEXEL / 2);
        return String.format("地図 %d/%d 底 %.0f〜%.0f 雲海の床〜%.0f 近さの平均 %.2f %s", valid, SIZE * SIZE, lo, hi, seaHi,
                valid == 0 ? 0.0 : prox / valid, outside);
    }
}
