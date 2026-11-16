package com.yayuy.overgrownvegetation.client.wall;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 蠢く壁の見た目の<b>試作</b>(2026-11-16、Fable。ユーザーが 3 案を比べて選ぶためのもの。選ばれたら残りの案を消して磨く)。
 *
 * <p>描くのは先頭の面だけ。中身はユーザーの決定(その 3)の 4 つ:
 * <ul>
 *   <li><b>根と蔦の濁流</b>: 面を細かく割って法線の向きへ波打たせ(数十センチ出入り)、接線の世界座標から取った絵を時刻で流す。
 *       上に蔦の層を別の向きへ流して重ねる。近くでは根の輪(小さな箱の鎖)が面から盛り上がる。</li>
 *   <li><b>飲まれた Mob の手足と顔</b>: バニラの Mob の絵の頭・腕・脚の箱を面から突き出し、蔦の層でくるむ(プレイヤーの形も)。</li>
 *   <li><b>脈打つ胞子嚢</b>: 小さな箱。膨らんで弾け(胞子の粒を吹く)、また膨らむ。</li>
 *   <li><b>光る筋</b>: 満光の層。拍に合わせて光の帯が走る。</li>
 * </ul>
 * 案({@link Style})はどれも 4 つ全部を持ち、主役と動きの強さが違う。
 *
 * <p><b>飾りの鍵は「面の向き + 接線 2 軸の升の座標」</b>(法線の向きの座標は入れない)。先頭の升は約 4 tick ごとに次の層へ入れ替わるので、
 * 升の座標から決めると顔も胞子嚢も 0.2 秒ごとに入れ替わる。こうしておくと、面が進んでも同じ顔が同じ所に乗ったまま押してくる。
 *
 * <p>画素は 1/16 ブロック(絵は 4 ブロックで 64 画素。箱の部品は 1 単位 = 1/16 ブロック)。胞子嚢だけは脈で 1 割ほど伸び縮みする。
 */
public final class TorrentCreepingWallRenderer implements CreepingWallRenderer {

