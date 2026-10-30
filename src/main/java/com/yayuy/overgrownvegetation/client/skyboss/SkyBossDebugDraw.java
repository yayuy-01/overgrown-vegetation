package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import com.yayuy.overgrownvegetation.skyboss.SkyBossWorld;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

/**
 * 確かめ用(Claude のテストクライアント {@code -Dov.devbridge=true} のときだけ): 頭の通った道を赤い線、節を置いた点を黄色い線で、
 * 奥行きを無視して重ねて描く。体が赤い線の上に乗っていれば、頭の道をなぞる曲がりの向きが合っている。ユーザーの runClient・配布では描かない。
 * 遠い所はシェーダーと同じ式でカメラへ寄せる(奥のクリップ面で切れないように)。
 *
 * <p>10-18 夕方から当たり判定の形も描く: 胴と頭の楕円の輪(水色)、腕・触手のカプセルの軸(水色)、扇の刃の間の三角形の縁(青)、
 * 弱点 = 避雷針の軸と蕾(赤紫)。形が描いたモデルからずれていないかを見る。
 */
final class SkyBossDebugDraw {

    static final boolean ENABLED = Boolean.getBoolean("ov.devbridge");
    /** 線を描くか(devbridge の {@code skyboss lines on|off}。10-19: 背の垂れ草のような細い所を撮るとき消す)。形の計算は ENABLED のまま。 */
    static boolean show = true;

    private static BufferBuilder b;
    private static Vec3 cam;
    private static double shrink;
    private static float sqStart;
    private static float sqLimit;
    private static float sqMax;

