package com.yayuy.overgrownvegetation.sky;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空の雲の場(2026-10-17。ユーザー指定の「ボクセル調の雲」の土台 —— ideas.voxel_clouds の段取り (1))。
 * クライアントの描画({@code client/VoxelClouds})も、これから作る雲の影・薄明光線・雲の穴・巨大生物の隠れ場所も、この同じ式を引く
 * (サーバーとクライアントで同じ答えになるよう、位置と時刻だけの関数にしてある)。
 *
 * <p>ユーザー指定(10-16): 雲は世界中、高さはバニラより高く建築の上限より上(下の層は y 300〜319 でブロックと重なってよい)。
 * 量は日によって変わり、多い日も雲が 1 つも無い日もある(10-18 ユーザー依頼で変更: 雲の海と積乱雲は時刻・天気で消さない。日の量で 0 になるのは
 * 床の無い並び方(群れ)の下の層の積雲だけ。床のある並び方では切れ目の割合と積乱雲の数だけが変わる)。下の層の薄い雲は y 300〜最大 350(巻雲 y 700 は後の段)。
 *
 * <p>形: 下の層は 2.5 次元の高さの場(柱ごとに底と天辺)。底は y 300 前後でほぼ平ら(積雲の底は平ら)、天辺は濃さで盛り上がる。
 * 濃さ = 大きさの違う 3 つのシンプレックス雑音の和。ある場所に雲があるかは「濃さ > その日の量で決まるしきい値」。しきい値は濃さの分布の
 * 分位点で決めるので、量 0.3 なら空のおよそ 3 割が雲になる。場は風で流れる(10-18 から時刻で向きと強さが変わる世界の風 {@link #windX}。場のずれは {@link #offsetX})。
 *
 * <p>量: 夜中(dayTime 18000)を区切りに「天気の日」を数え、日ごとに決まった乱数で 快晴 0(15%)・少し・ふつう・多め・曇り を選ぶ。
 * 日の替わりは 21 時〜3 時の間にゆっくり移す。雨・雷雨の間は曇りへ寄せる。{@code /time set} や眠りで日が飛ぶと量もすぐ替わる。
 *
 * <p>積乱雲(2026-10-17、段取り (2)。形・数・時刻は仮決め): 場の座標の {@link #STORM_GRID} ブロック四方の升ごとに、日ごとの乱数で 1 つ立つかを決める
 * (確率は呼ぶ側が渡す: 雲の多い日ほど高く、雷雨の間はほぼ必ず)。<b>重なり合う球(塊 {@link Puff})の集まり</b>で、底 300 の平たい台・横へ膨らむ胴
 * (高さの 3〜4 割で一番太い)・まわりのこぶ・カリフラワーの頭(ユーザーの理想 10-17 = 雄大積雲の写真)。一部は育ちきると天辺に金床(風下へずれた平たい円盤)。
 * 雲ごとの一生(10-18 ユーザー「雲海や積乱雲が時間や天候によって消えたりするのをなくせますか。現実の積乱雲も雨や雷雨の日だけでなく、朝や夜にもある」):
 * 升ごとに長さ {@link #LIFE_MIN}〜{@link #LIFE_MAX} の一生(湧く・育ちきったまま長く立つ・崩れる・少し休む)を繰り返し、始まりは升ごとにずらす。
 * どの時刻・どの天気でも、育つ途中・育ちきった・崩れる途中の雲が混ざって立つ(それまでは 9 時に一斉に湧き 13 時に育ちきり 19〜23 時に崩れ、夜と朝は無かった)。
 * 一生ごとに場所と形を引き直す。雷雨の間は数が増え、休みの間の前後を除いて育ちきる。
 * 10-17 ユーザー指定「横に狭すぎる、もっと巨大に」「筒すぎる」「高さ制限をもっと伸ばす」で、最初の柱の形(底 300・天辺 600 の丸屋根、半径 70〜120)から
 * 作り直した(最初の 600 は 10-16 のユーザー指定。今の天辺の値は仮決め)。
 * 天辺は ふつう y 1050〜1200・巨大 y 1400〜1500(縦に長い雄大積雲の写真に寄せて、太さより高く)。
 * まれに巨大な積乱雲(ユーザー指定 10-17「稀に積乱雲の周りに雲海を、とびっきり巨大なもの。空の巨大生物がすっぽり収まるくらい」)。
 * 10-18 にボスの大きさ(ユーザー指定で 3 倍: 全長約 2100・扇の幅約 1400・胴の厚み約 90、体の軸 y 950 前後)に合わせて大きくした(ユーザー「雲の大きさは今回のボスの調整に
 * 合わせて変更しましょう」= 空の巨大生物のセッションからの依頼): 胴の半径 {@link #GIANT_RADIUS_MIN}〜+{@link #GIANT_RADIUS_SPAN}・天辺 y 2600〜3000、
 * 金床は胴の 1.15〜1.6 倍(ふつうの雲は 2〜2.8 倍)。{@link #GIANT_SPACING} ブロックより近い巨大な雲は 1 つだけ残す(5〜6 km に 1 つほど)。
 * 雲の床のある並び方では足元の裾野が最大 450 まで盛り上がる(y 750 ほど。ボスが潜って隠れられる厚さ)。床の無い並び方ではまわり {@link #SEA_RADIUS} に雲海を作り、
 * 近くのほかの積乱雲を消す。それまでは胴の半径 380〜520・天辺 1400〜1500・雲海の半径 1800(ボスが全長 700 のころの大きさ)。
 *
 * <p>ワールドのシードは使わない(クライアントはシードを知らないので、どのワールドでも同じ雲の模様・同じ日の量になる)。
 * 世界ごとに変えたくなったら、ログイン時にシードを混ぜた値を送る。数値はユーザー指定の高さ以外すべて仮決め。
 */
public final class CloudField {
    /** 下の層の底と天辺の上限(ユーザー指定 10-16)。 */
    public static final int LOW_BASE = 300;
    public static final int LOW_TOP = 350;
    /** 積乱雲の天辺の上限(ふつう・巨大)。10-17 ユーザー「もっと伸ばす」で 600 から上げた。巨大は 10-18 にボスに合わせて 1500 → 3000。値は仮決め。 */
    public static final int STORM_TOP = 1200;
    public static final int GIANT_TOP = 3000;
    /** 巨大な積乱雲の胴の半径(育ちきったとき)と、残す間隔(10-18。仮決め)。 */
    public static final float GIANT_RADIUS_MIN = 1300.0F;
    public static final float GIANT_RADIUS_SPAN = 400.0F;
    public static final double GIANT_SPACING = 6000.0;
    /**
     * 積乱雲の並び方(10-17 夜、ユーザー「積乱雲は一つだけでなく複数がそこら中に。空に雲の迷宮のような空間を」の受け取り方の案。どれかをユーザーが選ぶ):
     * 1 = 群れ(中くらいの塔が 800 ブロック四方に 1〜3 本、間は青空)/ 2 = 谷(雲の床から塔が密に立ち、間が谷の通路)/
     * 3 = 壁と橋(2 に加えて、塔が並んでつながった壁と、宙に架かる雲の橋)。
     */
    public static final int STYLE_SWARM = 1;
    public static final int STYLE_VALLEY = 2;
    public static final int STYLE_MAZE = 3;
    /**
     * 入道雲(雄大積雲。2026-10-18 空の世界の順番 4、ユーザー選択「乳房雲・尾流雲・入道雲」。積乱雲は前からあるので、入道雲は「金床も雷も雨も無い、
     * 雲の海から湧き上がる中くらいの塔」と受け取った = 仮の解釈): {@link Storm#kind()} が {@value #KIND_TOWER}。{@link #TOWER_GRID} ブロック四方の升ごとに、
     * 地域の雲の多さ({@link #regionStorms})と積乱雲の確率で立つ。一生は {@link #TOWER_LIFE_MIN}〜{@link #TOWER_LIFE_MAX}(積乱雲の 3 分の 1 ほど)で、
     * 一生の 3 割をかけて湧き上がる(もくもく伸びるのが見える)。数値はすべて仮決め。
     */
    public static final int KIND_TOWER = 2;
    public static final int TOWER_GRID = 640;
    static final double TOWER_LIFE_MIN = 7000.0;
    static final double TOWER_LIFE_MAX = 13000.0;
    static final float TOWER_DENSITY = 0.75F;
    /** 乳房雲(10-18 空の世界の順番 4): 金床のうちこの割合に、底から垂れる丸い袋を付ける。袋の数の上限(塊が多いと雲の中の判定が重い)。仮決め。 */
    static final float MAMMATUS_CHANCE = 0.7F;
    static final int MAMMATUS_MAX = 160;
    /** 塔が上へ行くほど風下へ傾く割合(高さ 1 あたりの横のずれ)。 */
    public static final double TOWER_LEAN = 0.1;
    /** 塊の小ささの下限(これより小さい泡は箱 1〜2 つにしかならず、ざらつくだけ)。 */
    static final float MIN_PUFF = 26.0F;
    /** 積乱雲を立てる升の大きさ(場の座標、ブロック)。仮決め。 */
    public static final int STORM_GRID = 2048;
    /** 巨大な積乱雲のまわりの雲海の半径・天辺(床の無い並び方。10-18 にボス(全長約 2100・胴の厚み約 90)に合わせて 1800・440 から。仮決め)。 */
    public static final double SEA_RADIUS = 4500.0;
    public static final int SEA_TOP = 640;
    /** 谷・壁と橋の並び方の雲の床の天辺。 */
    public static final int FLOOR_TOP = 365;
    /** 積乱雲 1 つの一生の長さ(dayTime の tick。升ごとにこの間で決まる)。仮決め。 */
    static final double LIFE_MIN = 20000.0;
    static final double LIFE_MAX = 36000.0;
    /** 立つかどうかの境のぼかし(1 升に立つ確率がゆっくり変わる夜の間、境の雲はぱっと消えずに縮む)。仮決め。 */
    static final float PRESENCE_SOFT = 0.05F;
    /**
     * 雲の床(谷・壁と橋)の切れ目(10-18。床は時刻で消さず、日の雲の量で切れ目の割合だけ変える。切れ目は大きさ 900 ほどの雑音の分位点で決める)。
     * 10-18 夜 ユーザー「雲海(曇りや雨含む)3 割、晴れ(雲が空を占める割合が 0〜7 割の空)が 7 割に。地面から見たとき雲が多すぎて常に薄暗かった」:
     * 床の残る割合 = その場所の量 ÷ {@link #FLOOR_FULL_AT}。量 {@link #FLOOR_FULL_AT} 以上(曇りの日・雨・雷雨)は一面の雲海(谷の溝だけ)。
     * それまでは快晴の日でも床の 3 割が残り、ふつうの日は 5〜8 割が床だった(切れ目 = 0.7 × (1 − smooth(量 / 0.75)))。値は仮決め。
     */
    public static final float FLOOR_FULL_AT = 0.9F;
    /** 曇り(一面の雲海)の日の割合と、晴れの日の床の割合の上限(10-18 夜のユーザー指定「雲海 3 割・晴れは 0〜7 割」から。曇りの日の割合は仮決め、{@link #dayCoverage})。 */
    static final float OVERCAST_DAYS = 0.18F;
    static final float CLEAR_MAX_COVER = 0.5F;
    /**
     * 晴れの日の小さい雲・中くらいの雲(10-18 夜 ユーザー「大きい雲ばかりあり、すべてが分厚いので少し違和感。晴れの日の空に小さい雲や中くらいの雲も混ぜる」):
     * 雲の床のある並び方でも、晴れの日は床の割合を減らして({@link #floorAmount})薄くし({@link #FLOOR_THIN})、その分を濃さの場 {@link #density}
     * (大きさ 300・120・48 の雑音 = 小さい雲から中くらいの雲まで)の積雲で埋める({@link #cumulusAmount})。積雲の厚さは塊の芯ほど厚く、最大 {@link #CUMULUS_MAX}。
     * 数値は仮決め。
     */
    static final float FLOOR_THIN = 0.45F;
    static final float CUMULUS_MAX = 60.0F;
    static final float CUMULUS_GAIN = 1.1F;
    /** ボクセルの大きさ(横・縦、ブロック)。仮決め。 */
    public static final int CELL_XZ = 8;
    public static final int CELL_Y = 4;
    /**
     * ふだんの向きの風(偏西風。ブロック/tick)。バニラの雲(0.03)より少し速い 1 秒 1 ブロックほど。仮決め。
     * 10-18 から本当の風は {@link #windX}・{@link #windZ}(これに時刻で回る 3 つの風を足す)。この定数は平均の向き(積乱雲の傾き・金床のずれ)に使う。
     */
    public static final double WIND_X = 0.046;
    public static final double WIND_Z = 0.019;
    /**
     * 世界の風(2026-10-18。ユーザー選択「世界の風を日によって向き・強さが変わるようにし、雲もボスも同じ風で流す」= 空の巨大生物のセッションからの依頼):
     * ふだんの向きの風に、回る 3 つの風(大きさ 0.025・0.012・0.010、ひと回り 31000・13700(逆回り)・117000 tick = およそ 1.3 日・0.6 日・4.9 日)を足す。
     * 回る風の和(0.047)が平均(0.05)より小さいので、向きはふだんの向きから最大 70° ほど振れる(逆向きにはならない)、強さは秒速 0.1〜1.9 ブロック
     * (ふだん 0.6〜1.4)。ゲームの時刻(gameTime)と定数だけで決まる純粋な関数(サーバーとクライアントで同じ、/time set や眠りでも跳ばない)。
     * 場のずれ {@link #offsetX} は風の積分を閉じた式で出す(長い時間でも積み上げない)。数値は仮決め。
     */
    private static final double[] GUST_AMP = {0.025, 0.012, 0.010};
    private static final double[] GUST_OMEGA = {Math.PI * 2.0 / 31000.0, -Math.PI * 2.0 / 13700.0, Math.PI * 2.0 / 117000.0};
    private static final double[] GUST_PHASE = {1.3, 4.1, 2.6};

    /** その時刻の風(ブロック/tick)。t = gameTime + partialTick。 */
    public static double windX(double t) {
        double x = WIND_X;
        for (int i = 0; i < GUST_AMP.length; i++) {
            x += GUST_AMP[i] * Math.cos(GUST_OMEGA[i] * t + GUST_PHASE[i]);
        }
        return x;
    }

    public static double windZ(double t) {
        double z = WIND_Z;
        for (int i = 0; i < GUST_AMP.length; i++) {
            z += GUST_AMP[i] * Math.sin(GUST_OMEGA[i] * t + GUST_PHASE[i]);
        }
        return z;
    }

    /** 時刻 0 から t までに風が場を運んだ距離(ブロック。{@link #windX} の積分。場の座標 = 世界の座標 − これ)。 */
    public static double offsetX(double t) {
        double x = WIND_X * t;
        for (int i = 0; i < GUST_AMP.length; i++) {
            x += GUST_AMP[i] / GUST_OMEGA[i] * (Math.sin(GUST_OMEGA[i] * t + GUST_PHASE[i]) - Math.sin(GUST_PHASE[i]));
        }
        return x;
    }

    public static double offsetZ(double t) {
        double z = WIND_Z * t;
        for (int i = 0; i < GUST_AMP.length; i++) {
            z -= GUST_AMP[i] / GUST_OMEGA[i] * (Math.cos(GUST_OMEGA[i] * t + GUST_PHASE[i]) - Math.cos(GUST_PHASE[i]));
        }
        return z;
    }

    private static final long SEED = 0x0C10D5EEDL;
    private static final SimplexNoise BIG;
    private static final SimplexNoise MID;
    private static final SimplexNoise SMALL;
    private static final SimplexNoise PUFF;
    private static final SimplexNoise BASE;
    /** 濃さの分布(小さい順)。量からしきい値を引く。 */
    private static final float[] QUANTILES;
    private static final float[] GAP_QUANTILES;

    static {
        RandomSource random = RandomSource.create(SEED);
        BIG = new SimplexNoise(random);
        MID = new SimplexNoise(random);
        SMALL = new SimplexNoise(random);
        PUFF = new SimplexNoise(random);
        BASE = new SimplexNoise(random);
        int n = 4096;
        float[] q = new float[n];
        RandomSource pick = RandomSource.create(SEED ^ 0x5A5A5A5AL);
        for (int i = 0; i < n; i++) {
            q[i] = density(pick.nextDouble() * 40000.0 - 20000.0, pick.nextDouble() * 40000.0 - 20000.0);
        }
        Arrays.sort(q);
        QUANTILES = q;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            g[i] = gapNoise(pick.nextDouble() * 40000.0 - 20000.0, pick.nextDouble() * 40000.0 - 20000.0);
        }
        Arrays.sort(g);
        GAP_QUANTILES = g;
    }

    private static float gapNoise(double fx, double fz) {
        return (float) BASE.getValue(fx / 900.0 + 113.0, fz / 900.0 - 57.0);
    }

    /**
     * 地域ごとの雲の多さ(2026-10-18。ユーザー「現状はどんな場所でも雲の海があるので、場所によっては雲が少ない晴れている場所があるように」):
     * 大きさ {@link #REGION_SCALE} ほどのゆっくりした雑音(場の座標 = 風と一緒に流れる。晴れた地域も天気の塊として流れてくる)。−1 = 晴れた地域 〜 +1 = 雲の多い地域。
     * 雲の床の切れ目は「日の量 × (1 + {@link #REGION_AMP} × これ)」で決め({@link #regional}。10-18 夜からは掛け算、それまでは足し算)、積乱雲の立つ確率にも掛ける({@link #regionStorms})。
     * だいたい 2〜3 割の広さが晴れた地域(ふつうの日に床がほとんど無い)、2 割が雲の多い地域。最初の幅 0.6 は晴れた地域でも雲が半分残ったので 0.8 に。数値は仮決め。
     */
    public static final double REGION_SCALE = 9000.0;
    public static final float REGION_AMP = 0.8F;

    public static float region(double fx, double fz) {
        double n = 0.7 * MID.getValue(fx / REGION_SCALE + 311.0, fz / REGION_SCALE - 97.0)
                + 0.3 * SMALL.getValue(fx / (REGION_SCALE * 0.45) - 51.0, fz / (REGION_SCALE * 0.45) + 17.0);
        return Mth.clamp((float) n * 1.4F, -1.0F, 1.0F);
    }

    /** 地域で積乱雲の立つ確率に掛ける倍率(晴れた地域 0.15 〜 雲の多い地域 1.35)。 */
    public static float regionStorms(double fx, double fz) {
        return Mth.lerp(smooth((region(fx, fz) + 0.8F) / 1.4F), 0.15F, 1.35F);
    }

    /**
     * 升 (gx, gz) の真ん中の {@link #regionStorms}(2026-10-20)。積乱雲の一覧は毎フレーム千ほどの升で地域の雑音を引き直していた
     * (描画のスレッドの 1 割)。雑音は升の場所だけで決まるので、スレッドごとの小さな表に覚えておく(答えは同じ)。
     */
    static float regionStormsCell(int gx, int gz, int grid) {
        if (Math.abs(gx) >= (1 << 23) || Math.abs(gz) >= (1 << 23) || grid <= 0 || grid >= 0xFFFF) { // 0xFFFF 以上は鍵が目印の -1 とぶつかりうる
            return regionStorms((gx + 0.5) * grid, (gz + 0.5) * grid);
        }
        long key = ((long) (gx & 0xFFFFFF) << 40) | ((long) (gz & 0xFFFFFF) << 16) | grid;
        RegionCache c = REGION_CACHE.get();
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 52); // 上の 12 ビット = 4096 枠
        if (c.keys[i] == key) {
            return c.values[i];
        }
        float v = regionStorms((gx + 0.5) * grid, (gz + 0.5) * grid);
        c.keys[i] = key;
        c.values[i] = v;
        return v;
    }

    private static final ThreadLocal<RegionCache> REGION_CACHE = ThreadLocal.withInitial(RegionCache::new);

    private static final class RegionCache {
        static final int SIZE = 4096;
        final long[] keys = new long[SIZE];
        final float[] values = new float[SIZE];

        RegionCache() {
            java.util.Arrays.fill(keys, -1L); // 升の鍵は下 16 ビットが grid(> 0)なので -1 にはならない
        }
    }

    /** 雲の床の切れ目の割合(量 0 で 1 = 床なし 〜 {@link #FLOOR_FULL_AT} 以上で 0 = 一面の床)。 */
    public static float floorOpen(float coverage) {
        return Mth.clamp(1.0F - coverage / FLOOR_FULL_AT, 0.0F, 1.0F);
    }

    /**
     * その場所の量(10-18 夜から): 日の量に地域の差を掛ける(量 × (1 + {@link #REGION_AMP} × 地域))。掛け算なので快晴の日はどこも晴れ、
     * 広い範囲の平均は日の量のまま(足し算だと、雲の少ない日に雲の多い地域だけが増えて平均が上がった)。晴れの日の上限(量 0.7)から
     * {@link #FLOOR_FULL_AT} の間で地域の差を消し、曇りの日・雨・雷雨は一面の雲海にする。
     */
    public static float regional(float coverage, double fx, double fz) {
        float amp = REGION_AMP * (1.0F - smooth((coverage - 0.7F) / 0.2F));
        return Mth.clamp(coverage * (1.0F + amp * region(fx, fz)), 0.0F, 1.0F);
    }

    /** 雲の床に回す量(10-18 夜): 量の少ない日ほど床を減らす(量 0.1 で 4 割・0.5 で 7 割・{@link #FLOOR_FULL_AT} で全部)。残りは積雲。 */
    static float floorAmount(float coverage) {
        return coverage * Mth.lerp(smooth(coverage / FLOOR_FULL_AT), 0.35F, 1.0F);
    }

    /** 晴れの日の積雲の量(床に回さなかった分。曇りの日へ向かって 0)。 */
    static float cumulusAmount(float coverage) {
        return Math.max(0.0F, coverage - floorAmount(coverage)) * CUMULUS_GAIN * (1.0F - smooth((coverage - 0.6F) / 0.3F));
    }

    /** 晴れの日の積雲の厚さ(0 = 無い)。濃さの場がしきい値を越えた分で、塊の芯ほど厚い。 */
    public static float cumulusTop(double fx, double fz, float coverage) {
        float cc = cumulusAmount(regional(coverage, fx, fz));
        if (cc <= 0.002F) {
            return 0.0F;
        }
        float th = threshold(cc);
        float d = density(fx, fz);
        if (d <= th) {
            return 0.0F;
        }
        // しきい値をわずかに越えただけの所は出さない(箱 1 つのサイコロのような小さすぎる雲になった。10-18 夜のテストクライアント)。
        float h = (d - th) / 0.1F;
        if (h < 0.2F) {
            return 0.0F;
        }
        h = Mth.clamp((h - 0.2F) / 0.8F, 0.0F, 1.0F);
        float puff = (float) PUFF.getValue(fx / 20.0, fz / 20.0);
        return CELL_Y * 2 + (float) Math.pow(h, 0.6) * (CUMULUS_MAX - CELL_Y * 2) + 6.0F * puff * h;
    }

    /** 雲の床の残り方(0 = 切れ目 〜 1 = 床)。地域の雲の多さを日の量に足して決める。 */
    static float floorKeep(double fx, double fz, float coverage) {
        float open = floorOpen(floorAmount(regional(coverage, fx, fz)));
        if (open <= 0.002F) {
            return 1.0F;
        }
        if (open >= 0.998F) {
            return 0.0F;
        }
        float edge = GAP_QUANTILES[Mth.clamp((int) (open * (GAP_QUANTILES.length - 1)), 0, GAP_QUANTILES.length - 1)];
        return smooth((gapNoise(fx, fz) - edge) / 0.12F);
    }

    /**
     * 積乱雲 1 つ(場の座標)。radius = 胴の太さの基準(半径)、top = 育ちきったときの天辺、anvil = 金床を広げる雲か、giant = 巨大な雲(まわりに雲海)、
     * maturity = 育ち(0〜1)、id = 決まった番号、style = 並び方、kind = 0 塔 / 1 橋((x, z) から (x2, z2) へ高さ top に架かる雲)。
     */
    public record Storm(double x, double z, float radius, int top, boolean anvil, boolean giant, float maturity, long id,
                        int style, int kind, double x2, double z2) {
        public Storm(double x, double z, float radius, int top, boolean anvil, boolean giant, float maturity, long id, int style) {
            this(x, z, radius, top, anvil, giant, maturity, id, style, 0, x, z);
        }

        /** 今の天辺の高さ(育ちで伸びる。橋は架かる高さのまま)。 */
        public int topY() {
            return kind == 1 ? top : LOW_BASE + 60 + Math.round(maturity * (top - LOW_BASE - 60));
        }

        /** 入道雲(雄大積雲)か。雷・雨の柱・積乱雲探しの対象にしない。 */
        public boolean congestus() {
            return kind == KIND_TOWER;
        }

        /** 今の胴の太さ(育ちで太る)。 */
        public float bodyRadius() {
            return radius * (0.55F + 0.45F * maturity);
        }

        /** 横の広がりの見積もり(真ん中から。こぶ・金床・風の傾きを含む。橋は端から端まで)。 */
        public double reach() {
            if (kind == 1) {
                return Math.hypot(x2 - x, z2 - z) + radius * 2.0;
            }
            return radius * (anvil && !giant ? 3.4 : 2.9) + (top - LOW_BASE) * 0.11;
        }
    }

    /** 積乱雲の塊 1 つ(球を縦に squash 倍した楕円体。floor より下は切る = 金床の平らな底)。 */
    public record Puff(double x, double y, double z, float r, float squash, double floor) {
        public Puff(double x, double y, double z, float r, float squash) {
            this(x, y, z, r, squash, Double.NEGATIVE_INFINITY);
        }
    }

    /** 金床の形(真ん中 (x, y, z)・半径 radius・平らな底の高さ floor・広がり growth 0〜1)。乳房雲と尾流雲も同じ形を引く。 */
    public record Anvil(double x, double y, double z, float radius, double floor, float growth) {
    }

    /** 柱の答え: 雲が無ければ {@code bottom > top}。高さはブロック(y)。 */
    public record Column(int bottom, int top, float strength) {
        public boolean present() {
            return bottom < top;
        }
    }

    private static final Column NONE = new Column(1, 0, 0.0F);

    /** 場の座標(風で流れた分を引いた座標)。 */
    public static double fieldX(double worldX, long gameTime, float partialTick) {
        return worldX - offsetX((double) gameTime + partialTick);
    }

    public static double fieldZ(double worldZ, long gameTime, float partialTick) {
        return worldZ - offsetZ((double) gameTime + partialTick);
    }

    /** 濃さ(だいたい 0〜1)。場の座標で引く。 */
    public static float density(double fx, double fz) {
        double n = 0.55 * BIG.getValue(fx / 300.0, fz / 300.0)
                + 0.30 * MID.getValue(fx / 120.0, fz / 120.0)
                + 0.15 * SMALL.getValue(fx / 48.0, fz / 48.0);
        return (float) (0.5 + 0.5 * n);
    }

    /**
     * 場の座標の範囲にかかる積乱雲。chance = 1 升に立つ確率、thunder = 雷雨の強さ(0〜1、育ちを押し上げる)、giantChance = 立つ雲のうち巨大になる割合、
     * style = 並び方({@link #STYLE_SWARM} など)。巨大な雲の雲海の中のほかの雲は消す(範囲の外の巨大な雲も数えるため、広めに探してから絞る)。
     */
    public static List<Storm> storms(double minX, double minZ, double maxX, double maxZ, long dayTime, float chance, float thunder, float giantChance,
                                     int style) {
        List<Storm> all = new ArrayList<>();
        if (chance <= 0.0F) {
            return all;
        }
        int grid = style == STYLE_VALLEY ? 1000 : style == STYLE_MAZE ? 760 : 820;
        // 範囲の外の巨大な雲も数える(雲海で消す・近い巨大な雲どうしで 1 つ残す、のどちらも範囲で答えが変わらないよう)。
        double margin = SEA_RADIUS + GIANT_SPACING + 1400.0;
        int gx0 = Mth.floor((minX - margin) / grid);
        int gx1 = Mth.floor((maxX + margin) / grid);
        int gz0 = Mth.floor((minZ - margin) / grid);
        int gz1 = Mth.floor((maxZ + margin) / grid);
        float storm = Mth.clamp(thunder, 0.0F, 1.0F);
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                // 升ごとの一生(長さ・始まりのずれは升で決まる)。一生の番号ごとに場所と形を引き直す。
                long home = (gx * 0x9E3779B97F4A7C15L) ^ (gz * 0xC2B2AE3D27D4EB4FL) ^ (style * 0x2545F4914F6CDD1DL);
                double life = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * hash01(home, 14);
                double phase = (dayTime + life * hash01(home, 15)) / life;
                long epoch = Mth.floor(phase);
                float u = (float) (phase - epoch);
                long cell = home ^ (epoch * 0x165667B19E3779F9L);
                // 地域の雲の多さで確率を変える(晴れた地域はまれ、雲の多い地域は多い。10-18)。升の真ん中の地域で決める。
                float here = chance * regionStormsCell(gx, gz, grid);
                float h = hash01(cell, 1);
                if (h >= here) {
                    continue;
                }
                // 湧く(一生の 1 割)・育ちきったまま立つ・崩れる(0.82〜0.94)・休む。雷雨は休みの前後を除いて育ちきらせる(一生の境で場所が飛ばないよう)。
                float grow = smooth(u / 0.1F) * (1.0F - smooth((u - 0.82F) / 0.12F));
                float alive = smooth((u - 0.02F) / 0.08F) * (1.0F - smooth((u - 0.84F) / 0.08F));
                float maturity = Math.max(grow, storm * alive) * smooth((here - h) / PRESENCE_SOFT);
                if (maturity < 0.12F) {
                    continue;
                }
                if (hash01(cell, 7) < giantChance) {
                    double cx = (gx + 0.3 + 0.4 * hash01(cell, 2)) * grid;
                    double cz = (gz + 0.3 + 0.4 * hash01(cell, 3)) * grid;
                    all.add(new Storm(cx, cz, GIANT_RADIUS_MIN + GIANT_RADIUS_SPAN * hash01(cell, 4), GIANT_TOP - Math.round(400.0F * hash01(cell, 5)),
                            hash01(cell, 8) < 0.6F, true, maturity, cell, style));
                    continue;
                }
                if (style == STYLE_MAZE) {
                    // 壁: 3〜5 本の塔を一列に(隣と重なってつながる)。高さは列の中で揺れる。
                    double a = hash01(cell, 9) * Math.PI;
                    int n = 3 + (int) (hash01(cell, 10) * 3.0F);
                    float base = 120.0F + 70.0F * hash01(cell, 4);
                    double cx = (gx + 0.5) * grid;
                    double cz = (gz + 0.5) * grid;
                    double spacing = base * 0.95;
                    for (int k = 0; k < n; k++) {
                        long id = cell * 31L + k;
                        double d = (k - (n - 1) / 2.0) * spacing;
                        float rr = base * (0.75F + 0.4F * hash01(id, 4));
                        int top = 650 + Math.round(420.0F * hash01(id, 5));
                        all.add(new Storm(cx + Math.cos(a) * d, cz + Math.sin(a) * d, rr, top, false, false, maturity, id, style));
                    }
                    // 橋: 壁の端から、宙に架かる雲の橋を 1〜2 本(先に細い塔)。
                    int bridges = hash01(cell, 11) < 0.5F ? 1 : 2;
                    for (int k = 0; k < bridges; k++) {
                        long id = cell * 31L + 20 + k;
                        double side = k == 0 ? 1.0 : -1.0;
                        double ex = cx + Math.cos(a) * side * (n - 1) / 2.0 * spacing;
                        double ez = cz + Math.sin(a) * side * (n - 1) / 2.0 * spacing;
                        double ba = a + (hash01(id, 2) - 0.5) * 2.2 + (k == 0 ? 0.0 : Math.PI);
                        double len = 260.0 + 160.0 * hash01(id, 3);
                        double bx = ex + Math.cos(ba) * len;
                        double bz = ez + Math.sin(ba) * len;
                        int by = 470 + Math.round(260.0F * hash01(id, 5));
                        all.add(new Storm(ex, ez, 34.0F + 16.0F * hash01(id, 4), by, false, false, maturity, id, style, 1, bx, bz));
                        all.add(new Storm(bx, bz, 70.0F + 25.0F * hash01(id, 6), by + 70, false, false, maturity, id * 7L + 1, style));
                    }
                    continue;
                }
                if (style == STYLE_VALLEY) {
                    valleyCell(all, gx, gz, grid, cell, maturity, style);
                    continue;
                }
                int n = 1 + (int) (hash01(cell, 10) * 3.0F);
                boolean range = false;
                double ra = hash01(cell, 9) * Math.PI;
                for (int k = 0; k < n; k++) {
                    long id = cell * 31L + k;
                    double cx = (gx + 0.1 + 0.8 * hash01(id, 2)) * grid;
                    double cz = (gz + 0.1 + 0.8 * hash01(id, 3)) * grid;
                    if (range) {
                        double d = (k - (n - 1) / 2.0) * grid * 0.22;
                        cx = (gx + 0.5) * grid + Math.cos(ra) * d + (hash01(id, 2) - 0.5) * grid * 0.12;
                        cz = (gz + 0.5) * grid + Math.sin(ra) * d + (hash01(id, 3) - 0.5) * grid * 0.12;
                    }
                    float radius = style == STYLE_VALLEY ? 120.0F + 80.0F * hash01(id, 4) : 150.0F + 100.0F * hash01(id, 4);
                    int top = style == STYLE_VALLEY ? 650 + Math.round(400.0F * hash01(id, 5)) : 700 + Math.round(400.0F * hash01(id, 5));
                    // 金床はたまに(背の高い雲の 1 割ほど)。
                    boolean anvil = top > 1000 && hash01(id, 8) < 0.25F;
                    all.add(new Storm(cx, cz, radius, top, anvil, false, maturity, id, style));
                }
            }
        }
        congestus(all, minX, minZ, maxX, maxZ, dayTime, chance, style);
        // 巨大な雲どうしは雲海 2 つ分より近ければ、決まった順で 1 つだけ残す(どの範囲で探しても同じ答えになるよう、近くだけで決める)。
        List<Storm> giants = new ArrayList<>();
        for (Storm g : all) {
            if (!g.giant()) {
                continue;
            }
            boolean beaten = false;
            for (Storm o : all) {
                if (o != g && o.giant() && Math.hypot(o.x() - g.x(), o.z() - g.z()) < GIANT_SPACING
                        && hash01(o.id(), 10) < hash01(g.id(), 10)) {
                    beaten = true;
                    break;
                }
            }
            if (!beaten) {
                giants.add(g);
            }
        }
        List<Storm> list = new ArrayList<>();
        for (Storm s : all) {
            if (s.giant() && !giants.contains(s)) {
                continue;
            }
            // 雲の海の地形(谷)では巨大な雲も山の 1 つ(まわりの雲を消さない。巨大な雲に挟まれた谷ができる)。
            if (!s.giant() && s.style() < STYLE_VALLEY) {
                boolean drowned = false;
                for (Storm g : giants) {
                    if (Math.hypot(g.x() - s.x(), g.z() - s.z()) < SEA_RADIUS + s.reach()) {
                        drowned = true;
                        break;
                    }
                }
                if (drowned) {
                    continue;
                }
            }
            double r = s.reach();
            if (s.x() + r < minX || s.x() - r > maxX || s.z() + r < minZ || s.z() - r > maxZ) {
                continue;
            }
            list.add(s);
        }
        return list;
    }

    /**
     * 入道雲を立てる({@link #KIND_TOWER})。升ごとの一生は積乱雲と同じ作り(長さと始まりのずれは升で決まり、一生ごとに場所と形を引き直す)。
     * 湧き上がる(一生の 3 割)・立つ・崩れる(0.78〜0.92)・休む。胴の半径 70〜140・天辺 y 650〜1050(雲の床の上へ 100〜700 ほど突き出す)。
     */
    private static void congestus(List<Storm> all, double minX, double minZ, double maxX, double maxZ, long dayTime, float chance, int style) {
        int grid = TOWER_GRID;
        double margin = 500.0;
        int gx0 = Mth.floor((minX - margin) / grid);
        int gx1 = Mth.floor((maxX + margin) / grid);
        int gz0 = Mth.floor((minZ - margin) / grid);
        int gz1 = Mth.floor((maxZ + margin) / grid);
        float base = Mth.clamp(0.1F + chance, 0.0F, 1.0F) * TOWER_DENSITY;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                long home = (gx * 0xD1B54A32D192ED03L) ^ (gz * 0x8CB92BA72F3D8DD7L) ^ (style * 0x2545F4914F6CDD1DL) ^ 0x70E4L;
                double life = TOWER_LIFE_MIN + (TOWER_LIFE_MAX - TOWER_LIFE_MIN) * hash01(home, 14);
                double phase = (dayTime + life * hash01(home, 15)) / life;
                long epoch = Mth.floor(phase);
                float u = (float) (phase - epoch);
                long cell = home ^ (epoch * 0x165667B19E3779F9L);
                float here = base * regionStormsCell(gx, gz, grid);
                float h = hash01(cell, 1);
                if (h >= here) {
                    continue;
                }
                float maturity = smooth(u / 0.3F) * (1.0F - smooth((u - 0.78F) / 0.14F)) * smooth((here - h) / PRESENCE_SOFT);
                if (maturity < 0.12F) {
                    continue;
                }
                double cx = (gx + 0.15 + 0.7 * hash01(cell, 2)) * grid;
                double cz = (gz + 0.15 + 0.7 * hash01(cell, 3)) * grid;
                float radius = 70.0F + 70.0F * hash01(cell, 4);
                int top = 650 + Math.round(400.0F * hash01(cell, 5));
                all.add(new Storm(cx, cz, radius, top, false, false, maturity, cell, style, KIND_TOWER, cx, cz));
            }
        }
    }

    /**
     * 雲の海の地形(谷)の升 1 つの山の置き方(10-17 夜更けのユーザー指摘「積乱雲がまだ小さい」「量をもう少し増やして」「巨大な積乱雲に挟まれた雲の谷」):
     * 1000 ブロック四方の升の 3 割は山脈(3 つを一列)、2 割 5 分は双子の山脈(2 つずつ 2 列を平行に並べ、間を幅 220〜380 の雲の谷に。大きめの山)、
     * 残りは散らばった山 1〜2 つ。山の半径 170〜270(双子は 210〜300)、天辺 y 850〜1300(双子は 1000〜1400)。金床は背の高い山のたまに。巨大な雲は升の確率で別に立つ。
     * 最初(升 820・半径 190〜340・散らばりは 2〜3 つ)は山だらけで雲の海と空が見えなくなった。
     */
    private static void valleyCell(List<Storm> all, int gx, int gz, int grid, long cell, float maturity, int style) {
        float pattern = hash01(cell, 12);
        double cx = (gx + 0.5) * grid;
        double cz = (gz + 0.5) * grid;
        double a = hash01(cell, 9) * Math.PI;
        double ux = Math.cos(a);
        double uz = Math.sin(a);
        if (pattern < 0.3F) {
            for (int k = 0; k < 3; k++) {
                long id = cell * 31L + k;
                double d = (k - 1) * grid * 0.24;
                float radius = 170.0F + 100.0F * hash01(id, 4);
                int top = 850 + Math.round(450.0F * hash01(id, 5));
                all.add(new Storm(cx + ux * d + (hash01(id, 2) - 0.5) * grid * 0.1, cz + uz * d + (hash01(id, 3) - 0.5) * grid * 0.1,
                        radius, top, top > 1150 && hash01(id, 8) < 0.25F, false, maturity, id, style));
            }
        } else if (pattern < 0.55F) {
            float gap = 220.0F + 160.0F * hash01(cell, 13);
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < 2; k++) {
                    long id = cell * 31L + (side > 0 ? 10 : 0) + k;
                    float radius = 210.0F + 90.0F * hash01(id, 4);
                    double off = side * (radius * 1.15 + gap * 0.5);
                    double d = (k - 0.5) * grid * 0.3;
                    int top = 1000 + Math.round(400.0F * hash01(id, 5));
                    all.add(new Storm(cx + ux * d - uz * off, cz + uz * d + ux * off, radius, top, top > 1250 && hash01(id, 8) < 0.2F,
                            false, maturity, id, style));
                }
            }
        } else {
            int n = 1 + (hash01(cell, 10) < 0.5F ? 1 : 0);
            for (int k = 0; k < n; k++) {
                long id = cell * 31L + k;
                float radius = 170.0F + 100.0F * hash01(id, 4);
                int top = 850 + Math.round(450.0F * hash01(id, 5));
                all.add(new Storm((gx + 0.15 + 0.7 * hash01(id, 2)) * grid, (gz + 0.15 + 0.7 * hash01(id, 3)) * grid, radius, top,
                        top > 1150 && hash01(id, 8) < 0.25F, false, maturity, id, style));
            }
        }
    }

    private static final Map<Long, List<Puff>> PUFFS = new ConcurrentHashMap<>();

    /** 積乱雲の塊(id と育ちで決まる。何度も引くので覚えておく)。 */
    public static List<Puff> puffs(Storm s) {
        long key = (s.id() * 31L + Math.round(s.maturity() * 32.0F)) * 7L + s.style() * 3L + s.kind();
        List<Puff> cached = PUFFS.get(key);
        if (cached != null) {
            return cached;
        }
        // 10-18 夜に 128 → 512(外の輪で遠くの積乱雲が増え、作り直しのたびに塊を引き直していた)。
        if (PUFFS.size() > 512) {
            PUFFS.clear();
            PuffIndex.clear();
        }
        List<Puff> list = List.copyOf(buildPuffs(s));
        PUFFS.put(key, list);
        return list;
    }

    /**
     * 塊を並べる(10-17 夜の作り直し。ユーザー「球体感が強すぎる」「上の平らな傘はたまにでよい」): 大きな球を少し並べるのではなく、
     * 縦に長い胴の塊(縦 1.35 倍)の表面に子の泡(0.3〜0.5 倍)、その表面に孫の泡(0.13〜0.22 倍)を付ける入れ子(カリフラワー)。
     * 平たい台(底 300)・胴(高さの 3〜4 割で一番太く、段ごとにずれ、上へ行くほど風下へ傾く)・丸い頭(子と孫の泡が多い)。どの泡も天辺を越えない。
     * 金床は anvil の雲だけ(たまに)。橋(kind 1)は端から端へ宙に架かる泡の列(少したわむ)。
     */
    private static List<Puff> buildPuffs(Storm s) {
        RandomSource r = RandomSource.create(s.id() ^ 0x5EEDC10DL);
        List<Puff> list = new ArrayList<>();
        if (s.kind() == 1) {
            double len = Math.hypot(s.x2() - s.x(), s.z2() - s.z());
            int n = Math.max(2, (int) (len / (s.radius() * 0.8)));
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                double x = Mth.lerp(t, s.x(), s.x2());
                double z = Mth.lerp(t, s.z(), s.z2());
                double y = s.top() - 18.0 * Math.sin(Math.PI * t);
                float rr = s.radius() * (0.85F + 0.3F * r.nextFloat());
                list.add(new Puff(x, y, z, rr, 0.8F));
                bubbles(list, r, x, y, z, rr, 0.8F, 2 + r.nextInt(2), s.top() + s.radius(), 0.35F, 0.55F, 0.6F);
            }
            return list;
        }
        float body = s.bodyRadius();
        int top = s.topY();
        double h = top - LOW_BASE;
        double wl = Math.sqrt(WIND_X * WIND_X + WIND_Z * WIND_Z);
        double dirX = WIND_X / wl;
        double dirZ = WIND_Z / wl;
        double lean = TOWER_LEAN;
        if (s.kind() == KIND_TOWER) {
            // 入道雲: 平たい台・真ん中の塔(傾きは積乱雲の 6 割)・脇の低い塔 1〜2 本(育ちの段がずれた泡の塔が寄り集まる = もくもく)。
            for (int i = 0; i < 2; i++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double d = body * 0.4 * r.nextDouble();
                list.add(new Puff(s.x() + Math.cos(a) * d, LOW_BASE + body * 0.2, s.z() + Math.sin(a) * d, body * (1.2F + 0.4F * r.nextFloat()), 0.4F));
            }
            tower(list, r, s.x(), s.z(), body, top, dirX * lean * 0.6, dirZ * lean * 0.6);
            int side = 1 + r.nextInt(2);
            for (int k = 0; k < side; k++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double d = body * (0.6 + 0.4 * r.nextDouble());
                float rb = body * (0.5F + 0.2F * r.nextFloat());
                int ctop = LOW_BASE + (int) Math.round(h * (0.45 + 0.3 * r.nextDouble()));
                tower(list, r, s.x() + Math.cos(a) * d, s.z() + Math.sin(a) * d, rb, ctop, dirX * lean * 0.6, dirZ * lean * 0.6);
            }
            return list;
        }
        // 10-17 深夜(ユーザー「全体的にまだ柱感が強すぎる」): 1 本の柱ではなく、幅広の台の上に高さの違う子の塔(1〜3 本)を寄せた山にする。
        // 塔は上へ行くほど細り(下 1.05 → 上 0.6)、段ごとに横へふらつき、横へ張り出すこぶが付く。
        int cells = 3 + r.nextInt(4);
        double[] cx = new double[cells];
        double[] cz = new double[cells];
        float[] cr = new float[cells];
        int[] ctop = new int[cells];
        for (int c = 0; c < cells; c++) {
            if (c == 0) {
                cx[c] = s.x();
                cz[c] = s.z();
                cr[c] = body;
                ctop[c] = top;
            } else {
                double a = r.nextDouble() * Math.PI * 2.0;
                double d = body * (0.7 + 0.6 * r.nextDouble());
                cx[c] = s.x() + Math.cos(a) * d;
                cz[c] = s.z() + Math.sin(a) * d;
                cr[c] = body * (0.55F + 0.3F * r.nextFloat());
                ctop[c] = LOW_BASE + (int) Math.round(h * (0.35 + 0.4 * r.nextDouble()));
            }
        }
        // 幅広の台(平らな暗い底)。子の塔の足元まで広がる。
        for (int c = 0; c < cells; c++) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double d = cr[c] * 0.6 * r.nextDouble();
                list.add(new Puff(cx[c] + Math.cos(a) * d, LOW_BASE + cr[c] * 0.2, cz[c] + Math.sin(a) * d, cr[c] * (1.4F + 0.5F * r.nextFloat()), 0.4F));
            }
        }
        for (int c = 0; c < cells; c++) {
            tower(list, r, cx[c], cz[c], cr[c], ctop[c], dirX * lean, dirZ * lean);
        }
        Anvil an = anvil(s);
        if (an != null) {
            float big = an.radius();
            double ax = an.x();
            double az = an.z();
            double ay = an.y();
            double floor = an.floor();
            // 塔から金床へ広がる漏斗(10-17 ユーザー「傘のある積乱雲が下の積乱雲からスパッと切り離されているよう」、続けて「上の部分が柱から離れているほど
            // そっちの方に漏斗の角度が浅くなって、外側にまで斜めになるように」): 高さの 55% から金床の底まで、段ごとの断面を
            // 塔の断面(真ん中 = 塔の軸、半径 = その高さの胴の太さ)から金床の断面(真ん中 = 金床の真ん中、半径 = 金床の 0.92 倍)へ寄せた円にし、
            // 円の縁に塊を並べる(中は平たい芯で埋める)。寄せ方は上ほど速い(e = t^2.2)ので、上ほど角度が浅い。金床が遠くへ張り出している側ほど
            // 縁の動く距離が長いので、その側は浅い斜めのまま金床の縁まで届く。
            double neckFrom = LOW_BASE + h * 0.55;
            double neckTo = floor + 8.0;
            int steps = 14;
            float r0 = body * (float) (1.1 - 0.7 * 0.55);
            float rTop = big * 0.92F;
            for (int k = 0; k <= steps; k++) {
                double t = k / (double) steps;
                double e = Math.pow(t, 2.2);
                double y = Mth.lerp(t, neckFrom, neckTo);
                double fcx = Mth.lerp(e, s.x() + dirX * lean * (y - LOW_BASE), ax);
                double fcz = Mth.lerp(e, s.z() + dirZ * lean * (y - LOW_BASE), az);
                float rad = (float) Mth.lerp(e, r0, rTop);
                // 縁の塊: 下は丸く大きく、上ほど小さく平たく(浅い斜面に沿う)。
                float rr = (float) Mth.lerp(e, r0 * 0.55, rTop * 0.16);
                float sq = (float) Mth.lerp(e, 1.0, 0.45);
                double stepY = (neckTo - neckFrom) / steps;
                list.add(new Puff(fcx, y, fcz, rad * 0.88F, (float) Math.min(1.0, stepY * 1.6 / Math.max(1.0, rad * 0.88))));
                int ring = Math.max(6, (int) Math.ceil(2.0 * Math.PI * rad / (rr * 1.3)));
                double phase = r.nextDouble() * Math.PI * 2.0;
                for (int j = 0; j < ring; j++) {
                    double a = phase + j * Math.PI * 2.0 / ring;
                    float jr = rr * (0.85F + 0.3F * r.nextFloat());
                    list.add(new Puff(fcx + Math.cos(a) * (rad - jr * 0.4), y, fcz + Math.sin(a) * (rad - jr * 0.4), jr, sq));
                }
            }
            list.add(new Puff(ax, ay, az, big, 0.1F, floor));
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4.0 + r.nextDouble() * 0.5;
                double d = big * (0.75 + 0.15 * r.nextDouble());
                list.add(new Puff(ax + Math.cos(a) * d, ay - big * 0.02, az + Math.sin(a) * d, big * (0.3F + 0.15F * r.nextFloat()), 0.14F, floor));
            }
            if (hasMammatus(s)) {
                mammatus(list, RandomSource.create(s.id() ^ 0x3A33A7L), an, neckFrom + 0.45 * (neckTo - neckFrom));
            }
        }
        return list;
    }

    /**
     * 金床の形(金床の無い雲・入道雲・育ちきる前は null)。巨大な雲の金床は胴の 1.15〜1.6 倍(10-18。ふつうの雲と同じ 2〜2.8 倍だと半径 4000 を超えた)、
     * ふつうの雲は 2〜2.8 倍。真ん中は塔の頭から風下へ金床の 0.15〜0.45 倍ずれる。
     */
    /** 乳房雲を垂らす金床か(育ちきった金床の {@link #MAMMATUS_CHANCE})。 */
    public static boolean hasMammatus(Storm s) {
        Anvil an = anvil(s);
        return an != null && an.growth() > 0.5F && hash01(s.id(), 13) < MAMMATUS_CHANCE;
    }

    public static Anvil anvil(Storm s) {
        if (s.kind() != 0 || !s.anvil()) {
            return null;
        }
        float g = Mth.clamp((s.maturity() - 0.7F) / 0.3F, 0.0F, 1.0F);
        if (g <= 0.0F) {
            return null;
        }
        int top = s.topY();
        double h = top - LOW_BASE;
        double wl = Math.sqrt(WIND_X * WIND_X + WIND_Z * WIND_Z);
        double dirX = WIND_X / wl;
        double dirZ = WIND_Z / wl;
        float big = s.radius() * (s.giant() ? 1.15F + 0.45F * hash01(s.id(), 9) : 2.0F + 0.8F * hash01(s.id(), 9)) * (0.5F + 0.5F * g);
        double shift = big * (0.15 + 0.3 * hash01(s.id(), 11));
        double ay = top - big * 0.1;
        return new Anvil(s.x() + dirX * (TOWER_LEAN * h + shift), ay, s.z() + dirZ * (TOWER_LEAN * h + shift), big, ay - big * 0.1 * 0.35, g);
    }

    /**
     * 乳房雲(2026-10-18 空の世界の順番 4): 金床の下の面(塔から金床へ広がる漏斗の外側の斜面と平らな底)から垂れる丸い袋を、ずらした格子に並べる。
     * 袋の半径は金床の 0.09 倍ほど(ふつうの金床で 35〜65、巨大な金床で 130〜240)、間隔は 1.7 倍、数は {@link #MAMMATUS_MAX} まで。
     * 下の面の高さは、できた塊を上から下へたどって見つける({@link #underside}。最初は漏斗の断面の円で見積もったが、漏斗の縁のこぶの方が外へ大きく張り出していて、
     * 袋が雲の中に埋もれて見えなかった)。塔のすぐ脇(漏斗の下の 45% = 塔の横腹)には付けない。袋は下の面から 1.2 倍ほど垂らす(底は切らない)。
     * 袋どうしが積み重ならないよう、下の面は袋を足す前の塊で測る。数値はすべて仮決め。
     */
    private static void mammatus(List<Puff> list, RandomSource r, Anvil an, double minY) {
        float big = an.radius();
        double reach = big * 0.85;
        double spacing = Math.max(Math.max(MIN_PUFF + 4.0, big * 0.09) * 1.7, Math.sqrt(Math.PI * reach * reach / (MAMMATUS_MAX * 0.866)));
        float pr = (float) (spacing / 1.7);
        double step = Math.max(6.0, big * 0.012);
        int n = (int) Math.ceil(reach / spacing);
        List<Puff> pouches = new ArrayList<>();
        for (int j = -n; j <= n && pouches.size() < MAMMATUS_MAX; j++) {
            for (int i = -n; i <= n && pouches.size() < MAMMATUS_MAX; i++) {
                double px = an.x() + (i + ((j & 1) == 0 ? 0.0 : 0.5) + (r.nextDouble() - 0.5) * 0.4) * spacing;
                double pz = an.z() + (j * 0.866 + (r.nextDouble() - 0.5) * 0.4) * spacing;
                float jr = pr * (0.8F + 0.4F * r.nextFloat());
                float sq = 0.8F + 0.25F * r.nextFloat();
                if (Math.hypot(px - an.x(), pz - an.z()) > reach || r.nextFloat() < 0.12F) {
                    continue;
                }
                double under = underside(list, px, pz, an.y(), minY, step);
                if (Double.isNaN(under)) {
                    continue;
                }
                pouches.add(new Puff(px, under - jr * 0.3, pz, jr, sq));
            }
        }
        list.addAll(pouches);
    }

    /**
     * (x, z) の真上の雲の下の面の高さ: fromY(雲の中)から下へ step ずつたどり、雲から出た最初の高さ。fromY が雲の外、または minY まで雲の中なら NaN。
     * 乳房雲と尾流雲が、金床の下の面(漏斗の斜面)から垂れるのに使う。その列にかかる塊だけで確かめる。
     */
    public static double underside(List<Puff> puffs, double x, double z, double fromY, double minY, double step) {
        List<Puff> col = new ArrayList<>();
        for (Puff p : puffs) {
            double r = p.r() * 1.15;
            if (Math.abs(x - p.x()) < r && Math.abs(z - p.z()) < r) {
                col.add(p);
            }
        }
        if (col.isEmpty() || !inside(col, x, fromY, z)) {
            return Double.NaN;
        }
        for (double y = fromY - step; y > minY; y -= step) {
            if (!inside(col, x, y, z)) {
                return y + step * 0.5;
            }
        }
        return Double.NaN;
    }

    /**
     * 子の塔 1 本: 胴は上へ行くほど細る塊の列(縦 1.3 倍、段ごとに横へふらつく)、表面に横へ張り出すこぶ(子の泡 0.45〜0.7 倍)と孫の泡、天辺に丸い頭。
     * どの泡も top を越えない。
     */
    private static void tower(List<Puff> list, RandomSource r, double x, double z, float rb, int top, double leanX, double leanZ) {
        double h = top - LOW_BASE;
        if (h < rb * 0.6) {
            return;
        }
        float head = rb * 0.55F;
        double headY = top - head;
        double step = rb * 0.4;
        double wx = 0.0;
        double wz = 0.0;
        for (double y = LOW_BASE + rb * 0.35; y < headY; y += step) {
            double t = (y - LOW_BASE) / h;
            float rs = rb * (float) (1.1 - 0.7 * t) * (0.9F + 0.15F * r.nextFloat());
            rs = (float) Math.min(rs, (top - y) / 1.3);
            if (rs < MIN_PUFF) {
                break;
            }
            wx += (r.nextDouble() - 0.5) * 0.22 * rb;
            wz += (r.nextDouble() - 0.5) * 0.22 * rb;
            double px = x + leanX * (y - LOW_BASE) + wx;
            double pz = z + leanZ * (y - LOW_BASE) + wz;
            list.add(new Puff(px, y, pz, rs, 1.3F));
            bubbles(list, r, px, y, pz, rs, 1.3F, 3 + r.nextInt(3), top, 0.45F, 0.7F, 0.05F);
        }
        double hx = x + leanX * h + wx;
        double hz = z + leanZ * h + wz;
        list.add(new Puff(hx, headY, hz, head, 1.0F));
        bubbles(list, r, hx, headY, hz, head, 1.0F, 5 + r.nextInt(3), top, 0.4F, 0.6F, 0.3F);
    }

    /**
     * 塊 (x, y, z, rs, squash) の表面に子の泡を n 個
(半径 rs × lo〜hi)、それぞれの表面に孫の泡を 2〜3 個(rs × 0.13〜0.22)。
     * up = 上向きへ寄せる割合(0 = どの向きも同じ)。どの泡も topLimit を越えない・MIN_PUFF より小さい泡は付けない。
     */
    private static void bubbles(List<Puff> list, RandomSource r, double x, double y, double z, float rs, float squash, int n, double topLimit,
                                float lo, float hi, float up) {
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            double el = Math.asin(Mth.clamp(r.nextDouble() * 2.0 - 1.0 + up, -1.0, 1.0));
            double dx = Math.cos(el) * Math.cos(a);
            double dy = Math.sin(el);
            double dz = Math.cos(el) * Math.sin(a);
            double px = x + dx * rs * 0.95;
            double py = y + dy * rs * squash * 0.95;
            double pz = z + dz * rs * 0.95;
            float cr = rs * (lo + (hi - lo) * r.nextFloat());
            // 天辺の手前で止める高さを泡ごとにばらつかせる(全部を同じ高さで止めると、山の頂が平らな台になった)。
            cr = (float) Math.min(cr, (topLimit - py) * (0.55 + 0.45 * r.nextDouble()));
            if (cr < MIN_PUFF) {
                continue;
            }
            list.add(new Puff(px, py, pz, cr, 1.0F));
            int k = 2 + r.nextInt(2);
            for (int j = 0; j < k; j++) {
                double ga = r.nextDouble() * Math.PI * 2.0;
                double gel = Math.asin(Mth.clamp(r.nextDouble() * 1.6 - 0.6, -1.0, 1.0));
                double gx = px + Math.cos(gel) * Math.cos(ga) * cr * 0.85;
                double gy = py + Math.sin(gel) * cr * 0.85;
                double gz = pz + Math.cos(gel) * Math.sin(ga) * cr * 0.85;
                float gr = rs * (0.13F + 0.09F * r.nextFloat());
                gr = (float) Math.min(gr, (topLimit - gy) * (0.55 + 0.45 * r.nextDouble()));
                if (gr >= MIN_PUFF) {
                    list.add(new Puff(gx, gy, gz, gr, 1.0F));
                }
            }
        }
    }

    /**
     * 吐いた雲の塊(2026-10-19。空の巨大生物が吐いて隠れる雲、{@link CloudPuffs})。真ん中 (x, y, z)・横の半径 radius・高さ height のもくもくした塊:
     * 平たい芯の楕円体と、まわりの 7〜9 個の大きな塊(真ん中から半径の 0.45〜0.7 倍)、それぞれに子と孫の泡(積乱雲と同じ入れ子)。どの泡も天辺を越えない。
     * 形は seed で決まる(サーバーは位置と seed だけを送り、クライアントが同じ式で作る)。数値は仮決め。
     */
    public static List<Puff> blob(long seed, double x, double y, double z, float radius, float height) {
        RandomSource r = RandomSource.create(seed ^ 0xB10B5EEDL);
        List<Puff> list = new ArrayList<>();
        float half = height * 0.5F;
        double top = y + half;
        float core = radius * 0.62F;
        float coreSq = Math.min(1.0F, half / core);
        list.add(new Puff(x, y, z, core, coreSq));
        int n = 7 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            double a = (i + r.nextDouble() * 0.6) * Math.PI * 2.0 / n;
            double d = radius * (0.45 + 0.25 * r.nextDouble());
            float pr = radius * (0.32F + 0.14F * r.nextFloat());
            double px = x + Math.cos(a) * d;
            double pz = z + Math.sin(a) * d;
            double py = y + (r.nextDouble() - 0.4) * half * 0.6;
            float sq = Math.min(1.0F, half * (0.75F + 0.3F * r.nextFloat()) / pr);
            list.add(new Puff(px, py, pz, pr, sq));
            bubbles(list, r, px, py, pz, pr, sq, 2 + r.nextInt(2), top, 0.35F, 0.55F, 0.35F);
        }
        bubbles(list, r, x, y, z, core, coreSq, 6 + r.nextInt(3), top, 0.3F, 0.5F, 0.5F);
        return list;
    }

    /** その点が積乱雲の中か(塊の楕円体の和。縁は弱い 3 次元の雑音で少しうねる。でこぼこは泡の入れ子で付ける)。 */
    public static boolean inside(List<Puff> puffs, double x, double y, double z) {
        if (y < LOW_BASE) {
            return false;
        }
        // 塊の多い積乱雲は、その点の近くの塊だけを当てる(2026-10-19 軽量化。答えは同じ —— 塊に当たるのは水平に 1.1 r 以内だけ、
        // 当てる順は答えに効かない)。雲の組み立て・雲の自分の影で、点ごとに数百の塊を全部当てていた(作業スレッドの CPU の大半)。
        int[] near = null;
        if (puffs.size() >= PuffIndex.MIN_PUFFS) {
            near = PuffIndex.of(puffs).at(x, z);
            if (near == null) {
                return false;
            }
        }
        double noise = Double.NaN;
        int n = near == null ? puffs.size() : near.length;
        for (int k = 0; k < n; k++) {
            Puff p = puffs.get(near == null ? k : near[k]);
            if (y < p.floor()) {
                continue;
            }
            double dx = (x - p.x()) / p.r();
            double dy = (y - p.y()) / (p.r() * p.squash());
            double dz = (z - p.z()) / p.r();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 0.81) {
                return true;
            }
            if (d2 < 1.21) {
                if (Double.isNaN(noise)) {
                    noise = PUFF.getValue(x / 70.0, y / 70.0, z / 70.0);
                }
                double edge = 1.0 + 0.06 * noise;
                if (d2 < edge * edge) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 塊の一覧の水平の格子({@link #inside} を速くする。2026-10-19)。升ごとに、水平に 1.1 r の範囲がその升にかかる塊の番号を持つ。
     * 一覧(使い回される {@link #puffs} の結果)ごとに 1 回作り、一覧そのもの(同じもの)で引く。別スレッドから同時に引いてよい。
     */
    static final class PuffIndex {
        /** これより塊の少ない一覧は格子を作らず全部当てる。 */
        static final int MIN_PUFFS = 24;
        /** 升の辺(ブロック)。広すぎる一覧は升を大きくして 1 辺 256 升までにする。 */
        private static final double CELL = 48.0;
        private static final int MAX_CELLS = 256;
        /** 当たり得る水平の範囲(r の倍数)。{@link #inside} の {@code d2 < 1.21} と同じ。 */
        private static final double REACH = 1.1;
        private static final java.util.Map<List<Puff>, PuffIndex> CACHE = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private static final ThreadLocal<Object[]> LAST = ThreadLocal.withInitial(() -> new Object[2]);

        private final double minX;
        private final double minZ;
        private final double cell;
        private final int nx;
        private final int nz;
        private final int[][] cells;

        private PuffIndex(double minX, double minZ, double cell, int nx, int nz, int[][] cells) {
            this.minX = minX;
            this.minZ = minZ;
            this.cell = cell;
            this.nx = nx;
            this.nz = nz;
            this.cells = cells;
        }

        static PuffIndex of(List<Puff> puffs) {
            Object[] last = LAST.get();
            if (last[0] == puffs) {
                return (PuffIndex) last[1];
            }
            PuffIndex index = CACHE.get(puffs);
            if (index == null) {
                index = build(puffs);
                if (CACHE.size() > 1024) {
                    CACHE.clear();
                }
                CACHE.put(puffs, index);
            }
            last[0] = puffs;
            last[1] = index;
            return index;
        }

        static void clear() {
            CACHE.clear();
        }

        /** その点の升の塊の番号(当たり得る塊が無ければ null)。 */
        int[] at(double x, double z) {
            int i = (int) Math.floor((x - minX) / cell);
            int j = (int) Math.floor((z - minZ) / cell);
            if (i < 0 || j < 0 || i >= nx || j >= nz) {
                return null;
            }
            int[] c = cells[j * nx + i];
            return c.length == 0 ? null : c;
        }

        private static PuffIndex build(List<Puff> puffs) {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (Puff p : puffs) {
                double r = p.r() * REACH + 1.0;
                minX = Math.min(minX, p.x() - r);
                maxX = Math.max(maxX, p.x() + r);
                minZ = Math.min(minZ, p.z() - r);
                maxZ = Math.max(maxZ, p.z() + r);
            }
            double cell = Math.max(CELL, Math.max(maxX - minX, maxZ - minZ) / MAX_CELLS);
            int nx = Math.max(1, (int) Math.ceil((maxX - minX) / cell));
            int nz = Math.max(1, (int) Math.ceil((maxZ - minZ) / cell));
            int[] counts = new int[nx * nz];
            for (int pass = 0; pass < 2; pass++) {
                int[][] cells = pass == 1 ? new int[nx * nz][] : null;
                if (pass == 1) {
                    for (int c = 0; c < cells.length; c++) {
                        cells[c] = new int[counts[c]];
                        counts[c] = 0;
                    }
                }
                for (int k = 0; k < puffs.size(); k++) {
                    Puff p = puffs.get(k);
                    double r = p.r() * REACH + 1.0;
                    int i0 = Math.max(0, (int) Math.floor((p.x() - r - minX) / cell));
                    int i1 = Math.min(nx - 1, (int) Math.floor((p.x() + r - minX) / cell));
                    int j0 = Math.max(0, (int) Math.floor((p.z() - r - minZ) / cell));
                    int j1 = Math.min(nz - 1, (int) Math.floor((p.z() + r - minZ) / cell));
                    for (int j = j0; j <= j1; j++) {
                        for (int i = i0; i <= i1; i++) {
                            int c = j * nx + i;
                            if (pass == 1) {
                                cells[c][counts[c]] = k;
                            }
                            counts[c]++;
                        }
                    }
                }
                if (pass == 1) {
                    return new PuffIndex(minX, minZ, cell, nx, nz, cells);
                }
            }
            throw new IllegalStateException();
        }
    }

    /** 積乱雲の底(y 320)がその場所を覆っているか(下の層の雲を空ける・稲妻と雨の柱の出る所)。 */
    public static boolean baseCovers(List<Storm> storms, double fx, double fz) {
        for (Storm s : storms) {
            double r = s.reach();
            if (Math.abs(fx - s.x()) > r || Math.abs(fz - s.z()) > r) {
                continue;
            }
            if (inside(puffs(s), fx, LOW_BASE + 20.0, fz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 太陽の向きの線((fx, SHADOW_Y, fz) を通り、高さ y で xz が k·(y − shadowY) ずれる線)が積乱雲を通るか。雲の影の地図が引く。
     * 高さ 40 ごとに確かめる(塔の影は朝夕にとても遠くへ伸びる)。
     */
    public static boolean shadowLine(List<Storm> storms, double fx, double fz, double shadowY, double kx, double kz) {
        for (Storm s : storms) {
            double r = s.reach();
            int top = s.topY();
            List<Puff> puffs = null;
            for (double y = LOW_BASE + 12.0; y < top; y += 40.0) {
                double px = fx + kx * (y - shadowY);
                double pz = fz + kz * (y - shadowY);
                if (Math.abs(px - s.x()) > r || Math.abs(pz - s.z()) > r) {
                    continue;
                }
                if (puffs == null) {
                    puffs = puffs(s);
                }
                if (inside(puffs, px, y, pz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 雲の海の地形の天辺(底 300 からの高さ、0 = 雲が無い)。丘(大きな起伏 + 尾根)、谷(細い溝で薄く、底は切れる)、積乱雲の足元の裾野
     * (山の半径の 3 倍から盛り上がり、足元で 150 ほど)、表面の小さなもくもく。数値はすべて仮決め。
     * coverage = 日の雲の量(雲の少ない日ほど床に大きな切れ目 {@link #floorKeep}。裾野は切れない)。1 で切れ目なし。
     */
    public static float landscape(double fx, double fz, List<Storm> storms, float coverage) {
        // 10-17 夜更け(ユーザー「雲海側もなだらかな凹凸を」): 起伏を大きく・なだらかに(大きさ 1300 の丘 115 + 大きさ 700 の尾根 40)。
        double hill = smooth((float) (0.5 + 0.5 * BIG.getValue(fx / 1300.0, fz / 1300.0)));
        double ridge = 1.0 - Math.abs(MID.getValue(fx / 700.0 + 31.0, fz / 700.0));
        // 10-18 にボス(胴の厚み約 90)が潜れるよう起伏を 1.3 倍に(厚さ 50〜250 ほど)。
        float top = (float) (50.0 + 150.0 * hill + 52.0 * ridge * ridge);
        double valley = Math.abs(SMALL.getValue(fx / 1100.0 + 77.0, fz / 1100.0));
        float carve = smooth((float) ((valley - 0.03) / 0.09));
        float foot = 0.0F;
        for (Storm s : storms) {
            if (s.kind() != 0) {
                continue;
            }
            // 裾野: 山の半径の 3.3 倍から、山の高さに合わせて足元で 170〜220 まで盛り上がる(山が雲の海の坂から生える)。
            double dx = fx - s.x();
            double dz = fz - s.z();
            double most = s.bodyRadius() * 3.3;
            if (Math.abs(dx) > most || Math.abs(dz) > most) {
                continue;
            }
            // 10-18 夜(ユーザー「雲海 3 割・晴れ 7 割に。地面から見たとき雲が多すぎて常に薄暗かった」): 裾野もその場所の雲の量で縮める。
            // 晴れの日の少ない量では塔が裾野をほとんど持たずに立ち(半径の 1.3 倍・低く)、量 0.7 以上で 3.3 倍まで広がる(それまでは量によらず 3.3 倍で、
            // 雲の少ない日でも裾野が頭上を覆っていた)。巨大な雲は 3 割は残す(ボスが潜れる厚さの名残)。値は仮決め。
            float spread = smooth((regional(coverage, s.x(), s.z()) - 0.25F) / 0.5F);
            if (s.giant()) {
                spread = Math.max(spread, 0.3F);
            }
            double reach = s.bodyRadius() * (1.3 + 2.0 * spread);
            double d = Math.sqrt(dx * dx + dz * dz) / reach;
            if (d < 1.0) {
                // 巨大な雲の足元は 450 まで(10-18。ボスが潜って隠れられる厚さ)。
                float rise = (float) Math.min(s.giant() ? 450.0 : 220.0, 60.0 + 0.2 * (s.topY() - LOW_BASE)) * (0.35F + 0.65F * spread);
                foot = Math.max(foot, (float) (rise * Math.pow(smooth((float) (1.0 - d)), 1.3)));
            }
        }
        // 晴れの日の床のかけらは薄く(10-18 夜。量 0 で 0.45 倍、{@link #FLOOR_FULL_AT} で 1 倍)。
        float thin = Mth.lerp(smooth(coverage / FLOOR_FULL_AT), FLOOR_THIN, 1.0F);
        top = Math.max(top * carve * floorKeep(fx, fz, coverage) * thin, foot);
        if (top < 6.0F) {
            return 0.0F;
        }
        return top + 12.0F * (float) PUFF.getValue(fx / 45.0, fz / 45.0);
    }

    /** 巨大な積乱雲の雲海の濃さ(0〜1。真ん中ほど 1、SEA_RADIUS で 0)。 */
    public static float sea(List<Storm> storms, double fx, double fz) {
        float best = 0.0F;
        for (Storm s : storms) {
            if (!s.giant()) {
                continue;
            }
            double d = Math.hypot(fx - s.x(), fz - s.z());
            if (d >= SEA_RADIUS) {
                continue;
            }
            best = Math.max(best, smooth((float) (1.0 - d / SEA_RADIUS) * 1.6F) * smooth((s.maturity() - 0.2F) / 0.5F));
        }
        return best;
    }

    static float hash01(long seed, int salt) {
        long h = seed + salt * 0x632BE59BD9B4E019L + SEED;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

    /** 量(0〜1)から、雲になる濃さのしきい値。 */
    public static float threshold(float coverage) {
        if (coverage <= 0.0F) {
            return Float.MAX_VALUE;
        }
        int i = Mth.clamp((int) ((1.0F - coverage) * (QUANTILES.length - 1)), 0, QUANTILES.length - 1);
        return QUANTILES[i];
    }

    /**
     * 場の座標の 1 本の柱(横 {@link #CELL_XZ} の升の真ん中で引く)。底と天辺は {@link #CELL_Y} の倍数。
     * strength = 雲の芯の濃さ(0 = 縁 〜 1 = 芯)。
     */
    public static Column column(int cellX, int cellZ, float threshold, float coverage) {
        return column(cellX, cellZ, threshold, coverage, List.of(), STYLE_SWARM);
    }

    /**
     * 巨大な積乱雲のまわりでは、雲の量をほぼ一面に、厚さを雲海({@link #SEA_TOP} まで)にする。style = 並び方(谷・壁と橋は積乱雲が近くに無くても雲の床。
     * 10-18 までは近くに積乱雲がある間だけ床を出していたので、夜・朝・雲の少ない日は床ごと消えていた)。
     */
    public static Column column(int cellX, int cellZ, float threshold, float coverage, List<Storm> storms, int style) {
        return column(cellX, cellZ, threshold, coverage, storms, style, true);
    }

    /** cumulus = 晴れの日の積雲も入れるか(遠景の粗い格子では入れない。48〜96 ブロックの柱では小さい雲が点々の箱になる)。 */
    public static Column column(int cellX, int cellZ, float threshold, float coverage, List<Storm> storms, int style, boolean cumulus) {
        double fx = (cellX + 0.5) * CELL_XZ;
        double fz = (cellZ + 0.5) * CELL_XZ;
        float sea = storms.isEmpty() ? 0.0F : sea(storms, fx, fz);
        int cap = LOW_TOP;
        float seaThick = 0.0F;
        if (style >= STYLE_VALLEY) {
            // 雲の海の地形(谷の並び方。10-17 ユーザー「積乱雲だけが独立しているように見える。雲でできた海があって、そこに山や谷などがある感じ」):
            // 起伏のある雲の海(丘)に、谷(細い溝。底は薄く、いちばん深い所は切れて地面が見える)、山(積乱雲)の足元へ盛り上がる裾野。
            float landTop = landscape(fx, fz, storms, coverage);
            // 晴れの日の小さい雲・中くらいの雲(10-18 夜)。
            if (cumulus) {
                landTop = Math.max(landTop, cumulusTop(fx, fz, coverage));
            }
            if (landTop <= 0.0F) {
                return NONE;
            }
            int t = LOW_BASE + Math.max(CELL_Y, Math.round(landTop / CELL_Y) * CELL_Y);
            return new Column(LOW_BASE, t, 1.0F);
        }
        if (sea > 0.0F) {
            coverage = Mth.lerp(sea, coverage, 0.97F);
            threshold = threshold(coverage);
            cap = Math.max(cap, Math.round(Mth.lerp(sea, LOW_TOP, SEA_TOP)));
            seaThick = Math.max(seaThick, sea * (SEA_TOP - LOW_BASE - 20));
        }
        if (storms.isEmpty() || sea <= 0.0F) {
            // 地域の雲の多さ(10-18): 晴れた地域は量を減らし、雲の多い地域は増やす。
            threshold = threshold(regional(coverage, fx, fz));
        }
        float d = density(fx, fz);
        if (d <= threshold) {
            return NONE;
        }
        float h = Mth.clamp((d - threshold) / Math.max(0.02F, 1.0F - threshold), 0.0F, 1.0F);
        // 晴れの日の積雲は 20 ブロックほど、曇りの日は 45 ブロックほどまで盛り上がる。
        float maxThick = 14.0F + 34.0F * Mth.clamp(coverage, 0.0F, 1.0F);
        float puff = (float) PUFF.getValue(fx / 20.0, fz / 20.0);
        float thick = CELL_Y + (float) Math.pow(h, 0.6) * (maxThick - CELL_Y) + 6.0F * puff * h;
        if (seaThick > 0.0F) {
            // 雲海: 厚い雲の床の上に、ゆるい大きなうねり(最初は柱ごとのでこぼこが大きく、上から見ると針山のようだった)。
            float swell = (float) PUFF.getValue(fx / 140.0 + 7.0, fz / 140.0);
            thick = Math.max(thick, seaThick * (0.9F + 0.1F * h) + 12.0F * swell);
        }
        // 底はどこも 300(積雲の底は同じ高さで平ら。最初は大きな模様で 304 の所も作ったが、段の 4 ブロックの横の面が遠くで白い筋に見えた)。
        int base = LOW_BASE;
        int top = base + Math.max(CELL_Y, Math.round(thick / CELL_Y) * CELL_Y);
        top = Math.min(top, cap);
        return new Column(base, top, h);
    }

    /** その日の量(0〜1、雨の前)。dayTime はワールドの時刻(0 = 日の出)。 */
    public static float coverage(long dayTime) {
        double t = (dayTime + 6000.0) / 24000.0;
        long day = Mth.floor(t);
        double frac = t - day;
        if (frac < 0.125) {
            return Mth.lerp(smooth((float) (0.5 + frac / 0.25)), dayCoverage(day - 1), dayCoverage(day));
        }
        if (frac > 0.875) {
            return Mth.lerp(smooth((float) ((frac - 0.875) / 0.25)), dayCoverage(day), dayCoverage(day + 1));
        }
        return dayCoverage(day);
    }

    /** 雨・雷雨を入れた量。 */
    public static float coverage(long dayTime, float rain, float thunder) {
        float c = coverage(dayTime);
        c = Mth.lerp(rain, c, Math.max(c, 0.9F));
        return Mth.lerp(thunder, c, Math.max(c, 0.97F));
    }

    /**
     * 天気の日ごとの量(10-18 夜 ユーザー「雲海(曇りや雨含む)3 割、晴れ(雲が空を占める割合が 0〜7 割の空)が 7 割」): 曇り(一面の雲海)の日が
     * {@link #OVERCAST_DAYS}、残りは晴れの日で、量を 0〜{@link #CLEAR_MAX_COVER} から一様に選ぶ(床の残る割合は量とほぼ同じ。谷の溝と切れ目の縁の薄い所が消えるので、量 ÷ 0.9 より少し少ない)。
     * 雨・雷雨の間は量を 0.9 以上へ寄せる(一面の雲海)ので、バニラの雨(時間の 15% ほど)と合わせて雲海が時間の 3 割ほどになるよう、曇りの日は 18%(仮決め)。
     * それまでは 快晴 15%・少し 15%・ふつう 45%・多め 17%・曇り 8%(雲の床のある並び方では、ふつうの日でも床が 5〜8 割残った)。
     */
    static float dayCoverage(long day) {
        long h = day * 0x9E3779B97F4A7C15L + SEED;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        float u = (h >>> 40) / (float) (1 << 24);
        float v = ((h >>> 16) & 0xFFFFFF) / (float) (1 << 24);
        if (u < OVERCAST_DAYS) {
            return FLOOR_FULL_AT + 0.08F * v;
        }
        return CLEAR_MAX_COVER * v;
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private CloudField() {
    }
}
