package com.yayuy.overgrownvegetation.client.atmosphere.sound;

/**
 * <b>地下と水中の環境音の合成器</b>(2026-11-01。見本 C1・C2・C3・W1 をユーザー採用、C4・W2・W3 は不採用)。見本の合成器
 * (共有ノートの {@code 環境音-地下と水中-見本/Under.java})を、ゲームで流せる形へ移したもの。
 *
 * <ul>
 *     <li>{@link Drip}(C1): 天井から落ちる滴 1 つと洞窟の響き(残響 2.4 秒)。黒い霧で見通しが効かない洞窟の広さと湿り気を耳で伝える。</li>
 *     <li>{@link Creak}(C2): 巨大樹の根が天井の岩をこする遠い「ぎぃ……」1 回。森が地下まで来ている気配。</li>
 *     <li>{@link Hum}(C3): 胞子の溜まりの低いうなり(50〜62 Hz、約 6 秒の脈)。近づくと感染が進む危険を大きさで知らせる。流し続ける。</li>
 *     <li>{@link Underwater}(W1): 潜っている間のこもった低音。川・池 = 流れの揺らぎと遠い泡、海 = もっと低く広いうねり({@link Underwater#sea} で寄せる)。流し続ける。</li>
 * </ul>
 * 立ち上がりは余弦の窓(とがった縁を作らない。memory synth-sound-no-crackle)。どれも大きさ 1 で山が約 0.5(見本と同じ)。モノラル。
 * <b>Minecraft の型を使わない</b>({@link #main} で、ゲームで鳴る形の WAV を書き出して聞ける)。数値はすべて仮決め(見本の報告 環境音-地下と水中-報告-2026-11-01.md)。
 */
public final class CaveWaterSynth {

    public static final int SAMPLE_RATE = WindSynth.SAMPLE_RATE;
    private static final double TWO_PI = Math.PI * 2.0;

    private CaveWaterSynth() {
    }

    /**
     * 滴 1 つ(C1)。水面へ落ちる滴 = 0.85〜1.3 kHz の正弦(2.5 ms の余弦で立ち上がり τ 9〜12 ms で減る)+ 2 倍の薄い倍音 + 0.10〜0.15 秒後に −9 dB の
     * 跳ね返りの小滴 + 水面を打つ 3 ms の擦れ。岩へ落ちる滴 = 音程の無い 4 ms の帯の雑音の粒(1.6〜2.8 kHz)+ 30 ms に散る 3〜5 個の飛沫 + 鈍い胴。
     * 洞窟の響き(残響 2.4 秒・前の遅れ 25 ms・3.5 kHz の低域通過)を焼き込む。distance(ブロック)が遠いほど暗く、響きの割合が大きい。全体で約 3 秒。
     */
    public static final class Drip extends InsectSynth.Prerendered {
        private final int seed;
        private final boolean pool;
        private final double distance;

        public Drip(int seed, boolean pool, double distance) {
            this.seed = seed;
            this.pool = pool;
            this.distance = distance;
        }

        @Override
        float[] build() {
            InsectSynth.Rng r = new InsectSynth.Rng(this.seed);
            double[] d = new double[(int) (3.0 * SAMPLE_RATE)];
            int at = (int) (0.02 * SAMPLE_RATE);
            if (this.pool) {
                double f = 850.0 + 450.0 * r.next01();
                double rise = 1.15 + 0.2 * r.next01();
                double tau = 0.009 + 0.003 * r.next01();
                tone(d, at, f, rise, tau, 0.0025, 1.0);
                tone(d, at, f * 2.02, rise, tau * 0.5, 0.0025, 0.12);
                tone(d, at + (int) ((0.1 + 0.05 * r.next01()) * SAMPLE_RATE), 1600.0 + 500.0 * r.next01(), 1.1, 0.005, 0.002, 0.35);
                grain(d, at, 0.003, 2200.0, 1.5, 0.18, r);
            } else {
                grain(d, at, 0.004, 1600.0 + 1200.0 * r.next01(), 1.6, 1.0, r);
                int n = 3 + (int) (r.next01() * 2.99);
                for (int i = 0; i < n; i++) {
                    grain(d, at + (int) ((0.004 + 0.03 * r.next01()) * SAMPLE_RATE), 0.002 + 0.002 * r.next01(), 2500.0 + 2500.0 * r.next01(), 2.0,
                            0.15 + 0.2 * r.next01(), r);
                }
                tone(d, at, 420.0 + 120.0 * r.next01(), 1.0, 0.006, 0.002, 0.2);
            }
            // 遠さ: 見本の 1〜3.2 を 4〜20 ブロックへ(dist 1 = 4 ブロック)。
            double dist = Math.max(1.0, this.distance / 4.0);
            d = lowpass(lowpass(d, 9000.0 / dist), 9000.0 / dist);
            double direct = 1.0 / dist;
            double send = (0.55 + 0.1 * dist) / Math.sqrt(dist);
            double[] wet = new double[d.length];
            for (int i = 0; i < d.length; i++) {
                wet[i] = d[i] * send;
            }
            wet = lowpass(CaveReverb.render(wet, 2.4, 1.5, 0.45, 0.025), 3500.0);
            double[] out = new double[d.length];
            for (int i = 0; i < d.length; i++) {
                out[i] = d[i] * direct + wet[i] * 0.9;
            }
            return normalize(fadeOut(out, 0.3), 0.5);
        }
    }

