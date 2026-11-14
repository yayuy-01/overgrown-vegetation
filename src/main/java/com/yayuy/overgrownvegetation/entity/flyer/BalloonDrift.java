package com.yayuy.overgrownvegetation.entity.flyer;

import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;

/**
 * 「漂う型」(風任せの熱気球)の動き(2026-11-15、ペディキュラ段 1)。net.minecraft を import しない素の Java —— 計算だけで確かめるため。
 *
 * <p>Vault「飛ぶ型と漂う型の動き方(11-14)」のペディキュラ【決定】: 自分では高さしか変えられず、風に流されるだけ。高さごとに風が違うので、
 * 上下して行き先を選ぶ。高い所(地面 +40〜80)。熱気球なので昼は高く、夜は冷えて低く。林冠には止まらない(休む動きは無い)。
 *
 * <h2>高さの風【仮・Claude 11-15】</h2>
 * 今の卓越風({@link PrevailingWind})には高さの違いが無い。世界の風(胞子の場・雲の流れ)は変えずに、<b>漂う型が読むときだけ</b>
 * 高さで向きと強さを変える({@link #layerWind}):
 * <ul>
 *   <li>向き: 卓越風を ±{@link #LAYER_AMP_DEG} 度回す。回す角は高さに沿って {@link #LAYER_WAVE} ブロックで一巡する波で、波の位置は
 *       {@link #LAYER_PHASE_DAYS} 日でゆっくりずれる(どの高さがどちらへ流れるかは日によって変わる)。</li>
 *   <li>強さ: 海面 {@link #BASE_Y} から 1 ブロックごとに {@link #GAIN_PER_BLOCK} 倍強くなる(上限 {@link #GAIN_MAX} 倍)。</li>
 * </ul>
 * 帯(地面 +40〜80)の中で 40 ブロック動けば、向きは最大でおよそ 120 度変わる。
 */
public final class BalloonDrift {

    // ---- 高さの風【仮】 ----
    public static final double LAYER_AMP_DEG = 70.0D;
    public static final double LAYER_WAVE = 60.0D;
    public static final double LAYER_PHASE_DAYS = 2.5D;
    public static final double BASE_Y = 64.0D;
    public static final double GAIN_PER_BLOCK = 0.008D, GAIN_MAX = 1.8D;

    // ---- 高さの帯(地面から)【決定 11-14: 地面 +40〜80・昼は高く夜は低く。昼と夜の分け目は仮】 ----
    public static final double DAY_LOW = 55.0D, DAY_HIGH = 80.0D, NIGHT_LOW = 40.0D, NIGHT_HIGH = 55.0D;

    // ---- 高さ選び【仮】 ----
    /** 帯の中で比べる高さの数。 */
    public static final int CANDIDATES = 9;
    /** 今の狙いの高さを替えるのは、行き先へ向かう速さがこれ(ブロック/tick)より良くなるときだけ(行ったり来たりしない)。 */
    public static final double SWITCH_GAIN = 0.004D;

    /** 横の速さが風に寄る割合(1 tick あたり)【仮】。気球の重さの惰性 = 約 25 tick で風に乗る。 */
    public static final double H_FOLLOW = 0.04D;

    private BalloonDrift() {
    }

    /** 動きの状態(地面に対する速さ、ブロック/tick)。 */
    public static final class State {
        public double vx, vy, vz;
    }

    /**
     * 高さ y の風(ブロック/tick)を out[0..1] へ。
     *
     * @param seed    風の種({@code WindWeatherData#windSeed})
     * @param dayTime 暦の tick
     * @param bx      卓越風(高さに依らない値。ブロック/tick)
     */
    public static void layerWind(long seed, long dayTime, double y, double bx, double bz, double[] out) {
        double off = layerAngle(seed, dayTime, y);
        double c = Math.cos(off), s = Math.sin(off);
        double gain = FlightBody.clamp(1.0D + (y - BASE_Y) * GAIN_PER_BLOCK, 1.0D, GAIN_MAX);
        out[0] = (bx * c - bz * s) * gain;
        out[1] = (bx * s + bz * c) * gain;
    }

