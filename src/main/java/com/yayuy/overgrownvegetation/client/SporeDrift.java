package com.yayuy.overgrownvegetation.client;

import net.minecraft.util.RandomSource;

/**
 * 汚染地域の<b>胞子 1 粒の動き方</b>。光の筋の中で光る胞子({@link CanopyLight})と、バイオームの環境の胞子
 * ({@link DriftingSporeParticle})が<b>同じ式</b>で動く(2026-10-15 夜、ユーザー「環境パーティクルの胞子の動きが、差し込む光に照らされた
 * 胞子の動きと違いすぎる。光の胞子に合わせて」「突風が吹いた際はそれらがランダムな方向に一斉に動くように」)。
 *
 * <p>胞子はとても軽いので、周りの空気とほとんど同じに動く。空気の動きは 3 つの和:
 * <ol>
 *     <li><b>風</b>({@link CanopyWind#velocity}): 凪では毎秒 0.3 ブロックほどで風下へ流れ、突風が来るとその突風の向きへ毎秒 2〜5 ブロックで
 *     流される。突風は 1 つずつ向きが違い、林を渡ってくるので、来た所から順に周りの胞子が<b>揃って</b>流れ出す。</li>
 *     <li><b>渦</b>: 近くの粒が揃って回る、ゆっくり形を変える流れ(sin の積。波長 15〜35 ブロック・周期 7〜10 秒の大きい渦と、
 *     突風のときだけ目立つ波長 5〜7 ブロックの小さい渦)。粒ごとの乱数だけだと、ばらばらに震えて見える。</li>
 *     <li>ほんの少しの沈み(毎秒 0.02 ブロック)と、粒ごとの小さな揺らぎ。</li>
 * </ol>
 * 粒の速さは空気の速さへ毎 tick 9% ずつ寄せる(約 0.5 秒で追いつく。突風の出だしで、すっと流れ出す)。数値はすべて仮決め。
 * 突風のさなかの速さは、最初は毎秒 1.3 ブロックで、ユーザー「突風が吹く際はもっと速くしてもよい」(2026-10-15 夜)で 2.5 倍ほどにした
 * (凪はほぼそのまま。強い風ほど急に速くなる: 風の強さの 1 乗と 3 乗の和)。
 */
final class SporeDrift {

    /** 速さを空気へ寄せる割合(毎 tick)。 */
    private static final double FOLLOW = 0.09;
    /** 沈む速さ(ブロック/tick)。 */
    private static final double SETTLE = -0.0012;

    /** {@link CanopyWind#velocity} の受け皿(描画スレッドだけが使う)。 */
    private static final double[] WIND = new double[2];
    private static final double[] AIR = new double[3];
    private static final double[] FLOW = new double[2];

    /**
     * (x, z) で胞子が風に流される水平の速さ(ブロック/tick。渦と揺らぎを除いた分)を {@code out} = {x, z} へ。戻り値はその場の風の強さ。
     * 漂う胞子の束({@link GpuSpores})は升ごとにこれを積んで「押された量」にする。
     */
    static double flow(double x, double z, double time, double[] out) {
        CanopyWind.velocity(x, z, time, WIND);
        double wx = WIND[0];
        double wz = WIND[1];
        double w = Math.sqrt(wx * wx + wz * wz);
        double wc = Math.min(w, 1.2);
        // 流される速さ: 凪(0.2)で毎秒 0.3 ブロック、0.5 で 0.9、0.8 で 2、突風のさなか(1.0)で 3.3、いちばん強い所(1.2)で 5 ブロック。
        double speed = 0.004 + 0.05 * wc + 0.11 * wc * wc * wc;
        out[0] = 0.0;
        out[1] = 0.0;
        if (w > 1.0E-6) {
            out[0] = wx / w * speed;
            out[1] = wz / w * speed;
        }
        return w;
    }

    /** 速度 {@code v} = {x, y, z}(ブロック/tick)を 1 tick 進める。位置は呼び手が足す。 */
    static void step(double x, double y, double z, double[] v, double time, double phase, RandomSource random) {
        double w = air(x, y, z, time, phase, AIR);
        double jitter = 0.0009 * (1.0 + 2.0 * Math.min(w, 1.2));
        v[0] += (AIR[0] - v[0]) * FOLLOW + (random.nextDouble() - 0.5) * jitter;
        v[1] += (AIR[1] - v[1]) * FOLLOW + (random.nextDouble() - 0.5) * jitter * 0.6;
        v[2] += (AIR[2] - v[2]) * FOLLOW + (random.nextDouble() - 0.5) * jitter;
    }

    /** 生まれたばかりの粒の速度を、その場の空気の速さにそろえる(止まった所から動き出すと、周りの粒とずれて見える)。 */
    static void matchAir(double x, double y, double z, double[] v, double time, double phase) {
        air(x, y, z, time, phase, AIR);
        v[0] = AIR[0];
        v[1] = AIR[1];
        v[2] = AIR[2];
    }

    /** (x, y, z) の空気の速さ(ブロック/tick)を {@code out} へ。戻り値はその場の風の強さ({@link CanopyWind#strength} の尺度)。 */
    private static double air(double x, double y, double z, double time, double phase, double[] out) {
        double w = flow(x, z, time, FLOW);
        double wc = Math.min(w, 1.2);
        double ux = FLOW[0];
        double uz = FLOW[1];
        // 大きい渦(風が強いほど速く回る)。
        double a = 0.003 + 0.03 * wc;
        double ex = a * Math.sin(0.37 * y + 0.23 * z + 0.043 * time + 1.3) * Math.cos(0.19 * x - 0.031 * time);
        double ey = 0.6 * a * Math.sin(0.29 * x + 0.17 * z - 0.037 * time + 2.1) * Math.cos(0.21 * y + 0.027 * time);
        double ez = a * Math.sin(0.31 * x + 0.41 * y - 0.039 * time + 0.7) * Math.cos(0.23 * z + 0.033 * time);
        // 小さい渦(突風のときだけ目立つ。粒ごとに少しずらす)。
        double b = 0.012 * wc;
        ex += b * Math.sin(1.1 * y + 0.9 * z + 0.21 * time + phase);
        ey += 0.7 * b * Math.sin(0.95 * x + 1.2 * z - 0.19 * time + phase * 1.3);
        ez += b * Math.sin(1.05 * x + 0.85 * y - 0.23 * time + phase * 0.7);
        out[0] = ux + ex;
        out[1] = SETTLE + ey;
        out[2] = uz + ez;
        return w;
    }

    private SporeDrift() {
    }
}
