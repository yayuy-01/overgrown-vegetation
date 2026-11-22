package com.yayuy.overgrownvegetation.client.atmosphere.sound;

/**
 * 森の中の<b>水音の合成器・泡の作り</b>(2026-10-31。{@link WaterSynth} の作り直し)。ユーザー「場違い。水の流れる音ではないように聞こえる」
 * →「ここら辺の生物に由来しない音は現実世界と同じなので現実世界のものを参考に」。前の作りは帯の雑音をゆっくり揺らすだけで、1〜4 kHz が平らな
 * 一様な雑音(粒が毎秒 0.2 回)だった。本物のせせらぎは、巻き込まれた泡が鳴らす短い共鳴(Minnaert: 半径 r mm で約 3.26 / r kHz・
 * 鳴りながら少し上がる・数十 ms で減る。減り方と上がり方は van den Doel 2005 の泡の模型)の連なりと、その下の低めの乱流のうねりでできている。
 *
 * <ul>
 *     <li><b>せせらぎ</b>(流れる水): 泡を毎秒約 170 個(乱流で 0.15〜2 倍に揺れる)。大きさはべき分布(小さい泡ほど多い)で半径 0.9〜6 mm
 *     = 540 Hz〜3.6 kHz。その下に 380 Hz を中心の広い帯の雑音(同じ乱流で大きさが揺れる)。</li>
 *     <li><b>水面</b>: 1.3〜3.5 秒おきの「ちゃぷ」(低い帯の雑音が 0.12〜0.2 秒で立ち上がり 0.35〜0.65 秒で引く)と、岸にかかった所の大きめの泡 1〜4 個(「とぷ」)。</li>
 * </ul>
 * 泡は正弦の減る音で、立ち上がりは 1.5 ms の余弦の窓(雑音の「バチバチ」ではない。memory synth-sound-no-crackle)。
 * 呼び方・大きさのそろえ方は {@link WaterSynth} と同じ({@code flow} 0 = 水面だけ・1 = 流れだけ、{@code level})。<b>Minecraft の型を使わない</b>
 * ({@link #main} で WAV に書き出して聞ける)。数値はすべて仮決め(見本 new_stream / new_lap を 10-31 ユーザー了承「新しい見本で進めてよい」)。
 *
 * <p><b>今は未使用</b>(どこからも呼ばない)。最終の音 final_water_*.wav にユーザー「まだ変に感じる。まだ実装しないでおきましょう」(10-31)。
 * 水音そのものも {@code water.enabled} false で止めてある({@link ForestWaterSound})。作り直すときの土台として残す。
 */
public final class BubbleWaterSynth {

    public static final int SAMPLE_RATE = WindSynth.SAMPLE_RATE;
    public static final int BLOCK = WindSynth.BLOCK;
    /** 大きさ 1 のときの出力の大きさ(2 乗平均の平方根)。約 −22 dBFS({@link WaterSynth} と同じ)。 */
    private static final double OUTPUT_RMS = 0.08;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int MAX_BUBBLES = 128;

    // 大きさをそろえる係数(素の java で測った値、10-31。calibrate で出し直せる)。
    /** 一様な乱数を通した帯の雑音の標準偏差を 1 にする係数: 380 Hz・Q 0.6 / 260 Hz・Q 0.9 / 700 Hz・Q 1.2。 */
    static double BED_UNIT = 13.8748;
    static double LO_UNIT = 13.5179;
    static double MID_UNIT = 7.2221;
    /** せせらぎの泡だけの RMS(乱流の平均で)。下のうねりをこれの 0.55 倍にする。 */
    static double STREAM_BUBBLE_RMS = 0.2455;
    /** せせらぎ・水面それぞれの合計を RMS 1 にする係数。 */
    static double STREAM_NORM = 3.6182;
    static double LAP_NORM = 2.6537;

    private int rng;
    private double flow;
    private double level;
    private boolean primed;

    private final double[] bF = new double[MAX_BUBBLES];
    private final double[] bRise = new double[MAX_BUBBLES];
    private final double[] bDecay = new double[MAX_BUBBLES];
    private final double[] bEnv = new double[MAX_BUBBLES];
    private final double[] bPhase = new double[MAX_BUBBLES];
    private final int[] bAge = new int[MAX_BUBBLES];
    private final int[] bLen = new int[MAX_BUBBLES];
    private final int[] bDelay = new int[MAX_BUBBLES];
    private final double[] bAmp = new double[MAX_BUBBLES];
    private final boolean[] bLap = new boolean[MAX_BUBBLES];
    private int bubbles;

