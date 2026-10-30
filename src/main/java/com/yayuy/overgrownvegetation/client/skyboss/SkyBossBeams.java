package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.skyboss.SkyBossBeam;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import com.yayuy.overgrownvegetation.skyboss.SkyBossShapes;
import com.yayuy.overgrownvegetation.skyboss.SkyBossTuning;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 転がりのビームを描く(クライアント、2026-10-19)。開いた蕾の芯から外向きに、{@link SkyBossTuning#BEAM_LENGTH} の光の帯(加算)。
 * 時刻表はサーバーと同じ {@link SkyBossBeam}、向きは描画の姿勢(体の骨。うねり・転がり込み)から毎フレーム出し、{@link SkyBossBeam#aim} で揺らす。
 * 色は蕾の芯の光(電紋と同じ {@link SkyBossGlow#GLOW}・{@link SkyBossGlow#SOFT})。太さ・明るさは仮。
 * 遠い所は体と同じく見える向きのまま寄せる(雷 {@link SkyBossArcs} と同じ式。帯を細かく分けて曲げる)。
 */
final class SkyBossBeams {

    /** 帯を分ける数(寄せたときに曲がるので)。 */
    private static final int STEPS = 16;
    /** 芯と外の光の太さ(直径、ブロック)。 */
    private static final double CORE = 2.6;
    private static final double SOFT = 9.0;

    private static final double[] o = new double[3];
    private static final double[] d = new double[3];
    private static final double[] pts = new double[(STEPS + 1) * 3];
    private static Vec3 cam;
    private static float sqStart;
    private static float sqLimit;
    private static float sqMax;
    private static int drawn;

    static void draw(PoseStack pose, MultiBufferSource.BufferSource buffers, SkyBossFlight f, SkyBossPose ps, double t, Vec3 camera,
                     float squeezeStart, float squeezeLimit, float squeezeMax) {
        drawn = 0;
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null || ps == null || !ps.hasShapes() || !f.beaming(t) || t >= SkyBossBeam.rollEnd(f)) {
            return;
        }
        if (f.stunUntil != SkyBossFlight.NONE && t < f.stunUntil) {
            return;
        }
        cam = camera;
        sqStart = squeezeStart;
        sqLimit = squeezeLimit;
        sqMax = squeezeMax;
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.last().pose();
        for (int i = 0; i < s.budCount; i++) {
            if (ps.parts != null && !ps.parts.budSolid(i)) {
                continue;
            }
            double k = SkyBossBeam.strength(f, s, i, t);
            if (k <= 0.0) {
                continue;
            }
            ps.budWorld(i, o);
            // 10-19: 上下左右に少しずつ揺らした向き(サーバーの当たりと同じ式)
            SkyBossBeam.aim(ps, i, t, d);
            double r0 = s.budRadius[i] * SkyBossTuning.BLOCKS_PER_UNIT * 0.6;
            for (int j = 0; j <= STEPS; j++) {
                double u = r0 + (SkyBossTuning.BEAM_LENGTH - r0) * j / (double) STEPS;
                pts[j * 3] = o[0] + d[0] * u;
                pts[j * 3 + 1] = o[1] + d[1] * u;
                pts[j * 3 + 2] = o[2] + d[2] * u;
            }
            // 太さが少し揺らぐ(蕾ごとにずらす)
            double wob = 1.0 + 0.12 * Math.sin(t * 0.9 + i * 1.7);
            strip(vc, m, SOFT * wob * (0.6 + 0.4 * k), SkyBossGlow.SOFT, (float) (0.28 * k));
            strip(vc, m, CORE * wob, SkyBossGlow.GLOW, (float) (0.85 * k));
            drawn++;
        }
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        buffers.endBatch(RenderType.lightning());
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    /** 確かめ用(devbridge)。 */
    static String report() {
        return "ビーム " + drawn + " 本";
    }

    /** {@link #pts} を通る、カメラを向いた帯(幅 w。先へ行くほど薄く)。 */
    private static void strip(VertexConsumer vc, Matrix4f m, double w, int rgb, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        for (int i = 0; i < STEPS; i++) {
            double x0 = pts[i * 3] - cam.x;
            double y0 = pts[i * 3 + 1] - cam.y;
            double z0 = pts[i * 3 + 2] - cam.z;
            double x1 = pts[i * 3 + 3] - cam.x;
            double y1 = pts[i * 3 + 4] - cam.y;
            double z1 = pts[i * 3 + 5] - cam.z;
            double tx = x1 - x0;
            double ty = y1 - y0;
            double tz = z1 - z0;
            double mx = (x0 + x1) * 0.5;
            double my = (y0 + y1) * 0.5;
            double mz = (z0 + z1) * 0.5;
            double cx = ty * mz - tz * my;
            double cy = tz * mx - tx * mz;
            double cz = tx * my - ty * mx;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1.0E-9) {
                continue;
            }
            double h = w * 0.5 / cl;
            cx *= h;
            cy *= h;
            cz *= h;
            int a0 = alpha(alpha, i / (double) STEPS);
            int a1 = alpha(alpha, (i + 1) / (double) STEPS);
            // バニラの雷の型は裏を描かないので、カメラから見て表(反時計回り)になる並びで(雷と同じ)
            vertex(vc, m, x0 + cx, y0 + cy, z0 + cz, r, g, b, a0);
            vertex(vc, m, x0 - cx, y0 - cy, z0 - cz, r, g, b, a0);
            vertex(vc, m, x1 - cx, y1 - cy, z1 - cz, r, g, b, a1);
            vertex(vc, m, x1 + cx, y1 + cy, z1 + cz, r, g, b, a1);
        }
    }

    private static int alpha(float alpha, double k) {
        return Math.max(0, Math.min(255, Math.round(alpha * (float) (1.0 - 0.55 * k) * 255.0F)));
    }

    /** カメラからの位置の頂点(遠い所は体と同じく、見える向きのまま寄せる)。 */
    private static void vertex(VertexConsumer vc, Matrix4f m, double x, double y, double z, int r, int g, int b, int a) {
        double dd = Math.sqrt(x * x + y * y + z * z);
        if (sqMax > sqLimit && dd > sqStart) {
            double gg = sqStart + (dd - sqStart) * (sqLimit - sqStart) / (sqMax - sqStart);
            double k = gg / dd;
            x *= k;
            y *= k;
            z *= k;
        }
        vc.addVertex(m, (float) x, (float) y, (float) z).setColor(r, g, b, a);
    }

    private SkyBossBeams() {
    }
}
