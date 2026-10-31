package com.yayuy.overgrownvegetation.client.atmosphere.sound;

/**
 * 森の<b>虫・爬虫類の声の合成器</b>(2026-10-31。鳥の代わり)。過繁茂の森では鳥の声が消える設定(一覧 32)なので、鳥の代わりに
 * 虫・爬虫類の声を置く(10-31 ユーザー「鳥よりかは虫や爬虫類の鳴き声ですかね」→ 候補 a・c を採用、b のセミ系は入れない)。
 *
 * <ul>
 *     <li>{@link Cricket}(a コオロギ系、夕〜夜): 鳥のいない古い時代の森(メガネウラ・プロトファスマの類)の夜を、翅をこすって鳴く虫が埋める。
 *     4〜5 kHz の短いパルス 3〜4 つで 1 回の鳴き(約 0.1 秒)、0.5 秒ほど休んでまた鳴く。止めると今の鳴きを鳴き終えてから黙る。</li>
 *     <li>{@link Gecko}(c ヤモリ系、夜・近く): ヒロノムスのような小さな爬虫類が、近くの幹に隠れている気配。乾いた「チッ」を 3〜6 回
 *     (だんだん間が開き弱くなる)で 1 回の鳴きが終わる。</li>
 * </ul>
 * どちらも立ち上がりは余弦の窓(とがった縁を作らない。memory synth-sound-no-crackle)。薄い残響({@link Reverb})は林の中の響き。
 * モノラル(呼び手が音源の位置に置く)。<b>Minecraft の型を使わない</b>({@link #main} で、ゲームで鳴る形の WAV を書き出して聞ける)。数値はすべて仮決め。
 */
public final class InsectSynth {

    public static final int SAMPLE_RATE = WindSynth.SAMPLE_RATE;
    private static final double TWO_PI = Math.PI * 2.0;

    private InsectSynth() {
    }

    /** コオロギ系 1 匹。{@link #render} を呼び続け、{@link #setSinging} で鳴く・黙るを切り替える。 */
    public static final class Cricket {
        private final double freq;
        private final double period;
        private final int pulses;
        private final Reverb reverb = new Reverb(0.12);
        private int rng;
        private boolean singing = true;
        /** 今の鳴きの始めからのサンプル数(-1 = 鳴きの外)と、次の鳴きまでのサンプル数。 */
        private int chirpAge = -1;
        private int wait;
        private double phase;
        private double soft;

        public Cricket(int seed) {
            this.rng = seed == 0 ? 0x2545F491 : seed;
            this.freq = 4000.0 + 800.0 * next01();
            this.period = 0.55 + 0.25 * next01();
            this.pulses = next01() < 0.5 ? 3 : 4;
            this.wait = (int) (SAMPLE_RATE * this.period * next01());
        }

        public void setSinging(boolean singing) {
            this.singing = singing;
        }

        /** 鳴きの途中か(黙らせた後、鳴き終えたかを呼び手が見る)。 */
        public boolean inChirp() {
            return this.chirpAge >= 0;
        }

        public void render(float[] out, int offset, int frames, double level) {
            int pulseLen = (int) (0.020 * SAMPLE_RATE);
            int pulseStep = (int) (0.032 * SAMPLE_RATE);
            for (int i = 0; i < frames; i++) {
                double v = 0.0;
                if (this.chirpAge < 0) {
                    if (--this.wait <= 0 && this.singing) {
                        this.chirpAge = 0;
                    }
                }
                if (this.chirpAge >= 0) {
                    int p = this.chirpAge / pulseStep;
                    int in = this.chirpAge - p * pulseStep;
                    if (p >= this.pulses) {
                        this.chirpAge = -1;
                        this.wait = (int) (SAMPLE_RATE * this.period * (0.94 + 0.12 * next01())) - this.pulses * pulseStep;
                    } else {
                        if (in < pulseLen) {
                            double u = in / (double) pulseLen;
                            double env = Math.sin(Math.PI * u);
                            env *= env;
                            this.phase += TWO_PI * this.freq * (1.02 - 0.04 * u) / SAMPLE_RATE;
                            v = (p == 0 ? 0.8 : 1.0) * env * (Math.sin(this.phase) + 0.08 * Math.sin(2.0 * this.phase));
                        }
                        this.chirpAge++;
                    }
                }
                double r = this.reverb.process(v);
                // 林の中の距離: 高い所を少し丸める(7 kHz)
                this.soft += (v + r - this.soft) * 0.63;
                out[offset + i] += (float) (this.soft * level);
            }
        }

