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
 * 画素(RGBA32F): r = 岩の上面の高さ(ワールドの y。岩が無ければ −10000)、g = 過繁茂の地域なら 1。
 */
final class CaveMap {

    static final int SIZE = 64;
    private static final int REBUILD_TICKS = 40;
    private static final double RECENTER = 8.0;
    private static final float NO_ROCK = -10000.0F;

    private record Built(int originX, int originZ, float[] data, boolean anyOvergrown) {
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
        building = CompletableFuture.supplyAsync(() -> build(level, ox, oz), ScanThread.EXECUTOR).exceptionally(t -> {
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

    /** 作業スレッド: 列ごとに上から木・空気・水・下草を読み飛ばして最初の岩を探す。 */
    private static Built build(ClientLevel level, int ox, int oz) {
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
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                int rockY = Integer.MIN_VALUE;
                for (int y = top; y >= minY; y--) {
                    if (isRock(level.getBlockState(pos.set(wx, y, wz)))) {
                        rockY = y;
                        break;
                    }
                }
                if (rockY == Integer.MIN_VALUE) {
                    continue;
                }
                data[at] = rockY + 1.0F;
                if (ModBiomes.isOvergrownLike(level.getBiome(pos.set(wx, rockY, wz)))) {
                    data[at + 1] = 1.0F;
                    any = true;
                }
            }
        }
        return new Built(ox, oz, data, any);
    }

    /** 描画のスレッド: 新しい地図をテクスチャへ載せる(AFTER_SKY、{@link CaveLights#upload} から)。 */
    static void upload() {
        Built b = shown;
        if (!uploadPending || b == null) {
            return;
        }
        uploadPending = false;
        if (buffer == null) {
            buffer = MemoryUtil.memAllocFloat(SIZE * SIZE * 4);
        }
        buffer.clear();
        buffer.put(b.data);
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
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        GlStateManager._bindTexture(previous);
    }

    /** 確かめ用の一行。 */
    static String describe() {
        Built b = shown;
        return b == null ? "地下の地図 なし" : String.format("地下の地図 原点 %d %d 過繁茂 %s", b.originX, b.originZ, b.anyOvergrown);
    }
}
