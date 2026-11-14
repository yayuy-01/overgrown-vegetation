package com.yayuy.overgrownvegetation.client.voice;

import java.util.Random;

/**
 * 声を「声帯が植物につぶされて、かすれた・つぶれた声」にする加工の芯(2026-11-14)。<b>Minecraft を import しない</b>
 * (試聴用の {@code tools/g1-sound/HoarseVoice.java} と並べて素の javac で比べるため)。
 *
 * <p>{@code HoarseVoice} の {@code hoarse}・{@code crushed}・{@code breaks} を、塊ごとに流して処理できる形に移した。
 * フィルター・雑音・途切れの位置は状態として持ち越すので、塊の大きさをどう切っても出力は同じ。
 * {@code HoarseVoice} は音全体を見てから決める値が 3 つある(つぶれの前の山・雑音の RMS・途切れの位置)。
 * それは {@link #prepare} に「先読みした分」を渡して先に決める(短い声なら先読みで末尾まで届くので試聴用と同じ値になる)。
 *
 * <h2>版</h2>
 * <ul>
 *     <li>0 = 加工なし(聞き比べ用)</li>
 *     <li>1 = かすれ: hoarse(rough 90 Hz・量 0.75・息 0.9・seed 11)</li>
 *     <li>2 = つぶれ: hoarse(crushed(帯・押し込み 3.5), 70 Hz・0.8・0.7・seed 12)</li>
 *     <li>3 = つぶれ + 途切れ: 2 に breaks(3 回・seed 5)</li>
 * </ul>
 * 数値は {@code HoarseVoice#main} の 3 つの版のまま(ユーザーが試聴して選ぶ前の値。どれも【仮】)。
 * 強さ {@code strength} は 1 で試聴用と同じ。0 に向けて、かすれの量・息・押し込みが弱まる(途切れの回数は変えない)。
 */
public final class VoiceCrusher {

    public static final int MODE_DRY = 0;
    public static final int MODE_HOARSE = 1;
    public static final int MODE_CRUSHED = 2;
    public static final int MODE_CRUSHED_BREAKS = 3;

    /** 中型の声の帯(試聴用の牛・豚・ゾンビ)。 */
    public static final double MID_LO = 350, MID_HI = 2600;
    /** 小型の声の帯(試聴用の鶏)。 */
    public static final double SMALL_LO = 700, SMALL_HI = 4200;

    private final double sr;
    private final int mode;
    private final double drive;
    private final double amount;
    private final double breath;
    private final long seed;
    private final double rough;

    // つぶれ(高域通過 → 低域通過 → tanh → 低域通過)
    private final Biquad crushHp, crushLp, crushPost;
    // かすれ(ゆっくりした雑音・声の大きさ・息)
    private final Random roughRandom, breathRandom;
    private final Biquad roughLp, envLp1, envLp2, breathBp;

    /** つぶれの前の山(試聴用は音全体の山)。{@link #prepare} で決める。 */
    private double crushPeak = 1;
    /** ゆっくりした雑音を RMS 1 に揃える倍率(試聴用の {@code unit})。{@link #prepare} で決める。 */
    private double roughUnit = 1;
    /** 途切れの谷: 中心と半幅(サンプル)。 */
    private long[] breakCenter = new long[0];
    private long[] breakHalf = new long[0];
    /** これまでに処理したサンプル数(途切れの位置の基準)。 */
    private long pos;

    /**
     * @param sampleRate 元の音のサンプリング周波数
     * @param mode       0〜3(上の版)
     * @param lo         通す帯の下(中型 350・小型 700)
     * @param hi         通す帯の上(中型 2600・小型 4200)
     * @param strength   1 = 試聴用と同じ。0〜1.5 くらい
     */
    public VoiceCrusher(double sampleRate, int mode, double lo, double hi, double strength) {
        this.sr = sampleRate;
        this.mode = Math.max(0, Math.min(3, mode));
        double s = Math.max(0, strength);
        boolean crush = this.mode >= MODE_CRUSHED;
        this.rough = crush ? 70 : 90;
        this.amount = Math.min(1, (crush ? 0.8 : 0.75) * s);
        this.breath = (crush ? 0.7 : 0.9) * s;
        this.seed = crush ? 12 : 11;
        this.drive = 1 + (3.5 - 1) * s;
        this.crushHp = new Biquad(Biquad.HP, lo, 0.707, sampleRate);
        this.crushLp = new Biquad(Biquad.LP, hi, 0.707, sampleRate);
        this.crushPost = new Biquad(Biquad.LP, hi * 1.3, 0.707, sampleRate);
        this.roughRandom = new Random(this.seed);
        this.breathRandom = new Random(this.seed + 1);
        this.roughLp = new Biquad(Biquad.LP, this.rough, 0.707, sampleRate);
        this.envLp1 = new Biquad(Biquad.LP, 25, 0.707, sampleRate);
        this.envLp2 = new Biquad(Biquad.LP, 25, 0.707, sampleRate);
        this.breathBp = new Biquad(Biquad.BP, 2800, 0.6, sampleRate);
    }

    public int mode() {
        return this.mode;
    }