    private final Svf bed = new Svf();
    private final Svf lo = new Svf();
    private final Svf mid = new Svf();
    private final Slow turb1 = new Slow(1.2);
    private final Slow turb2 = new Slow(0.3);
    private double carry;
    /** 次の「ちゃぷ」までのサンプル数と、今の「ちゃぷ」の形。 */
    private int lapWait;
    private int lapAge = -1;
    private double lapGain;
    private double lapRise;
    private double lapFall;
    private double soft;

    public BubbleWaterSynth(int seed) {
        this.rng = seed == 0 ? 0x2545F491 : seed;
        this.bed.set(380.0, 0.6);
        this.lo.set(260.0, 0.9);
        this.mid.set(700.0, 1.2);
        this.lapWait = (int) (SAMPLE_RATE * (0.4 + 1.0 * next01()));
    }

    /**
     * {@code out} の {@code offset} から {@code frames} サンプル(モノラル)を作る。flow・level は呼ぶたびの目標で、ブロックごとになめらかに寄せる。
     */
    public void render(float[] out, int offset, int frames, double flowTarget, double levelTarget) {
        if (!this.primed) {
            this.flow = flowTarget;
            this.level = levelTarget;
            this.primed = true;
        }
        for (int done = 0; done < frames; done += BLOCK) {
            int n = Math.min(BLOCK, frames - done);
            double c = 1.0 - Math.exp(-n / (0.3 * SAMPLE_RATE));
            this.flow += (flowTarget - this.flow) * c;
            this.level += (levelTarget - this.level) * c;
            double wFlow = Math.sqrt(Math.max(0.0, this.flow));
            double wLap = Math.sqrt(Math.max(0.0, 1.0 - this.flow));
            double t1 = this.turb1.next(n);
            double t2 = this.turb2.next(n);
            double turb = Math.max(0.15, 1.0 + 0.6 * t1 + 0.4 * t2);
            double bedTurb = Math.max(0.2, 1.0 + 0.5 * t1 + 0.3 * t2);
            // せせらぎの泡をこのブロックの中にばらまく(流れが無いときは出さない)。
            if (wFlow > 0.01) {
                this.carry += 170.0 * turb * n / SAMPLE_RATE;
                while (this.carry >= 1.0) {
                    this.carry -= 1.0;
                    double rad = radius(0.9, 6.0, 2.2);
                    addBubble(3260.0 / rad, rad * (0.4 + 0.6 * next01()) * 0.25, (int) (next01() * n), false);
                }
            } else {
                this.carry = 0.0;
            }
            // 水面の「ちゃぷ」(水面が無いときは出さない)。
            if (wLap > 0.01) {
                this.lapWait -= n;
                if (this.lapWait <= 0) {
                    this.lapAge = 0;
                    this.lapGain = 0.6 + 0.4 * next01();
                    this.lapRise = 0.12 + 0.08 * next01();
                    this.lapFall = 0.35 + 0.3 * next01();
                    int k = 1 + (int) (next01() * 4.0);
                    for (int j = 0; j < k; j++) {
                        double rad = radius(1.5, 7.0, 1.8);
                        int delay = (int) ((this.lapRise * 0.8 + 0.12 * next01()) * SAMPLE_RATE);
                        addBubble(3260.0 / rad, this.lapGain * (0.9 + 0.6 * next01()), delay, true);
                    }
                    this.lapWait = (int) (SAMPLE_RATE * (1.3 + 2.2 * next01()));
                }
            }
            double gain = OUTPUT_RMS * this.level;
            double softC = 1.0 - Math.exp(-TWO_PI * (5000.0 + 2000.0 * this.flow) / SAMPLE_RATE);
            for (int i = 0; i < n; i++) {
                double x = nextSigned();
                double streamBubbles = 0.0;
                double lapBubbles = 0.0;
                for (int b = 0; b < this.bubbles; b++) {
                    if (this.bDelay[b] > 0) {
                        this.bDelay[b]--;
                        continue;
                    }
                    double v = sample(b);
                    if (this.bLap[b]) {
                        lapBubbles += v;
                    } else {
                        streamBubbles += v;
                    }
                }
                double bedV = this.bed.band(x) * BED_UNIT;
                double loV = this.lo.band(x) * LO_UNIT;
                double midV = this.mid.band(x) * MID_UNIT;
                double stream = (streamBubbles + bedV * STREAM_BUBBLE_RMS * 0.55 * bedTurb) * STREAM_NORM;
                double lapEnv = 0.0;
                if (this.lapAge >= 0) {
                    double tt = this.lapAge / (double) SAMPLE_RATE;
                    lapEnv = tt < this.lapRise ? 0.5 - 0.5 * Math.cos(Math.PI * tt / this.lapRise) : Math.exp(-(tt - this.lapRise) / this.lapFall);
                    this.lapAge++;
                    if (tt > this.lapRise + this.lapFall * 4.0) {
                        this.lapAge = -1;
                    }
                }
                double lap = (this.lapGain * lapEnv * (loV + 0.45 * midV) + lapBubbles + 0.04 * loV) * LAP_NORM;
                double v = (wFlow * stream + wLap * lap) * gain;
                this.soft += (v - this.soft) * softC;
                out[offset + done + i] = (float) this.soft;
            }
            removeDead();
        }
    }