    /** 高さ y で卓越風を回す角(ラジアン)。 */
    public static double layerAngle(long seed, long dayTime, double y) {
        double days = dayTime / (double) PrevailingWind.DAY;
        double phase = 2.0D * Math.PI * (PrevailingWind.unit(PrevailingWind.hash(seed, 0xBA11L)) + days / LAYER_PHASE_DAYS);
        return Math.toRadians(LAYER_AMP_DEG) * Math.sin(2.0D * Math.PI * (y - BASE_Y) / LAYER_WAVE + phase);
    }

    /** 暖かさ(0 = 夜 〜 1 = 昼)。日の出(暦 0)と日の入り(12000)の前後 2 時間ほどで移る。 */
    public static double warmth(long dayTime) {
        double tod = Math.floorMod(dayTime, (long) PrevailingWind.DAY);
        return FlightBody.clamp(0.5D + 1.5D * Math.sin(2.0D * Math.PI * tod / PrevailingWind.DAY), 0.0D, 1.0D);
    }

    /** 帯の下(地面からの高さ)。 */
    public static double bandLow(double warmth) {
        return NIGHT_LOW + (DAY_LOW - NIGHT_LOW) * warmth;
    }

    /** 帯の上(地面からの高さ)。 */
    public static double bandHigh(double warmth) {
        return NIGHT_HIGH + (DAY_HIGH - NIGHT_HIGH) * warmth;
    }

    /**
     * 帯 [low, high] の中から、行き先の向き (gx, gz)(単位ベクトル。0, 0 = 行き先なし)へいちばん速く流れる高さを選ぶ。
     * 今の狙い cur が帯の中にあり、選んだ高さとの差が {@link #SWITCH_GAIN} 以内なら cur のまま。行き先が無ければ帯の真ん中(cur が帯の中ならそのまま)。
     */
    public static double chooseHeight(long seed, long dayTime, double bx, double bz, double low, double high, double cur, double gx, double gz) {
        boolean curIn = !Double.isNaN(cur) && cur >= low && cur <= high;
        if (gx == 0.0D && gz == 0.0D) return curIn ? cur : (low + high) * 0.5D;
        double[] w = new double[2];
        double best = Double.NaN, bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < CANDIDATES; i++) {
            double y = low + (high - low) * i / (CANDIDATES - 1);
            layerWind(seed, dayTime, y, bx, bz, w);
            double sc = w[0] * gx + w[1] * gz;
            if (sc > bestScore) {
                bestScore = sc;
                best = y;
            }
        }
        if (curIn) {
            layerWind(seed, dayTime, cur, bx, bz, w);
            if (w[0] * gx + w[1] * gz >= bestScore - SWITCH_GAIN) return cur;
        }
        return best;
    }

    /**
     * 1 tick 進める。横は風(× {@link FlightBody.Params#windFactor})へ {@link #H_FOLLOW} で寄り、上下は狙いの高さへ
     * {@link FlightBody.Params#climbGain}・上限 {@code climbMax / sinkMax}・変わる速さ {@code vyAccel} で寄る(ヴェロフィルと同じ値の口)。
     *
     * @param dy 狙いの高さ − 今の高さ(ブロック)
     */
    public static void step(FlightBody.Params p, State s, double windX, double windZ, double dy) {
        s.vx += (windX * p.windFactor - s.vx) * H_FOLLOW;
        s.vz += (windZ * p.windFactor - s.vz) * H_FOLLOW;
        double vyWant = FlightBody.clamp(dy * p.climbGain, -p.sinkMax, p.climbMax);
        s.vy += FlightBody.clamp(vyWant - s.vy, -p.vyAccel, p.vyAccel);
    }
}