    /**
     * 根の軋み 1 回(C2)。柔らかい脈(1.5 ms の余弦)の列を木の胴の響き 160 / 380 / 820 Hz(Q 5〜7)に通す。脈の速さは 1 回の中で
     * 48〜56 → 70〜78 → 50〜58 Hz とゆっくり上がって下がり、脈ごとに間 ±7%・大きさ ±30% ゆらす(参考: 大枝の軋み ≒ 71 Hz・縄の軋み ≒ 94 Hz)。
     * 下に岩の圧の低いうなり(90 Hz 中心)。遠いので 1.1 kHz の 2 段の低域通過と長い響き(3.2 秒)。1 回 2.5〜4.5 秒 + 響き。
     */
    public static final class Creak extends InsectSynth.Prerendered {
        private final int seed;
        private final double distance;

        public Creak(int seed, double distance) {
            this.seed = seed;
            this.distance = distance;
        }

        @Override
        float[] build() {
            InsectSynth.Rng r = new InsectSynth.Rng(this.seed);
            double dur = 2.5 + 2.0 * r.next01();
            double[] rates = {48.0 + 8.0 * r.next01(), 70.0 + 8.0 * r.next01(), 50.0 + 8.0 * r.next01()};
            int n = (int) (dur * SAMPLE_RATE);
            int total = n + (int) (3.5 * SAMPLE_RATE);
            double[] pulse = new double[n];
            int pw = (int) (0.0015 * SAMPLE_RATE);
            double next = 0.0;
            while (next < n) {
                double u = next / n;
                double rate = u < 0.5 ? rates[0] + (rates[1] - rates[0]) * InsectSynth.smooth(u * 2.0)
                        : rates[1] + (rates[2] - rates[1]) * InsectSynth.smooth(u * 2.0 - 1.0);
                double amp = 1.0 + 0.3 * r.nextSigned();
                int i = (int) next;
                for (int k = 0; k < pw && i + k < n; k++) {
                    pulse[i + k] += amp * (0.5 - 0.5 * Math.cos(TWO_PI * k / pw));
                }
                next += SAMPLE_RATE / rate * (1.0 + 0.07 * r.nextSigned());
            }
            InsectSynth.Band b1 = new InsectSynth.Band(160.0, 5.0);
            InsectSynth.Band b2 = new InsectSynth.Band(380.0, 6.0);
            InsectSynth.Band b3 = new InsectSynth.Band(820.0, 7.0);
            InsectSynth.Band groan = new InsectSynth.Band(90.0, 1.2);
            double wobble = r.next01() * TWO_PI;
            double[] x = new double[total];
            for (int i = 0; i < n; i++) {
                double u = i / (double) n;
                double env = InsectSynth.envAD(u, 0.25, 0.3) * (0.85 + 0.15 * Math.sin(TWO_PI * 0.9 * u * dur + wobble));
                double p = pulse[i] * env;
                x[i] = b1.band(p) + 0.8 * b2.band(p) + 0.45 * b3.band(p) + 0.6 * groan.band(r.gauss() * 0.15) * InsectSynth.envAD(u, 0.35, 0.35);
            }
            // 遠さ: 見本の 1〜1.8 を 20〜40 ブロックへ(dist 1 = 20 ブロック)。
            double dist = Math.max(1.0, this.distance / 20.0);
            double lp = 1100.0 / Math.sqrt(dist);
            x = lowpass(lowpass(x, lp), lp);
            double[] send = new double[total];
            for (int i = 0; i < total; i++) {
                send[i] = x[i] * 0.8;
            }
            double[] wet = lowpass(CaveReverb.render(send, 3.2, 1.8, 0.6, 0.04), 1500.0);
            double[] out = new double[total];
            for (int i = 0; i < total; i++) {
                out[i] = x[i] * 0.6 / dist + wet[i];
            }
            return normalize(fadeOut(out, 0.5), 0.5);
        }
    }

