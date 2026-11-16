package com.yayuy.overgrownvegetation.data;

/**
 * ワールドイベント 7 つとフェーズの合図 F3・F7 の<b>見た目の値</b>(2026-11-16。{@code sky.json} の節 {@code worldEvents}。Gson の受け皿をそのまま使う:
 * 書いていない値は下の初期値)。読むのはクライアントの描画だけ({@code client/worldevent/})。<b>すべて仮決め</b>(ユーザーが絵を見て決める)。
 * 決まっている中身は Vault「ワールドイベント 7 つの中身の案」の【決定】、作り方は「ワールドイベントの見た目(実装の記録)」。
 */
public final class WorldEventLooks {

    public Aurora aurora = new Aurora();
    public SporeRise sporeRise = new SporeRise();
    public Pillars pillars = new Pillars();
    public Halo halo = new Halo();
    public Meteors meteors = new Meteors();
    public Eclipse eclipse = new Eclipse();
    public LeyTide leyTide = new LeyTide();
    public Shadow shadow = new Shadow();
    public Tide tide = new Tide();

    /** 胞子のオーロラ(夜空の緑〜黄緑の帯。汚染の森の上ほど明るい)。 */
    public static final class Aurora {
        /** 全体の強さ(0 = 出さない)。 */
        public float strength = 1.0F;
        /** 森の無い所の明るさ・森の上で足す明るさ。 */
        public float base = 0.45F;
        public float forestBoost = 0.9F;
        /** 森の明るい所 1 つの広がり(ブロック)。 */
        public float forestRadius = 900.0F;
        /** 幕の下の縁と上の端の高さ(カメラからではなく世界の y)。 */
        public float bottom = 1800.0F;
        public float top = 3600.0F;
        /** 幕の模様の大きさ(ブロック)と、流れる速さ(ブロック/秒)・縦の筋の揺らぎの速さ。 */
        public float scale = 2600.0F;
        public float drift = 14.0F;
        public float shimmer = 1.0F;
        /** 幕の下の色・上の色。 */
        public float[] colorLow = {0.20F, 1.00F, 0.38F};
        public float[] colorHigh = {0.72F, 0.95F, 0.22F};
        /** 出るのに掛ける tick・消えるのに掛ける tick(ゲーム内 1 時間 = 1000)。 */
        public float riseTicks = 1000.0F;
        public float fallTicks = 1000.0F;
        /** 森の霞の中にいるとき、どれだけ薄くするか(0〜1)。 */
        public float hazeDim = 0.7F;
    }

    /** オーロラの盛りに、森の梢の上へ昇るまばらな光の粒(弱く匂わせる。見た目だけ)。 */
    public static final class SporeRise {
        public float strength = 1.0F;
        /** 同時に出す粒の数の上限・カメラからの範囲(ブロック)。 */
        public int count = 70;
        public float range = 96.0F;
        /** 昇る速さ(ブロック/秒)・梢の上どこまで昇るか・粒の大きさ(ブロック)。 */
        public float speed = 0.7F;
        public float height = 42.0F;
        public float size = 0.16F;
        public float[] color = {0.62F, 0.95F, 0.30F};
    }

    /** フェーズ 7 の合図: 一夜だけ、森の方角の地平線に立つ胞子の柱。 */
    public static final class Pillars {
        public float strength = 1.0F;
        /** 本数・柱の見かけの高さ(仰角。度)と太さ(度)・横に広がる角度(度)。 */
        public int count = 7;
        public float heightDeg = 26.0F;
        public float widthDeg = 2.2F;
        public float spreadDeg = 70.0F;
        public float[] color = {0.60F, 0.92F, 0.28F};
    }

    /** 日暈(太陽のまわりの光の輪と幻日。空高くの胞子の結晶で光が曲がる)。 */
    public static final class Halo {
        public float strength = 1.0F;
        /** 輪の半径と太さ(度)。本物は 22 度。 */
        public float radiusDeg = 22.0F;
        public float widthDeg = 1.6F;
        /** 幻日の強さ・太陽のまわりの乳白色の膜の強さ・虹色の付き具合(0 = 白い輪)。 */
        public float dogs = 1.0F;
        public float veil = 0.3F;
        public float rainbow = 0.7F;
        /** 出る・消えるのに掛ける tick。 */
        public float riseTicks = 300.0F;
        public float fallTicks = 300.0F;
    }