    /**
     * 音全体を見て決める値を、先読みした分 {@code head}(モノラル・-1〜1)から決める。最初の {@link #process} の前に 1 回だけ呼ぶ。
     *
     * @param head     先読みした分(短い声なら全部)
     * @param complete 先読みで末尾まで届いたか(届いていれば途切れを全体の 15〜85% に置く。届かなければ先読みの範囲に置く)
     */
    public void prepare(float[] head, int length, boolean complete) {
        if (this.mode >= MODE_CRUSHED) {
            Biquad hp = this.crushHp.copyFresh(), lp = this.crushLp.copyFresh();
            double p = 0;
            for (int i = 0; i < length; i++) {
                p = Math.max(p, Math.abs((float) lp.step((float) hp.step(head[i]))));
            }
            this.crushPeak = p > 1e-6 ? p : 1;
        }
        if (this.mode >= MODE_HOARSE) {
            // 試聴用の unit(lp(noise(n, seed), rough)): 同じ乱数・同じフィルターで同じ長さを作って RMS を測る。
            Random r = new Random(this.seed);
            Biquad lp = this.roughLp.copyFresh();
            double e = 0;
            int n = Math.max(1, length);
            for (int i = 0; i < n; i++) {
                float v = (float) lp.step((float) (r.nextDouble() * 2 - 1));
                e += v * v;
            }
            double rms = Math.sqrt(e / n);
            this.roughUnit = 1 / Math.max(1e-9, rms);
        }
        if (this.mode >= MODE_CRUSHED_BREAKS) {
            Random r = new Random(5);
            int count = 3;
            this.breakCenter = new long[count];
            this.breakHalf = new long[count];
            for (int k = 0; k < count; k++) {
                this.breakCenter[k] = (int) ((0.15 + 0.7 * r.nextDouble()) * length);
                this.breakHalf[k] = (int) ((0.025 + 0.035 * r.nextDouble()) * this.sr);
            }
        }
    }

    /** {@code buf[off..off+n)} をその場で加工する(状態は次の塊へ持ち越す)。 */
    public void process(float[] buf, int off, int n) {
        if (this.mode == MODE_DRY) {
            this.pos += n;
            return;
        }
        boolean crush = this.mode >= MODE_CRUSHED;
        boolean breaks = this.mode >= MODE_CRUSHED_BREAKS;
        for (int i = off; i < off + n; i++) {
            float v = buf[i];
            if (crush) {
                float y = (float) this.crushLp.step((float) this.crushHp.step(v));
                y = (float) Math.tanh(y / this.crushPeak * this.drive);
                v = (float) this.crushPost.step(y);
            }
            // かすれ
            float rn = (float) (this.roughLp.step((float) (this.roughRandom.nextDouble() * 2 - 1)));
            rn = (float) (rn * this.roughUnit);
            float e = (float) this.envLp2.step((float) this.envLp1.step(Math.abs(v)));
            e = Math.max(0, e);
            float br = (float) this.breathBp.step((float) (this.breathRandom.nextDouble() * 2 - 1));
            double m = (1 - this.amount) + this.amount * Math.abs(rn) * 1.25;
            float y = (float) (v * m + br * e * this.breath);
            if (breaks) {
                for (int k = 0; k < this.breakCenter.length; k++) {
                    long c = this.breakCenter[k], w = this.breakHalf[k];
                    if (this.pos >= c - w && this.pos < c + w) {
                        double d = Math.abs(this.pos - c) / (double) w;
                        y = (float) (y * (0.12 + 0.88 * (0.5 - 0.5 * Math.cos(Math.PI * d))));
                    }
                }
            }
            buf[i] = y;
            this.pos++;
        }
    }

    /** 2 次の IIR(RBJ の式。試聴用 {@code SacSound#biquad} と同じ計算・同じ丸め)。 */
    static final class Biquad {
        static final int LP = 0, HP = 1, BP = 2;
        private final int type;
        private final double f, q, sr;
        private final double b0, b1, b2, a0, a1, a2;
        private double x1, x2, y1, y2;

        Biquad(int type, double f, double q, double sr) {
            this.type = type;
            // 試聴用は 48kHz で 4200 × 1.3 Hz まで。22kHz の元音でも安定するようナイキストの手前で頭打ちにする。
            this.f = Math.min(f, sr * 0.45);
            this.q = q;
            this.sr = sr;
            double w = 2 * Math.PI * this.f / sr, al = Math.sin(w) / (2 * q), c = Math.cos(w);
            this.a0 = 1 + al;
            this.a1 = -2 * c;
            this.a2 = 1 - al;
            if (type == LP) {
                this.b0 = (1 - c) / 2;
                this.b1 = 1 - c;
                this.b2 = this.b0;
            } else if (type == HP) {
                this.b0 = (1 + c) / 2;
                this.b1 = -(1 + c);
                this.b2 = this.b0;
            } else {
                this.b0 = al;
                this.b1 = 0;
                this.b2 = -al;
            }
        }

        Biquad copyFresh() {
            return new Biquad(this.type, this.f, this.q, this.sr);
        }

        /** 試聴用と同じく、前の入力は float・前の出力は double のまま持ち越し、返す値は float に丸める。 */
        double step(float x) {
            double v = (this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2) / this.a0;
            this.x2 = this.x1;
            this.x1 = x;
            this.y2 = this.y1;
            this.y1 = v;
            return (float) v;
        }
    }
}
