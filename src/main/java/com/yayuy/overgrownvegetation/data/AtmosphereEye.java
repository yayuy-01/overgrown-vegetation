package com.yayuy.overgrownvegetation.data;

/**
 * 洞窟の出入りの目の慣れ(節 eyeAdapt、2026-11-07。ユーザーの考え「暗い所から明るい所へ出た瞬間の視覚効果をここに仕込めば、外と洞窟を違和感なくつなげられる」)。
 * 読むのはクライアントの描画だけ({@code client/atmosphere/EyeAdaptation}・{@code CaveLights})。
 *
 * <p>ユーザー指定: 出るときは画面が白く飛び 2〜4 秒で戻る・白い間に地下モードの切り替えを済ませる・入るときは暗く光源だけが見えて数秒で岩の輪郭が見える・
 * 強さは暗い所にいた時間で決める(口を少し出入りしただけならほとんど出ない)・強さは「ほどほど」・世界全体で効かせる・オフにできる。数はすべて仮決め:
 * <ul>
 *     <li>{@code lightSeconds}・{@code darkSeconds}: 「目が慣れている明るさ」(EV = 明るさの 2 を底とする対数)が今の明るさを追う時定数。明るくなる向き(明順応)・暗くなる向き(暗順応)。</li>
 *     <li>{@code deadband}・{@code range}: 今の明るさと慣れの差(EV)が deadband を超えた分を range で 0 → 1 にしたものが、まぶしさ(出るとき)・暗さ(入るとき)の度合い。</li>
 *     <li>今の明るさ = max((空の光/15)² × 昼の明るさ, (ブロックの光/15)² × {@code blockWeight}, {@code floor})。昼の明るさは夜 {@code nightSky} 〜 昼 1。</li>
 *     <li>まぶしさ: {@code glareExposure}(明るさの倍率の上乗せ)・{@code glareBloom}(明るい所のにじみ)・{@code glareWash}(白へ寄せてコントラストを下げる)・{@code glareDesaturate}(彩度を抜く)。</li>
 *     <li>暗さ: {@code darkAmbient}(地下モードの床の持ち上げ ambient の倍率の下限)・{@code darkShadow}(暗い所をさらに沈める割合。明るい光源は残す)。</li>
 *     <li>{@code hideSeconds}・{@code hideFrom}: まぶしさ・暗さが hideFrom を超えている間、地下モードの寄せの時定数をこの秒数まで縮める(白い間に霧とシェーダーの切り替えを済ませる)。</li>
 * </ul>
 */
public final class AtmosphereEye {

    private AtmosphereEye() {
    }

    public static final class EyeAdaptJson {
        /** false = 目の慣れを切る。 */
        public Boolean enabled;
        public Double lightSeconds, darkSeconds, deadband, range, nightSky, blockWeight, floor, glareExposure, glareBloom, glareWash, glareDesaturate,
                darkAmbient, darkShadow, hideSeconds, hideFrom;
    }

    public record EyeAdaptParams(boolean enabled, float lightSeconds, float darkSeconds, float deadband, float range, float nightSky, float blockWeight,
                                 float floor, float glareExposure, float glareBloom, float glareWash, float glareDesaturate, float darkAmbient,
                                 float darkShadow, float hideSeconds, float hideFrom) {
        static EyeAdaptParams of(EyeAdaptJson j) {
            EyeAdaptJson v = j != null ? j : new EyeAdaptJson();
            return new EyeAdaptParams(v.enabled == null || v.enabled, c(v.lightSeconds, 2.5, 0.05, 60.0), c(v.darkSeconds, 8.0, 0.05, 120.0),
                    c(v.deadband, 1.0, 0.0, 10.0), c(v.range, 6.0, 0.1, 20.0), c(v.nightSky, 0.03, 0.0, 1.0), c(v.blockWeight, 0.2, 0.0, 1.0),
                    c(v.floor, 0.008, 0.0001, 1.0), c(v.glareExposure, 0.5, 0.0, 4.0), c(v.glareBloom, 0.4, 0.0, 4.0), c(v.glareWash, 0.2, 0.0, 1.0),
                    c(v.glareDesaturate, 0.3, 0.0, 1.0), c(v.darkAmbient, 0.15, 0.0, 1.0), c(v.darkShadow, 0.35, 0.0, 1.0),
                    c(v.hideSeconds, 0.6, 0.0, 60.0), c(v.hideFrom, 0.3, 0.0, 1.0));
        }
    }

