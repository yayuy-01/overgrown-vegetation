package com.yayuy.overgrownvegetation.client.ley;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ley.LeyPylonBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyWires;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import com.yayuy.overgrownvegetation.ley.LeyBatteryHolder;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 地脈の柱を描く(2026-11-22 から GeckoLib の模型 {@link LeyPylonModel}。電池を掛けている間だけ骨 {@code battery} を見せる)。
 * その後で、柱から張った線を描く(2026-11-13 サバイバルの背骨 段 2b)。端の点({@link LeyWires#anchor})どうしを、少したるんだ曲線(放物線。たるみは長さの 4% + 0.05【仮】)で結ぶ。
 * 線は細い帯を 2 枚十字に組んだもの(どの向きから見ても太さがある)。柱どうしの線は座標の小さい方だけが描く(二重に描かない)。
 * 絵 {@code textures/block/ley_wire.png}(布巻きの銅線【仮】)。明るさは区間ごとにその場の光を読む。
 */
public class LeyPylonRenderer extends GeoBlockRenderer<LeyPylonBlockEntity> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/ley_wire.png");
    private static final float HALF_WIDTH = 0.035F;

    public LeyPylonRenderer() {
        super(new LeyPylonModel<>());
    }

    @Override
    public void preRender(PoseStack poseStack, LeyPylonBlockEntity animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        if (!isReRender) {
            LeyPylonModel.showBattery(model, animatable.getBlockState().hasProperty(LeyBatteryHolder.BATTERY)
                    && animatable.getBlockState().getValue(LeyBatteryHolder.BATTERY));
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    /**
     * 1.20.1: GeckoLib 4.8.4 の GeoBlockRenderer は render を生の BlockEntity で宣言していて、継いだ側で上書きできない(名前がぶつかる)。
     * 本流の render(柱の模型の後に線)の代わりに、模型を描き終えて積みを戻した後に呼ばれる renderFinal で線を描く(積みはブロックの原点のまま)。
     */
    @Override
    public void renderFinal(PoseStack poseStack, LeyPylonBlockEntity be, BakedGeoModel model, MultiBufferSource buffers, @Nullable VertexConsumer buffer,
                            float partialTick, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        super.renderFinal(poseStack, be, model, buffers, buffer, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
        renderWires(be, poseStack, buffers);
    }

    private static void renderWires(LeyPylonBlockEntity be, PoseStack poseStack, MultiBufferSource buffers) {
        Level level = be.getLevel();
        if (level == null || be.links().isEmpty()) {
            return;
        }
        BlockPos me = be.getBlockPos();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for (BlockPos other : be.links()) {
            if (level.getBlockEntity(other) instanceof LeyPylonBlockEntity o && o.hasLink(me) && other.asLong() < me.asLong()) {
                continue; // 相手の柱が描く
            }
            Vec3 a = LeyWires.anchor(level, me, other);
            Vec3 b = LeyWires.anchor(level, other, me);
            drawWire(level, poseStack, vc, me, a, b);
        }
    }

    private static void drawWire(Level level, PoseStack poseStack, VertexConsumer vc, BlockPos origin, Vec3 a, Vec3 b) {
        double len = a.distanceTo(b);
        double sag = len * 0.04 + 0.05;
        int segments = Math.max(4, (int) Math.ceil(len * 1.5));
        Vec3 dir = b.subtract(a);
        Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
        Vec3 side = flat.lengthSqr() < 1.0e-6 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(-flat.z, 0.0, flat.x).normalize();
        Vec3 up = side.cross(dir).normalize();
        PoseStack.Pose pose = poseStack.last();
        Vec3 prev = point(a, b, sag, 0.0);
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            Vec3 cur = point(a, b, sag, t);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(prev.add(cur).scale(0.5)));
            float u0 = (float) ((i - 1) * len / segments);
            float u1 = (float) (i * len / segments);
            quad(vc, pose, origin, prev, cur, side, light, u0, u1);
            quad(vc, pose, origin, prev, cur, up, light, u0, u1);
            prev = cur;
        }
    }

    private static Vec3 point(Vec3 a, Vec3 b, double sag, double t) {
        Vec3 p = a.lerp(b, t);
        return p.add(0.0, -sag * 4.0 * t * (1.0 - t), 0.0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, BlockPos origin, Vec3 p0, Vec3 p1, Vec3 offset, int light, float u0, float u1) {
        Vec3 o = offset.scale(HALF_WIDTH);
        vertex(vc, pose, origin, p0.add(o), u0, 0.0F, light);
        vertex(vc, pose, origin, p0.subtract(o), u0, 1.0F, light);
        vertex(vc, pose, origin, p1.subtract(o), u1, 1.0F, light);
        vertex(vc, pose, origin, p1.add(o), u1, 0.0F, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, BlockPos origin, Vec3 p, float u, float v, int light) {
        vc.vertex(pose.pose(), (float) (p.x - origin.getX()), (float) (p.y - origin.getY()), (float) (p.z - origin.getZ()))
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(LeyPylonBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    /**
     * 描く範囲。1.20.1 では描き手ではなくブロックエンティティの {@code getRenderBoundingBox()} が聞かれる(Forge の IForgeBlockEntity)。
     * {@code LeyPylonBlockEntity} から呼んでもらう(G1 への頼み)。それまでは柱の 1 升の箱で、柱が画面の外だと線も消える。
     */
    public static AABB getRenderBoundingBox(LeyPylonBlockEntity be) {
        // 毎フレーム呼ばれるので、線 1 本ごとに箱を作らず、座標の端だけ数えて箱を 1 つ作る(11-22 見直し 15)
        BlockPos me = be.getBlockPos();
        int x0 = me.getX(), y0 = me.getY(), z0 = me.getZ(), x1 = x0, y1 = y0, z1 = z0;
        for (BlockPos p : be.links()) {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        return new AABB(x0 - 1.0, y0 - 1.0, z0 - 1.0, x1 + 2.0, y1 + 2.0, z1 + 2.0);
    }
}
