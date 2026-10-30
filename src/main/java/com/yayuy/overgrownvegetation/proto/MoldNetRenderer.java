package com.yayuy.overgrownvegetation.proto;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 粘菌の網の試作の描画。核のまわりのブロックの<b>形</b>(輪郭の形を箱に分けたもの)の面に、面から 0.01 浮かせて動く網の絵を貼る。
 * 全面のブロックだけでなく、階段・ハーフ・柵・塀なども形どおりに覆う(ユーザー 2026-10-08)。
 *
 * <ul>
 *   <li>面は約 0.25 ブロックのマスに割る。マスの中心から 0.02 外の点が、同じブロックか隣のブロックの形の中なら、そのマスは隠れているので描かない
 *       (階段の内側の段・隣と接する面)。当たり判定の無いブロック(草・花・松明)と液体は覆わない。</li>
 *   <li>頂点ごとの濃さ = 核からの距離 + 場所で決まるノイズ(動いても模様が揺れない)。進む向きへ前を伸ばす。</li>
 *   <li>絵の座標はブロックの中の位置(0〜1)。絵は 1 マスで継ぎ目なく繋がるので、箱やブロックをまたいでも網が続く。</li>
 *   <li>光はマスの外の点のブロックの明るさ。</li>
 * </ul>
 * 核は仮の小さな箱。壁に張り付いている間は壁に沿って倒す。
 */
public class MoldNetRenderer extends EntityRenderer<MoldCarpetProtoEntity> {

    /** マスの大きさの目安(ブロック)。 */
    static final double CELL = 0.25;
    /** 網の届く半径(ブロック。核のまわり 3×3 マスに収まる)。 */
    static final double REACH = 1.35;
    /** 面から浮かせる距離。 */
    static final float LIFT = 0.01F;
    /** 隠れているかを調べる点の、面からの距離。 */
    static final double PROBE = 0.02;
    /** 仮の核の半分の幅と高さ(当たり判定 0.35 × 0.2 に合わせる)。 */
    static final float CORE_HALF = 0.16F, CORE_H = 0.17F;

    public MoldNetRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(MoldCarpetProtoEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(MoldCarpetProtoEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        Level level = entity.level();
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(MoldCarpetProto.NET_SPRITE);
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));

        // 描画の原点(ディスパッチャーが置いた位置と同じ補間)
        double ox = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double oy = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double oz = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        // 進む向きと強さ(なめらかにした動き)
        double fx = Mth.lerp(partialTick, entity.flowXOld, entity.flowX);
        double fz = Mth.lerp(partialTick, entity.flowZOld, entity.flowZ);
        double speed = Math.sqrt(fx * fx + fz * fz);
        double push = Mth.clamp(speed / 0.04, 0, 1);
        double dx = speed > 1e-5 ? fx / speed : 0;
        double dz = speed > 1e-5 ? fz / speed : 0;
        double time = entity.tickCount + partialTick;
        Net net = new Net(ox, oy, oz, oy + 0.1, dx, dz, push, REACH + 0.07 * Math.sin(time * 0.07));

        Shapes shapes = new Shapes(level);
        PoseStack.Pose pose = poseStack.last();
        int bx0 = Mth.floor(ox) - 2;
        int by0 = Mth.floor(net.cy) - 2;
        int bz0 = Mth.floor(oz) - 2;
        for (int bx = bx0; bx <= bx0 + 4; bx++) {
            for (int by = by0; by <= by0 + 4; by++) {
                for (int bz = bz0; bz <= bz0 + 4; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    for (AABB box : shapes.boxes(pos)) {
                        for (Direction d : Direction.values()) {
                            boxFace(vc, pose, sprite, net, shapes, pos, box, d);
                        }
                    }
                }
            }
        }

