package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereEvents;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Random;

/**
 * <b>G5 光る胞子の夜</b>(過繁茂の密林の環境ギミック。2026-11-01 ユーザー選択、2026-11-08「雲と一緒に」で出る条件と月の光の柱を足した)。
 *
 * <p>設定: 森が胞子を放つ様子を、美しくも不気味な見せ場にする。夕方の光の粒({@link DuskMotes})の拡張で、年に数回の夜。
 *
 * <ul>
 *     <li><b>いつ</b>: 雨上がり(本当の雨が止んでから {@code glowNight.wetMinutes} 分以内に夜を迎えた。湿りの判定は薄明光線の雨上がりと同じく本当の雨
 *     {@link CloudSky#rawRain} で見る)で、雲に切れ目がある夜(雲の量 {@code gapMin}〜{@code gapMax})。夜ごとのくじ {@code chance}
 *     (夜の番号とワールドの雲の種で決まるので、入り直しても同じ答え)。起きた夜は明け方まで続き、また雨が降れば止む。</li>
 *     <li><b>月の光の柱</b>: この夜だけ雲の影の地図を月の向きで作り({@link CloudShadows#lightDirection})、薄明光線({@link CrepuscularRays})が
 *     雲の切れ目から青白い柱を下ろす。地形にも月の雲の影を薄く落とす({@code moonShadow})。</li>
 *     <li><b>降る光る胞子</b>: カメラのまわりに林冠から降る粒(加算の光、青緑寄りの黄緑 2 色)と、近くの月の柱の中を降る粒。柱の中の粒は明るい。
 *     過繁茂の近く({@link ForestPresence#overgrown})だけ。夕方の光の粒もこの夜は一晩中出す({@code duskBoost})。</li>
 * </ul>
 * 雰囲気の演出がオフなら呼ばれない。重さの段で粒の数を減らし、「低」では柱の中の粒を出さない。数値は {@code atmosphere.json glowNight}(すべて仮決め)。
 * 0.2.0 のワールドイベント「月夜に光る胞子がマリンスノーのように降る」とは、見た目の部品(降る光る粒・月の柱)を共有できる作り(報告の 4 節)。
 */
public final class GlowNight {

    /** 開発用の上書き: 0 = 自然に任せる、1 = 夜なら起こす(雨上がり・くじ・雲の切れ目を見ない)、2 = 起こさない。 */
    private static int force;
    private static float amount;
    private static long sinceRain = Long.MAX_VALUE / 2;
    /** 起きた夜の番号(起きていなければ Long.MIN_VALUE)。 */
    private static long latched = Long.MIN_VALUE;
    private static boolean night;
    private static float gaps;
    private static double driftX;
    private static double driftZ;
    /** カメラの所が過繁茂のバイオームか(0〜1 にならしたもの。林冠の上から見下ろしても降る)。 */
    private static float biome;

    private static final Random RANDOM = new Random();
    private static float[] fx = new float[0];
    private static float[] fz = new float[0];
    private static float[] fh = new float[0];
    private static float[] fph = new float[0];
    private static float[] px = new float[0];
    private static float[] pz = new float[0];
    private static float[] ph = new float[0];
    private static final double[] AXIS = new double[3];

    private GlowNight() {
    }

    private static AtmosphereEvents.GlowNightParams params() {
        return DesignData.atmosphere().glowNight();
    }

    /** 今の出方(0〜1)。月の雲の影・月の柱・粒が読む。 */
    public static float amount() {
        return amount;
    }

    static void clear() {
        amount = 0.0F;
        sinceRain = Long.MAX_VALUE / 2;
        latched = Long.MIN_VALUE;
    }

    /** 夜の番号(日没 18 時から次の日の 17 時 59 分までを同じ番号に)。 */
    static long nightIndex(long dayTime) {
        return Math.floorDiv(dayTime + 12000L, 24000L);
    }