    /**
     * 過繁茂の外の洞窟(節 caveOpen、2026-11-07 判断の後の直し。ユーザー「おすすめで直す」)。地下モードの仕組みは世界全体、過繁茂らしさは色と見通しで出す 2 層の方針で、
     * 過繁茂の外の洞窟だけ値を変える(過繁茂の中は前のまま)。過繁茂の度合い(カメラの所の判定を blendSeconds で寄せたもの)で混ぜる。数はすべて仮決め:
     * <ul>
     *     <li>{@code fogScale}: 黒い霧の始まりと終わり(cave.fogStart・fogEnd)の倍率(広い洞窟の見通し)。</li>
     *     <li>{@code fogDarken}: 霧の色(cave.fogColor)の倍率(黒へ寄せる)。</li>
     *     <li>{@code lightGain}: 点光源の足し分(cave.lightGain)の倍率(たいまつの洞窟が明るすぎた)。</li>
     *     <li>{@code tint}: ライトマップの色寄せの強さの倍率。{@code ambientColor}: 床の持ち上げの色(灰色寄り)。</li>
     * </ul>
     */
    public static final class CaveOpenJson {
        public Double fogScale, fogDarken, lightGain, tint, blendSeconds;
        public double[] ambientColor;
    }

    public record CaveOpenParams(float fogScale, float fogDarken, float lightGain, float tint, float[] ambientColor, float blendSeconds) {
        static CaveOpenParams of(CaveOpenJson j) {
            CaveOpenJson v = j != null ? j : new CaveOpenJson();
            double[] a = v.ambientColor != null && v.ambientColor.length >= 3 ? v.ambientColor : new double[]{0.86, 0.88, 0.86};
            return new CaveOpenParams(c(v.fogScale, 2.5, 0.25, 8.0), c(v.fogDarken, 0.4, 0.0, 1.0), c(v.lightGain, 0.5, 0.0, 2.0),
                    c(v.tint, 0.3, 0.0, 1.0), new float[]{c(a[0], 0.86, 0.0, 2.0), c(a[1], 0.88, 0.0, 2.0), c(a[2], 0.86, 0.0, 2.0)},
                    c(v.blendSeconds, 2.0, 0.0, 30.0));
        }
    }

    /**
     * 洞窟の天井の穴から差す日光の筋(節 caveShafts、2026-11-07。ユーザー「暗闇の中の洞窟へ地上に空いた穴から日光が差し込む」)。読むのは
     * {@code client/atmosphere/CaveShafts} と {@code ShaftMotes}。ユーザー指定は向き(巨大な筋・黒い霧の中でもはっきり・床のにじみ・漂う粒・
     * 朝夕は傾いて金・正午は白・夜は月光・雨は弱く・世界中)だけで、数はすべて仮決め:
     * <ul>
     *     <li>{@code radius}・{@code scanPerTick}・{@code maxShafts}: 探す範囲(ブロック)・1 tick に調べる列・筋の上限。</li>
     *     <li>{@code minDrop}: 穴から床までの長さの下限。{@code widthPerRadius}・{@code maxWidth}: 筋の太さ = 穴の半径 × これ(上限)。</li>
     *     <li>{@code strength}・{@code coreAlpha}・{@code haloAlpha}・{@code glow}: 全体の強さ・芯・にじみの帯・床のにじみ。</li>
     *     <li>{@code fogKeep}: 黒い霧の中で筋を残す割合(1 = 霧で沈めない)。{@code dustColor}: 過繁茂の外の漂う粒(埃)の色。</li>
     * </ul>
     */
    public static final class CaveShaftsJson {
        public Boolean enabled;
        public Double radius, scanPerTick, maxShafts, minDrop, widthPerRadius, maxWidth, strength, coreAlpha, haloAlpha, glow, fogKeep;
        public double[] dustColor;
    }

