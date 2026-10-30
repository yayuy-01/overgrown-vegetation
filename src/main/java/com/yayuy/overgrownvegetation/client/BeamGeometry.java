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
        // 2026-09-24 軽量化(監査 1329): 面ごとに Vec3 を十数個作っていたのを成分ごとの計算にし、
        // 次の面の t0 は今の面の t1 と同じ式なので cos / sin を使い回す(三角関数が半分になる)。
        // 角の式・演算の中身と順番は以前の u.scale(cos).add(v.scale(sin)) と a.add(d.scale(r)) のままなので、
        // 頂点は同じ値になる(spin を基底の回転に畳み込む案は丸めが変わるので入れていない)。
        double ux = u.x;
        double uy = u.y;
        double uz = u.z;
        double vx = v.x;
        double vy = v.y;
        double vz = v.z;
        double first = (Math.PI * 2.0D) * 0 / SIDES + spin;
        double c0 = Math.cos(first);
        double s0 = Math.sin(first);
        for (int i = 0; i < SIDES; i++) {
            double t1 = (Math.PI * 2.0D) * (i + 1) / SIDES + spin;
            double c1 = Math.cos(t1);
            double s1 = Math.sin(t1);
            double d0x = ux * c0 + vx * s0;
            double d0y = uy * c0 + vy * s0;
            double d0z = uz * c0 + vz * s0;
            double d1x = ux * c1 + vx * s1;
            double d1y = uy * c1 + vy * s1;
            double d1z = uz * c1 + vz * s1;
            float u0 = (float) i / SIDES;
            float u1 = (float) (i + 1) / SIDES;
            vertex(buffer, matrix, a.x + d0x * ra, a.y + d0y * ra, a.z + d0z * ra, rgb, alpha, u0, v0);
            vertex(buffer, matrix, a.x + d1x * ra, a.y + d1y * ra, a.z + d1z * ra, rgb, alpha, u1, v0);
            vertex(buffer, matrix, b.x + d1x * rb, b.y + d1y * rb, b.z + d1z * rb, rgb, alpha, u1, v1);
            vertex(buffer, matrix, b.x + d0x * rb, b.y + d0y * rb, b.z + d0z * rb, rgb, alpha, u0, v1);
            c0 = c1;
            s0 = s1;
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
        // 2026-09-24 軽量化(監査 1329): cone と同じく、次の分割の t0 = 今の t1 の cos / sin を使い回し、
        // 縁の点は Vec3 を作らず成分で出す(center.add(cos * r, 0, sin * r) と同じ演算)。
        double cx = center.x;
        double cy = center.y + 0.0D;
        double cz = center.z;
        double first = (Math.PI * 2.0D) * 0 / segments + spin;
        double c0 = Math.cos(first);
        double s0 = Math.sin(first);
        for (int i = 0; i < segments; i++) {
            double t1 = (Math.PI * 2.0D) * (i + 1) / segments + spin;
            double c1 = Math.cos(t1);
            double s1 = Math.sin(t1);
            vertex(buffer, matrix, center, rgb, alpha, 0.5F, 0.5F);
            vertex(buffer, matrix, center, rgb, alpha, 0.5F, 0.5F);
            vertex(buffer, matrix, cx + c1 * radius, cy, cz + s1 * radius, rgb, 0.0F,
                    (float) (0.5D + 0.5D * c1), (float) (0.5D + 0.5D * s1));
            vertex(buffer, matrix, cx + c0 * radius, cy, cz + s0 * radius, rgb, 0.0F,
                    (float) (0.5D + 0.5D * c0), (float) (0.5D + 0.5D * s0));
            c0 = c1;
            s0 = s1;
        }
    }

    public static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 p, float[] rgb, float a, float u, float v) {
        vertex(buffer, matrix, p.x, p.y, p.z, rgb, a, u, v);
    }

    /** 座標を成分で受け取る版(Vec3 を作らずに済む。2026-09-24 軽量化、監査 1329)。 */
    public static void vertex(VertexConsumer buffer, Matrix4f matrix, double x, double y, double z,
                              float[] rgb, float a, float u, float v) {
        buffer.addVertex(matrix, (float) x, (float) y, (float) z)
                .setColor(rgb[0], rgb[1], rgb[2], a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private BeamGeometry() {
    }
}
