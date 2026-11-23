package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SeedPod;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 地面に落ちた莢({@link G1SeedPod})の絵(2026-11-13、能力の第 3 回)。新しい絵は描かず、<b>クリーパーの 1 段目の背の莢の箱 1 つ</b>
 * (geo の骨 pods の 5 × 7 × 5 の箱。絵 {@code developed_parasitized_creeper.png} の同じ画素)をそのまま 1 つ描く
 * = 背から落ちた莢そのもの。大きさも同じ(1 画素 = 1/16 ブロック)。
 *
 * <p>割れる時刻へ向けて脈打ちながら膨らむ(仕様「地面の莢が脈打って膨らむ」。脈は終わりに向けて速くなる = {@link G1SeedPod#pulsePeriod})。
 * 光らせない。クリーパーの絵が描き直されたら、下の UV(箱の位置)を geo に合わせて直す。
 */
public class G1SeedPodRenderer extends EntityRenderer<G1SeedPod> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/developed_parasitized_creeper.png");
    /** クリーパーの絵の大きさと、莢の箱(5 × 7 × 5)の面の UV(geo の 3 つ目の箱)。 */
    private static final float TEX_W = 128.0F, TEX_H = 80.0F;
    private static final float W = 5.0F / 16.0F, H = 7.0F / 16.0F;
    private static final float[] NORTH = {61, 5, 5, 7}, EAST = {56, 5, 5, 7}, SOUTH = {71, 5, 5, 7}, WEST = {66, 5, 5, 7}, UP = {56, 0, 5, 5},
            DOWN = {61, 0, 5, 5};
    /** 割れる直前の大きさ(倍)と、脈 1 回の膨らみ。【仮】 */
    private static final float SWELL = 1.35F;
    private static final float PULSE = 0.10F;

    public G1SeedPodRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.2F;
    }

    @Override
    public void render(G1SeedPod entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float age = entity.tickCount + partialTick;
        int fuse = entity.fuse();
        float f = Mth.clamp(age / fuse, 0.0F, 1.0F);
        int period = G1SeedPod.pulsePeriod(entity.tickCount, fuse);
        float beat = (age % period) / period; // 脈: すばやく膨らんでゆっくり戻る
        float pulse = f < 0.15F ? 0.0F : PULSE * (beat < 0.25F ? beat / 0.25F : 1.0F - (beat - 0.25F) / 0.75F);
        float scale = 1.0F + (SWELL - 1.0F) * f * f + pulse;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-entity.getYRot()));
        pose.scale(scale, scale, scale);
        PoseStack.Pose last = pose.last();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        float x0 = -W / 2.0F, x1 = W / 2.0F, z0 = -W / 2.0F, z1 = W / 2.0F, y0 = 0.0F, y1 = H;
        // 北(-Z)・南(+Z)・西(-X)・東(+X)・上・下
        quad(vc, last, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, NORTH, 0, 0, -1, light);
        quad(vc, last, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, SOUTH, 0, 0, 1, light);
        quad(vc, last, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, WEST, -1, 0, 0, light);
        quad(vc, last, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, EAST, 1, 0, 0, light);
        quad(vc, last, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, UP, 0, 1, 0, light);
        quad(vc, last, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, DOWN, 0, -1, 0, light);
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    /** 4 頂点(左上・右上・右下・左下)と面の UV。 */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float[] uv, float nx, float ny, float nz, int light) {
        float u0 = uv[0] / TEX_W, v0 = uv[1] / TEX_H, u1 = (uv[0] + uv[2]) / TEX_W, v1 = (uv[1] + uv[3]) / TEX_H;
        vertex(vc, pose, ax, ay, az, u0, v0, nx, ny, nz, light);
        vertex(vc, pose, bx, by, bz, u1, v0, nx, ny, nz, light);
        vertex(vc, pose, cx, cy, cz, u1, v1, nx, ny, nz, light);
        vertex(vc, pose, dx, dy, dz, u0, v1, nx, ny, nz, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, float nx, float ny, float nz,
                               int light) {
        vc.vertex(pose.pose(), x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(pose.normal(), nx, ny, nz).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(G1SeedPod entity) {
        return TEXTURE;
    }
}