    public record CaveShaftsParams(boolean enabled, int radius, int scanPerTick, int maxShafts, float minDrop, float widthPerRadius, float maxWidth,
                                   float strength, float coreAlpha, float haloAlpha, float glow, float fogKeep, float[] dustColor) {
        static CaveShaftsParams of(CaveShaftsJson j) {
            CaveShaftsJson v = j != null ? j : new CaveShaftsJson();
            double[] d = v.dustColor != null && v.dustColor.length >= 3 ? v.dustColor : new double[]{0.95, 0.92, 0.85};
            return new CaveShaftsParams(v.enabled == null || v.enabled, Math.round(c(v.radius, 48.0, 8.0, 96.0)), Math.round(c(v.scanPerTick, 16.0, 1.0, 128.0)),
                    Math.round(c(v.maxShafts, 16.0, 0.0, 64.0)), c(v.minDrop, 6.0, 1.0, 64.0), c(v.widthPerRadius, 0.35, 0.05, 2.0),
                    c(v.maxWidth, 4.0, 0.5, 12.0), c(v.strength, 1.0, 0.0, 4.0), c(v.coreAlpha, 0.32, 0.0, 1.0), c(v.haloAlpha, 0.12, 0.0, 1.0),
                    c(v.glow, 0.35, 0.0, 2.0), c(v.fogKeep, 0.8, 0.0, 1.0),
                    new float[]{c(d[0], 0.95, 0.0, 1.0), c(d[1], 0.92, 0.0, 1.0), c(d[2], 0.85, 0.0, 1.0)});
        }
    }

    /**
     * 手に持った明かり(節 heldLights、2026-11-07 ユーザー決定)。たいまつ・ランタン・光る植物など光るブロックのアイテムを手に持つと、手の所に点光源を 1 つ足す
     * ({@code client/atmosphere/CaveLights#heldLights})。見た目だけ。色と半径と炎のゆらぎは置いたブロックの点光源と同じ。数はすべて仮決め:
     * {@code strength}・{@code radiusScale} = 置いたときの強さ・半径の倍率、{@code daylightCut} = 明るい所(空の光 × 昼)で弱める割合、
     * {@code otherPlayers}・{@code otherRange} = 近くの他のプレイヤーの明かりも入れるか・その距離。画質の段「低」では出さない。
     */
    public static final class HeldLightsJson {
        public Boolean enabled, otherPlayers;
        public Double strength, radiusScale, daylightCut, otherRange;
    }

    public record HeldLightsParams(boolean enabled, float strength, float radiusScale, float daylightCut, boolean otherPlayers, float otherRange) {
        static HeldLightsParams of(HeldLightsJson j) {
            HeldLightsJson v = j != null ? j : new HeldLightsJson();
            return new HeldLightsParams(v.enabled == null || v.enabled, c(v.strength, 1.0, 0.0, 4.0), c(v.radiusScale, 0.8, 0.1, 3.0),
                    c(v.daylightCut, 1.0, 0.0, 1.0), v.otherPlayers == null || v.otherPlayers, c(v.otherRange, 32.0, 0.0, 128.0));
        }
    }

    /**
     * 洞窟の呼吸(節 caveBreath、2026-11-08 境目の帯 1。設定: 地下の汚染は胞子が流れ込むから起きる。それを口の所で見せる)。読むのは {@code client/atmosphere/CaveBreath}。
     * 卓越風が口へ吹き込むときは外の胞子・埃の粒が中へ流れ込み、口から吹き出すときは中の湿った空気が霞になって出ていく。ユーザー指定は向きだけで、数はすべて仮決め:
     * <ul>
     *     <li>{@code radius}・{@code scanPerTick}・{@code maxVents}: 口を探す範囲・1 tick に試す所・覚えておく口の上限。</li>
     *     <li>{@code skyMin}・{@code skyMax}: 口の内側とみなす空気の空の光。{@code reachMax}: 内側から外(上に岩の天井が無い所)まで探す長さ。
     *     {@code depthMax}: 中へ流れ込む長さの上限。</li>
     *     <li>{@code flowThreshold}: 風の向きと口の向きの内積がこれより小さければ凪(出入り無し)。{@code windGain}: 風の強さを量へ掛ける倍率。</li>
     *     <li>{@code inSpeed}・{@code outSpeed}: 流れ込む粒・出ていく霞の速さ(ブロック/tick)。{@code inRate}・{@code outRate}: 口 1 つ・1 tick に出す粒・霞の数(風がいちばん強いとき)。</li>
     *     <li>{@code moteCounts}・{@code hazeCounts}: 画質の段(高・中・低)ごとの粒・霞の上限。{@code moteSize}・{@code moteAlpha}・{@code hazeSize}・{@code hazeAlpha}: 大きさと濃さ。</li>
     *     <li>縦穴(11-08 ユーザー決定): 床の空の光が {@code shaftSkyMin} 以上で上に岩が無く、まわりの輪に天井がある所。昼夜の温度差で出入りする: 太陽の高さ ÷ {@code thermalSun} で
     *     昼は吹き出し(霞が穴を昇る)、夜は吹き込み(粒が穴を下りる)、量は {@code thermalGain} 倍。{@code nearFrom}〜{@code nearTo}: カメラに近い粒を小さく・薄くする距離。</li>
     *     <li>{@code dustFloor}: 胞子の無い所でも出す埃の割合(胞子の濃さ 0 のとき)。{@code dustColor}・{@code sporeColor}・{@code hazeColor}: 色(胞子の濃さで埃 → 胞子へ寄せる)。</li>
     * </ul>
     */
    public static final class CaveBreathJson {
        public Boolean enabled;
        public Double radius, scanPerTick, maxVents, skyMin, skyMax, reachMax, depthMax, flowThreshold, windGain, inSpeed, outSpeed, inRate, outRate,
                moteSize, moteAlpha, hazeSize, hazeAlpha, dustFloor, thermalSun, thermalGain, shaftSkyMin, nearFrom, nearTo, overgrownHazeLight;
        public int[] moteCounts, hazeCounts;
        public double[] dustColor, sporeColor, hazeColor, overgrownHazeColor;
    }

