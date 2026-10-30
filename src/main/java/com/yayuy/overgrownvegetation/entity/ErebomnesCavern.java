package com.yayuy.overgrownvegetation.entity;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;

/**
 * {@link ErebomnesEntity エレボムネス}が棲める「<b>大きな空洞</b>」かを測る(2026-10-01)。湧きの関門
 * ({@link ErebomnesEntity#acceptOvergrownSpawn})が使う。ブロックの読み方だけに頼る純粋な関数にしてあるので、
 * GameTest は仮想の {@link BlockGetter}(大広間・細い坑道・峡谷)を渡して直接確かめられる。
 *
 * <p>採用の案(ユーザー改変): 「巨大な洞窟の中だけに棲み、空中をゆっくり泳ぐ」。<b>何を「巨大」とみなすかの数値はすべて仮決め</b>:
 * <ol>
 *     <li><b>床と天井</b>: 始点の列を下へ {@value #FLOOR_SCAN} マスまで床を探し、床から上へ {@value #CEILING_SCAN} マスまで天井を探す。
 *     床から天井まで {@link #MIN_HEIGHT} × 体格(体格 2 で 8 ブロック)以上。天井が見つからない列は高さ {@value #CEILING_SCAN} とみなす
 *     (空に開いているかは呼ぶ側が空の光で見る)。</li>
 *     <li><b>置く高さ</b>: 空洞の高さの {@link #HOVER_FRACTION} の所。ただし足元が床から {@link #HOVER_MIN} × 体格(3 ブロック)以上、
 *     体の上端と天井の間が {@link #CEILING_MARGIN} × 体格(1.5 ブロック)以上。</li>
 *     <li><b>横の広がり</b>: 体の真ん中の高さで 4 本の軸(東西・南北・斜め 2 本)の差し渡しを測る(片側 {@link #SPAN_SCAN} × 体格まで)。
 *     いちばん狭い軸で {@link #MIN_SPAN_NARROW} × 体格(10)以上、いちばん広い軸で {@link #MIN_SPAN_WIDE} × 体格(16)以上。
 *     全長 8.6 の体が向きを変えられて、峡谷のような細長い溝は外れる。</li>
 *     <li><b>空気の量</b>: 置く点からつながった空気のマスを数える(横は ±{@link #SPAN_SCAN} × 体格、縦は床から天井まで)。
 *     {@link #MIN_VOLUME} × 体格³(1200)マスに届けば大きな空洞。坑道の交差や柱の林を外す。届いたらそこで数えるのをやめる。</li>
 * </ol>
 * 水・溶岩の入ったマスは「空いていない」(空中を泳ぐ種なので水中へは出ない)。当たり判定の無い植物(蔦・苔・下草)は空いている扱い。
 *
 * <p>重さ: 湧きの 1 回ごとに呼ぶだけ(毎 tick ではない)。いちばん重い空気の数えでも 1200 マス × 6 方向の読み取りで打ち切る。
 * 世界から測るときは {@link #loadedOnly} で包む —— 読み込まれていないチャンクは「壁」として読み、生成を待たない。
 */
public final class ErebomnesCavern {

    // ------------------------------------------------------------------ 数値(体格 1 のブロック。使うときは体格を掛ける。すべて仮決め)

    /** 床から天井までの高さの下限(体格 2 で 8 ブロック)。 */
    public static final double MIN_HEIGHT = 4.0D;
    /** 置く高さ: 足元が床からこれ以上(体格 2 で 3 ブロック)。 */
    public static final double HOVER_MIN = 1.5D;
    /** 置く高さ: 空洞の高さのこの割合の所を目安に。 */
    public static final double HOVER_FRACTION = 0.4D;
    /** 体の上端と天井の間の空き(体格 2 で 1.5 ブロック)。 */
    public static final double CEILING_MARGIN = 0.75D;
    /** 横の広がりを測る片側の長さ(体格 2 で 16 ブロック)。空気を数える箱の横の半幅も同じ。 */
    public static final double SPAN_SCAN = 8.0D;
    /** いちばん狭い軸の差し渡しの下限(体格 2 で 10)。 */
    public static final double MIN_SPAN_NARROW = 5.0D;
    /** いちばん広い軸の差し渡しの下限(体格 2 で 16)。 */
    public static final double MIN_SPAN_WIDE = 8.0D;
    /** つながった空気のマス数の下限(× 体格³。体格 2 で 1200)。 */
    public static final double MIN_VOLUME = 150.0D;
    /** 床を探して下へ読む段数。 */
    public static final int FLOOR_SCAN = 16;
    /** 天井を探して上へ読む段数。 */
    public static final int CEILING_SCAN = 32;