        private double next01() {
            this.rng ^= this.rng << 13;
            this.rng ^= this.rng >>> 17;
            this.rng ^= this.rng << 5;
            return (this.rng * (1.0 / 2147483648.0) + 1.0) * 0.5;
        }
    }

    /** ヤモリ系の 1 回の鳴き(3〜6 回の「チッ」)。鳴き終えたら {@link #done}。 */
    public static final class Gecko {
        private final double[] clickAt;
        private final double[] clickGain;
        private final Reverb reverb = new Reverb(0.18);
        private final Band band = new Band(2600.0, 3.5);
        private int rng;
        private int age;
        private final int length;
        private double soft;

        public Gecko(int seed, int clicks) {
            this.rng = seed == 0 ? 0x2545F491 : seed;
            this.clickAt = new double[clicks];
            this.clickGain = new double[clicks];
            double t = 0.05;
            double gap = 0.15 + 0.03 * next01();
            for (int i = 0; i < clicks; i++) {
                this.clickAt[i] = t;
                this.clickGain[i] = (1.0 - 0.07 * i) * (0.9 + 0.1 * next01());
                t += gap;
                gap *= 1.08 + 0.06 * next01();
            }
            this.length = (int) ((t + 0.5) * SAMPLE_RATE);
        }

        public boolean done() {
            return this.age >= this.length;
        }

        public void render(float[] out, int offset, int frames, double level) {
            for (int i = 0; i < frames; i++) {
                double tAll = this.age / (double) SAMPLE_RATE;
                double v = 0.0;
                double noise = this.band.band(nextSigned());
                for (int k = 0; k < this.clickAt.length; k++) {
                    double tt = tAll - this.clickAt[k];
                    if (tt < 0.0 || tt > 0.07) {
                        continue;
                    }
                    // 立ち上がり 2.5 ms(余弦)・消え方は指数(雑音 15 ms、音程の芯 22 ms。芯は 1.9 kHz から 30 ms で 1.6 kHz へ)。
                    double att = tt < 0.0025 ? 0.5 - 0.5 * Math.cos(Math.PI * tt / 0.0025) : 1.0;
                    double f = 1900.0 - 300.0 * Math.min(1.0, tt / 0.03);
                    double core = Math.sin(TWO_PI * f * tt);
                    // 雑音は一様な乱数(標準偏差 0.577)なので 1.73 倍して見本(正規分布の雑音)にそろえる。
                    v += this.clickGain[k] * att * (1.6 * 1.73 * noise * Math.exp(-tt / 0.015) + 0.7 * core * Math.exp(-tt / 0.022));
                }
                double r = this.reverb.process(v);
                // 6 kHz の 1 次の低域通過
                this.soft += (v + r - this.soft) * 0.58;
                out[offset + i] += (float) (this.soft * level);
                this.age++;
            }
        }

        private double next01() {
            return (nextSigned() + 1.0) * 0.5;
        }

        private double nextSigned() {
            this.rng ^= this.rng << 13;
            this.rng ^= this.rng >>> 17;
            this.rng ^= this.rng << 5;
            return this.rng * (1.0 / 2147483648.0);
        }
    }

    /** 薄い残響: ずらした写し 6 つ(だんだん弱く)を 2.5 kHz の 2 段の低域通過で丸める。 */
    static final class Reverb {
        private static final int[] TAPS = {1543, 2711, 3967, 5303, 7121, 9433};
        private final double[] line = new double[16384];
        private final double amount;
        private int at;
        private double l1;
        private double l2;

