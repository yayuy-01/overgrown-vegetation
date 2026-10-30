package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;

/**
 * 寄生されたブレイズの、動きにつられて揺れる体と棒(2026-10-09 夜。ユーザー「移動や待機のときに周囲の棒がもっと動くように」)。
 * 待機の揺れはアニメ(生成器 {@code G1BlazeAnims} の idle)、こちらは<b>動いたぶんだけ</b>足す揺れ。クライアントだけで 1 tick ごとに進める
 * 振り子(ばね + 減衰)で、描画は {@code client.ParasitizedBlazeModel} が骨へ足す。
 * <ul>
 *   <li>体: 上の胞子嚢から吊られた体(骨 root)が、進む向きと逆へ遅れて傾き、止まると行き過ぎて揺れ戻る。</li>
 *   <li>棒 8 本: 1 本ずつ別の振り子(揺れの速さ・効き方を少しずつ変えて、そろって動かないように)。進む向きと逆へなびき、
 *       速さが変わるたびに振れる。下りると先が外へ開き、上ると内へ閉じる。</li>
 *   <li>芯のねじれ: 体が向きを変えると、棒の環が置いていかれ、遅れて追いつく(行き過ぎて戻る)。</li>
 * </ul>
 * 角度は度。傾きは「下の端がずれる向き」を体の向き(前・右)で持つ。数値はすべて仮決め。
 * 漂っている 15 秒は入力を 0 にする(ユーザー指定「15 秒間は何もせず死んだ場所で漂うだけ」。目覚めから効く)。
 */
public final class BlazeRodSwing {

    /** 棒の本数(生成器 G1Blaze.RODS の 8 本。骨は {@code rod<i>_hang})。 */
    public static final int RODS = 8;

    private static final double DT = 0.05;

    // 並び: 体の前・右、芯のねじれ、棒 i の前・右・開き(3 + 3 i 〜)
    private static final int ROOT_F = 0;
    private static final int ROOT_R = 1;
    private static final int TWIST = 2;
    private static final int N = 3 + 3 * RODS;

    /** 体: 揺れの速さ(Hz)・減衰・速さ 1 ブロック/tick あたりの傾き(度)・速さが変わったときの振れ((度/秒)/(ブロック/tick))・上限(度)。 */
    private static final double ROOT_HZ = 0.55;
    private static final double ROOT_DAMP = 0.3;
    private static final double ROOT_TRAIL = 40;
    private static final double ROOT_KICK = 260;
    private static final double ROOT_MAX = 18;

    /** 棒: 1 本ずつの揺れの速さ(Hz)と効き方(倍)。 */
    private static final double[] ROD_HZ = {0.95, 1.2, 0.85, 1.1, 1.3, 0.9, 1.15, 1.0};
    private static final double[] ROD_GAIN = {1.0, 0.85, 1.15, 0.95, 1.1, 0.9, 1.05, 1.2};
    private static final double ROD_DAMP = 0.16;
    private static final double ROD_TRAIL = 100;
    private static final double ROD_KICK = 800;
    private static final double ROD_MAX = 40;
    /** 開き: 下る速さ 1 ブロック/tick あたり外へ開く角度(度)。上ると閉じる。 */
    private static final double FLARE_TRAIL = 120;
    private static final double FLARE_KICK = 500;
    private static final double FLARE_MIN = -20;
    private static final double FLARE_MAX = 35;

    /** ねじれ: 体が向きを変えた角度のうち、環が置いていかれる割合と、戻りの揺れの速さ(Hz)・減衰・上限(度)。 */
    private static final double TWIST_LAG = 0.45;
    private static final double TWIST_HZ = 0.6;
    private static final double TWIST_DAMP = 0.2;
    private static final double TWIST_MAX = 40;

    private final float[] pos = new float[N];
    private final float[] prev = new float[N];
    private final double[] vel = new double[N];
    private double lastForward;
    private double lastRight;
    private double lastUp;
    private boolean started;

