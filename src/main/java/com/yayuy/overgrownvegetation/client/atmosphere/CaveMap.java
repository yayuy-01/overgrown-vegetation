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
    /** 「まわりに天井になれる岩がある」とみなす、目より上の岩の高さ(段。11-07 仮決め 4 = 頭より高い丘・崖・洞窟の天井)。 */
    static final int ROOF_NEAR = 4;
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

    private record Built(int originX, int originZ, float[] data, float[] coarse, float[] water, boolean anyRoof, long fineMicros, long coarseMicros,
                         long waterMicros, boolean[] overhang, float[] underY, int overhangCount, long overhangMicros) {
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

    /** 列に地下モードを掛けてよいか(11-07 から岩のある列すべて。確かめ用の前の形では過繁茂の列だけ)。作業スレッドから呼ぶ。 */
    private static boolean eligible(ClientLevel level, BlockPos.MutableBlockPos pos, int wx, int rockY, int wz) {
        return !CaveLights.devOvergrownOnly || ModBiomes.isOvergrownLike(level.getBiome(pos.set(wx, rockY, wz)));
    }

    /** 地図があり、まわりに天井になれる岩(目より {@link #ROOF_NEAR} 段以上高い岩の列)があるか(地下の光源を集めるか。11-07 までは「過繁茂の列があるか」)。 */
    static boolean nearRoof() {
        Built b = shown;
        return b != null && b.anyRoof;
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
                if (eligible(level, pos, wx, rockY, wz)) {
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
                // 11-07 世界全体へ: g は「地下モードを掛けてよい列」。前は過繁茂の列だけ、今は岩のある列すべて(確かめ用の cavedev worldwide off で前の形)。
                // 「まわりに天井になれる岩がある」(光源を集めるか・地図の外の空の光の暗い所を洞窟とみなすか)は、目より {@link #ROOF_NEAR} 段以上高い岩の列があるかで見る
                // (岩 = 地表の土や石も含むので、岩のある列の有無では平原でも常に真になる)。過繁茂の列があるときも前と同じく真(過繁茂の地域の中を前と変えない)。
                boolean overgrownColumn = ModBiomes.isOvergrownLike(level.getBiome(pos.set(wx, rockY, wz)));
                if (overgrownColumn || !CaveLights.devOvergrownOnly) {
                    data[at + 1] = 1.0F;
                    if (overgrownColumn || rockY + 1.0F - eyeY >= ROOF_NEAR) {
                        any = true;
                    }
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
        boolean[] overhang = new boolean[SIZE * SIZE];
        float[] underY = new float[SIZE * SIZE];
        java.util.Arrays.fill(underY, Float.NaN);
        int overhangs = devNoOverhang ? 0 : markOverhangs(level, ox, oz, data, coarse, overhang, underY, pos, minY);
        long t4 = System.nanoTime();
        return new Built(ox, oz, data, coarse, water, any, (t1 - t0) / 1000L, (t2 - t1) / 1000L, (t3 - t2) / 1000L, overhang, underY, overhangs, (t4 - t3) / 1000L);
    }

    // ---- 開けた張り出し(2026-11-09 ユーザー報告「空に浮かぶ構造物に近づくと真っ暗になる」) ----

    /** 確かめ用: 開けた張り出しを見ない(前の形。cavedev overhang off)。 */
    static volatile boolean devNoOverhang;
    /** 確かめ用: 印の列を地下モードの対象から外す(11-09 の最初の形。cavedev overhang cut on)。既定は口までの距離を 0 にするだけ。 */
    static volatile boolean devOverhangCut;
    /** 開けた張り出しとみなす、外へ開けた方向の数の下限(8 方向のうち。仮決め 5)。 */
    static final int OVERHANG_OPEN_DIRS = 5;
    /** 外へ開けているかを見る距離(ブロック)。細かい地図の中は細かい地図、外は粗い地図(仮決め、128 まで。11-09 ユーザーの構造物は幅 150 ほどで、64 では真ん中の下が外れた)。 */
    private static final int[] OVERHANG_REACH = {4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128};
    private static final int[][] DIR8 = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /**
     * 作業スレッド: <b>開けた張り出し</b>の列に印を付け、その列を地下モードの対象から外す(g = 0。2026-11-09)。
     * 空に浮かぶ構造物や大きな張り出しの下は、岩の天井があって空の光も届きにくいので、前は洞窟と同じ黒い霧になっていた(ユーザー報告・写真 22・23)。
     * 張り出し = いちばん上の岩の続き(屋根)の下面 a の下に空気があり、8 方向のうち {@link #OVERHANG_OPEN_DIRS} 方向以上で、{@link #OVERHANG_REACH} の距離のどこかに
     * 岩の上面が a 以下の列(= その高さで空が開いている列)がある。洞窟は口の方向(1〜2 方向)しか開けていないので外れない。
     * 粗い地図の升も同じ決まりで外す(遠くから見た構造物の下の地面)。粗い升は、まわりより 6 段以上高い升だけ天井の下面を読む(ほとんどの升は読まない)。
     *
     * @return 印を付けた細かい列の数
     */
    private static int markOverhangs(ClientLevel level, int ox, int oz, float[] data, float[] coarse, boolean[] overhang, float[] underY,
                                     BlockPos.MutableBlockPos pos,
                                     int minY) {
        int count = 0;
        // 市松の半分の列だけ調べ、残りの列は上下左右 4 列のうち 2 列以上に印があれば印(11-09 軽く。構造物の下の印はまとまって付くので)
        for (int pass = 0; pass < 2; pass++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    if (((x + z) & 1) != pass) {
                        continue;
                    }
                    int at = (z * SIZE + x) * 4;
                    if (data[at] <= NO_ROCK + 1.0F) {
                        continue;
                    }
                    float top = data[at] - 1.0F;
                    float a = data[at + 3];
                    if (top - a >= CEIL_SCAN - 1 || a - 1 < minY) {
                        continue; // 下まで岩(山・地面)
                    }
                    boolean mark;
                    if (pass == 0) {
                        int u = overhangColumn(level, pos, ox, oz, data, coarse, ox + x, oz + z, (int) a, minY);
                        mark = u != Integer.MIN_VALUE;
                        if (mark) {
                            underY[z * SIZE + x] = u;
                        }
                    } else {
                        int n = 0;
                        n += x > 0 && overhang[z * SIZE + x - 1] ? 1 : 0;
                        n += x < SIZE - 1 && overhang[z * SIZE + x + 1] ? 1 : 0;
                        n += z > 0 && overhang[(z - 1) * SIZE + x] ? 1 : 0;
                        n += z < SIZE - 1 && overhang[(z + 1) * SIZE + x] ? 1 : 0;
                        mark = n >= 2;
                        if (mark) {
                            underY[z * SIZE + x] = minUnder(underY, overhang, x, z, 1);
                        }
                    }
                    if (mark) {
                        overhang[z * SIZE + x] = true;
                        if (devOverhangCut) {
                            data[at + 1] = 0.0F; // 確かめ用の前の形: 地下モードの対象から外す(バニラの暗さになる)
                        } else {
                            // 地下の照らし方(床の持ち上げ・点光源)は残し、外から見て口からの距離で黒へ沈むのだけやめる(口までの距離 0)。
                            // 11-09 ユーザーのワールド: 対象から外すと、過繁茂した構造物の中・下がバニラの暗さ(空の光 0)で前より暗くなった
                            data[at + 2] = 0.0F;
                        }
                        count++;
                    }
                }
            }
        }
        // 穴埋め(11-09 ユーザーの写真 25・26: 印の列の中に印の無い列が点々と残り、そこだけ外から見て黒へ沈んで、床に段の付いたまだらが出た)。
        // 印の無い天井の下の列は、まわり 5×5 の 6 割以上が印なら印にする(柱・吊り下がった飾りの列)。
        boolean[] filled = overhang.clone();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                if (overhang[i] || data[i * 4] <= NO_ROCK + 1.0F || data[i * 4 + 2] <= 0.5F) {
                    continue;
                }
                int marked = 0;
                int total = 0;
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if ((dx != 0 || dz != 0) && nx >= 0 && nz >= 0 && nx < SIZE && nz < SIZE) {
                            total++;
                            marked += overhang[nz * SIZE + nx] ? 1 : 0;
                        }
                    }
                }
                if (total > 0 && marked >= 0.6F * total) {
                    filled[i] = true;
                    underY[i] = minUnder(underY, overhang, x, z, 2);
                    if (devOverhangCut) {
                        data[i * 4 + 1] = 0.0F;
                    } else {
                        data[i * 4 + 2] = 0.0F;
                    }
                    count++;
                }
            }
        }
        System.arraycopy(filled, 0, overhang, 0, overhang.length);
        int cx0 = ox + COARSE_OFFSET;
        int cz0 = oz + COARSE_OFFSET;
        for (int z = 0; z < CSIZE; z++) {
            for (int x = 0; x < CSIZE; x++) {
                int at = (z * CSIZE + x) * 4;
                if (coarse[at] <= NO_ROCK + 1.0F || coarse[at + 1] < 0.5F) {
                    continue;
                }
                int wx = cx0 + x * COARSE + COARSE / 2;
                int wz = cz0 + z * COARSE + COARSE / 2;
                // まわりより 6 段以上高い升だけ(平らな地面・なだらかな丘は天井の下面を読まない)
                if (openDirs(ox, oz, data, coarse, wx, wz, coarse[at] - 1.0F - 6.0F) < OVERHANG_OPEN_DIRS) {
                    continue;
                }
                int topY = (int) (coarse[at] - 1.0F);
                int bottom = topY;
                while (bottom > topY - CEIL_SCAN && bottom - 1 >= minY && isRock(level.getBlockState(pos.set(wx, bottom - 1, wz)))) {
                    bottom--;
                }
                if (topY - bottom >= CEIL_SCAN - 1 || bottom - 1 < minY) {
                    continue; // 山の頂(下まで岩)
                }
                if (overhangColumn(level, pos, ox, oz, data, coarse, wx, wz, bottom, minY) != Integer.MIN_VALUE) {
                    coarse[at + 1] = 0.0F;
                }
            }
        }
        return count;
    }

    /** 列 (wx, wz) から 8 方向に {@link #OVERHANG_REACH} の距離を見て、岩の上面が高さ {@code a} 以下の列(か、岩の無い読み込まれた列)がある方向の数。 */
    private static int openDirs(int ox, int oz, float[] data, float[] coarse, int wx, int wz, float a) {
        int open = 0;
        int cx0 = ox + COARSE_OFFSET;
        int cz0 = oz + COARSE_OFFSET;
        for (int[] d : DIR8) {
            double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
            for (int reach : OVERHANG_REACH) {
                int sx = wx + (int) Math.round(d[0] * reach / len);
                int sz = wz + (int) Math.round(d[1] * reach / len);
                int fx = sx - ox;
                int fz = sz - oz;
                float r;
                boolean known;
                if (fx >= 0 && fz >= 0 && fx < SIZE && fz < SIZE) {
                    r = data[(fz * SIZE + fx) * 4];
                    known = true; // 読み込まれていない列は NO_ROCK のまま(開けていると数えない)
                    if (r <= NO_ROCK + 1.0F) {
                        known = false;
                    }
                } else {
                    int gx = Math.floorDiv(sx - cx0, COARSE);
                    int gz = Math.floorDiv(sz - cz0, COARSE);
                    if (gx < 0 || gz < 0 || gx >= CSIZE || gz >= CSIZE) {
                        break;
                    }
                    int c = (gz * CSIZE + gx) * 4;
                    r = coarse[c];
                    known = coarse[c + 2] > 0.5F && r > NO_ROCK + 1.0F;
                }
                if (known && r <= a) {
                    open++;
                    break;
                }
            }
        }
        return open;
    }

    /** 列を下へたどるのは屋根の下面からこの段数まで(仮決め)。 */
    private static final int UNDERSIDE_SCAN = 320;
    /** 1 列で覚える浮いた岩の下面の数・見通しまで調べる数の上限(仮決め)。 */
    private static final int MAX_UNDERSIDES = 12;
    private static final int MAX_SEEN_CHECKS = 3;
    /** 見通しを調べる高さ(下面から何段下。11-09: 吊り下がった飾りで 1・3 段下だけでは 8 方向のうち 4 方向しか通らなかった)。 */
    private static final int[] RAY_DROPS = {1, 3, 6, 10};

    /**
     * 列が開けた張り出しの下なら、その判定に使った浮いた岩の下面の高さ。違えば Integer.MIN_VALUE。浮いた岩の下面を下から順に試す
     * (11-09: 構造物の下の地面の下に洞窟があると、いちばん下の下面は洞窟の天井になり、外れていた)。高さの試しは安いので全部、見通しは {@link #MAX_SEEN_CHECKS} 回まで。
     */
    private static int overhangColumn(ClientLevel level, BlockPos.MutableBlockPos pos, int ox, int oz, float[] data, float[] coarse, int wx, int wz, int a,
                                      int minY) {
        int[] us = undersides(level, pos, wx, wz, a, minY);
        int checks = 0;
        for (int i = us.length - 1; i >= 0 && checks < MAX_SEEN_CHECKS; i--) {
            if (openDirs(ox, oz, data, coarse, wx, wz, us[i]) < OVERHANG_OPEN_DIRS) {
                continue;
            }
            checks++;
            if (seenDirs(level, pos, ox, oz, data, coarse, wx, wz, us[i]) >= OVERHANG_OPEN_DIRS) {
                return us[i];
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 列の屋根の下面 a と、その下の浮いた岩の下面(上から順。地面に着くまで、{@link #MAX_UNDERSIDES} 個まで)。 */
    private static int[] undersides(ClientLevel level, BlockPos.MutableBlockPos pos, int wx, int wz, int a, int minY) {
        int[] buf = new int[MAX_UNDERSIDES];
        int n = 0;
        buf[n++] = a;
        int y = a - 1;
        int stop = Math.max(minY, a - UNDERSIDE_SCAN);
        while (y > stop && n < MAX_UNDERSIDES) {
            while (y > stop && !isRock(level.getBlockState(pos.set(wx, y, wz)))) {
                y--;
            }
            if (y <= stop) {
                break;
            }
            int top = y;
            while (y > stop && top - y < CEIL_SCAN && isRock(level.getBlockState(pos.set(wx, y - 1, wz)))) {
                y--;
            }
            if (top - y >= CEIL_SCAN - 1 || y - 1 <= minY) {
                break; // 地面に着いた
            }
            buf[n++] = y;
            y--;
        }
        return java.util.Arrays.copyOf(buf, n);
    }

    /**
     * 列 (wx, wz) の屋根の下面 {@code a} のすぐ下(1 段下と 3 段下)の高さで 8 方向へ 2 ブロックずつ進み、岩に当たる前に「その高さで空の開けた列」
     * (岩の上面が a 以下)へ出られる方向の数。丘の中の洞窟は壁に当たるので数えない(11-09: 高さだけで見ると、まわりの平地より高い所の洞窟が外になった)。
     * 空に浮かぶ構造物の下は空気が外まで続くので数える。読み込まれていない所・地図の外で止まる。
     */
    private static int seenDirs(ClientLevel level, BlockPos.MutableBlockPos pos, int ox, int oz, float[] data, float[] coarse, int wx, int wz, int a) {
        int open = 0;
        int done = 0;
        int cx0 = ox + COARSE_OFFSET;
        int cz0 = oz + COARSE_OFFSET;
        for (int[] d : DIR8) {
            double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
            boolean seen = false;
            for (int dy : RAY_DROPS) {
                if (seen) {
                    break;
                }
                int y = a - dy;
                // 下面からこの高さまで、列が空気で続いている所だけ(11-09: 床・地面の中や世界の底の下から撃つと、空気として抜けて丘の洞窟が外になった)
                boolean clear = y > level.getMinBuildHeight();
                for (int k = a - 1; clear && k >= y; k--) {
                    clear = !isRock(level.getBlockState(pos.set(wx, k, wz)));
                }
                if (!clear) {
                    break;
                }
                for (int s = 2; s <= OVERHANG_REACH[OVERHANG_REACH.length - 1]; s += s < 16 ? 2 : s < 48 ? 4 : 8) {
                    int sx = wx + (int) Math.round(d[0] * s / len);
                    int sz = wz + (int) Math.round(d[1] * s / len);
                    if (!level.hasChunk(sx >> 4, sz >> 4) || isRock(level.getBlockState(pos.set(sx, y, sz)))) {
                        break; // 壁・床に当たった
                    }
                    int fx = sx - ox;
                    int fz = sz - oz;
                    float r;
                    if (fx >= 0 && fz >= 0 && fx < SIZE && fz < SIZE) {
                        r = data[(fz * SIZE + fx) * 4];
                    } else {
                        int gx = Math.floorDiv(sx - cx0, COARSE);
                        int gz = Math.floorDiv(sz - cz0, COARSE);
                        if (gx < 0 || gz < 0 || gx >= CSIZE || gz >= CSIZE) {
                            break;
                        }
                        r = coarse[(gz * CSIZE + gx) * 4];
                    }
                    if (r > NO_ROCK + 1.0F && r <= a) {
                        seen = true;
                        break;
                    }
                }
            }
            if (seen) {
                open++;
            }
            done++;
            // 答えが決まったら打ち切る(11-09 軽く: ユーザーの構造物で地図 1 回が 0.9 秒かかった)
            if (open >= OVERHANG_OPEN_DIRS || done - open > DIR8.length - OVERHANG_OPEN_DIRS) {
                return open;
            }
        }
        return open;
    }

    /**
     * 確かめ用(cavedev overhangmap): カメラのまわり ±r 列の印を文字の地図で(行 = z、列 = x、北が上)。
     * '.' = 岩が無いか天井の無い列(口までの距離 0)、'o' = 開けた張り出しの印、'#' = 印の無い天井の下(外から見て黒へ沈む列)、'@' = カメラ。
     */
    static String overhangMap(int cx, int cz, int r) {
        Built b = shown;
        if (b == null) {
            return "地図なし";
        }
        StringBuilder s = new StringBuilder();
        for (int z = cz - r; z <= cz + r; z++) {
            s.append('\n');
            for (int x = cx - r; x <= cx + r; x++) {
                int fx = x - b.originX;
                int fz = z - b.originZ;
                if (fx < 0 || fz < 0 || fx >= SIZE || fz >= SIZE) {
                    s.append(' ');
                    continue;
                }
                int i = fz * SIZE + fx;
                char c = x == cx && z == cz ? '@' : b.overhang[i] ? 'o' : b.data[i * 4 + 2] > 0.5F ? '#' : '.';
                s.append(c);
            }
        }
        return s.toString();
    }

    /** 確かめ用(cavedev overhangwhy x z): 列の屋根の下面・浮いた岩のいちばん下の面・高さで開けた方向・見通せた方向。描画のスレッドで地図を読み直す。 */
    static String explainOverhang(ClientLevel level, int wx, int wz) {
        Built b = shown;
        if (b == null) {
            return "地図なし";
        }
        int x = wx - b.originX;
        int z = wz - b.originZ;
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
            return "地図の外";
        }
        int at = (z * SIZE + x) * 4;
        float top = b.data[at] - 1.0F;
        float a = b.data[at + 3];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        StringBuilder s = new StringBuilder(String.format("岩の上面 %.0f 屋根の下面 %.0f 印 %s 下面ごと(高さで開けた・見通せた):", top, a, b.overhang[z * SIZE + x]));
        for (int u : undersides(level, pos, wx, wz, (int) a, level.getMinBuildHeight())) {
            s.append(String.format(" %d(%d・%d)", u, openDirs(b.originX, b.originZ, b.data, b.coarse, wx, wz, u),
                    seenDirs(level, pos, b.originX, b.originZ, b.data, b.coarse, wx, wz, u)));
        }
        return s.toString();
    }

    /** 印の列の、判定に使った浮いた岩の下面の高さのうち、(x, z) のまわり r 列で一番低いもの(11-09: 一番高いものにすると、構造物の中のカメラが下にいると取り違えられ、葉の面が分かれた)(穴埋め・市松の残りの列に)。 */
    private static float minUnder(float[] underY, boolean[] overhang, int x, int z, int r) {
        float best = Float.NaN;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < SIZE && nz < SIZE && overhang[nz * SIZE + nx] && !Float.isNaN(underY[nz * SIZE + nx])) {
                    best = Float.isNaN(best) ? underY[nz * SIZE + nx] : Math.min(best, underY[nz * SIZE + nx]);
                }
            }
        }
        return best;
    }

    /**
     * カメラ (wx, y, wz) が、空に浮かぶ構造物の<b>下</b>(浮いた岩のいちばん下の面より下の、開けた空気)にいるか(11-09)。カメラの列か、まわり 5×5 の印の列の
     * 下面の高さのうち一番低いものより下なら真。構造物の<b>中</b>(下面より上)では偽 = カメラはもとの地下の判定のまま
     * (中で外扱いにすると、面が自分の空の光だけで地下かどうか決まり、葉の面が明るい緑と真っ暗に分かれた。ユーザーの写真 28〜30)。
     */
    static boolean cameraBelowOverhang(int wx, int wz, double y) {
        Built b = shown;
        if (b == null) {
            return false;
        }
        int x = wx - b.originX;
        int z = wz - b.originZ;
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
            return false;
        }
        float u = b.overhang[z * SIZE + x] ? b.underY[z * SIZE + x] : Float.NaN;
        if (Float.isNaN(u) && overhangShare(wx, wz, 2) >= 0.5F) {
            u = minUnder(b.underY, b.overhang, x, z, 2);
        }
        return !Float.isNaN(u) && y < u;
    }

    /** (wx, wz) のまわり (2r+1)² 列のうち開けた張り出しの印の列の割合(地図の外の列は数えない。11-09: 構造物の柱の列は地面まで岩なので印が付かず、柱のそばのカメラが地下になった)。 */
    static float overhangShare(int wx, int wz, int r) {
        Built b = shown;
        if (b == null) {
            return 0.0F;
        }
        int marked = 0;
        int total = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = wx + dx - b.originX;
                int z = wz + dz - b.originZ;
                if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
                    continue;
                }
                total++;
                marked += b.overhang[z * SIZE + x] ? 1 : 0;
            }
        }
        return total == 0 ? 0.0F : (float) marked / total;
    }

    /** 列 (wx, wz) が開けた張り出しの下か(細かい地図の中だけ。地図が無い・外なら偽)。カメラの地下の判定・洞窟の口の演出が見る(11-09)。 */
    static boolean overhangAt(int wx, int wz) {
        Built b = shown;
        if (b == null) {
            return false;
        }
        int x = wx - b.originX;
        int z = wz - b.originZ;
        return x >= 0 && z >= 0 && x < SIZE && z < SIZE && b.overhang[z * SIZE + x];
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
        return b == null ? "地下の地図 なし" : String.format("地下の地図 原点 %d %d 岩の天井 %s 作る時間 細かい %d µs 粗い %d µs 水面 %d µs 開けた張り出し %d 列 %d µs", b.originX, b.originZ, b.anyRoof,
                b.fineMicros, b.coarseMicros, b.waterMicros, b.overhangCount, b.overhangMicros);
    }
}