    private static final double SQRT2 = Math.sqrt(2.0D);
    /** 差し渡しを測る 4 本の軸。 */
    private static final int[][] AXES = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    private ErebomnesCavern() {
    }

    /**
     * 測った結果。{@code ok} が false なら {@code reason} に理由(日本語。GameTest の失敗文に使う)。
     *
     * @param floorY     床のすぐ上のマス(足を置ける高さ)。見つからなければ {@link Integer#MIN_VALUE}
     * @param ceilingY   天井のブロックの高さ(見つからなければ床 + {@value #CEILING_SCAN})
     * @param placeY     置く高さ(足元の y)。ok のときだけ意味がある
     * @param narrowSpan いちばん狭い軸の差し渡し(ブロック)
     * @param wideSpan   いちばん広い軸の差し渡し
     * @param volume     数えたつながった空気のマス(下限に届いたら打ち切るので、それ以上はありうる)
     */
    public record Result(boolean ok, String reason, int floorY, int ceilingY, double placeY,
                         double narrowSpan, double wideSpan, int volume) {
        static Result fail(String reason, int floorY, int ceilingY, double narrow, double wide, int volume) {
            return new Result(false, reason, floorY, ceilingY, Double.NaN, narrow, wide, volume);
        }
    }

    /** そのマスを体が通れるか(空気、または当たり判定が無く液体でもないもの)。 */
    public static boolean isOpen(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * {@code start} の列が大きな空洞かを測る。
     *
     * @param start      始点(空いているマス。湧かせ役が選んだ床の上の足元など)
     * @param scale      体格(SCALE。体格 2 がふつう)
     * @param bodyHeight 体の当たり判定の高さ(体格込み。体格 2 で 2.1)
     */
    public static Result measure(BlockGetter level, BlockPos start, double scale, double bodyHeight) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int x = start.getX();
        int z = start.getZ();
        int y = start.getY();
        if (!isOpen(level, m.set(x, y, z))) {
            return Result.fail("始点が埋まっている", Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0, 0);
        }
        // 1. 床
        int floorY = Integer.MIN_VALUE;
        for (int d = 0; d <= FLOOR_SCAN; d++) {
            if (!isOpen(level, m.set(x, y - d - 1, z))) {
                floorY = y - d;
                break;
            }
        }
        if (floorY == Integer.MIN_VALUE) {
            return Result.fail("床が " + FLOOR_SCAN + " マス以内に無い", floorY, Integer.MIN_VALUE, 0, 0, 0);
        }
        // 2. 天井
        int ceilingY = floorY + CEILING_SCAN;
        for (int yy = floorY; yy < floorY + CEILING_SCAN; yy++) {
            if (!isOpen(level, m.set(x, yy, z))) {
                ceilingY = yy;
                break;
            }
        }
        int height = ceilingY - floorY;
        if (height < MIN_HEIGHT * scale) {
            return Result.fail("天井が低い(床から " + height + " マス)", floorY, ceilingY, 0, 0, 0);
        }
        // 3. 置く高さ
        double lo = floorY + HOVER_MIN * scale;
        double hi = ceilingY - bodyHeight - CEILING_MARGIN * scale;
        if (hi < lo) {
            return Result.fail("体と床・天井の空きが取れない", floorY, ceilingY, 0, 0, 0);
        }
        double placeY = Mth.clamp(floorY + height * HOVER_FRACTION, lo, hi);
        // 4. 横の広がり(体の真ん中の高さ)
        int midY = Mth.floor(placeY + bodyHeight * 0.5D);
        int scan = Mth.ceil(SPAN_SCAN * scale);
        double narrow = Double.MAX_VALUE;
        double wide = 0.0D;
        for (int[] axis : AXES) {
            double step = axis[0] != 0 && axis[1] != 0 ? SQRT2 : 1.0D;
            int a = reach(level, m, x, midY, z, axis[0], axis[1], scan);
            int b = reach(level, m, x, midY, z, -axis[0], -axis[1], scan);
            double span = (a + b + 1) * step;
            narrow = Math.min(narrow, span);
            wide = Math.max(wide, span);
        }
        if (narrow < MIN_SPAN_NARROW * scale) {
            return Result.fail("狭い(いちばん狭い差し渡し " + fmt(narrow) + ")", floorY, ceilingY, narrow, wide, 0);
        }
        if (wide < MIN_SPAN_WIDE * scale) {
            return Result.fail("広がりが足りない(いちばん広い差し渡し " + fmt(wide) + ")", floorY, ceilingY, narrow, wide, 0);
        }
        // 5. つながった空気の量
        int need = Mth.ceil(MIN_VOLUME * scale * scale * scale);
        int volume = floodVolume(level, x, midY, z, scan, floorY, ceilingY, need);
        if (volume < need) {
            return Result.fail("空気が少ない(" + volume + " マス < " + need + ")", floorY, ceilingY, narrow, wide, volume);
        }
        return new Result(true, "", floorY, ceilingY, placeY, narrow, wide, volume);
    }