    /**
     * 1 tick 進める。
     *
     * @param forward 体の前への速さ(ブロック/tick)
     * @param right   体の右への速さ
     * @param up      上への速さ
     * @param turn    体の向きが変わった角度(度/tick。バニラの yBodyRot が増える向き = 上から見て時計回り)
     */
    public void tick(double forward, double right, double up, double turn) {
        if (!this.started) {
            // 見え始め(読み込み・湧いた直後)は、その時の速さを「前からの速さ」とみなす(いきなり振れない)
            this.started = true;
            this.lastForward = forward;
            this.lastRight = right;
            this.lastUp = up;
            turn = 0;
        }
        System.arraycopy(this.pos, 0, this.prev, 0, N);
        double df = forward - this.lastForward;
        double dr = right - this.lastRight;
        double du = up - this.lastUp;
        this.lastForward = forward;
        this.lastRight = right;
        this.lastUp = up;
        // 体: 下の端が進む向きと逆へ(前へ進めば後ろへ)
        spring(ROOT_F, -ROOT_TRAIL * forward, ROOT_HZ, ROOT_DAMP, -ROOT_KICK * df, -ROOT_MAX, ROOT_MAX);
        spring(ROOT_R, -ROOT_TRAIL * right, ROOT_HZ, ROOT_DAMP, -ROOT_KICK * dr, -ROOT_MAX, ROOT_MAX);
        // ねじれ: 体が回ったぶん環が置いていかれる(その場で角度をずらし、ばねで戻す)
        this.pos[TWIST] = (float) Mth.clamp(this.pos[TWIST] + TWIST_LAG * turn, -TWIST_MAX, TWIST_MAX);
        spring(TWIST, 0, TWIST_HZ, TWIST_DAMP, 0, -TWIST_MAX, TWIST_MAX);
        for (int i = 0; i < RODS; i++) {
            int b = 3 + 3 * i;
            double g = ROD_GAIN[i];
            spring(b, -ROD_TRAIL * g * forward, ROD_HZ[i], ROD_DAMP, -ROD_KICK * g * df, -ROD_MAX, ROD_MAX);
            spring(b + 1, -ROD_TRAIL * g * right, ROD_HZ[i], ROD_DAMP, -ROD_KICK * g * dr, -ROD_MAX, ROD_MAX);
            spring(b + 2, -FLARE_TRAIL * g * up, ROD_HZ[i], ROD_DAMP, -FLARE_KICK * g * du, FLARE_MIN, FLARE_MAX);
        }
    }

    /** 速さが前の tick のまま変わらなかったことにして 1 tick 進める(テレポートの tick。位置の飛びを速さに数えない)。 */
    public void tickUnchanged() {
        tick(this.lastForward, this.lastRight, this.lastUp, 0);
    }

    /** ばね 1 つを 1 tick(角度 = 度、速さ = 度/秒)。{@code kick} は速さへその場で足す。上限に当たったら速さを捨てる。 */
    private void spring(int k, double target, double hz, double damp, double kick, double min, double max) {
        double w = 2 * Math.PI * hz;
        double v = this.vel[k] + kick;
        v += (w * w * (target - this.pos[k]) - 2 * damp * w * v) * DT;
        double p = this.pos[k] + v * DT;
        if (p < min || p > max) {
            p = Mth.clamp(p, min, max);
            v = 0;
        }
        this.pos[k] = (float) p;
        this.vel[k] = v;
    }

    private float at(int k, float partialTick) {
        return Mth.lerp(partialTick, this.prev[k], this.pos[k]);
    }

    /** 体の下の端が前へずれる角度(度。後ろへは負)。 */
    public float rootForward(float partialTick) {
        return at(ROOT_F, partialTick);
    }

    /** 体の下の端が右へずれる角度。 */
    public float rootRight(float partialTick) {
        return at(ROOT_R, partialTick);
    }

    /** 環のねじれ(度。体の yBodyRot が増える向きへ回ったぶん置いていかれる = 正)。 */
    public float twist(float partialTick) {
        return at(TWIST, partialTick);
    }

    public float rodForward(int rod, float partialTick) {
        return at(3 + 3 * rod, partialTick);
    }

    public float rodRight(int rod, float partialTick) {
        return at(4 + 3 * rod, partialTick);
    }

    /** 棒の先が外へ開く角度(内へ閉じるのは負)。 */
    public float rodFlare(int rod, float partialTick) {
        return at(5 + 3 * rod, partialTick);
    }
}