        Reverb(double amount) {
            this.amount = amount;
        }

        double process(double x) {
            this.line[this.at] = x;
            double wet = 0.0;
            double g = this.amount;
            for (int tap : TAPS) {
                wet += g * this.line[(this.at - tap) & 16383];
                g *= 0.7;
            }
            this.at = (this.at + 1) & 16383;
            double c = 1.0 - Math.exp(-TWO_PI * 2500.0 / SAMPLE_RATE);
            this.l1 += (wet - this.l1) * c;
            this.l2 += (this.l1 - this.l2) * c;
            return this.l2;
        }
    }

    /** 状態変数フィルター(帯域通過)。 */
    static final class Band {
        private final double a1;
        private final double a2;
        private final double a3;
        private double ic1;
        private double ic2;

        Band(double hz, double q) {
            double g = Math.tan(Math.PI * hz / SAMPLE_RATE);
            double k = 1.0 / q;
            this.a1 = 1.0 / (1.0 + g * (g + k));
            this.a2 = g * this.a1;
            this.a3 = g * this.a2;
        }

        double band(double x) {
            double v3 = x - this.ic2;
            double v1 = this.a1 * this.ic1 + this.a2 * v3;
            double v2 = this.ic2 + this.a2 * this.ic1 + this.a3 * v3;
            this.ic1 = 2.0 * v1 - this.ic1;
            this.ic2 = 2.0 * v2 - this.ic2;
            return v1;
        }
    }

    /**
     * 確かめ用: ゲームで鳴る形の WAV を書く。{@code java InsectSynth.java <出力フォルダ>}。
     * crickets = 3 匹(9・14・19 ブロック先)が 25 秒鳴き、12 秒でプレイヤーがいちばん近い 1 匹に 4 ブロックまで寄ってその 1 匹が黙る /
     * gecko_4・gecko_6 = 1 回の鳴き(4 回・6 回)。
     */
    public static void main(String[] args) throws java.io.IOException {
        String dir = args.length > 0 ? args[0] : ".";
        int n = SAMPLE_RATE * 25;
        float[] buf = new float[n];
        Cricket[] voices = {new Cricket(101), new Cricket(202), new Cricket(303)};
        double[] dist = {9.0, 14.0, 19.0};
        int block = 2205;
        for (int at = 0; at < n; at += block) {
            double t = at / (double) SAMPLE_RATE;
            for (int v = 0; v < voices.length; v++) {
                double d = dist[v];
                if (v == 0) {
                    // 8 秒から 12 秒で 9 → 4 ブロックへ寄る。4 ブロックで黙る(鳴きは鳴き終える)。
                    d = t < 8.0 ? 9.0 : Math.max(4.0, 9.0 - 5.0 * (t - 8.0) / 4.0);
                    voices[v].setSinging(d > 4.0);
                }
                // 距離の減り方の仮: 1 / (1 + d / 6)
                voices[v].render(buf, at, Math.min(block, n - at), 0.9 / (1.0 + d / 6.0));
            }
        }
        BubbleWaterSynth.writeWav(dir + "/final_crickets_25s.wav", norm(buf, 0.5));
        for (int clicks : new int[]{4, 6}) {
            float[] g = new float[SAMPLE_RATE * 3];
            Gecko gecko = new Gecko(clicks * 31, clicks);
            gecko.render(g, 0, g.length, 1.0);
            BubbleWaterSynth.writeWav(dir + "/final_gecko_" + clicks + ".wav", norm(g, 0.5));
        }
    }

    private static float[] norm(float[] x, double to) {
        double peak = 0;
        for (float v : x) {
            peak = Math.max(peak, Math.abs(v));
        }
        for (int i = 0; i < x.length; i++) {
            x[i] *= (float) (to / peak);
        }
        return x;
    }
}
