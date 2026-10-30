package com.yayuy.overgrownvegetation.data;

/**
 * 過繁茂の密林の雰囲気の設計値({@code atmosphere.json}、2026-10-30 作り直しの第 1 段「光の土台」)。正本は Vault
 * 「過繁茂の密林の雰囲気づくり(作り直しの一覧)」。読むのはクライアントの描画だけ(サーバーの明るさ・湧きは変わらない)。
 *
 * <p>節 {@code light} = 林床の持ち上げ・林冠の影・光の筋・光る胞子・木漏れ日。第 2 段(風)・第 3 段(霧)・第 4 段(空)は
 * 節を足していく({@code wind}・{@code fog}・{@code sky})。書いていない項目は既定値。どの値がユーザーの了承済みかは
 * {@code atmosphere.json} の {@code _comment} と Vault の一覧ノートにある。オン/オフと重さの段はクライアント設定
 * ({@code OvergrownClientConfig} の {@code atmosphere*})。
 */
public final class AtmosphereTable {

    public static final AtmosphereTable DEFAULT = new AtmosphereTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Light light;
        public WindJson wind;
        public SwayJson sway;
        public LeavesJson leaves;
        public SoundJson sound;
        public SporesJson spores;
    }

    /** 節 {@code light}。 */
    public static final class Light {
        /**
         * 森の中にいる度合い(提案 5、10-30): 頭上の覆いの割合が coverStart で 0・coverFull で 1。寄せる秒数(ちらつき止め)と、湿地の森の度合い。
         */
        public Double coverStart, coverFull, presenceSmoothSeconds, swampPresence;
        /** 林床の持ち上げ: 葉に削られた空の光を戻す割合と、空の光 0 段での寄せ方の倍率(7 段で 1)。 */
        public Double skyLift, skyLiftDark;
        /** 林冠の影: 全部影のときの明るさ(ふつう・逆光に慣れきったとき)・青緑に寄せる強さ・森の中で日向以外をいつも寄せる強さ・草や幹にも落とす強さ。 */
        public Double shade, shadeBacklit, shadeTint, forestTint, surfaceShade;
        /** 空気の中の光の向きの差: 背 phaseMin・太陽の方 phaseMax、q^phasePower。 */
        public Double phaseMin, phaseMax, phasePower;
        /** 光の筋と光る胞子・木漏れ日の明るさ、正午のぼかしの強さ、月光の筋の強さ(満月で昼の何倍か)。 */
        public Double intensity, noonSoftness, moonStrength;
        /** 光の筋の上限(本)・光る胞子の上限(粒)・木漏れ日のマスの上限。重さの段(高・中・低)で掛ける前の数。 */
        public Integer maxShafts, maxMotes, maxDapples;
        /** 提案 1(10-30): 正午の筋の強さ(朝夕を 1 として)と、正午に筋の上を薄くして短く見せる割合。 */
        public Double noonShaftStrength, noonShaftShorten;
        /**
         * 提案 2(10-30): 空気に何かあるときだけ筋がはっきり。乾いた午後の濃さ hazeDry、朝(5〜9 時)の湿り気・雨上がり(雨が止んでから afterRainMinutes 分)・
         * 胞子の濃さ(sporeHazeFrom % から sporeHazeFull % で最大)のいちばん強いもので 1 へ寄せる。
         */
        public Double hazeDry, afterRainMinutes, sporeHazeFrom, sporeHazeFull;
        /** 提案 3(10-30): 木漏れ日の大きさ(低い梢・高い梢 dappleHighRise ブロック以上)と、低い梢の斑点の縁のくっきりさ(0〜1)。 */
        public Double dappleLowScale, dappleHighScale, dappleHighRise, dappleLowSharpness;
        /** 提案 4(10-30): 夕方・夜の影の色の比(R, G, B。明るさはそろえる)と、夜の青緑(銀青)の強さ。 */
        public double[] duskTint, nightTint;
        public Double nightTintStrength;
    }

    /**
     * 節 {@code wind}(第 2 段「動き」の風の場、10-30 夜)。突風の間隔(秒)・雷雨で間隔に掛ける倍率・凪の空気の動きの強さの幅・
     * 突風の強さの幅・前線の進む速さ(ブロック/秒)・前線の横の半幅・山の前と後ろの長さ・生まれる所(風上へのブロック)・
     * 雷雨の間の凪の強さ(風の音が鳴り続ける)・前線が過ぎたあとの粒の余韻(秒)・全体の倍率。
     */
    public static final class WindJson {
        public Double gustIntervalMin, gustIntervalMax, stormIntervalScale, calmMin, calmMax, gustStrengthMin, gustStrengthMax;
        public Double frontSpeedMin, frontSpeedMax, frontHalfWidthMin, frontHalfWidthMax, frontAheadMin, frontAheadMax;
        public Double frontBehindMin, frontBehindMax, spawnDistanceMin, spawnDistanceMax, stormCalm, afterglowSeconds, strength;
    }

    /** 節 {@code sway}: 揺れの倍率・風の音がこの大きさで揺れが全開・揺れの立ち上がりと収まり(秒)・風下へ押される量・枝の揺れ・はためき(ブロック)。 */
    public static final class SwayJson {
        public Double amount, gateFullLevel, riseSeconds, fallSeconds, push, branchCalm, branchGust, flutter;
    }

    /**
     * 節 {@code leaves}: 落ち葉を出す半径・生きている数の上限(重さの段の粒の量を掛ける前)・突風のさなかの 1 秒あたりの数・凪の数と、
     * 葉の色の揺らぎ(一覧 24)の成葉の青緑の強さ・若葉の黄緑の強さ(0〜1。10-17 了承の 1)。
     */
    public static final class LeavesJson {
        public Double radius, perSecondGust, perSecondCalm, toneStrength, youngStrength;
        public Integer maxAlive;
    }

    /** 節 {@code sound}: 風の音の大きさ・鳴らし始める風の強さ(凪はこれ以下で無音)・雷雨で大きさに足す倍率(1 + stormVolume × 嵐の度合い)。 */
    public static final class SoundJson {
        public Double volume, calmThreshold, stormVolume;
    }

    /**
     * 節 {@code spores}: 環境の胞子の粒の数(重さの段 高・中・低)・粒の立方体の一辺(ブロック)・粒を置く箱の半幅と高さ(ブロック)・
     * 地下で残す割合・夜の明るさ・光の筋や木漏れ日の日向で明るくする割合(10-30 夜 提案 3)。
     */
    public static final class SporesJson {
        public int[] counts;
        public Double size, boxHalf, boxHeight, undergroundKeep, nightBrightness, litBoost;
    }

    /** 風の場の値(秒・ブロックの単位のまま)。 */
    public record WindParams(float gustIntervalMin, float gustIntervalMax, float stormIntervalScale, float calmMin, float calmMax,
                             float gustStrengthMin, float gustStrengthMax, float frontSpeedMin, float frontSpeedMax,
                             float frontHalfWidthMin, float frontHalfWidthMax, float frontAheadMin, float frontAheadMax,
                             float frontBehindMin, float frontBehindMax, float spawnDistanceMin, float spawnDistanceMax,
                             float stormCalm, float afterglowSeconds, float strength) {
        static WindParams of(WindJson w) {
            WindJson v = w != null ? w : new WindJson();
            float iMin = clamp(v.gustIntervalMin, 60.0, 5.0, 3600.0);
            float sMin = clamp(v.gustStrengthMin, 0.3, 0.0, 1.2);
            float spMin = clamp(v.frontSpeedMin, 3.0, 0.5, 30.0);
            float hwMin = clamp(v.frontHalfWidthMin, 40.0, 4.0, 400.0);
            float aMin = clamp(v.frontAheadMin, 4.0, 0.5, 100.0);
            float bMin = clamp(v.frontBehindMin, 12.0, 0.5, 200.0);
            float dMin = clamp(v.spawnDistanceMin, 45.0, 8.0, 200.0);
            float cMin = clamp(v.calmMin, 0.07, 0.0, 0.5);
            return new WindParams(iMin, Math.max(iMin, clamp(v.gustIntervalMax, 120.0, 5.0, 3600.0)),
                    clamp(v.stormIntervalScale, 0.7, 0.1, 1.0), cMin, Math.max(cMin, clamp(v.calmMax, 0.17, 0.0, 0.5)),
                    sMin, Math.max(sMin, clamp(v.gustStrengthMax, 0.75, 0.0, 1.2)),
                    spMin, Math.max(spMin, clamp(v.frontSpeedMax, 6.0, 0.5, 30.0)),
                    hwMin, Math.max(hwMin, clamp(v.frontHalfWidthMax, 70.0, 4.0, 400.0)),
                    aMin, Math.max(aMin, clamp(v.frontAheadMax, 6.0, 0.5, 100.0)),
                    bMin, Math.max(bMin, clamp(v.frontBehindMax, 20.0, 0.5, 200.0)),
                    dMin, Math.max(dMin, clamp(v.spawnDistanceMax, 60.0, 8.0, 200.0)),
                    clamp(v.stormCalm, 0.45, 0.0, 1.2), clamp(v.afterglowSeconds, 10.0, 0.0, 60.0), clamp(v.strength, 1.0, 0.0, 3.0));
        }
    }

    /** 葉と草の揺れの値。 */
    public record SwayParams(float amount, float gateFullLevel, float riseSeconds, float fallSeconds, float push,
                             float branchCalm, float branchGust, float flutter) {
        static SwayParams of(SwayJson s) {
            SwayJson v = s != null ? s : new SwayJson();
            return new SwayParams(clamp(v.amount, 1.0, 0.0, 3.0), clamp(v.gateFullLevel, 0.15, 0.01, 1.0),
                    clamp(v.riseSeconds, 0.6, 0.05, 10.0), clamp(v.fallSeconds, 1.3, 0.05, 10.0), clamp(v.push, 0.05, 0.0, 0.3),
                    clamp(v.branchCalm, 0.012, 0.0, 0.2), clamp(v.branchGust, 0.04, 0.0, 0.3), clamp(v.flutter, 0.015, 0.0, 0.2));
        }
    }

    /** 落ち葉の値。 */
    public record LeavesParams(float radius, int maxAlive, float perSecondGust, float perSecondCalm, float toneStrength,
                               float youngStrength) {
        static LeavesParams of(LeavesJson l) {
            LeavesJson v = l != null ? l : new LeavesJson();
            return new LeavesParams(clamp(v.radius, 16.0, 4.0, 48.0), v.maxAlive != null ? Math.max(0, Math.min(400, v.maxAlive)) : 90,
                    clamp(v.perSecondGust, 24.0, 0.0, 200.0), clamp(v.perSecondCalm, 0.3, 0.0, 20.0),
                    clamp(v.toneStrength, 1.0, 0.0, 1.0), clamp(v.youngStrength, 1.0, 0.0, 1.0));
        }
    }

    /** 風の音の値。 */
    public record SoundParams(float volume, float calmThreshold, float stormVolume) {
        static SoundParams of(SoundJson s) {
            SoundJson v = s != null ? s : new SoundJson();
            return new SoundParams(clamp(v.volume, 1.0, 0.0, 2.0), clamp(v.calmThreshold, 0.22, 0.0, 1.0), clamp(v.stormVolume, 1.5, 0.0, 4.0));
        }
    }

    /** 環境の胞子の値。{@code counts} は 高・中・低 の順。 */
    public record SporesParams(int[] counts, float size, float boxHalf, float boxHeight, float undergroundKeep, float nightBrightness,
                               float litBoost) {
        static SporesParams of(SporesJson s) {
            SporesJson v = s != null ? s : new SporesJson();
            int[] def = {1500, 900, 400};
            int[] c = new int[3];
            for (int i = 0; i < 3; i++) {
                c[i] = Math.max(0, Math.min(4096, v.counts != null && v.counts.length > i ? v.counts[i] : def[i]));
            }
            return new SporesParams(c, clamp(v.size, 0.045, 0.01, 0.3), clamp(v.boxHalf, 20.0, 4.0, 64.0),
                    clamp(v.boxHeight, 14.0, 4.0, 48.0), clamp(v.undergroundKeep, 0.25, 0.0, 1.0), clamp(v.nightBrightness, 0.35, 0.0, 1.0),
                    clamp(v.litBoost, 0.5, 0.0, 2.0));
        }
    }

    private final float coverStart, coverFull, presenceSmoothSeconds, swampPresence;
    private final float noonShaftStrength, noonShaftShorten, hazeDry, afterRainMinutes, sporeHazeFrom, sporeHazeFull;
    private final float dappleLowScale, dappleHighScale, dappleHighRise, dappleLowSharpness, nightTintStrength;
    private final float[] duskTint, nightTint;
    private final float skyLift, skyLiftDark;
    private final float shade, shadeBacklit, shadeTint, forestTint, surfaceShade;
    private final float phaseMin, phaseMax, phasePower;
    private final float intensity, noonSoftness, moonStrength;
    private final int maxShafts, maxMotes, maxDapples;
    private final WindParams wind;
    private final SwayParams sway;
    private final LeavesParams leaves;
    private final SoundParams sound;
    private final SporesParams spores;

    private AtmosphereTable(Json j) {
        Light l = j != null && j.light != null ? j.light : new Light();
        this.coverStart = clamp(l.coverStart, 0.15, 0.0, 1.0);
        this.coverFull = Math.max(this.coverStart + 0.01F, clamp(l.coverFull, 0.7, 0.0, 1.0));
        this.presenceSmoothSeconds = clamp(l.presenceSmoothSeconds, 1.5, 0.0, 60.0);
        this.noonShaftStrength = clamp(l.noonShaftStrength, 0.6, 0.0, 2.0);
        this.noonShaftShorten = clamp(l.noonShaftShorten, 0.25, 0.0, 0.7);
        this.hazeDry = clamp(l.hazeDry, 0.45, 0.0, 1.0);
        this.afterRainMinutes = clamp(l.afterRainMinutes, 4.0, 0.0, 60.0);
        this.sporeHazeFrom = clamp(l.sporeHazeFrom, 60.0, 0.0, 1000.0);
        this.sporeHazeFull = Math.max(this.sporeHazeFrom + 1.0F, clamp(l.sporeHazeFull, 200.0, 0.0, 1000.0));
        this.dappleLowScale = clamp(l.dappleLowScale, 0.75, 0.1, 5.0);
        this.dappleHighScale = clamp(l.dappleHighScale, 1.6, 0.1, 5.0);
        this.dappleHighRise = clamp(l.dappleHighRise, 60.0, 4.0, 400.0);
        this.dappleLowSharpness = clamp(l.dappleLowSharpness, 0.7, 0.0, 0.95);
        this.duskTint = ratio(l.duskTint, 0.98, 0.90, 1.10);
        this.nightTint = ratio(l.nightTint, 0.86, 0.96, 1.12);
        this.nightTintStrength = clamp(l.nightTintStrength, 0.6, 0.0, 1.0);
        this.swampPresence = clamp(l.swampPresence, 0.45, 0.0, 1.0);
        this.skyLift = clamp(l.skyLift, 0.6, 0.0, 1.0);
        this.skyLiftDark = clamp(l.skyLiftDark, 0.45, 0.0, 1.0);
        this.shade = clamp(l.shade, 0.75, 0.0, 1.0);
        this.shadeBacklit = clamp(l.shadeBacklit, 0.58, 0.0, 1.0);
        this.shadeTint = clamp(l.shadeTint, 1.0, 0.0, 2.0);
        this.forestTint = clamp(l.forestTint, 1.0, 0.0, 1.0);
        this.surfaceShade = clamp(l.surfaceShade, 1.0, 0.0, 1.0);
        this.phaseMin = clamp(l.phaseMin, 0.06, 0.0, 1.0);
        this.phaseMax = clamp(l.phaseMax, 3.0, 0.0, 10.0);
        this.phasePower = clamp(l.phasePower, 2.2, 0.1, 10.0);
        this.intensity = clamp(l.intensity, 1.0, 0.0, 3.0);
        this.noonSoftness = clamp(l.noonSoftness, 0.4, 0.0, 1.0);
        this.moonStrength = clamp(l.moonStrength, 0.5, 0.0, 1.0);
        this.maxShafts = l.maxShafts != null ? Math.max(0, Math.min(14, l.maxShafts)) : 14;
        this.maxMotes = l.maxMotes != null ? Math.max(0, Math.min(14 * 64, l.maxMotes)) : 420;
        this.maxDapples = l.maxDapples != null ? Math.max(0, Math.min(2000, l.maxDapples)) : 420;
        this.wind = WindParams.of(j != null ? j.wind : null);
        this.sway = SwayParams.of(j != null ? j.sway : null);
        this.leaves = LeavesParams.of(j != null ? j.leaves : null);
        this.sound = SoundParams.of(j != null ? j.sound : null);
        this.spores = SporesParams.of(j != null ? j.spores : null);
    }

    /** 色の比を、明るさ 0.3R + 0.59G + 0.11B が 1 になるようそろえる。 */
    private static float[] ratio(double[] v, double r, double g, double b) {
        double[] c = v != null && v.length == 3 ? v : new double[]{r, g, b};
        double lum = Math.max(1.0E-3, 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]);
        return new float[]{(float) (c[0] / lum), (float) (c[1] / lum), (float) (c[2] / lum)};
    }

    private static float clamp(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }

    public static AtmosphereTable fromJson(Json j) {
        return j == null ? DEFAULT : new AtmosphereTable(j);
    }

    public float coverStart() {
        return coverStart;
    }

    public float coverFull() {
        return coverFull;
    }

    public float presenceSmoothSeconds() {
        return presenceSmoothSeconds;
    }

    public float noonShaftStrength() {
        return noonShaftStrength;
    }

    public float noonShaftShorten() {
        return noonShaftShorten;
    }

    public float hazeDry() {
        return hazeDry;
    }

    public float afterRainMinutes() {
        return afterRainMinutes;
    }

    public float sporeHazeFrom() {
        return sporeHazeFrom;
    }

    public float sporeHazeFull() {
        return sporeHazeFull;
    }

    public float dappleLowScale() {
        return dappleLowScale;
    }

    public float dappleHighScale() {
        return dappleHighScale;
    }

    public float dappleHighRise() {
        return dappleHighRise;
    }

    public float dappleLowSharpness() {
        return dappleLowSharpness;
    }

    /** 夕方の影の色の比(明るさ 1)。 */
    public float[] duskTint() {
        return duskTint;
    }

    /** 夜の影の色の比(明るさ 1)。 */
    public float[] nightTint() {
        return nightTint;
    }

    public float nightTintStrength() {
        return nightTintStrength;
    }

    public float swampPresence() {
        return swampPresence;
    }

    public float skyLift() {
        return skyLift;
    }

    public float skyLiftDark() {
        return skyLiftDark;
    }

    public float shade() {
        return shade;
    }

    public float shadeBacklit() {
        return shadeBacklit;
    }

    public float shadeTint() {
        return shadeTint;
    }

    public float forestTint() {
        return forestTint;
    }

    public float surfaceShade() {
        return surfaceShade;
    }

    public float phaseMin() {
        return phaseMin;
    }

    public float phaseMax() {
        return phaseMax;
    }

    public float phasePower() {
        return phasePower;
    }

    public float intensity() {
        return intensity;
    }

    public float noonSoftness() {
        return noonSoftness;
    }

    public float moonStrength() {
        return moonStrength;
    }

    /** 光の筋の上限(14 本まで。光る胞子の束の組の数と同じ)。 */
    public int maxShafts() {
        return maxShafts;
    }

    public int maxMotes() {
        return maxMotes;
    }

    public int maxDapples() {
        return maxDapples;
    }
    /** 風の場(節 {@code wind})。 */
    public WindParams wind() {
        return wind;
    }

    /** 葉と草の揺れ(節 {@code sway})。 */
    public SwayParams sway() {
        return sway;
    }

    /** 落ち葉(節 {@code leaves})。 */
    public LeavesParams leaves() {
        return leaves;
    }

    /** 風の音(節 {@code sound})。 */
    public SoundParams sound() {
        return sound;
    }

    /** 環境の胞子(節 {@code spores})。 */
    public SporesParams spores() {
        return spores;
    }
}