    /**
     * 胞子の溜まりのうなり(C3、流し続ける)。狭い帯の雑音 2 本(52 / 61 Hz、Q 10)+ 少しずれた正弦 2 本(55.0 / 55.4 Hz)。高さは ±1.5 Hz でゆっくりさまよう
     * (試験音にしない)。0.17 Hz(約 6 秒に 1 回)で大きさ 0.55〜1.0 の脈。小さいスピーカー用に 2 倍(110 Hz)を −10 dB・3 倍を −20 dB、
     * 脈の山でだけ 300〜700 Hz の湿った息を −30 dB。大きさ(近さ)は音源の volume で決める。
     */
    public static final class Hum {
        /** 大きさ 1 で山が約 0.5 になる倍率({@link #main} で測った)。 */
        private static final double OUT = 0.16;
        private final InsectSynth.Rng r;
        private final InsectSynth.Band n1 = new InsectSynth.Band(52.0, 10.0);
        private final InsectSynth.Band n2 = new InsectSynth.Band(61.0, 10.0);
        private final InsectSynth.Band breath = new InsectSynth.Band(480.0, 1.5);
        private double d1;
        private double d2;
        private double ph1;
        private double ph2;
        private double ph3;
        private double ph4;
        private long age;

        public Hum(int seed) {
            this.r = new InsectSynth.Rng(seed);
        }

        public void render(float[] out, int offset, int frames, double level) {
            for (int i = 0; i < frames; i++) {
                double t = this.age / (double) SAMPLE_RATE;
                if (this.age % 441 == 0) {
                    this.d1 = this.d1 * 0.995 + this.r.gauss() * 0.04;
                    this.d2 = this.d2 * 0.995 + this.r.gauss() * 0.04;
                }
                double w1 = Math.max(-1.5, Math.min(1.5, this.d1));
                double w2 = Math.max(-1.5, Math.min(1.5, this.d2));
                double pulse = 0.55 + 0.45 * InsectSynth.smooth(0.5 + 0.5 * Math.sin(TWO_PI * 0.17 * t + 0.4 * Math.sin(TWO_PI * 0.031 * t)));
                this.ph1 = (this.ph1 + TWO_PI * (55.0 + w1) / SAMPLE_RATE) % TWO_PI;
                this.ph2 = (this.ph2 + TWO_PI * (55.4 + w2) / SAMPLE_RATE) % TWO_PI;
                this.ph3 = (this.ph3 + TWO_PI * 2.0 * (55.2 + w1) / SAMPLE_RATE) % TWO_PI;
                this.ph4 = (this.ph4 + TWO_PI * 3.0 * (55.2 + w2) / SAMPLE_RATE) % TWO_PI;
                double y = 3.0 * this.n1.band(this.r.gauss()) + 3.0 * this.n2.band(this.r.gauss())
                        + 0.35 * Math.sin(this.ph1) + 0.3 * Math.sin(this.ph2)
                        + 0.21 * Math.sin(this.ph3) * (0.7 + 0.3 * pulse) + 0.06 * Math.sin(this.ph4);
                y *= pulse;
                double b = Math.max(0.0, pulse - 0.75) / 0.25;
                y += 0.02 * this.breath.band(this.r.gauss()) * b * b;
                out[offset + i] += (float) (y * OUT * level);
                this.age++;
            }
        }
    }

