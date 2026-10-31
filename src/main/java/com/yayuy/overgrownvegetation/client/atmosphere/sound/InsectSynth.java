package com.yayuy.overgrownvegetation.client.atmosphere.sound;

/**
 * 森の<b>虫・爬虫類の声の合成器</b>(2026-10-31。鳥の代わり)。過繁茂の森では鳥の声が消える設定(一覧 32)なので、鳥の代わりに
 * 虫・爬虫類の声を置く(10-31 ユーザー「鳥よりかは虫や爬虫類の鳴き声ですかね」→ 候補 a・c を採用、b のセミ系は入れない)。
 * 10-31 夜に 4 種を足した(見本 01・02・04・05 をユーザー「いい感じ」): {@link Chorus} 昼の合唱・{@link Katydid} キリギリス様・{@link Rustle} 下草のガサガサ・
 * {@link Reptile} 爬虫類の息とコッ。1 回きりの声は {@link Call}。
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

    /** 1 回きりの声(鳴き終えたら {@link #done})。ヤモリ・昼の合唱・キリギリス様・下草のガサガサ・爬虫類がこの形。 */
    public interface Call {
        void render(float[] out, int offset, int frames, double level);

        boolean done();
    }

    /** ヤモリ系の 1 回の鳴き(3〜6 回の「チッ」)。鳴き終えたら {@link #done}。 */
    public static final class Gecko implements Call {
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

    /**
     * 昼の合唱(10-31 夜 ユーザー採用「01」)。石炭紀にセミはいないが、大きな虫が翅や体を震わせる持続音として昼を埋める。2〜3 層の「ジー」:
     * 帯の雑音(2.9 / 3.7 / 4.7 kHz)を体の震え(92 / 117 / 141 Hz)でなめらかに刻み、層ごとにずれた時刻に 3〜4 秒で膨らみ・4〜6 秒保ち・4 秒ほどで引く。
     * 遠くの音なので 4 kHz の 2 段の低域通過と残響。1 回 10〜18 秒。
     */
    public static final class Chorus implements Call {
        private static final double[] FC = {2900.0, 3700.0, 4700.0};
        private static final double[] Q = {6.0, 7.0, 7.0};
        private static final double[] RATE = {92.0, 117.0, 141.0};
        private static final double[] GAIN = {1.0, 0.8, 0.6};
        /** 鳴りの山がおよそ 0.5 になる倍率(見本 01 と同じ大きさ)。 */
        private static final double OUT = 0.17;
        private final Rng rng;
        private final Band[] bands;
        private final double[] phase;
        private final double[][] shape;
        private final Reverb reverb = new Reverb(0.3);
        private final int length;
        private int age;
        private double l1;
        private double l2;

        public Chorus(int seed, int layers) {
            this.rng = new Rng(seed);
            int n = Math.max(1, Math.min(3, layers));
            this.bands = new Band[n];
            this.phase = new double[n];
            this.shape = new double[n][];
            double start = 0.3;
            double end = 0.0;
            for (int i = 0; i < n; i++) {
                this.bands[i] = new Band(FC[i] * (0.97 + 0.06 * this.rng.next01()), Q[i]);
                this.phase[i] = this.rng.next01();
                double up = 3.0 + this.rng.next01();
                double hold = 6.0 - i - this.rng.next01();
                double down = 4.0 + 0.5 * this.rng.next01();
                this.shape[i] = new double[]{start, up, Math.max(3.0, hold), down};
                end = Math.max(end, start + up + Math.max(3.0, hold) + down);
                start += 2.7 + 0.8 * this.rng.next01();
            }
            this.length = (int) ((end + 0.6) * SAMPLE_RATE);
        }

        @Override
        public boolean done() {
            return this.age >= this.length;
        }

        @Override
        public void render(float[] out, int offset, int frames, double level) {
            double c = 1.0 - Math.exp(-TWO_PI * 4000.0 / SAMPLE_RATE);
            for (int i = 0; i < frames; i++) {
                double t = this.age / (double) SAMPLE_RATE;
                double v = 0.0;
                for (int k = 0; k < this.bands.length; k++) {
                    double[] s = this.shape[k];
                    double env;
                    if (t < s[0]) {
                        env = 0.0;
                    } else if (t < s[0] + s[1]) {
                        env = smooth((t - s[0]) / s[1]);
                    } else if (t < s[0] + s[1] + s[2]) {
                        env = 1.0;
                    } else {
                        env = 1.0 - smooth((t - s[0] - s[1] - s[2]) / s[3]);
                    }
                    if (env <= 0.0) {
                        continue;
                    }
                    env *= 0.85 + 0.15 * Math.sin(TWO_PI * (0.13 + 0.04 * k) * t + k);
                    this.phase[k] += RATE[k] / SAMPLE_RATE;
                    double m = 0.5 + 0.5 * Math.sin(TWO_PI * this.phase[k]);
                    v += GAIN[k] * env * (0.25 + 0.75 * m * m) * this.bands[k].band(this.rng.gauss());
                }
                this.l1 += (v * OUT - this.l1) * c;
                this.l2 += (this.l1 - this.l2) * c;
                out[offset + i] += (float) ((this.l2 + this.reverb.process(this.l2)) * level);
                this.age++;
            }
        }
    }

    /**
     * 夜のキリギリス様(10-31 夜 ユーザー採用「02」)。コオロギの澄んだ「リッ」とは違う、ざらついた「ギーッ」。翅の歯 1 枚ずつを 0.7 ms の雑音の粒で鳴らし
     * (毎秒 220 枚、±15% ゆらぐ)、fc(4.7〜5.4 kHz、Q 9)と fc / 2 の共鳴で響かせる。1 回 0.28 秒を 0.45 秒おきに 2〜4 回で一区切り、3〜5 秒休んで次の区切り。
     * 区切りを {@code phrases} 回鳴いたら終わる(同じ所で鳴き続ける 1 匹)。
     */
    public static final class Katydid implements Call {
        private static final double SYL = 0.28;
        private static final double STEP = 0.45;
        private static final double TOOTH = 0.0007;
        private static final double OUT = 0.18;
        private final Rng rng;
        private final Band hi;
        private final Band lo;
        private final Reverb reverb = new Reverb(0.14);
        private int phrasesLeft;
        /** 鳴く区間の終わり・凪で、今の区切りを鳴き終えてから黙る(11-01。呼び手が立てる)。 */
        private volatile boolean stopRequested;
        private double[] toothAt = new double[0];
        private double[] toothGain = new double[0];
        private int next;
        private int grainStart = -1;
        private double grainGain;
        private int nextPhrase;
        private int end = Integer.MAX_VALUE;
        private int age;
        private double soft;

        public Katydid(int seed, int phrases) {
            this.rng = new Rng(seed);
            double fc = 4700.0 + 700.0 * this.rng.next01();
            this.hi = new Band(fc, 9.0);
            this.lo = new Band(fc / 2.0, 4.0);
            this.phrasesLeft = Math.max(1, phrases);
            this.nextPhrase = (int) (0.05 * SAMPLE_RATE);
        }

        @Override
        public boolean done() {
            return this.age >= this.end;
        }

        /** 今の区切りを鳴き終えたら終わる(区切りの途中では切らない)。 */
        public void requestStop() {
            this.stopRequested = true;
        }

        /** 次の区切り(2〜4 回の「ギーッ」)の歯の時刻を作る。 */
        private void buildPhrase() {
            int count = 2 + (int) (this.rng.next01() * 3.0);
            double[] at = new double[count * 80];
            double[] gain = new double[at.length];
            int n = 0;
            double t0 = this.age / (double) SAMPLE_RATE;
            double ts = 0.0;
            for (int s = 0; s < count; s++) {
                double tt = 0.0;
                while (tt < SYL && n < at.length) {
                    double env = tt < 0.03 ? smooth(tt / 0.03) : tt > SYL - 0.06 ? 1.0 - smooth((tt - SYL + 0.06) / 0.06) : 1.0;
                    at[n] = t0 + ts + tt;
                    gain[n] = env * (0.6 + 0.4 * this.rng.next01()) * (s == count - 1 ? 0.8 : 1.0);
                    n++;
                    tt += (1.0 / 220.0) * (0.85 + 0.3 * this.rng.next01());
                }
                ts += STEP * (0.95 + 0.1 * this.rng.next01());
            }
            this.toothAt = java.util.Arrays.copyOf(at, n);
            this.toothGain = java.util.Arrays.copyOf(gain, n);
            this.next = 0;
            this.phrasesLeft--;
            double last = n > 0 ? at[n - 1] : t0;
            if (this.phrasesLeft > 0) {
                this.nextPhrase = (int) ((last + 3.0 + 1.8 * this.rng.next01()) * SAMPLE_RATE);
            } else {
                this.nextPhrase = Integer.MAX_VALUE;
                this.end = (int) ((last + 0.6) * SAMPLE_RATE);
            }
        }

        @Override
        public void render(float[] out, int offset, int frames, double level) {
            int len = (int) (TOOTH * SAMPLE_RATE);
            double c = 1.0 - Math.exp(-TWO_PI * 7000.0 / SAMPLE_RATE);
            for (int i = 0; i < frames; i++) {
                if (this.stopRequested && this.next >= this.toothAt.length && this.end == Integer.MAX_VALUE) {
                    // 区切りの合間(または鳴き終えた所)で止める: 残りの響きの 0.6 秒で終わる。
                    this.nextPhrase = Integer.MAX_VALUE;
                    this.end = this.age + (int) (0.6 * SAMPLE_RATE);
                }
                if (this.age >= this.nextPhrase) {
                    buildPhrase();
                }
                if (this.next < this.toothAt.length && this.age >= (int) (this.toothAt[this.next] * SAMPLE_RATE)) {
                    this.grainStart = this.age;
                    this.grainGain = this.toothGain[this.next++];
                }
                double e = 0.0;
                if (this.grainStart >= 0) {
                    int k = this.age - this.grainStart;
                    if (k < len) {
                        double w = Math.sin(Math.PI * k / len);
                        e = this.grainGain * w * w * this.rng.gauss();
                    } else {
                        this.grainStart = -1;
                    }
                }
                double v = (this.hi.band(e) + 0.35 * this.lo.band(e)) * OUT;
                this.soft += (v - this.soft) * c;
                out[offset + i] += (float) ((this.soft + this.reverb.process(this.soft)) * level);
                this.age++;
            }
        }
    }

    /**
     * 下草のガサガサ(10-31 夜 ユーザー採用「04」)。大きな多足類が湿った落ち葉の上を歩く。1〜4.5 kHz の短い雑音の粒(2〜8 ms、立ち上がり 1 ms 以上の余弦)を
     * 毎秒 140 個前後、脚の波(0.12〜0.2 秒)で密・疎にして散らし、0.6〜2.4 kHz の薄い引きずりを敷く。6 kHz の低域通過。
     * 参考は BigSoundBank「Feet in Leaves #1 / #2」(CC0、乾いた落ち葉)。山を 4〜8 kHz から 2〜4 kHz へ下げ、クレストを 25 dB 以下に丸めた。
     */
    public static final class Rustle implements Call {
        private static final double OUT = 0.15;
        private static final int MAX_GRAINS = 24;
        private final Rng rng;
        private final double len;
        private final double legRate;
        private final double legPhase;
        private final Band bed = new Band(1300.0, 0.8);
        private final Reverb reverb = new Reverb(0.1);
        private final Band[] grain = new Band[MAX_GRAINS];
        private final int[] gStart = new int[MAX_GRAINS];
        private final int[] gLen = new int[MAX_GRAINS];
        private final int[] gAtt = new int[MAX_GRAINS];
        private final double[] gAmp = new double[MAX_GRAINS];
        private final int length;
        private double nextGrain;
        private int age;
        private double soft;

        public Rustle(int seed, double seconds) {
            this.rng = new Rng(seed);
            this.len = Math.max(0.3, seconds);
            this.legRate = 1.0 / (0.12 + 0.08 * this.rng.next01());
            this.legPhase = this.rng.next01();
            this.length = (int) ((this.len + 0.5) * SAMPLE_RATE);
        }

        @Override
        public boolean done() {
            return this.age >= this.length;
        }

        private double leg(double t) {
            return 0.5 + 0.5 * Math.sin(TWO_PI * (this.legPhase + this.legRate * t));
        }

        @Override
        public void render(float[] out, int offset, int frames, double level) {
            double c = 1.0 - Math.exp(-TWO_PI * 6000.0 / SAMPLE_RATE);
            for (int i = 0; i < frames; i++) {
                double t = this.age / (double) SAMPLE_RATE;
                double v = 0.0;
                if (t < this.len) {
                    double env = envAD(t / this.len, 0.12, 0.3);
                    v += 0.12 * env * this.bed.band(this.rng.gauss());
                    // 粒: 毎秒 140(脚の波で 0.3〜1.7 倍)。時刻が来たら空いている枠に置く。
                    while (this.nextGrain <= t) {
                        double lg = leg(this.nextGrain);
                        double e = envAD(this.nextGrain / this.len, 0.12, 0.3);
                        startGrain(e * Math.exp(0.45 * this.rng.gauss()) * (0.6 + 0.6 * lg));
                        double u = Math.max(1e-9, 1.0 - this.rng.next01());
                        this.nextGrain += -Math.log(u) / (140.0 * (0.3 + 1.4 * lg * lg));
                    }
                }
                for (int g = 0; g < MAX_GRAINS; g++) {
                    if (this.grain[g] == null) {
                        continue;
                    }
                    int k = this.age - this.gStart[g];
                    int len = this.gLen[g];
                    int att = this.gAtt[g];
                    double w = k < att ? smooth(k / (double) att) : k < len ? 1.0 - smooth((k - att) / (double) (len - att)) : 0.0;
                    v += this.gAmp[g] * this.grain[g].band(w * this.rng.gauss());
                    if (k >= len + 200) {
                        this.grain[g] = null;
                    }
                }
                v *= OUT;
                this.soft += (v - this.soft) * c;
                out[offset + i] += (float) ((this.soft + this.reverb.process(this.soft)) * level);
                this.age++;
            }
        }

        private void startGrain(double amp) {
            for (int g = 0; g < MAX_GRAINS; g++) {
                if (this.grain[g] == null) {
                    double f = 1000.0 + 3500.0 * Math.pow(this.rng.next01(), 1.4);
                    this.grain[g] = new Band(f, 1.5);
                    this.gStart[g] = this.age;
                    this.gLen[g] = (int) ((0.002 + 0.006 * this.rng.next01()) * SAMPLE_RATE);
                    this.gAtt[g] = Math.max((int) (0.001 * SAMPLE_RATE), this.gLen[g] / 3);
                    this.gAmp[g] = amp;
                    return;
                }
            }
        }
    }

    /**
     * 爬虫類の息とコッ(10-31 夜 ユーザー採用「05」)。ヤモリの高い「チッ」と対にした、もう少し大きな体の気配。無声の息(2.6 / 4.3 kHz の帯の雑音、70 ms で出て
     * 0.35〜0.55 秒保ち 0.25 秒で引く。音程なし)と、喉の低い「コッ」(280 Hz と 640 Hz の短く減る響き、音程固定)を 1〜3 回。肺で鳴く声にしない。
     */
    public static final class Reptile implements Call {
        private static final double OUT = 0.31;
        private final Rng rng;
        private final double hissLen;
        private final double[] knockAt;
        private final double[] knockGain;
        private final Band hissA = new Band(2600.0, 0.9);
        private final Band hissB = new Band(4300.0, 1.5);
        private final Reverb reverb = new Reverb(0.15);
        private Band knockRes;
        private int knock = -1;
        private final int length;
        private int age;
        private double soft;

        public Reptile(int seed) {
            this.rng = new Rng(seed);
            boolean hiss = this.rng.next01() < 0.6;
            this.hissLen = hiss ? 0.35 + 0.2 * this.rng.next01() : 0.0;
            int knocks = hiss ? 1 + (int) (this.rng.next01() * 2.0) : 2 + (int) (this.rng.next01() * 2.0);
            this.knockAt = new double[knocks];
            this.knockGain = new double[knocks];
            double t = hiss ? 0.05 + this.hissLen + 0.3 + 0.2 * this.rng.next01() : 0.05;
            for (int i = 0; i < knocks; i++) {
                this.knockAt[i] = t;
                this.knockGain[i] = (1.0 - 0.17 * i) * (0.9 + 0.1 * this.rng.next01());
                t += 0.16 + 0.05 * this.rng.next01();
            }
            this.length = (int) ((this.knockAt[knocks - 1] + 0.09 + 0.5) * SAMPLE_RATE);
        }

        @Override
        public boolean done() {
            return this.age >= this.length;
        }

        @Override
        public void render(float[] out, int offset, int frames, double level) {
            double c = 1.0 - Math.exp(-TWO_PI * 5000.0 / SAMPLE_RATE);
            for (int i = 0; i < frames; i++) {
                double t = this.age / (double) SAMPLE_RATE;
                double v = 0.0;
                double th = t - 0.05;
                if (this.hissLen > 0.0 && th >= 0.0 && th < this.hissLen + 0.25) {
                    double env = th < 0.07 ? smooth(th / 0.07) : th < this.hissLen ? 1.0 : 1.0 - smooth((th - this.hissLen) / 0.25);
                    env *= 0.9 + 0.1 * Math.sin(TWO_PI * 9.0 * th);
                    double nz = this.rng.gauss();
                    v += 0.5 * env * (this.hissA.band(nz) + 0.6 * this.hissB.band(nz));
                }
                if (this.knock + 1 < this.knockAt.length && t >= this.knockAt[this.knock + 1]) {
                    this.knock++;
                    this.knockRes = new Band(640.0, 6.0);
                }
                if (this.knock >= 0) {
                    double tk = t - this.knockAt[this.knock];
                    if (tk < 0.09) {
                        double att = tk < 0.003 ? smooth(tk / 0.003) : 1.0;
                        double core = Math.sin(TWO_PI * 280.0 * tk) * Math.exp(-tk / 0.016);
                        double exc = tk < 0.004 ? Math.sin(Math.PI * tk / 0.004) * this.rng.gauss() : 0.0;
                        v += this.knockGain[this.knock] * att * (0.9 * core + 1.4 * this.knockRes.band(exc) + 0.25 * exc);
                    }
                }
                v *= OUT;
                this.soft += (v - this.soft) * c;
                out[offset + i] += (float) ((this.soft + this.reverb.process(this.soft)) * level);
                this.age++;
            }
        }
    }

    /** 短い 1 回きりの音を、最初に流すとき(音のスレッド)にまとめて作って流す。 */
    abstract static class Prerendered implements Call {
        private float[] buf;
        private int pos;

        abstract float[] build();

        @Override
        public boolean done() {
            return this.buf != null && this.pos >= this.buf.length;
        }

        @Override
        public void render(float[] out, int offset, int frames, double level) {
            if (this.buf == null) {
                this.buf = build();
            }
            int n = Math.min(frames, this.buf.length - this.pos);
            for (int i = 0; i < n; i++) {
                out[offset + i] += (float) (this.buf[this.pos + i] * level);
            }
            this.pos += Math.max(0, n);
        }
    }

    /**
     * 頭上の枝葉が揺れる(10-31 夜 ユーザー採用「07b」)。姿の見えない大きなものが林冠を渡り、風の無いときに梢の一か所だけが「ザワッ」と揺れる。
     * 0.4 / 0.9 / 2.4 / 5 kHz の 4 帯の葉ずれを 2〜3 回の膨らみ(立ち上がり 0.15 秒・e 分の 1 が 0.35 秒)と葉のはためき(30〜70 ms ごとになめらかに)で動かし、
     * まばらな葉の粒(毎秒 40)を少し。6.5 kHz の低域通過。帯は BigSoundBank「Wind in a Tree」「Wind in the Trees」(CC0)の大きい所に合わせた
     * (前の 07 はこもりすぎ、10-31 夜 ユーザー)。1 回 1.6〜2.4 秒。
     */
    public static final class CanopyShake extends Prerendered {
        private static final double OUT = 0.6;
        private final int seed;

        public CanopyShake(int seed) {
            this.seed = seed;
        }

        @Override
        float[] build() {
            Rng r = new Rng(this.seed);
            int count = r.next01() < 0.5 ? 2 : 3;
            double[] surges = new double[count];
            for (int s = 1; s < count; s++) {
                surges[s] = surges[s - 1] + 0.4 + 0.2 * r.next01();
            }
            double len = surges[count - 1] + 1.2;
            int n = (int) ((len + 0.5) * SAMPLE_RATE);
            double[] x = new double[n];
            Band d = new Band(400.0, 0.8);
            Band a = new Band(900.0, 0.7);
            Band b = new Band(2400.0, 0.8);
            Band c = new Band(5000.0, 1.0);
            double flutter = 0.0;
            double target = 1.0;
            int hold = 0;
            int body = (int) (len * SAMPLE_RATE);
            for (int k = 0; k < body; k++) {
                double t = k / (double) SAMPLE_RATE;
                double env = 0.0;
                for (int s = 0; s < count; s++) {
                    double tt = t - surges[s];
                    if (tt >= 0.0) {
                        env += (s == 0 ? 1.0 : 0.7) * (tt < 0.15 ? smooth(tt / 0.15) : Math.exp(-(tt - 0.15) / 0.35));
                    }
                }
                if (--hold <= 0) {
                    target = 0.4 + 1.2 * r.next01();
                    hold = (int) ((0.03 + 0.04 * r.next01()) * SAMPLE_RATE);
                }
                flutter += (target - flutter) * 0.003;
                double nz = r.gauss();
                x[k] += 0.5 * env * flutter * (0.6 * d.band(nz) + 1.2 * a.band(nz) + 0.8 * b.band(nz) + 0.15 * c.band(nz));
            }
            double t = 0.0;
            while ((t += -Math.log(Math.max(1e-9, 1.0 - r.next01())) / 40.0) < len) {
                double env = 0.0;
                for (double s : surges) {
                    if (t > s) {
                        env = Math.max(env, Math.exp(-(t - s) / 0.5));
                    }
                }
                Band g = new Band(1500.0 + 2500.0 * r.next01(), 2.0);
                int s0 = (int) (t * SAMPLE_RATE);
                int l = (int) (0.003 * SAMPLE_RATE);
                double amp = 0.12 * env * Math.exp(0.4 * r.gauss());
                for (int k = 0; k < l + 300 && s0 + k < n; k++) {
                    double w = k < l ? Math.sin(Math.PI * k / l) : 0.0;
                    x[s0 + k] += amp * g.band(w * w * r.gauss());
                }
            }
            return finish(x, 6500.0, 1, 0.2, OUT);
        }
    }

    /**
     * 小枝が 1 本折れる(10-31 夜 ユーザー採用「08b」。ユーザー案「何歩ぶんも並べず、1 歩で枝を踏んで折った音だけを、たまに周りから」)。
     * 何か重いものが近くの下草を踏んだ気配。踏み込みの葉の小さな粒(0.1 秒)→ 主な割れ(12 ms に 4〜6 個の 2.5 ms の割れ、2〜5 kHz)→ 30〜60 ms 後に
     * 裂け残りの小さな割れ。木の胴は 500 Hz と 1.2 kHz。遠いほど暗く(7 kHz / (1 + 距離 / 12) の 2 段の低域通過)。参考は BigSoundBank
     * 「Steps on the Twigs」「Broken twigs #1」(CC0)。
     */
    public static final class TwigSnap extends Prerendered {
        private static final double OUT = 0.45;
        private final int seed;
        private final double distance;

        public TwigSnap(int seed, double distance) {
            this.seed = seed;
            this.distance = distance;
        }

        @Override
        float[] build() {
            Rng r = new Rng(this.seed);
            double[] x = new double[(int) (0.9 * SAMPLE_RATE)];
            double t0 = 0.12;
            double t = t0 - 0.1;
            while ((t += -Math.log(Math.max(1e-9, 1.0 - r.next01())) / 220.0) < t0) {
                Band g = new Band(1000.0 + 3000.0 * r.next01(), 1.5);
                int s0 = (int) (t * SAMPLE_RATE);
                int l = (int) ((0.002 + 0.005 * r.next01()) * SAMPLE_RATE);
                double amp = 0.25 * smooth((t - t0 + 0.1) / 0.1) * Math.exp(0.4 * r.gauss());
                for (int k = 0; k < l + 200 && s0 + k < x.length; k++) {
                    double w = k < l ? Math.sin(Math.PI * k / l) : 0.0;
                    x[s0 + k] += amp * g.band(w * w * r.gauss());
                }
            }
            crack(x, t0, r, 1.0, 4 + (int) (r.next01() * 3.0), 0.012);
            crack(x, t0 + 0.03 + 0.03 * r.next01(), r, 0.55, 2 + (int) (r.next01() * 2.0), 0.008);
            return finish(x, 7000.0 / (1.0 + this.distance / 12.0), 2, 0.3, OUT);
        }

        private static void crack(double[] x, double t0, Rng r, double gain, int cracks, double span) {
            Band body = new Band(500.0, 4.0);
            Band body2 = new Band(1200.0, 3.0);
            double[] exc = new double[(int) (0.08 * SAMPLE_RATE)];
            for (int c = 0; c < cracks; c++) {
                double tc = span * c / cracks + 0.002 * r.next01();
                double amp = gain * (c == 0 ? 0.8 : 0.4 + 0.4 * r.next01());
                Band hi = new Band(2000.0 + 3000.0 * r.next01(), 2.5);
                int s0 = (int) (tc * SAMPLE_RATE);
                int l = (int) (0.0025 * SAMPLE_RATE);
                for (int k = 0; k < (int) (0.02 * SAMPLE_RATE) && s0 + k < exc.length; k++) {
                    double w = k < l ? Math.pow(Math.sin(Math.PI * k / l), 2) : 0.0;
                    exc[s0 + k] += amp * hi.band(w * r.gauss()) + amp * 0.6 * w * r.gauss();
                }
            }
            int i0 = (int) (t0 * SAMPLE_RATE);
            for (int k = 0; k < exc.length && i0 + k < x.length; k++) {
                x[i0 + k] += exc[k] + 0.9 * body.band(exc[k]) + 0.6 * body2.band(exc[k]);
            }
        }
    }

    /** 作り終えた音に低域通過(hz・段数)と残響をかけ、倍率 out で float にする。 */
    static float[] finish(double[] x, double hz, int stages, double reverb, double out) {
        double c = 1.0 - Math.exp(-TWO_PI * hz / SAMPLE_RATE);
        double[] lp = new double[stages];
        Reverb rev = new Reverb(reverb);
        float[] f = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            for (int s = 0; s < stages; s++) {
                lp[s] += (v - lp[s]) * c;
                v = lp[s];
            }
            f[i] = (float) ((v + rev.process(v)) * out);
        }
        return f;
    }

    static double smooth(double u) {
        u = Math.max(0.0, Math.min(1.0, u));
        return 0.5 - 0.5 * Math.cos(Math.PI * u);
    }

    /** u(0〜1)の始め a と終わり d を余弦でなめらかに出し入れする。 */
    static double envAD(double u, double a, double d) {
        return u < a ? smooth(u / a) : u > 1.0 - d ? 1.0 - smooth((u - 1.0 + d) / d) : 1.0;
    }

    /** xorshift の乱数。gauss は一様な乱数 3 つの和(標準偏差 1)。 */
    static final class Rng {
        private int s;

        Rng(int seed) {
            this.s = seed == 0 ? 0x2545F491 : seed;
        }

        double nextSigned() {
            this.s ^= this.s << 13;
            this.s ^= this.s >>> 17;
            this.s ^= this.s << 5;
            return this.s * (1.0 / 2147483648.0);
        }

        double next01() {
            return (nextSigned() + 1.0) * 0.5;
        }

        double gauss() {
            return nextSigned() + nextSigned() + nextSigned();
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
        // 10-31 夜に採用した 4 種(1 回ずつ。大きさ 1 のまま書き、山の高さを出す = 見本と同じ 0.5 前後になるよう OUT を決めた)
        call(dir, "final_chorus", new Chorus(0x0C00_0001, 3));
        call(dir, "final_katydid", new Katydid(0x0C00_0002, 3));
        call(dir, "final_rustle", new Rustle(0x0C00_0003, 1.3));
        call(dir, "final_reptile_a", new Reptile(0x0C00_0004));
        call(dir, "final_reptile_b", new Reptile(0x0C00_0007));
        call(dir, "final_canopy", new CanopyShake(0x0C00_0008));
        call(dir, "final_twig_10", new TwigSnap(0x0C00_0009, 10.0));
        call(dir, "final_twig_20", new TwigSnap(0x0C00_000A, 20.0));
    }

    private static void call(String dir, String name, Call c) throws java.io.IOException {
        java.util.List<float[]> parts = new java.util.ArrayList<>();
        int block = 2205;
        int total = 0;
        while (!c.done() && total < SAMPLE_RATE * 60) {
            float[] b = new float[block];
            c.render(b, 0, block, 1.0);
            parts.add(b);
            total += block;
        }
        float[] all = new float[total];
        double peak = 0.0;
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, all, i * block, block);
        }
        for (float v : all) {
            peak = Math.max(peak, Math.abs(v));
        }
        System.out.printf("%s: %.1f 秒 山 %.3f%n", name, total / (double) SAMPLE_RATE, peak);
        BubbleWaterSynth.writeWav(dir + "/" + name + ".wav", all);
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
