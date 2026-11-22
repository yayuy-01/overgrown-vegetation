package com.yayuy.overgrownvegetation.entity.flyer;

import java.util.function.DoubleSupplier;

/**
 * 「止まっては飛ぶ」動き(2026-11-15、アクムネス段 1 で足した。トンボ型の部品)。Vault「飛ぶ型と漂う型の動き方(11-14)」のアクムネスの節:
 * <b>空中でピタッと止まり、次の狙い場所へ短く鋭く飛んで、また止まる</b>。止まる間は惰性より「止まる」を優先する。
 *
 * <ul>
 *   <li>{@link Phase#HOLD}: 止まり点(アンカー)に留まる。動いていたら {@link Params#stopDecel} で一気に殺し、着いたら速さ 0(風にも流されない)。
 *       わずかな揺れはアニメ(体の上下)に任せ、実体の位置は動かさない。</li>
 *   <li>{@link Phase#DART}: 行き先へまっすぐ。{@link Params#dartAccel} で加速し、{@link Params#dartMax} まで。止まれる距離
 *       (v² ÷ 2 × {@link Params#dartBrake})に入ったら減速して行き先で止まる。風は速さの割合だけ受ける(止まりぎわには効かない)。</li>
 * </ul>
 * 向き(体をどちらへ向けるか)は使う側が決める。行き先の選び方({@link #pickSpot})も部品として置く(世界は {@link Space} で渡す)。
 * net.minecraft を import しない素の Java(テスト場に収まらない距離を計算だけで確かめるため)。{@link FlightBody} の惰性の飛び方とは別の道で、
 * {@link SkyFlyer#ownFlight} から使う。
 */
public final class DartHover {

    public enum Phase { HOLD, DART }

    /** 種ごとの値。数値はどれも【仮・Claude】。 */
    public static final class Params {
        /** 鋭く飛ぶ速さの上限(ブロック/tick)・加速・減速(ブロック/tick²)。 */
        public double dartMax = 1.0D, dartAccel = 0.2D, dartBrake = 0.22D;
        /** 止まるときに速さを殺す割合(ブロック/tick²)。大きいほど「ピタッと」。 */
        public double stopDecel = 0.3D;
        /** 止まり点へ戻る強さ(ずれ × これ = 戻る速さ)と、その上限。 */
        public double holdGain = 0.4D, holdMaxSpeed = 0.15D;
        /** 着いたとみなす距離。 */
        public double arrive = 0.08D;
        /** 鋭く飛ぶのを諦めるまで(tick)。何かに阻まれたとき。 */
        public int dartGiveUp = 60;
        /** 風を受ける割合(飛んでいる間、速さ ÷ 上限 に掛ける)。 */
        public double windFactor = 0.5D;
    }

    public Phase phase = Phase.HOLD;
    /** 止まり点。 */
    public double ax = Double.NaN, ay, az;
    /** 行き先。 */
    public double tx, ty, tz;
    /** この tick の速さ(地面に対して)。{@link #step} の答え。 */
    public double vx, vy, vz;
    /** 今の段に入ってからの tick。 */
    public int phaseTicks;

    /** ここで止まる(今の速さは次の tick から {@link Params#stopDecel} で殺す)。 */
    public void holdAt(double x, double y, double z) {
        this.ax = x;
        this.ay = y;
        this.az = z;
        this.phase = Phase.HOLD;
        this.phaseTicks = 0;
    }

    /** (x, y, z) へ鋭く飛ぶ。 */
    public void dartTo(double x, double y, double z) {
        this.tx = x;
        this.ty = y;
        this.tz = z;
        this.phase = Phase.DART;
        this.phaseTicks = 0;
    }

    public boolean holding() {
        return this.phase == Phase.HOLD;
    }

    /** 止まっていて、速さが 0 になったか。 */
    public boolean settled() {
        return this.phase == Phase.HOLD && this.vx == 0.0D && this.vy == 0.0D && this.vz == 0.0D;
    }

    /** 今の速さ。 */
    public double speed() {
        return Math.sqrt(this.vx * this.vx + this.vy * this.vy + this.vz * this.vz);
    }