    /**
     * 水中のこもった低音(W1、流し続ける)。川・池 = ピンクに近い雑音を 520 Hz の 2 段の低域通過、流れの揺らぎ(速さの違う 3 本のさまよい、±40% まで)、
     * 遠いこもった泡(450〜700 Hz、毎秒 3 個前後)。海 = 300 Hz の 2 段の低域通過 + 38 Hz 中心の深いうなり、うねり 0.09 Hz(約 11 秒)±30%、泡なし。
     * {@link #sea}(0 = 川・池、1 = 海)へ 2 秒ほどで寄せ、2 つを混ぜる。{@link #depth}(0〜1)が深いほど低域通過を最大 25% 下げる(大きさは呼び手が下げる)。
     */
    public static final class Underwater {
        /** 大きさ 1 で山が約 0.5 になる倍率({@link #main} で測った)。 */
        private static final double OUT_RIVER = 0.066;
        private static final double OUT_SEA = 0.063;
        public volatile float sea;
        public volatile float depth;
        private final InsectSynth.Rng r;
        private double b0;
        private double b1;
        private double b2;
        private final double[] turb = new double[3];
        private final double[] lpRiver = new double[2];
        private final double[] lpSea = new double[2];
        private final double[] lpBubble = new double[2];
        private double hpRiver;
        private double hpSea;
        private final InsectSynth.Band deep = new InsectSynth.Band(38.0, 4.0);
        private final double lfoA;
        private final double lfoB;
        private double seaNow;
        private double depthNow;
        private long age;
        /** 泡: 次の泡までの残り・今鳴っている泡(高さ・上がり・減り・経過)。 */
        private int nextBubble;
        private double bf;
        private double brise;
        private double btau;
        private double bgain;
        private double bph;
        private int bage = Integer.MAX_VALUE;

        public Underwater(int seed, boolean sea) {
            this.r = new InsectSynth.Rng(seed);
            this.sea = sea ? 1.0F : 0.0F;
            this.seaNow = this.sea;
            this.lfoA = this.r.next01() * TWO_PI;
            this.lfoB = this.r.next01() * TWO_PI;
        }

        public void render(float[] out, int offset, int frames, double level) {
            double seaTarget = this.sea;
            double depthTarget = this.depth;
            double cRiverBase = 1.0 - Math.exp(-TWO_PI * 520.0 / SAMPLE_RATE);
            double cSeaBase = 1.0 - Math.exp(-TWO_PI * 300.0 / SAMPLE_RATE);
            double cBub = 1.0 - Math.exp(-TWO_PI * 900.0 / SAMPLE_RATE);
            double hpR = 1.0 - Math.exp(-TWO_PI * 40.0 / SAMPLE_RATE);
            double hpS = 1.0 - Math.exp(-TWO_PI * 25.0 / SAMPLE_RATE);
            for (int i = 0; i < frames; i++) {
                if (this.age % 441 == 0) {
                    this.seaNow += (seaTarget - this.seaNow) * 0.05;
                    this.depthNow += (depthTarget - this.depthNow) * 0.05;
                    for (int k = 0; k < 3; k++) {
                        this.turb[k] = this.turb[k] * (0.96 - 0.012 * k) + this.r.gauss() * 0.06;
                    }
                }
                double t = this.age / (double) SAMPLE_RATE;
                double w = this.r.gauss();
                this.b0 = 0.99765 * this.b0 + w * 0.0990460;
                this.b1 = 0.96300 * this.b1 + w * 0.2965164;
                this.b2 = 0.57000 * this.b2 + w * 1.0526913;
                double pink = this.b0 + this.b1 + this.b2 + w * 0.1848;
                double low = 1.0 - 0.25 * this.depthNow;
                // 川・池
                double modR = 1.0 + Math.max(-0.4, Math.min(0.4, this.turb[0] + 0.7 * this.turb[1] + 0.5 * this.turb[2]));
                double vr = pink * modR;
                double cr = cRiverBase * low;
                this.lpRiver[0] += (vr - this.lpRiver[0]) * cr;
                this.lpRiver[1] += (this.lpRiver[0] - this.lpRiver[1]) * cr;
                double river = this.lpRiver[1] + 0.25 * bubble(cBub);
                this.hpRiver += (river - this.hpRiver) * hpR;
                river -= this.hpRiver;
                // 海
                double modS = 1.0 + 0.3 * Math.sin(TWO_PI * 0.09 * t + this.lfoA) + 0.08 * Math.sin(TWO_PI * 0.23 * t + this.lfoB);
                double vs = pink * modS + 4.0 * this.deep.band(this.r.gauss()) * (0.8 + 0.2 * Math.sin(TWO_PI * 0.09 * t + this.lfoA + 1.0));
                double cs = cSeaBase * low;
                this.lpSea[0] += (vs - this.lpSea[0]) * cs;
                this.lpSea[1] += (this.lpSea[0] - this.lpSea[1]) * cs;
                double seaV = this.lpSea[1];
                this.hpSea += (seaV - this.hpSea) * hpS;
                seaV -= this.hpSea;
                double y = river * OUT_RIVER * (1.0 - this.seaNow) + seaV * OUT_SEA * this.seaNow;
                out[offset + i] += (float) (y * level);
                this.age++;
            }
        }

