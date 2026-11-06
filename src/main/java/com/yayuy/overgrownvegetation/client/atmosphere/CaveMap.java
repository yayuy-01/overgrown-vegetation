package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <b>地下の地図</b>(2026-11-01 夜 見え方の直し 4)。地下モード({@link CaveLights})の黒い霧と点光源を、カメラの所でなく<b>画面の点(断片)の所</b>で決めるための地図。
 * 前はカメラの所だけで決めていたので、外から洞窟をのぞくと中が地上の霧(白)のままで、中から口を見ると外まで黒い霧だった。
 *
 * <p>カメラのまわり 64×64 列(1 列 1 画素)に、その列の「いちばん上の岩の上面の高さ」と「過繁茂の地域か」を持つ。岩 = 空気でも木の一部
 * ({@code #not_cave_roof}・{@link TreeCanopyGroundFinder#isTreePart})でもなく、動きを止めるブロック(水・下草・蔦は岩でない。{@code CaveGrowth} の天井と同じ)。
 * 地形のシェーダー({@code ov_cave.glsl} の {@code ovCaveUnder})は、断片の真上の岩の厚み(この高さ − 断片の高さ)が roofFrom〜roofTo で 0 → 1、
 * 断片の空の光が skyFrom → skyTo で 0 → 1 を掛けて「そこが地下か」を出す(面の法線の側へ半ブロックずらした列を見るので、崖の面は外の列で決まる)。
 * 作るのは裏のスレッド({@link ScanThread})で 2 秒おき・カメラが 8 ブロック動いたら。原点は 8 に丸める。MistMap と同じく読むだけ。
 * 画素(RGBA32F): r = 岩の上面の高さ(ワールドの y。岩が無ければ −10000)、g = 過繁茂の地域なら 1、b = いちばん近い口の列までの水平距離({@link #mouthDistance}、11-04)、
 * a = 天井の下面の高さ(続く岩のいちばん下のブロックの y)。
 */
final class CaveMap {

    static final int SIZE = 64;
    private static final int REBUILD_TICKS = 40;
    private static final double RECENTER = 8.0;
    private static final float NO_ROCK = -10000.0F;
    /**
     * 天井と見なす岩の厚み(縦に続く岩のブロックの数、仮決め 3)。これより薄い岩の層は読み飛ばしてその下を見る(11-02 霧の剥がれ: 巨大樹の幹の上に乗った
     * 1 段の土・草・過繁茂ブロックが天井になり、その下の林床が地下扱い = 胞子の霞が外れて黒い霧の手前の澄んだ暗さになっていた)。
     */
    static final int MIN_ROOF = 3;
    /** 確かめ用: 真なら前の数え方(1 段でも天井)。撮り比べのため。 */
    static boolean devThinRoof;

    /**
     * 粗い地図(2026-11-05 光の点検 16): 細かい地図の外の断片の「空の光だけで地下」を、上に岩が無い所では打ち消す。{@link #COARSE} ブロック升の
     * 真ん中の 1 列を読んだ 128×128(512 ブロック四方 = 描画距離 16 を覆う。読む列は 16384)。画素は r = 岩の上面(細かい地図と同じ数え方。木は岩でない)・
     * g = 過繁茂なら 1・b = 読めた列なら 1・a = 0。テクスチャ(幅 128)の行 64〜191 に載せる(細かい地図は行 0〜63 の左 64 列)。
     * 原点は細かい地図の原点 + 32 − 256(シェーダーも同じ式で出すので uniform は増やさない)。前は細かい地図の外を「空の光 3/15 未満なら地下」で決めていて、
     * 巨大樹の傘の下の幹・林床(空の光ほぼ 0)が地下になり、外から洞窟の霧の黒に沈んでいた。
     * 升を 8 ブロックにした最初の版は、升の真ん中が岸に当たると傘の下の池が、升が小さな丘(fog14、幅 16)の端をまたぐと洞窟の奥の壁が取り違えられた
     * (升の中の数か所の最小・2 番目に低い値も試したが、池と丘の端の両方は直らなかった)。4 ブロックで両方とも合う。
     */
    static final int COARSE = 4;
    static final int CSIZE = 128;
    static final int COARSE_OFFSET = SIZE / 2 - CSIZE * COARSE / 2;

    /**
     * 洞窟の水面の地図(11-06 光の点検 10)。細かい地図と同じ 64×64 の列ごとに、カメラの高さ +{@link #WATER_UP} 〜 −{@link #WATER_DOWN} 段の中で
     * 上から最初に見つけた水面(上が水でない水のブロックの y + 水の高さ)。r = 水面の y(ワールド)・g = 水があれば 1。テクスチャ(幅 128)の行 0〜63 の
     * 右 64 列(前は空き)に載せる。シェーダーは洞窟の水の下の面を水深で暗くする({@code ov_cave.glsl} の {@code ovCaveWaterBottom})。段の範囲は仮決め。
     */
    static final int WATER_UP = 8;
    static final int WATER_DOWN = 24;

    private record Built(int originX, int originZ, float[] data, float[] coarse, float[] water, boolean anyOvergrown, long fineMicros, long coarseMicros,
                         long waterMicros) {
    }

    @Nullable
    private static CompletableFuture<Built> building;
    @Nullable
    private static Built shown;
    private static long builtAt = -1_000_000L;
    private static boolean uploadPending;
    private static int texture;
    private static FloatBuffer buffer;

    private CaveMap() {
    }

    static void clear() {
        building = null;
        shown = null;
        builtAt = -1_000_000L;
        uploadPending = false;
    }

    /** 地図があり、まわりに過繁茂の列があるか(地下の光源を集めるか)。 */
    static boolean nearOvergrown() {
        Built b = shown;
        return b != null && b.anyOvergrown;
    }

    static boolean ready() {
        return shown != null && texture != 0;
    }

    static int originX() {
        Built b = shown;
        return b != null ? b.originX : 0;
    }

    static int originZ() {
        Built b = shown;
        return b != null ? b.originZ : 0;
    }

    static int texture() {
        return texture;
    }

    /** 毎 tick({@link CaveLights#tick} から)。 */
    static void tick(ClientLevel level, Vec3 eye, long ticks) {
        if (building != null && building.isDone()) {
            Built done = building.getNow(null);
            building = null;
            if (done != null) {
                shown = done;
                uploadPending = true;
            }
        }
        if (building != null) {
            return;
        }
        Built b = shown;
        boolean moved = b == null || Math.abs(eye.x - (b.originX + SIZE / 2.0)) > RECENTER || Math.abs(eye.z - (b.originZ + SIZE / 2.0)) > RECENTER;
        if (!moved && ticks - builtAt < REBUILD_TICKS) {
            return;
        }
        builtAt = ticks;
        int ox = (Mth.floor(eye.x) >> 3 << 3) - SIZE / 2;
        int oz = (Mth.floor(eye.z) >> 3 << 3) - SIZE / 2;
        int eyeY = Mth.floor(eye.y);
        building = CompletableFuture.supplyAsync(() -> build(level, ox, oz, eyeY), ScanThread.EXECUTOR).exceptionally(t -> {
            OvergrownVegetation.LOGGER.warn("地下の地図を作れなかった", t);
            return null;
        });
    }

    /**
     * 岩(洞窟の天井になれるブロック)か。光を遮る立方体で、水を含まないもの(胞子昆布の柱・水没した物を天井にすると、
     * その下の海底が地下になって黒い霧の筋が出た。11-01 撮影)。
     */
    static boolean isRock(BlockState state) {
        return !state.isAir() && state.canOcclude() && state.blocksMotion() && state.getFluidState().isEmpty()
                && !state.is(ModTags.NOT_CAVE_ROOF) && !TreeCanopyGroundFinder.isTreePart(state);
    }

    /** {@code y} から下へ続く岩の数({@link #MIN_ROOF} で打ち切り)。 */
    private static int rockRun(net.minecraft.world.level.BlockGetter level, BlockPos.MutableBlockPos pos, int x, int y, int z, int minY) {
        int run = 0;
        while (run < MIN_ROOF && y - run >= minY && isRock(level.getBlockState(pos.set(x, y - run, z)))) {
            run++;
        }
        return run;
    }

    /**
     * カメラ側の天井({@link CaveLights} の地上と混ぜる係数): {@code (x, z)} の列で {@code y0} から上 {@code scan} 段のうちに、{@link #MIN_ROOF} 段以上続く岩があるか。
     * 地図と同じ数え方(前は {@link ForestPresence#hasRockRoof} = 1 段の不透明なブロックでも天井)。空の無い次元は常に天井。
     */
    static boolean thickRoofAbove(ClientLevel level, int x, int y0, int z, int scan) {
        if (!level.dimensionType().hasSkyLight()) {
            return true;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = Math.min(level.getMaxBuildHeight() - 1, y0 + scan);
        int minY = level.getMinBuildHeight();
        for (int y = y0; y <= top; y++) {
            if (isRock(level.getBlockState(pos.set(x, y, z)))) {
                // 上へ続く岩を数える(下へ数える rockRun と向きだけ違う)。
                int run = 0;
                while (run < MIN_ROOF && y + run <= top && isRock(level.getBlockState(pos.set(x, y + run, z)))) {
                    run++;
                }
                if (run >= MIN_ROOF || devThinRoof || y + run > top) {
                    return true;
                }
                y += run;
            }
        }
        return false;
    }

    /** 作業スレッド: 列ごとに上から木・空気・水・下草を読み飛ばし、{@link #MIN_ROOF} 段以上続く最初の岩を探す。 */
    /** 列 (wx, wz) のいちばん上の {@link #MIN_ROOF} 段以上続く岩の上のブロックの y(無ければ {@code Integer.MIN_VALUE})。 */
    private static int rockTop(ClientLevel level, BlockPos.MutableBlockPos pos, int wx, int wz, int minY) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
        for (int y = top; y >= minY; y--) {
            if (isRock(level.getBlockState(pos.set(wx, y, wz)))) {
                int run = rockRun(level, pos, wx, y, wz, minY);
                if (run >= MIN_ROOF || devThinRoof || y - run < minY) {
                    return y;
                }
                y -= run - 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 作業スレッド: 粗い地図(画素の真ん中の 1 列だけ読む)。 */
    private static float[] buildCoarse(ClientLevel level, int ox, int oz, BlockPos.MutableBlockPos pos, int minY) {
        float[] out = new float[CSIZE * CSIZE * 4];
        int cx = ox + COARSE_OFFSET;
        int cz = oz + COARSE_OFFSET;
        for (int z = 0; z < CSIZE; z++) {
            for (int x = 0; x < CSIZE; x++) {
                int wx = cx + x * COARSE + COARSE / 2;
                int wz = cz + z * COARSE + COARSE / 2;
                int at = (z * CSIZE + x) * 4;
                out[at] = NO_ROCK;
                if (!level.hasChunk(wx >> 4, wz >> 4)) {
                    continue;
                }
                out[at + 2] = 1.0F;
                int rockY = rockTop(level, pos, wx, wz, minY);
                if (rockY == Integer.MIN_VALUE) {
                    continue;
                }
                out[at] = rockY + 1.0F;
                if (ModBiomes.isOvergrownLike(level.getBiome(pos.set(wx, rockY, wz)))) {
                    out[at + 1] = 1.0F;
                }
            }
        }
        return out;
    }

    /** 作業スレッド: 洞窟の水面の地図({@link #WATER_UP})。地図に過繁茂の列が無ければ読まない(地下モードの光は過繁茂のまわりだけ)。 */
    private static float[] buildWater(ClientLevel level, int ox, int oz, int eyeY, BlockPos.MutableBlockPos pos) {
        float[] out = new float[SIZE * SIZE * 4];
        int minY = level.getMinBuildHeight();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int wx = ox + x;
                int wz = oz + z;
                if (!level.hasChunk(wx >> 4, wz >> 4)) {
                    continue;
                }
                boolean aboveWater = false;
                for (int y = eyeY + WATER_UP; y >= Math.max(minY, eyeY - WATER_DOWN); y--) {
                    var fluid = level.getFluidState(pos.set(wx, y, wz));
                    boolean water = fluid.is(net.minecraft.tags.FluidTags.WATER);
                    if (water && !aboveWater && y < eyeY + WATER_UP) {
                        int at = (z * SIZE + x) * 4;
                        out[at] = y + fluid.getHeight(level, pos);
                        out[at + 1] = 1.0F;
                        break;
                    }
                    aboveWater = water;
                }
            }
        }
        return out;
    }

    private static Built build(ClientLevel level, int ox, int oz, int eyeY) {
        long t0 = System.nanoTime();
        float[] data = new float[SIZE * SIZE * 4];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        boolean any = false;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int wx = ox + x;
                int wz = oz + z;
                int at = (z * SIZE + x) * 4;
                data[at] = NO_ROCK;
                if (!level.hasChunk(wx >> 4, wz >> 4)) {
                    continue;
                }
                int rockY = rockTop(level, pos, wx, wz, minY);
                if (rockY == Integer.MIN_VALUE) {
                    continue;
                }
                data[at] = rockY + 1.0F;
                if (ModBiomes.isOvergrownLike(level.getBiome(pos.set(wx, rockY, wz)))) {
                    data[at + 1] = 1.0F;
                    any = true;
                }
                // 天井の下面(続く岩のいちばん下のブロック。CEIL_SCAN 段で打ち切り)。口までの距離に使う(11-04 地下の光の直し 12)。
                int bottom = rockY;
                while (bottom > rockY - CEIL_SCAN && bottom - 1 >= minY && isRock(level.getBlockState(pos.set(wx, bottom - 1, wz)))) {
                    bottom--;
                }
                data[at + 3] = bottom;
            }
        }
        mouthDistance(data, loaded(level, ox, oz));
        long t1 = System.nanoTime();
        float[] coarse = buildCoarse(level, ox, oz, pos, minY);
        long t2 = System.nanoTime();
        float[] water = any ? buildWater(level, ox, oz, eyeY, pos) : null;
        long t3 = System.nanoTime();
        return new Built(ox, oz, data, coarse, water, any, (t1 - t0) / 1000L, (t2 - t1) / 1000L, (t3 - t2) / 1000L);
    }

    /** 天井の下面を探す深さ(仮決め 64)。これより厚い岩は「とても深い天井」として扱う。 */
    static final int CEIL_SCAN = 64;
    /** 口までの距離の打ち切り(列。仮決め 32)。 */
    static final int MOUTH_CAP = 32;
    /** 半径 {@link #MOUTH_CAP} の中の列の差を近い順に並べたもの(dx, dz, 距離)。 */
    private static final int[][] OFFSETS = offsets();

    private static int[][] offsets() {
        List<int[]> list = new ArrayList<>();
        for (int dz = -MOUTH_CAP; dz <= MOUTH_CAP; dz++) {
            for (int dx = -MOUTH_CAP; dx <= MOUTH_CAP; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 0 && d2 <= MOUTH_CAP * MOUTH_CAP) {
                    list.add(new int[]{dx, dz, d2});
                }
            }
        }
        list.sort((a, b) -> Integer.compare(a[2], b[2]));
        return list.toArray(new int[0][]);
    }

    private static boolean[] loaded(ClientLevel level, int ox, int oz) {
        boolean[] out = new boolean[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                out[z * SIZE + x] = level.hasChunk((ox + x) >> 4, (oz + z) >> 4);
            }
        }
        return out;
    }

    /**
     * 各列の b に「いちばん近い口の列までの水平距離」(列、{@link #MOUTH_CAP} で打ち切り)を書く(11-04 地下の光の直し 12)。
     * 口 = 読み込み済みで、岩の上面(r)がこの列の天井の下面(a)以下の列(その高さで空が開いている。崖の斜面のように天井より高い列は口でない)。
     * 岩の無い列・天井の無い列は 0。地図の外は見ない(口が無ければ打ち切りの値)。シェーダーは外から見た洞窟の中をこの距離で黒へ沈める。
     * 作業スレッドで呼ぶ。素の Java で検算できるよう、BlockGetter に触らない。
     */
    static void mouthDistance(float[] data, boolean[] loaded) {
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int at = (z * SIZE + x) * 4;
                if (data[at] <= NO_ROCK + 1.0F) {
                    data[at + 2] = 0.0F;
                    continue;
                }
                float ceil = data[at + 3];
                float found = MOUTH_CAP;
                for (int[] o : OFFSETS) {
                    int mx = x + o[0];
                    int mz = z + o[1];
                    if (mx < 0 || mz < 0 || mx >= SIZE || mz >= SIZE) {
                        continue;
                    }
                    int m = mz * SIZE + mx;
                    if (loaded[m] && data[m * 4] <= ceil) {
                        found = (float) Math.sqrt(o[2]);
                        break;
                    }
                }
                data[at + 2] = found;
            }
        }
    }

    /** 描画のスレッド: 新しい地図をテクスチャへ載せる(AFTER_SKY、{@link CaveLights#upload} から)。 */
    static void upload() {
        Built b = shown;
        if (!uploadPending || b == null) {
            return;
        }
        uploadPending = false;
        if (buffer == null) {
            buffer = MemoryUtil.memAllocFloat(CSIZE * (SIZE + CSIZE) * 4);
        }
        // 幅 CSIZE のテクスチャ: 行 0〜63 の左 64 列が細かい地図、右 64 列が洞窟の水面の地図(11-06。無ければ 0)、行 64〜191 が粗い地図。
        buffer.clear();
        float[] pad = new float[(CSIZE - SIZE) * 4];
        for (int z = 0; z < SIZE; z++) {
            buffer.put(b.data, z * SIZE * 4, SIZE * 4);
            if (b.water != null) {
                buffer.put(b.water, z * SIZE * 4, SIZE * 4);
            } else {
                buffer.put(pad);
            }
        }
        buffer.put(b.coarse);
        buffer.flip();
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture == 0) {
            texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        } else {
            GlStateManager._bindTexture(texture);
        }
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, CSIZE, SIZE + CSIZE, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        GlStateManager._bindTexture(previous);
    }

    /** 確かめ用: 列 (wx, wz) の画素(r 岩の上面・g 過繁茂・b 口までの距離・a 天井の下面)。地図の外なら null。 */
    @Nullable
    static float[] texel(int wx, int wz) {
        Built b = shown;
        if (b == null) {
            return null;
        }
        int x = wx - b.originX;
        int z = wz - b.originZ;
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
            return null;
        }
        int at = (z * SIZE + x) * 4;
        return new float[]{b.data[at], b.data[at + 1], b.data[at + 2], b.data[at + 3]};
    }

    /** 確かめ用: 列 (wx, wz) を含む粗い地図の升の画素(r 岩の上面・g 過繁茂・b 読めた・升の原点 x・z)。粗い地図の外なら null。 */
    @Nullable
    static float[] coarseTexel(int wx, int wz) {
        Built b = shown;
        if (b == null) {
            return null;
        }
        int ox = b.originX + COARSE_OFFSET;
        int oz = b.originZ + COARSE_OFFSET;
        int x = Math.floorDiv(wx - ox, COARSE);
        int z = Math.floorDiv(wz - oz, COARSE);
        if (x < 0 || z < 0 || x >= CSIZE || z >= CSIZE) {
            return null;
        }
        int at = (z * CSIZE + x) * 4;
        return new float[]{b.coarse[at], b.coarse[at + 1], b.coarse[at + 2], ox + x * COARSE, oz + z * COARSE};
    }

    /** 確かめ用の一行。 */
    static String describe() {
        Built b = shown;
        return b == null ? "地下の地図 なし" : String.format("地下の地図 原点 %d %d 過繁茂 %s 作る時間 細かい %d µs 粗い %d µs 水面 %d µs", b.originX, b.originZ, b.anyOvergrown,
                b.fineMicros, b.coarseMicros, b.waterMicros);
    }
}
