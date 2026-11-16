package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * <b>浅瀬と深みの色・岸の泡</b>(2026-11-12、ユーザー選択「浅瀬と深みの色・岸の泡」。水まわり ②。理由: バニラの水はどこも同じ色と透け方で、
 * 岸の浅瀬も海の深みも同じ板に見え、雲や光に比べて浮いていた)。
 *
 * <h2>深さの地図</h2>
 * カメラのまわり {@link #SIZE}×{@link #SIZE} 列(1 列 1 画素)について、列のいちばん上の水面から下へ水が何ブロック続くか(深さ)と、
 * 岸(水面に水の無い列)までの水平の距離を、裏のスレッド({@link ScanThread})で {@link #REBUILD_TICKS} tick ごとに調べる。
 * 列のたどり方は水底の網目の光({@link WaterCaustics})と同じ(空気・葉・当たり判定の無い物は抜け、固いブロックで止まる)が、空へ開いていない水も数える。
 * 画素: r = 深さ × 8(0〜31.9 ブロック)、g = 岸までの距離 × 32(0〜8 ブロック、それより遠いと 255)、b = 水面のブロック − 基準の高さ + 1、a = 128 + 水面の広さ(まわり 33 × 33 列のうち水の割合)× 127(水の列なら 128 以上、無ければ 0)。
 *
 * <h2>シェーダー</h2>
 * {@code shaders/include/ov_water.glsl} の {@code ovWaterDepth}(バニラの半透明と Sodium の地形)。水の上向きの面で、地図の水面と同じ高さの所だけ:
 * 浅いほど明るく透け、深いほど暗く濃く(深さの e 分の 1 = {@link #CLEAR_DEPTH})。岸から {@link #FOAM_WIDTH} ブロックほどに、寄せては返す泡の縁。
 * 地図の外は「やや深い水」の見え方に寄せる(地図の縁で見え方が切れないように)。数値はすべて仮決め。
 */
final class WaterDepth {

    static final int SIZE = 192;
    /** 地図の基準の高さ = カメラの何ブロック下か(b は 1〜255)。 */
    private static final int BELOW = 96;
    private static final int REBUILD_TICKS = 40;
    private static final int SCAN_DOWN = 96;
    private static final int MAX_DEPTH = 31;
    /** 水面の広さを数える四角の半径(ブロック。33 × 33 列)。仮決め(11-12 夜)。 */
    private static final int OPEN_RADIUS = 16;
    /** 泡が立ち始める・満ちる水面の広さ(まわりの水の列の割合)。仮決め。 */
    static final float FOAM_OPEN_FROM = 0.36F;
    static final float FOAM_OPEN_FULL = 0.48F;
    /** 岸の元にする陸の列: まわり (2 × SOURCE_RADIUS + 1) 四方のうち陸の割合が SOURCE_LAND 以上(11-13。水の中の幹で泡が立たないように)。仮決め。 */
    private static final int SOURCE_RADIUS = 3;
    static final float SOURCE_LAND = 0.25F;
    /** 地図の a: 岸の元でない陸の列の印(水の列は 128 以上、岸の元の陸と地図の外は 0)。 */
    private static final int LAND_NOT_SHORE = 40;
    /** 岸までの距離を数える上限(ブロック)。 */
    private static final float SHORE_MAX = 7.9F;

    /** 深さの e 分の 1(ブロック): 浅瀬の透け方。11-12: 2.5 では 10 ブロックの海で海藻が見えず「バニラの水は澄んだまま」に合わないので 5。濁り(水まわり ⑤)で汚染地域だけ短くする予定。仮決め。 */
    static final float CLEAR_DEPTH = 5.0F;
    /** 浅瀬・深みの不透明さと、深みの暗さ(元の色に掛ける)。仮決め。 */
    static final float ALPHA_SHALLOW = 0.4F;
    static final float ALPHA_DEEP = 0.85F;
    static final float DEEP_DARKEN = 0.7F;
    /** 地図の外の水の「透け具合」(0 = 深み 〜 1 = 浅瀬)。 */
    static final float OUTSIDE_CLARITY = 0.2F;
    /** 泡の色・強さ・幅(ブロック)。仮決め。 */
    static final float[] FOAM_COLOR = {0.9F, 0.94F, 0.96F};
    static final float FOAM_STRENGTH = 0.9F;
    static final float FOAM_WIDTH = 1.4F;

    private static final class Map {
        final int originX;
        final int originZ;
        final int baseY;
        final int[] pixels = new int[SIZE * SIZE];

        Map(int originX, int originZ, int baseY) {
            this.originX = originX;
            this.originZ = originZ;
            this.baseY = baseY;
        }
    }

    @Nullable
    private static CompletableFuture<Map> building;
    private static long buildStartTick = -1_000_000L;
    private static int buildFailures;
    @Nullable
    private static Map shown;
    private static boolean uploadPending;
    @Nullable
    private static DynamicTexture texture;
    private static boolean broken;
    /** テクスチャに載っている地図(水中の浮遊物 {@link WaterMotes} が読む。11-13)。載っていなければ null。 */
    @Nullable
    private static Map uploaded;

    static void clear() {
        building = null;
        buildStartTick = -1_000_000L;
        shown = null;
        uploaded = null;
    }

    /** 水中の浮遊物({@link WaterMotes})が深さの地図を読めるか。 */
    static boolean ready() {
        return !broken && texture != null && uploaded != null;
    }

    static int textureId() {
        return texture != null ? texture.getId() : 0;
    }

    /** 載っている地図の (originX, originZ, baseY)。{@link #ready()} のときだけ呼ぶ。 */
    static int originX() {
        return uploaded.originX;
    }

    static int originZ() {
        return uploaded.originZ;
    }

    static int baseY() {
        return uploaded.baseY;
    }

    static String describe() {
        Map m = shown;
        int water = 0;
        if (m != null) {
            for (int px : m.pixels) {
                if ((px >>> 24) >= 128) {
                    water++;
                }
            }
        }
        return String.format("深さの地図 %s 水の列 %d%s", m != null, water, broken ? " 停止" : "");
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, long ticks) {
        if (broken || !VisualQuality.waterLife()) {
            return;
        }
        if (building != null) {
            if (!building.isDone()) {
                return;
            }
            Map done = building.getNow(null);
            building = null;
            if (done != null) {
                shown = done;
                buildFailures = 0;
                uploadPending = true;
            }
        }
        if (ticks - buildStartTick < REBUILD_TICKS) {
            return;
        }
        buildStartTick = ticks;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Map m = new Map(Mth.floor(eye.x) - SIZE / 2, Mth.floor(eye.z) - SIZE / 2, Mth.floor(eye.y) - BELOW);
        building = CompletableFuture.supplyAsync(() -> build(level, m), ScanThread.EXECUTOR).exceptionally(e -> {
            if (++buildFailures >= 3) {
                broken = true;
                OvergrownVegetation.LOGGER.error("水の深さの地図を 3 回続けて作れなかったので、このセッションでは浅瀬の色と泡を使わない", e);
            } else {
                OvergrownVegetation.LOGGER.warn("水の深さの地図を作れなかった(次の回にもう一度): {}", e.toString());
            }
            return null;
        });
    }

    private static Map build(ClientLevel level, Map m) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] depth = new int[SIZE * SIZE];
        int[] surface = new int[SIZE * SIZE];
        boolean[] water = new boolean[SIZE * SIZE];
        for (int index = 0; index < SIZE * SIZE; index++) {
            int x = m.originX + index % SIZE;
            int z = m.originZ + index / SIZE;
            int s = surfaceY(level, m, x, z, pos);
            if (s == Integer.MIN_VALUE) {
                continue;
            }
            int d = 0;
            for (int y = s; y > s - MAX_DEPTH && level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER); y--) {
                d++;
            }
            water[index] = true;
            surface[index] = s;
            depth[index] = d;
        }
        // 水面の広さと岸の元を数える積み上げた表(2 次元の累積和。水の列の数)。
        int[] sum = new int[(SIZE + 1) * (SIZE + 1)];
        for (int j = 0; j < SIZE; j++) {
            int row = 0;
            for (int i = 0; i < SIZE; i++) {
                row += water[j * SIZE + i] ? 1 : 0;
                sum[(j + 1) * (SIZE + 1) + i + 1] = sum[j * (SIZE + 1) + i + 1] + row;
            }
        }
        // 岸までの距離: 岸の元の列からの 2 回なぞりの距離(縦横 1・斜め 1.41)。
        // 11-13(ユーザー「泡が変なところに発生している」。広い池の真ん中で、水に立つ木の幹のまわりに泡が立っていた): 水の無い列のうち、
        // まわり SOURCE_RADIUS の四角で陸が SOURCE_LAND 以上の列だけを岸の元にする(まっすぐな岸 約 0.57・岬の先 約 0.3、2 × 2 の幹 約 0.08・
        // 3 × 3 の幹 約 0.18)。水の中の幹・杭・1 ブロックの岩のまわりには泡が付かない。
        float[] shore = new float[SIZE * SIZE];
        for (int k = 0; k < shore.length; k++) {
            if (water[k]) {
                shore[k] = SHORE_MAX;
                continue;
            }
            int ci = k % SIZE;
            int cj = k / SIZE;
            int i0 = Math.max(0, ci - SOURCE_RADIUS);
            int j0 = Math.max(0, cj - SOURCE_RADIUS);
            int i1 = Math.min(SIZE, ci + SOURCE_RADIUS + 1);
            int j1 = Math.min(SIZE, cj + SOURCE_RADIUS + 1);
            int wet = sum[j1 * (SIZE + 1) + i1] - sum[j0 * (SIZE + 1) + i1] - sum[j1 * (SIZE + 1) + i0] + sum[j0 * (SIZE + 1) + i0];
            int all = (i1 - i0) * (j1 - j0);
            shore[k] = (all - wet) >= SOURCE_LAND * all ? 0.0F : SHORE_MAX;
        }
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int k = j * SIZE + i;
                if (!water[k]) {
                    continue;
                }
                float v = shore[k];
                if (i > 0) v = Math.min(v, shore[k - 1] + 1.0F);
                if (j > 0) v = Math.min(v, shore[k - SIZE] + 1.0F);
                if (i > 0 && j > 0) v = Math.min(v, shore[k - SIZE - 1] + 1.414F);
                if (i < SIZE - 1 && j > 0) v = Math.min(v, shore[k - SIZE + 1] + 1.414F);
                shore[k] = v;
            }
        }
        for (int j = SIZE - 1; j >= 0; j--) {
            for (int i = SIZE - 1; i >= 0; i--) {
                int k = j * SIZE + i;
                if (!water[k]) {
                    continue;
                }
                float v = shore[k];
                if (i < SIZE - 1) v = Math.min(v, shore[k + 1] + 1.0F);
                if (j < SIZE - 1) v = Math.min(v, shore[k + SIZE] + 1.0F);
                if (i < SIZE - 1 && j < SIZE - 1) v = Math.min(v, shore[k + SIZE + 1] + 1.414F);
                if (i > 0 && j < SIZE - 1) v = Math.min(v, shore[k + SIZE - 1] + 1.414F);
                shore[k] = v;
            }
        }
        // 水面の広さ(11-12 夜、ユーザー「泡がすべての水の縁に出るからかなり目立つ」→「広い水だけ」): まわり OPEN_RADIUS ブロックの四角のうち
        // 水の列の割合。海・大きな湖の岸で 0.45〜0.6、川で 0.2〜0.35、池で 0.05 ほど。上の積み上げた表で数える。
        for (int k = 0; k < SIZE * SIZE; k++) {
            if (!water[k]) {
                // 岸の元でない陸の列(水の中の幹など)は a = LAND_NOT_SHORE・g = 255(岸から遠い)にする。シェーダーは 4 列の補間で
                // 陸の列を岸までの距離 0 と読むので、印が無いと幹のすぐ隣に泡の輪が残る(ov_water.glsl の ovDepthTexel)。
                if (shore[k] > 0.0F) {
                    m.pixels[k] = (LAND_NOT_SHORE << 24) | (255 << 8);
                }
                continue;
            }
            int ci = k % SIZE;
            int cj = k / SIZE;
            int i0 = Math.max(0, ci - OPEN_RADIUS);
            int j0 = Math.max(0, cj - OPEN_RADIUS);
            int i1 = Math.min(SIZE, ci + OPEN_RADIUS + 1);
            int j1 = Math.min(SIZE, cj + OPEN_RADIUS + 1);
            int count = sum[j1 * (SIZE + 1) + i1] - sum[j0 * (SIZE + 1) + i1] - sum[j1 * (SIZE + 1) + i0] + sum[j0 * (SIZE + 1) + i0];
            float open = count / (float) ((i1 - i0) * (j1 - j0));
            int r = Mth.clamp(depth[k] * 8, 0, 255);
            int g = shore[k] >= SHORE_MAX ? 255 : Mth.clamp(Math.round(shore[k] * 32.0F), 0, 254);
            int b = Mth.clamp(surface[k] - m.baseY + 1, 1, 255);
            // a = 128 + 水面の広さ × 127(128 以上なら水の列)。
            int a = 128 + Mth.clamp(Math.round(open * 127.0F), 0, 127);
            m.pixels[k] = (a << 24) | (b << 16) | (g << 8) | r;
        }
        return m;
    }

    /** 列のいちばん上の水面のブロックの y。水が無い・固いブロックの下・落ちている水なら Integer.MIN_VALUE。 */
    private static int surfaceY(ClientLevel level, Map m, int x, int z, BlockPos.MutableBlockPos pos) {
        if (!level.hasChunkAt(pos.set(x, m.baseY, z))) {
            return Integer.MIN_VALUE;
        }
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, m.baseY + 254);
        int bottom = Math.max(m.baseY + 1, top - SCAN_DOWN);
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                return fluid.getValue(FlowingFluid.FALLING) ? Integer.MIN_VALUE : y;
            }
            BlockState state = level.getBlockState(pos);
            // 睡蓮の葉は当たり判定があるが水面の飾りなので素通りし、下の水を数える(11-13。岸と数えて泡が立っていた)。
            if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(com.yayuy.overgrownvegetation.ModTags.OVERGROWN_LEAVES) || state.is(net.minecraft.world.level.block.Blocks.LILY_PAD)
                    || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** 空を描いた後・地形の前に({@link Atmosphere} から)、地図と値を半透明の層のシェーダーへ(Sodium へは SodiumTerrain が写す)。 */
    static void upload(boolean on) {
        ShaderInstance shader = GameRenderer.getRendertypeTranslucentShader();
        if (shader == null) {
            return;
        }
        Map m = shown;
        if (broken || !on || !VisualQuality.waterLife() || SodiumTerrain.unsupported() || m == null) {
            shader.safeGetUniform("OvDepthA").set(0.0F, 0.0F, 1.0F, 0.0F);
            uploaded = null;
            return;
        }
        try {
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
                uploadPending = true;
            }
            if (uploadPending) {
                NativeImage image = texture.getPixels();
                if (image != null) {
                    for (int j = 0; j < SIZE; j++) {
                        for (int i = 0; i < SIZE; i++) {
                            image.setPixelRGBA(i, j, m.pixels[j * SIZE + i]);
                        }
                    }
                    texture.upload();
                    uploaded = m;
                }
                uploadPending = false;
            }
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
            float seconds = (float) ((Atmosphere.time(pt) / 20.0) % 600.0);
            shader.setSampler("OvDepthMap", texture.getId());
            shader.safeGetUniform("OvDepthA").set((float) (m.originX - cam.x), (float) (m.originZ - cam.z), (float) SIZE, 1.0F);
            shader.safeGetUniform("OvDepthB").set(CLEAR_DEPTH, ALPHA_SHALLOW, ALPHA_DEEP, DEEP_DARKEN);
            shader.safeGetUniform("OvDepthC").set((float) (m.baseY - cam.y), OUTSIDE_CLARITY, seconds, FOAM_WIDTH);
            shader.safeGetUniform("OvFoam").set(FOAM_COLOR[0], FOAM_COLOR[1], FOAM_COLOR[2], FOAM_STRENGTH);
            shader.safeGetUniform("OvFoamB").set(FOAM_OPEN_FROM, FOAM_OPEN_FULL, 0.0F, 0.0F);
        } catch (RuntimeException e) {
            broken = true;
            shader.safeGetUniform("OvDepthA").set(0.0F, 0.0F, 1.0F, 0.0F);
            OvergrownVegetation.LOGGER.error("浅瀬の色と泡で例外が出たので、このセッションでは使わない", e);
        }
    }

    private WaterDepth() {
    }
}
