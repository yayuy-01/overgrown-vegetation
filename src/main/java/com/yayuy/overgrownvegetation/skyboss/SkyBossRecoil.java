package com.yayuy.overgrownvegetation.skyboss;

/**
 * 避雷針が爆発したときの体の反動(2026-10-18 夜)。サーバー(当たり判定の形)とクライアント(描画)が同じ式で出す。
 *
 * <p>ユーザーの直し(10-17)「避雷針部分が爆発し、その反動で避雷針がついている部分を中心に下に押し込まれるような感じに」。
 * 形はモデルの道具の {@code SkyGlaucusAttacks.shortBody}(アニメ short_body)と同じ式をここへ写した: 避雷針と扇の付いた胴の真ん中(b02〜b11)が一塊で
 * モデルの −y(腹の側 = 避雷針から離れる向き)へ最大 34 単位押し込まれ、頭と尾は遅れて付いてきて、跳ね返りながら収まる。
 *
 * <p>アニメの short_body は体の層のうねりを止めて差し替えるので、ゲームでは流さずに、この式の値をうねりと道をなぞる曲がりに<b>足す</b>
 * (うねりが止まらない・当たり判定の形にもそのまま入る)。腹が上の渡りの間は避雷針が下を向いているので、体は上へ押し上げられる(避雷針から離れる向き)。
 */
public final class SkyBossRecoil {

    /** 体の骨の数(頭・b01〜b18・尾 16。{@code SkyGlaucusAnims.bodyNames} の並び)。 */
    public static final int BONES = 1 + SkyBossBody.SEGS + SkyBossBody.TAIL;
    /** 反動が続く長さ(元の秒。爆発から跳ね返りが収まるまで + 尾の遅れ)。 */
    public static final double END = SkyBossTuning.ROD_BOOM + 3.7;

    /** 頭の先・避雷針の z(モデルの単位。道具と同じ)と、押し込みの深さの最大。 */
    private static final double ZH = -334.0;
    private static final double ZR = -194.0;
    private static final double DMAX = 34.0;
    private static final double ZA = SkyBossBody.Z0 + SkyBossBody.SEG_L;
    private static final double ZB = SkyBossBody.Z0 + SkyBossBody.SEG_L * 11;
    private static final double[] ZS = new double[BONES + 1];

    static {
        ZS[0] = ZH;
        for (int i = 0; i < SkyBossBody.SEGS; i++) {
            ZS[1 + i] = SkyBossBody.Z0 + SkyBossBody.SEG_L * i;
        }
        double z0 = SkyBossBody.Z0 + SkyBossBody.SEG_L * SkyBossBody.SEGS;
        for (int k = 0; k <= SkyBossBody.TAIL; k++) {
            ZS[1 + SkyBossBody.SEGS + k] = z0 + SkyBossBody.TAIL_L * k;
        }
    }

    /**
     * 元の秒 {@code t}(爆発のアニメの頭から)の反動。{@code rotDeg} = 骨ごとの回転 x(道具のアニメの度。頭・b01〜b18・尾 1〜16 の順。親に対する差)、
     * 戻り値 = 体の根(body)の位置 y(モデルの単位)。続く間の外なら全部 0。
     */
    public static double compute(double t, double[] rotDeg) {
        java.util.Arrays.fill(rotDeg, 0.0);
        if (!(t >= 0.0 && t <= END)) {
            return 0.0;
        }
        double[] ys = new double[ZS.length];
        for (int i = 0; i < ZS.length; i++) {
            double z = ZS[i];
            if (z >= ZA && z <= ZB) {
                ys[i] = mid(z, t);
            } else if (z < ZA) {
                // 頭の側: 付け根ほど一塊に付いていき、先ほど遅れて半分だけ付いてくる
                double f = Math.exp(-Math.pow((ZA - z) / 45.0, 2));
                ys[i] = mid(ZA, t) * f + mid(ZA, t - 0.25) * (1 - f) * 0.3;
            } else {
                double f = Math.exp(-Math.pow((z - ZB) / 130.0, 2));
                ys[i] = mid(ZB, t) * f + mid(ZB, t - 0.35 * (1 - f)) * (1 - f) * 0.35;
            }
        }
        double[] ax = new double[ZS.length - 1];
        for (int i = 0; i + 1 < ZS.length; i++) {
            ax[i] = Math.toDegrees(Math.atan2(ys[i + 1] - ys[i], ZS[i + 1] - ZS[i]));
        }
        rotDeg[1] = ax[1];
        rotDeg[0] = ax[0] - ax[1];
        for (int i = 2; i < BONES; i++) {
            rotDeg[i] = ax[i] - ax[i - 1];
        }
        return ys[1];
    }

    /** 胴の真ん中の高さ(一塊 = z に対して直線。避雷針の所で −D、後ろの端で −0.7 D)。 */
    private static double mid(double z, double t) {
        return -DMAX * recoil(t) * (1 - 0.3 * (z - ZR) / (ZB - ZR));
    }

    /**
     * 爆発の反動の形(0 → 1 → 跳ね返り → 0)。押し込みは道具と同じ速さ(爆発から 0.22 秒で底)。
     * 10-19 ユーザーの直し「元の姿勢に戻るのが少々速すぎる」→ 底で少し留まり、戻りを 約 2 倍ゆっくりに(底から元の高さまで 約 1.9 秒、前は 約 0.9 秒)、
     * 跳ね返りも小さく(−0.25 → −0.15)。道具の short_body(ゲームでは流さない)とはここが違う。値は仮。
     */
    static double recoil(double t) {
        double b = SkyBossTuning.ROD_BOOM;
        return curve(t, b, 0, b + 0.22, 1, b + 0.45, 0.96, b + 1.4, -0.15, b + 2.3, 0.05, b + 3.2, 0);
    }

    /** 道具の {@code SkyGlaucusAnims.curve}: キー {時刻, 値, …} の間をなめらかに(3u² − 2u³)。 */
    private static double curve(double t, double... k) {
        if (t <= k[0]) {
            return k[1];
        }
        for (int i = 2; i < k.length; i += 2) {
            if (t <= k[i]) {
                double u = (t - k[i - 2]) / (k[i] - k[i - 2]);
                double s = u * u * (3 - 2 * u);
                return k[i - 1] + (k[i + 1] - k[i - 1]) * s;
            }
        }
        return k[k.length - 1];
    }

    private SkyBossRecoil() {
    }
}