    /** 流星群(一晩じゅう流れ星・たまに火球。音は付けない)。 */
    public static final class Meteors {
        public float strength = 1.0F;
        /** 盛り(真夜中)の 1 分あたりの流れ星の数・火球の間の平均(分)。 */
        public float perMinute = 40.0F;
        public float fireballMinutes = 2.5F;
        public float[] color = {0.92F, 0.96F, 1.0F};
        public float[] fireballColor = {1.0F, 0.82F, 0.45F};
    }

    /** 日食(ユーザー決定: 夜と同じ真っ暗・星が出る・地平線だけ夕焼け色。敵対 Mob の動きは変えない = 見た目だけ)。 */
    public static final class Eclipse {
        /** 暗くなる深さ(1 = 夜と同じ。ユーザー決定は 1)。 */
        public float darkness = 1.0F;
        /** 太陽の絵の芯の大きさ(空の描画の単位。太陽の絵は幅 60)。月はこの 1.67 倍。 */
        public float sunSize = 17.0F;
        /** 地平線の夕焼け色・その強さ・帯の高さ(視線の y)・太陽の側をどれだけ明るくするか。 */
        public float[] horizonColor = {1.0F, 0.42F, 0.14F};
        public float horizonStrength = 0.5F;
        public float horizonHeight = 0.10F;
        public float horizonToward = 0.6F;
        /** 真っ暗な間の霧の色(遠くの地形が溶ける色)。 */
        public float[] fogColor = {0.085F, 0.045F, 0.035F};
        /** 真っ暗な間に太陽のまわりに見える光の輪(コロナ)の強さ。 */
        public float corona = 1.0F;
    }

    /** 地脈の大潮(地脈の線の上の地面から立ちのぼる光・岩肌の筋の強まり。効き目はサーバー側)。 */
    public static final class LeyTide {
        public float strength = 1.0F;
        /** 昼の強さ(夜を 1 として)。 */
        public float dayStrength = 0.45F;
        /** 光を立てる範囲(カメラから。ブロック)・線からの幅(ブロック)・線の上の升のうち光を立てる割合。 */
        public float range = 64.0F;
        public float lineWidth = 3.0F;
        public float density = 0.30F;
        /** 光の帯の高さと幅(ブロック)。 */
        public float height = 7.0F;
        public float width = 1.3F;
        /** 通常世界の色(水色)・汚染地域の色(地脈の根の色)。 */
        public float[] color = {0.38F, 0.86F, 1.0F};
        public float[] pollutedColor = {0.675F, 0.89F, 0.10F};
        /** 岩肌の地脈の筋の明るさの倍率。 */
        public float veinBoost = 2.2F;
    }

    /** 地平線を歩く影(監視塔に似せた巨大な影絵。霧の日だけ)。 */
    public static final class Shadow {
        public float strength = 1.0F;
        /** 見かけの高さ(度)・足元の仰角(度。負 = 地平線の下)。 */
        public float heightDeg = 24.0F;
        public float baseDeg = -5.0F;
        /** 霧の色からどれだけ暗くするか・影の濃さ。 */
        public float darken = 0.42F;
        public float opacity = 0.62F;
        /** 濃霧の度合いがここから見え始め・ここで見えきる。 */
        public float fogFrom = 0.15F;
        public float fogFull = 0.35F;
        /** 1 歩の秒・何歩ごとに止まるか・止まる秒・1 歩で進む幅(体の高さを 1 として)。 */
        public float stepSeconds = 14.0F;
        public int stepsPerWalk = 2;
        public float pauseSeconds = 12.0F;
        public float stride = 0.10F;
    }

    /** 光る潮(夜の水面が青緑に光る・岸へ寄せる光・泳いだ跡)。 */
    public static final class Tide {
        public float strength = 1.0F;
        public float[] color = {0.16F, 0.92F, 0.78F};
        /** 岸の光・1 画素の火花・泳いだ跡の光の強さ。 */
        public float shore = 1.0F;
        public float sparks = 0.9F;
        public float wake = 1.2F;
        /** 泳いだ跡の広さ(ブロック)・残る秒。 */
        public float wakeRadius = 1.4F;
        public float wakeSeconds = 8.0F;
    }
}