        /** 遠いこもった泡を 1 標本ぶん(毎秒 3 個前後。450〜700 Hz、τ 15 ms、1.12 倍へ上がる、900 Hz の 2 段の低域通過)。 */
        private double bubble(double c) {
            if (--this.nextBubble <= 0) {
                this.nextBubble = (int) (-Math.log(1.0 - 0.999 * this.r.next01()) / 3.0 * SAMPLE_RATE) + 1;
                this.bf = 450.0 + 250.0 * this.r.next01();
                this.brise = 1.12;
                this.btau = 0.015;
                this.bgain = 0.5 + 0.5 * this.r.next01();
                this.bage = 0;
            }
            double v = 0.0;
            if (this.bage < this.btau * 7.0 * SAMPLE_RATE) {
                double s = this.bage / (double) SAMPLE_RATE;
                double f = this.bf * (1.0 + (this.brise - 1.0) * (1.0 - Math.exp(-s / (this.btau * 3.0))));
                this.bph += TWO_PI * f / SAMPLE_RATE;
                v = this.bgain * InsectSynth.smooth(s / 0.003) * Math.exp(-s / this.btau) * Math.sin(this.bph);
                this.bage++;
            }
            this.lpBubble[0] += (v - this.lpBubble[0]) * c;
            this.lpBubble[1] += (this.lpBubble[0] - this.lpBubble[1]) * c;
            return this.lpBubble[1];
        }
    }

    // ---------------------------------------------------------------- 道具

    /** 余弦で立ち上がり指数で減る正弦。高さは τ の 3 倍かけて rise 倍へ(泡の上がり方)。 */
    static void tone(double[] d, int i0, double f, double rise, double tau, double att, double g) {
        int n = (int) (tau * 7.0 * SAMPLE_RATE);
        double ph = 0.0;
        for (int k = 0; k < n && i0 + k < d.length; k++) {
            double t = k / (double) SAMPLE_RATE;
            ph += TWO_PI * f * (1.0 + (rise - 1.0) * (1.0 - Math.exp(-t / (tau * 3.0)))) / SAMPLE_RATE;
            d[i0 + k] += g * InsectSynth.smooth(t / att) * Math.exp(-t / tau) * Math.sin(ph);
        }
    }

    /** 余弦の窓をかけた帯の雑音の粒(とがらせない)。 */
    static void grain(double[] d, int i0, double len, double fc, double q, double g, InsectSynth.Rng r) {
        InsectSynth.Band bp = new InsectSynth.Band(fc, q);
        int n = Math.max(2, (int) (len * SAMPLE_RATE));
        for (int k = 0; k < n * 3 && i0 + k < d.length; k++) {
            double u = k / (double) n;
            double w = u < 1.0 ? 0.5 - 0.5 * Math.cos(TWO_PI * u) : 0.0;
            d[i0 + k] += g * bp.band(r.gauss() * w) * 3.0;
        }
    }

