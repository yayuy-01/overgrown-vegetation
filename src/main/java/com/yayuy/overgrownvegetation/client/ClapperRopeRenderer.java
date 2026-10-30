package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.defense.ClapperRope;
import com.yayuy.overgrownvegetation.defense.ClapperRopeBlockEntity;
import net.minecraft.client.model.LeashKnotModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 鳴子の紐の描画(2026-10-15、サバイバル用アイテム ㊵)。
 * <ul>
 *     <li>紐: 揺れ({@code ClapperRopePhysics})の点を前の tick と今の tick の間で補間し、十字に組んだ細い帯で描く(縄の絵)。</li>
 *     <li>結び目: 両端の柱に、バニラのリードを柵に結んだときと同じ結び目の模型(ユーザー指定「紐がブロックに巻き付いている見た目」)。
 *     塀は柱が太いので 1.5 倍に。</li>
 *     <li>板: ユーザーの模型(輪に紐を通した板)。振り子の角度で傾く。</li>
 * </ul>
 * 明るさは点ごとに {@code LevelRenderer#getLightColor}(動く光も入る)。
 */
public class ClapperRopeRenderer implements BlockEntityRenderer<ClapperRopeBlockEntity> {

    private static final ResourceLocation ROPE_TEXTURE = OvergrownVegetation.id("textures/entity/clapper_rope.png");
    private static final ResourceLocation KNOT_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/lead_knot.png");
    private static final ResourceLocation BOARD_TEXTURE = OvergrownVegetation.id("textures/entity/clapper_board.png");
    private static final float ROPE_WIDTH = 0.045F;
    private static final int VIEW_DISTANCE = 48;

    private final LeashKnotModel<?> knot;

    public ClapperRopeRenderer(BlockEntityRendererProvider.Context context) {
        this.knot = new LeashKnotModel<>(context.bakeLayer(ModelLayers.LEASH_KNOT));
    }

    @Override
    public AABB getRenderBoundingBox(ClapperRopeBlockEntity be) {
        return be.ropeBox();
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(ClapperRopeBlockEntity be, Vec3 camera) {
        if (!be.hasRope()) {
            return false;
        }
        AABB box = be.ropeBox();
        double dx = Math.max(0.0D, Math.max(box.minX - camera.x, camera.x - box.maxX));
        double dy = Math.max(0.0D, Math.max(box.minY - camera.y, camera.y - box.maxY));
        double dz = Math.max(0.0D, Math.max(box.minZ - camera.z, camera.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= (double) VIEW_DISTANCE * VIEW_DISTANCE;
    }

    @Override
    public void render(ClapperRopeBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffer, int packedLight,
                       int packedOverlay) {
        Level level = be.getLevel();
        if (level == null || !be.hasRope()) {
            return;
        }
        int n = ClapperRope.SEGMENTS + 1;
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        be.interpolatedPoints(partialTick, x, y, z);
        BlockPos origin = be.getBlockPos();

        // 紐
        VertexConsumer rope = buffer.getBuffer(RenderType.entityCutoutNoCull(ROPE_TEXTURE));
        PoseStack.Pose last = pose.last();
        double v = 0.0D;
        for (int i = 0; i < n - 1; i++) {
            Vec3 a = new Vec3(x[i], y[i], z[i]);
            Vec3 b = new Vec3(x[i + 1], y[i + 1], z[i + 1]);
            Vec3 d = b.subtract(a);
            double len = d.length();
            if (len < 1.0E-5D) {
                continue;
            }
            Vec3 dir = d.scale(1.0D / len);
            Vec3 side = dir.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1.0E-6D) {
                side = new Vec3(1, 0, 0);
            }
            side = side.normalize().scale(ROPE_WIDTH);
            Vec3 up = dir.cross(side).normalize().scale(ROPE_WIDTH);
            int la = light(level, a);
            int lb = light(level, b);
            float v0 = (float) v;
            float v1 = (float) (v + len * 2.0D);
            v += len * 2.0D;
            ribbon(rope, last, a, b, side, origin, la, lb, v0, v1);
            ribbon(rope, last, a, b, up, origin, la, lb, v0, v1);
        }

        // 結び目
        for (BlockPos post : new BlockPos[]{be.getSource(), be.getTarget()}) {
            pose.pushPose();
            pose.translate(post.getX() - origin.getX() + 0.5D, post.getY() - origin.getY() + 0.375D, post.getZ() - origin.getZ() + 0.5D);
            float wide = level.getBlockState(post).getBlock() instanceof WallBlock ? 1.5F : 1.0F;
            pose.scale(-wide, -1.0F, wide);
            VertexConsumer kc = buffer.getBuffer(RenderType.entityCutoutNoCull(KNOT_TEXTURE));
            knot.renderToBuffer(pose, kc, LevelRenderer.getLightColor(level, post), OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }

        // 吊るした物(2026-10-22、燻す物。アイテムの描画をそのまま紐の点の下に。揺らさない)
        if (!be.getHangings().isEmpty()) {
            net.minecraft.client.renderer.entity.ItemRenderer items = net.minecraft.client.Minecraft.getInstance().getItemRenderer();
            for (ClapperRopeBlockEntity.Hanging h : be.getHangings()) {
                double fi = h.t() * ClapperRope.SEGMENTS;
                int i0 = Math.max(0, Math.min(n - 2, (int) Math.floor(fi)));
                double f = fi - i0;
                double ax = x[i0] + (x[i0 + 1] - x[i0]) * f;
                double ay = y[i0] + (y[i0 + 1] - y[i0]) * f;
                double az = z[i0] + (z[i0 + 1] - z[i0]) * f;
                float yaw = (float) Math.atan2(x[i0 + 1] - x[i0], z[i0 + 1] - z[i0]);
                pose.pushPose();
                pose.translate(ax - origin.getX(), ay - ClapperRope.HANG_ITEM_DROP - origin.getY(), az - origin.getZ());
                pose.mulPose(Axis.YP.rotation(yaw + (float) (Math.PI / 2.0D)));
                pose.scale(0.5F, 0.5F, 0.5F);
                items.renderStatic(h.stack(), net.minecraft.world.item.ItemDisplayContext.FIXED, light(level, new Vec3(ax, ay, az)),
                        OverlayTexture.NO_OVERLAY, pose, buffer, level, 0);
                pose.popPose();
            }
        }

        // 板
        float[] boards = be.getBoards();
        if (boards.length == 0) {
            return;
        }
        VertexConsumer solid = buffer.getBuffer(RenderType.entityCutout(BOARD_TEXTURE));
        for (int k = 0; k < boards.length; k++) {
            double fi = boards[k] * ClapperRope.SEGMENTS;
            int i0 = Math.max(0, Math.min(n - 2, (int) Math.floor(fi)));
            double f = fi - i0;
            double ax = x[i0] + (x[i0 + 1] - x[i0]) * f;
            double ay = y[i0] + (y[i0 + 1] - y[i0]) * f;
            double az = z[i0] + (z[i0 + 1] - z[i0]) * f;
            float yaw = (float) Math.atan2(x[i0 + 1] - x[i0], z[i0 + 1] - z[i0]);
            // 低い柵の間の紐は真ん中で板が地面に届く。地面に着いた板は持ち上げて揺らさない。
            // 明るさは紐の点で取る(板の中心で取ると地面の中に入って真っ黒になる)。
            float lift = groundLift(level, ax, ay - HANG_LENGTH, az);
            float[] swing = lift > 0.0F ? NO_SWING : be.swing(k, partialTick);
            int light = light(level, new Vec3(ax, ay, az));
            pose.pushPose();
            pose.translate(ax - origin.getX(), ay - origin.getY(), az - origin.getZ());
            pose.mulPose(Axis.YP.rotation(yaw));
            pose.mulPose(Axis.ZP.rotation(swing[0]));
            pose.mulPose(Axis.XP.rotation(swing[1]));
            pose.translate(0.0F, lift, 0.0F);
            PoseStack.Pose p = pose.last();
            for (ModelCube cube : BOARD_MODEL) {
                cube.render(solid, p, light);
            }
            pose.popPose();
        }
    }

    /** 紐から板の下端まで。 */
    private static final float HANG_LENGTH = (float) (ClapperRope.BOARD_DROP + ClapperRope.BOARD_HEIGHT);
    private static final float[] NO_SWING = {0.0F, 0.0F};

    /**
     * 板の模型(2026-10-15 ユーザー製 {@code naruko.geo.json}・絵 {@code textures/entity/clapper_board.png})。模型の 1 ドット = 1/16 ブロック、
     * 絵の UV は 8×8 の座標(絵は 16×16 なので 8 で割る)。模型の X(板の幅)を紐の向き(この座標の z)へ、模型の Z(厚み)をこの座標の x へ
     * 90 度回して置き、輪の中の高さ 4.45 ドットを紐の点(原点)に合わせる。
     */
    private static final ModelCube[] BOARD_MODEL = {
            // 板 2.5 × 4 × 1 ドット
            ModelCube.of(-1.25F, 0.0F, 0.0F, 2.5F, 4.0F, 1.0F,
                    new float[]{0, 0, 2.5F, 4}, new float[]{0, 4, 1, 4}, new float[]{3, 0, 2.5F, 4}, new float[]{1, 4, 1, 4},
                    new float[]{2, 4, 2.5F, 1}, new float[]{2, 5, 2.5F, 1}),
            // 紐を通す輪 0.2 × 0.7 × 0.8 ドット
            ModelCube.of(-0.05F, 4.0F, 0.1F, 0.2F, 0.7F, 0.8F,
                    new float[]{2, 6, 0.5F, 0.5F}, new float[]{5, 4, 1, 0.5F}, new float[]{6, 2, 0.5F, 0.5F}, new float[]{5, 5, 1, 0.5F},
                    new float[]{6, 0, 0.5F, 1}, new float[]{6, 1, 0.5F, 1}),
    };
    private static final float MODEL_ATTACH_Y = 4.45F;

    /** geo.json の箱 1 つ(この描き方の座標に直したもの)。UV は {u0, v0, u1, v1}(0〜1)。 */
    private record ModelCube(float x0, float y0, float z0, float x1, float y1, float z1,
                             float[] north, float[] south, float[] west, float[] east, float[] up, float[] down) {

        /** geo.json の origin・size(ドット)と面ごとの uv・uv_size(8×8 の座標、上下は向きを問わない)から作る。 */
        static ModelCube of(float ox, float oy, float oz, float sx, float sy, float sz,
                            float[] mNorth, float[] mEast, float[] mSouth, float[] mWest, float[] mUp, float[] mDown) {
            // 模型の X → この座標の z、模型の Z → この座標の −x(厚みの真ん中 0.5 ドットを 0 に)。
            float lx0 = -(oz + sz - 0.5F) / 16.0F, lx1 = -(oz - 0.5F) / 16.0F;
            float lz0 = ox / 16.0F, lz1 = (ox + sx) / 16.0F;
            float ly0 = (oy - MODEL_ATTACH_Y) / 16.0F, ly1 = (oy + sy - MODEL_ATTACH_Y) / 16.0F;
            // 面の向きも回す: 模型の北(−Z)→ 東(+x)、南 → 西、東(+X)→ 南(+z)、西 → 北。
            return new ModelCube(lx0, ly0, lz0, lx1, ly1, lz1,
                    uv(mWest), uv(mEast), uv(mSouth), uv(mNorth), uv(mUp), uv(mDown));
        }

        private static float[] uv(float[] r) {
            float a = r[0] / 8.0F, b = r[1] / 8.0F, c = (r[0] + r[2]) / 8.0F, d = (r[1] + r[3]) / 8.0F;
            return new float[]{Math.min(a, c), Math.min(b, d), Math.max(a, c), Math.max(b, d)};
        }

        void render(VertexConsumer vc, PoseStack.Pose p, int light) {
            int c = 0xFFFFFFFF;
            // face() は頂点 a・b(u0 側)が外から見て右なので、u1 を左に渡して絵の左を左に貼る。
            face(vc, p, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, light, c, up[0], up[2], up[1], up[3]);
            face(vc, p, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, light, c, down[0], down[2], down[1], down[3]);
            face(vc, p, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1, light, c, north[2], north[0], north[1], north[3]);
            face(vc, p, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0, 0, 1, light, c, south[2], south[0], south[1], south[3]);
            face(vc, p, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1, 0, 0, light, c, west[2], west[0], west[1], west[3]);
            face(vc, p, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0, light, c, east[2], east[0], east[1], east[3]);
        }
    }

    /** 板の下端 {@code bottomY} が地面(当たり判定のあるブロック)に埋まる深さ。埋まらなければ 0。 */
    private static float groundLift(Level level, double x, double bottomY, double z) {
        BlockPos pos = BlockPos.containing(x, bottomY, z);
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0F;
        }
        double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        return top > bottomY ? (float) (top - bottomY + 0.01D) : 0.0F;
    }

    private static int light(Level level, Vec3 p) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(p));
    }

    /** a → b の帯(幅の向き {@code side})。 */
    private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 side, BlockPos origin, int la, int lb,
                               float v0, float v1) {
        float ax = (float) (a.x - origin.getX()), ay = (float) (a.y - origin.getY()), az = (float) (a.z - origin.getZ());
        float bx = (float) (b.x - origin.getX()), by = (float) (b.y - origin.getY()), bz = (float) (b.z - origin.getZ());
        float sx = (float) side.x, sy = (float) side.y, sz = (float) side.z;
        vertex(vc, pose, ax - sx, ay - sy, az - sz, 0.0F, v0, la);
        vertex(vc, pose, ax + sx, ay + sy, az + sz, 1.0F, v0, la);
        vertex(vc, pose, bx + sx, by + sy, bz + sz, 1.0F, v1, lb);
        vertex(vc, pose, bx - sx, by - sy, bz - sz, 0.0F, v1, lb);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light) {
        vc.addVertex(pose, x, y, z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void face(VertexConsumer vc, PoseStack.Pose p,
                             float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                             float dx, float dy, float dz, float nx, float ny, float nz, int light, int color,
                             float u0, float u1, float v0, float v1) {
        vc.addVertex(p, ax, ay, az).setColor(color).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        vc.addVertex(p, bx, by, bz).setColor(color).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        vc.addVertex(p, cx, cy, cz).setColor(color).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        vc.addVertex(p, dx, dy, dz).setColor(color).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }
}
