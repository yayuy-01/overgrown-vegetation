package com.yayuy.overgrownvegetation.entity.lizard;

import net.minecraft.util.Mth;

/**
 * トカゲ型の背骨の左右のくねりと、止まっている間の見回し(2026-11-15。クライアントだけ。値は全部【仮・Claude】)。
 *
 * <h2>くねり</h2>
 * 這う爬虫類は対角の脚(左前 + 右後ろ)を一緒に前へ出し、そのとき胴は「前へ出た脚の肩・腰が前へ出る」ように S 字に曲がる。
 * 左前が右前より前にあるとき、肩の帯は右へ回り(頭側が右)、腰の帯は逆に回る(尾側が右)= 胴の右側がへこむ。
 * ここでは<b>実際の足の位置</b>(足の置き場が決めたもの)から曲げの量を出すので、踏み替えと胴の振れが必ず合う。
 * <ul>
 *   <li>{@code d} = ((左前 − 右前) + (右後ろ − 左後ろ)) ÷ 2(足の前後のずれ、ブロック)。曲げ A = d × {@link #GAIN} 度(±{@link #MAX})。</li>
 *   <li>胸の頭側の左への角 = −A、腰の尾側の左への角 = −A(同じ向きにへこむ)。</li>
 *   <li>尾は腰の振れを {@link #TAIL_DELAY} tick ずつ遅らせて伝え、先ほど {@link #TAIL_GROW} ずつ大きく振る(進む波)。</li>
 *   <li>首は胸の振れの {@link #NECK_CANCEL} を打ち消す(頭は前を向いたまま)。</li>
 * </ul>
 *
 * <h2>見回し</h2>
 * 止まっている間({@code paused})は首を {@link #LOOK_LIFT} 度上げ、頭を左右へ ±{@link #LOOK_SCAN} 度ゆっくり振る({@link #LOOK_PERIOD} tick で 1 往復)。
 * ピリムネスでは使わない(11-15 ユーザー決定でアニメ look_around を残した)。アニメの無い種と切り替え用(LizardRunner#lookByAnimation が false の種)。
 * 角はどれも「その骨の先が体に対して左へ向く角(度)」。骨への足し方は描画({@code client/lizard/SpineWaveRig})。
 */
public final class SpineWave {

    /** 足の前後のずれ 1 ブロックあたりの曲げ(度)・曲げの上限(度)【仮】。 */
    public static final double GAIN = 11.0D, MAX = 13.0D;
    /** 曲げが目標へ寄る割合(1 tick)【仮】。 */
    static final double FOLLOW = 0.45D;
    /** 尾の節ごとの遅れ(tick)と、節ごとに振れを大きくする割合【仮】。 */
    static final int TAIL_DELAY = 2;
    static final double TAIL_GROW = 0.3D;
    /** 首が胸の振れを打ち消す割合【仮】。 */
    static final double NECK_CANCEL = 0.85D;
    /** 見回し: 首を上げる角・頭を振る角(度)・1 往復の tick・効きが寄る割合【仮】。 */
    public static final double LOOK_LIFT = 14.0D, LOOK_SCAN = 28.0D;
    static final int LOOK_PERIOD = 46;
    static final double LOOK_FOLLOW = 0.18D;

    /** 尾の節の数(この型は 5 まで)。 */
    public static final int TAILS = 5;
    private static final int HIST = 16;

    /** 出す値の番号。 */
    public static final int CHEST = 0, HIP = 1, NECK = 2, LOOK_PITCH = 3, LOOK_YAW = 4, TAIL0 = 5;
    private static final int N = TAIL0 + TAILS;

    private final double[] now = new double[N], prev = new double[N];
    private final double[] hist = new double[HIST];
    private int head;
    private double bend, look;

    /**
     * 1 tick 進める。
     *
     * @param legFwd 足の基準の置き場からの前へのずれ(左前・右前・左後ろ・右後ろ、ブロック、体格 1)。足を置いていない間は null(曲げは 0 へ戻る)
     * @param speed  前へ進む速さ(ブロック/tick、体格 1)
     * @param paused 止まって見回しているか
     * @param tick   個体の tick(見回しの位相)
     */
    public void tick(double[] legFwd, double speed, boolean paused, int tick) {
        System.arraycopy(this.now, 0, this.prev, 0, N);
        double target = 0.0D;
        if (legFwd != null && speed > 0.01D) {
            double d = ((legFwd[0] - legFwd[1]) + (legFwd[3] - legFwd[2])) * 0.5D;
            target = Mth.clamp(d * GAIN, -MAX, MAX);
        }
        this.bend += (target - this.bend) * FOLLOW;
        this.head = (this.head + 1) % HIST;
        this.hist[this.head] = this.bend;
        this.now[CHEST] = -this.bend;
        this.now[HIP] = -this.bend;
        this.now[NECK] = this.bend * NECK_CANCEL;   // 胸の頭側が −A 左へ向いた分を、首で +A × 0.85 戻す
        for (int i = 0; i < TAILS; i++) {
            this.now[TAIL0 + i] = -past((i + 1) * TAIL_DELAY) * (1.0D + TAIL_GROW * (i + 1));
        }
        this.look += ((paused ? 1.0D : 0.0D) - this.look) * LOOK_FOLLOW;
        this.now[LOOK_PITCH] = LOOK_LIFT * this.look;
        this.now[LOOK_YAW] = LOOK_SCAN * this.look * Math.sin(tick * Math.PI * 2.0D / LOOK_PERIOD);
    }

    private double past(int ticks) {
        return this.hist[Math.floorMod(this.head - Math.min(ticks, HIST - 1), HIST)];
    }

    /** 値(度)。描く瞬間へ補間する。 */
    public double get(int which, float pt) {
        return Mth.lerp(pt, this.prev[which], this.now[which]);
    }

    /** 見回しの効き(0〜1)。 */
    public double lookWeight() {
        return this.look;
    }
}
