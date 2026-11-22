package com.yayuy.overgrownvegetation.entity.flyer;

/**
 * ゆるくまとまる群れ(2026-11-15、セミナリア段 1)。V 字({@link VFormation})を組まない種の後続が使う。素の Java。
 *
 * <p>【決定 11-14】セミナリアは数体で飛ぶが V 字の隊列は組まない。<b>ゆるくまとまる程度は仮決め</b>(Claude):
 * 後続 k の居場所は、先頭のまわりの円盤の中に黄金角で散らした点(先頭の向きで回す。半径 {@link #RING_BASE} + {@link #RING_STEP}·√k、
 * 少し後ろ寄り {@link #BACK_BIAS})。そこへ各々のゆっくりした揺れ(±{@link #WANDER} ブロック)を足すので、形は決まらず群れがほどけたり寄ったりする。
 * 追い方は {@link VFormation#follow} と同じ(自分の居場所の少し先を目指して向きを決め、前後のずれは速さで直す)。
 */
public final class LooseFormation {

    /** 居場所の円盤: 1 体目の半径・増え方・後ろ寄りの量・上下のばらけ(ブロック)【仮】。 */
    public static final double RING_BASE = 2.5D, RING_STEP = 2.2D, BACK_BIAS = 1.5D, UP_SPREAD = 0.8D;
    /** 各々の居場所のゆっくりした揺れの幅(ブロック)と周期(tick)【仮】。居場所どうしの間(最も近くて先頭と 1 番の 約 3.7)より十分小さく。 */
    public static final double WANDER = 1.0D, WANDER_PERIOD = 160.0D;
    /** 居場所の少し先を目指す長さ(ブロック)【仮】。V 字(12)より遅い種向けに短い。 */
    public static final double LOOKAHEAD = 6.0D;
    /** 前後のずれを速さにする係数・上げ下げの幅【仮】。 */
    public static final double SPEED_GAIN = 0.04D, SPEED_DOWN = 0.12D, SPEED_UP = 0.2D;
    /** これより離れたら居場所へまっすぐ追いつく(ブロック)【仮】。 */
    public static final double CATCH_UP = 20.0D;

    private static final double GOLDEN = Math.toRadians(137.507764D);

    private LooseFormation() {
    }

    /**
     * 並び k の [後ろ, 左, 上](ブロック)。phase は個体ごとの揺れの位相(0〜2π)、t は時刻(tick)。
     */
    public static void slot(int k, double phase, double t, double[] out) {
        if (k <= 0) {
            out[0] = out[1] = out[2] = 0.0D;
            return;
        }
        double a = k * GOLDEN;
        double r = RING_BASE + RING_STEP * Math.sqrt(k);
        double w = 2.0D * Math.PI * t / WANDER_PERIOD;
        out[0] = BACK_BIAS + r * Math.cos(a) + WANDER * Math.sin(w + phase);
        out[1] = r * Math.sin(a) + WANDER * Math.sin(w * 0.71D + phase * 1.7D);
        out[2] = UP_SPREAD * (((k % 3) - 1) + 0.5D * Math.sin(w * 0.53D + phase * 2.3D));
    }

    /**
     * 後続への指示を出す(答えは {@link VFormation.Command} を使い回す)。
     *
     * @param off {@link #slot} の答え([後ろ, 左, 上])
     */
    public static void follow(double lx, double ly, double lz, double lh, double ls, double lTurnDeg, double[] off,
                              double mx, double mz, double maxSpeed, VFormation.Command out) {
        double h = Math.toRadians(lh);
        double fx = -Math.sin(h), fz = Math.cos(h);
        double leftX = Math.cos(h), leftZ = Math.sin(h);
        double sx = lx - fx * off[0] + leftX * off[1];
        double sz = lz - fz * off[0] + leftZ * off[1];
        out.slotX = sx;
        out.slotZ = sz;
        out.y = ly + off[2];
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
        out.speed = ls + Math.toRadians(lTurnDeg) * off[1] + FlightBody.clamp(out.along * SPEED_GAIN, -SPEED_DOWN, SPEED_UP);
    }
}