    /** 案 1 つ。数はどれも【仮】(Fable 11-16。ユーザーが選んだ後に design json へ移す)。 */
    public record Style(String name, ResourceLocation base, float amp, float flowU, float flowV, float waveSpeed,
                        float limbChance, float sacChance, int sacCluster, float arcChance, int arcKind,
                        float veinBase, float veinPulse, int minBlockLight, float tintR, float tintG, float tintB) {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "textures/entity/creeping_wall/" + name + ".png");
    }

    private static final ResourceLocation ROOTS_THICK = tex("wall_roots_thick");
    private static final ResourceLocation ROOTS_FINE = tex("wall_roots_fine");
    private static final ResourceLocation VINES = tex("wall_vines");
    private static final ResourceLocation VEINS = tex("wall_veins");
    private static final ResourceLocation PARTS = tex("wall_parts");
    private static final ResourceLocation PARTS_GLOW = tex("wall_parts_glow");

    /** 案 A「根の濁流」: 太い根が主役で、波と流れが強い。顔・胞子嚢・光は控えめ。 */
    public static final Style STYLE_A = new Style("a", ROOTS_THICK, 0.34F, 0.012F, 0.030F, 1.0F,
            0.07F, 0.06F, 1, 0.55F, 1, 0.35F, 0.85F, 5, 1.00F, 1.00F, 1.00F);
    /** 案 B「飲まれた者たち」: 細い蔦の網から、手足と顔がたくさん突き出て揺れる。波は中くらい。 */
    public static final Style STYLE_B = new Style("b", ROOTS_FINE, 0.24F, 0.008F, 0.016F, 0.8F,
            0.34F, 0.08F, 1, 0.16F, 2, 0.35F, 0.85F, 5, 0.95F, 1.00F, 0.95F);
    /** 案 C「胞子嚢と光」: 胞子嚢の房が脈打って弾け、光る筋が強い。暗い洞窟で奥から光って近づく。 */
    public static final Style STYLE_C = new Style("c", ROOTS_FINE, 0.28F, 0.010F, 0.020F, 1.2F,
            0.09F, 0.26F, 3, 0.14F, 2, 0.65F, 1.40F, 7, 0.90F, 1.00F, 0.85F);

    /** 今の案(確かめ用の切り替えは devbridge の {@code wallstyle})。 */
    public static volatile Style style = STYLE_A;

    private static final double RADIUS = 40.0D;
    private static final int MAX_FACES = 8_000;
    /** 距離の段(ブロック): L0 まで 4×4 に割る・L1 まで 2×2 と飾り・L2 まで蔦の層・その先は土台と光る筋だけ。 */
    private static final double L0 = 7.0D, L1 = 13.0D, L2 = 26.0D;
    private static final float PX = 1.0F / 16.0F;
    private static final float TILE = 4.0F;

    // ---- 面の一覧(毎フレーム作り直す。配列は使い回す) ----
    private final int[] fx = new int[MAX_FACES], fy = new int[MAX_FACES], fz = new int[MAX_FACES];
    private final byte[] fdir = new byte[MAX_FACES], flod = new byte[MAX_FACES];
    private final float[] fprog = new float[MAX_FACES];
    private final float[] fd2 = new float[MAX_FACES];
    private final int[] flight = new int[MAX_FACES], fh = new int[MAX_FACES];
    private final long[] ftick = new long[MAX_FACES];
    private final ClientCreepingWall.View[] fview = new ClientCreepingWall.View[MAX_FACES];
    /** 面の格子の高さ(法線の向きの出入り)。近く 5×5・中 3×3・遠く 2×2。 */
    private final float[] heights = new float[MAX_FACES * 25];
    private int faceCount, heightCount;

    private MultiBufferSource.BufferSource buffers;
    private float t, lastT = Float.NaN, beat, partial;
    private double camX, camY, camZ;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    // ---- 計測 ----
    public static long lastNanos, maxNanos;
    public static int lastFaces, lastNear;
    private static long sumNanos, sumFaces, sumNear;
    private static int frames;

    private static final Direction[] DIRS = Direction.values();
    /** 向きごとの法線 n・接線 u・w。 */
    private static final int[][] N = new int[6][3], U = new int[6][3], W = new int[6][3];

    static {
        for (Direction d : DIRS) {
            int i = d.ordinal();
            N[i] = new int[]{d.getStepX(), d.getStepY(), d.getStepZ()};
            if (d.getAxis() == Direction.Axis.Y) {
                U[i] = new int[]{1, 0, 0};
                W[i] = new int[]{0, 0, 1};
            } else if (d.getAxis() == Direction.Axis.X) {
                U[i] = new int[]{0, 0, 1};
                W[i] = new int[]{0, 1, 0};
            } else {
                U[i] = new int[]{1, 0, 0};
                W[i] = new int[]{0, 1, 0};
            }
        }
    }

    @Override
    public void render(RenderLevelStageEvent event, ClientLevel level, Camera camera, Iterable<ClientCreepingWall.View> views, float time) {
        long t0 = System.nanoTime();
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 21));
        }
        Style st = style;
        Vec3 cam = camera.getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        // 時刻は 1 時間で折り返す(大きな時刻を float にすると絵の流れが粗くなる)。
        partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        t = (float) (level.getGameTime() % 72000L) + partial;
        float bs = Math.max(0.0F, Mth.sin(t * (Mth.TWO_PI / 24.0F)));
        beat = bs * bs * bs;
        collect(event.getFrustum(), level, views, time, st);

        int near = 0;
        for (int f = 0; f < faceCount; f++) {
            if (flod[f] <= 1) {
                near++;
            }
        }
        // 1. 土台(根の濁流)と側面
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(st.base));
        for (int f = 0; f < faceCount; f++) {
            drawSurface(vc, f, st, 0);
            if (flod[f] <= 1) {
                drawSkirts(vc, level, f, st);
            }
        }
        // 2. 上に重なる蔦
        vc = buffers.getBuffer(RenderType.entityCutoutNoCull(VINES));
        for (int f = 0; f < faceCount; f++) {
            if (flod[f] <= 2) {
                drawSurface(vc, f, st, 1);
            }
        }
        // 3. 小さな箱の部品(根の輪・胞子嚢)
        vc = buffers.getBuffer(RenderType.entityCutoutNoCull(PARTS));
        for (int f = 0; f < faceCount; f++) {
            if (fd2[f] < 100.0F) {
                drawArc(vc, f, st);
            }
            if (flod[f] <= 1) {
                drawSacs(vc, level, f, st, false);
            }
        }
        // 4. 飲まれた Mob(絵ごとにまとめて)と、それをくるむ蔦
        if (near > 0) {
            for (int tex = 0; tex < LIMB_TEX.length; tex++) {
                vc = null;
                for (int f = 0; f < faceCount; f++) {
                    if (flod[f] > 1) {
                        continue;
                    }
                    int kind = limbKind(f, st);
                    if (kind < 0 || LIMBS[kind][0] != tex) {
                        continue;
                    }
                    if (vc == null) {
                        vc = buffers.getBuffer(RenderType.entityCutoutNoCull(LIMB_TEX[tex]));
                    }
                    drawLimb(vc, f, kind, false);
                }
            }
            vc = buffers.getBuffer(RenderType.entityCutoutNoCull(VINES));
            for (int f = 0; f < faceCount; f++) {
                if (fd2[f] < 81.0F) {
                    int kind = limbKind(f, st);
                    if (kind >= 0) {
                        drawLimb(vc, f, kind, true);
                    }
                }
            }
        }
        // 5. 光る層(筋・胞子嚢の中の光)
        vc = buffers.getBuffer(RenderType.eyes(VEINS));
        for (int f = 0; f < faceCount; f++) {
            drawSurface(vc, f, st, 2);
        }
        vc = buffers.getBuffer(RenderType.eyes(PARTS_GLOW));
        for (int f = 0; f < faceCount; f++) {
            if (flod[f] <= 1) {
                drawSacs(vc, level, f, st, true);
            }
        }
        buffers.endBatch();
        lastT = t;

        lastFaces = faceCount;
        lastNear = near;
        lastNanos = System.nanoTime() - t0;
        maxNanos = Math.max(maxNanos, lastNanos);
        sumNanos += lastNanos;
        sumFaces += faceCount;
        sumNear += near;
        if (++frames >= 200) {
            OvergrownVegetation.LOGGER.info("[creeping_wall] 案 {} 描画 平均 {} ms・最大 {} ms・面 平均 {}(近く {})・FPS {}", st.name,
                    String.format("%.3f", sumNanos / 1e6 / frames), String.format("%.3f", maxNanos / 1e6), sumFaces / frames, sumNear / frames,
                    Minecraft.getInstance().getFps());
            frames = 0;
            sumNanos = 0;
            sumFaces = 0;
            sumNear = 0;
            maxNanos = 0;
        }
    }

    // ------------------------------------------------------------------ 面を集める

    // ---- 面の覚え(tick ごとに集め直す。毎フレーム集め直すと、広い空洞では先頭の升 × 6 回のブロック読みだけで数 ms かかる) ----
    private int[] kx = new int[2048], ky = new int[2048], kz = new int[2048], klight = new int[2048];
    private byte[] kdir = new byte[2048];
    private long[] ktick = new long[2048];
    private float[] kprog = new float[2048];
    private ClientCreepingWall.View[] kview = new ClientCreepingWall.View[2048];
    private int kCount;
    private long kTime = Long.MIN_VALUE;
    private double kCamX, kCamY, kCamZ;
    private Style kStyle;

    private void recollect(ClientLevel level, Iterable<ClientCreepingWall.View> views, float time, Style st) {
        kCount = 0;
        for (ClientCreepingWall.View v : views) {
            v.collectFaces(level, camX, camY, camZ, RADIUS + 6.0D, time, face -> {
                if (kCount >= MAX_FACES * 3) {
                    return;
                }
                if (kCount == kx.length) {
                    int n = kx.length * 2;
                    kx = java.util.Arrays.copyOf(kx, n);
                    ky = java.util.Arrays.copyOf(ky, n);
                    kz = java.util.Arrays.copyOf(kz, n);
                    klight = java.util.Arrays.copyOf(klight, n);
                    kdir = java.util.Arrays.copyOf(kdir, n);
                    ktick = java.util.Arrays.copyOf(ktick, n);
                    kprog = java.util.Arrays.copyOf(kprog, n);
                    kview = java.util.Arrays.copyOf(kview, n);
                }
                long cell = face.cell();
                int k = kCount++;
                int d = face.dir().ordinal();
                kx[k] = BlockPos.getX(cell);
                ky[k] = BlockPos.getY(cell);
                kz[k] = BlockPos.getZ(cell);
                kdir[k] = (byte) d;
                ktick[k] = v.front.get(cell);
                kprog[k] = face.progress();
                kview[k] = v;
                scratch.set(kx[k] + N[d][0], ky[k] + N[d][1], kz[k] + N[d][2]);
                int light = LevelRenderer.getLightColor(level, scratch);
                klight[k] = (light & 0xFFFF0000) | Math.max(light & 0xFFFF, st.minBlockLight << 4);
            });
        }
        kTime = level.getGameTime();
        kCamX = camX;
        kCamY = camY;
        kCamZ = camZ;
        kStyle = st;
    }

    private void collect(Frustum frustum, ClientLevel level, Iterable<ClientCreepingWall.View> views, float time, Style st) {
        double mx = camX - kCamX, my = camY - kCamY, mz = camZ - kCamZ;
        if (kTime != level.getGameTime() || kStyle != st || mx * mx + my * my + mz * mz > 16.0D) {
            recollect(level, views, time, st);
        }
        faceCount = 0;
        heightCount = 0;
        float now = level.getGameTime() + partial;
        for (int k = 0; k < kCount && faceCount < MAX_FACES; k++) {
            int x = kx[k], y = ky[k], z = kz[k], d = kdir[k];
            double dx = x + 0.5 - camX, dy = y + 0.5 - camY, dz = z + 0.5 - camZ;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > RADIUS * RADIUS) {
                continue;
            }
            // 裏向き(カメラが面の後ろ側にある)は描かない。塊の向こう側の面はどうせ隠れる
            if ((dx + N[d][0] * 0.5) * N[d][0] + (dy + N[d][1] * 0.5) * N[d][1] + (dz + N[d][2] * 0.5) * N[d][2] > 0.1D) {
                continue;
            }
            if (frustum != null && !frustum.isVisible(new AABB(x - 0.6, y - 0.6, z - 0.6, x + 1.6, y + 1.6, z + 1.6))) {
                continue;
            }
            ClientCreepingWall.View v = kview[k];
            int f = faceCount++;
            fx[f] = x;
            fy[f] = y;
            fz[f] = z;
            fdir[f] = (byte) d;
            ftick[f] = ktick[k];
            // 進み具合は届いた時刻から自分で出す。データの側は 0.85 で頭打ちなので、隣の列が先に次の層へ入ると 1 ブロック近い段差になる。
            // 1.2 まで伸ばすと、次の層の面(0 から始まる)とほぼつながる。止まった後はデータの値のまま。
            fprog[f] = v.isStopped() ? kprog[k] : Mth.clamp((now - ktick[k]) * v.blocksPerTick, 0.0F, 1.2F);
            fview[f] = v;
            int lod = d2 < L0 * L0 ? 0 : d2 < L1 * L1 ? 1 : d2 < L2 * L2 ? 2 : 3;
            flod[f] = (byte) lod;
            flight[f] = klight[k];
            fd2[f] = (float) d2;
            // 格子の高さ
            int n = lod == 0 ? 4 : lod == 1 ? 2 : 1;
            fh[f] = heightCount;
            int a0 = x * U[d][0] + y * U[d][1] + z * U[d][2], b0 = x * W[d][0] + y * W[d][1] + z * W[d][2];
            for (int j = 0; j <= n; j++) {
                for (int i = 0; i <= n; i++) {
                    heights[heightCount++] = height(a0 + (float) i / n, b0 + (float) j / n, d, st);
                }
            }
        }
    }

    private static void vtx(VertexConsumer vc, float x, float y, float z, float r, float g, float b, float u, float v, int light, float nx, float ny, float nz) {
        int color = 0xFF000000 | (int) (Math.min(1.0F, r) * 255.0F) << 16 | (int) (Math.min(1.0F, g) * 255.0F) << 8 | (int) (Math.min(1.0F, b) * 255.0F);
        vc.addVertex(x, y, z, color, u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }

    /**
     * 面の出入り(ブロック)。接線の世界座標 (a, b) と向きと時刻だけで決まるので、同じ平面の隣の面とつながる。
     * 0 〜 約 1.3 × amp。
     */
    private float height(float a, float b, int d, Style st) {
        float ph = d * 1.7F, ts = t * st.waveSpeed;
        // 座標は大きいので、周期の中へ折ってから三角関数へ(float の粗さで波が跳ばないように)
        float am = a - 64.0F * Mth.floor(a / 64.0F), bm = b - 64.0F * Mth.floor(b / 64.0F);
        float s1 = Mth.sin(am * 1.9635F + ts * 0.07F + ph + 1.3F * Mth.sin(bm * 1.0799F - ts * 0.045F));
        float s2 = Mth.sin(bm * 2.2580F - ts * 0.06F + ph * 0.7F + 1.1F * Mth.sin(am * 0.7854F + ts * 0.035F));
        float s3 = Mth.sin((am + bm) * 4.1233F + ts * 0.16F);
        return st.amp * (0.52F + 0.26F * s1 + 0.20F * s2 + 0.10F * s3 + 0.22F * beat);
    }

    // ------------------------------------------------------------------ 面(土台・蔦・光る筋)

    /** layer 0 = 土台、1 = 蔦(1 画素手前)、2 = 光る筋(土台と同じ位置)。 */
    private void drawSurface(VertexConsumer vc, int f, Style st, int layer) {
        int d = fdir[f], lod = flod[f];
        int n = lod == 0 ? 4 : lod == 1 ? 2 : 1;
        int step = 1; // 蔦は、近くでも 2×2 に間引く(光る筋は土台と同じ格子。粗いと土台の裏へ潜る)
        if (layer == 1 && n == 4) {
            step = 2;
        }
        int[] nn = N[d], uu = U[d], ww = W[d];
        float bx = (float) (fx[f] + 0.5 + nn[0] * 0.5 - camX), by = (float) (fy[f] + 0.5 + nn[1] * 0.5 - camY), bz = (float) (fz[f] + 0.5 + nn[2] * 0.5 - camZ);
        int a0 = fx[f] * uu[0] + fy[f] * uu[1] + fz[f] * uu[2], b0 = fx[f] * ww[0] + fy[f] * ww[1] + fz[f] * ww[2];
        float au = Math.floorMod(a0, 4), bv = Math.floorMod(b0, 4);
        float su, sv, lift;
        if (layer == 1) {
            su = wrap(-st.flowU * 0.7F * t + 1.7F);
            sv = wrap(st.flowV * 1.5F * t + 0.9F);
            lift = PX;
        } else {
            su = wrap(st.flowU * t);
            sv = wrap(st.flowV * t);
            lift = layer == 2 ? 0.012F : 0.0F; // 光る筋は土台のすぐ手前(同じ奥行きに重ねると、正面から見たときに消えた)
        }
        float prog = fprog[f] + lift;
        int light = layer == 2 ? 0xF000F0 : flight[f];
        // 光る層(RenderType.eyes)は裏面を捨てる。u × w が法線と逆を向く向き(上・北・東)では頂点の並びを逆にする
        boolean flip = layer == 2 && (d == 1 || d == 2 || d == 5);
        int h0 = fh[f];
        for (int j = 0; j < n; j += step) {
            for (int i = 0; i < n; i += step) {
                for (int k0 = 0; k0 < 4; k0++) {
                    int k = flip ? (4 - k0) & 3 : k0;
                    int ii = i + (k == 1 || k == 2 ? step : 0), jj = j + (k >= 2 ? step : 0);
                    float s = (float) ii / n - 0.5F, tt = (float) jj / n - 0.5F;
                    float h = heights[h0 + jj * (n + 1) + ii];
                    float push = prog + h;
                    float px = bx + uu[0] * s + ww[0] * tt + nn[0] * push;
                    float py = by + uu[1] * s + ww[1] * tt + nn[1] * push;
                    float pz = bz + uu[2] * s + ww[2] * tt + nn[2] * push;
                    float u = (au + s + 0.5F + su) / TILE, v = (bv + tt + 0.5F + sv) / TILE;
                    float r, g, b;
                    if (layer == 2) {
                        float a = a0 + s + 0.5F, bb = b0 + tt + 0.5F;
                        float band = Math.max(0.0F, Mth.sin(Math.floorMod(a0, 64) * 0.9F + (s + 0.5F) * 0.9F + (Math.floorMod(b0, 64) + tt + 0.5F) * 0.6F - t * 0.21F + d));
                        float glow = Math.min(1.0F, st.veinBase + st.veinPulse * (0.55F * band * band + 0.45F * beat));
                        r = g = b = glow;
                    } else {
                        float k2 = 0.50F + 0.50F * Mth.clamp(h / (st.amp * 1.1F), 0.0F, 1.0F); // 奥まった所は暗い
                        if (layer == 1) {
                            k2 = 0.75F + 0.25F * k2;
                        }
                        r = st.tintR * k2;
                        g = st.tintG * k2;
                        b = st.tintB * k2;
                    }
                    vtx(vc, px, py, pz, r, g, b, u, v, light, nn[0], nn[1], nn[2]);
                }
            }
        }
    }

    private static float wrap(float v) {
        return v - TILE * Mth.floor(v / TILE);
    }

    /**
     * 側面(面の縁から升の面まで)。隣の升と届いた時刻が違う・隣が空いている、で面の縁に段差ができる所だけ。岩に接した縁は描かない。
     */
    private void drawSkirts(VertexConsumer vc, ClientLevel level, int f, Style st) {
        int d = fdir[f];
        int n = flod[f] == 0 ? 4 : 2;
        int[] nn = N[d], uu = U[d], ww = W[d];
        int x = fx[f], y = fy[f], z = fz[f];
        float bx = (float) (x + 0.5 + nn[0] * 0.5 - camX), by = (float) (y + 0.5 + nn[1] * 0.5 - camY), bz = (float) (z + 0.5 + nn[2] * 0.5 - camZ);
        int a0 = x * uu[0] + y * uu[1] + z * uu[2], b0 = x * ww[0] + y * ww[1] + z * ww[2];
        float au = Math.floorMod(a0, 4), bv = Math.floorMod(b0, 4);
        float su = wrap(st.flowU * t), sv = wrap(st.flowV * t);
        int h0 = fh[f];
        int light = flight[f];
        ClientCreepingWall.View view = fview[f];
        for (int e = 0; e < 4; e++) {
            // e: 0 = −u の縁、1 = +u、2 = −w、3 = +w
            int[] tt = e < 2 ? uu : ww;
            int sg = (e & 1) == 0 ? -1 : 1;
            int qx = x + tt[0] * sg, qy = y + tt[1] * sg, qz = z + tt[2] * sg;
            long q = BlockPos.asLong(qx, qy, qz);
            if (view.front.containsKey(q) && view.front.get(q) == ftick[f]) {
                continue; // 同じ層の隣: 段差なし
            }
            scratch.set(qx + nn[0], qy + nn[1], qz + nn[2]);
            BlockState s = level.getBlockState(scratch);
            if (!s.isAir() && s.isSolidRender(level, scratch)) {
                continue; // 岩に接した縁
            }
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 4; k++) {
                    int idx = i + (k == 1 || k == 2 ? 1 : 0);
                    boolean back = k >= 2;
                    int ii, jj;
                    if (e < 2) {
                        ii = sg < 0 ? 0 : n;
                        jj = idx;
                    } else {
                        ii = idx;
                        jj = sg < 0 ? 0 : n;
                    }
                    float sx = (float) ii / n - 0.5F, sy = (float) jj / n - 0.5F;
                    float h = heights[h0 + jj * (n + 1) + ii];
                    float push = back ? -0.05F : fprog[f] + h;
                    float px = bx + uu[0] * sx + ww[0] * sy + nn[0] * push;
                    float py = by + uu[1] * sx + ww[1] * sy + nn[1] * push;
                    float pz = bz + uu[2] * sx + ww[2] * sy + nn[2] * push;
                    // 絵は縁から奥へ折り返して続ける
                    float fold = back ? fprog[f] + h + 0.05F : 0.0F;
                    float u = (au + sx + 0.5F + su + (e < 2 ? sg * fold : 0.0F)) / TILE;
                    float v = (bv + sy + 0.5F + sv + (e >= 2 ? sg * fold : 0.0F)) / TILE;
                    float k2 = back ? 0.35F : 0.6F;
                    vtx(vc, px, py, pz, st.tintR * k2, st.tintG * k2, st.tintB * k2, u, v, light, tt[0] * sg, tt[1] * sg, tt[2] * sg);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 飾りの鍵

    /** 面の鍵(向き + 接線 2 軸の升の座標)から決まる乱数。 */
    private int key(int f, int salt) {
        int d = fdir[f];
        int a = fx[f] * U[d][0] + fy[f] * U[d][1] + fz[f] * U[d][2], b = fx[f] * W[d][0] + fy[f] * W[d][1] + fz[f] * W[d][2];
        int h = a * 73856093 ^ b * 19349663 ^ (d + 1) * 83492791 ^ salt * 0x9E3779B9;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private static float frac(int h) {
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** 面の上の点 (s, w は −0.5〜0.5) の、カメラからの位置を out へ。面の出入りも入れる。 */
    private void anchor(int f, float s, float w, float extra, float[] out) {
        int d = fdir[f];
        int[] nn = N[d], uu = U[d], ww = W[d];
        int a0 = fx[f] * uu[0] + fy[f] * uu[1] + fz[f] * uu[2], b0 = fx[f] * ww[0] + fy[f] * ww[1] + fz[f] * ww[2];
        float push = fprog[f] + height(a0 + s + 0.5F, b0 + w + 0.5F, d, style) + extra;
        out[0] = (float) (fx[f] + 0.5 + nn[0] * 0.5 - camX) + uu[0] * s + ww[0] * w + nn[0] * push;
        out[1] = (float) (fy[f] + 0.5 + nn[1] * 0.5 - camY) + uu[1] * s + ww[1] * w + nn[1] * push;
        out[2] = (float) (fz[f] + 0.5 + nn[2] * 0.5 - camZ) + uu[2] * s + ww[2] * w + nn[2] * push;
    }

    private final float[] o = new float[3], va = new float[3], vb = new float[3], vcx = new float[3], p0 = new float[3], p1 = new float[3];

    // ------------------------------------------------------------------ 根の輪

    /** 面から盛り上がる根の輪(5 節の箱の鎖)。ゆっくり這う。 */
    private void drawArc(VertexConsumer vc, int f, Style st) {
        int h = key(f, 11);
        if (frac(h) >= st.arcChance) {
            return;
        }
        int d = fdir[f];
        int[] nn = N[d], uu = U[d], ww = W[d];
        int h2 = key(f, 12);
        float ang = frac(h2) * Mth.TWO_PI;
        float ca = Mth.cos(ang), sa = Mth.sin(ang);
        // 輪の向き(面の中)
        float dx = uu[0] * ca + ww[0] * sa, dy = uu[1] * ca + ww[1] * sa, dz = uu[2] * ca + ww[2] * sa;
        int th = st.arcKind == 1 ? ((h2 >>> 8 & 1) == 0 ? 4 : 3) : ((h2 >>> 8 & 3) == 0 ? 3 : 2);
        int texV = th == 4 ? 8 : th == 3 ? 0 : 18;
        float radius = (6.0F + frac(h2 >>> 3) * 4.0F) * PX * (st.arcKind == 1 ? 1.25F : 1.0F);
        float crawl = Mth.sin(t * 0.05F * st.waveSpeed + frac(h) * 6.28F);
        float rise = (3.5F + frac(h2 >>> 5) * 3.0F) * PX * (0.85F + 0.25F * crawl + 0.3F * beat);
        float slide = crawl * 2.5F * PX;
        float js = (frac(h >>> 4) - 0.5F) * 0.3F, jw = (frac(h >>> 9) - 0.5F) * 0.3F;
        anchor(f, js, jw, -1.5F * PX, o);
        float k2 = 0.85F;
        for (int i = 0; i < 5; i++) {
            float f0 = Mth.PI * i / 5.0F, f1 = Mth.PI * (i + 1) / 5.0F;
            float c0 = Mth.cos(f0) * radius + slide, s0 = Mth.sin(f0) * rise, c1 = Mth.cos(f1) * radius + slide, s1 = Mth.sin(f1) * rise;
            p0[0] = o[0] + dx * c0 + nn[0] * s0;
            p0[1] = o[1] + dy * c0 + nn[1] * s0;
            p0[2] = o[2] + dz * c0 + nn[2] * s0;
            p1[0] = o[0] + dx * c1 + nn[0] * s1;
            p1[1] = o[1] + dy * c1 + nn[1] * s1;
            p1[2] = o[2] + dz * c1 + nn[2] * s1;
            float lx = p1[0] - p0[0], ly = p1[1] - p0[1], lz = p1[2] - p0[2];
            float len = Mth.sqrt(lx * lx + ly * ly + lz * lz);
            if (len < 1.0e-4F) {
                continue;
            }
            va[0] = lx / len;
            va[1] = ly / len;
            va[2] = lz / len;
            // 節の「上」= 法線から節の向きの成分を抜いたもの
            float dot = nn[0] * va[0] + nn[1] * va[1] + nn[2] * va[2];
            vb[0] = nn[0] - va[0] * dot;
            vb[1] = nn[1] - va[1] * dot;
            vb[2] = nn[2] - va[2] * dot;
            float bl = Mth.sqrt(vb[0] * vb[0] + vb[1] * vb[1] + vb[2] * vb[2]);
            if (bl < 1.0e-3F) {
                vb[0] = dx;
                vb[1] = dy;
                vb[2] = dz;
                bl = 1.0F;
            }
            vb[0] /= bl;
            vb[1] /= bl;
            vb[2] /= bl;
            cross(va, vb, vcx);
            float lpx = len / PX + 0.8F;
            box(vc, p0, va, vb, vcx, -0.4F, lpx - 0.4F, -th * 0.5F, th * 0.5F, -th * 0.5F, th * 0.5F,
                    0, texV, 6, th, th, 64, 32, k2, k2, k2, flight[f]);
        }
    }

    private static void cross(float[] a, float[] b, float[] out) {
        out[0] = a[1] * b[2] - a[2] * b[1];
        out[1] = a[2] * b[0] - a[0] * b[2];
        out[2] = a[0] * b[1] - a[1] * b[0];
    }

    // ------------------------------------------------------------------ 胞子嚢

    /** 脈打つ胞子嚢。膨らみ切ると弾けて胞子を吹き、しぼんでまた膨らむ。glow = 中の光の層。 */
    private void drawSacs(VertexConsumer vc, ClientLevel level, int f, Style st, boolean glow) {
        int h = key(f, 21);
        float r = frac(h);
        if (r < st.limbChance || r >= st.limbChance + st.sacChance) {
            return; // 手足の升には置かない
        }
        int d = fdir[f];
        int[] nn = N[d], uu = U[d], ww = W[d];
        int count = 1 + (st.sacCluster > 1 ? Math.floorMod(h >>> 6, st.sacCluster) : 0);
        for (int i = 0; i < count; i++) {
            int hi = key(f, 22 + i);
            float js = (frac(hi) - 0.5F) * 0.62F, jw = (frac(hi >>> 5) - 0.5F) * 0.62F;
            float period = 170.0F + frac(hi >>> 9) * 260.0F, phase = frac(hi >>> 3) * period;
            float cyc = (t + phase) / period;
            float c = cyc - Mth.floor(cyc);
            float size, bright;
            if (c < 0.03F) {
                size = 0.35F;
                bright = 1.0F - c / 0.03F * 0.8F;
            } else if (c < 0.30F) {
                size = 0.35F + 0.65F * (c - 0.03F) / 0.27F;
                bright = 0.2F;
            } else {
                float swell = (c - 0.30F) / 0.70F;
                float pulse = Mth.sin(t * (0.18F + 0.5F * swell * swell) + phase);
                size = 1.0F + 0.22F * swell + 0.07F * pulse * (0.4F + swell);
                bright = 0.25F + 0.55F * swell * swell + 0.2F * Math.max(0.0F, pulse) + (swell > 0.96F ? 0.6F : 0.0F);
            }
            float big = 0.85F + 0.45F * frac(hi >>> 13);
            size *= big;
            anchor(f, js, jw, -1.0F * PX, o);
            if (!glow && !Float.isNaN(lastT) && t > lastT && t - lastT < 4.0F && Mth.floor((lastT + phase) / period) != Mth.floor(cyc)) {
                burst(level, o, nn, hi); // 弾けた瞬間(1 回だけ)
            }
            for (int k = 0; k < 3; k++) {
                va[k] = uu[k] * size;
                vb[k] = nn[k] * size;
                vcx[k] = ww[k] * size;
            }
            float cr, cg, cb;
            int light;
            if (glow) {
                cr = cg = cb = Math.min(1.0F, bright * (0.6F + 0.4F * (st.veinBase + st.veinPulse) / 1.7F + 0.3F));
                light = 0xF000F0;
            } else {
                cr = cg = cb = 1.0F;
                light = flight[f];
            }
            float e = glow ? 0.2F : 0.0F; // 光る層は少しふくらませる(同じ奥行きに重ねない)
            if (!glow) {
                box(vc, o, va, vb, vcx, -4, 4, 0, 2, -4, 4, 24, 20, 8, 2, 8, 64, 32, cr, cg, cb, light); // 根元(暗い)
            }
            box(vc, o, va, vb, vcx, -3 - e, 3 + e, 0, 5 + e, -3 - e, 3 + e, 24, 0, 6, 5, 6, 64, 32, cr, cg, cb, light);
            box(vc, o, va, vb, vcx, -2 - e, 2 + e, 5, 7 + e + e, -2 - e, 2 + e, 24, 12, 4, 2, 4, 64, 32, cr, cg, cb, light);
        }
    }

    private void burst(ClientLevel level, float[] rel, int[] nn, int seed) {
        double x = rel[0] + camX + nn[0] * 0.35, y = rel[1] + camY + nn[1] * 0.35, z = rel[2] + camZ + nn[2] * 0.35;
        java.util.Random rnd = new java.util.Random(seed ^ Float.floatToIntBits(t));
        for (int i = 0; i < 12; i++) {
            double sp = 0.05 + rnd.nextDouble() * 0.10;
            level.addParticle(ModParticles.SPORE_JET.get(), x, y, z,
                    nn[0] * sp + (rnd.nextDouble() - 0.5) * 0.08, nn[1] * sp + (rnd.nextDouble() - 0.5) * 0.08 + 0.01, nn[2] * sp + (rnd.nextDouble() - 0.5) * 0.08);
        }
    }

    // ------------------------------------------------------------------ 飲まれた Mob

    private static final ResourceLocation[] LIMB_TEX = {
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/skeleton/skeleton.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/cow/cow.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/pig/pig.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/creeper/creeper.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/spider/spider.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"),
    };
    private static final int HEAD = 0, ARM = 1, LEGS = 2;
    /** 絵の番号・絵の幅・高さ・箱の展開の左上 u v・箱の大きさ dx dy dz・出し方・重み。 */
    private static final int[][] LIMBS = {
            {0, 64, 64, 0, 0, 8, 8, 8, HEAD, 5},    // ゾンビの頭
            {0, 64, 64, 40, 16, 4, 12, 4, ARM, 6},  // ゾンビの腕
            {1, 64, 32, 0, 0, 8, 8, 8, HEAD, 3},    // スケルトンの頭
            {1, 64, 32, 40, 16, 2, 12, 2, ARM, 4},  // スケルトンの腕
            {2, 64, 32, 0, 0, 8, 8, 6, HEAD, 3},    // 牛の頭
            {3, 64, 32, 0, 0, 8, 8, 8, HEAD, 2},    // 豚の頭
            {4, 64, 32, 0, 0, 8, 8, 8, HEAD, 2},    // クリーパーの頭
            {5, 64, 32, 18, 0, 16, 2, 2, LEGS, 4},  // クモの脚
            {6, 64, 64, 0, 0, 8, 8, 8, HEAD, 2},    // プレイヤーの頭(前に飲まれた人)
            {6, 64, 64, 40, 16, 4, 12, 4, ARM, 2},  // プレイヤーの腕
    };
    private static final int LIMB_WEIGHT;

    static {
        int s = 0;
        for (int[] l : LIMBS) {
            s += l[9];
        }
        LIMB_WEIGHT = s;
    }

    /** この面に付く手足・顔の種類(無ければ −1)。 */
    private int limbKind(int f, Style st) {
        int h = key(f, 21);
        if (frac(h) >= st.limbChance) {
            return -1;
        }
        int w = Math.floorMod(key(f, 31), LIMB_WEIGHT);
        for (int i = 0; i < LIMBS.length; i++) {
            w -= LIMBS[i][9];
            if (w < 0) {
                return i;
            }
        }
        return 0;
    }

    /** 手足・顔を 1 つ。wrap = くるむ蔦の層(半画素ふくらませて蔦の絵を貼る)。 */
    private void drawLimb(VertexConsumer vc, int f, int kind, boolean wrap) {
        int[] l = LIMBS[kind];
        int d = fdir[f];
        int[] nn = N[d], uu = U[d], ww = W[d];
        int h = key(f, 32), h2 = key(f, 33);
        float js = (frac(h) - 0.5F) * 0.4F, jw = (frac(h >>> 7) - 0.5F) * 0.4F;
        float ph = frac(h >>> 11) * 6.28F;
        // ゆっくり揺れ、ときどき引きつる。押し出されては吸い込まれる
        float sway = Mth.sin(t * 0.07F + ph), sway2 = Mth.sin(t * 0.045F + ph * 1.7F);
        float tw = Mth.sin(t * 0.021F + ph * 0.6F);
        float twitch = tw > 0.93F ? Mth.sin(t * 1.9F) * 0.5F : 0.0F;
        float inout = (0.5F + 0.5F * Mth.sin(t * 0.03F + ph * 2.3F));
        int mode = l[8];
        int dx = l[5], dy = l[6], dz = l[7];
        float tr = wrap ? 0.95F : 0.80F, tg = wrap ? 0.95F : 0.88F, tb = wrap ? 0.95F : 0.70F; // Mob は腐った緑へ寄せる
        float inf = wrap ? 0.5F : 0.0F;
        int tu = wrap ? (h2 >>> 4 & 31) : l[3], tv = wrap ? (h2 >>> 10 & 31) : l[4];
        int tw2 = wrap ? 64 : l[1], th2 = wrap ? 64 : l[2];
        int light = flight[f];
        int parts = mode == LEGS ? 2 : 1;
        for (int part = 0; part < parts; part++) {
            float yaw, pitch, roll, out;
            if (mode == HEAD) {
                yaw = (frac(h2) - 0.5F) * 1.0F + sway * 0.14F + twitch * 0.2F;
                pitch = (frac(h2 >>> 6) - 0.35F) * 0.8F + sway2 * 0.10F;
                roll = (frac(h2 >>> 12) - 0.5F) * 0.9F + sway2 * 0.06F;
                out = 3.0F + 3.5F * inout; // 8 画素のうち外へ出る分
                triad(uu, ww, nn, yaw, pitch, roll);
                anchor(f, js, jw, 0.0F, o);
                box(vc, o, va, vb, vcx, -dx * 0.5F - inf, dx * 0.5F + inf, -dy * 0.5F - inf, dy * 0.5F + inf, out - dz - inf, out + inf,
                        tu, tv, dx, dy, dz, tw2, th2, tr, tg, tb, light);
            } else if (mode == ARM) {
                yaw = (frac(h2) - 0.5F) * 0.9F + sway * 0.22F + twitch * 0.5F;
                pitch = (frac(h2 >>> 6) - 0.5F) * 0.9F + sway2 * 0.20F + twitch * 0.3F;
                roll = frac(h2 >>> 12) * 6.28F;
                out = 6.0F + 4.0F * inout; // 12 画素のうち外へ出る分
                // 腕は長い軸(絵の下 = 手)を外へ向ける: 箱の B = −法線
                triadArm(uu, ww, nn, yaw, pitch, roll);
                anchor(f, js, jw, 0.0F, o);
                box(vc, o, va, vb, vcx, -dx * 0.5F - inf, dx * 0.5F + inf, -out - inf, dy - out + inf, -dz * 0.5F - inf, dz * 0.5F + inf,
                        tu, tv, dx, dy, dz, tw2, th2, tr, tg, tb, light);
            } else {
                float sg = part == 0 ? 1.0F : -1.0F;
                yaw = sg * (0.35F + frac(h2 >>> (part * 5)) * 0.5F) + sway * 0.18F * sg + twitch * 0.6F;
                pitch = (frac(h2 >>> 6 + part * 3) - 0.5F) * 0.7F + sway2 * 0.2F;
                out = 8.0F + 5.0F * inout; // 16 画素のうち外へ出る分
                // 脚は長い軸(箱の A)を外へ
                triadLeg(uu, ww, nn, yaw, pitch);
                anchor(f, js + sg * 0.12F, jw, 0.0F, o);
                box(vc, o, va, vb, vcx, out - dx - inf, out + inf, -dy * 0.5F - inf, dy * 0.5F + inf, -dz * 0.5F - inf, dz * 0.5F + inf,
                        tu, tv, dx, dy, dz, tw2, th2, tr, tg, tb, light);
            }
        }
    }

    /** 頭の向き: A = u、B = w、C = 法線 を、B まわり yaw・A まわり pitch・C まわり roll で回す。結果は va・vb・vcx。 */
    private void triad(int[] uu, int[] ww, int[] nn, float yaw, float pitch, float roll) {
        float cy = Mth.cos(yaw), sy = Mth.sin(yaw), cp = Mth.cos(pitch), sp = Mth.sin(pitch), cr = Mth.cos(roll), sr = Mth.sin(roll);
        for (int k = 0; k < 3; k++) {
            float a1 = uu[k] * cy - nn[k] * sy, c1 = uu[k] * sy + nn[k] * cy;
            float b2 = ww[k] * cp + c1 * sp, c2 = -ww[k] * sp + c1 * cp;
            va[k] = a1 * cr + b2 * sr;
            vb[k] = -a1 * sr + b2 * cr;
            vcx[k] = c2;
        }
    }

    /** 腕の向き: B = −法線(手が外)。A = u、C = w を法線まわりに roll、全体を yaw・pitch で傾ける。 */
    private void triadArm(int[] uu, int[] ww, int[] nn, float yaw, float pitch, float roll) {
        float cr = Mth.cos(roll), sr = Mth.sin(roll), cy = Mth.cos(yaw), sy = Mth.sin(yaw), cp = Mth.cos(pitch), sp = Mth.sin(pitch);
        for (int k = 0; k < 3; k++) {
            float a0 = uu[k] * cr + ww[k] * sr, c0 = -uu[k] * sr + ww[k] * cr, b0 = -nn[k];
            // yaw: A と B を混ぜる(C まわり)
            float a1 = a0 * cy + b0 * sy, b1 = -a0 * sy + b0 * cy;
            // pitch: C と B を混ぜる(A まわり)
            float c2 = c0 * cp + b1 * sp, b2 = -c0 * sp + b1 * cp;
            va[k] = a1;
            vb[k] = b2;
            vcx[k] = c2;
        }
    }

    /** 脚の向き: A = 法線(外)。yaw で u の側へ、pitch で w の側へ倒す。 */
    private void triadLeg(int[] uu, int[] ww, int[] nn, float yaw, float pitch) {
        float cy = Mth.cos(yaw), sy = Mth.sin(yaw), cp = Mth.cos(pitch), sp = Mth.sin(pitch);
        for (int k = 0; k < 3; k++) {
            float a1 = nn[k] * cy + uu[k] * sy, c1 = -nn[k] * sy + uu[k] * cy;
            float a2 = a1 * cp + ww[k] * sp, b2 = -a1 * sp + ww[k] * cp;
            va[k] = a2;
            vb[k] = b2;
            vcx[k] = c1;
        }
    }

    // ------------------------------------------------------------------ 箱

    private final float[] cx = new float[8], cyv = new float[8], cz = new float[8];

    /**
     * 向きのある箱(単位 = 1/16 ブロック)。原点 org(カメラからの位置)、軸 a・b・c(b = 絵の上、+c = 絵の正面)。
     * 絵はバニラの箱の展開と同じ並び(左上 tu tv、箱の大きさ dx dy dz)。
     */
    private void box(VertexConsumer vc, float[] org, float[] a, float[] b, float[] c, float x0, float x1, float y0, float y1, float z0, float z1,
                     int tu, int tv, int dx, int dy, int dz, int texW, int texH, float r, float g, float bl, int light) {
        for (int i = 0; i < 8; i++) {
            float x = ((i & 1) == 0 ? x0 : x1) * PX, y = ((i & 2) == 0 ? y0 : y1) * PX, z = ((i & 4) == 0 ? z0 : z1) * PX;
            cx[i] = org[0] + a[0] * x + b[0] * y + c[0] * z;
            cyv[i] = org[1] + a[1] * x + b[1] * y + c[1] * z;
            cz[i] = org[2] + a[2] * x + b[2] * y + c[2] * z;
        }
        float iw = 1.0F / texW, ih = 1.0F / texH;
        // +c(正面): 左上から 絵の上 = +b
        quad(vc, 6, 7, 5, 4, (tu + dz) * iw, (tv + dz) * ih, (tu + dz + dx) * iw, (tv + dz + dy) * ih, c[0], c[1], c[2], r, g, bl, light);
        // −c(背)
        quad(vc, 3, 2, 0, 1, (tu + 2 * dz + dx) * iw, (tv + dz) * ih, (tu + 2 * dz + 2 * dx) * iw, (tv + dz + dy) * ih, -c[0], -c[1], -c[2], r, g, bl, light);
        // +a(横)
        quad(vc, 7, 3, 1, 5, (tu + dz + dx) * iw, (tv + dz) * ih, (tu + 2 * dz + dx) * iw, (tv + dz + dy) * ih, a[0], a[1], a[2], r, g, bl, light);
        // −a(横)
        quad(vc, 2, 6, 4, 0, tu * iw, (tv + dz) * ih, (tu + dz) * iw, (tv + dz + dy) * ih, -a[0], -a[1], -a[2], r, g, bl, light);
        // +b(上)
        quad(vc, 2, 3, 7, 6, (tu + dz) * iw, tv * ih, (tu + dz + dx) * iw, (tv + dz) * ih, b[0], b[1], b[2], r, g, bl, light);
        // −b(下)
        quad(vc, 4, 5, 1, 0, (tu + dz + dx) * iw, tv * ih, (tu + dz + 2 * dx) * iw, (tv + dz) * ih, -b[0], -b[1], -b[2], r, g, bl, light);
    }

    /** 4 隅(左上・右上・右下・左下の順)。 */
    private void quad(VertexConsumer vc, int i0, int i1, int i2, int i3, float u0, float v0, float u1, float v1,
                      float nx, float ny, float nz, float r, float g, float b, int light) {
        if (nx * cx[i0] + ny * cyv[i0] + nz * cz[i0] > 0.0F) {
            return; // カメラから見て裏の面
        }
        // 並びを、外から見て反時計回りにそろえる(光る層は裏面を捨てるので、軸の組の向きで並びが逆だと消える)
        float ex = cx[i1] - cx[i0], ey = cyv[i1] - cyv[i0], ez = cz[i1] - cz[i0], gx = cx[i3] - cx[i0], gy = cyv[i3] - cyv[i0], gz = cz[i3] - cz[i0];
        if ((ey * gz - ez * gy) * nx + (ez * gx - ex * gz) * ny + (ex * gy - ey * gx) * nz < 0.0F) {
            int s = i1;
            i1 = i3;
            i3 = s;
            vtx(vc, cx[i0], cyv[i0], cz[i0], r, g, b, u0, v0, light, nx, ny, nz);
            vtx(vc, cx[i1], cyv[i1], cz[i1], r, g, b, u0, v1, light, nx, ny, nz);
            vtx(vc, cx[i2], cyv[i2], cz[i2], r, g, b, u1, v1, light, nx, ny, nz);
            vtx(vc, cx[i3], cyv[i3], cz[i3], r, g, b, u1, v0, light, nx, ny, nz);
            return;
        }
        vtx(vc, cx[i0], cyv[i0], cz[i0], r, g, b, u0, v0, light, nx, ny, nz);
        vtx(vc, cx[i1], cyv[i1], cz[i1], r, g, b, u1, v0, light, nx, ny, nz);
        vtx(vc, cx[i2], cyv[i2], cz[i2], r, g, b, u1, v1, light, nx, ny, nz);
        vtx(vc, cx[i3], cyv[i3], cz[i3], r, g, b, u0, v1, light, nx, ny, nz);
    }
}