    /** {@code (x, y, z)} から {@code (dx, dz)} の向きへ、空いているマスが続く数(最大 {@code max})。 */
    private static int reach(BlockGetter level, BlockPos.MutableBlockPos m, int x, int y, int z, int dx, int dz, int max) {
        for (int k = 1; k <= max; k++) {
            if (!isOpen(level, m.set(x + dx * k, y, z + dz * k))) {
                return k - 1;
            }
        }
        return max;
    }

    /**
     * 始点からつながった空いているマスを数える(幅優先)。横は始点から ±{@code radius}、縦は {@code floorY − 1} 〜 {@code ceilingY}
     * の箱の中だけ。{@code need} に届いたら打ち切る。
     */
    private static int floodVolume(BlockGetter level, int x0, int y0, int z0, int radius, int floorY, int ceilingY, int need) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LongOpenHashSet seen = new LongOpenHashSet(need * 2);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long first = BlockPos.asLong(x0, y0, z0);
        if (!isOpen(level, m.set(x0, y0, z0))) {
            return 0;
        }
        seen.add(first);
        queue.enqueue(first);
        int count = 0;
        int minY = floorY - 1;
        while (!queue.isEmpty()) {
            long cur = queue.dequeueLong();
            count++;
            if (count >= need) {
                return count;
            }
            int cx = BlockPos.getX(cur);
            int cy = BlockPos.getY(cur);
            int cz = BlockPos.getZ(cur);
            for (int i = 0; i < 6; i++) {
                int nx = cx + (i == 0 ? 1 : i == 1 ? -1 : 0);
                int ny = cy + (i == 2 ? 1 : i == 3 ? -1 : 0);
                int nz = cz + (i == 4 ? 1 : i == 5 ? -1 : 0);
                if (Math.abs(nx - x0) > radius || Math.abs(nz - z0) > radius || ny < minY || ny > ceilingY) {
                    continue;
                }
                long key = BlockPos.asLong(nx, ny, nz);
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                if (isOpen(level, m.set(nx, ny, nz))) {
                    queue.enqueue(key);
                }
            }
        }
        return count;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /**
     * 読み込み済みのチャンクだけを読む {@link BlockGetter}(サーバー)。読み込まれていないチャンク・高さの範囲の外は石として返す
     * (= 壁。測るのをそこで止めるだけで、チャンクの生成を待たない。重さの監査 2026-09-24 の教訓)。
     */
    public static BlockGetter loadedOnly(ServerLevel level) {
        return new LoadedOnly(level);
    }

    private static final class LoadedOnly implements BlockGetter {
        private static final BlockState WALL = Blocks.STONE.defaultBlockState();
        private final ServerLevel level;
        @Nullable
        private LevelChunk lastChunk;
        private long lastChunkKey = Long.MIN_VALUE;

        LoadedOnly(ServerLevel level) {
            this.level = level;
        }

        @Nullable
        private LevelChunk chunk(BlockPos pos) {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
            if (key != this.lastChunkKey) {
                this.lastChunkKey = key;
                this.lastChunk = this.level.getChunkSource().getChunkNow(cx, cz);
            }
            return this.lastChunk;
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (this.level.isOutsideBuildHeight(pos)) {
                return WALL;
            }
            LevelChunk c = chunk(pos);
            return c == null ? WALL : c.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return this.level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return this.level.getMinBuildHeight();
        }
    }
}
