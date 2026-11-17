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
        public FogJson fog;
        public MarchJson fogMarch;
        public SilhouetteJson silhouette;
        public DropsJson drops;
        public DustJson dust;
        public DuskJson dusk;
        public WaterJson water;
        public InsectsJson insects;
        public AtmosphereLife.WetJson wet;
        public AtmosphereLife.WaterGlintJson waterGlint;
        public AtmosphereLife.RainLookJson rainLook;
        /** 胞子の空気(11-06、{@link AtmosphereAir})。 */
        public AtmosphereAir.SporeAirJson sporeAir;
        /** 洞窟の出入りの目の慣れ(11-07、{@link AtmosphereEye})。 */
        public AtmosphereEye.EyeAdaptJson eyeAdapt;
        /** 光るもののにじみ(11-13 仕上げ B、{@link AtmosphereEye})。 */
        public AtmosphereEye.BloomJson bloom;
        /** 鉱石のきらめき(11-13 仕上げ E、{@link AtmosphereEye})。 */
        public AtmosphereEye.OreSparkleJson oreSparkle;
        /** 濡れた岩の照り(11-14 仕上げ F、{@link AtmosphereEye})。 */
        public AtmosphereEye.CaveWetJson caveWet;
        /** 岩肌の地脈の筋(11-14 仕上げ F、{@link AtmosphereEye})。 */
        public AtmosphereEye.LeyVeinsJson leyVeins;
        /** テクスチャの明暗から面の凹凸(11-14 仕上げ G、{@link AtmosphereEye})。 */
        public AtmosphereEye.SurfaceBumpJson surfaceBump;
        /** 隙間と根元の陰り(簡易 AO。11-14 仕上げ H、{@link AtmosphereEye})。 */
        public AtmosphereEye.SimpleAoJson simpleAo;
        /** フェーズが上がったときの合図(11-16、{@link AtmosphereEye})。 */
        public AtmosphereEye.PhaseCueJson phaseCue;
        /** 濃霧(天気。11-16、{@link AtmosphereEye})。 */
        public AtmosphereEye.DenseFogJson denseFog;
        /** 怖い現象 A・D・E(11-16、{@link AtmosphereScary})。 */
        public AtmosphereScary.Json scary;
        /** 天気の見た目の残り(11-16。雷雨の強化の残り・凪・強風の日。{@link WeatherLookEye})。 */
        public java.util.Map<String, Object> stormExtras, calmLook, galeLook;
        /** 過繁茂の外の洞窟(11-07、{@link AtmosphereEye})。 */
        public AtmosphereEye.CaveOpenJson caveOpen;
        /** 洞窟の天井の穴の光の筋(11-07、{@link AtmosphereEye})。 */
        public AtmosphereEye.CaveShaftsJson caveShafts;
        /** 洞窟の呼吸(11-08 境目の帯 1、{@link AtmosphereEye})。 */
        public AtmosphereEye.CaveBreathJson caveBreath;
        /** 手に持った明かり(11-07、{@link AtmosphereEye})。 */
        public AtmosphereEye.HeldLightsJson heldLights;
        public AtmosphereLife.SteamJson steam;
        public AtmosphereLife.RipplesJson ripples;
        public AtmosphereLife.GnatsJson gnats;
        public AtmosphereLife.DropRippleJson dropRipple;
        public AtmosphereLife.RainRippleJson rainRipple;
        public AtmosphereLife.AlgaeJson algae;
        public AtmosphereLife.CreakJson creak;
        /** 巨大樹のまわりの生き物と音(11-06 改善案 ⑤、{@link AtmosphereGiantLife})。 */
        public AtmosphereGiantLife.Json giantLife;
        /** 洞窟の小ネタ(11-07、{@link AtmosphereCaveLife})。 */
        public AtmosphereCaveLife.Json caveLife;
        /** 見た目だけの小さな生き物(11-17、{@link AtmosphereCritters})。 */
        public AtmosphereCritters.Json smallCritters;
        /** 巨大樹の胞子の柱・夜の地脈の光(11-06 改善案 ①③、{@link AtmosphereGiantTree})。 */
        public AtmosphereGiantTree.Json giantTree;
        /** G4 胞子の風・G5 光る胞子の夜(11-08、{@link AtmosphereEvents})。 */
        public AtmosphereEvents.GaleJson gale;
        public AtmosphereEvents.GlowNightJson glowNight;
        public AtmosphereWater.CausticsJson caustics;
        public AtmosphereWater.ShaftsJson underwaterShafts;
        public AtmosphereCave.CaveJson cave;
    }

    /**
     * 節 {@code silhouette}(合間の小物、巨大樹のシルエット。10-15 夜 了承・10-16 地下の根は対象外)。本物の木がバニラの霧で cutAt
     * (霧の始めから終わりまでの割合)だけ進んだ所から先をシルエットへ入れ替え、cutBand ブロックで重ねて溶かす。入れ替えた先の霞みは
     * バニラの霧を hazeStretch 倍の長さへ伸ばし(遠くの影が霞んで残る)、高さの霧({@code ov_fog.glsl})は入れ替えた所から先の濃さを mistStretch 分の 1 に。
     */
    public static final class SilhouetteJson {
        public Double cutAt, cutBand, hazeStretch, mistStretch;
        /** 11-06 夜(ユーザー「巨大樹の上の方が見えない」、仮決め): 森の霞の中で、霞の k を何倍に引き延ばして影絵を霞ませるか。 */
        public Double forestHazeStretch;
    }

    /** 節 {@code drops}(雨上がりの水滴): 生きている数の上限(高・中・低)・1 秒に試す数・探す範囲(水平、ブロック)・雨の最中の強さ(perSecond に掛ける)・雨が止んでから 0 になるまでの分(10-31 雨中の量から減らす)。 */
    public static final class DropsJson {
        public int[] counts;
        public Double perSecond, range, duringRain, afterMinutes;
    }

    /**
     * 節 {@code dust}(胞子の粉の見た目): 見せ始める降り積もり %・濃さの曲がり(% ÷ 100 の gamma 乗)・
     * 葉の上と水面の上の薄め・薄いとき / 濃いときの色。
     */
    public static final class DustJson {
        public Double minPercent, gamma, leafFactor, waterFactor, maxAlpha;
        public double[] thinColor, thickColor;
    }

    /**
     * 節 {@code dusk}(夕方の光の粒、案 D1): 粒の数(高・中・低)・大きさ(ブロック)・出る時刻(0〜24 時。fromHour で出始め fullHour で揃い、
     * fadeHour から toHour で消える)・林床からの高さ・箱の半幅・明滅の周期(秒)・明るさ・色 2 つ(粒ごとに混ぜる)。
     */
    public static final class DuskJson {
        public int[] counts;
        public Double size, fromHour, fullHour, fadeHour, toHour, heightMin, heightMax, boxHalf, blinkSeconds, brightness;
        public double[] warmColor, coolColor;
    }

    /** 節 {@code water}(水音): 鳴らすか(既定 false。10-31 から保留)・大きさ・水を探す半径(ブロック)・鳴り始める水のブロック数・いちばん大きくなる数・流れる水の重み。 */
    public static final class WaterJson {
        public Boolean enabled;
        public Double volume, radius, minBlocks, fullBlocks, flowingWeight;
    }

    /**
     * 節 {@code insects}(虫・爬虫類の声、鳥の代わり): 森の度合いの下限・大物の Mob が近いと黙る半径(今はフックだけ)・
     * コオロギ系(鳴く時刻・匹数・距離・黙る近さ・鳴き直すまでの秒・大きさ)・ヤモリ系(鳴く時刻・間隔の秒・チッの回数・距離・大きさ)。
     */
    public static final class InsectsJson {
        public Double minForest, hushRadius;
        public Double cricketFromHour, cricketToHour, cricketVoicesMin, cricketVoicesMax, cricketDistanceMin, cricketDistanceMax,
                cricketQuietDistance, cricketResumeSeconds, cricketVolume;
        public Double geckoFromHour, geckoToHour, geckoIntervalMin, geckoIntervalMax, geckoClicksMin, geckoClicksMax,
                geckoDistanceMin, geckoDistanceMax, geckoVolume;
        // 10-31 夜 ユーザー採用の 4 種(昼の合唱・キリギリス様・下草のガサガサ・爬虫類)と、全種で同時に鳴る声の上限
        public Double maxVoices;
        public Double chorusFromHour, chorusToHour, chorusIntervalMin, chorusIntervalMax, chorusDistanceMin, chorusDistanceMax, chorusVolume;
        public Double katydidFromHour, katydidToHour, katydidIntervalMin, katydidIntervalMax, katydidDistanceMin, katydidDistanceMax,
                katydidPhrasesMin, katydidPhrasesMax, katydidVoicesMax, katydidVolume;
        public Double rustleFromHour, rustleToHour, rustleIntervalMin, rustleIntervalMax, rustleDistanceMin, rustleDistanceMax, rustleVolume;
        public Double reptileFromHour, reptileToHour, reptileIntervalMin, reptileIntervalMax, reptileDistanceMin, reptileDistanceMax, reptileVolume;
        // 11-01 ユーザー採用の動く音 2 種(頭上の枝葉の揺れ・小枝の折れ)
        public Double canopyFromHour, canopyToHour, canopyIntervalMin, canopyIntervalMax, canopyDistanceMin, canopyDistanceMax, canopyVolume;
        public Double twigFromHour, twigToHour, twigIntervalMin, twigIntervalMax, twigDistanceMin, twigDistanceMax, twigVolume;
        // 11-01 ユーザー「休憩なしに常に鳴り続けているのはおかしい」: 1 匹ごとの鳴く区間・黙る区間と、森全体の凪
        public Double cricketSingMin, cricketSingMax, cricketRestMin, cricketRestMax, katydidSingMin, katydidSingMax;
        public Double lullIntervalMin, lullIntervalMax, lullMin, lullMax;
    }

    /** 巨大樹のシルエットの値。 */
    public record SilhouetteParams(float cutAt, float cutBand, float hazeStretch, float mistStretch, float forestHazeStretch) {
        static SilhouetteParams of(SilhouetteJson s) {
            SilhouetteJson v = s != null ? s : new SilhouetteJson();
            return new SilhouetteParams(clamp(v.cutAt, 0.55, 0.05, 0.95), clamp(v.cutBand, 8.0, 0.5, 64.0),
                    clamp(v.hazeStretch, 3.2, 1.0, 20.0), clamp(v.mistStretch, 3.0, 1.0, 20.0), clamp(v.forestHazeStretch, 16.0, 1.0, 50.0));
        }
    }

    /** 雨上がりの水滴の値。 */
    public record DropsParams(int[] counts, float perSecond, float range, float duringRain, float afterMinutes) {
        static DropsParams of(DropsJson d) {
            DropsJson v = d != null ? d : new DropsJson();
            return new DropsParams(tierCounts(v.counts, new int[]{200, 120, 50}, 1000), clamp(v.perSecond, 100.0, 0.0, 1000.0),
                    clamp(v.range, 14.0, 4.0, 32.0), clamp(v.duringRain, 0.25, 0.0, 1.0),
                    clamp(v.afterMinutes, 3.0, 0.0, 60.0));
        }
    }

    /** 胞子の粉の見た目の値。 */
    public record DustParams(float minPercent, float gamma, float leafFactor, float waterFactor, float maxAlpha, float[] thinColor,
                             float[] thickColor) {
        static DustParams of(DustJson d) {
            DustJson v = d != null ? d : new DustJson();
            return new DustParams(clamp(v.minPercent, 1.0, 0.0, 100.0), clamp(v.gamma, 1.2, 0.1, 5.0),
                    clamp(v.leafFactor, 0.6, 0.0, 1.0), clamp(v.waterFactor, 0.4, 0.0, 1.0), clamp(v.maxAlpha, 0.42, 0.0, 1.0), rgb(v.thinColor, 0.88, 0.92, 0.78),
                    rgb(v.thickColor, 0.76, 0.85, 0.50));
        }
    }

    /** 夕方の光の粒の値。 */
    public record DuskParams(int[] counts, float size, float fromHour, float fullHour, float fadeHour, float toHour, float heightMin,
                             float heightMax, float boxHalf, float blinkSeconds, float brightness, float[] warmColor, float[] coolColor) {
        static DuskParams of(DuskJson d) {
            DuskJson v = d != null ? d : new DuskJson();
            float from = clamp(v.fromHour, 17.0, 0.0, 24.0);
            float full = Math.max(from + 0.05F, clamp(v.fullHour, 18.0, 0.0, 24.0));
            float fade = Math.max(full, clamp(v.fadeHour, 20.0, 0.0, 24.0));
            float to = Math.max(fade + 0.05F, clamp(v.toHour, 21.0, 0.0, 26.0));
            float hMin = clamp(v.heightMin, 1.0, 0.0, 8.0);
            return new DuskParams(tierCounts(v.counts, new int[]{60, 35, 15}, 512), clamp(v.size, 0.06, 0.01, 0.3), from, full, fade, to,
                    hMin, Math.max(hMin, clamp(v.heightMax, 3.0, 0.0, 12.0)), clamp(v.boxHalf, 16.0, 4.0, 32.0),
                    clamp(v.blinkSeconds, 4.0, 0.5, 30.0), clamp(v.brightness, 1.0, 0.0, 3.0), rgb(v.warmColor, 0.80, 0.95, 0.42),
                    rgb(v.coolColor, 0.55, 0.95, 0.60));
        }
    }

    /** 水音の値。 */
    public record WaterParams(boolean enabled, float volume, float radius, float minBlocks, float fullBlocks, float flowingWeight) {
        static WaterParams of(WaterJson w) {
            WaterJson v = w != null ? w : new WaterJson();
            float min = clamp(v.minBlocks, 4.0, 0.0, 1000.0);
            return new WaterParams(v.enabled != null && v.enabled, clamp(v.volume, 0.35, 0.0, 2.0), clamp(v.radius, 12.0, 4.0, 32.0), min,
                    Math.max(min + 1.0F, clamp(v.fullBlocks, 60.0, 1.0, 4000.0)), clamp(v.flowingWeight, 3.0, 0.0, 20.0));
        }
    }

    /** 虫・爬虫類の声の値(時刻は 0〜24 時。from > to なら日をまたぐ)。 */
    public record InsectsParams(float minForest, float hushRadius, float cricketFromHour, float cricketToHour, int cricketVoicesMin,
                                int cricketVoicesMax, float cricketDistanceMin, float cricketDistanceMax, float cricketQuietDistance,
                                float cricketResumeSeconds, float cricketVolume, float geckoFromHour, float geckoToHour, float geckoIntervalMin,
                                float geckoIntervalMax, int geckoClicksMin, int geckoClicksMax, float geckoDistanceMin, float geckoDistanceMax,
                                float geckoVolume, int maxVoices, CallParams chorus, CallParams katydid, int katydidPhrasesMin, int katydidPhrasesMax,
                                int katydidVoicesMax, CallParams rustle, CallParams reptile, CallParams canopy, CallParams twig, float cricketSingMin, float cricketSingMax,
                                float cricketRestMin, float cricketRestMax, float katydidSingMin, float katydidSingMax, float lullIntervalMin,
                                float lullIntervalMax, float lullMin, float lullMax) {
        /** ヤモリの値を 1 回きりの声の形で。 */
        public CallParams gecko() {
            return new CallParams(this.geckoFromHour, this.geckoToHour, this.geckoIntervalMin, this.geckoIntervalMax, this.geckoDistanceMin,
                    this.geckoDistanceMax, this.geckoVolume);
        }

        static InsectsParams of(InsectsJson i) {
            InsectsJson v = i != null ? i : new InsectsJson();
            int vMin = Math.round(clamp(v.cricketVoicesMin, 1.0, 0.0, 6.0));
            int cMin = Math.round(clamp(v.geckoClicksMin, 3.0, 1.0, 12.0));
            float dMin = clamp(v.cricketDistanceMin, 8.0, 2.0, 48.0);
            float gMin = clamp(v.geckoDistanceMin, 6.0, 2.0, 48.0);
            float iMin = clamp(v.geckoIntervalMin, 120.0, 5.0, 3600.0);
            int kMin = Math.round(clamp(v.katydidPhrasesMin, 3.0, 1.0, 40.0));
            float sMin = clamp(v.cricketSingMin, 20.0, 1.0, 3600.0);
            float rMin = clamp(v.cricketRestMin, 15.0, 0.0, 3600.0);
            float kSMin = clamp(v.katydidSingMin, 10.0, 1.0, 3600.0);
            float lIMin = clamp(v.lullIntervalMin, 180.0, 10.0, 7200.0);
            float lMin = clamp(v.lullMin, 30.0, 0.0, 3600.0);
            return new InsectsParams(clamp(v.minForest, 0.3, 0.0, 1.0), clamp(v.hushRadius, 24.0, 0.0, 128.0),
                    clamp(v.cricketFromHour, 17.0, 0.0, 24.0), clamp(v.cricketToHour, 6.0, 0.0, 24.0), vMin,
                    Math.max(vMin, Math.round(clamp(v.cricketVoicesMax, 3.0, 0.0, 6.0))), dMin,
                    Math.max(dMin, clamp(v.cricketDistanceMax, 20.0, 2.0, 64.0)), clamp(v.cricketQuietDistance, 4.0, 0.0, 16.0),
                    clamp(v.cricketResumeSeconds, 30.0, 0.0, 600.0), clamp(v.cricketVolume, 0.25, 0.0, 2.0),
                    clamp(v.geckoFromHour, 20.0, 0.0, 24.0), clamp(v.geckoToHour, 4.0, 0.0, 24.0), iMin,
                    Math.max(iMin, clamp(v.geckoIntervalMax, 300.0, 5.0, 3600.0)), cMin,
                    Math.max(cMin, Math.round(clamp(v.geckoClicksMax, 6.0, 1.0, 12.0))), gMin,
                    Math.max(gMin, clamp(v.geckoDistanceMax, 12.0, 2.0, 64.0)), clamp(v.geckoVolume, 0.3, 0.0, 2.0),
                    Math.round(clamp(v.maxVoices, 4.0, 0.0, 12.0)),
                    CallParams.of(v.chorusFromHour, v.chorusToHour, v.chorusIntervalMin, v.chorusIntervalMax, v.chorusDistanceMin, v.chorusDistanceMax,
                            v.chorusVolume, new double[]{10.0, 16.0, 90.0, 240.0, 30.0, 60.0, 0.6}),
                    CallParams.of(v.katydidFromHour, v.katydidToHour, v.katydidIntervalMin, v.katydidIntervalMax, v.katydidDistanceMin,
                            v.katydidDistanceMax, v.katydidVolume, new double[]{19.0, 4.0, 20.0, 60.0, 10.0, 24.0, 0.5}),
                    kMin, Math.max(kMin, Math.round(clamp(v.katydidPhrasesMax, 8.0, 1.0, 40.0))), Math.round(clamp(v.katydidVoicesMax, 2.0, 0.0, 6.0)),
                    CallParams.of(v.rustleFromHour, v.rustleToHour, v.rustleIntervalMin, v.rustleIntervalMax, v.rustleDistanceMin, v.rustleDistanceMax,
                            v.rustleVolume, new double[]{0.0, 24.0, 120.0, 360.0, 4.0, 8.0, 0.4}),
                    CallParams.of(v.reptileFromHour, v.reptileToHour, v.reptileIntervalMin, v.reptileIntervalMax, v.reptileDistanceMin,
                            v.reptileDistanceMax, v.reptileVolume, new double[]{17.0, 5.0, 240.0, 600.0, 6.0, 14.0, 0.5}),
                    CallParams.of(v.canopyFromHour, v.canopyToHour, v.canopyIntervalMin, v.canopyIntervalMax, v.canopyDistanceMin, v.canopyDistanceMax,
                            v.canopyVolume, new double[]{0.0, 24.0, 240.0, 600.0, 10.0, 20.0, 0.5}),
                    CallParams.of(v.twigFromHour, v.twigToHour, v.twigIntervalMin, v.twigIntervalMax, v.twigDistanceMin, v.twigDistanceMax,
                            v.twigVolume, new double[]{0.0, 24.0, 300.0, 720.0, 8.0, 24.0, 0.6}),
                    sMin, Math.max(sMin, clamp(v.cricketSingMax, 60.0, 1.0, 3600.0)), rMin, Math.max(rMin, clamp(v.cricketRestMax, 40.0, 0.0, 3600.0)),
                    kSMin, Math.max(kSMin, clamp(v.katydidSingMax, 30.0, 1.0, 3600.0)),
                    lIMin, Math.max(lIMin, clamp(v.lullIntervalMax, 360.0, 10.0, 7200.0)), lMin, Math.max(lMin, clamp(v.lullMax, 90.0, 0.0, 3600.0)));
        }
    }

    /** 1 回きりの声 1 種の値(時刻は 0〜24 時、from > to なら日をまたぐ・from 0 to 24 で一日中。間隔は秒、距離はブロック)。 */
    public record CallParams(float fromHour, float toHour, float intervalMin, float intervalMax, float distanceMin, float distanceMax, float volume) {
        /** def = 既定値 {from, to, intervalMin, intervalMax, distanceMin, distanceMax, volume}。 */
        static CallParams of(Double from, Double to, Double iMin, Double iMax, Double dMin, Double dMax, Double volume, double[] def) {
            float im = clamp(iMin, def[2], 5.0, 3600.0);
            float dm = clamp(dMin, def[4], 2.0, 96.0);
            return new CallParams(clamp(from, def[0], 0.0, 24.0), clamp(to, def[1], 0.0, 24.0), im, Math.max(im, clamp(iMax, def[3], 5.0, 3600.0)),
                    dm, Math.max(dm, clamp(dMax, def[5], 2.0, 128.0)), clamp(volume, def[6], 0.0, 1.0));
        }
    }

    private static int[] tierCounts(int[] v, int[] def, int max) {
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            c[i] = Math.max(0, Math.min(max, v != null && v.length > i ? v[i] : def[i]));
        }
        return c;
    }

    private static float[] rgb(double[] v, double r, double g, double b) {
        double[] c = v != null && v.length == 3 ? v : new double[]{r, g, b};
        return new float[]{(float) Math.max(0.0, Math.min(1.0, c[0])), (float) Math.max(0.0, Math.min(1.0, c[1])),
                (float) Math.max(0.0, Math.min(1.0, c[2]))};
    }

    /**
     * 節 {@code fogMarch}(第 3 段「霧」の細部 = 品質 HIGH だけの半解像度のレイマーチ、10-30 夜)。近く distance ブロックまでを steps 歩で積み、
     * fadeFrom〜distance で土台(解析)と受け渡す。3D の濃淡 noise(noiseScale ブロックの升、風で流れる)・霧の中の光の筋(林冠の日向の地図で日の当たる所だけ、
     * 前方散乱 Henyey-Greenstein の g = scatterG、強さ sunScatter)・描く大きさ resolution(画面に対する割合)。
     */
    public static final class MarchJson {
        public Integer steps;
        public Double distance, fadeFrom, noise, noiseScale, sunScatter, scatterG, resolution, shaftYield;
    }

    /** 霧の細部の値。 */
    public record MarchParams(int steps, float distance, float fadeFrom, float noise, float noiseScale, float sunScatter, float scatterG,
                              float resolution, float shaftYield) {
        static MarchParams of(MarchJson m) {
            MarchJson v = m != null ? m : new MarchJson();
            float distance = clamp(v.distance, 48.0, 8.0, 128.0);
            return new MarchParams(v.steps != null ? Math.max(4, Math.min(32, v.steps)) : 10, distance,
                    Math.min(distance - 1.0F, clamp(v.fadeFrom, 40.0, 0.0, 128.0)), clamp(v.noise, 0.7, 0.0, 1.0),
                    clamp(v.noiseScale, 10.0, 1.0, 100.0), clamp(v.sunScatter, 1.2, 0.0, 5.0), clamp(v.scatterG, 0.6, 0.0, 0.95),
                    clamp(v.resolution, 0.5, 0.25, 1.0), clamp(v.shaftYield, 0.5, 0.0, 1.0));
        }
    }

    /**
     * 節 {@code fog}(第 3 段「霧」の土台 = 高さの霧の解析積分、10-30 夜)。層の中の濃さ(1 ブロックあたり)と天井の柔らかさ(k)・
     * 層の厚さ(薄い朝 depthMin 〜 濃い朝 + depthRange)・層の底の取り方(梢から測った高さ地図の低い方から lowPercentile)・
     * 林冠の雲海(梢 = canopyPercentile と高い方 highPercentile の小さい方 + seaAbove)・上から見る倍率(aboveRamp ブロック上で aboveBoost 倍)・
     * 中にいるときのバニラの霧(insideFogEnd = 10-16 了承の 64、始まりは × insideNearFraction、てっぺんから insideSoft 下で全部)・
     * 昼の霞(朝の濃さの noonDensity 倍、厚さ noonDepth)・雨上がり(濃さ × afterRainDensity、厚さ + afterRainDepth)・薄い朝の濃さ weakMorning・
     * 二色(近い暖かい色 nearColor → 遠い青緑 farColor を colorDistance で混ぜる。夜は nightColor)・太陽の方の明るさ sunLobe・
     * くぼみのまだら(noise の強さ、noiseScale ブロックの升)・過繁茂の地域からはみ出す格子の数(谷へ流れ落ちる所 proxCells、平地 proxCellsFlat)。
     * 雨上がりの霧が残る時間 mistAfterRainMinutes と、そのとき昼でも残る層の強さ afterRainLinger・雷雨(濃さ × stormDensity、厚さ + stormDepth、
     * 色 × stormDarken)・胞子濃度で地面近くの霞に足す分 sporeHaze(光の節の sporeHazeFrom〜Full で 0 → 1)・
     * 森の中の霧の色の暗さ forestFogDark(遠い色とバニラの霧の色に、森の度合いで掛ける)・林冠の陰の霧の暗さの下限 shadeFloor。
     * 色味(10-30 夜 ユーザー採用 A1・A5、参考画像「霧は白〜灰緑で青くない」): 朝霧の近い色 nearColor は白・遠い色 farColor は明るい灰緑、昼の霞は hazeColor(薄い黄緑がかった灰)。
     * 森の中では林冠の隙間の空とバニラの霧の色を forestSkyColor(明るい白)へ forestSky の強さで飛ばす(朝霧の中にいる間は除く)。
     * 胞子の霧(10-31 ユーザー「森の中にいるときだけ描画距離 6 チャンクくらい」): 森の列を通った視線の長さが forestFogStart〜forestFogEnd で見えなくなる
     * (0 = 切る。バニラの 6 チャンクの霧と同じ 86.4〜96)。森の霧は林冠の床から forestFogAbove ブロック上まで。壁の手前の霞 forestFogDensity(森 1 ブロックあたり、0 = 壁だけ。カメラが森の中にいる間はバニラの霧の式を上書きして Mob・粒にも同じに掛ける)。
     * 胞子の霧の色: 昼 forestFogDayColor(11-01 ユーザー「白〜黄緑の胞子」: #ace300 を白へ 75% 寄せた色 → 11-01 夜「若干明るい緑」で白へ 45% 程度の [0.74, 0.90, 0.52])・朝夕 forestFogDawnColor(朝霧の白緑)・夜は nightColor。値は仮決め。
     * 水中の濁り(11-01 ユーザー「水中が透き通りすぎ」): 海・川の 4 バイオームで潜っている間の霧の終わり waterFogOvergrownOcean ほか(ブロック、0 = バニラのまま)・
     * 始まり = 終わり × waterFogNearFraction。潜る時間で澄んでいかない。距離は仮決め。
     * 11-01 夕方(ユーザー「川は真っ暗すぎ。川・池は明るい黄緑、海は青緑のもや」= 参考画像 水中-2026-11-01): 森の池(waterFogOvergrown・waterFogOvergrowth)も加え、
     * 霧の色を 6 つのキー water*Color で持つ(海・川はバイオームの water_fog_color も同じ色。霧の品質を切ったときのバニラ用)。色・距離は仮決め。
     * 11-01 夜(ユーザー「森の池と川の水を統一」): 池の距離・色の既定を川と同じに。境目の移り(ユーザー「変わり目をもっとゆっくり」): 目標へ指数で寄せる時定数
     * transitionForestSeconds(森の胞子の霧)・transitionCaveSeconds(地上 ↔ 地下)・transitionWaterSeconds(水中の霧の距離と色。水に出入りした瞬間は寄せない)。秒は仮決め。
     */
    public static final class FogJson {
        public Double density, falloff, depthMin, depthRange, lowPercentile, canopyPercentile, highPercentile, seaAbove;
        public Double aboveBoost, aboveRamp, insideFogEnd, insideNearFraction, insideSoft, noonDensity, noonDepth;
        public Double afterRainDensity, afterRainDepth, weakMorning, colorDistance, sunLobe, noise, noiseScale, proxCells;
        public Double proxCellsFlat, mistAfterRainMinutes, afterRainLinger, stormDensity, stormDepth, stormDarken, sporeHaze;
        public Double forestFogDark, shadeFloor, forestSky, stormFogEnd, sunGlow, farDesat, desatFrom, desatTo;
        public Double forestFogEnd, forestFogStart, forestFogAbove, forestFogDensity;
        public Double waterFogOvergrownOcean, waterFogOvergrowthOcean, waterFogOvergrownRiver, waterFogOvergrowthRiver, waterFogNearFraction;
        public Double waterFogOvergrown, waterFogOvergrowth;
        public Double transitionForestSeconds, transitionCaveSeconds, transitionWaterSeconds;
        /** 11-01 夜 見え方の直し 5(仮決め): 水中の霧の色を水面からの深さで暗くする。深さ waterDepthDarkenBlocks で waterDepthDarkenMin 倍(0 で切る)。光の筋も同じ倍率で弱める。 */
        public Double waterDepthDarkenBlocks, waterDepthDarkenMin;
        /**
         * 11-01 夜 見え方の直し 6(ユーザー「森の中で昼でも白い靄、朝方のよう。胞子の霧と分かるよう若干明るい緑に」。値は仮決め): 森の中で、
         * 隙間の空の色(バニラの霧の色)と昼の霞の色(地形の霧の層の近い色・遠い色)を昼の胞子の霧の色へ寄せる割合。朝(MistLayer の 4〜9 時)は寄せない。
         */
        public Double forestSkyGreen, forestHazeGreen;
        /** 11-05 光の点検 2(ユーザー確定): カメラが森の中にいる間、霞の色を空の明るさで shadeFloor〜1 に落とす(false で切る)。 */
        public Boolean forestDim;
        /** 11-05 光の点検 3(仮決め): 霞の太陽側の色 hazeSunColor(比。明るさはそろえる)・その混ざりの最大 hazeSunMix・遠さの目安 hazeSunReach(ブロック。近くは弱く)・
         *  霞の全体に空の光の色(light.skyTint*)を映す割合 hazeTintMix。 */
        public double[] hazeSunColor;
        public Double hazeSunMix, hazeSunReach, hazeTintMix;
        /** 11-05 光の点検 4(仮決め): 夜の霧の色を月で変える。夕方〜夜の入りは今の nightColor のまま、夜の度合いの分だけ、満月で月が高いと nightColorFull(灰に近い青灰)、新月・月の無い間は nightColorNew(ほぼ黒)。
         *  月の方の霞は hazeMoonColor(比。明るさはそろえる)へ hazeMoonMix だけ寄せ、明るさを hazeMoonBright 倍まで上げる(霞の太陽側と同じ仕組み)。 */
        public double[] nightColorFull, nightColorNew, hazeMoonColor;
        public Double hazeMoonMix, hazeMoonBright;
        /**
         * 空気遠近(11-06 バニラ地域の光 3、ユーザー決定。値は仮決め): 世界全体で遠くの地形を霧の色(その時刻の地平線の色)にわずかに溶かす。霧の終わりで aerialStrength、
         * 霧の終わりの aerialStart 倍の所から 2 乗で濃くなる。夜は aerialNight 倍・雨と雷は aerialRain 倍。地下・水中・ネザー・エンドでは掛けない。
         * 過繁茂の中は胞子の霞に譲る(森の霧の度合いの分だけ弱める)。0 で切る。
         */
        public Double aerialStrength, aerialStart, aerialNight, aerialRain;
        public double[] nearColor, farColor, nightColor, hazeColor, forestSkyColor, forestFogDayColor, forestFogDawnColor;
        public double[] waterOvergrownOceanColor, waterOvergrowthOceanColor, waterOvergrownRiverColor, waterOvergrowthRiverColor,
                waterOvergrownColor, waterOvergrowthColor;
    }

    /** 霧の土台の値。 */
    public record FogParams(float density, float falloff, float depthMin, float depthRange, float lowPercentile, float canopyPercentile,
                            float highPercentile, float seaAbove, float aboveBoost, float aboveRamp, float insideFogEnd,
                            float insideNearFraction, float insideSoft, float noonDensity, float noonDepth, float afterRainDensity,
                            float afterRainDepth, float weakMorning, float colorDistance, float sunLobe, float noise, float noiseScale,
                            float proxCells, float proxCellsFlat, float mistAfterRainMinutes, float afterRainLinger, float stormDensity,
                            float stormDepth, float stormDarken, float sporeHaze, float forestFogDark, float shadeFloor, float forestSky,
                            float stormFogEnd, float sunGlow, float farDesat, float desatFrom, float desatTo, float forestFogEnd, float forestFogStart,
                            float forestFogAbove, float forestFogDensity,
                            float waterFogOvergrownOcean, float waterFogOvergrowthOcean, float waterFogOvergrownRiver, float waterFogOvergrowthRiver,
                            float waterFogNearFraction,
                            float[] nearColor, float[] farColor, float[] nightColor, float[] nightColorFull, float[] nightColorNew, float[] hazeColor, float[] forestSkyColor,
                            float[] forestFogDayColor, float[] forestFogDawnColor,
                            float waterFogOvergrown, float waterFogOvergrowth,
                            float[] waterOvergrownOceanColor, float[] waterOvergrowthOceanColor, float[] waterOvergrownRiverColor,
                            float[] waterOvergrowthRiverColor, float[] waterOvergrownColor, float[] waterOvergrowthColor,
                            float transitionForestSeconds, float transitionCaveSeconds, float transitionWaterSeconds,
                            float waterDepthDarkenBlocks, float waterDepthDarkenMin,
                            float forestSkyGreen, float forestHazeGreen, boolean forestDim,
                            float[] hazeSunColor, float hazeSunMix, float hazeSunReach, float hazeTintMix,
                            float[] hazeMoonColor, float hazeMoonMix, float hazeMoonBright,
                            float aerialStrength, float aerialStart, float aerialNight, float aerialRain) {
        static FogParams of(FogJson f) {
            FogJson v = f != null ? f : new FogJson();
            return new FogParams(clamp(v.density, 0.0045, 0.0, 0.2), clamp(v.falloff, 0.35, 0.02, 5.0),
                    clamp(v.depthMin, 5.0, 0.5, 40.0), clamp(v.depthRange, 5.0, 0.0, 40.0),
                    clamp(v.lowPercentile, 0.4, 0.0, 1.0), clamp(v.canopyPercentile, 0.75, 0.0, 1.0), clamp(v.highPercentile, 0.9, 0.0, 1.0),
                    clamp(v.seaAbove, 3.0, -10.0, 20.0), clamp(v.aboveBoost, 8.0, 1.0, 40.0), clamp(v.aboveRamp, 24.0, 1.0, 200.0),
                    clamp(v.insideFogEnd, 64.0, 8.0, 1024.0), clamp(v.insideNearFraction, 0.3, 0.0, 1.0), clamp(v.insideSoft, 3.0, 0.1, 20.0),
                    clamp(v.noonDensity, 0.25, 0.0, 1.0), clamp(v.noonDepth, 2.5, 0.0, 40.0),
                    clamp(v.afterRainDensity, 1.4, 0.0, 5.0), clamp(v.afterRainDepth, 3.0, 0.0, 40.0), clamp(v.weakMorning, 0.45, 0.0, 1.0),
                    clamp(v.colorDistance, 60.0, 1.0, 1000.0), clamp(v.sunLobe, 0.6, 0.0, 3.0), clamp(v.noise, 0.6, 0.0, 1.0),
                    clamp(v.noiseScale, 24.0, 2.0, 400.0), clamp(v.proxCells, 5.0, 0.0, 16.0), clamp(v.proxCellsFlat, 1.5, 0.0, 16.0),
                    clamp(v.mistAfterRainMinutes, 20.0, 0.0, 240.0), clamp(v.afterRainLinger, 0.6, 0.0, 1.0),
                    clamp(v.stormDensity, 1.3, 1.0, 10.0), clamp(v.stormDepth, 4.0, 0.0, 40.0), clamp(v.stormDarken, 0.5, 0.0, 1.0),
                    clamp(v.sporeHaze, 0.35, 0.0, 2.0), clamp(v.forestFogDark, 1.0, 0.0, 1.0), clamp(v.shadeFloor, 0.3, 0.0, 1.0), clamp(v.forestSky, 1.0, 0.0, 1.0),
                    clamp(v.stormFogEnd, 40.0, 8.0, 1024.0), clamp(v.sunGlow, 0.8, 0.0, 3.0), clamp(v.farDesat, 0.30, 0.0, 1.0),
                    clamp(v.desatFrom, 48.0, 0.0, 1024.0), clamp(v.desatTo, 192.0, 1.0, 2048.0),
                    clamp(v.forestFogEnd, 96.0, 0.0, 1024.0), clamp(v.forestFogStart, 86.4, 0.0, 1024.0), clamp(v.forestFogAbove, 4.0, -8.0, 40.0), clamp(v.forestFogDensity, 0.03, 0.0, 0.2),
                    clamp(v.waterFogOvergrownOcean, 14.0, 0.0, 256.0), clamp(v.waterFogOvergrowthOcean, 20.0, 0.0, 256.0),
                    clamp(v.waterFogOvergrownRiver, 18.0, 0.0, 256.0), clamp(v.waterFogOvergrowthRiver, 24.0, 0.0, 256.0),
                    clamp(v.waterFogNearFraction, 0.2, 0.0, 1.0),
                    rgb(v.nearColor, 1.00, 1.00, 0.96), rgb(v.farColor, 0.90, 0.93, 0.88), rgb(v.nightColor, 0.10, 0.15, 0.10),
                    rgb(v.nightColorFull, 0.11, 0.12, 0.13), rgb(v.nightColorNew, 0.03, 0.04, 0.03),
                    rgb(v.hazeColor, 0.94, 0.96, 0.86), rgb(v.forestSkyColor, 1.00, 1.00, 0.96),
                    rgb(v.forestFogDayColor, 0.74, 0.90, 0.52), rgb(v.forestFogDawnColor, 0.90, 0.93, 0.88),
                    clamp(v.waterFogOvergrown, 18.0, 0.0, 256.0), clamp(v.waterFogOvergrowth, 24.0, 0.0, 256.0),
                    rgb(v.waterOvergrownOceanColor, 0.184, 0.376, 0.345), rgb(v.waterOvergrowthOceanColor, 0.227, 0.439, 0.408),
                    rgb(v.waterOvergrownRiverColor, 0.306, 0.478, 0.149), rgb(v.waterOvergrowthRiverColor, 0.353, 0.541, 0.188),
                    rgb(v.waterOvergrownColor, 0.306, 0.478, 0.149), rgb(v.waterOvergrowthColor, 0.353, 0.541, 0.188),
                    clamp(v.transitionForestSeconds, 4.0, 0.0, 60.0), clamp(v.transitionCaveSeconds, 4.0, 0.0, 60.0),
                    clamp(v.transitionWaterSeconds, 5.0, 0.0, 60.0),
                    clamp(v.waterDepthDarkenBlocks, 24.0, 0.0, 256.0), clamp(v.waterDepthDarkenMin, 0.4, 0.0, 1.0),
                    clamp(v.forestSkyGreen, 0.6, 0.0, 1.0), clamp(v.forestHazeGreen, 0.6, 0.0, 1.0),
                    v.forestDim == null || v.forestDim,
                    ratio(v.hazeSunColor, 1.00, 0.72, 0.52), clamp(v.hazeSunMix, 0.8, 0.0, 1.0), clamp(v.hazeSunReach, 10.0, 1.0, 1024.0),
                    clamp(v.hazeTintMix, 0.3, 0.0, 1.0),
                    ratio(v.hazeMoonColor, 0.85, 0.92, 1.00), clamp(v.hazeMoonMix, 0.4, 0.0, 1.0), clamp(v.hazeMoonBright, 1.4, 1.0, 3.0),
                    clamp(v.aerialStrength, 0.35, 0.0, 1.0), clamp(v.aerialStart, 0.35, 0.0, 0.95), clamp(v.aerialNight, 0.5, 0.0, 2.0),
                    clamp(v.aerialRain, 1.3, 0.0, 3.0));
        }

        private static float[] rgb(double[] c, double r, double g, double b) {
            double[] x = c != null && c.length == 3 ? c : new double[]{r, g, b};
            return new float[]{(float) Math.max(0.0, Math.min(1.0, x[0])), (float) Math.max(0.0, Math.min(1.0, x[1])),
                    (float) Math.max(0.0, Math.min(1.0, x[2]))};
        }
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
        /** 色味(10-30 夜 ユーザー採用 A2・A4。見本で決める仮決め): 昼の影の色の比 shadeHue(明るさはそろえる。前の青緑 0.80:1.00:1.06 から緑寄りへ)と、
         *  日なたの暖かい白〜金 sunWarm(日の当たる面に掛ける比。木漏れ日の斑点もこの色)・その強さ sunWarmStrength。 */
        public double[] shadeHue, sunWarm;
        public Double sunWarmStrength;
        /** 緑の照り返し(A3、10-30 夜 ユーザー採用): 森の中でライトマップの中ほどの明るさに緑を足す強さ(仮決め)。 */
        public Double greenBounce;
        /** 空の光の色(11-05 光の点検 3、値は仮決め): 太陽の高さで 昼(白)→ 金 skyTintGold → 日の入り skyTintDusk → 日没後 skyTintTwilight → 夜(白 = 今の夜のまま)。
         *  切り替わる太陽の高さ skyTintHeights(5 つ: 白の終わり・金・日の入り・青灰・夜の始まり)。色は比(明るさはそろえる)。
         *  森の中の地形の全面に、林冠の開いた所(頂点の空の光が高い所)で skyTintStrength・傘の下はその skyTintShade 倍だけ掛ける。 */
        public double[] skyTintGold, skyTintDusk, skyTintTwilight, skyTintHeights;
        public Double skyTintStrength, skyTintShade;
        /** 11-13(ユーザー決定「曇りの日は地面の色を弱める」。値は仮決め): 雲が空を覆う日(雲の天井)に、空の光の色(朝焼け・夕焼け)を弱める割合(0 = 弱めない)。 */
        public Double skyTintOvercast;
        /**
         * 夜の暗さ(11-05 光の点検 4、値は仮決め): 夜の林床の持ち上げ(満月 skyLiftNightFull・新月 skyLiftNightNew を月の満ち欠けで寄せる)と、夜の 0〜3 段の寄せ方 skyLiftDarkNight。
         * 月の向きの面の明暗(上向き 1・月の側 moonShadeSide・反対側 moonShadeBack・下向き moonShadeDown。頂点の面の明るさと合わせた比)とその強さ moonShadeStrength。
         * 月の斑点(太陽の地図を月の向きで作る): 月の影の深さ moonMapStrength・月の当たる所の色 moonMapColor(比)・地図を作る月の満ち欠けの下限 moonMapPhaseMin。
         * 夜の暗い所の彩度 nightDesaturate(月の当たらない所でこの倍まで落とす)。
         */
        public Double skyLiftNightFull, skyLiftNightNew, skyLiftDarkNight;
        /** 過繁茂の外(バニラの森)の夜の林床の持ち上げ(11-06 2 層、ユーザー決定「普通の世界の夜は森より少しだけ明るく」。値は仮決め)。過繁茂の分で skyLiftNightFull・New へ寄せる。 */
        public Double skyLiftNightFullOpen, skyLiftNightNewOpen;
        /** 点検 21(11-06。値は仮決め): 過繁茂の外(バニラの森)の昼の林床の持ち上げ skyLiftOpen(過繁茂の分で skyLift へ寄せる)と、
         *  幹・地形の影の濃さ trunkShadow・届く高さの差 terrainShadowReach(11-06 バニラ地域の光 1 で太陽の向きの長さから変えた)・縁のぼけ trunkShadowSoft(届く高さのうち薄れていく割合)。 */
        public Double skyLiftOpen, trunkShadow, terrainShadowReach, trunkShadowSoft;
        /** 11-13(値は仮決め): 森の外から見た巨大樹の空の光の下限(0〜1 = 0〜15 段。0 = 切る)。樹冠の下は空の光が 0 で、森の外からは幹が真っ黒だった(ov_giant.glsl)。 */
        public Double giantOutsideSky;
        public Double moonShadeSide, moonShadeBack, moonShadeDown, moonShadeStrength;
        public Double moonMapStrength, moonMapPhaseMin, nightDesaturate;
        /** 夜の森の Mob を沈める(11-06、仮決め): 過繁茂の森の夜に Mob の明るさを満月 mobNightFull・新月 mobNightNew 倍へ(空の光の段で下げる。client/light/MobNightDim)。 */
        public Double mobNightFull, mobNightNew;
        public double[] moonMapColor;
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
        /**
         * 新しい葉の板の揺れ(11-01 夜 見え方の直し 1、仮決め): 板の先が付け根より余分に揺れる倍率・横の板の先が上下する量(枝の揺れの倍率)・
         * 胞子昆布の葉の水の揺れ(ブロック。風によらない)・その速さ(回/秒)。
         */
        public Double fringeBend, fringeLift, kelpAmount, kelpHz;
    }

    /**
     * 節 {@code leaves}: 落ち葉を出す半径・生きている数の上限(重さの段の粒の量を掛ける前)・突風のさなかの 1 秒あたりの数・凪の数と、
     * 葉の色の揺らぎ(一覧 24)の成葉の青緑の強さ・若葉の黄緑の強さ(0〜1。10-17 了承の 1)。
     */
    public static final class LeavesJson {
        public Double radius, perSecondGust, perSecondCalm, toneStrength, youngStrength;
        /** 落ち葉の出やすさの倍率(11-01 夜 見え方の直し 1、仮決め): 封印木・木生シダの芯から(過繁茂の葉は 1)。葉の裏の透け(36)は 11-01 夜にユーザー決定で削除。 */
        public Double fallSigillaria, fallTreeFern;
        public Integer maxAlive;
    }

    /** 節 {@code sound}: 風の音の大きさ・鳴らし始める風の強さ(凪はこれ以下で無音)・雷雨で大きさに足す倍率(1 + stormVolume × 嵐の度合い)。 */
    public static final class SoundJson {
        public Double volume, calmThreshold, stormVolume;
        /** 地下と水中の環境音(2026-11-01、{@link AtmosphereSound})。 */
        public AtmosphereSound.CaveSoundJson cave;
        public AtmosphereSound.WaterSoundJson water;
    }

    /**
     * 節 {@code spores}: 環境の胞子の粒の数(重さの段 高・中・低)・粒の立方体の一辺(ブロック)・粒を置く箱の半幅と高さ(ブロック)・
     * 地下で残す割合・夜の明るさ・光の筋や木漏れ日の日向で明るくする割合(10-30 夜 提案 3)。
     */
    public static final class SporesJson {
        public int[] counts;
        public Double size, boxHalf, boxHeight, undergroundKeep, nightBrightness, litBoost;
        /**
         * 粒の揺られ方(10-30 夜 ユーザー「上下に揺れたりランダムな方に回転したりと風に揺られている感じに」。環境の胞子と光る胞子の両方)。
         * 風の向きを軸にした左右のふらつきの幅(上下の揺れは 10-31 ユーザー「上下の揺れを無くしましょう」で外した)
         * (凪 wobbleCalm + 風の強さあたり wobbleGust ブロック)、立方体の転がり(凪の回転 spinCalm ラジアン/秒 + 風に流された 1 ブロックあたり
         * spinPerBlock ラジアン)。
         */
        public Double wobbleCalm, wobbleGust, spinCalm, spinPerBlock;
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
                             float branchCalm, float branchGust, float flutter, float fringeBend, float fringeLift,
                             float kelpAmount, float kelpHz) {
        static SwayParams of(SwayJson s) {
            SwayJson v = s != null ? s : new SwayJson();
            return new SwayParams(clamp(v.amount, 1.0, 0.0, 3.0), clamp(v.gateFullLevel, 0.15, 0.01, 1.0),
                    clamp(v.riseSeconds, 0.6, 0.05, 10.0), clamp(v.fallSeconds, 1.3, 0.05, 10.0), clamp(v.push, 0.05, 0.0, 0.3),
                    clamp(v.branchCalm, 0.012, 0.0, 0.2), clamp(v.branchGust, 0.04, 0.0, 0.3), clamp(v.flutter, 0.015, 0.0, 0.2),
                    clamp(v.fringeBend, 1.5, 0.0, 4.0), clamp(v.fringeLift, 2.0, 0.0, 6.0), clamp(v.kelpAmount, 0.18, 0.0, 0.6),
                    clamp(v.kelpHz, 0.2, 0.02, 1.0));
        }
    }

    /** 落ち葉の値。 */
    public record LeavesParams(float radius, int maxAlive, float perSecondGust, float perSecondCalm, float toneStrength,
                               float youngStrength, float fallSigillaria, float fallTreeFern) {
        static LeavesParams of(LeavesJson l) {
            LeavesJson v = l != null ? l : new LeavesJson();
            return new LeavesParams(clamp(v.radius, 16.0, 4.0, 48.0), v.maxAlive != null ? Math.max(0, Math.min(400, v.maxAlive)) : 90,
                    clamp(v.perSecondGust, 24.0, 0.0, 200.0), clamp(v.perSecondCalm, 0.3, 0.0, 20.0),
                    clamp(v.toneStrength, 1.0, 0.0, 1.0), clamp(v.youngStrength, 1.0, 0.0, 1.0),
                    clamp(v.fallSigillaria, 0.6, 0.0, 4.0), clamp(v.fallTreeFern, 0.4, 0.0, 4.0));
        }
    }

    /** 風の音の値と、地下・水中の環境音の値(cave・water)。 */
    public record SoundParams(float volume, float calmThreshold, float stormVolume, AtmosphereSound.CaveSoundParams cave,
                              AtmosphereSound.WaterSoundParams water) {
        static SoundParams of(SoundJson s) {
            SoundJson v = s != null ? s : new SoundJson();
            return new SoundParams(clamp(v.volume, 1.0, 0.0, 2.0), clamp(v.calmThreshold, 0.22, 0.0, 1.0), clamp(v.stormVolume, 1.5, 0.0, 4.0),
                    AtmosphereSound.CaveSoundParams.of(v.cave), AtmosphereSound.WaterSoundParams.of(v.water));
        }
    }

    /** 環境の胞子の値。{@code counts} は 高・中・低 の順。 */
    public record SporesParams(int[] counts, float size, float boxHalf, float boxHeight, float undergroundKeep, float nightBrightness,
                               float litBoost, float wobbleCalm, float wobbleGust, float spinCalm, float spinPerBlock) {
        static SporesParams of(SporesJson s) {
            SporesJson v = s != null ? s : new SporesJson();
            int[] def = {1500, 900, 400};
            int[] c = new int[3];
            for (int i = 0; i < 3; i++) {
                c[i] = Math.max(0, Math.min(4096, v.counts != null && v.counts.length > i ? v.counts[i] : def[i]));
            }
            return new SporesParams(c, clamp(v.size, 0.045, 0.01, 0.3), clamp(v.boxHalf, 20.0, 4.0, 64.0),
                    clamp(v.boxHeight, 14.0, 4.0, 48.0), clamp(v.undergroundKeep, 0.25, 0.0, 1.0), clamp(v.nightBrightness, 0.35, 0.0, 1.0),
                    clamp(v.litBoost, 0.5, 0.0, 2.0), clamp(v.wobbleCalm, 0.05, 0.0, 2.0), clamp(v.wobbleGust, 0.15, 0.0, 4.0), clamp(v.spinCalm, 0.06, 0.0, 10.0),
                    clamp(v.spinPerBlock, 0.15, 0.0, 10.0));
        }
    }

    private final float coverStart, coverFull, presenceSmoothSeconds, swampPresence;
    private final float noonShaftStrength, noonShaftShorten, hazeDry, afterRainMinutes, sporeHazeFrom, sporeHazeFull;
    private final float dappleLowScale, dappleHighScale, dappleHighRise, dappleLowSharpness, nightTintStrength;
    private final float[] duskTint, nightTint;
    private final float[] shadeHue, sunWarm;
    private final float sunWarmStrength;
    private final float greenBounce;
    private final float[] skyTintGold, skyTintDusk, skyTintTwilight, skyTintHeights;
    private final float skyTintStrength, skyTintShade;
    private final float skyTintOvercast;
    private final float skyLift, skyLiftDark;
    private final float skyLiftNightFull, skyLiftNightNew, skyLiftDarkNight;
    private final float skyLiftNightFullOpen, skyLiftNightNewOpen;
    private final float skyLiftOpen, trunkShadow, terrainShadowReach, trunkShadowSoft;
    private final float giantOutsideSky;
    private final float moonShadeSide, moonShadeBack, moonShadeDown, moonShadeStrength;
    private final float moonMapStrength, moonMapPhaseMin, nightDesaturate;
    private final float mobNightFull, mobNightNew;
    private final float[] moonMapColor;
    private final float shade, shadeBacklit, shadeTint, forestTint, surfaceShade;
    private final float phaseMin, phaseMax, phasePower;
    private final float intensity, noonSoftness, moonStrength;
    private final int maxShafts, maxMotes, maxDapples;
    private final WindParams wind;
    private final SwayParams sway;
    private final LeavesParams leaves;
    private final SoundParams sound;
    private final SporesParams spores;
    private final FogParams fog;
    private final MarchParams fogMarch;
    private final SilhouetteParams silhouette;
    private final DropsParams drops;
    private final DustParams dust;
    private final DuskParams dusk;
    private final WaterParams water;
    private final InsectsParams insects;
    private final AtmosphereLife.WetParams wet;
    private final AtmosphereLife.WaterGlintParams waterGlint;
    private final AtmosphereLife.RainLookParams rainLook;
    private final AtmosphereAir.SporeAirParams sporeAir;
    private final AtmosphereEye.EyeAdaptParams eyeAdapt;
    private final AtmosphereEye.BloomParams bloom;
    private final AtmosphereEye.OreSparkleParams oreSparkle;
    private final AtmosphereEye.CaveWetParams caveWet;
    private final AtmosphereEye.LeyVeinsParams leyVeins;
    private final AtmosphereEye.SurfaceBumpParams surfaceBump;
    private final AtmosphereEye.SimpleAoParams simpleAo;
    private final AtmosphereEye.PhaseCueParams phaseCue;
    private final AtmosphereEye.DenseFogParams denseFog;
    private final AtmosphereScary.Params scary;
    private final WeatherLookEye.Params stormExtras, calmLook, galeLook;
    private final AtmosphereEye.CaveOpenParams caveOpen;
    private final AtmosphereEye.CaveShaftsParams caveShafts;
    private final AtmosphereEye.CaveBreathParams caveBreath;
    private final AtmosphereEye.HeldLightsParams heldLights;
    private final AtmosphereLife.SteamParams steam;
    private final AtmosphereLife.RipplesParams ripples;
    private final AtmosphereLife.GnatsParams gnats;
    private final AtmosphereLife.DropRippleParams dropRipple;
    private final AtmosphereLife.RainRippleParams rainRipple;
    private final AtmosphereLife.AlgaeParams algae;
    private final AtmosphereLife.CreakParams creak;
    private final AtmosphereGiantLife.Params giantLife;
    private final AtmosphereCaveLife.Params caveLife;
    private final AtmosphereCritters.Params smallCritters;
    private final AtmosphereGiantTree.Params giantTree;
    private final AtmosphereEvents.GaleParams gale;
    private final AtmosphereEvents.GlowNightParams glowNight;
    private final AtmosphereWater.CausticsParams caustics;
    private final AtmosphereWater.ShaftsParams underwaterShafts;
    private final AtmosphereCave.CaveParams cave;

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
        this.shadeHue = ratio(l.shadeHue, 0.92, 1.00, 0.78);
        this.sunWarm = l.sunWarm != null && l.sunWarm.length == 3
                ? new float[]{(float) l.sunWarm[0], (float) l.sunWarm[1], (float) l.sunWarm[2]} : new float[]{1.10F, 1.06F, 0.90F};
        this.sunWarmStrength = clamp(l.sunWarmStrength, 1.0, 0.0, 2.0);
        this.greenBounce = clamp(l.greenBounce, 0.03, 0.0, 0.5);
        this.skyTintGold = ratio(l.skyTintGold, 1.00, 0.88, 0.66);
        this.skyTintDusk = ratio(l.skyTintDusk, 1.00, 0.74, 0.60);
        this.skyTintTwilight = ratio(l.skyTintTwilight, 0.70, 0.85, 1.00);
        this.skyTintHeights = descending(l.skyTintHeights, new float[]{0.35F, 0.15F, 0.0F, -0.15F, -0.35F});
        this.skyTintStrength = clamp(l.skyTintStrength, 1.0, 0.0, 1.0);
        this.skyTintShade = clamp(l.skyTintShade, 0.7, 0.0, 1.0);
        this.skyTintOvercast = clamp(l.skyTintOvercast, 0.8, 0.0, 1.0);
        this.swampPresence = clamp(l.swampPresence, 0.45, 0.0, 1.0);
        this.skyLift = clamp(l.skyLift, 1.0, 0.0, 1.0);
        this.skyLiftDark = clamp(l.skyLiftDark, 0.8, 0.0, 1.0);
        this.skyLiftNightFull = clamp(l.skyLiftNightFull, 0.35, 0.0, 1.0);
        this.skyLiftNightNew = clamp(l.skyLiftNightNew, 0.05, 0.0, 1.0);
        this.skyLiftNightFullOpen = clamp(l.skyLiftNightFullOpen, 0.45, 0.0, 1.0);
        this.skyLiftNightNewOpen = clamp(l.skyLiftNightNewOpen, 0.12, 0.0, 1.0);
        this.skyLiftOpen = clamp(l.skyLiftOpen, 0.5, 0.0, 1.0);
        this.giantOutsideSky = clamp(l.giantOutsideSky, 0.8, 0.0, 1.0);
        this.trunkShadow = clamp(l.trunkShadow, 0.25, 0.0, 0.9);
        this.terrainShadowReach = clamp(l.terrainShadowReach, 32.0, 2.0, 64.0);
        this.trunkShadowSoft = clamp(l.trunkShadowSoft, 0.5, 0.0, 1.0);
        this.skyLiftDarkNight = clamp(l.skyLiftDarkNight, 0.3, 0.0, 1.0);
        this.moonShadeSide = clamp(l.moonShadeSide, 0.8, 0.0, 1.0);
        this.moonShadeBack = clamp(l.moonShadeBack, 0.5, 0.0, 1.0);
        this.moonShadeDown = clamp(l.moonShadeDown, 0.35, 0.0, 1.0);
        this.moonShadeStrength = clamp(l.moonShadeStrength, 1.0, 0.0, 1.0);
        this.moonMapStrength = clamp(l.moonMapStrength, 0.15, 0.0, 1.0);
        this.moonMapPhaseMin = clamp(l.moonMapPhaseMin, 0.5, 0.0, 0.99);
        this.nightDesaturate = clamp(l.nightDesaturate, 0.4, 0.0, 1.0);
        this.mobNightFull = clamp(l.mobNightFull, 0.5, 0.0, 1.0);
        this.mobNightNew = clamp(l.mobNightNew, 0.35, 0.0, 1.0);
        this.moonMapColor = ratio(l.moonMapColor, 0.75, 0.85, 1.00);
        this.shade = clamp(l.shade, 0.96, 0.0, 1.0);
        this.shadeBacklit = clamp(l.shadeBacklit, 0.86, 0.0, 1.0);
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
        this.fog = FogParams.of(j != null ? j.fog : null);
        this.fogMarch = MarchParams.of(j != null ? j.fogMarch : null);
        this.silhouette = SilhouetteParams.of(j != null ? j.silhouette : null);
        this.drops = DropsParams.of(j != null ? j.drops : null);
        this.dust = DustParams.of(j != null ? j.dust : null);
        this.dusk = DuskParams.of(j != null ? j.dusk : null);
        this.water = WaterParams.of(j != null ? j.water : null);
        this.insects = InsectsParams.of(j != null ? j.insects : null);
        this.wet = AtmosphereLife.WetParams.of(j != null ? j.wet : null);
        this.waterGlint = AtmosphereLife.WaterGlintParams.of(j != null ? j.waterGlint : null);
        this.rainLook = AtmosphereLife.RainLookParams.of(j != null ? j.rainLook : null);
        this.sporeAir = AtmosphereAir.SporeAirParams.of(j != null ? j.sporeAir : null);
        this.eyeAdapt = AtmosphereEye.EyeAdaptParams.of(j != null ? j.eyeAdapt : null);
        this.bloom = AtmosphereEye.BloomParams.of(j != null ? j.bloom : null);
        this.oreSparkle = AtmosphereEye.OreSparkleParams.of(j != null ? j.oreSparkle : null);
        this.caveWet = AtmosphereEye.CaveWetParams.of(j != null ? j.caveWet : null);
        this.leyVeins = AtmosphereEye.LeyVeinsParams.of(j != null ? j.leyVeins : null);
        this.surfaceBump = AtmosphereEye.SurfaceBumpParams.of(j != null ? j.surfaceBump : null);
        this.simpleAo = AtmosphereEye.SimpleAoParams.of(j != null ? j.simpleAo : null);
        this.phaseCue = AtmosphereEye.PhaseCueParams.of(j != null ? j.phaseCue : null);
        this.denseFog = AtmosphereEye.DenseFogParams.of(j != null ? j.denseFog : null);
        this.scary = AtmosphereScary.Params.of(j != null ? j.scary : null);
        this.stormExtras = WeatherLookEye.storm(j != null ? j.stormExtras : null);
        this.calmLook = WeatherLookEye.calm(j != null ? j.calmLook : null);
        this.galeLook = WeatherLookEye.gale(j != null ? j.galeLook : null);
        this.caveOpen = AtmosphereEye.CaveOpenParams.of(j != null ? j.caveOpen : null);
        this.caveShafts = AtmosphereEye.CaveShaftsParams.of(j != null ? j.caveShafts : null);
        this.caveBreath = AtmosphereEye.CaveBreathParams.of(j != null ? j.caveBreath : null);
        this.heldLights = AtmosphereEye.HeldLightsParams.of(j != null ? j.heldLights : null);
        this.steam = AtmosphereLife.SteamParams.of(j != null ? j.steam : null);
        this.ripples = AtmosphereLife.RipplesParams.of(j != null ? j.ripples : null);
        this.gnats = AtmosphereLife.GnatsParams.of(j != null ? j.gnats : null);
        this.dropRipple = AtmosphereLife.DropRippleParams.of(j != null ? j.dropRipple : null);
        this.rainRipple = AtmosphereLife.RainRippleParams.of(j != null ? j.rainRipple : null);
        this.algae = AtmosphereLife.AlgaeParams.of(j != null ? j.algae : null);
        this.creak = AtmosphereLife.CreakParams.of(j != null ? j.creak : null);
        this.giantLife = AtmosphereGiantLife.Params.of(j != null ? j.giantLife : null);
        this.caveLife = AtmosphereCaveLife.Params.of(j != null ? j.caveLife : null);
        this.smallCritters = AtmosphereCritters.Params.of(j != null ? j.smallCritters : null);
        this.giantTree = AtmosphereGiantTree.Params.of(j != null ? j.giantTree : null);
        this.gale = AtmosphereEvents.GaleParams.of(j != null ? j.gale : null);
        this.glowNight = AtmosphereEvents.GlowNightParams.of(j != null ? j.glowNight : null);
        this.caustics = AtmosphereWater.CausticsParams.of(j != null ? j.caustics : null);
        this.underwaterShafts = AtmosphereWater.ShaftsParams.of(j != null ? j.underwaterShafts : null);
        this.cave = AtmosphereCave.CaveParams.of(j != null ? j.cave : null);
    }

    /** 色の比を、明るさ 0.3R + 0.59G + 0.11B が 1 になるようそろえる。 */
    private static float[] ratio(double[] v, double r, double g, double b) {
        double[] c = v != null && v.length == 3 ? v : new double[]{r, g, b};
        double lum = Math.max(1.0E-3, 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]);
        return new float[]{(float) (c[0] / lum), (float) (c[1] / lum), (float) (c[2] / lum)};
    }

    /** 上から下へ並んだ高さ(5 つ)。数・順が合わなければ既定値。 */
    private static float[] descending(double[] v, float[] def) {
        if (v == null || v.length != def.length) {
            return def;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) Math.max(-1.0, Math.min(1.0, v[i]));
            if (i > 0 && out[i] >= out[i - 1]) {
                return def;
            }
        }
        return out;
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

    /** 昼の影の色の比(明るさ 1)。 */
    public float[] shadeHue() {
        return shadeHue;
    }

    /** 日なたの暖かい白〜金(掛け算の比)。 */
    public float[] sunWarm() {
        return sunWarm;
    }

    public float sunWarmStrength() {
        return sunWarmStrength;
    }

    /** 緑の照り返しの強さ(A3)。 */
    public float greenBounce() {
        return greenBounce;
    }

    /** 空の光の色(11-05 光の点検 3): 金・日の入り・日没後の色の比(明るさ 1 にそろえた)と、切り替わる太陽の高さ 5 つ(上から)。 */
    public float[] skyTintGold() {
        return skyTintGold;
    }

    public float[] skyTintDusk() {
        return skyTintDusk;
    }

    public float[] skyTintTwilight() {
        return skyTintTwilight;
    }

    public float[] skyTintHeights() {
        return skyTintHeights;
    }

    /** 林冠の開いた所での強さと、傘の下でその何倍か。 */
    public float skyTintStrength() {
        return skyTintStrength;
    }

    public float skyTintShade() {
        return skyTintShade;
    }

    /** 曇りの日に空の光の色を弱める割合(11-13)。 */
    public float skyTintOvercast() {
        return skyTintOvercast;
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

    public float skyLiftNightFull() {
        return skyLiftNightFull;
    }

    public float skyLiftNightNew() {
        return skyLiftNightNew;
    }

    /** 過繁茂の外の夜の林床の持ち上げ(満月・新月。11-06 2 層)。 */
    public float skyLiftNightFullOpen() {
        return skyLiftNightFullOpen;
    }

    public float skyLiftNightNewOpen() {
        return skyLiftNightNewOpen;
    }

    /** 森の外から見た巨大樹の空の光の下限(11-13、0〜1)。 */
    public float giantOutsideSky() {
        return giantOutsideSky;
    }

    /** 過繁茂の外の昼の林床の持ち上げ(点検 21)。 */
    public float skyLiftOpen() {
        return skyLiftOpen;
    }

    /** 幹の影の帯の濃さ(0 = 無し。点検 21)。 */
    public float trunkShadow() {
        return trunkShadow;
    }

    /** 地形の影(幹・岩・丘・崖・家)が届く、影を落とす物と面の高さの差(ブロック。バニラ地域の光 1、11-06)。 */
    public float terrainShadowReach() {
        return terrainShadowReach;
    }

    /** 幹の影の帯の縁のぼけ(届く長さのうち、薄れていく割合)。 */
    public float trunkShadowSoft() {
        return trunkShadowSoft;
    }

    public float skyLiftDarkNight() {
        return skyLiftDarkNight;
    }

    public float moonShadeSide() {
        return moonShadeSide;
    }

    public float moonShadeBack() {
        return moonShadeBack;
    }

    public float moonShadeDown() {
        return moonShadeDown;
    }

    public float moonShadeStrength() {
        return moonShadeStrength;
    }

    public float moonMapStrength() {
        return moonMapStrength;
    }

    public float moonMapPhaseMin() {
        return moonMapPhaseMin;
    }

    public float mobNightFull() {
        return mobNightFull;
    }

    public float mobNightNew() {
        return mobNightNew;
    }

    public float nightDesaturate() {
        return nightDesaturate;
    }

    /** 月の斑点の色(比。明るさ 1 にそろえる)。 */
    public float[] moonMapColor() {
        return moonMapColor;
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

    /** 霧の土台(節 {@code fog})。 */
    public FogParams fog() {
        return fog;
    }

    /** 霧の細部(節 {@code fogMarch})。 */
    public MarchParams fogMarch() {
        return fogMarch;
    }

    /** 巨大樹のシルエット(節 {@code silhouette})。 */
    public SilhouetteParams silhouette() {
        return silhouette;
    }

    /** 雨上がりの水滴(節 {@code drops})。 */
    public DropsParams drops() {
        return drops;
    }

    /** 胞子の粉の見た目(節 {@code dust})。 */
    public DustParams dust() {
        return dust;
    }

    /** 夕方の光の粒(節 {@code dusk})。 */
    public DuskParams dusk() {
        return dusk;
    }

    /** 水音(節 {@code water})。 */
    public WaterParams water() {
        return water;
    }

    /** 虫・爬虫類の声(節 {@code insects})。 */
    public InsectsParams insects() {
        return insects;
    }

    /** 見え方の段(10-31)の値(節 wet・steam・ripples・gnats・creak。{@link AtmosphereLife})。 */
    /** 水面の太陽と月(節 waterGlint、11-06 バニラ地域の光 4)。 */
    /** 雨の見え方(節 rainLook、11-06)。 */
    /** 胞子の空気(節 sporeAir、11-06。{@link AtmosphereAir})。 */
    /** 洞窟の出入りの目の慣れ(節 eyeAdapt、11-07。{@link AtmosphereEye})。 */
    /** 過繁茂の外の洞窟(節 caveOpen、11-07。{@link AtmosphereEye})。 */
    /** 洞窟の天井の穴の光の筋(節 caveShafts、11-07。{@link AtmosphereEye})。 */
    /** 手に持った明かり(節 heldLights、11-07。{@link AtmosphereEye})。 */
    public AtmosphereEye.HeldLightsParams heldLights() {
        return heldLights;
    }

    public AtmosphereEye.CaveShaftsParams caveShafts() {
        return caveShafts;
    }

    /** 洞窟の呼吸(節 caveBreath、11-08 境目の帯 1。{@link AtmosphereEye})。 */
    public AtmosphereEye.CaveBreathParams caveBreath() {
        return caveBreath;
    }

    public AtmosphereEye.CaveOpenParams caveOpen() {
        return caveOpen;
    }

    public AtmosphereEye.EyeAdaptParams eyeAdapt() {
        return eyeAdapt;
    }

    /** 光るもののにじみ(節 bloom、11-13 仕上げ B。{@link AtmosphereEye})。 */
    public AtmosphereEye.BloomParams bloom() {
        return bloom;
    }

    /** 鉱石のきらめき(節 oreSparkle、11-13 仕上げ E。{@link AtmosphereEye})。 */
    public AtmosphereEye.OreSparkleParams oreSparkle() {
        return oreSparkle;
    }

    /** 濡れた岩の照り(節 caveWet、11-14 仕上げ F。{@link AtmosphereEye})。 */
    public AtmosphereEye.CaveWetParams caveWet() {
        return caveWet;
    }

    /** 岩肌の地脈の筋(節 leyVeins、11-14 仕上げ F。{@link AtmosphereEye})。 */
    public AtmosphereEye.LeyVeinsParams leyVeins() {
        return leyVeins;
    }

    /** テクスチャの明暗から面の凹凸(節 surfaceBump、11-14 仕上げ G。{@link AtmosphereEye})。 */
    public AtmosphereEye.SurfaceBumpParams surfaceBump() {
        return surfaceBump;
    }

    /** 隙間と根元の陰り(簡易 AO。節 simpleAo、11-14 仕上げ H。{@link AtmosphereEye})。 */
    public AtmosphereEye.SimpleAoParams simpleAo() {
        return simpleAo;
    }

    /** フェーズが上がったときの合図(節 phaseCue、11-16。{@link AtmosphereEye})。 */
    public AtmosphereEye.PhaseCueParams phaseCue() {
        return phaseCue;
    }

    /** 濃霧(天気。節 denseFog、11-16。{@link AtmosphereEye})。 */
    public AtmosphereEye.DenseFogParams denseFog() {
        return denseFog;
    }

    /** 怖い現象 A・D・E(節 scary、11-16。{@link AtmosphereScary})。 */
    public AtmosphereScary.Params scary() {
        return scary;
    }

    /** 天気の見た目の残り(節 stormExtras・calmLook・galeLook、11-16。{@link WeatherLookEye})。 */
    public WeatherLookEye.Params stormExtras() {
        return stormExtras;
    }

    public WeatherLookEye.Params calmLook() {
        return calmLook;
    }

    public WeatherLookEye.Params galeLook() {
        return galeLook;
    }

    public AtmosphereAir.SporeAirParams sporeAir() {
        return sporeAir;
    }

    public AtmosphereLife.RainLookParams rainLook() {
        return rainLook;
    }

    public AtmosphereLife.WaterGlintParams waterGlint() {
        return waterGlint;
    }

    public AtmosphereLife.WetParams wet() {
        return wet;
    }

    public AtmosphereLife.SteamParams steam() {
        return steam;
    }

    public AtmosphereLife.RipplesParams ripples() {
        return ripples;
    }

    public AtmosphereLife.GnatsParams gnats() {
        return gnats;
    }

    public AtmosphereLife.DropRippleParams dropRipple() {
        return dropRipple;
    }

    public AtmosphereLife.RainRippleParams rainRipple() {
        return rainRipple;
    }

    public AtmosphereLife.AlgaeParams algae() {
        return algae;
    }

    /** 洞窟の小ネタ(節 caveLife、11-07)。 */
    public AtmosphereCaveLife.Params caveLife() {
        return caveLife;
    }

    /** 見た目だけの小さな生き物(節 smallCritters、11-17)。 */
    public AtmosphereCritters.Params smallCritters() {
        return smallCritters;
    }

    /** 巨大樹のまわりの生き物と音(節 giantLife、11-06 改善案 ⑤)。 */
    public AtmosphereGiantLife.Params giantLife() {
        return giantLife;
    }

    /** 巨大樹の胞子の柱・夜の地脈の光(節 giantTree、11-06 改善案 ①③)。 */
    public AtmosphereGiantTree.Params giantTree() {
        return giantTree;
    }

    /** G4 胞子の風(節 gale、11-08。{@link AtmosphereEvents})。 */
    public AtmosphereEvents.GaleParams gale() {
        return gale;
    }

    /** G5 光る胞子の夜(節 glowNight、11-08。{@link AtmosphereEvents})。 */
    public AtmosphereEvents.GlowNightParams glowNight() {
        return glowNight;
    }

    public AtmosphereLife.CreakParams creak() {
        return creak;
    }

    public AtmosphereWater.CausticsParams caustics() {
        return caustics;
    }

    public AtmosphereWater.ShaftsParams underwaterShafts() {
        return underwaterShafts;
    }

    /** 地下モード(節 {@code cave}、11-01 試作。{@link AtmosphereCave})。 */
    public AtmosphereCave.CaveParams cave() {
        return cave;
    }
}
