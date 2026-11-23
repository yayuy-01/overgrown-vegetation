package com.yayuy.overgrownvegetation.client.wall;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * <b>仮の</b>描き方(2026-11-16 試作。手触りを見るためだけ)。先頭の面を、苔の絵を貼った濃い茶緑の板で描く。
 * 板は今の進み具合ぶん前へ押し出した箱(前の面 + 4 つの側面)で、角の頂点を位置と時刻から決まる量だけ法線の向きへ揺らす(わずかに波打つ面。決定 その 4)。
 * 本当の見た目は Fable のセッションが {@link CreepingWallRenderer} を書き直して差し替える。
 */
public final class BoxCreepingWallRenderer implements CreepingWallRenderer {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/block/moss_block.png");
    /** 描く距離(ブロック)と面の上限。 */
    private static final double RADIUS = 40.0D;
    private static final int MAX_FACES = 8_000;
    /** 側面(押し出しの箱の横)を描く距離。遠くは前の面だけ。 */
    private static final double SIDE_RADIUS = 12.0D;
    /** 揺れの大きさ(ブロック)。 */
    private static final float WOBBLE = 0.12F;

    private MultiBufferSource.BufferSource buffers;
    private int faceCount;
    /** 測った値(ns)。 */
    public static long lastNanos, maxNanos;
    public static int lastFaces;
    private static long sumNanos, sumFaces;
    private static int frames;