    /**
     * 1 tick 進める。位置を (x, y, z) として、この tick に動く量 (vx, vy, vz) を出す。
     *
     * @param blocked 前の tick にぶつかったか(飛んでいる途中なら、その場で止まる)
     */
    public void step(Params p, double x, double y, double z, double windX, double windZ, boolean blocked) {
        this.phaseTicks++;
        if (Double.isNaN(this.ax)) holdAt(x, y, z);
        if (this.phase == Phase.DART && (blocked && this.phaseTicks > 2 || this.phaseTicks > p.dartGiveUp)) {
            holdAt(x, y, z);
        }
        if (this.phase == Phase.DART) {
            double dx = this.tx - x, dy = this.ty - y, dz = this.tz - z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < p.arrive) {
                holdAt(this.tx, this.ty, this.tz);
            } else {
                double ux = dx / d, uy = dy / d, uz = dz / d;
                double along = this.vx * ux + this.vy * uy + this.vz * uz;
                double brakeCap = Math.sqrt(2.0D * p.dartBrake * d);
                double want = Math.min(p.dartMax, brakeCap);
                double s = along < want ? Math.min(want, Math.max(0.0D, along) + p.dartAccel) : Math.max(want, along - p.dartBrake);
                s = Math.min(s, d);   // 行き過ぎない
                double w = p.windFactor * Math.min(1.0D, s / p.dartMax);
                this.vx = ux * s + windX * w;
                this.vy = uy * s;
                this.vz = uz * s + windZ * w;
                return;
            }
        }
        // 止まる: 速いうちは止まり点を引きずってブレーキだけ(行き過ぎて戻らない = 止まった所で止まる)
        double cur = speed();
        if (cur > p.holdMaxSpeed) {
            double k = Math.max(0.0D, cur - p.stopDecel) / cur;
            this.vx *= k;
            this.vy *= k;
            this.vz *= k;
            this.ax = x + this.vx;
            this.ay = y + this.vy;
            this.az = z + this.vz;
            return;
        }
        // 遅くなったら、ずれを戻す速さへ寄せる。着いたら 0
        double dx = this.ax - x, dy = this.ay - y, dz = this.az - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < p.arrive * 0.5D && cur <= p.stopDecel) {
            this.vx = this.vy = this.vz = 0.0D;
            return;
        }
        double ws = Math.min(p.holdMaxSpeed, d * p.holdGain);
        double wx = d > 1e-9 ? dx / d * ws : 0.0D, wy = d > 1e-9 ? dy / d * ws : 0.0D, wz = d > 1e-9 ? dz / d * ws : 0.0D;
        if (ws >= d) {
            wx = dx;
            wy = dy;
            wz = dz;
        }
        double ex = wx - this.vx, ey = wy - this.vy, ez = wz - this.vz;
        double e = Math.sqrt(ex * ex + ey * ey + ez * ez);
        double k = e > p.stopDecel ? p.stopDecel / e : 1.0D;
        this.vx += ex * k;
        this.vy += ey * k;
        this.vz += ez * k;
    }

    // ------------------------------------------------------------------ 行き先の選び方

    /** 世界を見る口(使う側が渡す)。 */
    public interface Space {
        /** 体の芯が (x, y, z)(足元)に収まるか(読み込まれていない所は false)。 */
        boolean fits(double x, double y, double z);

        /** (x0..) から (x1..) へ体の芯の高さでまっすぐ行けるか。 */
        boolean clearPath(double x0, double y0, double z0, double x1, double y1, double z1);

        /** (x, y, z)(足元)から相手が見えるか。 */
        boolean canSee(double x, double y, double z);

        /** 相手がいないときの基準の高さ((x, z) の林冠の上。読めなければ NaN)。 */
        double floorAt(double x, double z);
    }

    /** 行き先の決まり。数値はどれも【仮・Claude】(高さの帯 8〜20 は決定 11-14)。 */
    public static final class SpotRules {
        /** 相手の足元からの高さの帯【決定 11-14: 8〜20】。 */
        public double aboveMin = 8.0D, aboveMax = 20.0D;
        /** 相手から水平に離れる距離。 */
        public double radiusMin = 5.0D, radiusMax = 14.0D;
        /** 1 回に飛ぶ長さ。遠い相手へはこの長さずつ寄る。 */
        public double hopMin = 4.0D, hopMax = 14.0D;
        /** 前に使った場所からこれより近い所は選ばない。 */
        public double reuseGap = 4.0D;
        /** 相手がいないとき: 家のまわりの半径・林冠からの高さ。 */
        public double idleRadius = 16.0D, idleAboveMin = 3.0D, idleAboveMax = 8.0D;
        /** 試す数。 */
        public int tries = 16;
    }

    /**
     * 次の止まり場所を選ぶ。相手がいるとき(hasTarget)は相手のまわりの高さの帯から、見える所を優先して。
     * 相手が遠いときは相手へ向かって 1 跳びぶん寄る所。いないときは家のまわりの林冠の少し上。
     * 前に使った場所(used、[x, y, z] の並び)と今いる所の近くは選ばない。
     *
     * @return [x, y, z, 見えるか(1/0)]。どこも選べなければ null
     */
    public static double[] pickSpot(SpotRules r, Space s, DoubleSupplier rnd, double x, double y, double z,
                                    boolean hasTarget, double px, double py, double pz,
                                    double homeX, double homeZ, double[] used, int usedCount) {
        double[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double hd = Math.sqrt((px - x) * (px - x) + (pz - z) * (pz - z));
        boolean approach = hasTarget && hd > r.radiusMax + r.hopMax;
        for (int i = 0; i < r.tries; i++) {
            double cx, cy, cz;
            if (approach) {
                // 遠い相手へ: 相手の方へ 1 跳び(±35 度)。高さは帯の中
                double a = Math.atan2(pz - z, px - x) + (rnd.getAsDouble() * 2.0D - 1.0D) * Math.toRadians(35.0D);
                double h = lerp(rnd.getAsDouble(), r.hopMin + (r.hopMax - r.hopMin) * 0.5D, r.hopMax);
                cx = x + Math.cos(a) * h;
                cz = z + Math.sin(a) * h;
                cy = py + lerp(rnd.getAsDouble(), r.aboveMin, r.aboveMax);
            } else if (hasTarget) {
                double a = rnd.getAsDouble() * Math.PI * 2.0D;
                double rr = lerp(rnd.getAsDouble(), r.radiusMin, r.radiusMax);
                cx = px + Math.cos(a) * rr;
                cz = pz + Math.sin(a) * rr;
                cy = py + lerp(rnd.getAsDouble(), r.aboveMin, r.aboveMax);
            } else {
                double a = rnd.getAsDouble() * Math.PI * 2.0D;
                double rr = Math.sqrt(rnd.getAsDouble()) * r.idleRadius;
                cx = homeX + Math.cos(a) * rr;
                cz = homeZ + Math.sin(a) * rr;
                double f = s.floorAt(cx, cz);
                if (Double.isNaN(f)) continue;
                cy = f + lerp(rnd.getAsDouble(), r.idleAboveMin, r.idleAboveMax);
            }
            // 1 跳びの長さに収める(遠すぎれば今の所から行き先の向きへ hopMax まで)
            double dx = cx - x, dy = cy - y, dz = cz - z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > r.hopMax) {
                double k = r.hopMax / d;
                cx = x + dx * k;
                cy = y + dy * k;
                cz = z + dz * k;
                d = r.hopMax;
            }
            if (d < r.hopMin * 0.5D) continue;
            if (near(cx, cy, cz, used, usedCount, r.reuseGap)) continue;
            if (!s.fits(cx, cy, cz) || !s.clearPath(x, y, z, cx, cy, cz)) continue;
            boolean see = hasTarget && s.canSee(cx, cy, cz);
            // 見える所を強く、帯の真ん中の高さ・ほどよい長さを少し優先
            double score = (see ? 100.0D : 0.0D) - Math.abs(d - (r.hopMin + r.hopMax) * 0.5D);
            if (hasTarget) score -= Math.abs(cy - py - (r.aboveMin + r.aboveMax) * 0.5D) * 0.5D;
            if (score > bestScore) {
                bestScore = score;
                best = new double[]{cx, cy, cz, see ? 1.0D : 0.0D};
            }
        }
        return best;
    }

    static boolean near(double x, double y, double z, double[] used, int count, double gap) {
        double g2 = gap * gap;
        for (int i = 0; i < count; i++) {
            double dx = used[i * 3] - x, dy = used[i * 3 + 1] - y, dz = used[i * 3 + 2] - z;
            if (dx * dx + dy * dy + dz * dz < g2) return true;
        }
        return false;
    }

    static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}