    /** 夜ごとのくじ(0〜1)。夜の番号とワールドの雲の種から。 */
    static float roll(long nightIndex, long seed) {
        long h = nightIndex * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL + 0x51A7E5L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, LocalPlayer player) {
        AtmosphereEvents.GlowNightParams p = params();
        float rain = CloudSky.rawRain(level, 1.0F);
        if (rain > 0.2F) {
            sinceRain = 0L;
        } else if (sinceRain < Long.MAX_VALUE / 2) {
            sinceRain++;
        }
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        night = sun.y < -0.02;
        long idx = nightIndex(level.getDayTime());
        float coverage = CloudSky.coverage(level, 1.0F);
        gaps = SunLight.smooth(Mth.clamp((coverage - p.gapMin()) / 0.08F, 0.0F, 1.0F))
                * (1.0F - SunLight.smooth(Mth.clamp((coverage - p.gapMax()) / 0.06F, 0.0F, 1.0F)));
        boolean wet = sinceRain < (long) (p.wetMinutes() * 1200.0F);
        if (force == 0 && p.enabled() && night && wet && rain < 0.2F && latched != idx && roll(idx, CloudField.seed()) < p.chance()) {
            latched = idx;
        }
        float target;
        if (force == 1) {
            target = night ? 1.0F : 0.0F;
        } else if (force == 2 || !p.enabled()) {
            target = 0.0F;
        } else {
            target = latched == idx && night && rain < 0.2F ? gaps : 0.0F;
        }
        amount += (target - amount) / Math.max(1.0F, p.fadeSeconds() * 20.0F);
        if (amount < 0.0005F && target == 0.0F) {
            amount = 0.0F;
        }
        boolean overgrown = level.hasChunkAt(player.blockPosition()) && com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(player.blockPosition()));
        biome += ((overgrown ? 1.0F : 0.0F) - biome) * 0.02F;
        if (amount > 0.0F) {
            // 粒を卓越風で少しずつ流す(林の風の場のカメラの所の速さ。粒は軽いので弱く)。
            double[] v = new double[2];
            Wind.current().velocity(player.getX(), player.getZ(), Atmosphere.time(0.0F), v);
            driftX += v[0] * 0.06;
            driftZ += v[1] * 0.06;
        }
    }

    private static void ensure(int fall, int pillar) {
        if (fx.length != fall) {
            fx = fill(fall);
            fz = fill(fall);
            fh = fill(fall);
            fph = fill(fall);
        }
        if (px.length != pillar) {
            px = fill(pillar);
            pz = fill(pillar);
            ph = fill(pillar);
        }
    }

    private static float[] fill(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = RANDOM.nextFloat();
        }
        return a;
    }

    private static double wrap(double v, double size) {
        return v - Math.floor(v / size) * size;
    }

    /** 足し算の光の回({@link Atmosphere} の AddLight の中、薄明光線の後)。 */
    static void render(ClientLevel level, float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        AtmosphereEvents.GlowNightParams p = params();
        float forest = Math.max(ForestPresence.overgrown(), biome) * (1.0F - ForestPresence.underground());
        if (amount <= 0.002F || forest <= 0.01F) {
            return;
        }
        int tier = Math.min(2, VisualQuality.atmosphereTier());
        ensure(p.counts()[tier], p.pillarCounts()[tier]);
        double t = Atmosphere.time(partialTick);
        float k = amount * forest * p.brightness();
        float[] c1 = p.color();
        float[] c2 = p.color2();

        // 林冠から降る粒(カメラのまわりの繰り返しの箱。世界に固定し、下へ落ちて下の縁から上の縁へ戻る)。
        double box = p.boxHalf() * 2.0;
        double hRange = p.heightBelow() + p.heightAbove();
        double y0 = cam.y - p.heightBelow();
        double fall = p.fallSpeed() / 20.0 * t;
        for (int i = 0; i < fx.length; i++) {
            float q = fph[i];
            double x = cam.x - p.boxHalf() + wrap(fx[i] * box + driftX - (cam.x - p.boxHalf()), box);
            double z = cam.z - p.boxHalf() + wrap(fz[i] * box + driftZ - (cam.z - p.boxHalf()), box);
            double y = y0 + wrap(fh[i] * hRange * 7.0 - fall * (0.7 + 0.6 * q) - y0, hRange);
            // ゆらゆら(綿毛のように横へ揺れながら落ちる)。
            double w = t * (0.02 + 0.02 * q) + q * 6.283;
            x += Math.sin(w) * 0.6;
            z += Math.cos(w * 0.9) * 0.6;
            float dxe = (float) (Math.abs(x - cam.x) / p.boxHalf());
            float dze = (float) (Math.abs(z - cam.z) / p.boxHalf());
            float edge = (1.0F - SunLight.smooth(Mth.clamp((Math.max(dxe, dze) - 0.7F) / 0.3F, 0.0F, 1.0F)));
            float yt = (float) ((y - y0) / hRange);
            float yEdge = SunLight.smooth(Mth.clamp(yt / 0.1F, 0.0F, 1.0F)) * (1.0F - SunLight.smooth(Mth.clamp((yt - 0.8F) / 0.2F, 0.0F, 1.0F)));
            float pulse = 0.55F + 0.45F * Mth.sin((float) (t * 0.05 + q * 30.0));
            float pillar = 1.0F + 2.0F * CrepuscularRays.glowAt(x, y, z);
            float a = k * edge * yEdge * pulse * pillar * 0.8F;
            if (a <= 0.01F) {
                continue;
            }
            float r = Mth.lerp(q, c1[0], c2[0]);
            float g = Mth.lerp(q, c1[1], c2[1]);
            float b = Mth.lerp(q, c1[2], c2[2]);
            LifeSprites.dot(x, y, z, p.size() * (0.7F + 0.6F * fh[i]), left, up, r, g, b, Math.min(1.0F, a));
            if (p.haloSize() > 0.0F && (i & 1) == 0) {
                LifeSprites.blob(x, y, z, p.haloSize(), 1.0F, left, up, r, g, b, Math.min(1.0F, a * 0.3F));
            }
        }

        // 月の柱の中を降る粒(近い柱から maxPillars 本。柱の足元の梢の上 50 ブロックから下へ)。
        int beams = Math.min(p.maxPillars(), CrepuscularRays.beamCount());
        if (px.length == 0 || beams == 0) {
            return;
        }
        int perBeam = Math.max(1, px.length);
        for (int j = 0; j < beams; j++) {
            CrepuscularRays.axisAt(j, cam.y, AXIS);
            double ax = AXIS[0];
            double az = AXIS[2];
            double half = AXIS[1];
            double dist = Math.hypot(ax - cam.x, az - cam.z);
            if (dist > 220.0) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(ax), Mth.floor(az));
            double base = top - 4.0;
            double span = 54.0;
            float far = 1.0F - SunLight.smooth(Mth.clamp((float) ((dist - 160.0) / 60.0), 0.0F, 1.0F));
            for (int i = 0; i < perBeam; i++) {
                float q = ph[i];
                double y = base + wrap(pz[i] * span * 5.0 + j * 7.3 - fall * 0.6 * (0.7 + 0.6 * q), span);
                CrepuscularRays.axisAt(j, y, AXIS);
                double r = half * 0.85 * Math.sqrt(px[i]);
                double ang = q * 6.283 + t * 0.004;
                double x = AXIS[0] + Math.cos(ang) * r;
                double z = AXIS[2] + Math.sin(ang) * r;
                float yt = (float) ((y - base) / span);
                float yEdge = SunLight.smooth(Mth.clamp(yt / 0.08F, 0.0F, 1.0F)) * (1.0F - SunLight.smooth(Mth.clamp((yt - 0.6F) / 0.4F, 0.0F, 1.0F)));
                float pulse = 0.6F + 0.4F * Mth.sin((float) (t * 0.06 + q * 25.0 + j));
                float a = amount * p.brightness() * yEdge * pulse * far * Math.min(1.0F, CrepuscularRays.glowAt(x, y, z) * 2.5F + 0.2F);
                if (a <= 0.01F) {
                    continue;
                }
                // 遠くでも点に見えるよう、距離に応じて少し大きく。
                float size = (float) Math.max(p.size() * 1.5, dist * 0.0035);
                float rr = Mth.lerp(q, c1[0], c2[0]);
                float gg = Mth.lerp(q, c1[1], c2[1]);
                float bb = Mth.lerp(q, c1[2], c2[2]);
                LifeSprites.dot(x, y, z, size, left, up, rr, gg, bb, Math.min(1.0F, a));
                if ((i & 1) == 0) {
                    LifeSprites.blob(x, y, z, size * 6.0F, 1.0F, left, up, rr, gg, bb, Math.min(1.0F, a * 0.3F));
                }
            }
        }
    }

    /** 開発用・{@code /ov fx glownight}: 0 = 自然に任せる、1 = 夜なら起こす、2 = 起こさない。 */
    public static void force(int mode) {
        force = Math.max(0, Math.min(2, mode));
    }

    public static String describe(ClientLevel level) {
        AtmosphereEvents.GlowNightParams p = params();
        long idx = level != null ? nightIndex(level.getDayTime()) : 0L;
        return String.format("光る胞子の夜 %.2f(%s)夜 %s 雨上がり %s 雲の切れ目 %.2f 今夜のくじ %.2f / %.2f 起きた夜 %s 月の柱 %d 本",
                amount, force == 1 ? "強制オン" : force == 2 ? "強制オフ" : "自然", night,
                sinceRain < (long) (p.wetMinutes() * 1200.0F) ? (sinceRain / 20) + " 秒前に止んだ" : "いいえ", gaps,
                roll(idx, CloudField.seed()), p.chance(), latched == idx ? "今夜" : "いいえ", CrepuscularRays.beamCount());
    }
}
