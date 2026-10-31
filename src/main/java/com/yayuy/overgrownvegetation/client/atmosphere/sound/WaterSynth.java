package com.yayuy.overgrownvegetation.client.atmosphere.sound;

/**
 * 森の中の<b>水音の合成器</b>(合間の小物、案 E1。2026-10-31)。風の音({@link WindSynth})と同じ作り: 録音を流さず、なめらかな雑音の帯だけで作る
 * (とがった粒・泡のはじける音は入れない。memory synth-sound-no-crackle)。
 *
 * <ul>
 *     <li><b>せせらぎ</b>(流れる水): 中〜高い帯(900〜2600 Hz)の雑音。帯の高さと大きさが、それぞれ毎秒 0.5〜3 回ほどの別々の速さでゆっくりゆらぐ
 *     (一定の雑音だと「さー」という機械の音に聞こえる)。</li>
 *     <li><b>たぷたぷ</b>(水面): 低い帯(180〜420 Hz)の雑音が、数秒おきにゆっくりふくらんでしぼむ(岸に寄せる小さな波)。</li>
 * </ul>
 * 混ぜ方は呼び手が {@code flow}(0 = 水面だけ・1 = 流れだけ)で渡し、大きさは {@code level}。音の中身の大きさは flow によらずそろえる。
 * モノラル(呼び手が水のある所に置いて向きを付ける)。<b>Minecraft の型を使わない</b>(素の java で {@link #main} から WAV に書き出して聞ける)。
 * 数値はすべて仮決め。
 */
public final class WaterSynth {

    public static final int SAMPLE_RATE = WindSynth.SAMPLE_RATE;
    public static final int BLOCK = WindSynth.BLOCK;
    /** 大きさ 1 のときの出力の大きさ(2 乗平均の平方根)。約 −22 dBFS。 */
    private static final double OUTPUT_RMS = 0.08;
    private static final double TWO_PI = Math.PI * 2.0;

    private int rng;
    private double flow;
    private double level;
    private boolean primed;

    private final Svf babble1 = new Svf();
    private final Svf babble2 = new Svf();
    private final Svf lap = new Svf();
    private final Slow pitch1 = new Slow(0.6);
    private final Slow pitch2 = new Slow(1.7);
    private final Slow flutter = new Slow(2.8);
    private final Slow swell = new Slow(0.18);
    /** 全体の高い音を少し丸める 1 次の低域通過(林の中の距離)。 */
    private double soft;
    /** たぷたぷの帯の上の裾を削る 1 次の低域通過(600 Hz)。 */
    private double lapLow;

    public WaterSynth(int seed) {
        this.rng = seed == 0 ? 0x2545F491 : seed;
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
            // 0.3 秒ほどで目標へ(大きさの段差でぷつっと鳴らないように)。
            double c = 1.0 - Math.exp(-n / (0.3 * SAMPLE_RATE));
            this.flow += (flowTarget - this.flow) * c;
            this.level += (levelTarget - this.level) * c;
            double p1 = this.pitch1.next(n);
            double p2 = this.pitch2.next(n);
            double fl = this.flutter.next(n);
            double sw = this.swell.next(n);
            this.babble1.set(1400.0 * Math.pow(2.0, 0.55 * p1), 2.2);
            this.babble2.set(2300.0 * Math.pow(2.0, 0.45 * p2), 3.0);
            this.lap.set(280.0 * Math.pow(2.0, 0.4 * sw), 1.4);
            // せせらぎの大きさのゆらぎ(0.55〜1.45)、たぷたぷのふくらみ(0.25〜1.75)。
            double babbleAmp = Math.max(0.2, 1.0 + 0.8 * fl);
            double lapAmp = 1.0 + 0.75 * Math.max(-1.0, Math.min(1.0, sw / 0.45));
            // 帯の雑音の標準偏差を 1 にそろえる係数(Q と帯の幅から。素の java で WAV に書いて測った値、10-31)。
            double gBabble = 3.6;
            double gLap = 11.1;
            double wFlow = Math.sqrt(this.flow);
            double wLap = Math.sqrt(1.0 - this.flow);
            double gain = OUTPUT_RMS * this.level;
            double softC = 1.0 - Math.exp(-TWO_PI * 5000.0 / SAMPLE_RATE);
            double lapC = 1.0 - Math.exp(-TWO_PI * 600.0 / SAMPLE_RATE);
            for (int i = 0; i < n; i++) {
                double x = nextSigned();
                double b = (0.65 * this.babble1.band(x) + 0.45 * this.babble2.band(x)) * gBabble * babbleAmp;
                this.lapLow += (this.lap.band(x) - this.lapLow) * lapC;
                double l = this.lapLow * gLap * lapAmp;
                double v = (wFlow * b + wLap * l) * gain;
                this.soft += (v - this.soft) * softC;
                out[offset + done + i] = (float) this.soft;
            }
        }
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

    /** 確かめ用: {@code java WaterSynth.java <出力.wav> <flow> <秒>} で WAV を書き、山・RMS・隣との差・帯ごとの強さを出す。 */
    public static void main(String[] args) throws java.io.IOException {
        String path = args.length > 0 ? args[0] : "water.wav";
        double flow = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 8.0;
        int n = (int) (seconds * SAMPLE_RATE);
        float[] buf = new float[n];
        WaterSynth s = new WaterSynth(12345);
        for (int at = 0; at < n; at += 6615) {
            s.render(buf, at, Math.min(6615, n - at), flow, 1.0);
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
}
