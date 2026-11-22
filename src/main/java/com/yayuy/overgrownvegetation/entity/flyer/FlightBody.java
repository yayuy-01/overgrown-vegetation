package com.yayuy.overgrownvegetation.entity.flyer;

/**
 * 「飛ぶ型」の体の動き(2026-11-15、ヴェロフィル段 1。後でセミナリア・アクムネス・ペディキュラにも使う)。
 *
 * <p>Vault「飛ぶ型と漂う型の動き方(11-14)」の共通の土台【決定】:
 * <ul>
 *   <li><b>惰性</b>: 向きは 1 tick あたりの上限までしか曲がらない(回る速さも急には変わらない)。速さは機首の向きへだけ入り、横へは滑らない。
 *       曲がるときは弧を描く(半径 = 速さ ÷ 回る速さ)。目一杯曲がっている間は少し失速する。</li>
 *   <li><b>風</b>: 地面に対する速さ = 空気に対する速さ + 風。風上へは遅く、風下へは速い。</li>
 *   <li><b>体の傾き</b>: 曲がり方から出す(描画はクライアントが同じ式 {@link #bankDeg} で回す。アニメではない)。</li>
 * </ul>
 * 向きは Minecraft の yaw(度。0 = 南 +z、90 = 西 −x)。前 = (−sin, cos)、左 = (cos, sin)。
 * net.minecraft を import しない素の Java —— テスト場に収まらない旋回(半径 10 ブロック級)を計算だけで確かめるため。
 */
public final class FlightBody {

    /** 種ごとの値。数値はどれも【仮・Claude】(巡航 0.7 はユーザー決定 11-15)。 */
    public static final class Params {
        /** 巡航の速さ・下限・上限(ブロック/tick、空気に対して)。 */
        public double cruise = 0.7D, minSpeed = 0.25D, maxSpeed = 0.95D;
        /** 速さの上げ下げ(1 tick あたり)。 */
        public double accel = 0.015D, decel = 0.025D;
        /** 巡航の速さでの回る速さの上限(度/tick)。遅いほど小回りが利く(横の加速の上限が同じ)が、{@link #maxTurnCapDeg} まで。 */
        public double maxTurnDeg = 4.0D, maxTurnCapDeg = 9.0D;
        /** 回る速さの変わる速さ(度/tick²)= ロールの入り方の惰性。 */
        public double turnAccelDeg = 0.6D;
        /** 向きのずれを回る速さにする係数(1/tick)。 */
        public double turnGain = 0.12D;
        /** 昇り・降りの上限(ブロック/tick)と、上下の速さの変わる速さ。 */
        public double climbMax = 0.15D, sinkMax = 0.10D, vyAccel = 0.012D;
        /** 高さのずれを上下の速さにする係数。 */
        public double climbGain = 0.08D;
        /** 目一杯曲がっている間の失速(巡航に対する割合)。 */
        public double stallLoss = 0.06D;
        /** 昇る間に速さを削る割合(上下の速さに掛ける)。 */
        public double climbCost = 0.04D;
        /**
         * 風への任せ具合(2026-11-15 セミナリアで足した): 卓越風({@code FlyerWind}、強さ 1 = 0.1 ブロック/tick)に掛ける倍率。
         * 1 = ヴェロフィル(既定)。軽い種ほど大きく、巡航と同じくらいの風なら「風上へはほとんど進めない」。
         */
        public double windFactor = 1.0D;
        /**
         * ひらひら揺れる(2026-11-15 セミナリアで足した。{@code SkyFlyer} が飛ぶ間の指示に足す): 向きの揺れの幅(度)・高さの揺れの幅(ブロック)・
         * 揺れのおおよその周期(tick)。0 = 揺れない(既定)。
         */
        public double flutterDeg = 0.0D, flutterBob = 0.0D, flutterPeriod = 30.0D;
    }