        core(entity, partialTick, poseStack, vc, sprite, packedLight);
        super.render(entity, yaw, partialTick, poseStack, buffers, packedLight);
    }

    /** 網の形(描画の原点・網の中心・進む向き・届く半径)。 */
    record Net(double ox, double oy, double oz, double cy, double dx, double dz, double push, double reach) {

        /** 世界の点 (x, y, z) の網の濃さ 0〜1。 */
        double alpha(double x, double y, double z) {
            double rx = x - ox;
            double ry = y - cy;
            double rz = z - oz;
            double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
            // 前(進む向き)は遠くまで、後ろは近くまで
            double along = (rx * dx + rz * dz) / Math.max(r, 1e-4);
            double reachHere = reach * (1 + 0.35 * push * along);
            // 縁のいびつさ(場所で決まるノイズ。動いても模様が揺れない)
            double n = noise(x * 1.7, y * 1.7, z * 1.7) * 0.65 + noise(x * 4.1, y * 4.1, z * 4.1) * 0.35;
            double edge = reachHere + (n - 0.5) * 0.9;
            return Mth.clamp((edge - r) / 0.45, 0, 1);
        }
    }

    /** 1 フレームの間だけ、ブロックごとの形の箱を覚えておく。 */
    static final class Shapes {
        final Level level;
        final Map<BlockPos, List<AABB>> cache = new HashMap<>();

        Shapes(Level level) {
            this.level = level;
        }

        /** ブロックの形の箱(ブロックの中の座標 0〜1)。当たり判定の無いブロックと空気・液体は空。 */
        List<AABB> boxes(BlockPos pos) {
            return this.cache.computeIfAbsent(pos.immutable(), p -> {
                BlockState state = this.level.getBlockState(p);
                if (state.isAir() || state.getCollisionShape(this.level, p).isEmpty()) {
                    return List.of();
                }
                return state.getShape(this.level, p).toAabbs();
            });
        }

        /** 世界の点がどれかのブロックの形の中か。 */
        boolean solid(double x, double y, double z) {
            BlockPos p = BlockPos.containing(x, y, z);
            double lx = x - p.getX();
            double ly = y - p.getY();
            double lz = z - p.getZ();
            for (AABB b : this.boxes(p)) {
                if (lx > b.minX && lx < b.maxX && ly > b.minY && ly < b.maxY && lz > b.minZ && lz < b.maxZ) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 箱 1 つの面 1 枚を、隠れていないマスだけ描く。 */
    static void boxFace(VertexConsumer vc, PoseStack.Pose pose, TextureAtlasSprite sprite, Net net, Shapes shapes,
                        BlockPos pos, AABB box, Direction d) {
        int axis = d.getAxis().ordinal();       // 0 = x, 1 = y, 2 = z
        int ua = axis == 0 ? 1 : 0;              // 面の上の 2 つの軸
        int va = axis == 2 ? 1 : 2;
        int sign = d.getAxisDirection().getStep();
        double[] lo = {box.minX, box.minY, box.minZ};
        double[] hi = {box.maxX, box.maxY, box.maxZ};
        double[] base = {pos.getX(), pos.getY(), pos.getZ()};
        double plane = base[axis] + (sign > 0 ? hi[axis] : lo[axis]);

        // 核の側を向いた面だけ
        double[] c = {net.ox, net.cy, net.oz};
        if ((c[axis] - plane) * sign <= 0.02) {
            return;
        }
        // 面のいちばん近い点が網の届く所より遠ければ飛ばす
        double eu = Math.max(Math.max(base[ua] + lo[ua] - c[ua], 0), c[ua] - base[ua] - hi[ua]);
        double ev = Math.max(Math.max(base[va] + lo[va] - c[va], 0), c[va] - base[va] - hi[va]);
        double en = c[axis] - plane;
        if (eu * eu + ev * ev + en * en > (net.reach + 0.7) * (net.reach + 0.7)) {
            return;
        }

        int nu = Math.max(1, (int) Math.ceil((hi[ua] - lo[ua]) / CELL - 1e-6));
        int nv = Math.max(1, (int) Math.ceil((hi[va] - lo[va]) / CELL - 1e-6));
        double[] lu = new double[nu + 1];
        double[] lv = new double[nv + 1];
        for (int i = 0; i <= nu; i++) {
            lu[i] = lo[ua] + (hi[ua] - lo[ua]) * i / nu;
        }
        for (int j = 0; j <= nv; j++) {
            lv[j] = lo[va] + (hi[va] - lo[va]) * j / nv;
        }
        float[][] a = new float[nu + 1][nv + 1];
        double[] w = new double[3];
        w[axis] = plane;
        for (int i = 0; i <= nu; i++) {
            for (int j = 0; j <= nv; j++) {
                w[ua] = base[ua] + lu[i];
                w[va] = base[va] + lv[j];
                a[i][j] = (float) net.alpha(w[0], w[1], w[2]);
            }
        }

        float nx = d.getStepX();
        float ny = d.getStepY();
        float nz = d.getStepZ();
        float lift = (float) (plane + LIFT * sign - (axis == 0 ? net.ox : axis == 1 ? net.oy : net.oz));
        double[] q = new double[3];
        for (int i = 0; i < nu; i++) {
            for (int j = 0; j < nv; j++) {
                if (a[i][j] + a[i + 1][j] + a[i + 1][j + 1] + a[i][j + 1] < 0.01F) {
                    continue;
                }
                // マスの中心のすぐ外が何かの形の中なら隠れている
                q[axis] = plane + PROBE * sign;
                q[ua] = base[ua] + (lu[i] + lu[i + 1]) * 0.5;
                q[va] = base[va] + (lv[j] + lv[j + 1]) * 0.5;
                if (shapes.solid(q[0], q[1], q[2])) {
                    continue;
                }
                int light = LevelRenderer.getLightColor(shapes.level, BlockPos.containing(q[0], q[1], q[2]));
                vertex(vc, pose, sprite, net, axis, ua, va, lift, base, lu[i], lv[j], a[i][j], light, nx, ny, nz);
                vertex(vc, pose, sprite, net, axis, ua, va, lift, base, lu[i + 1], lv[j], a[i + 1][j], light, nx, ny, nz);
                vertex(vc, pose, sprite, net, axis, ua, va, lift, base, lu[i + 1], lv[j + 1], a[i + 1][j + 1], light, nx, ny, nz);
                vertex(vc, pose, sprite, net, axis, ua, va, lift, base, lu[i], lv[j + 1], a[i][j + 1], light, nx, ny, nz);
            }
        }
    }

    /** 頂点 1 つ。{@code u} / {@code v} はブロックの中の座標(0〜1)で、そのまま絵の座標にする。 */
    static void vertex(VertexConsumer vc, PoseStack.Pose pose, TextureAtlasSprite sprite, Net net, int axis, int ua, int va,
                       float lift, double[] base, double u, double v, float alpha, int light, float nx, float ny, float nz) {
        float[] p = new float[3];
        double[] o = {net.ox, net.oy, net.oz};
        p[axis] = lift;
        p[ua] = (float) (base[ua] + u - o[ua]);
        p[va] = (float) (base[va] + v - o[va]);
        float tu = Mth.lerp((float) Mth.clamp(u, 0, 1), sprite.getU0(), sprite.getU1());
        float tv = Mth.lerp((float) Mth.clamp(v, 0, 1), sprite.getV0(), sprite.getV1());
        vc.addVertex(pose, p[0], p[1], p[2])
                .setColor(1.0F, 1.0F, 1.0F, alpha)
                .setUv(tu, tv)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    /**
     * 仮の核: 網の絵を橙に染めた小さな低い箱。壁に張り付いている間は底を壁へ向けて倒す
     * (底の面の真ん中が壁に来るように、倒す分だけ壁の側へ寄せる)。
     */
    static void core(MoldCarpetProtoEntity entity, float partialTick, PoseStack poseStack, VertexConsumer vc, TextureAtlasSprite sprite, int light) {
        float tilt = Mth.lerp(partialTick, entity.tiltOld, entity.tilt);
        Direction w = entity.wall;
        float half = CORE_H * 0.5F;
        float toWall = (entity.getBbWidth() * 0.5F - half) * tilt;
        poseStack.pushPose();
        poseStack.translate(w.getStepX() * toWall, half, w.getStepZ() * toWall);
        if (tilt > 0.001F) {
            poseStack.mulPose(Axis.of(new Vector3f(-w.getStepZ(), 0, w.getStepX())).rotationDegrees(90 * tilt));
        }
        poseStack.translate(0, -half, 0);
        PoseStack.Pose pose = poseStack.last();
        float x0 = -CORE_HALF, x1 = CORE_HALF, y0 = 0.01F, y1 = CORE_H, z0 = -CORE_HALF, z1 = CORE_HALF;
        float[][] q = {
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0},
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0},
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1},
                {x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0, 0, 1},
                {x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1, 0, 0},
                {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0},
        };
        float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        for (float[] f : q) {
            for (int k = 0; k < 4; k++) {
                vc.addVertex(pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2])
                        .setColor(0.95F, 0.55F, 0.2F, 1.0F)
                        .setUv(Mth.lerp(uv[k][0], sprite.getU0(), sprite.getU1()), Mth.lerp(uv[k][1], sprite.getV0(), sprite.getV1()))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, f[12], f[13], f[14]);
            }
        }
        poseStack.popPose();
    }

    /** なめらかな値のノイズ 0〜1(場所だけで決まる)。 */
    static double noise(double x, double y, double z) {
        int xi = Mth.floor(x);
        int yi = Mth.floor(y);
        int zi = Mth.floor(z);
        double tx = smooth(x - xi);
        double ty = smooth(y - yi);
        double tz = smooth(z - zi);
        double v = 0;
        for (int k = 0; k < 8; k++) {
            int ix = k & 1;
            int iy = (k >> 1) & 1;
            int iz = (k >> 2) & 1;
            double w = (ix == 1 ? tx : 1 - tx) * (iy == 1 ? ty : 1 - ty) * (iz == 1 ? tz : 1 - tz);
            v += w * hash(xi + ix, yi + iy, zi + iz);
        }
        return v;
    }

    static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    static double hash(int x, int y, int z) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
        h ^= h >>> 13;
        h *= 0x5DEECE66DL;
        h ^= h >>> 17;
        return (h & 0xFFFF) / 65535.0;
    }
}
