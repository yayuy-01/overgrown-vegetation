package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.sky.VoxelClouds;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 薄明光線(天使の梯子)(2026-10-17。ユーザー 10-16「雨や曇りの後に薄明光線(天使の梯子)が出るような」。2026-11-08 雲の段階 1 で parked の
 * {@code client/CrepuscularRays} から戻し、束ね役 {@link Atmosphere} の足し算の光の回に入れた)。
 *
 * <ul>
 *   <li>雲の切れ目: 雲の影の地図({@link CloudShadows.Map})で、雲の無い升のうち周り 7 × 7 升の半分以上が雲の所をつなげて 1 つの切れ目にする
 *       (開けた青空は切れ目ではない)。1 秒ごとに探し直し、地図の真ん中に近い順に {@link #MAX_BEAMS} 本まで(重さの段で減らす)。</li>
 *   <li>筋: 切れ目の真ん中の雲の底(y 300)から、日の光の向きへ地面の高さまで伸ばす、カメラを向く帯(林冠の光の筋 {@link LightShafts} と同じ作り)。</li>
 *   <li>明るさ: 加算の光({@link AddLight})。太陽の方を向くほど明るい({@link SunLight#phase})。カメラの近くは薄く、太陽が雲に隠れている間ほど濃い。
 *       空気が湿っているほど濃い(雨の後の 5 分・朝・雲の多い日)。雲がほとんど無い日・一面の曇りの日は出ない。</li>
 *   <li>つなぎ(11-08): 過繁茂の森の中({@link ForestPresence#openOvergrown})は林冠の光の筋があり、葉の向こうの空の筋は見えないので弱める。
 *       地下({@link ForestPresence#underground})・屋根の下(カメラの空の明るさが低い)では出さない。森の霞({@link MistView#fogAmount})で薄める。</li>
 * </ul>
 * 雲の影の地図があるときだけ(昼・太陽の高さ 7° 以上・雲を描いている)。数値はすべて仮決め。強さは {@code sky.json rays.strength}。
 */
public final class CrepuscularRays {
    static final int MAX_BEAMS = 48;
    static final int RESCAN_TICKS = 20;
    /** 地図の真ん中からこの升数の中で切れ目を探す(8 × 44 = 約 350 ブロック)。 */
    static final int SEARCH_RADIUS = 44;
    /** 切れ目とみなす: 周り(半径 3 升)のうち雲の割合。 */
    static final float ENCLOSED = 0.5F;
    /** これより広い空き(升の数)は切れ目でなく青空。 */
    static final int MAX_GAP_CELLS = 36;
    /** 筋 1 本の芯の濃さ(加算)。 */
    static final float ALPHA = 0.04F;
    static final float NEAR_FROM = 50.0F;
    static final float NEAR_TO = 220.0F;
    /** 雨が止んでから空気が乾くまで(tick)。 */
    static final int AFTERGLOW_TICKS = 6000;
    static final float FADE_FROM = 260.0F;
    static final float FADE_TO = 420.0F;

    private record Beam(double fx, double fz, float width, float weight) {
    }

    private static List<Beam> beams = List.of();
    private static CloudShadows.Map scanned;
    private static int ticks;
    private static float afterglow;

    static void clear() {
        beams = List.of();
        scanned = null;
    }

    /** 毎 tick(呼び手は {@link Atmosphere})。 */
    static void tick(ClientLevel level) {
        float rain = CloudSky.rawRain(level, 1.0F);
        afterglow = rain > 0.2F ? 1.0F : Math.max(0.0F, afterglow - 1.0F / AFTERGLOW_TICKS);
        if (++ticks % RESCAN_TICKS != 0) {
            return;
        }
        CloudShadows.Map m = CloudShadows.current();
        if (m == null || CloudShadows.base() <= 0.0F || DesignData.sky().rays <= 0.0F) {
            clear();
            return;
        }
        if (m != scanned) {
            scanned = m;
            beams = scan(m);
        }
    }

    /** 地図から雲の切れ目を探す。 */
    private static List<Beam> scan(CloudShadows.Map m) {
        int n = CloudShadows.SIZE;
        float[] v = m.values;
        int[] sum = new int[(n + 1) * (n + 1)];
        for (int j = 0; j < n; j++) {
            int row = 0;
            for (int i = 0; i < n; i++) {
                row += v[j * n + i] >= 0.05F ? 1 : 0;
                sum[(j + 1) * (n + 1) + i + 1] = sum[j * (n + 1) + i + 1] + row;
            }
        }
        int c = n / 2;
        boolean[] candidate = new boolean[n * n];
        for (int j = Math.max(3, c - SEARCH_RADIUS); j < Math.min(n - 3, c + SEARCH_RADIUS); j++) {
            for (int i = Math.max(3, c - SEARCH_RADIUS); i < Math.min(n - 3, c + SEARCH_RADIUS); i++) {
                if (v[j * n + i] >= 0.05F) {
                    continue;
                }
                int x0 = i - 3;
                int z0 = j - 3;
                int x1 = i + 4;
                int z1 = j + 4;
                int clouds = sum[z1 * (n + 1) + x1] - sum[z0 * (n + 1) + x1] - sum[z1 * (n + 1) + x0] + sum[z0 * (n + 1) + x0];
                candidate[j * n + i] = clouds >= ENCLOSED * 49.0F;
            }
        }
        List<Beam> found = new ArrayList<>();
        boolean[] seen = new boolean[n * n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int k = 0; k < n * n; k++) {
            if (!candidate[k] || seen[k]) {
                continue;
            }
            int cells = 0;
            double si = 0.0;
            double sj = 0.0;
            seen[k] = true;
            queue.add(k);
            while (!queue.isEmpty()) {
                int q = queue.poll();
                int qi = q % n;
                int qj = q / n;
                cells++;
                si += qi;
                sj += qj;
                int[] next = {q - 1, q + 1, q - n, q + n};
                for (int d = 0; d < 4; d++) {
                    int r = next[d];
                    if (d < 2 && r / n != qj || r < 0 || r >= n * n || seen[r] || !candidate[r]) {
                        continue;
                    }
                    seen[r] = true;
                    queue.add(r);
                }
            }
            if (cells > MAX_GAP_CELLS) {
                continue;
            }
            double ci = si / cells;
            double cj = sj / cells;
            double fx = (m.originX + ci + 0.5) * CloudField.CELL_XZ;
            double fz = (m.originZ + cj + 0.5) * CloudField.CELL_XZ;
            float width = Mth.clamp(CloudField.CELL_XZ * (float) Math.sqrt(cells) * 1.1F, CloudField.CELL_XZ, 40.0F);
            double dist = Math.hypot(ci - c, cj - c);
            found.add(new Beam(fx, fz, width, (float) dist));
        }
        found.sort((a, b) -> Float.compare(a.weight(), b.weight()));
        int max = VisualQuality.shaftCap(MAX_BEAMS);
        return found.size() > max ? List.copyOf(found.subList(0, max)) : List.copyOf(found);
    }

    /** 空気の湿り(0.3〜1): 雨の後・朝・雲の多い日ほど筋が濃い。 */
    private static float humidity(ClientLevel level, float coverage) {
        long day = Math.floorMod(level.getDayTime(), 24000L);
        float morning = day < 4000L ? 0.7F * (1.0F - day / 4000.0F) : 0.0F;
        float cloudy = 0.7F * SunLight.smooth(Mth.clamp((coverage - 0.5F) / 0.3F, 0.0F, 1.0F));
        return 0.3F + 0.7F * Math.max(afterglow, Math.max(morning, cloudy));
    }

    /** 足し算の光の回(呼び手は {@link Atmosphere}。描き先は {@link AddLight#drawToMainTarget} で決まっている)。 */
    static void render(RenderLevelStageEvent event) {
        lastStrength = 0.0F;
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || beams.isEmpty()) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float coverage = VoxelClouds.coverage(level, partialTick);
        float covered = SunLight.smooth(Mth.clamp((coverage - 0.15F) / 0.15F, 0.0F, 1.0F))
                * (1.0F - SunLight.smooth(Mth.clamp((coverage - 0.9F) / 0.07F, 0.0F, 1.0F)));
        Vec3 eye = event.getCamera().getPosition();
        int skyLight = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye));
        float exposure = SunLight.smooth(Mth.clamp((skyLight - 9.0F) / 5.0F, 0.0F, 1.0F));
        Vec3 sun = SunLight.sunDirection(level, partialTick);
        // G5 光る胞子の夜(11-08): 夜は月の光の柱。雲の影の地図も月の向きで作ってある({@link CloudShadows#lightDirection})。
        boolean moon = sun.y < 0.0 && GlowNight.amount() > 0.001F;
        var night = DesignData.atmosphere().glowNight();
        float forest = 1.0F - (moon ? 1.0F - night.forestKeep() : 0.9F) * ForestPresence.openOvergrown();
        float strength = (moon ? night.rays() * GlowNight.amount() * (SunLight.MOON_FLOOR + (1.0F - SunLight.MOON_FLOOR) * level.getMoonBrightness())
                : DesignData.sky().rays * humidity(level, coverage)
                * (0.5F + 0.5F * Mth.clamp((1.0F - CloudShadows.sunVisibility()) / CloudShadows.SUN_BLOCK, 0.0F, 1.0F)))
                * CloudShadows.base() * covered * exposure * forest * (1.0F - ForestPresence.underground())
                // 雲の中では見えない(霧に沈む。改善 ②)。
                * (1.0F - com.yayuy.overgrownvegetation.client.sky.CloudInterior.amount(partialTick));
        if (strength <= 0.003F) {
            return;
        }
        Vec3 light = moon ? sun.scale(-1.0) : sun;
        if (light.y < 0.05) {
            return;
        }
        float r;
        float g;
        float b;
        if (moon) {
            float[] c = night.rayColor();
            r = c[0];
            g = c[1];
            b = c[2];
        } else {
            float warm = 1.0F - SunLight.smooth(Mth.clamp(((float) sun.y - 0.15F) / 0.5F, 0.0F, 1.0F));
            r = 1.0F;
            g = Mth.lerp(warm, 0.95F, 0.78F);
            b = Mth.lerp(warm, 0.84F, 0.5F);
        }
        long gameTime = level.getGameTime();
        double windX = CloudField.offsetX((double) gameTime + partialTick);
        double windZ = CloudField.offsetZ((double) gameTime + partialTick);
        double bottomY = Math.min(eye.y - 24.0, 62.0);
        double length = (CloudField.LOW_BASE - bottomY) / light.y;
        Vec3 down = light.scale(-1.0);
        lastStrength = strength;
        lastWindX = windX;
        lastWindZ = windZ;
        lastDown = down;

        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = buffers.getBuffer(AddLight.renderType());
        for (Beam beam : beams) {
            Vec3 top = new Vec3(beam.fx() + windX, CloudField.LOW_BASE, beam.fz() + windZ);
            ribbon(c, top, down, length, beam.width(), eye, light, ALPHA * strength, r, g, b);
        }
        buffers.endBatch(AddLight.renderType());
    }

    /** このフレームの筋の強さ(0 = 描いていない)と、筋の位置を世界へ戻す風の分・下る向き(G4・G5 の粒が読む)。 */
    private static float lastStrength;
    private static double lastWindX;
    private static double lastWindZ;
    private static Vec3 lastDown = new Vec3(0.0, -1.0, 0.0);

    /** このフレームに筋を描いたか(G4 の光る胞子の波)。 */
    static boolean lit() {
        return lastStrength > 0.003F && !beams.isEmpty();
    }

    /**
     * 点 (x, y, z) がどれかの筋の中にどれだけ入っているか(0〜1、筋の芯で 1、縁で 0)× 筋の強さ(1 で頭打ち)。G4 胞子の風の粒を光らせる。
     * 筋の幅は描くときと同じ(下ほど 25% 広い)。
     */
    static float glowAt(double x, double y, double z) {
        if (lastStrength <= 0.003F) {
            return 0.0F;
        }
        float best = 0.0F;
        double length = (CloudField.LOW_BASE - y) / Math.max(0.05, -lastDown.y);
        for (Beam beam : beams) {
            double tx = beam.fx() + lastWindX;
            double tz = beam.fz() + lastWindZ;
            double vx = x - tx;
            double vy = y - CloudField.LOW_BASE;
            double vz = z - tz;
            double t = vx * lastDown.x + vy * lastDown.y + vz * lastDown.z;
            if (t <= 0.0) {
                continue;
            }
            double dx = vx - lastDown.x * t;
            double dy = vy - lastDown.y * t;
            double dz = vz - lastDown.z * t;
            double half = beam.width() * 0.5 * (1.0 + 0.25 * Math.min(1.0, t / Math.max(1.0, length)));
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) / half;
            if (d < 1.0) {
                best = Math.max(best, 1.0F - SunLight.smooth((float) d));
            }
        }
        return best * Math.min(1.0F, lastStrength * 2.0F);
    }

    /** 描いている筋の数(G5 の柱の中の粒)。 */
    static int beamCount() {
        return lastStrength > 0.003F ? beams.size() : 0;
    }

    /** i 番目の筋の、高さ y での芯の点(out[0], out[2])と半幅(out[1])。G5 の柱の中の粒。 */
    static void axisAt(int i, double y, double[] out) {
        Beam beam = beams.get(i);
        double t = (y - CloudField.LOW_BASE) / Math.min(-0.05, lastDown.y);
        out[0] = beam.fx() + lastWindX + lastDown.x * t;
        out[2] = beam.fz() + lastWindZ + lastDown.z * t;
        out[1] = beam.width() * 0.5;
    }

    /** 雨上がりの湿り(1 = 雨の最中・止んだ直後 → {@link #AFTERGLOW_TICKS} で 0)。 */
    static float afterglow() {
        return afterglow;
    }

    /** 1 本の筋(カメラを向く帯)。横は 縁 0 → 中ほど → 芯 の山なり、縦は雲の底で湧き、下の 4 割で薄れる。森の霞の向こうは薄める。 */
    private static void ribbon(VertexConsumer c, Vec3 top, Vec3 dir, double length, float width, Vec3 eye, Vec3 sun, float alpha,
                               float r, float g, float b) {
        final int rows = 10;
        float[] prev = null;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            Vec3 p = top.add(dir.scale(length * t));
            Vec3 toEye = eye.subtract(p);
            Vec3 side = dir.cross(toEye);
            double len = side.length();
            if (len < 1.0E-5) {
                prev = null;
                continue;
            }
            double sinAngle = len / toEye.length();
            float facing = SunLight.smooth(Mth.clamp((float) ((sinAngle - 0.12) / 0.33), 0.0F, 1.0F));
            float half = width * 0.5F * (1.0F + 0.25F * t);
            side = side.scale(half / len);
            double horizontal = Math.hypot(p.x - eye.x, p.z - eye.z);
            float dist = (float) toEye.length();
            float near = 0.2F + 0.8F * SunLight.smooth(Mth.clamp((dist - NEAR_FROM) / (NEAR_TO - NEAR_FROM), 0.0F, 1.0F));
            float far = 1.0F - SunLight.smooth(Mth.clamp((float) ((horizontal - FADE_FROM) / (FADE_TO - FADE_FROM)), 0.0F, 1.0F));
            float a = alpha * facing * far * near * (1.0F - MistView.fogAmount(dist))
                    * SunLight.smooth(Mth.clamp(t / 0.06F, 0.0F, 1.0F))
                    * (1.0F - SunLight.smooth(Mth.clamp((t - 0.6F) / 0.4F, 0.0F, 1.0F)))
                    * SunLight.phase(-toEye.x, -toEye.y, -toEye.z, sun);
            a = Math.min(a, 1.0F);
            float cx = (float) (p.x - eye.x);
            float cy = (float) (p.y - eye.y);
            float cz = (float) (p.z - eye.z);
            float sx = (float) side.x;
            float sy = (float) side.y;
            float sz = (float) side.z;
            float[] row = {
                    cx - sx, cy - sy, cz - sz,
                    cx - sx * 0.5F, cy - sy * 0.5F, cz - sz * 0.5F,
                    cx, cy, cz,
                    cx + sx * 0.5F, cy + sy * 0.5F, cz + sz * 0.5F,
                    cx + sx, cy + sy, cz + sz,
                    a};
            if (prev != null) {
                float pa = prev[15];
                quad(c, prev, 0, 0.0F, row, 0, 0.0F, prev, 3, pa * 0.6F, row, 3, a * 0.6F, r, g, b);
                quad(c, prev, 3, pa * 0.6F, row, 3, a * 0.6F, prev, 6, pa, row, 6, a, r, g, b);
                quad(c, prev, 6, pa, row, 6, a, prev, 9, pa * 0.6F, row, 9, a * 0.6F, r, g, b);
                quad(c, prev, 9, pa * 0.6F, row, 9, a * 0.6F, prev, 12, 0.0F, row, 12, 0.0F, r, g, b);
            }
            prev = row;
        }
    }

    private static void quad(VertexConsumer c, float[] p0, int i0, float a0, float[] p1, int i1, float a1,
                             float[] p2, int i2, float a2, float[] p3, int i3, float a3, float r, float g, float b) {
        c.addVertex(p0[i0], p0[i0 + 1], p0[i0 + 2]).setColor(r, g, b, a0);
        c.addVertex(p1[i1], p1[i1 + 1], p1[i1 + 2]).setColor(r, g, b, a1);
        c.addVertex(p3[i3], p3[i3 + 1], p3[i3 + 2]).setColor(r, g, b, a3);
        c.addVertex(p2[i2], p2[i2 + 1], p2[i2 + 2]).setColor(r, g, b, a2);
    }

    static int count() {
        return beams.size();
    }

    private CrepuscularRays() {
    }
}