    static void draw(SkyBossFlight f, double hx, double hy, double hz, double t, Vec3 camera, double shrinkK, SkyBossPose ps,
                     float squeezeStart, float squeezeLimit, float squeezeMax) {
        cam = camera;
        shrink = shrinkK;
        sqStart = squeezeStart;
        sqLimit = squeezeLimit;
        sqMax = squeezeMax;
        double ox = SkyBossWorld.worldX(0.0, t);
        double oz = SkyBossWorld.worldZ(0.0, t);
        b = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        double ax = hx;
        double ay = hy;
        double az = hz;
        for (int i = 0; i < f.trail.size(); i++) {
            double bx = f.trail.x(i);
            double by = f.trail.y(i);
            double bz = f.trail.z(i);
            line(ax + ox, ay, az + oz, bx + ox, by, bz + oz, 255, 40, 40);
            ax = bx;
            ay = by;
            az = bz;
        }
        double[][] joints = ps.points;
        for (double[] j : joints) {
            line(j[0], j[1] + 18, j[2], j[0], j[1] - 18, j[2], 255, 230, 40);
        }
        // 緑: 体の真ん中から曲がる内側へ(内側の扇が下がっていれば傾きの向きが合っている)
        if (Math.abs(f.yawRate) > 1.0E-4) {
            double yr = Math.toRadians(f.yaw);
            double side = f.yawRate > 0 ? 1.0 : -1.0;
            double ix = -Math.cos(yr) * side;
            double iz = -Math.sin(yr) * side;
            double[] c = joints[joints.length / 4];
            line(c[0], c[1], c[2], c[0] + ix * 600, c[1], c[2] + iz * 600, 40, 255, 40);
        }
        if (ps.hasShapes()) {
            shapes(ps);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.enableDepthTest();
        b = null;
    }

    private static void shapes(SkyBossPose ps) {
        // 楕円の筒: 始まりと終わりの輪
        for (int i = 0; i < ps.sliceCount; i++) {
            int o = i * 3;
            for (int end = 0; end < 2; end++) {
                double zz = end == 0 ? 0.0 : ps.sLen[i];
                double cx = ps.sA[o] + ps.sEz[o] * zz;
                double cy = ps.sA[o + 1] + ps.sEz[o + 1] * zz;
                double cz = ps.sA[o + 2] + ps.sEz[o + 2] * zz;
                double px = 0;
                double py = 0;
                double pz = 0;
                for (int k = 0; k <= 12; k++) {
                    double an = Math.PI * 2 * k / 12;
                    double ca = Math.cos(an) * ps.sRx[i];
                    double sa = Math.sin(an) * ps.sRy[i];
                    double x = cx + ps.sEx[o] * ca + ps.sEy[o] * sa;
                    double y = cy + ps.sEx[o + 1] * ca + ps.sEy[o + 1] * sa;
                    double z = cz + ps.sEx[o + 2] * ca + ps.sEy[o + 2] * sa;
                    if (k > 0) {
                        line(px, py, pz, x, y, z, 80, 220, 255);
                    }
                    px = x;
                    py = y;
                    pz = z;
                }
            }
        }
        for (int i = 0; i < ps.capCount; i++) {
            int o = i * 3;
            boolean weak = ps.cPart[i] != SkyBossPose.PART_MOSS;
            // 灰色: 壊れて形から外れた避雷針
            boolean off = ps.cR[i] <= 0.0;
            line(ps.cA[o], ps.cA[o + 1], ps.cA[o + 2], ps.cB[o], ps.cB[o + 1], ps.cB[o + 2], off ? 120 : weak ? 255 : 80, off ? 120 : weak ? 60 : 220, off ? 120 : 255);
        }
        for (int i = 0; i < ps.triCount; i++) {
            int o = i * 9;
            for (int e = 0; e < 3; e++) {
                int a = o + e * 3;
                int c = o + ((e + 1) % 3) * 3;
                line(ps.tP[a], ps.tP[a + 1], ps.tP[a + 2], ps.tP[c], ps.tP[c + 1], ps.tP[c + 2], 60, 110, 255);
            }
        }
        // 赤: 刃の先(描いた刃の先に乗っていれば扇の形が合っている)
        if (ps.bladeTips != null) {
            for (double[] tips : ps.bladeTips) {
                for (int j = 0; j + 2 < tips.length; j += 3) {
                    line(tips[j] - 12, tips[j + 1], tips[j + 2], tips[j] + 12, tips[j + 1], tips[j + 2], 255, 20, 20);
                    line(tips[j], tips[j + 1] - 12, tips[j + 2], tips[j], tips[j + 1] + 12, tips[j + 2], 255, 20, 20);
                    line(tips[j], tips[j + 1], tips[j + 2] - 12, tips[j], tips[j + 1], tips[j + 2] + 12, 255, 20, 20);
                }
            }
        }
        for (int i = 0; i < ps.sphCount; i++) {
            int o = i * 3;
            // 灰色の小さな印: 壊れて形から外れた蕾
            boolean off = ps.spR[i] <= 0.0;
            double r = off ? 6.0 : ps.spR[i];
            int cr = off ? 120 : 255;
            int cg = off ? 120 : 60;
            int cb = off ? 120 : 255;
            line(ps.spC[o] - r, ps.spC[o + 1], ps.spC[o + 2], ps.spC[o] + r, ps.spC[o + 1], ps.spC[o + 2], cr, cg, cb);
            line(ps.spC[o], ps.spC[o + 1] - r, ps.spC[o + 2], ps.spC[o], ps.spC[o + 1] + r, ps.spC[o + 2], cr, cg, cb);
            line(ps.spC[o], ps.spC[o + 1], ps.spC[o + 2] - r, ps.spC[o], ps.spC[o + 1], ps.spC[o + 2] + r, cr, cg, cb);
        }
    }

    private static void line(double x0, double y0, double z0, double x1, double y1, double z1, int r, int g, int bl) {
        vertex(x0, y0, z0, r, g, bl);
        vertex(x1, y1, z1, r, g, bl);
    }

    private static void vertex(double x, double y, double z, int r, int g, int bl) {
        double dx = (x - cam.x) * shrink;
        double dy = (y - cam.y) * shrink;
        double dz = (z - cam.z) * shrink;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (sqMax > sqLimit && d > sqStart) {
            double k = (sqStart + (d - sqStart) * (sqLimit - sqStart) / (sqMax - sqStart)) / d;
            dx *= k;
            dy *= k;
            dz *= k;
        }
        b.addVertex((float) dx, (float) dy, (float) dz).setColor(r, g, bl, 255);
    }

    private SkyBossDebugDraw() {
    }
}
