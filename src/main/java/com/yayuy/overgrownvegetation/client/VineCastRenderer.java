package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.VineExecutioner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 蔦の処刑者の描画(2026-10-23)。モデルはふつうに描き、引き寄せのあいだは<b>左肩の触手の束の先から相手まで蔦を伸ばして</b>描く
 * (ユーザー指定「腕から蔦が飛び出して届く」)。蔦は過繁茂の原木の絵を貼った細い四角の管で、{@link VineExecutioner#CAST_REACH} tick かけて
 * 先が相手まで伸び、届いた後は相手に絡んだまま(相手が引かれて動けば先も付いて動く)。たわみと揺れは少しだけ。
 */
public class VineCastRenderer extends G1GeoRenderer<VineExecutioner> {

    private static final ResourceLocation VINE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("overgrown_vegetation", "textures/block/overgrown_log.png");
    /** 蔦の出どころ = cast の 0.32 秒の姿勢での左肩の束の先(生成器 G1ExecutionerUser#castTip、モデル単位 (12.4, 38.4, −26.9))。ブロック: 左・上・前。 */
    private static final double TIP_LEFT = 12.4 / 16.0, TIP_UP = 38.4 / 16.0, TIP_FORWARD = 26.9 / 16.0;
    private static final int SEGMENTS = 14;

    public VineCastRenderer(EntityRendererProvider.Context context) {
        // 10-24: 肩と背の触手がゆっくり揺れて、たまに速く振れる(歩き・攻撃のキーの上に足す)
        super(context, new OrganicMotionModel<>("vine_executioner", new String[0], 0, false,
                new String[]{"whip_r", "whip_r2", "whip_l", "back_r", "back_l"}, 4.0F), 1.0F, 1.1F);
    }

    @Override
    public void render(VineExecutioner entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        Entity target = entity.castTarget();
        if (target == null || entity.isDeadOrDying()) {
            return;
        }
        float age = entity.castAge(partialTick);
        double reach = Mth.clamp(age / VineExecutioner.CAST_REACH, 0.0D, 1.0D);
        double s = entity.getScale();
        float yaw = Mth.lerp(partialTick, entity.yBodyRotO, entity.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 left = new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw));
        Vec3 start = left.scale(TIP_LEFT * s).add(0, TIP_UP * s, 0).add(forward.scale(TIP_FORWARD * s));
        Vec3 origin = entity.getPosition(partialTick);
        Vec3 end = target.getPosition(partialTick).add(0, target.getBbHeight() * 0.55D, 0).subtract(origin);

        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(VINE_TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        Vec3 line = end.subtract(start);
        double len = line.length();
        if (len < 0.1D) {
            return;
        }
        Vec3 dir = line.scale(1.0D / len);
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0E-4D ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 up = side.cross(dir).normalize();
        Vec3 prev = null;
        double prevW = 0;
        for (int i = 0; i <= SEGMENTS; i++) {
            double t = reach * i / SEGMENTS;
            // たわみ(真ん中が下がる)と、伸びている間だけのうねり
            double sag = -0.08D * len * Math.sin(Math.PI * t / Math.max(reach, 1.0E-3D)) * reach;
            double wiggle = (1.0D - reach) * 0.25D * Math.sin(t * 9.0D + age * 0.9D) + 0.04D * Math.sin(t * 17.0D + age * 0.4D);
            Vec3 p = start.add(line.scale(t)).add(0, sag, 0).add(side.scale(wiggle));
            double w = 0.11D * s * (1.0D - 0.45D * i / SEGMENTS);
            if (prev != null) {
                tube(vc, pose, prev, p, prevW, w, side, up, i, packedLight);
            }
            prev = p;
            prevW = w;
        }
    }

    /** 2 点の間に四角の管の 4 面を描く(太さ w0 → w1)。v は節ごとに絵の 1/4 ずつ進める(繰り返しを使わない)。 */
    private static void tube(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, double w0, double w1, Vec3 side, Vec3 up, int seg, int light) {
        Vec3[] o = {side, up, side.scale(-1), up.scale(-1)};
        float v0 = (seg % 4) / 4.0F, v1 = v0 + 0.25F;
        for (int f = 0; f < 4; f++) {
            Vec3 n = o[f];
            Vec3 c0 = o[(f + 3) % 4].add(n), c1 = o[(f + 1) % 4].add(n);   // 面の 2 つの角(中心からの向き)
            float u0 = f * 0.25F, u1 = u0 + 0.25F;
            vertex(vc, pose, a.add(c0.scale(w0 / 2)), u0, v0, n, light);
            vertex(vc, pose, a.add(c1.scale(w0 / 2)), u1, v0, n, light);
            vertex(vc, pose, b.add(c1.scale(w1 / 2)), u1, v1, n, light);
            vertex(vc, pose, b.add(c0.scale(w1 / 2)), u0, v1, n, light);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float u, float v, Vec3 n, int light) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    @Override
    public boolean shouldRender(VineExecutioner entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        // 蔦が伸びている間は、本体が画面の外でも蔦が見えるように描く
        return entity.castTarget() != null || super.shouldRender(entity, frustum, x, y, z);
    }
}
