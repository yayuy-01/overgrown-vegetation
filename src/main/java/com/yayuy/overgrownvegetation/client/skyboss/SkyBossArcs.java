package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.network.SkyBossArcPayload;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import com.yayuy.overgrownvegetation.skyboss.SkyBossTuning;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 放電の雷を描く(2026-10-19)。決定「ボスを中心に、ガラスの球体の中のテスラコイル(プラズマボール)が周りへ雷を放つような雷」。
 * <ul>
 *   <li><b>まわりの雷</b>(当たらない): 避雷針の先から太い筋が 6 本、範囲({@link SkyBossTuning#DISCHARGE_RADIUS})の端の近くまで伸び続け、0.7〜1.1 秒ごとに
 *       向きを変える(プラズマボールの真ん中の電極)。ほかに体の表面(避雷針の先・背・扇の刃の先)から外への細い火花が tick ごとに 10 本生まれて数 tick で消える。どれも種と tick から決まる式なので、状態を持たずどのクライアントでも同じ形
 *       (サーバーは使わない)。</li>
 *   <li><b>当たった雷</b>: サーバーが範囲の中の相手に当てた 1 本({@link SkyBossArcPayload})。体の近い所から相手へ、太く明るく。</li>
 * </ul>
 * 形は 2 tick ごとにぎざぎざを引き直す(ちらつき)。色は道具の電紋の光の色(扇の先の色から取った {@link SkyBossGlow#GLOW} と {@link SkyBossGlow#SOFT})。
 * バニラの雷の描き方(加算)で、カメラを向く帯。遠い所は体と同じく見える向きのままカメラへ寄せる。
 */
final class SkyBossArcs {

    /** まわりの雷(体じゅうの火花): 1 tick に生まれる数と、1 本の長さ(tick)。仮。 */
    private static final int AMBIENT_PER_TICK = 10;
    /** 避雷針の先から伸び続ける太い筋(プラズマボールの真ん中の電極から伸びる筋): 本数と、1 本が向きを変えずに続く長さ(tick)。仮。 */
    private static final int CHANNELS = 6;
    private static final int CHANNEL_LIFE_MIN = 14;
    private static final int CHANNEL_LIFE_MAX = 22;
    private static final int AMBIENT_LIFE_MIN = 3;
    private static final int AMBIENT_LIFE_MAX = 7;
    /** 当たった雷の長さ(tick)。 */
    private static final int HIT_LIFE = 8;

    /** 当たった雷(受け取った tick 付き)。 */
    private record Hit(double fx, double fy, double fz, double tx, double ty, double tz, int seed, long at) {
    }

    private static final List<Hit> HITS = new ArrayList<>();

    static void onArc(SkyBossArcPayload p, long now) {
        HITS.add(new Hit(p.fx(), p.fy(), p.fz(), p.tx(), p.ty(), p.tz(), p.seed(), now));
        if (HITS.size() > 64) {
            HITS.remove(0);
        }
    }

    static void clear() {
        HITS.clear();
    }

    // ------------------------------------------------------------------ 描く
    private static Vec3 cam;
    private static float sqStart;
    private static float sqLimit;
    private static float sqMax;
    private static final double[] pts = new double[64 * 3];
    private static final double[] tmp = new double[3];

    /**
     * 雷を描く({@link SkyBossRenderer} が体の後に)。{@code t} = 描画の時刻、寄せ方は体と同じ値。
     */
    static void draw(PoseStack pose, MultiBufferSource.BufferSource buffers, SkyBossFlight f, SkyBossPose ps, long seed, double t, Vec3 camera,
                     float squeezeStart, float squeezeLimit, float squeezeMax) {
        long now = (long) Math.floor(t);
        HITS.removeIf(h -> now - h.at > HIT_LIFE || now < h.at - 2);
        boolean ambient = f.discharging(t) && ps != null && ps.hasShapes();
        if (!ambient && HITS.isEmpty()) {
            return;
        }
        cam = camera;
        sqStart = squeezeStart;
        sqLimit = squeezeLimit;
        sqMax = squeezeMax;
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.last().pose();
        if (ambient) {
            // 避雷針の先からの太い筋: 筋ごとに決まった長さで向きを選び直し、その間は形だけちらつく(出始めと終わりは細く暗く)
            if (ps.capCount > 0) {
                int o = (ps.capCount - 1) * 3;
                int a0 = ps.rodFirstCapsule() * 3;
                double ax = ps.cB[o] - ps.cA[a0];
                double ay = ps.cB[o + 1] - ps.cA[a0 + 1];
                double az = ps.cB[o + 2] - ps.cA[a0 + 2];
                double al = Math.max(1.0E-6, Math.sqrt(ax * ax + ay * ay + az * az));
                for (int c = 0; c < CHANNELS; c++) {
                    long base = mix(seed, c, 7);
                    int life = CHANNEL_LIFE_MIN + (int) (u(base, 1) * (CHANNEL_LIFE_MAX - CHANNEL_LIFE_MIN + 1));
                    double run = t + u(base, 2) * life;
                    long period = (long) Math.floor(run / life);
                    double age = run - period * life;
                    long h = mix(base, period, 11);
                    double dx = ax / al + (u(h, 3) * 2.0 - 1.0) * 1.1;
                    double dy = ay / al + (u(h, 4) * 2.0 - 1.0) * 1.1;
                    double dz = az / al + (u(h, 5) * 2.0 - 1.0) * 1.1;
                    double dl = Math.max(1.0E-6, Math.sqrt(dx * dx + dy * dy + dz * dz));
                    double len = SkyBossTuning.DISCHARGE_RADIUS * (0.75 + 0.25 * u(h, 6));
                    float fade = (float) Math.sin(Math.PI * Math.min(1.0, age / life));
                    bolt(vc, m, ps.cB[o], ps.cB[o + 1], ps.cB[o + 2], ps.cB[o] + dx / dl * len, ps.cB[o + 1] + dy / dl * len,
                            ps.cB[o + 2] + dz / dl * len, h, (long) Math.floor(t / 2.0), 0.95F * (0.35F + 0.65F * fade), 2.6);
                }
            }
            for (long k = now - AMBIENT_LIFE_MAX + 1; k <= now; k++) {
                if (!f.discharging(k)) {
                    continue;
                }
                for (int j = 0; j < AMBIENT_PER_TICK; j++) {
                    long h = mix(seed, k, j);
                    int life = AMBIENT_LIFE_MIN + (int) (u(h, 1) * (AMBIENT_LIFE_MAX - AMBIENT_LIFE_MIN + 1));
                    double age = t - k;
                    if (age < 0.0 || age >= life) {
                        continue;
                    }
                    if (!ambientEnds(ps, h, tmp)) {
                        continue;
                    }
                    double fx = tmp[0];
                    double fy = tmp[1];
                    double fz = tmp[2];
                    double tx = pts[0];
                    double ty = pts[1];
                    double tz = pts[2];
                    float fade = (float) (1.0 - age / life);
                    bolt(vc, m, fx, fy, fz, tx, ty, tz, h, (long) Math.floor(t / 2.0), 0.8F * fade, 1.5);
                }
            }
        }
        for (Hit hit : HITS) {
            double age = t - hit.at;
            if (age < 0.0) {
                continue;
            }
            float fade = (float) Math.max(0.0, 1.0 - age / HIT_LIFE);
            bolt(vc, m, hit.fx, hit.fy, hit.fz, hit.tx, hit.ty, hit.tz, hit.seed, (long) Math.floor(t / 2.0), fade, 3.0);
        }
        lastVertices = vertices;
        vertices = 0;
        lastDrawTick = now;
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        buffers.endBatch(RenderType.lightning());
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    /**
     * まわりの雷 1 本の始まり(→ {@code from})と終わり(→ {@link #pts} の頭)。避雷針の先 15%・扇の刃の先 35%・背の表面 50%。
     * 外への向きに乱れを足し、範囲の 45〜100% の長さ。
     */
    private static boolean ambientEnds(SkyBossPose ps, long h, double[] from) {
        double pick = u(h, 2);
        double ox;
        double oy;
        double oz;
        if (pick < 0.15 && ps.capCount > 0) {
            int o = (ps.capCount - 1) * 3;
            from[0] = ps.cB[o];
            from[1] = ps.cB[o + 1];
            from[2] = ps.cB[o + 2];
            int a = ps.rodFirstCapsule() * 3;
            ox = ps.cB[o] - ps.cA[a];
            oy = ps.cB[o + 1] - ps.cA[a + 1];
            oz = ps.cB[o + 2] - ps.cA[a + 2];
        } else if (pick < 0.5 && ps.bladeTips != null && ps.bladeTips.length > 0) {
            double[] tips = ps.bladeTips[(int) (u(h, 3) * ps.bladeTips.length) % ps.bladeTips.length];
            if (tips == null || tips.length < 3) {
                return false;
            }
            int n = tips.length / 3;
            int i = ((int) (u(h, 4) * n) % n) * 3;
            from[0] = tips[i];
            from[1] = tips[i + 1];
            from[2] = tips[i + 2];
            ox = tips[i] - ps.boundX;
            oy = tips[i + 1] - ps.boundY;
            oz = tips[i + 2] - ps.boundZ;
        } else {
            if (ps.sliceCount == 0) {
                return false;
            }
            int i = (int) (u(h, 5) * ps.sliceCount) % ps.sliceCount;
            int o = i * 3;
            double along = u(h, 6) * ps.sLen[i];
            double phi = (u(h, 7) - 0.5) * Math.toRadians(140.0);
            double c = Math.cos(phi);
            double s = Math.sin(phi);
            ox = ps.sEy[o] * c + ps.sEx[o] * s;
            oy = ps.sEy[o + 1] * c + ps.sEx[o + 1] * s;
            oz = ps.sEy[o + 2] * c + ps.sEx[o + 2] * s;
            from[0] = ps.sA[o] + ps.sEz[o] * along + ps.sEy[o] * c * ps.sRy[i] + ps.sEx[o] * s * ps.sRx[i];
            from[1] = ps.sA[o + 1] + ps.sEz[o + 1] * along + ps.sEy[o + 1] * c * ps.sRy[i] + ps.sEx[o + 1] * s * ps.sRx[i];
            from[2] = ps.sA[o + 2] + ps.sEz[o + 2] * along + ps.sEy[o + 2] * c * ps.sRy[i] + ps.sEx[o + 2] * s * ps.sRx[i];
        }
        double l = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (l < 1.0E-6) {
            ox = 0.0;
            oy = 1.0;
            oz = 0.0;
        } else {
            ox /= l;
            oy /= l;
            oz /= l;
        }
        // 外への向き + 乱れ
        double rx = u(h, 8) * 2.0 - 1.0;
        double ry = u(h, 9) * 2.0 - 1.0;
        double rz = u(h, 10) * 2.0 - 1.0;
        double dx = ox + rx * 0.7;
        double dy = oy + ry * 0.7;
        double dz = oz + rz * 0.7;
        double dl = Math.max(1.0E-6, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double len = SkyBossTuning.DISCHARGE_RADIUS * (0.45 + 0.55 * u(h, 11));
        pts[0] = from[0] + dx / dl * len;
        pts[1] = from[1] + dy / dl * len;
        pts[2] = from[2] + dz / dl * len;
        return true;
    }

    /** ぎざぎざの雷 1 本(と枝 0〜2 本)。{@code frame} が変わるとぎざぎざを引き直す。{@code thick} = 太さの倍率。 */
    private static void bolt(VertexConsumer vc, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, long seed, long frame,
                             float bright, double thick) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 2.0) {
            return;
        }
        double fx = dx / len;
        double fy = dy / len;
        double fz = dz / len;
        // 横の 2 本の向き
        double sx = -fz;
        double sy = 0.0;
        double sz = fx;
        double sl = Math.sqrt(sx * sx + sz * sz);
        if (sl < 1.0E-4) {
            sx = 1.0;
            sz = 0.0;
            sl = 1.0;
        }
        sx /= sl;
        sz /= sl;
        double ux = sy * fz - sz * fy;
        double uy = sz * fx - sx * fz;
        double uz = sx * fy - sy * fx;
        // 10-19 に半径 2000 へ: 長い雷ほど節を多く(45 ブロックに 1 つ、8〜48)、ぎざぎざは長さの 5%(110 まで)
        int n = Math.max(8, Math.min(48, (int) (len / 45.0)));
        double jag = Math.min(110.0, len * 0.05);
        long fs = mix(seed, frame, 99);
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            double taper = Math.sqrt(Math.sin(Math.PI * k));
            double j1 = (u(fs, 20 + i) * 2.0 - 1.0) * jag * taper;
            double j2 = (u(fs, 60 + i) * 2.0 - 1.0) * jag * taper;
            pts[i * 3] = ax + dx * k + sx * j1 + ux * j2;
            pts[i * 3 + 1] = ay + dy * k + sy * j1 + uy * j2;
            pts[i * 3 + 2] = az + dz * k + sz * j1 + uz * j2;
        }
        // 太さは長さの平方根で(200 で 約 2.9・2000 で 約 6.6 ブロック × 倍率。長さに比べて太くなりすぎない)
        double w = (1.2 + Math.sqrt(len) * 0.12) * thick;
        strip(vc, m, n, w * 3.2, SkyBossGlow.SOFT, 0.28F * bright);
        strip(vc, m, n, w, SkyBossGlow.GLOW, 0.85F * bright);
        // 枝: 途中の節から先の向きへ斜めに、短く
        int branches = (int) (u(seed, 30) * 3.0);
        double[] keep = new double[(n + 1) * 3];
        System.arraycopy(pts, 0, keep, 0, keep.length);
        for (int b = 0; b < branches; b++) {
            int at = 2 + (int) (u(seed, 31 + b) * Math.max(1, n - 4));
            double px = keep[at * 3];
            double py = keep[at * 3 + 1];
            double pz = keep[at * 3 + 2];
            double bl = len * (0.15 + 0.2 * u(seed, 35 + b));
            double ex = fx + (u(fs, 40 + b) - 0.5) * 1.4;
            double ey = fy + (u(fs, 44 + b) - 0.5) * 1.4;
            double ez = fz + (u(fs, 48 + b) - 0.5) * 1.4;
            double el = Math.max(1.0E-6, Math.sqrt(ex * ex + ey * ey + ez * ez));
            int bn = Math.max(5, Math.min(12, (int) (bl / 60.0)));
            for (int i = 0; i <= bn; i++) {
                double k = i / (double) bn;
                double jj = (u(fs, 100 + b * 10 + i) - 0.5) * jag * 0.6 * Math.sin(Math.PI * k);
                pts[i * 3] = px + ex / el * bl * k + sx * jj;
                pts[i * 3 + 1] = py + ey / el * bl * k + sy * jj;
                pts[i * 3 + 2] = pz + ez / el * bl * k + sz * jj;
            }
            strip(vc, m, bn, w * 1.8, SkyBossGlow.SOFT, 0.2F * bright);
            strip(vc, m, bn, w * 0.55, SkyBossGlow.GLOW, 0.6F * bright);
        }
    }

    /** {@link #pts} の 0〜n の点を通る、カメラを向いた帯(幅 w)。 */
    private static void strip(VertexConsumer vc, Matrix4f m, int n, double w, int rgb, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        for (int i = 0; i < n; i++) {
            double x0 = pts[i * 3] - cam.x;
            double y0 = pts[i * 3 + 1] - cam.y;
            double z0 = pts[i * 3 + 2] - cam.z;
            double x1 = pts[i * 3 + 3] - cam.x;
            double y1 = pts[i * 3 + 4] - cam.y;
            double z1 = pts[i * 3 + 5] - cam.z;
            // 帯の横 = 節の向き × カメラへの向き
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
            // バニラの雷の型は裏を描かないので、カメラから見て表(反時計回り)になる並びで
            vertex(vc, m, x0 + cx, y0 + cy, z0 + cz, r, g, b, a);
            vertex(vc, m, x0 - cx, y0 - cy, z0 - cz, r, g, b, a);
            vertex(vc, m, x1 - cx, y1 - cy, z1 - cz, r, g, b, a);
            vertex(vc, m, x1 + cx, y1 + cy, z1 + cz, r, g, b, a);
        }
    }

    /** カメラからの位置 (x, y, z) の頂点(遠い所は体と同じく、見える向きのまま寄せる)。 */
    private static void vertex(VertexConsumer vc, Matrix4f m, double x, double y, double z, int r, int g, int b, int a) {
        double d = Math.sqrt(x * x + y * y + z * z);
        if (sqMax > sqLimit && d > sqStart) {
            double gg = sqStart + (d - sqStart) * (sqLimit - sqStart) / (sqMax - sqStart);
            double k = gg / d;
            x *= k;
            y *= k;
            z *= k;
        }
        vc.addVertex(m, (float) x, (float) y, (float) z).setColor(r, g, b, a);
        vertices++;
    }

    // ------------------------------------------------------------------ 確かめ用(devbridge の skyboss arcs)
    private static int vertices;
    private static int lastVertices;
    private static long lastDrawTick = Long.MIN_VALUE;

    static String report() {
        return "雷の頂点 " + lastVertices + "(描いた tick " + lastDrawTick + ")・当たった雷 " + HITS.size();
    }

    // ------------------------------------------------------------------ 決まった乱数
    private static long mix(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + c * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /** 0〜1。 */
    private static double u(long h, int k) {
        long x = mix(h, k, 0x5DEECE66DL);
        return (x >>> 11) * 0x1.0p-53;
    }

    private SkyBossArcs() {
    }
}
