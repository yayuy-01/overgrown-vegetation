package com.yayuy.overgrownvegetation.data;

/**
 * 熱水の森(ネザーの汚染地)の見え方の設計値({@code heat_fx.json}、2026-11-10 熱水の森のシェーダー段階 1)。クライアントの描画だけが読む。
 * 読み手は {@code client/atmosphere/heat/}(度合い {@code HeatFxPresence}・暗さ {@code HeatFxShade}・霧 {@code HeatFxFog})。
 *
 * <p>ユーザー決定(11-10): 暗さ 0.55・霧の終わり 72・霧の色(案 3)・霧は横だけ・境目は広くゆっくり・昼夜の周期 20 分・空は昼に体ごと/夜は先と粒・
 * 地の模様は昼 40% / 夜 100%・夕と朝は空の光が紫へ。ほかの数値は仮決め(段階 2 の値 {@link Glow} もほぼ仮)。
 */
public final class HeatFxTable {

    public static final HeatFxTable DEFAULT = new HeatFxTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public PresenceJson presence;
        public ShadeJson shade;
        public FogJson fog;
        public GlowJson glow;
        public CycleJson cycle;
        public DayJson day;
    }

    /** 段階 2(11-10): 光る面の目印・脈動・偽の空の昼夜で光る面に掛ける明るさ。階級 = tintindex(1 空の先と粒・2 地の目印・3 地の粒・4 地・5 空の体)。 */
    public static final class GlowJson {
        /** 1 = 光る面に目印の色を返し、シェーダーが階級を見分ける(0 = 白のまま。脈動も昼夜の明るさも効かない)。 */
        public Double codes;
        /** 1 = 脈動する。 */
        public Double pulse;
        /** 脈動の周期(秒)と、位相をずらすなめらかな雑音の升の大きさ(ブロック)。 */
        public Double pulsePeriod;
        public Double pulseCell;
        /** 階級 1〜5 の脈動の深さ(d なら、いちばん暗いとき 1 − d 倍)。 */
        public double[] pulseDepth;
        /** 1 = 偽の空の昼夜がある(0 = いつも夜の姿)。 */
        public Double dayNight;
        /** 階級 1〜5 の明るさの倍率(昼・夜)。 */
        public double[] dayGain;
        public double[] nightGain;
        /** 昼に空の体(階級 5)をブロックの光 15 の明るさへ寄せる割合。 */
        public Double skyBodyDayLift;
        /** 夕と朝のいちばん紫のときに空の階級(1・5)に掛ける色。 */
        public double[] duskTint;
    }

    /** 偽の空の 1 日(tick。dayTime を 24000 で割った余りで、昼の始まりから)。 */
    public static final class CycleJson {
        public Double dayStart;
        public Double dayTicks;
        public Double duskTicks;
        public Double nightTicks;
        public Double dawnTicks;
    }

    /** 昼の暗さと霧(夜の値は shade・fog)。夕と朝の霧の紫。 */
    public static final class DayJson {
        public Double shadeStrength;
        public double[] fogColor;
        public Double fogEnd;
        /** 夕と朝のいちばん紫のときの霧の色。 */
        public double[] duskFogColor;
        /** 夕と朝に霧の色を紫へ寄せる割合(1 = duskFogColor まで・0 = 寄せない = 空の光る面だけ紫)。 */
        public Double duskFogMix;
    }

    /** 段階 2 の値(読みやすいように 1 つにまとめた)。 */
    public static final class Glow {
        public final boolean codes;
        public final boolean pulse;
        public final float pulsePeriod;
        public final float pulseCell;
        public final float[] pulseDepth;
        public final boolean dayNight;
        public final float[] dayGain;
        public final float[] nightGain;
        public final float skyBodyDayLift;
        public final float[] duskTint;
        public final float dayStart;
        public final float dayTicks;
        public final float duskTicks;
        public final float nightTicks;
        public final float dawnTicks;
        public final float dayShadeStrength;
        public final float[] dayFogColor;
        public final float dayFogEnd;
        public final float[] duskFogColor;
        public final float duskFogMix;

        Glow(GlowJson g, CycleJson c, DayJson d) {
            codes = v(g == null ? null : g.codes, 1.0) >= 0.5F;
            pulse = v(g == null ? null : g.pulse, 1.0) >= 0.5F;
            pulsePeriod = Math.max(0.5F, v(g == null ? null : g.pulsePeriod, 7.0));
            pulseCell = Math.max(0.5F, v(g == null ? null : g.pulseCell, 4.0));
            pulseDepth = arr(g == null ? null : g.pulseDepth, 5, new float[]{0.12F, 0.18F, 0.12F, 0.15F, 0.1F});
            dayNight = v(g == null ? null : g.dayNight, 1.0) >= 0.5F;
            dayGain = arr(g == null ? null : g.dayGain, 5, new float[]{1.0F, 0.4F, 0.4F, 0.4F, 1.0F});
            nightGain = arr(g == null ? null : g.nightGain, 5, new float[]{1.0F, 1.0F, 1.0F, 1.0F, 1.0F});
            skyBodyDayLift = v(g == null ? null : g.skyBodyDayLift, 0.7);
            duskTint = arr(g == null ? null : g.duskTint, 3, new float[]{0.85F, 0.55F, 1.0F});
            dayStart = v(c == null ? null : c.dayStart, 0.0);
            dayTicks = Math.max(1.0F, v(c == null ? null : c.dayTicks, 12000.0));
            duskTicks = Math.max(1.0F, v(c == null ? null : c.duskTicks, 1800.0));
            nightTicks = Math.max(1.0F, v(c == null ? null : c.nightTicks, 8400.0));
            dawnTicks = Math.max(1.0F, v(c == null ? null : c.dawnTicks, 1800.0));
            dayShadeStrength = v(d == null ? null : d.shadeStrength, 0.0);
            dayFogColor = arr(d == null ? null : d.fogColor, 3, new float[]{0.06F, 0.15F, 0.1F});
            dayFogEnd = v(d == null ? null : d.fogEnd, 128.0);
            duskFogColor = arr(d == null ? null : d.duskFogColor, 3, new float[]{0.035F, 0.02F, 0.05F});
            duskFogMix = v(d == null ? null : d.duskFogMix, 0.0);
        }

        private static float[] arr(double[] a, int n, float[] fallback) {
            if (a == null || a.length != n) {
                return fallback;
            }
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                out[i] = (float) a[i];
            }
            return out;
        }
    }

    private final Glow glow;

    /** 段階 2 の値(目印・脈動・昼夜)。 */
    public Glow glow() {
        return glow;
    }

    /** 「熱水の森の中にいる」の度合い。 */
    public static final class PresenceJson {
        /** 床を数える円の半径(ブロック。横の距離。近いほど重く数え、この距離で重み 0。11-10 追記 1 で箱 → 床の円へ)。 */
        public Double radius;
        /** 1 tick に調べる列の数(列ごとにカメラの少し上から下へ最初の床を探す)。 */
        public Double samplesPerTick;
        /** 床を探す深さ(カメラから下へ何ブロックまで)。 */
        public Double scanDown;
        /** 数えた数を忘れる速さ(tick。この長さで 1/e に)。 */
        public Double memoryTicks;
        /** 床のうち熱水の森のブロック(床の植物を含む)の重み付きの割合が、これ以下なら度合い 0・これ以上なら 1(なめらかな段)。 */
        public Double ratioLow;
        public Double ratioHigh;
        /** 度合いが目標を追う速さ(tick。この長さで差が 1/e に)。 */
        public Double followTicks;
    }

    /** 1. 光らない面を暗くする(ライトマップ)。 */
    public static final class ShadeJson {
        /** 暗くする強さ(0〜1)。ブロックの光 0 の面の明るさを (1 − 強さ) 倍にする。 */
        public Double strength;
        /** ブロックの光の段がこれ以上の面はほぼ元のまま(光る面は 4 以上・溶岩や松明に照らされた面)。 */
        public Double keepLevel;
    }

    /** 2. 霧。 */
    public static final class FogJson {
        /** 森の中の霧の色 [r, g, b](0〜1)。 */
        public double[] color;
        /** 霧の始まり・終わり(ブロック)。 */
        public Double start;
        public Double end;
        /** 1 = 霧を横の距離だけで測る(真上の天井 = 偽の空は霧に沈まない)。0 = 球(バニラのネザーと同じ)。 */
        public Double cylinder;
        /** 1 = 霧の終わりより先の区画も描かせる(Sodium は霧の色が不透明なら霧の終わりの先を描かない)。 */
        public Double keepFarSections;
    }

    private final float radius;
    private final float samplesPerTick;
    private final float scanDown;
    private final float memoryTicks;
    private final float ratioLow;
    private final float ratioHigh;
    private final float followTicks;
    private final float shadeStrength;
    private final float shadeKeepLevel;
    private final float[] fogColor;
    private final float fogStart;
    private final float fogEnd;
    private final boolean fogCylinder;
    private final boolean fogKeepFarSections;

    private HeatFxTable(Json j) {
        PresenceJson p = j == null ? null : j.presence;
        ShadeJson s = j == null ? null : j.shade;
        FogJson f = j == null ? null : j.fog;
        radius = Math.max(4.0F, v(p == null ? null : p.radius, 48.0));
        samplesPerTick = v(p == null ? null : p.samplesPerTick, 16.0);
        scanDown = Math.max(4.0F, v(p == null ? null : p.scanDown, 48.0));
        memoryTicks = Math.max(1.0F, v(p == null ? null : p.memoryTicks, 40.0));
        ratioLow = v(p == null ? null : p.ratioLow, 0.1);
        ratioHigh = Math.max(ratioLow + 0.01F, v(p == null ? null : p.ratioHigh, 0.7));
        followTicks = Math.max(1.0F, v(p == null ? null : p.followTicks, 60.0));
        shadeStrength = v(s == null ? null : s.strength, 0.55);
        shadeKeepLevel = Math.max(1.0F, v(s == null ? null : s.keepLevel, 5.0));
        double[] c = f == null ? null : f.color;
        fogColor = c != null && c.length == 3 ? new float[]{(float) c[0], (float) c[1], (float) c[2]} : new float[]{0.012F, 0.035F, 0.025F};
        fogStart = v(f == null ? null : f.start, 2.0);
        fogEnd = Math.max(fogStart + 1.0F, v(f == null ? null : f.end, 72.0));
        fogCylinder = v(f == null ? null : f.cylinder, 1.0) >= 0.5F;
        fogKeepFarSections = v(f == null ? null : f.keepFarSections, 1.0) >= 0.5F;
        glow = new Glow(j == null ? null : j.glow, j == null ? null : j.cycle, j == null ? null : j.day);
    }

    public static HeatFxTable fromJson(Json j) {
        return j == null ? DEFAULT : new HeatFxTable(j);
    }

    private static float v(Double d, double fallback) {
        return (float) (d == null ? fallback : d);
    }

    public float radius() {
        return radius;
    }

    public float scanDown() {
        return scanDown;
    }

    public float samplesPerTick() {
        return samplesPerTick;
    }

    public float memoryTicks() {
        return memoryTicks;
    }

    public float ratioLow() {
        return ratioLow;
    }

    public float ratioHigh() {
        return ratioHigh;
    }

    public float followTicks() {
        return followTicks;
    }

    public float shadeStrength() {
        return shadeStrength;
    }

    public float shadeKeepLevel() {
        return shadeKeepLevel;
    }

    /** 霧の色の成分(0 = r・1 = g・2 = b)。 */
    public float fogColor(int i) {
        return fogColor[i];
    }

    public float fogStart() {
        return fogStart;
    }

    public float fogEnd() {
        return fogEnd;
    }

    public boolean fogCylinder() {
        return fogCylinder;
    }

    public boolean fogKeepFarSections() {
        return fogKeepFarSections;
    }
}