    /** 泡 1 つの次のサンプル。 */
    private double sample(int b) {
        int age = this.bAge[b]++;
        double f = this.bF[b] * (1.0 + this.bRise[b] * age);
        this.bPhase[b] += TWO_PI * f / SAMPLE_RATE;
        double att = age < 66 ? 0.5 - 0.5 * Math.cos(Math.PI * age / 66.0) : 1.0;
        double v = this.bAmp[b] * att * this.bEnv[b] * Math.sin(this.bPhase[b]);
        this.bEnv[b] *= this.bDecay[b];
        return v;
    }

    private void addBubble(double f0, double amp, int delay, boolean lap) {
        if (this.bubbles >= MAX_BUBBLES) {
            return;
        }
        double d = 0.043 * f0 + 0.0014 * Math.pow(f0, 1.5);
        int b = this.bubbles++;
        this.bF[b] = f0;
        // f(t) = f0 (1 + ξ d t)、ξ = 0.1。サンプルごとの係数にしておく。
        this.bRise[b] = 0.1 * d / SAMPLE_RATE;
        this.bDecay[b] = Math.exp(-d / SAMPLE_RATE);
        this.bEnv[b] = 1.0;
        this.bPhase[b] = 0.0;
        this.bAge[b] = 0;
        this.bLen[b] = (int) Math.min(SAMPLE_RATE * 6.0 / d, SAMPLE_RATE * 0.25);
        this.bDelay[b] = delay;
        this.bAmp[b] = amp;
        this.bLap[b] = lap;
    }

    private void removeDead() {
        int w = 0;
        for (int b = 0; b < this.bubbles; b++) {
            if (this.bAge[b] >= this.bLen[b]) {
                continue;
            }
            if (w != b) {
                this.bF[w] = this.bF[b];
                this.bRise[w] = this.bRise[b];
                this.bDecay[w] = this.bDecay[b];
                this.bEnv[w] = this.bEnv[b];
                this.bPhase[w] = this.bPhase[b];
                this.bAge[w] = this.bAge[b];
                this.bLen[w] = this.bLen[b];
                this.bDelay[w] = this.bDelay[b];
                this.bAmp[w] = this.bAmp[b];
                this.bLap[w] = this.bLap[b];
            }
            w++;
        }
        this.bubbles = w;
    }