    /** 体の傾きの式の重さ(傾き = atan(速さ × 回る速さ[rad] ÷ これ))。巡航 0.7・4 度/tick で約 35 度。 */
    public static final double BANK_G = 0.07D;
    /** 体の傾きの上限(度)。 */
    public static final double BANK_MAX = 55.0D;

    /** 向き(度、Minecraft の yaw)。 */
    public double heading;
    /** 空気に対する前への速さ(ブロック/tick)。 */
    public double speed;
    /** 上下の速さ(ブロック/tick)。 */
    public double vy;
    /** 回る速さ(度/tick、+ = yaw が増える向き)。 */
    public double turnRate;
    /** この tick の地面に対する速さ(風込み)。{@link #step} の答え。 */
    public double vx, vz;

    /** 今の速さで曲がれる上限(度/tick)。 */
    public double maxTurn(Params p) {
        // 巡航の 0.75 倍より速い間は巡航の上限のまま(目一杯曲がって少し失速しても半径が縮まない)。それより遅いと小回りが利く
        double s = Math.max(Math.min(this.speed, p.cruise * 0.75D), p.minSpeed);
        return Math.min(p.maxTurnCapDeg, p.maxTurnDeg * p.cruise * 0.75D / s);
    }

    /**
     * 1 tick 進める。
     *
     * @param desiredHeading 向かいたい向き(度)
     * @param speedCmd       出したい速さ(空気に対して。下限・上限に丸める)
     * @param dy             目指す高さ − 今の高さ(ブロック)
     * @param windX          風(ブロック/tick)
     */
    public void step(Params p, double desiredHeading, double speedCmd, double dy, double windX, double windZ) {
        // 向き: ずれに比例した回る速さを、上限と「回る速さの変わる速さ」で丸める(急にはロールに入れない)
        double max = maxTurn(p);
        double err = wrapDeg(desiredHeading - this.heading);
        double want = clamp(err * p.turnGain, -max, max);
        this.turnRate += clamp(want - this.turnRate, -p.turnAccelDeg, p.turnAccelDeg);
        this.turnRate = clamp(this.turnRate, -max, max);
        this.heading = wrapDeg(this.heading + this.turnRate);

        // 上下
        double vyWant = clamp(dy * p.climbGain, -p.sinkMax, p.climbMax);
        this.vy += clamp(vyWant - this.vy, -p.vyAccel, p.vyAccel);

        // 速さ: 目一杯曲がっている間は失速、昇る間は削れる
        double target = clamp(speedCmd, p.minSpeed, p.maxSpeed);
        double hard = Math.abs(this.turnRate) / max;
        if (hard > 0.5D) target -= p.cruise * p.stallLoss * (hard - 0.5D) * 2.0D;
        double d = target - this.speed;
        this.speed += d > 0 ? Math.min(d, p.accel) : Math.max(d, -p.decel);
        if (this.vy > 0) this.speed -= this.vy * p.climbCost;
        this.speed = clamp(this.speed, p.minSpeed * 0.5D, p.maxSpeed);

        double h = Math.toRadians(this.heading);
        this.vx = -Math.sin(h) * this.speed + windX * p.windFactor;
        this.vz = Math.cos(h) * this.speed + windZ * p.windFactor;
    }

    /** 体の傾き(度、+ = yaw が増える向きへ曲がるとき)。クライアントも同じ式。 */
    public static double bankDeg(double speed, double turnRateDeg) {
        double b = Math.toDegrees(Math.atan(speed * Math.toRadians(turnRateDeg) / BANK_G));
        return clamp(b, -BANK_MAX, BANK_MAX);
    }

    /** (dx, dz) の向きを Minecraft の yaw(度)で。 */
    public static double yawOf(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static double wrapDeg(double a) {
        a %= 360.0D;
        if (a >= 180.0D) a -= 360.0D;
        if (a < -180.0D) a += 360.0D;
        return a;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