    @Override
    public void render(RenderLevelStageEvent event, ClientLevel level, Camera camera, Iterable<ClientCreepingWall.View> views, float time) {
        long t0 = System.nanoTime();
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new BufferBuilder(1 << 20));
        }
        Vec3 cam = camera.getPosition();
        // 1.20.1: この段の描画の行列は単位行列(1.21 はカメラの回転入り)。event の PoseStack(カメラの回転)で書く = バニラの Mob と同じ形
        PoseStack.Pose pose = event.getPoseStack().last();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        faceCount = 0;
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
        net.minecraft.client.renderer.culling.Frustum frustum = event.getFrustum();
        for (ClientCreepingWall.View v : views) {
            v.collectFaces(level, cam.x, cam.y, cam.z, RADIUS, time, f -> {
                if (faceCount >= MAX_FACES) {
                    return;
                }
                long cell = f.cell();
                int cx = BlockPos.getX(cell), cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell);
                if (frustum != null && !frustum.isVisible(new net.minecraft.world.phys.AABB(cx - 0.5, cy - 0.5, cz - 0.5, cx + 1.5, cy + 1.5, cz + 1.5))) {
                    return; // 画面の外
                }
                faceCount++;
                long p = f.cell();
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                Direction d = f.dir();
                lightPos.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                int light = LevelRenderer.getLightColor(level, lightPos);
                int block = Math.max(light & 0xFFFF, 6 << 4); // 真っ暗な洞窟でも、うっすら見える(本当は光る筋で見せる)
                light = (light & 0xFFFF0000) | block;
                float shade = 0.75F + 0.25F * hash01(x * 31 + y * 17 + z * 7);
                float r = 0.42F * shade, g = 0.40F * shade, b = 0.26F * shade;
                double ddx = x + 0.5 - cam.x, ddy = y + 0.5 - cam.y, ddz = z + 0.5 - cam.z;
                boolean sides = ddx * ddx + ddy * ddy + ddz * ddz < SIDE_RADIUS * SIDE_RADIUS;
                drawFace(vc, pose, cam, x, y, z, d, f.progress(), time, r, g, b, light, sides);
            });
        }
        buffers.endBatch();
        lastFaces = faceCount;
        lastNanos = System.nanoTime() - t0;
        maxNanos = Math.max(maxNanos, lastNanos);
        // 重さの記録(200 フレームごとにログへ。試作の計測用)
        sumNanos += lastNanos;
        sumFaces += faceCount;
        if (++frames >= 200) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[creeping_wall] 描画 平均 {} ms・最大 {} ms・面 平均 {}・FPS {}",
                    String.format("%.3f", sumNanos / 1e6 / frames), String.format("%.3f", maxNanos / 1e6), sumFaces / frames,
                    net.minecraft.client.Minecraft.getInstance().getFps());
            frames = 0;
            sumNanos = 0;
            sumFaces = 0;
            maxNanos = 0;
        }
    }

    /** 升 (x, y, z) の d の側の面を、progress だけ前へ押し出した箱で描く。 */
    private static void drawFace(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, int x, int y, int z, Direction d, float progress,
                                 float time, float r, float g, float b, int light, boolean sides) {
        // 面の 4 隅(世界の整数座標)。法線 n、面の上の 2 軸 u・w
        int nx = d.getStepX(), ny = d.getStepY(), nz = d.getStepZ();
        int ux, uy, uz, wx, wy, wz;
        if (d.getAxis() == Direction.Axis.Y) {
            ux = 1; uy = 0; uz = 0; wx = 0; wy = 0; wz = 1;
        } else if (d.getAxis() == Direction.Axis.X) {
            ux = 0; uy = 0; uz = 1; wx = 0; wy = 1; wz = 0;
        } else {
            ux = 1; uy = 0; uz = 0; wx = 0; wy = 1; wz = 0;
        }
        // 面の平面の位置: 升の中心から法線の向きへ 0.5
        double cx = x + 0.5 + nx * 0.5, cy = y + 0.5 + ny * 0.5, cz = z + 0.5 + nz * 0.5;
        double[][] corner = new double[4][3];
        int[][] sign = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            corner[i][0] = cx + (ux * sign[i][0] + wx * sign[i][1]) * 0.5;
            corner[i][1] = cy + (uy * sign[i][0] + wy * sign[i][1]) * 0.5;
            corner[i][2] = cz + (uz * sign[i][0] + wz * sign[i][1]) * 0.5;
        }
        float[] front = new float[12];
        float[] back = new float[12];
        for (int i = 0; i < 4; i++) {
            double wob = wobble(corner[i][0], corner[i][1], corner[i][2], time);
            double push = progress + wob;
            front[i * 3] = (float) (corner[i][0] + nx * push - cam.x);
            front[i * 3 + 1] = (float) (corner[i][1] + ny * push - cam.y);
            front[i * 3 + 2] = (float) (corner[i][2] + nz * push - cam.z);
            back[i * 3] = (float) (corner[i][0] - nx * 0.05 - cam.x);
            back[i * 3 + 1] = (float) (corner[i][1] - ny * 0.05 - cam.y);
            back[i * 3 + 2] = (float) (corner[i][2] - nz * 0.05 - cam.z);
        }
        quad(vc, pose, front, 0, 1, 2, 3, nx, ny, nz, r, g, b, light);
        if (sides && progress > 0.05F) {
            float sr = r * 0.8F, sg = g * 0.8F, sb = b * 0.8F;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                float[] q = {back[i * 3], back[i * 3 + 1], back[i * 3 + 2], back[j * 3], back[j * 3 + 1], back[j * 3 + 2],
                        front[j * 3], front[j * 3 + 1], front[j * 3 + 2], front[i * 3], front[i * 3 + 1], front[i * 3 + 2]};
                quad(vc, pose, q, 0, 1, 2, 3, 0, 1, 0, sr, sg, sb, light);
            }
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, float[] v, int a, int b, int c, int d, int nx, int ny, int nz,
                             float r, float g, float bl, int light) {
        int[] idx = {a, b, c, d};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int k = 0; k < 4; k++) {
            int i = idx[k];
            vc.vertex(pose.pose(), v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
                    .color(r, g, bl, 1.0F)
                    .uv(uv[k][0], uv[k][1])
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light)
                    .normal(pose.normal(), nx, ny, nz).endVertex();
        }
    }

    /** 隅の揺れ(同じ隅は隣の面と同じ値になるので、面の間に割れ目ができない)。 */
    private static double wobble(double x, double y, double z, float time) {
        float h1 = hash01(Mth.floor(x * 2) * 73856093 ^ Mth.floor(y * 2) * 19349663 ^ Mth.floor(z * 2) * 83492791);
        float h2 = hash01(Mth.floor(x * 2) * 19349663 ^ Mth.floor(y * 2) * 83492791 ^ Mth.floor(z * 2) * 73856093);
        return WOBBLE * (0.6 * Math.sin(time * 0.11 + h1 * 6.283) + 0.4 * Math.sin(time * 0.27 + h2 * 6.283));
    }

    private static float hash01(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
