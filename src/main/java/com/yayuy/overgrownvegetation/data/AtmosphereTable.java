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
     */
    public static final class FogJson {
        public Double density, falloff, depthMin, depthRange, lowPercentile, canopyPercentile, highPercentile, seaAbove;
        public Double aboveBoost, aboveRamp, insideFogEnd, insideNearFraction, insideSoft, noonDensity, noonDepth;
        public Double afterRainDensity, afterRainDepth, weakMorning, colorDistance, sunLobe, noise, noiseScale, proxCells;
        public Double proxCellsFlat, mistAfterRainMinutes, afterRainLinger, stormDensity, stormDepth, stormDarken, sporeHaze;
        public Double forestFogDark, shadeFloor, forestSky, stormFogEnd, sunGlow, farDesat, desatFrom, desatTo;
        public double[] nearColor, farColor, nightColor, hazeColor, forestSkyColor;
    }

    /** 霧の土台の値。 */
    public record FogParams(float density, float falloff, float depthMin, float depthRange, float lowPercentile, float canopyPercentile,
                            float highPercentile, float seaAbove, float aboveBoost, float aboveRamp, float insideFogEnd,
                            float insideNearFraction, float insideSoft, float noonDensity, float noonDepth, float afterRainDensity,
                            float afterRainDepth, float weakMorning, float colorDistance, float sunLobe, float noise, float noiseScale,
                            float proxCells, float proxCellsFlat, float mistAfterRainMinutes, float afterRainLinger, float stormDensity,
                            float stormDepth, float stormDarken, float sporeHaze, float forestFogDark, float shadeFloor, float forestSky,
                            float stormFogEnd, float sunGlow, float farDesat, float desatFrom, float desatTo,
                            float[] nearColor, float[] farColor, float[] nightColor, float[] hazeColor, float[] forestSkyColor) {
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
                    clamp(v.stormFogEnd, 40.0, 8.0, 1024.0), clamp(v.sunGlow, 0.8, 0.0, 3.0), clamp(v.farDesat, 0.25, 0.0, 1.0),
                    clamp(v.desatFrom, 48.0, 0.0, 1024.0), clamp(v.desatTo, 192.0, 1.0, 2048.0),
                    color(v.nearColor, 0.96, 0.96, 0.92), color(v.farColor, 0.80, 0.86, 0.80), color(v.nightColor, 0.10, 0.15, 0.10),
                    color(v.hazeColor, 0.84, 0.87, 0.74), color(v.forestSkyColor, 0.94, 0.96, 0.90));
        }

        private static float[] color(double[] c, double r, double g, double b) {
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
    private final FogParams fog;
    private final MarchParams fogMarch;

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
        this.shadeHue = ratio(l.shadeHue, 0.78, 1.00, 0.92);
        this.sunWarm = l.sunWarm != null && l.sunWarm.length == 3
                ? new float[]{(float) l.sunWarm[0], (float) l.sunWarm[1], (float) l.sunWarm[2]} : new float[]{1.18F, 1.08F, 0.80F};
        this.sunWarmStrength = clamp(l.sunWarmStrength, 1.0, 0.0, 2.0);
        this.greenBounce = clamp(l.greenBounce, 0.06, 0.0, 0.5);
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
        this.fog = FogParams.of(j != null ? j.fog : null);
        this.fogMarch = MarchParams.of(j != null ? j.fogMarch : null);
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

    /** 霧の土台(節 {@code fog})。 */
    public FogParams fog() {
        return fog;
    }

    /** 霧の細部(節 {@code fogMarch})。 */
    public MarchParams fogMarch() {
        return fogMarch;
    }
}
