package com.yayuy.overgrownvegetation.client.ley;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ley.LeyPylonBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyWires;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 地脈の柱から張った線を描く(2026-11-13 サバイバルの背骨 段 2b)。端の点({@link LeyWires#anchor})どうしを、少したるんだ曲線(放物線。たるみは長さの 4% + 0.05【仮】)で結ぶ。
 * 線は細い帯を 2 枚十字に組んだもの(どの向きから見ても太さがある)。柱どうしの線は座標の小さい方だけが描く(二重に描かない)。
 * 絵 {@code textures/block/ley_wire.png}(布巻きの銅線【仮】)。明るさは区間ごとにその場の光を読む。
 */
public class LeyPylonRenderer implements BlockEntityRenderer<LeyPylonBlockEntity> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/ley_wire.png");
    private static final float HALF_WIDTH = 0.035F;

    @Override
    public void render(LeyPylonBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
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
        vc.addVertex(pose, (float) (p.x - origin.getX()), (float) (p.y - origin.getY()), (float) (p.z - origin.getZ()))
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public boolean shouldRenderOffScreen(LeyPylonBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public AABB getRenderBoundingBox(LeyPylonBlockEntity be) {
        AABB box = new AABB(be.getBlockPos());
        for (BlockPos p : be.links()) {
            box = box.minmax(new AABB(p));
        }
        return box.inflate(1.0);
    }
}