    /** 泡の半径(mm)を、べき分布 r^-beta で rMin〜rMax から。 */
    private double radius(double rMin, double rMax, double beta) {
        double a = Math.pow(rMin, 1.0 - beta);
        double b = Math.pow(rMax, 1.0 - beta);
        return Math.pow(a + (b - a) * next01(), 1.0 / (1.0 - beta));
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

    /** 状態変数フィルター(帯域通過。WindSynth と同じ式)。 */
    private static final class Svf {
        private double a1;
        private double a2;
        private double a3;
        private double ic1;
        private double ic2;

        void set(double hz, double q) {
            double g = Math.tan(Math.PI * Math.min(hz, SAMPLE_RATE * 0.45) / SAMPLE_RATE);
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

    /** ゆっくりした雑音(2 段の低域通過。ばらつき 0.45 にそろえる。WindSynth の SlowNoise と同じ)。 */
    private final class Slow {
        private final double rate;
        private double s1;
        private double s2;

        Slow(double hz) {
            this.rate = hz;
        }

        double next(int samples) {
            double coef = 1.0 - Math.exp(-TWO_PI * this.rate * samples / SAMPLE_RATE);
            this.s1 += (nextSigned() - this.s1) * coef;
            this.s2 += (this.s1 - this.s2) * coef;
            double a = 1.0 - coef;
            double std = Math.sqrt(coef * (1.0 + a * a) / Math.pow(2.0 - coef, 3.0) / 3.0);
            return Math.max(-1.0, Math.min(1.0, this.s2 * 0.45 / Math.max(std, 1.0E-9)));
        }
    }

    /**
     * 確かめ用: {@code java BubbleWaterSynth.java <出力.wav> <flow> <秒>} で WAV を書き、山・RMS・隣との差を出す。
     * {@code calibrate} で大きさをそろえる係数を測り直して出す(出た値を上の定数へ)。
     */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length > 0 && args[0].equals("calibrate")) {
            calibrate();
            return;
        }
        String path = args.length > 0 ? args[0] : "water.wav";
        double flow = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 10.0;
        int n = (int) (seconds * SAMPLE_RATE);
        float[] buf = new float[n];
        BubbleWaterSynth s = new BubbleWaterSynth(12345);
        for (int at = 0; at < n; at += 6615) {
            s.render(buf, at, Math.min(6615, n - at), flow, 1.0);
        }
        // 頭と尾は 0.4 秒の余弦(見本と同じに聞き比べるため。ゲームでは大きさの寄せが代わりになる)
        int m = (int) (0.4 * SAMPLE_RATE);
        for (int i = 0; i < m; i++) {
            double w = 0.5 - 0.5 * Math.cos(Math.PI * i / m);
            buf[i] *= (float) w;
            buf[n - 1 - i] *= (float) w;
        }
        double peak = 0;
        double sum2 = 0;
        double maxStep = 0;
        for (int i = 0; i < n; i++) {
            peak = Math.max(peak, Math.abs(buf[i]));
            sum2 += buf[i] * buf[i];
            if (i > 0) {
                maxStep = Math.max(maxStep, Math.abs(buf[i] - buf[i - 1]));
            }
        }
        double rms = Math.sqrt(sum2 / n);
        System.out.printf("%s flow %.2f: 山 %.3f / RMS %.4f(%.1f dBFS)/ 山 ÷ RMS %.2f = %.1f dB / 隣との差の最大 %.4f(RMS の %.1f 倍)%n",
                path, flow, peak, rms, 20 * Math.log10(rms), peak / rms, 20 * Math.log10(peak / rms), maxStep, maxStep / rms);
        writeWav(path, buf);
    }

    static void writeWav(String path, float[] buf) throws java.io.IOException {
        int n = buf.length;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(n * 2);
        for (float v : buf) {
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, v)) * 32767));
        }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(path)) {
            o.write(b.array());
        }
    }

    /** 係数を 1 に戻して 60 秒ぶん鳴らし、各部分の RMS から係数を出す。 */
    private static void calibrate() {
        int n = SAMPLE_RATE * 60;
        BubbleWaterSynth s = new BubbleWaterSynth(777);
        double sb = 0, sl = 0, sm = 0;
        for (int i = 0; i < n; i++) {
            double x = s.nextSigned();
            double a = s.bed.band(x);
            double b = s.lo.band(x);
            double c = s.mid.band(x);
            sb += a * a;
            sl += b * b;
            sm += c * c;
        }
        BED_UNIT = 1 / Math.sqrt(sb / n);
        LO_UNIT = 1 / Math.sqrt(sl / n);
        MID_UNIT = 1 / Math.sqrt(sm / n);
        STREAM_NORM = 1.0;
        LAP_NORM = 1.0;
        STREAM_BUBBLE_RMS = 0.0;
        STREAM_BUBBLE_RMS = measure(1.0);
        STREAM_NORM = 1.0 / measure(1.0);
        LAP_NORM = 1.0 / measure(0.0);
        System.out.printf("BED_UNIT %.4f / LO_UNIT %.4f / MID_UNIT %.4f / STREAM_BUBBLE_RMS %.4f / STREAM_NORM %.4f / LAP_NORM %.4f%n",
                BED_UNIT, LO_UNIT, MID_UNIT, STREAM_BUBBLE_RMS, STREAM_NORM, LAP_NORM);
    }

    /** 60 秒鳴らした出力の RMS ÷ OUTPUT_RMS。 */
    private static double measure(double flow) {
        int n = SAMPLE_RATE * 60;
        float[] buf = new float[n];
        BubbleWaterSynth s = new BubbleWaterSynth(4242);
        for (int at = 0; at < n; at += 6615) {
            s.render(buf, at, Math.min(6615, n - at), flow, 1.0);
        }
        double sum2 = 0;
        for (float v : buf) {
            sum2 += v * v;
        }
        return Math.sqrt(sum2 / n) / OUTPUT_RMS;
    }
}
