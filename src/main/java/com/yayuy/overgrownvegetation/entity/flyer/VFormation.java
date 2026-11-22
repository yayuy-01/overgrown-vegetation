package com.yayuy.overgrownvegetation.entity.flyer;

/**
 * V 字の隊列(2026-11-15、ヴェロフィル段 1)。素の Java(テストで隊列が収まるかを計算だけで回す)。
 *
 * <p>並び: 0 = 先頭。1, 3, 5 … は先頭の左、2, 4, 6 … は右。列の k 番目(左右それぞれ 1, 2, 3 …)は先頭から
 * 横へ {@link #LATERAL} × k・後ろへ {@link #BACK} × k・上へ {@link #UP} × k【仮】。位置は先頭の向きで回すので、曲がるときは形ごと回る。
 *
 * <p>後続は自分の位置そのものを追わない(退避中の旧版で、近い点を追うと旋回の半径より内側を指して輪を描くだけになった)。
 * 先頭と平行に、自分の位置の {@link #LOOKAHEAD} ブロック先を目指して向きを決め、前後のずれは速さで直す。
 */
public final class VFormation {

    /** 横・後ろ・上の間隔(ブロック)【仮】。翼幅 5.8 に対して横 4.5(翼の先が少し重なる。雁の並び)。 */
    public static final double LATERAL = 4.5D, BACK = 2.5D, UP = 0.3D;
    /** 後続が目指す点の先取り(ブロック)【仮】。 */
    public static final double LOOKAHEAD = 12.0D;
    /** 前後のずれを速さにする係数・上げ下げの幅【仮】。 */
    public static final double SPEED_GAIN = 0.05D, SPEED_DOWN = 0.2D, SPEED_UP = 0.3D;
    /** これより離れたら隊列を忘れて自分の位置へまっすぐ追いつく(ブロック)【仮】。 */
    public static final double CATCH_UP = 30.0D;

    private VFormation() {
    }

    /** 並び k の [後ろ, 左, 上](ブロック)。 */
    public static void slot(int k, double[] out) {
        if (k <= 0) {
            out[0] = out[1] = out[2] = 0.0D;
            return;
        }
        int rank = (k + 1) / 2;
        double side = (k & 1) == 1 ? 1.0D : -1.0D;
        out[0] = BACK * rank;
        out[1] = LATERAL * rank * side;
        out[2] = UP * rank;
    }

    /** 後続への指示。使い回してよい。 */
    public static final class Command {
        /** 向かう向き(度)・出す速さ・目指す高さ。 */
        public double heading, speed, y;
        /** 自分の位置(隊列の中の点)と、そこまでの水平の距離。 */
        public double slotX, slotZ, dist;
        /** 前後のずれ(+ = 遅れている)。 */
        public double along;
    }

    /**
     * 後続 k への指示を出す。
     *
     * @param lh 先頭の向き(度)、ls 先頭の速さ(空気に対して)、lTurnDeg 先頭の回る速さ(度/tick)。曲がっている間は外側ほど速く飛ぶ(隊列ごと回る)見込みを足す
     */
    public static void follow(double lx, double ly, double lz, double lh, double ls, double lTurnDeg, int k,
                              double mx, double mz, double maxSpeed, double[] tmp, Command out) {
        slot(k, tmp);
        double h = Math.toRadians(lh);
        double fx = -Math.sin(h), fz = Math.cos(h);
        double leftX = Math.cos(h), leftZ = Math.sin(h);
        double sx = lx - fx * tmp[0] + leftX * tmp[1];
        double sz = lz - fz * tmp[0] + leftZ * tmp[1];
        out.slotX = sx;
        out.slotZ = sz;
        out.y = ly + tmp[2];
        double ex = sx - mx, ez = sz - mz;
        out.dist = Math.sqrt(ex * ex + ez * ez);
        out.along = ex * fx + ez * fz;
        if (out.dist > CATCH_UP) {
            out.heading = FlightBody.yawOf(ex, ez);
            out.speed = maxSpeed;
            return;
        }
        double ax = sx + fx * LOOKAHEAD - mx, az = sz + fz * LOOKAHEAD - mz;
        out.heading = FlightBody.yawOf(ax, az);
        out.speed = ls + Math.toRadians(lTurnDeg) * tmp[1] + FlightBody.clamp(out.along * SPEED_GAIN, -SPEED_DOWN, SPEED_UP);
    }
}