    static double[] lowpass(double[] x, double hz) {
        double c = 1.0 - Math.exp(-TWO_PI * hz / SAMPLE_RATE);
        double y = 0.0;
        double[] o = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y += (x[i] - y) * c;
            o[i] = y;
        }
        return o;
    }

    static double[] fadeOut(double[] x, double sec) {
        int n = Math.min(x.length, (int) (sec * SAMPLE_RATE));
        for (int i = 0; i < n; i++) {
            x[x.length - 1 - i] *= InsectSynth.smooth(i / (double) n);
        }
        return x;
    }

    static float[] normalize(double[] x, double peak) {
        double p = 1e-9;
        for (double v : x) {
            p = Math.max(p, Math.abs(v));
        }
        float[] f = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            f[i] = (float) (x[i] * peak / p);
        }
        return f;
    }

    /** 洞窟の響き(Freeverb の形のモノ版。櫛 8 本 + 全域通過 4 本)。rt = −60 dB までの秒、size = 遅れの倍率、damp = 櫛の中の高い音の減り、pre = 前の遅れ(秒)。 */
    static final class CaveReverb {
        private static final int[] COMBS = {1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116};
        private static final int[] ALLPASS = {556, 441, 341, 225};

        static double[] render(double[] x, double rt, double size, double damp, double pre) {
            int n = x.length;
            int p = (int) (pre * SAMPLE_RATE);
            double[] out = new double[n];
            for (int c : COMBS) {
                int d = (int) (c * size);
                double g = Math.pow(10.0, -3.0 * d / (rt * SAMPLE_RATE));
                double[] buf = new double[d];
                double lp = 0.0;
                int idx = 0;
                for (int i = 0; i < n; i++) {
                    double in = i - p >= 0 ? x[i - p] : 0.0;
                    double y = buf[idx];
                    lp = y * (1.0 - damp) + lp * damp;
                    buf[idx] = in + lp * g;
                    idx = (idx + 1) % d;
                    out[i] += y;
                }
            }
            for (int a : ALLPASS) {
                int d = (int) (a * size);
                double[] buf = new double[d];
                int idx = 0;
                for (int i = 0; i < n; i++) {
                    double b = buf[idx];
                    double y = -out[i] + b;
                    buf[idx] = out[i] + b * 0.5;
                    idx = (idx + 1) % d;
                    out[i] = y;
                }
            }
            for (int i = 0; i < n; i++) {
                out[i] *= 0.125;
            }
            return out;
        }
    }

    /**
     * 確かめ用: ゲームで鳴る形の WAV を書き、山を出す。{@code javac -d <tmp> CaveWaterSynth.java InsectSynth.java WindSynth.java} のあと
     * {@code java -cp <tmp> …CaveWaterSynth <出力フォルダ>}。drip_pool_4 / drip_rock_12 / creak_30 は 1 回ぶん、hum・river・sea は 20 秒(大きさ 1)。
     */
    public static void main(String[] args) throws java.io.IOException {
        java.io.File dir = new java.io.File(args.length > 0 ? args[0] : ".");
        write(new java.io.File(dir, "game_drip_pool_4.wav"), all(new Drip(11, true, 4.0)));
        write(new java.io.File(dir, "game_drip_pool_16.wav"), all(new Drip(12, true, 16.0)));
        write(new java.io.File(dir, "game_drip_rock_12.wav"), all(new Drip(13, false, 12.0)));
        write(new java.io.File(dir, "game_creak_30.wav"), all(new Creak(14, 30.0)));
        int n = SAMPLE_RATE * 20;
        float[] hum = new float[n];
        new Hum(15).render(hum, 0, n, 1.0);
        write(new java.io.File(dir, "game_hum.wav"), hum);
        float[] river = new float[n];
        Underwater uw = new Underwater(16, false);
        for (int i = 0; i < n; i += 6615) {
            uw.render(river, i, Math.min(6615, n - i), 1.0);
        }
        write(new java.io.File(dir, "game_river.wav"), river);
        float[] sea = new float[n];
        Underwater us = new Underwater(17, true);
        for (int i = 0; i < n; i += 6615) {
            us.render(sea, i, Math.min(6615, n - i), 1.0);
        }
        write(new java.io.File(dir, "game_sea.wav"), sea);
    }

    private static float[] all(InsectSynth.Call c) {
        java.util.List<float[]> parts = new java.util.ArrayList<>();
        int total = 0;
        while (!c.done() && total < SAMPLE_RATE * 30) {
            float[] b = new float[SAMPLE_RATE / 10];
            c.render(b, 0, b.length, 1.0);
            parts.add(b);
            total += b.length;
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] b : parts) {
            System.arraycopy(b, 0, out, at, b.length);
            at += b.length;
        }
        return out;
    }

    private static void write(java.io.File f, float[] x) throws java.io.IOException {
        double peak = 0.0;
        for (float v : x) {
            peak = Math.max(peak, Math.abs(v));
        }
        System.out.printf("%s: %.1f 秒 山 %.3f%n", f.getName(), x.length / (double) SAMPLE_RATE, peak);
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            int bytes = x.length * 2;
            o.writeBytes("RIFF");
            o.writeInt(Integer.reverseBytes(36 + bytes));
            o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            o.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2));
            o.writeShort(Short.reverseBytes((short) 2));
            o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data");
            o.writeInt(Integer.reverseBytes(bytes));
            for (float v : x) {
                o.writeShort(Short.reverseBytes((short) Math.round(Math.max(-1.0F, Math.min(1.0F, v)) * 32767.0F)));
            }
        }
    }
}