    public record CaveBreathParams(boolean enabled, int radius, int scanPerTick, int maxVents, int skyMin, int skyMax, int reachMax, int depthMax,
                                   float flowThreshold, float windGain, float inSpeed, float outSpeed, float inRate, float outRate, int[] moteCounts,
                                   int[] hazeCounts, float moteSize, float moteAlpha, float hazeSize, float hazeAlpha, float dustFloor, float[] dustColor,
                                   float[] sporeColor, float[] hazeColor, float thermalSun, float thermalGain, int shaftSkyMin, float nearFrom,
                                   float nearTo, float[] overgrownHazeColor, float overgrownHazeLight) {
        static CaveBreathParams of(CaveBreathJson j) {
            CaveBreathJson v = j != null ? j : new CaveBreathJson();
            return new CaveBreathParams(v.enabled == null || v.enabled, Math.round(c(v.radius, 32.0, 8.0, 64.0)), Math.round(c(v.scanPerTick, 3.0, 1.0, 32.0)),
                    Math.round(c(v.maxVents, 8.0, 0.0, 32.0)), Math.round(c(v.skyMin, 4.0, 0.0, 15.0)), Math.round(c(v.skyMax, 13.0, 0.0, 15.0)),
                    Math.round(c(v.reachMax, 7.0, 2.0, 16.0)), Math.round(c(v.depthMax, 14.0, 2.0, 32.0)), c(v.flowThreshold, 0.2, 0.0, 1.0),
                    c(v.windGain, 1.0, 0.0, 4.0), c(v.inSpeed, 0.1, 0.01, 0.5), c(v.outSpeed, 0.05, 0.01, 0.5), c(v.inRate, 1.5, 0.0, 10.0),
                    c(v.outRate, 0.15, 0.0, 2.0), counts(v.moteCounts, new int[]{180, 100, 40}), counts(v.hazeCounts, new int[]{30, 18, 8}),
                    c(v.moteSize, 0.04, 0.005, 0.3), c(v.moteAlpha, 0.75, 0.0, 1.0), c(v.hazeSize, 1.4, 0.2, 6.0), c(v.hazeAlpha, 0.12, 0.0, 1.0),
                    c(v.dustFloor, 0.35, 0.0, 1.0), color(v.dustColor, new double[]{0.80, 0.76, 0.66}), color(v.sporeColor, new double[]{0.80, 0.86, 0.48}),
                    color(v.hazeColor, new double[]{0.82, 0.85, 0.82}), c(v.thermalSun, 0.3, 0.02, 1.0),
                    c(v.thermalGain, 0.8, 0.0, 2.0), Math.round(c(v.shaftSkyMin, 12.0, 0.0, 15.0)), c(v.nearFrom, 0.8, 0.0, 8.0), c(v.nearTo, 4.0, 0.1, 16.0),
                    color(v.overgrownHazeColor, new double[]{0.90, 0.94, 1.0}), c(v.overgrownHazeLight, 0.6, 0.0, 1.0));
        }
    }

    private static int[] counts(int[] v, int[] def) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.max(0, Math.min(1024, v != null && v.length > i ? v[i] : def[i]));
        }
        return out;
    }

    private static float[] color(double[] v, double[] def) {
        double[] d = v != null && v.length >= 3 ? v : def;
        return new float[]{c(d[0], def[0], 0.0, 1.0), c(d[1], def[1], 0.0, 1.0), c(d[2], def[2], 0.0, 1.0)};
    }

    private static float c(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }
}
