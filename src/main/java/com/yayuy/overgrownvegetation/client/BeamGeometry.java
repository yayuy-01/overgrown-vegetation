package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 光線を描くための共通の形 — 円錐台と円盤と頂点。
 *
 * <p>攻城戦ボスのビーム({@link SiegeBeamRenderer})と、フィロセンスのビーム
 * ({@link PhyllosensBeamRenderer})で同じ見た目・同じテクスチャを使うので、
 * 頂点の並べ方はここに 1 つだけ置いてある。太さと色は呼ぶ側が決める。
 *
 * <p>テクスチャは {@code textures/entity/siege_beam.png}(32×64、上下左右ともタイル可能)を
 * u = 周方向・v = 長さ方向に貼る。v は時間で流れる(光が飛んでいく見た目)。
 */
public final class BeamGeometry {

    public static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/siege_beam.png");

    /** 円錐台の分割数。 */
    public static final int SIDES = 12;
    /** テクスチャの長さ方向 1 タイルぶんのブロック数。 */
    public static final float TEXTURE_TILE_LENGTH = 48.0F;
    /** テクスチャが長さ方向へ流れる速さ(タイル/tick)。 */
    public static final float TEXTURE_SCROLL = 0.035F;

    /**
     * 線分 {@code a}-{@code b} に沿った {@value #SIDES} 角の円錐台(a 側の半径 {@code ra}、b 側の半径 {@code rb})。
     * テクスチャは u = 周方向、v = 長さ方向で、{@code scroll} 倍の速さで流れ、{@code spin}(rad)だけ軸まわりに回る。
     * 両面描画。座標はカメラ相対。
     *
     * @param tileLength テクスチャの長さ方向 1 タイルぶんのブロック数(細いビームでは短くすると密になる)
     */
    public static void cone(VertexConsumer buffer, Matrix4f matrix, Vec3 a, Vec3 b, float ra, float rb,
                            float[] rgb, float alpha, float time, float scroll, float spin, float tileLength) {
        Vec3 axis = b.subtract(a);
        double length = axis.length();
        if (length < 1.0E-4D || (ra <= 0.0F && rb <= 0.0F)) {
            return;
        }
        axis = axis.scale(1.0D / length);
        Vec3 helper = Math.abs(axis.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 u = axis.cross(helper).normalize();
        Vec3 v = axis.cross(u).normalize();
        float v0 = -time * TEXTURE_SCROLL * scroll;
        float v1 = v0 + (float) (length / tileLength);
        for (int i = 0; i < SIDES; i++) {
            double t0 = (Math.PI * 2.0D) * i / SIDES + spin;
            double t1 = (Math.PI * 2.0D) * (i + 1) / SIDES + spin;
            Vec3 d0 = u.scale(Math.cos(t0)).add(v.scale(Math.sin(t0)));
            Vec3 d1 = u.scale(Math.cos(t1)).add(v.scale(Math.sin(t1)));
            float u0 = (float) i / SIDES;
            float u1 = (float) (i + 1) / SIDES;
            vertex(buffer, matrix, a.add(d0.scale(ra)), rgb, alpha, u0, v0);
            vertex(buffer, matrix, a.add(d1.scale(ra)), rgb, alpha, u1, v0);
            vertex(buffer, matrix, b.add(d1.scale(rb)), rgb, alpha, u1, v1);
            vertex(buffer, matrix, b.add(d0.scale(rb)), rgb, alpha, u0, v1);
        }
    }

    /** {@link #cone} の既定タイル長({@value #TEXTURE_TILE_LENGTH} ブロック)版。 */
    public static void cone(VertexConsumer buffer, Matrix4f matrix, Vec3 a, Vec3 b, float ra, float rb,
                            float[] rgb, float alpha, float time, float scroll, float spin) {
        cone(buffer, matrix, a, b, ra, rb, rgb, alpha, time, scroll, spin, TEXTURE_TILE_LENGTH);
    }

    /** 水平な円盤(中心が濃く縁へ向かって透明になる)。座標はカメラ相対。 */
    public static void disc(VertexConsumer buffer, Matrix4f matrix, Vec3 center, float radius,
                            float[] rgb, float alpha, float time) {
        if (radius <= 0.0F) {
            return;
        }
        int segments = 24;
        double spin = time * 0.02D;
        for (int i = 0; i < segments; i++) {
            double t0 = (Math.PI * 2.0D) * i / segments + spin;
            double t1 = (Math.PI * 2.0D) * (i + 1) / segments + spin;
            Vec3 p0 = center.add(Math.cos(t0) * radius, 0.0D, Math.sin(t0) * radius);
            Vec3 p1 = center.add(Math.cos(t1) * radius, 0.0D, Math.sin(t1) * radius);
            vertex(buffer, matrix, center, rgb, alpha, 0.5F, 0.5F);
            vertex(buffer, matrix, center, rgb, alpha, 0.5F, 0.5F);
            vertex(buffer, matrix, p1, rgb, 0.0F, (float) (0.5D + 0.5D * Math.cos(t1)), (float) (0.5D + 0.5D * Math.sin(t1)));
            vertex(buffer, matrix, p0, rgb, 0.0F, (float) (0.5D + 0.5D * Math.cos(t0)), (float) (0.5D + 0.5D * Math.sin(t0)));
        }
    }

    public static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 p, float[] rgb, float a, float u, float v) {
        buffer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(rgb[0], rgb[1], rgb[2], a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private BeamGeometry() {
    }
}
