package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.sky.RainIntensity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 胞子の場(胞子の場と風・第 1 段、2026-10-21〜22)。チャンク 1 つ = 1 升。升ごとに<b>浮遊胞子</b>・<b>林床の低い層</b>・<b>降り積もった胞子</b>。
 * 嵐の間に持ち上がった胞子は<b>上空の塊</b>({@link Parcel})として遠くへ運ばれ、離れた所にまとめて落ちる(飛び地)。
 *
 * <p>升は 32×32 の区画({@link Tile})に詰め、値のある区画だけを持つ(世界の広さではなく汚染の広さに比例)。
 * 1 回の更新({@link #step})の計算量は持っている升の数に比例。net.minecraft を import しない素の Java ——
 * 第 2 段で写しを裏のスレッドで回す前提で、ワールドに触らない(発芽は {@link #newSprouts}、塊の着地は {@link #newLandings} に出すだけ)。
 *
 * <p>1 回の更新の順番: 発生源の放出 → 沈着と死滅(浮遊・低い層 → 降り積もり)→ 降り積もりの減衰と発芽 → 運搬(浮遊 = 拡散 + 風、低い層 = 土地で変わる拡散)
 * → 嵐なら上空へ持ち上げ。運搬は押し出し(隣の升へ割合で配る)なので量が保存される。
 */
public final class SporeField {
    public static final int TB = 5, TS = 1 << TB, TN = TS * TS;
    static final long NOT = Long.MIN_VALUE;
    /** 土地の広がりの倍率の上限(刻みの計算に使う。2026-10-30 に湿った土地 ×3 に合わせて 2.5 → 3)。 */
    static final double MAX_SPREAD = 3.0;

    /** 調整できる数値。すべて仮決め(速さだけはユーザー指定に合わせる)。 */
    public static final class Params {
        /** 1 回の更新の間隔(tick)。 */
        public int stepTicks = 100;
        /** 段階ごとの放出(1 升・1 日あたり、乾いた午後の山で): 繁茂・過繁茂(極相は 2026-10-30 に消した)。 */
        public double emitThriving = 0.5, emitOvergrown = 1.0;
        /** 発芽から繁茂・過繁茂になるまでの日数(繁茂までは放出しない)。 */
        public double daysToThriving = 0.5, daysToOvergrown = 2.0;
        /** 放出の倍率: 雨の間・雷雨・夜。昼は午後(14 時)に 1 まで上がる。 */
        public double releaseRain = 0.2, releaseStorm = 1.0, releaseNight = 0.3;
        /** 風が胞子を巻き上げる分: 放出 × (1 − windLift + windLift × 強さ)(強さは 3 で頭打ち)。 */
        public double windLift = 0.9;
        /** 風の強さ 1 あたりの運ぶ速さ(チャンク/日)。 */
        public double windSpeed = 50;
        /** 拡散(1 日に隣 1 升へ出す割合。1 回の更新ではこれ × 間隔)。 */
        public double diffusionPerDay = 1.9;
        /** 沈着(1 日あたり): 乾いた午後・夜・雨(洗い落とし)・雨上がり・雷雨(高く巻き上がる)。 */
        public double depDay = 0.3, depNight = 1.5, depRain = 10, depAfterRain = 3, depStorm = 1.0;
        /** 雨上がりとみなす長さ(tick)。 */
        public int afterRainTicks = 6000;
        /** 浮遊胞子の死滅・降り積もった胞子の減衰(1 日あたり)。 */
        public double airDecay = 0.7, depDecay = 0.5;
        /** 発芽: 降り積もりのしきい値、湿りがそろったときの 1 日あたりの確率。 */
        public double germThreshold = 0.05, germPerDay = 4;
        /** 2 回目の発芽(1 チャンクに萌芽体 2 体目): しきい値、1 回目から空ける日数、1 升の上限。 */
        public double secondThreshold = 0.5, secondAfterDays = 1;
        public int maxSprouts = 2;
        /** 萌芽体 1 体あたりの放出の上乗せ(1 チャンクの放出 × (1 + これ × 萌芽体の数))。【決定】10-22 追記 3 の (1)「根付いた萌芽体 = その升の放出口」。量は仮決め、見積もり器の既定は 0。 */
        public double sproutEmitBonus = 0;
        /** 本体の奥の更新を間引く(1 = する)。 */
        public int thinInterior = 1;
        /** 奥の印を付け直す間隔(更新の回数)、浮遊と林床を別々の回数に刻む(1 = する)、隣から入った分を 16 ビットで持つ(1 = する)。 */
        public int interiorRemarkSteps = 100, splitLayers = 1, half16 = 1;
        /** これ未満の浮遊胞子は捨てる。 */
        public double eps = 1e-4;
        /** 強風・嵐の間の沈着と死滅の倍率(小さいほど長く浮く)。 */
        public double loftFactor = 0.3;
        /** 林床の低い層: 低い層への放出(浮遊への放出とは別、同じ放出量に対する倍率)、拡散(1 日に隣 1 升へ。土地の倍率が掛かる)、沈着・死滅(1 日あたり)。 */
        public double groundEmit = 4, groundDiffusion = 30, groundDep = 3, groundDecay = 1;
        /** 嵐の上空の層: 浮遊胞子を持ち上げる速さ(1 日あたり)、塊にまとめる間隔(tick)、1 回にまとめる塊の上限、上空の速さの倍率。 */
        public double liftPerDay = 0.5;
        public int parcelEveryTicks = 2000, maxParcelsPerBatch = 2;
        /** 上空の速さ: 卓越風 × highSpeedMul(上空の風は地上が凪でも強さ highMinStrength より弱まらない)。 */
        public double highSpeedMul = 1.5, highMinStrength = 1.0;
        /** 塊が落ちるまでの距離(本体の外へ出てからのチャンク、対数で一様): 最小・最大。途中で 1 チャンクごとに落とす割合。落ちる円の半径(チャンク)。最長の滞空(日)。落ちる前に半径 dropMin の中に汚染があれば、dropMin の 1/4 ずつ先へ延ばす(近い飛び地を出さない)。 */
        public double dropMin = 100, dropMax = 800, pathLoss = 0.002, dumpRadius = 2, maxAloftDays = 8;
        /**
         * 谷風・山風(2026-10-30、作り直し側の valley を写した): 昼(日の出〜日の入り)は浮遊が<b>いちばん高い隣</b>へ、夜は<b>いちばん低い隣</b>へ
         * 移る速さ(1 日あたり。作り直し側の半日に 0.15 が移る = 1 − e^(−k/2) = 0.15 から k = −2 ln 0.85 ≈ 0.325)。
         */
        public double valleyUpPerDay = 0.325, valleyDownPerDay = 0.325;
        /** 逆転層の高さ(ブロック)と日ごとのゆれ(± ブロック)。これ以上の高さの升へは地上の浮遊が入らない(上空の塊の落ちは入る)。 */
        public double inversionY = 200, inversionJitter = 10;
        /** 山(地表の高さ ≥ mountainY): 昼だけ浮遊の沈着が mountTrap 倍(地上の胞子の壁。夜は効かない)。 */
        public double mountainY = 120, mountTrap = 2.5;
        /**
         * 息をする地割れ(2026-10-30、作り直し側の fissure を写した): 昼に浮遊へ吐く量(1 日あたり。日の出〜日の入りに sin で配る)、
         * 夜(tod 13000〜23000)に半径 inhaleRadius チャンクの浮遊を吸い込む速さ(1 日あたり)。
         */
        public double exhalePerDay = 50, inhalePerDay = 2.2, inhaleRadius = 6;
        /**
         * 土地の倍率(2026-10-30、作り直し側の sprout の幅): 湿った土地・川沿い・乾いた土地・高い山(地表 ≥ mountainY)。発芽のしきい値と
         * 林床の広がりの両方に掛かる。湿った・乾いたはバイオームの降水量 ≥ wetDownfall / ≤ dryDownfall。高さは heightStride チャンクおきに引いて補間。
         */
        public double wetMul = 3, riverMul = 3, dryMul = 0.15, mountMul = 0.08, wetDownfall = 0.85, dryDownfall = 0.2;
        public int heightStride = 8;
        /**
         * 地下の層(2026-11-04 暴露量ノート 追記 6【決定】、数はすべて仮決め): 地上の浮遊が洞窟の口の多さ(0〜1)に応じて染み込む速さ(1 日あたり、
         * 口 1 のとき)、地下の浮遊の死滅と地下の降り積もりへの沈着(地上の 1/4: airDecay / 4、昼夜の沈着の平均 / 4)、
         * 地下の降り積もりの減衰(depDecay / 4)、測ったことの無いチャンクの口の多さ。隣の地下へは伝えない。
         */
        public double soakPerDay = 4.0, underDecayPerDay = 0.175, underDepPerDay = 0.225, underDepDecayPerDay = 0.125, mouthDefault = 0.05;
        /** 逆転層より上でも発芽・飛び地の着地を許す、生きている巨大樹の根元からの水平の距離(チャンク)。 */
        public double treeExemptChunks = 4;
        /**
         * ネザーの寒暖差の風(2026-11-11。{@link #thermal} があるときだけ効く。オーバーワールドは 0 のまま): 浮遊が<b>冷たい升から熱い隣の升へ</b>
         * 1 日に移る割合 = 温度の差 × thermalWindPerDay(隣 1 つあたり thermalWindCap で頭打ち)。
         */
        public double thermalWindPerDay = 0, thermalWindCap = 0;
        /** ネザーの天井への持ち上げ: 温度が thermalLiftFrom を超えた分 × thermalLiftPerDay の速さで浮遊を上空の塊へ(嵐の持ち上げと同じ口)。 */
        public double thermalLiftPerDay = 0, thermalLiftFrom = 1;
        /** 1 = 上空の塊の向きを風に依らずばらばら(ネザーは風 0。塊ごとに 0〜360°)。 */
        public int parcelRandomDir = 0;
        /** 1 = 放出を夜に多く(夜 releaseNightHigh・昼 releaseDayLow。ネザーの胞子嚢)。満月の夜は fullMoonMul 倍。 */
        public int nightRelease = 0;
        public double releaseNightHigh = 1.0, releaseDayLow = 0.3, fullMoonMul = 1.0;
        /** 満月の夜(nightRelease のとき)の発芽のしきい値の倍率(小さいほど発芽しやすい = 前線の波。1 = 変えない)。 */
        public double fullMoonGermMul = 1.0;
        /**
         * 繁茂の前(daysToThriving まで)の放出(2026-11-12 ネザー: 苔は胞子で増えるので、覆いの広がりに合わせて少し出す)。
         * emitThriving × earlyEmit × (経過 ÷ daysToThriving)²(覆いは起点から広がるので面積で 2 乗)。0 = 出さない(オーバーワールド)。
         */
        public double earlyEmit = 0;
        /**
         * 管の樹(2026-11-12 ネザー段階 2): 繁茂の後(daysToThriving から)の台帳のチャンクの放出のうち、下地として残す割合(1 = 前と同じ)。
         * 残りの出どころは管の樹で、満月の夜だけ {@link Thermal#tubeFactor}(0〜1 = 管の樹の多さ)× tubeMoonEmit を足す。
         */
        public double ledgerBaseEmit = 1.0, tubeMoonEmit = 0;

        /** 同じ値の写し(ネザーの場がオーバーワールドの値を共有しないように)。 */
        public Params copy() {
            Params c = new Params();
            for (var f : Params.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.set(c, f.get(this));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            return c;
        }

        public void set(String k, double v) {
            try {
                var f = Params.class.getField(k);
                if (f.getType() == int.class) f.setInt(this, (int) v);
                else f.setDouble(this, v);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("知らない数値: " + k);
            }
        }
    }

    static final class Tile {
        final int tx, tz;
        final float[] air = new float[TN], next = new float[TN], dep = new float[TN];
        /** 林床の低い層(風に乗らず四方へ染み出すだけ)。 */
        final float[] low = new float[TN], lowNext = new float[TN];
        /** 直前の運搬で隣から入ってきた浮遊・低い層(自分の放出を除く。汚染済みの升の 2 回目の発芽に使う)。 */
        // 読む側は 16 ビット(半精度。【決定】10-23 追記 8 の軽量化 2)、貯める側は float
        final short[] inAir = new short[TN], inLow = new short[TN];
        final float[] inAirNext = new float[TN], inLowNext = new float[TN];
        /** 16 ビットにしないとき(half16 = 0)の読む側。 */
        float[] inAirF, inLowF;
        /** 発芽しない升(水など)の数。 */
        int never;
        /** 本体の奥(自分も周りの 8 区画も全部汚染済み)。林床の層を計算せず、浮遊は 1 回の更新で 1 回だけ進める(軽量化 3)。 */
        boolean interior;
        /** 1 回目に発芽した時刻(tick)。NOT = 汚染されていない。 */
        final long[] sprout = new long[TN];
        /** 発芽した回数(萌芽体の数。上限 {@link Params#maxSprouts})。 */
        final byte[] count = new byte[TN];
        /** 印(bit1 = 読み込まれるまで発芽を待つ {@link #WAIT}。bit0 は 2026-10-30 まで極相の印で、今は読まない)。 */
        final byte[] flags = new byte[TN];
        int corrupted;
        float[] germ; // 土地の発芽しやすさ({@link Ground})。null = どこも 1
        float[] spread; // 土地の林床の広がりやすさ。null = どこも 1
        boolean[] wet; // 水辺(昼でも湿っている)
        float[] airEmit; // 土地の空気への放出の倍率({@link Ground#airEmitFactor}。水の下の株は小さい)。null = どこも 1
        /** 地表の高さ(ブロック。2026-10-30 谷風・逆転層・山の壁)。null = 高さを持たない(平らな合成の世界)。保存する。 */
        float[] height;
        /**
         * 地下の層(2026-11-04): 地下の浮遊・地下の降り積もり・洞窟の口の多さ(0〜1。NaN = まだ測っていない → {@link Params#mouthDefault})・
         * 洞窟の床から発芽した回数(上限 {@link Params#maxSprouts})。どれも保存する(区画ファイルの版 3)。
         */
        final float[] under = new float[TN], underDep = new float[TN], mouth = new float[TN];
        final byte[] caveCount = new byte[TN];
        /** 嵐の間に持ち上げた量と、その重心を求める和(塊にまとめるまで)。 */
        double lifted, liftX, liftZ;
        /** 温度(ネザーの寒暖差の風、2026-11-11。{@link #thermal} が無ければ null)。{@link #step} の頭で引き直してにじませる。保存しない。 */
        float[] temp;
        /** 放出の倍率(ネザーの湖の一押し、2026-11-11。{@link Thermal#emitFactor}。null = どこも 1)。温度と一緒に引き直す。 */
        float[] emitMul;
        /** 管の樹の多さ({@link Thermal#tubeFactor}。null = どこも 0)。温度と一緒に引き直す。 */
        float[] tube;

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
            java.util.Arrays.fill(sprout, NOT);
            java.util.Arrays.fill(mouth, Float.NaN);
        }
    }

    /** 上空の塊。 */
    public static final class Parcel {
        public double x, z, mass, traveled, dropAt;
        /** 本体の外を飛んだ時間(tick)。滞空の上限はこれで数える。 */
        public long outsideTicks;
        /** 道筋のうち汚染からいちばん遠かった点と、その距離(区画単位の見積もり。滞空の上限で落ちるときに使う)。 */
        public double bestX, bestZ, bestD = -1;
        public final long born;
        public final double turn; // 風からのずれ(ラジアン)

        Parcel(double x, double z, double mass, double dropAt, long born, double turn) {
            this.x = x;
            this.z = z;
            this.mass = mass;
            this.dropAt = dropAt;
            this.born = born;
            this.turn = turn;
        }
    }

    /**
     * 1 回の更新の天気と時刻(升に依らない分)。tick = 汚染の暦(PhaseData#clock。発芽からの日数・追いつきに使う)、
     * dayTime = その時点の一日の時刻のもと(Level#getDayTime。昼夜の放出・沈着に使う)。見積もり器では同じ値。
     */
    public record Conditions(long tick, long dayTime, boolean raining, boolean thundering, boolean afterRain, float rainIntensity, float afterIntensity) {
        public Conditions(long tick, long dayTime, boolean raining, boolean thundering, boolean afterRain) {
            this(tick, dayTime, raining, thundering, afterRain, RainIntensity.NORMAL, RainIntensity.NORMAL);
        }

        public Conditions(long tick, boolean raining, boolean thundering, boolean afterRain) {
            this(tick, tick, raining, thundering, afterRain);
        }

        /** 雷の無い雨の強さを「普通 = 1」に直した値(11-12 雨の種類。小雨 0.5・大雨 1.5)。雨でなければ 0。 */
        double rainK() {
            return raining && !thundering ? Math.max(0.0, Math.min(RAIN_K_MAX, RainIntensity.normalized(rainIntensity))) : 0.0;
        }

        /** 雨の後の湿り気の強さ(止んだ雨の強さ、普通 = 1)。雨の後でなければ 0。 */
        double afterK() {
            return afterRain ? Math.max(0.0, Math.min(RAIN_K_MAX, RainIntensity.normalized(afterIntensity))) : 0.0;
        }
    }

    /*
     * 雨の種類と胞子(2026-11-12、ユーザー決定。正規化して「普通の雨 = 今までの数値」。数値は仮決め):
     *  - 洗い落とし: 雨の強さで沈着を晴れの値から depRain まで(普通で depRain、大雨は越える)、放出を晴れの値から releaseRain まで。
     *    地点の濃度の rainFactor も強さで(SporeConcentration)。雷雨は別扱い(巻き上げる。下)。
     *  - 湿った地面: 雨の間・雨の後の発芽の確率を強さで(普通 1 倍)。雨の後の湿りの長さも止んだ雨の強さで(SporeFieldServer)。
     *  - 風で飛びにくい: 雷の無い雨の間は運ぶ速さを {@link #RAIN_DRAG} だけ落とす(普通の雨で 1 − RAIN_DRAG)。
     *  - 雷雨(ユーザー「雷によって胞子の生産と拡散能力が一時的に向上し、雲よりも上の風に乗って遠くへ行く」): 放出 × THUNDER_RELEASE、
     *    上空への持ち上げ × THUNDER_LIFT。
     */
    static final double RAIN_K_MAX = 1.6;
    static final double RAIN_DRAG = 0.3;
    static final double THUNDER_RELEASE = 1.5;
    static final double THUNDER_LIFT = 1.5;

    /**
     * 升ごとの土地。発芽しやすさ(降り積もり × これ ≥ しきい値で発芽。0 = 発芽しない水面・岩、1 = ふつう)・
     * 林床の広がりやすさ(乾いた所 0.3 〜 湿地・川沿い 2、高い所は遅い。上限 {@link #MAX_SPREAD})・水辺(昼でも湿っている)。
     * ゲームではバイオーム・水面・高さから升ごとに 1 回求めて覚える(第 2 段)。
     */
    public interface Ground {
        float germFactor(int cx, int cz);

        boolean wet(int cx, int cz);

        default float spreadFactor(int cx, int cz) {
            return 1;
        }

        /** 空気への放出の倍率(海の升は水の下の株なので小さい。2026-10-24 追記 18、仮 0.3)。林床への放出は変えない。 */
        default float airEmitFactor(int cx, int cz) {
            return 1;
        }

        /** 地表の高さ(ブロック)。NaN = 高さを持たない(谷風・逆転層・山の壁が効かない)。区画を作るときに 1 回だけ呼ぶ。 */
        default float height(int cx, int cz) {
            return Float.NaN;
        }

        /** 土地の倍率(湿った・川沿い・乾いた・山。発芽しやすさと林床の広がりやすさに掛ける)。高さは {@link #height} の値。 */
        default float terrainMul(int cx, int cz, float height) {
            return 1;
        }
    }

    /**
     * 升ごとの温度(ネザーの寒暖差の風・天井への持ち上げ、2026-11-11)。0 = 冷たい(森の奥)〜 1 = 熱い(溶岩の海)。
     * 裏のスレッドから呼ぶので、ワールドではなく写し(台帳・溶岩の割合)だけを読むこと。
     */
    public interface Thermal {
        float temperature(int cx, int cz);

        /** 放出の倍率(ネザー: 湖のチャンクは多く出す = 湖が風の元。2026-11-11)。 */
        default float emitFactor(int cx, int cz) {
            return 1f;
        }

        /** 管の樹の多さ(0〜1。2026-11-12)。満月の夜に羽根から吐く量の倍率({@link Params#tubeMoonEmit})。 */
        default float tubeFactor(int cx, int cz) {
            return 0f;
        }
    }

    /** null = 温度なし(オーバーワールド)。 */
    public Thermal thermal;

    /** null = どこもふつう・乾き(平らな合成の世界)。区画を作る前に入れる。 */
    public Ground ground;
    public final long seed;
    public final Params p;
    final HashMap<Long, Tile> tiles = new HashMap<>();
    /**
     * 区画を引くための表(2026-11-15 軽量化 E)。{@link #tiles} は鍵の hash が tx ^ tz に偏って遅い(計算の 16%)が、舐める順番が場の答え
     * (足し算の順)を決めているので替えず、引くのだけこちらで。{@link #tiles} と必ず一緒に足し引きする。
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Tile> index = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** 上空の塊。 */
    public final List<Parcel> parcels = new ArrayList<>();
    /** 直前の {@link #step} で発芽した升 {cx, cz, 飛び地なら 1, 何回目か}。 */
    public final List<int[]> newSprouts = new ArrayList<>();
    /** 直前の {@link #step} で着地した塊 {x, z, 量, 飛んだ距離}。 */
    public final List<double[]> newLandings = new ArrayList<>();
    public long corruptedCount, sproutEvents, launched;
    /** 計測(2026-11-14 胞子の場の重さ): 作った区画の数と時間・捨てた区画の数・1 刻みの本体の時間・塊の時間。 */
    public long devNewTiles, devNewTileNanos, devRemoved, devOnceNanos, devParcelNanos;
    /** 計測用の切り替え: 空の区画を捨てない(縁の区画の作り直しを止めて、計算そのものの重さを見る)。 */
    public static volatile boolean devKeepEmptyTiles;
    /** 計測用の切り替え: 捨てた区画を控えに置く(軽量化 A。既定は入り)。false で前の動き(毎回作り直す)。 */
    public static volatile boolean devDormant = !Boolean.getBoolean("ov.spore.noDormant");
    /** 計測: 控えから戻した区画の数。 */
    public long devRevived;

    public SporeField(long seed, Params p) {
        this.seed = seed;
        this.p = p;
    }

    static long key(int tx, int tz) {
        return (tx & 0xFFFFFFFFL) | ((long) tz << 32);
    }

    Tile tile(int cx, int cz, boolean create) {
        int tx = cx >> TB, tz = cz >> TB;
        long k = key(tx, tz);
        Tile t = index.get(k);
        if (t == null && create) {
            // 捨てた空の区画が控えにあれば戻す(2026-11-15 軽量化 A。土地の表を引き直さない。中身は捨てたときに空にしてある)
            t = dormant.remove(k);
            if (t == null) t = newTile(tx, tz);
            else {
                devRevived++;
                if (thermal != null) fillTemp(t);
            }
            tiles.put(k, t);
            index.put(k, t);
        }
        return t;
    }

    /**
     * 捨てた空の区画の控え(2026-11-15 軽量化 A)。縁の区画は「隣からしきい値未満が入る → 作る → 空なので捨てる」を刻みごとに繰り返し、
     * 作るたびに 1,024 升の土地(高さ・バイオーム)を引き直していた(本物の 49 万チャンクで計算の約 55%)。
     * 捨てるときに中身を空にしてここへ置き、次に要るときに戻す。場の答えは捨てて作り直したときと同じ
     * (違いは、読み込まれたチャンクから入れた本物の高さ・洞窟の口の値が残ること)。古い順に {@link #DORMANT_MAX} 個まで。保存はしない。
     */
    private final java.util.LinkedHashMap<Long, Tile> dormant = new java.util.LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Tile> eldest) {
            return size() > DORMANT_MAX;
        }
    };
    /** 控えの上限(1 個 約 74 KB。1024 個で約 75 MB)。 */
    static final int DORMANT_MAX = 1024;

    /** 空の区画を控えへ回す前に、動く値を全部 0 に戻す(新しく作った区画と同じ中身にする)。土地の表・高さ・洞窟の口は残す。 */
    private static void clearForDormant(Tile t) {
        java.util.Arrays.fill(t.air, 0f);
        java.util.Arrays.fill(t.next, 0f);
        java.util.Arrays.fill(t.dep, 0f);
        java.util.Arrays.fill(t.low, 0f);
        java.util.Arrays.fill(t.lowNext, 0f);
        java.util.Arrays.fill(t.inAir, (short) 0);
        java.util.Arrays.fill(t.inLow, (short) 0);
        java.util.Arrays.fill(t.inAirNext, 0f);
        java.util.Arrays.fill(t.inLowNext, 0f);
        if (t.inAirF != null) java.util.Arrays.fill(t.inAirF, 0f);
        if (t.inLowF != null) java.util.Arrays.fill(t.inLowF, 0f);
        java.util.Arrays.fill(t.under, 0f);
        java.util.Arrays.fill(t.underDep, 0f);
        java.util.Arrays.fill(t.flags, (byte) 0);
        java.util.Arrays.fill(t.count, (byte) 0);
        java.util.Arrays.fill(t.caveCount, (byte) 0);
        java.util.Arrays.fill(t.sprout, NOT);
        t.corrupted = 0;
        t.lifted = t.liftX = t.liftZ = 0;
        t.interior = false;
    }

    static int idx(int cx, int cz) {
        return ((cz & (TS - 1)) << TB) | (cx & (TS - 1));
    }

    /** 汚染された升として置く(初めの帯・ゲームの台帳から)。 */
    public void setCorrupted(int cx, int cz, long since) {
        Tile t = tile(cx, cz, true);
        int i = idx(cx, cz);
        if (t.sprout[i] == NOT) {
            t.corrupted++;
            corruptedCount++;
            t.count[i] = 1;
        }
        t.sprout[i] = since;
        t.dep[i] = 0;
    }

    public boolean isCorrupted(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t != null && t.sprout[idx(cx, cz)] != NOT;
    }

    public long sproutTick(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? NOT : t.sprout[idx(cx, cz)];
    }

    public int sproutCount(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.count[idx(cx, cz)];
    }

    public double air(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.air[idx(cx, cz)];
    }

    public double deposited(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.dep[idx(cx, cz)];
    }

    /** 点の放出(胞子嚢の破裂・G4 の死亡球など)。 */
    public void emitPoint(int cx, int cz, double amount) {
        tile(cx, cz, true).air[idx(cx, cz)] += (float) amount;
    }

    /**
     * その升の浮遊の {@code fraction} を上空の層へ持ち上げる(巨大樹の梢、2026-10-23 D。嵐の持ち上げと同じく、
     * 次の塊の放ち({@link Params#parcelEveryTicks})で塊になって風下へ飛ぶ)。
     */
    public void liftAt(int cx, int cz, double fraction) {
        Tile t = tile(cx, cz, false);
        if (t == null || fraction <= 0) return;
        int i = idx(cx, cz);
        double a = t.air[i] * Math.min(1.0D, fraction);
        if (a <= 0) return;
        t.air[i] -= (float) a;
        t.lifted += a;
        t.liftX += a * (cx + 0.5);
        t.liftZ += a * (cz + 0.5);
    }

    /** 直接の降り積もり(上空の塊の着地)。 */
    public void depositPoint(int cx, int cz, double amount) {
        tile(cx, cz, true).dep[idx(cx, cz)] += (float) amount;
    }

    /** シャベルで粉を払った: その升の降り積もりを share の割合だけ減らす(4×4 ブロックの升 1 つ = 1/16。発芽を止める効果は無い)。 */
    public void sweep(int cx, int cz, double share) {
        Tile t = tile(cx, cz, false);
        if (t != null) t.dep[idx(cx, cz)] *= (float) (1.0D - share);
    }

    /** 汚染を取り消す(ゲームが発芽を受け取らなかった升・焼けて台帳から外れた升)。降り積もりも捨てる。 */
    public void unsprout(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        if (t == null) return;
        int i = idx(cx, cz);
        if (t.sprout[i] != NOT) {
            t.sprout[i] = NOT;
            t.corrupted--;
            corruptedCount--;
        }
        t.count[i] = 0;
        t.flags[i] = 0;
        t.dep[i] = 0;
        t.interior = false;
    }

    /**
     * 吸い込み口(除去装置): 中心のチャンク (cx, cz) から半径 {@code radiusChunks}(チャンクの中心どうしの距離)の升の
     * 浮遊と林床から、合わせて最大 {@code cap} を抜く(足りなければある分だけ。升ごとの量に比例して抜く)。抜いた量を返す。
     */
    public double extractAround(int cx, int cz, double radiusChunks, double cap) {
        if (cap <= 0) return 0;
        int r = (int) Math.ceil(radiusChunks);
        double total = 0;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > radiusChunks * radiusChunks) continue;
                Tile t = tile(cx + dx, cz + dz, false);
                if (t == null) continue;
                int i = idx(cx + dx, cz + dz);
                total += t.air[i] + t.low[i];
            }
        if (total <= 0) return 0;
        double take = Math.min(cap, total);
        float keep = (float) (1 - take / total);
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > radiusChunks * radiusChunks) continue;
                Tile t = tile(cx + dx, cz + dz, false);
                if (t == null) continue;
                int i = idx(cx + dx, cz + dz);
                t.air[i] *= keep;
                t.low[i] *= keep;
            }
        return take;
    }

    /** 半径 {@code radiusChunks} の升の降り積もりを捨てる(稼働中の除去装置の範囲では発芽させない)。 */
    public void clearDepositAround(int cx, int cz, double radiusChunks) {
        int r = (int) Math.ceil(radiusChunks);
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > radiusChunks * radiusChunks) continue;
                Tile t = tile(cx + dx, cz + dz, false);
                if (t != null) t.dep[idx(cx + dx, cz + dz)] = 0;
            }
    }

    /** 萌芽体(根付いた姿)が 1 体壊された: その升の萌芽体の数を 1 減らす(放出が減る。また 2 体目が発芽しうる)。 */
    public void removeOneSprout(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        if (t == null) return;
        int i = idx(cx, cz);
        if (t.count[i] > 0) t.count[i]--;
    }
    /** 林床の値(/ov の表示)。 */
    public double low(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.low[idx(cx, cz)];
    }

    // ---------------------------------------------------------------- 地下の層(2026-11-04 暴露量ノート 追記 6)

    /** 地下の層を回すか(オーバーワールド = true、ネザーの場は false。Params は場どうしで共用なので場ごとに持つ)。 */
    public boolean underground = true;
    /** 生きている巨大樹の根元のチャンク({@link #chunkKey})。逆転層より上でも発芽・着地を許す例外。サーバーが更新ごとに入れる(保存しない)。 */
    public long[] treeChunks = new long[0];
    /** 直前の {@link #step} で洞窟の床から発芽した升 {cx, cz, 何回目か}(チャンクの汚染・台帳は変えない。洞窟の床に芽の点を置くだけ)。 */
    public final List<int[]> newCaveSprouts = new ArrayList<>();

    /** 地下の浮遊。 */
    public double under(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.under[idx(cx, cz)];
    }

    /** 地下の降り積もり。 */
    public double underDeposited(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.underDep[idx(cx, cz)];
    }

    /** 洞窟の床から発芽した回数。 */
    public int caveCount(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? 0 : t.caveCount[idx(cx, cz)];
    }

    /** 洞窟の口の多さ(0〜1。測っていなければ既定値)。 */
    public double mouth(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? p.mouthDefault : mouthOf(t, idx(cx, cz));
    }

    private double mouthOf(Tile t, int i) {
        float m = t.mouth[i];
        return Float.isNaN(m) ? p.mouthDefault : m;
    }

    /** 測った洞窟の口の多さ(0〜1)を入れる。区画が無ければ何もしない(作り直すときは既定値。読み込まれたら測り直す)。 */
    public void setMouth(int cx, int cz, float m) {
        Tile t = tile(cx, cz, false);
        if (t != null) t.mouth[idx(cx, cz)] = Math.max(0f, Math.min(1f, m));
    }

    /** 地下の降り積もりを直に足す(テスト・開発用)。 */
    public void depositUnderPoint(int cx, int cz, double amount) {
        tile(cx, cz, true).underDep[idx(cx, cz)] += (float) amount;
    }

    /** 生きている巨大樹の根元から水平 {@link Params#treeExemptChunks} チャンク以内か。 */
    public boolean nearTree(int cx, int cz) {
        double r2 = p.treeExemptChunks * p.treeExemptChunks;
        for (long k : treeChunks) {
            double dx = cx - (int) k, dz = cz - (int) (k >> 32);
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /** 逆転層より上で、巨大樹の例外でもない升(発芽しない・飛び地が落ちない。2026-11-04)。 */
    private boolean barrenHigh(Tile t, int i, int cx, int cz) {
        return aboveInversion(t, i) && !nearTree(cx, cz);
    }

    /** {@link #barrenHigh} のチャンク座標版(区画が無ければ高さを引いて作る。塊の着地にだけ使う)。 */
    private boolean barrenHighAt(int cx, int cz) {
        Tile t = tile(cx, cz, ground != null);
        return t != null && barrenHigh(t, idx(cx, cz), cx, cz);
    }

    /** 持っている区画(保存用。同じパッケージの保存係だけが中を読む)。 */
    Iterable<Tile> tilesView() {
        return tiles.values();
    }

    /** 読み込んだ区画を入れる(保存係から)。数え直しもする。 */
    void putLoadedTile(Tile t) {
        Tile old = tiles.put(key(t.tx, t.tz), t);
        index.put(key(t.tx, t.tz), t);
        if (old != null) corruptedCount -= old.corrupted;
        corruptedCount += t.corrupted;
    }

    /** 新しい空の区画(保存係が読み込みに使う。土地の表はここで埋める)。 */
    Tile newTile(int tx, int tz) {
        return newTile(tx, tz, null);
    }

    /** {@code savedHeight} = 保存してあった地表の高さ(無ければ null で、土地から引く。2026-10-30 より前のセーブも null)。 */
    Tile newTile(int tx, int tz, float[] savedHeight) {
        long devN0 = System.nanoTime();
        Tile t = new Tile(tx, tz);
        if (thermal != null) fillTemp(t);
        if (ground != null) {
            t.germ = new float[TN];
            t.spread = new float[TN];
            t.wet = new boolean[TN];
            t.airEmit = new float[TN];
            boolean saved = savedHeight != null && savedHeight.length == TN;
            float[] h = saved ? savedHeight.clone() : null;
            for (int i = 0; i < TN; i++) {
                int x = (tx << TB) + (i & (TS - 1)), z = (tz << TB) + (i >> TB);
                float hy = saved ? h[i] : ground.height(x, z);
                if (!Float.isNaN(hy)) {
                    if (h == null) {
                        h = new float[TN];
                        java.util.Arrays.fill(h, Float.NaN);
                    }
                    h[i] = hy;
                }
                fillGround(t, i, x, z, hy, true);
                t.wet[i] = ground.wet(x, z);
                t.airEmit[i] = ground.airEmitFactor(x, z);
            }
            t.height = h;
        }
        devNewTiles++;
        devNewTileNanos += System.nanoTime() - devN0;
        return t;
    }

    private void fillTemp(Tile t) {
        if (t.temp == null) t.temp = new float[TN];
        for (int i = 0; i < TN; i++) {
            t.temp[i] = thermal.temperature((t.tx << TB) + (i & (TS - 1)), (t.tz << TB) + (i >> TB));
            if (t.emitMul == null) t.emitMul = new float[TN];
            t.emitMul[i] = thermal.emitFactor((t.tx << TB) + (i & (TS - 1)), (t.tz << TB) + (i >> TB));
            if (p.tubeMoonEmit > 0) {
                if (t.tube == null) t.tube = new float[TN];
                t.tube[i] = thermal.tubeFactor((t.tx << TB) + (i & (TS - 1)), (t.tz << TB) + (i >> TB));
            }
        }
    }

    /** 温度を引き直し、隣と 1 回にじませる(境目に坂を作る)。区画の外の隣は {@link #thermal} から直に引く。 */
    private void refreshTemps() {
        if (thermal == null) return;
        for (Tile t : tiles.values()) fillTemp(t);
        HashMap<Long, float[]> blurred = new HashMap<>();
        for (Tile t : tiles.values()) {
            float[] out = new float[TN];
            for (int i = 0; i < TN; i++) {
                int cx = (t.tx << TB) + (i & (TS - 1)), cz = (t.tz << TB) + (i >> TB);
                double sum = tempAt(cx + 1, cz) + tempAt(cx - 1, cz) + tempAt(cx, cz + 1) + tempAt(cx, cz - 1);
                out[i] = (float) (0.5 * t.temp[i] + 0.125 * sum);
            }
            blurred.put(key(t.tx, t.tz), out);
        }
        for (Tile t : tiles.values()) t.temp = blurred.get(key(t.tx, t.tz));
    }

    /** 升の温度(区画があればその値、無ければ {@link #thermal} から)。 */
    double tempAt(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        if (t != null && t.temp != null) return t.temp[idx(cx, cz)];
        return thermal == null ? 0 : thermal.temperature(cx, cz);
    }

    /** 升 i の発芽しやすさと林床の広がりやすさ(土地の倍率込み)を埋め直す。発芽しない升の数も合わせる。 */
    private void fillGround(Tile t, int i, int x, int z, float hy, boolean fresh) {
        boolean wasNever = !fresh && t.germ[i] == 0;
        float mul = ground.terrainMul(x, z, hy);
        t.germ[i] = ground.germFactor(x, z) * mul;
        t.spread[i] = (float) Math.min(MAX_SPREAD, ground.spreadFactor(x, z) * mul);
        boolean never = t.germ[i] == 0;
        if (never && !wasNever) t.never++;
        else if (!never && wasNever) t.never--;
    }

    /**
     * 読み込まれたチャンクの本物の地表の高さ(2026-10-30。区画を作るときは生成器の式を {@link Params#heightStride} おきに引いて
     * 補間した値なので、山頂などがずれる)。区画が無ければ何もしない。土地の倍率も高さで変わるので埋め直す。
     */
    public void setExactHeight(int cx, int cz, float y) {
        Tile t = tile(cx, cz, false);
        if (t == null || ground == null) return;
        int i = idx(cx, cz);
        if (t.height == null) {
            t.height = new float[TN];
            java.util.Arrays.fill(t.height, Float.NaN);
        }
        t.height[i] = y;
        fillGround(t, i, cx, cz, y, false);
    }

    /** 地表の高さ(区画が無い・高さを持たないなら NaN)。 */
    public float heightAt(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null || t.height == null ? Float.NaN : t.height[idx(cx, cz)];
    }

    // ---------------------------------------------------------------- 息をする地割れ・逆転層(2026-10-30)

    /** 息をする地割れの升({@link #chunkKey})。サーバーが更新ごとに入れる(保存しない。地割れの中心は EarthquakeData が持つ)。 */
    public long[] breathers = new long[0];
    /** この更新の日の逆転層の高さ(ブロック)。{@link #step} の頭で決める。 */
    double inversionNow = Double.POSITIVE_INFINITY;

    /** その日の逆転層の高さ: inversionY ± inversionJitter(seed と日のハッシュ。小さい種の Random は使わない)。 */
    public double inversionY(long day) {
        double u = PrevailingWind.unit(PrevailingWind.hash(seed ^ 0x1A5E0F17L, day));
        return p.inversionY + p.inversionJitter * (2 * u - 1);
    }

    /** 逆転層より上の升か(地上の浮遊が入らない)。 */
    private boolean aboveInversion(Tile t, int i) {
        return t.height != null && t.height[i] >= inversionNow;
    }

    /**
     * 息をする地割れ(1 回の更新ぶん): 昼(sin > 0)は浮遊へ exhalePerDay × π × sin × dt を吐き(昼の半日で 1 日ぶん)、
     * 夜(tod 13000〜23000)は半径 inhaleRadius の浮遊を exp(−inhalePerDay × dt) に減らす(吸い込んだ分は地下へ消える)。
     */
    private void breathe(long dayTime, double dt) {
        if (breathers.length == 0) return;
        double tod = Math.floorMod(dayTime, PrevailingWind.DAY);
        double s = Math.sin(2 * Math.PI * tod / PrevailingWind.DAY);
        boolean night = tod >= 13000 && tod < 23000;
        double keep = Math.exp(-p.inhalePerDay * dt);
        int r = (int) Math.ceil(p.inhaleRadius);
        double r2 = p.inhaleRadius * p.inhaleRadius;
        for (long k : breathers) {
            int cx = (int) k, cz = (int) (k >> 32);
            if (s > 0 && p.exhalePerDay > 0) emitPoint(cx, cz, p.exhalePerDay * Math.PI * s * dt);
            if (!night || p.inhalePerDay <= 0) continue;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > r2) continue;
                    Tile t = tile(cx + dx, cz + dz, false);
                    if (t != null) t.air[idx(cx + dx, cz + dz)] *= (float) keep;
                }
        }
    }

    /**
     * 地割れの圧の前線(2026-10-24 追記 19): 中心から全方位へ速さ speed(チャンク/日)で広がる輪。輪が通った升の浮遊へ
     * strength / 距離^falloff を 1 回だけ配る(風を無視)。配る量が minAir を割る半径で止まる。前線の円の中は嵐の沈着になる。
     * 前線の後は卓越風と拡散に任せる。保存しない(数分で終わる。サーバーを止めると消える)。
     */
    public static final class RiftFront {
        public final int cx, cz;
        public final double strength, falloff, speed, minAir;
        public double radius;
        public boolean done;
        /** 止める半径(チャンク。2026-10-24 ユーザー指定: 無制限をやめ 12〜16 チャンク)。縁の 3 割でなだらかに 0 へ。 */
        public double maxRadius = Double.MAX_VALUE;

        public RiftFront(int cx, int cz, double strength, double falloff, double speedPerDay, double minAir) {
            this.cx = cx;
            this.cz = cz;
            this.strength = strength;
            this.falloff = falloff;
            this.speed = speedPerDay;
            this.minAir = minAir;
        }

        double at(double r) {
            double v = strength / Math.pow(Math.max(1.0D, r), falloff);
            if (maxRadius < Double.MAX_VALUE) {
                double edge = maxRadius * 0.7D;
                if (r >= maxRadius) return 0.0D;
                if (r > edge) {
                    double u = (r - edge) / (maxRadius - edge);
                    v *= 1.0D - u * u * (3.0D - 2.0D * u);
                }
            }
            return v;
        }
    }

    public final List<RiftFront> fronts = new ArrayList<>();
    /** 直前の {@link #step} の前線の作業量(配った升の数。重さの目安)。 */
    public int frontCells;

    public void addRiftFront(RiftFront rf) {
        fronts.add(rf);
    }

    private void advanceFronts(double dt) {
        frontCells = 0;
        if (fronts.isEmpty()) return;
        for (RiftFront rf : fronts) {
            double r0 = rf.radius, r1 = r0 + rf.speed * dt;
            // 止まる半径(配る量が minAir を割る所)
            double stop = rf.minAir <= 0 ? Double.MAX_VALUE : Math.pow(rf.strength / rf.minAir, 1.0D / rf.falloff);
            stop = Math.min(stop, rf.maxRadius);
            if (r1 >= stop) {
                r1 = stop;
                rf.done = true;
            }
            int ri = (int) Math.ceil(r1);
            double r0s = r0 * r0, r1s = r1 * r1;
            for (int dz = -ri; dz <= ri; dz++) {
                double dz2 = (double) dz * dz;
                if (dz2 > r1s) continue;
                // この行で輪(r0 < 距離 ≤ r1)に入る dx の範囲だけ回す
                int xo = (int) Math.floor(Math.sqrt(r1s - dz2));
                int xi = dz2 >= r0s ? -1 : (int) Math.floor(Math.sqrt(r0s - dz2));
                for (int dx = -xo; dx <= xo; dx++) {
                    if (dx >= -xi && dx <= xi) {
                        dx = xi; // 内側を飛ばす
                        continue;
                    }
                    double d2 = dz2 + (double) dx * dx;
                    if (d2 <= r0s && r0 > 0 || d2 > r1s) continue;
                    double amt = rf.at(Math.sqrt(d2));
                    if (amt < p.eps) continue;
                    Tile tt = tile(rf.cx + dx, rf.cz + dz, true);
                    tt.air[idx(rf.cx + dx, rf.cz + dz)] += (float) amt;
                    frontCells++;
                }
            }
            rf.radius = r1;
        }
        fronts.removeIf(rf -> rf.done);
    }

    private boolean tileInFront(Tile tl) {
        int x0 = tl.tx << TB, z0 = tl.tz << TB;
        for (RiftFront rf : fronts) {
            double nx = Math.max(x0, Math.min(rf.cx, x0 + TS - 1)) - rf.cx, nz = Math.max(z0, Math.min(rf.cz, z0 + TS - 1)) - rf.cz;
            if (nx * nx + nz * nz <= rf.radius * rf.radius) return true;
        }
        return false;
    }

    private boolean inFront(int cx, int cz) {
        for (RiftFront rf : fronts) {
            double dx = cx - rf.cx, dz = cz - rf.cz;
            if (dx * dx + dz * dz <= rf.radius * rf.radius) return true;
        }
        return false;
    }

    /** 印: 読み込まれるまで発芽を待つ升
(ゲームの設定「読み込まれていない範囲への侵食」がオフで台帳が断った。flags の bit1)。 */
    static final byte WAIT = 2;
    /** この更新で初めて発芽した升の、発芽した瞬間の降り積もり(断られたときに戻す)。サーバーが入力を取り込んだ後に空にする。 */
    final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap sproutDep = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
    /** 読み込まれて待ちを解かれ、その場で発芽した升(サーバーが次の答えの発芽に足す)。 */
    public final it.unimi.dsi.fastutil.longs.LongArrayList released = new it.unimi.dsi.fastutil.longs.LongArrayList();

    static long chunkKey(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32); // ChunkPos.asLong と同じ
    }

    /**
     * 読み込まれていないので台帳が発芽を断った(2026-10-24 相談セッションの答え): 発芽を取り消すが降り積もりは発芽の直前の値に戻し、
     * 読み込まれるまで待つ印を付ける(印の間は発芽を試み直さない。降り積もりはその間も増減し、しきい値を割ったら印も外す)。
     */
    public void waitForLoad(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        if (t == null) return;
        int i = idx(cx, cz);
        float before = sproutDep.containsKey(chunkKey(cx, cz)) ? sproutDep.remove(chunkKey(cx, cz)) : (float) p.germThreshold;
        if (t.sprout[i] != NOT) {
            t.sprout[i] = NOT;
            t.corrupted--;
            corruptedCount--;
        }
        t.count[i] = 0;
        t.dep[i] = Math.max(t.dep[i], before);
        t.flags[i] |= WAIT;
    }

    public boolean isWaiting(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t != null && (t.flags[idx(cx, cz)] & WAIT) != 0;
    }

    /** 読み込まれた: 待ちの印を外し、降り積もりがしきい値を越えていればその場で発芽させる({@link #released} に出す)。 */
    public boolean releaseWaiting(int cx, int cz, long now) {
        Tile t = tile(cx, cz, false);
        if (t == null) return false;
        int i = idx(cx, cz);
        if ((t.flags[i] & WAIT) == 0) return false;
        t.flags[i] &= ~WAIT;
        float g = t.germ == null ? 1f : t.germ[i];
        if (t.sprout[i] != NOT || t.dep[i] * g < p.germThreshold) return false;
        t.sprout[i] = now;
        t.corrupted++;
        corruptedCount++;
        t.count[i] = 1;
        t.dep[i] = 0;
        released.add(chunkKey(cx, cz));
        return true;
    }

    /** 待ちの印の付いた升を全部たどる(読み込み直したときの見張りの作り直し)。 */
    public void forEachWaiting(java.util.function.LongConsumer v) {
        for (Tile tl : tiles.values())
            for (int i = 0; i < TN; i++)
                if ((tl.flags[i] & WAIT) != 0) v.accept(chunkKey((tl.tx << TB) + (i & (TS - 1)), (tl.tz << TB) + (i >> TB)));
    }

    public int tileCount() {
        return tiles.size();
    }

    /**
     * 1 回分進める。{@code wind} は卓越風(地形の足し算なし。升ごとの海陸風・山谷風は第 2 段で地形の表を持たせて足す)。
     *
     * <p>林床の層と浮遊の層は、それぞれ安定する最小の回数に刻む(林床は土地の倍率で 3 回ほど、浮遊はふつう 1 回・嵐で 2 回)。
     * 浮遊を進めない刻みでは、浮遊は貯めたまま次の浮遊の刻みで経った時間ぶんまとめて進める。
     * 本体の奥の区画(軽量化 3)は林床を計算しない。隣から入った分の 16 ビットへの変換は 1 回の更新の終わりに 1 回だけ(軽量化 2)。
     */
    public void step(Conditions c, PrevailingWind.Sample wind) {
        newSprouts.clear();
        newLandings.clear();
        newCaveSprouts.clear();
        double full = p.stepTicks / (double) PrevailingWind.DAY; // 日
        advanceFronts(full);
        inversionNow = inversionY(Math.floorDiv(c.dayTime(), (long) PrevailingWind.DAY));
        breathe(c.dayTime(), full);
        refreshTemps(); // ネザーの温度(オーバーワールドは thermal = null で何もしない)
        // 1 回で升の外へ出す割合が 0.5 を超えないように刻む(押し出しの安定と、間隔に依らない答えのため)
        double gmax = ground == null ? 1 : MAX_SPREAD;
        double moveAir = (4 * p.diffusionPerDay + wind.strength * p.windSpeed * (Math.abs(wind.dirX) + Math.abs(wind.dirZ))
                + (thermal != null ? 4 * p.thermalWindCap : 0)) * full;
        double moveLow = (4 * p.groundDiffusion * gmax + (thermal != null ? 4 * p.thermalWindCap : 0)) * full;
        int nAir = Math.max(1, (int) Math.ceil(moveAir / 0.5));
        int nLow = Math.max(1, (int) Math.ceil(moveLow / 0.5));
        if (p.splitLayers == 0) nAir = nLow = Math.max(nAir, nLow);
        int n = Math.max(nAir, nLow);
        boolean thin = p.thinInterior != 0;
        if (thin && (stepCounter++ % p.interiorRemarkSteps == 0)) markInterior();
        if (!thin) for (Tile tl : tiles.values()) tl.interior = false;
        double airDone = 0;
        for (int k = 0; k < n; k++) {
            long t = c.tick() - (long) (p.stepTicks * (n - 1 - k) / (double) n);
            // 浮遊はこの刻みで進めるか(n 回のうち nAir 回、等間隔)。進めるなら前回からの経過ぶん
            boolean doAir = (long) (k + 1) * nAir / n > (long) k * nAir / n;
            double dtAir = 0;
            if (doAir) {
                double upTo = full * (k + 1) / n;
                dtAir = upTo - airDone;
                airDone = upTo;
            }
            boolean doLow = (long) (k + 1) * nLow / n > (long) k * nLow / n;
            stepOnce(c, wind, doAir ? dtAir : 0, doLow ? full / nLow : 0, full / n, t);
        }
        if (p.half16 != 0) {
            for (Tile tl : tiles.values()) {
                for (int i = 0; i < TN; i++) {
                    tl.inAir[i] = Float.floatToFloat16(tl.inAirNext[i]);
                    tl.inLow[i] = Float.floatToFloat16(tl.inLowNext[i]);
                }
                java.util.Arrays.fill(tl.inAirNext, 0f);
                java.util.Arrays.fill(tl.inLowNext, 0f);
            }
        }
        long devP0 = System.nanoTime();
        moveParcels(c, wind, full);
        devParcelNanos += System.nanoTime() - devP0;
    }

    private long stepCounter;

    /** 本体の奥の印を付け直す(自分も周りの 8 区画も、全部の升が「汚染済みか発芽しない升」)。 */
    private void markInterior() {
        for (Tile tl : tiles.values()) {
            boolean in = tl.corrupted + tl.never >= TN;
            for (int dz = -1; in && dz <= 1; dz++)
                for (int dx = -1; in && dx <= 1; dx++) {
                    Tile nb = index.get(key(tl.tx + dx, tl.tz + dz));
                    in = nb != null && nb.corrupted + nb.never >= TN;
                }
            tl.interior = in;
        }
    }

    /**
     * 1 刻み。dtAir = 0 なら浮遊を進めない、dtLow = 0 なら林床を進めない。dtSub は降り積もりの減衰と発芽の確率に使う刻みの長さ。
     */
    /** 昼の度合い 0〜1(時刻 tod の風の昼夜の強さから。午後 2 時に 1、夜と朝 6〜8 時ごろは 0)。 */
    public static double dayness(double tod) {
        double sun = PrevailingWind.diurnalStrength(tod); // 0.15〜1、午後に山
        return (sun - PrevailingWind.P_NIGHT) / (PrevailingWind.P_AFTERNOON - PrevailingWind.P_NIGHT);
    }

    /** 浮遊の沈着の速さ(1 日あたり)。巨大樹の胞子の柱(クライアント)も同じ値で薄める(2026-11-06 に {@link #stepOnce} から出した)。 */
    public static double deposition(Params p, double dayness, boolean raining, boolean thundering, boolean afterRain) {
        return deposition(p, dayness, raining, thundering, afterRain, raining ? 1.0 : 0.0, afterRain ? 1.0 : 0.0);
    }

    /**
     * 雨の強さ入り(11-12 雨の種類)。rainK・afterK = 雨・止んだ雨の強さ(普通 = 1、{@link Conditions#rainK})。普通の雨で今までと同じ値。
     * 雨は晴れの値から depRain へ rainK で寄せる(大雨は越える)。雨の後も止んだ雨の強さで depAfterRain へ。
     */
    public static double deposition(Params p, double dayness, boolean raining, boolean thundering, boolean afterRain, double rainK, double afterK) {
        if (thundering) return p.depStorm;
        double dry = p.depNight + (p.depDay - p.depNight) * dayness;
        if (raining) return Math.max(0.0, dry + (p.depRain - dry) * rainK);
        return afterRain ? Math.max(dry, dry + (p.depAfterRain - dry) * afterK) : dry;
    }

    private void stepOnce(Conditions c, PrevailingWind.Sample wind, double dtAir, double dtLow, double dtSub, long t) {
        boolean doAir = dtAir > 0, doLow = dtLow > 0;
        double tod = Math.floorMod(c.dayTime() - (c.tick() - t), PrevailingWind.DAY);
        double dayness = dayness(tod); // 0〜1、午後に山
        boolean night = tod >= 13000 && tod < 23000;

        // 放出の倍率
        double release;
        double rainK = c.rainK();
        double releaseDry = p.releaseNight + (1 - p.releaseNight) * dayness;
        if (c.thundering()) release = p.releaseStorm * THUNDER_RELEASE;
        else if (c.raining()) release = Math.max(0.02, releaseDry + (p.releaseRain - releaseDry) * rainK);
        else release = releaseDry;
        boolean moonNight = p.nightRelease != 0 && night && fullMoon(c.dayTime() - (c.tick() - t));
        if (p.nightRelease != 0 && !c.thundering() && !c.raining()) {
            // ネザーの胞子嚢は夜に多く出す(夜の光 = 繁殖の光)。満月の夜は一斉放出(2026-11-11 ユーザー決定: ふつうの夜の 3 倍)
            release = night ? p.releaseNightHigh : p.releaseDayLow;
            if (night && fullMoon(c.dayTime() - (c.tick() - t))) release *= p.fullMoonMul;
        }
        double releaseLow = release;
        release *= 1 - p.windLift + p.windLift * Math.min(wind.strength, 3.0);

        // 沈着の速さ
        double dep = deposition(p, dayness, c.raining(), c.thundering(), c.afterRain(), rainK, c.afterK());
        // 強風・嵐の間は胞子が高く長く浮く(沈着も死滅も遅く)
        double decay = p.airDecay;
        if (wind.strongDay || wind.storm) {
            dep *= p.loftFactor;
            decay *= p.loftFactor;
        }
        // 嵐の間は浮遊胞子の一部を上空の層へ持ち上げる(飛び地のもと)
        double lift = c.thundering() ? p.liftPerDay * THUNDER_LIFT : 0;
        double k = dep + decay + lift;
        double keep = Math.exp(-k * dtAir);
        double toDep = (1 - keep) * dep / k;
        double toLift = (1 - keep) * lift / k;
        // 地割れの圧の前線の中(噴出中・前線の通過中)は嵐の沈着(2026-10-24 追記 19)
        double kS = p.depStorm + decay + lift, keepS = Math.exp(-kS * dtAir);
        double toDepS = (1 - keepS) * p.depStorm / kS, toLiftS = (1 - keepS) * lift / kS;
        // 谷風と山の壁(2026-10-30): 昼(日の出〜日の入り)は高い隣へ・夜は低い隣へ。山(高さ ≥ mountainY)は昼だけ沈着 × mountTrap
        boolean daytime = Math.sin(2 * Math.PI * tod / PrevailingWind.DAY) > 0;
        double fValley = 1 - Math.exp(-(daytime ? p.valleyUpPerDay : p.valleyDownPerDay) * dtAir);
        double depM = dep * p.mountTrap, kM = depM + decay + lift, keepM = Math.exp(-kM * dtAir);
        double toDepM = (1 - keepM) * depM / kM, toLiftM = (1 - keepM) * lift / kM;
        boolean wall = daytime && p.mountTrap != 1;
        double depKeep = Math.exp(-p.depDecay * dtSub);
        double kl = p.groundDep + p.groundDecay;
        double keepLow = Math.exp(-kl * dtLow), toDepLow = (1 - keepLow) * p.groundDep / kl;
        double gd = p.groundDiffusion * dtLow;
        boolean moist = c.raining() || c.afterRain() || night;
        // 湿った地面で発芽が速い(11-12): 雨の間・雨の後は強さで(普通 1 倍・小雨 0.6 倍ほど・大雨 1.4 倍ほど)。
        double wetK = c.raining() && !c.thundering() ? rainK : c.afterK();
        double germP = p.germPerDay * dtSub * (wetK > 0 ? Math.pow(wetK, 0.8) : 1.0);
        long secondGap = (long) (p.secondAfterDays * PrevailingWind.DAY);

        // 運搬の割合
        // 雨の間は湿って重く、風で遠くへ運ばれにくい(11-12)。運ぶ速さだけ落とす(雲を流す風 PrevailingWind はそのまま)。
        double v = wind.strength * p.windSpeed * dtAir * (1.0 - RAIN_DRAG * Math.min(rainK, 1.5));
        double ax = wind.dirX * v, az = wind.dirZ * v;
        double fx = Math.abs(ax), fz = Math.abs(az);
        int sx = ax >= 0 ? 1 : -1, sz = az >= 0 ? 1 : -1;
        double d = p.diffusionPerDay * dtAir;
        double stay = 1 - 4 * d - fx - fz;
        float eps = (float) p.eps;
        boolean half = p.half16 != 0;
        double full = p.stepTicks / (double) PrevailingWind.DAY;
        // 地下の層(2026-11-04): 風・雨・昼夜が無いので減りは一定。洞窟の中はいつも湿っている(仮決め)
        boolean under = underground && doAir;
        double kU = p.underDepPerDay + p.underDecayPerDay;
        double keepU = Math.exp(-kU * dtAir), toDepU = kU > 0 ? (1 - keepU) * p.underDepPerDay / kU : 0;
        double underDepKeep = Math.exp(-p.underDepDecayPerDay * dtAir);
        double germPU = p.germPerDay * dtAir;

        long devT0 = System.nanoTime();
        List<Tile> list = new ArrayList<>(tiles.values());
        for (Tile tl : list) {
            int bx = tl.tx << TB, bz = tl.tz << TB;
            boolean deep = tl.interior;
            boolean lowHere = doLow && !deep;
            boolean riftTile = !fronts.isEmpty() && tileInFront(tl);
            for (int i = 0; i < TN; i++) {
                double a = tl.air[i], lo = tl.low[i];
                boolean rz = riftTile && inFront(bx + (i & (TS - 1)), bz + (i >> TB));
                double tDep = rz ? toDepS : toDep, tKeep = rz ? keepS : keep, tLift = rz ? toLiftS : toLift;
                float hy = tl.height == null ? Float.NaN : tl.height[i];
                if (!rz && wall && hy >= p.mountainY) {
                    tDep = toDepM;
                    tKeep = keepM;
                    tLift = toLiftM;
                }
                long sp = tl.sprout[i];
                if (sp != NOT) {
                    double age = (t - sp) / (double) PrevailingWind.DAY;
                    double e = age < p.daysToThriving ? (p.earlyEmit > 0 ? p.emitThriving * p.earlyEmit * (age / p.daysToThriving) * (age / p.daysToThriving) : 0)
                            : age < p.daysToOvergrown ? p.emitThriving : p.emitOvergrown;
                    if (p.sproutEmitBonus > 0) e *= 1 + p.sproutEmitBonus * tl.count[i];
                    if (tl.emitMul != null) e *= tl.emitMul[i];
                    // 管の樹(2026-11-12): 繁茂の後の放出は下地 ledgerBaseEmit を残し、満月の夜は管の樹の羽根から吐く分を足す
                    if (age >= p.daysToThriving && (p.ledgerBaseEmit != 1.0 || p.tubeMoonEmit > 0)) {
                        e *= p.ledgerBaseEmit + (moonNight && tl.tube != null ? p.tubeMoonEmit * tl.tube[i] : 0);
                    }
                    if (doAir) a += e * release * dtAir * (tl.airEmit == null ? 1f : tl.airEmit[i]);
                    if (lowHere) lo += e * releaseLow * dtLow * p.groundEmit;
                }
                // 降り積もりと発芽(汚染済みの升にも貯める。1 升の発芽は maxSprouts 回まで)
                if (tl.count[i] < p.maxSprouts) {
                    boolean first = sp == NOT;
                    // 16 ビットのときは 1 回の更新ぶん貯めた流入なので、この刻みの長さの割合だけ使う(10-23 の二重数えの直し)
                    double inA = half ? Float.float16ToFloat(tl.inAir[i]) * (dtAir / full) : tl.inAirF == null ? 0 : tl.inAirF[i];
                    double inL = half ? Float.float16ToFloat(tl.inLow[i]) * (dtLow / full) : tl.inLowF == null ? 0 : tl.inLowF[i];
                    // 汚染済みの升は自分の放出を数えず、隣から入ってきた分だけを降り積もらせる(【決定】10-22 追記 5)
                    double dd = tl.dep[i] * depKeep
                            + (first ? (doAir ? a * tDep : 0) + (lowHere ? lo * toDepLow : 0)
                            : (doAir ? inA * tDep : 0) + (lowHere ? inL * toDepLow : 0));
                    float g = tl.germ == null ? 1f : tl.germ[i];
                    double need = first ? p.germThreshold : p.secondThreshold;
                    if (moonNight) need *= p.fullMoonGermMul; // ネザー: 満月の夜だけ発芽しやすい(前線の波、2026-11-11)
                    // 読み込まれるまで待つ印(下回ったら外す。印の間は発芽を試み直さない。2026-10-24)
                    if ((tl.flags[i] & WAIT) != 0 && dd * g < p.germThreshold) tl.flags[i] &= ~WAIT;
                    if (g > 0 && (tl.flags[i] & WAIT) == 0 && dd * g >= need && (first || t - sp >= secondGap)
                            && (moist || (tl.wet != null && tl.wet[i]))
                            && !barrenHigh(tl, i, bx + (i & (TS - 1)), bz + (i >> TB)) // 逆転層より上は発芽しない(巨大樹の近くは例外。2026-11-04)
                            && PrevailingWind.unit(PrevailingWind.hash(seed ^ t, ((long) (bx + (i & 31)) << 32) ^ (bz + (i >> TB)))) < germP) {
                        int cx = bx + (i & (TS - 1)), cz = bz + (i >> TB);
                        if (first) {
                            sproutDep.put(chunkKey(cx, cz), (float) dd); // 断られたら戻す(waitForLoad)
                            tl.sprout[i] = t;
                            tl.corrupted++;
                            corruptedCount++;
                        }
                        tl.count[i]++;
                        sproutEvents++;
                        dd = 0;
                        newSprouts.add(new int[]{cx, cz, first && isolated(cx, cz) ? 1 : 0, tl.count[i]});
                    }
                    tl.dep[i] = (float) dd;
                } else {
                    tl.dep[i] = 0;
                }
                int cx = bx + (i & (TS - 1)), cz = bz + (i >> TB);
                if (lowHere) {
                    lo *= keepLow;
                    if (lo >= eps) {
                        // 林床の層: 隣との辺の広がりやすさ = 両側の調和平均(乾いた所が壁になる。量の偏りは出ない)
                        float s0 = tl.spread == null ? 1f : tl.spread[i];
                        double out = 0;
                        out += pushLow(tl, cx + 1, cz, lo, gd, s0);
                        out += pushLow(tl, cx - 1, cz, lo, gd, s0);
                        out += pushLow(tl, cx, cz + 1, lo, gd, s0);
                        out += pushLow(tl, cx, cz - 1, lo, gd, s0);
                        // 寒暖差の風は地面近くの風なので、林床の層も冷たい升から熱い隣へ運ぶ(2026-11-11。浮遊だけでは前線は変わらなかった)
                        if (thermal != null && tl.temp != null && p.thermalWindPerDay > 0) {
                            double tHere = tl.temp[i];
                            for (int dir = 0; dir < 4; dir++) {
                                int nx = cx + (dir == 0 ? 1 : dir == 1 ? -1 : 0), nz = cz + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                                double dT = tempAt(nx, nz) - tHere;
                                if (dT <= 0) continue;
                                double q = lo * Math.min(p.thermalWindCap, dT * p.thermalWindPerDay) * dtLow;
                                Tile nt = (nx >> TB) == tl.tx && (nz >> TB) == tl.tz ? tl : tile(nx, nz, true);
                                nt.lowNext[idx(nx, nz)] += (float) q;
                                nt.inLowNext[idx(nx, nz)] += (float) q;
                                out += q;
                            }
                        }
                        tl.lowNext[i] += (float) (lo - out);
                    }
                }
                if (!doAir) continue;
                if (under) a = stepUnder(tl, i, cx, cz, a, dtAir, keepU, toDepU, underDepKeep, germPU, t);
                double lifted = a * tLift;
                a *= tKeep;
                if (lifted > 0) {
                    tl.lifted += lifted;
                    tl.liftX += lifted * (cx + 0.5);
                    tl.liftZ += lifted * (cz + 0.5);
                }
                if (a < eps) continue;
                if (fValley > 0 && !Float.isNaN(hy)) {
                    // 谷風・山風: いちばん高い(夜は低い)隣へ。逆転層より上の隣へは行かない
                    int bestX = 0, bestZ = 0;
                    float bh = hy;
                    for (int dir = 0; dir < 4; dir++) {
                        int nx = cx + (dir == 0 ? 1 : dir == 1 ? -1 : 0), nz = cz + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                        Tile nt = (nx >> TB) == tl.tx && (nz >> TB) == tl.tz ? tl : tile(nx, nz, true);
                        if (nt.height == null) continue;
                        int ni = idx(nx, nz);
                        float nh = nt.height[ni];
                        if (Float.isNaN(nh) || aboveInversion(nt, ni)) continue;
                        if (daytime ? nh > bh : nh < bh) {
                            bh = nh;
                            bestX = nx - cx;
                            bestZ = nz - cz;
                        }
                    }
                    if (bestX != 0 || bestZ != 0) {
                        double m = a * fValley;
                        a -= m;
                        add(tl, i, cx + bestX, cz + bestZ, (float) m);
                    }
                }
                if (thermal != null && tl.temp != null) {
                    double tHere = tl.temp[i];
                    // 天井への持ち上げ(ネザーの飛び地): 熱い升の浮遊が昇って上空の塊へ
                    double hot = tHere - p.thermalLiftFrom;
                    if (hot > 0 && p.thermalLiftPerDay > 0) {
                        double up = a * (1 - Math.exp(-p.thermalLiftPerDay * hot * dtAir));
                        a -= up;
                        tl.lifted += up;
                        tl.liftX += up * (cx + 0.5);
                        tl.liftZ += up * (cz + 0.5);
                    }
                    // 寒暖差の風: 冷たい升から熱い隣へ(差に比例・隣 1 つあたり thermalWindCap まで)。森の中は温度が揃って凪ぐ
                    if (p.thermalWindPerDay > 0 && a >= eps) {
                        double a0 = a;
                        for (int dir = 0; dir < 4; dir++) {
                            int nx = cx + (dir == 0 ? 1 : dir == 1 ? -1 : 0), nz = cz + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                            double dT = tempAt(nx, nz) - tHere;
                            if (dT <= 0) continue;
                            double m = a0 * Math.min(p.thermalWindCap, dT * p.thermalWindPerDay) * dtAir;
                            a -= m;
                            add(tl, i, nx, nz, (float) m);
                        }
                    }
                }
                tl.next[i] += (float) (a * stay);
                if (d > 0) {
                    float q = (float) (a * d);
                    add(tl, i, cx + 1, cz, q);
                    add(tl, i, cx - 1, cz, q);
                    add(tl, i, cx, cz + 1, q);
                    add(tl, i, cx, cz - 1, q);
                }
                if (fx > 0) add(tl, i, cx + sx, cz, (float) (a * fx));
                if (fz > 0) add(tl, i, cx, cz + sz, (float) (a * fz));
            }
        }
        // 入れ替え(進めた層だけ)と、空の区画の片付け
        List<Long> empty = null;
        for (var en : tiles.entrySet()) {
            Tile tl = en.getValue();
            if (doAir) {
                System.arraycopy(tl.next, 0, tl.air, 0, TN);
                java.util.Arrays.fill(tl.next, 0f);
            }
            if (doLow) {
                if (tl.interior) java.util.Arrays.fill(tl.low, 0f); // 奥へ入ってきた林床の分は吸われたことにする
                else System.arraycopy(tl.lowNext, 0, tl.low, 0, TN);
                java.util.Arrays.fill(tl.lowNext, 0f);
            }
            if (!half) {
                if (tl.inAirF == null) {
                    tl.inAirF = new float[TN];
                    tl.inLowF = new float[TN];
                }
                System.arraycopy(tl.inAirNext, 0, tl.inAirF, 0, TN);
                java.util.Arrays.fill(tl.inAirNext, 0f);
                System.arraycopy(tl.inLowNext, 0, tl.inLowF, 0, TN);
                java.util.Arrays.fill(tl.inLowNext, 0f);
            }
            if (tl.corrupted == 0 && tl.lifted == 0 && isEmpty(tl, eps)) {
                if (empty == null) empty = new ArrayList<>();
                empty.add(en.getKey());
            }
        }
        if (empty != null && !devKeepEmptyTiles) {
            for (long kk : empty) {
                Tile gone = tiles.remove(kk);
                index.remove(kk);
                if (gone != null && devDormant) {
                    clearForDormant(gone);
                    dormant.put(kk, gone);
                }
            }
            devRemoved += empty.size();
        }
        devOnceNanos += System.nanoTime() - devT0;
    }

    /**
     * 地下の層の 1 刻み(2026-11-04 暴露量ノート 追記 6【決定】): 地上の浮遊 {@code a} から 口の多さ × soakPerDay の速さで地下へ染み込ませ(移すので量は保存)、
     * 地下の浮遊を死滅と地下の降り積もりへの沈着で減らし、地下の降り積もりがしきい値を越えたら洞窟の床から発芽させる
     * ({@link #newCaveSprouts} に出すだけ。チャンクの汚染・台帳は変えない)。隣の地下へは伝えない(入り口の無い洞窟には空気では届かない)。
     * 染み込んだ後の地上の浮遊を返す。
     */
    private double stepUnder(Tile tl, int i, int cx, int cz, double a, double dtAir, double keepU, double toDepU, double underDepKeep,
                             double germPU, long t) {
        double mo = mouthOf(tl, i);
        double u = tl.under[i];
        if (mo > 0 && a > 0 && p.soakPerDay > 0) {
            double q = a * (1 - Math.exp(-p.soakPerDay * mo * dtAir));
            a -= q;
            u += q;
        }
        double ud = tl.underDep[i] * underDepKeep + u * toDepU;
        u *= keepU;
        tl.under[i] = u < p.eps * 1e-2 ? 0f : (float) u;
        if (tl.caveCount[i] < p.maxSprouts && ud > 0) {
            float g = tl.germ == null ? 1f : tl.germ[i];
            double need = tl.caveCount[i] == 0 ? p.germThreshold : p.secondThreshold;
            if (g > 0 && ud * g >= need && !barrenHigh(tl, i, cx, cz)
                    && PrevailingWind.unit(PrevailingWind.hash(seed ^ 0xCA7E5EEDL ^ t, ((long) cx << 32) ^ cz)) < germPU) {
                tl.caveCount[i]++;
                ud = 0;
                newCaveSprouts.add(new int[]{cx, cz, tl.caveCount[i]});
            }
        }
        tl.underDep[i] = (float) ud;
        return a;
    }

    private double pushLow(Tile from, int cx, int cz, double lo, double gd, float s0) {
        double f = gd;
        if (from.spread != null) {
            float s1 = spreadAt(from, cx, cz);
            f *= 2.0 * s0 * s1 / Math.max(1e-6, s0 + s1);
        }
        double q = lo * f;
        Tile t = (cx >> TB) == from.tx && (cz >> TB) == from.tz ? from : tile(cx, cz, true);
        t.lowNext[idx(cx, cz)] += (float) q;
        t.inLowNext[idx(cx, cz)] += (float) q;
        return q;
    }

    private float spreadAt(Tile from, int cx, int cz) {
        if ((cx >> TB) == from.tx && (cz >> TB) == from.tz) return from.spread[idx(cx, cz)];
        // 区画が無ければ作る(どのみち直後の pushLow が作る。土地の倍率は高さ込みで区画を作るときに決まる。2026-10-30)
        Tile t = tile(cx, cz, true);
        return t.spread != null ? t.spread[idx(cx, cz)] : 1f;
    }

    /**
     * 上空の塊: 一定の間隔で、区画ごとに持ち上げた量を重い順に {@link Params#maxParcelsPerBatch} 個の塊にまとめて放つ
     * (残りは捨てずに次の塊へ)。塊は卓越風 × {@link Params#highSpeedMul} で飛び、途中で少しずつ落とし、
     * 決めた距離(対数で一様)に着いたら円の中へまとめて降り積もらせる。
     */
    private void moveParcels(Conditions c, PrevailingWind.Sample wind, double dtDays) {
        long t = c.tick();
        if (Math.floorMod(t, p.parcelEveryTicks) < p.stepTicks) {
            List<Tile> src = new ArrayList<>();
            for (Tile tl : tiles.values()) if (tl.lifted > 0) src.add(tl);
            if (!src.isEmpty()) {
                src.sort((x, y) -> Double.compare(y.lifted, x.lifted));
                double total = 0;
                for (Tile tl : src) total += tl.lifted;
                int np = Math.min(p.maxParcelsPerBatch, src.size());
                // 重い区画から np 個。量は全体を np 等分(持ち上げた分はすべて上空へ)
                for (int j = 0; j < np; j++) {
                    Tile tl = src.get(j);
                    long h = PrevailingWind.hash(seed ^ 0xA10F7L, t * 31 + j);
                    double u = PrevailingWind.unit(h);
                    double dropAt = p.dropMin * Math.pow(p.dropMax / p.dropMin, u);
                    double turn = Math.toRadians(30) * (PrevailingWind.unit(PrevailingWind.hash(h, 7)) * 2 - 1);
                    if (p.parcelRandomDir != 0) turn = 2 * Math.PI * PrevailingWind.unit(PrevailingWind.hash(h, 7)); // ネザー: 風が無いので四方へ
                    parcels.add(new Parcel(tl.liftX / tl.lifted, tl.liftZ / tl.lifted, total / np, dropAt, t, turn));
                    launched++;
                }
                for (Tile tl : src) tl.lifted = tl.liftX = tl.liftZ = 0;
            }
        }
        if (parcels.isEmpty()) return;
        double speed = Math.max(p.highMinStrength, wind.strength) * p.windSpeed * p.highSpeedMul * dtDays;
        List<Parcel> landed = null;
        for (Parcel pc : parcels) {
            double ang = p.parcelRandomDir != 0 ? pc.turn : Math.atan2(wind.dirZ, wind.dirX) + pc.turn;
            pc.x += Math.cos(ang) * speed;
            pc.z += Math.sin(ang) * speed;
            // 落ちるまでの距離は本体の外へ出てから数える(飛び地の距離 = 縁からの距離)
            if (!isCorrupted((int) Math.floor(pc.x), (int) Math.floor(pc.z))) {
                pc.traveled += speed;
                pc.outsideTicks += p.stepTicks;
                double dd = tileDistance(pc.x, pc.z, 3 * p.dropMin);
                if (dd > pc.bestD) {
                    pc.bestD = dd;
                    pc.bestX = pc.x;
                    pc.bestZ = pc.z;
                }
            }
            double drop = pc.mass * (1 - Math.exp(-p.pathLoss * speed));
            pc.mass -= drop;
            emitPoint((int) Math.floor(pc.x), (int) Math.floor(pc.z), drop);
            boolean timeUp = pc.outsideTicks > p.maxAloftDays * PrevailingWind.DAY || (t - pc.born) > 4 * p.maxAloftDays * PrevailingWind.DAY;
            if (!timeUp && pc.traveled >= pc.dropAt && corruptedWithin(pc.x, pc.z, p.dropMin)) {
                pc.dropAt += p.dropMin * 0.25; // まだ汚染から dropMin 以内(縁に沿って飛んだ)→ もう少し飛ぶ
                continue;
            }
            if (!timeUp && pc.traveled >= pc.dropAt && barrenHighAt((int) Math.floor(pc.x), (int) Math.floor(pc.z))) {
                pc.dropAt += p.dropMin * 0.25; // 逆転層より上の山には落とさない → もう少し飛ぶ(2026-11-04)
                continue;
            }
            if (timeUp || pc.traveled >= pc.dropAt) {
                if (landed == null) landed = new ArrayList<>();
                landed.add(pc);
            }
        }
        if (landed == null) return;
        int r = (int) Math.ceil(p.dumpRadius);
        for (Parcel pc : landed) {
            parcels.remove(pc);
            // 滞空の上限で落ちる所がまだ汚染に近い(縁に沿って飛び続けた)→ 道筋のうち汚染からいちばん遠かった点が dropMin 以上ならそこへ落とす(【決定】10-23 追記 6)。
            // それも無ければまとめて落とさず、浮遊胞子として薄く散らす(近い飛び地を出さない)
            if (corruptedWithin(pc.x, pc.z, p.dropMin)) {
                if (pc.bestD >= p.dropMin && !corruptedWithin(pc.bestX, pc.bestZ, p.dropMin)) {
                    pc.x = pc.bestX;
                    pc.z = pc.bestZ;
                } else {
                    emitPoint((int) Math.floor(pc.x), (int) Math.floor(pc.z), pc.mass);
                    continue;
                }
            }
            int x0 = (int) Math.floor(pc.x), z0 = (int) Math.floor(pc.z);
            // 逆転層より上の升(巨大樹の近くを除く)には降り積もらせない(2026-11-04)。全部が上なら浮遊として薄く散らす
            int cells = 0;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (dx * dx + dz * dz <= p.dumpRadius * p.dumpRadius && !barrenHighAt(x0 + dx, z0 + dz)) cells++;
            if (cells == 0) {
                emitPoint(x0, z0, pc.mass);
                continue;
            }
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz <= p.dumpRadius * p.dumpRadius && !barrenHighAt(x0 + dx, z0 + dz)) depositPoint(x0 + dx, z0 + dz, pc.mass / cells);
                }
            newLandings.add(new double[]{pc.x, pc.z, pc.mass, pc.traveled});
        }
    }

    /** 満月の日か(バニラの月の満ち欠け: 日 % 8 == 0 が満月)。 */
    public static boolean fullMoon(long dayTime) {
        return Math.floorMod(Math.floorDiv(dayTime, (long) PrevailingWind.DAY), 8L) == 0;
    }

    /** いちばん近い「汚染のある区画」の箱までの距離(実際の距離以下の見積もり。limit で打ち切り)。 */
    double tileDistance(double x, double z, double limit) {
        int R = (int) Math.ceil(limit / TS) + 1;
        int tx0 = (int) Math.floor(x) >> TB, tz0 = (int) Math.floor(z) >> TB;
        double best = limit;
        for (int tz = tz0 - R; tz <= tz0 + R; tz++)
            for (int tx = tx0 - R; tx <= tx0 + R; tx++) {
                Tile tl = index.get(key(tx, tz));
                if (tl == null || tl.corrupted == 0) continue;
                double dx = Math.max(0, Math.max((tx << TB) - x, x - ((tx << TB) + TS)));
                double dz = Math.max(0, Math.max((tz << TB) - z, z - ((tz << TB) + TS)));
                best = Math.min(best, Math.hypot(dx, dz));
            }
        return best;
    }

    /** 半径 r(チャンク)の中に汚染された升があるか(区画単位で先に飛ばす)。 */
    boolean corruptedWithin(double x, double z, double r) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z), ri = (int) Math.ceil(r);
        for (int tz = (z0 - ri) >> TB; tz <= (z0 + ri) >> TB; tz++)
            for (int tx = (x0 - ri) >> TB; tx <= (x0 + ri) >> TB; tx++) {
                Tile tl = index.get(key(tx, tz));
                if (tl == null || tl.corrupted == 0) continue;
                // 2026-11-15 軽量化 I(答えは同じ): 区画のいちばん近い升でも届かなければ飛ばす。全部の升が汚染済みならその升で決まる
                int bx = tx << TB, bz = tz << TB;
                double ndx = Math.max(bx, Math.min(bx + TS - 1, x0)) - x0, ndz = Math.max(bz, Math.min(bz + TS - 1, z0)) - z0;
                if (ndx * ndx + ndz * ndz > r * r) continue;
                if (tl.corrupted == TN) return true;
                for (int i = 0; i < TN; i++) {
                    if (tl.sprout[i] == NOT) continue;
                    double dx = bx + (i & (TS - 1)) - x0, dz = bz + (i >> TB) - z0;
                    if (dx * dx + dz * dz <= r * r) return true;
                }
            }
        return false;
    }

    private boolean isEmpty(Tile tl, float eps) {
        for (int i = 0; i < TN; i++) {
            if (tl.air[i] >= eps || tl.low[i] >= eps || tl.dep[i] >= eps) return false;
            if (tl.under[i] >= eps || tl.underDep[i] >= eps || tl.caveCount[i] != 0) return false; // 地下の層(2026-11-04)
        }
        return true;
    }

    /** 升 {@code fromIdx} の浮遊 q を隣 (cx, cz) へ押し出す。隣が逆転層より上なら入れず、その場(送り元)に降り積もらせる(2026-10-30)。 */
    private void add(Tile from, int fromIdx, int cx, int cz, float q) {
        Tile t = (cx >> TB) == from.tx && (cz >> TB) == from.tz ? from : tile(cx, cz, true);
        int i = idx(cx, cz);
        if (aboveInversion(t, i)) {
            from.dep[fromIdx] += q;
            return;
        }
        t.next[i] += q;
        t.inAirNext[i] += q;
    }

    private boolean isolated(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && isCorrupted(cx + dx, cz + dz)) return false;
            }
        return true;
    }
}
