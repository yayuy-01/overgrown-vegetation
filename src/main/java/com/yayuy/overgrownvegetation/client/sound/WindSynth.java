package com.yayuy.overgrownvegetation.client.sound;

/**
 * 林を渡る風の音の<b>合成器</b>(2026-10-15 夜。ユーザー「突風が吹いた際に風の音が鳴るように。現実の音を参考に、しっかりリアリティのある環境音に」)。
 * 録音を流すのではなく、その場の風の強さから 1 サンプルずつ作る —— 見えている突風({@link com.yayuy.overgrownvegetation.client.CanopyWind})と
 * 音がずれず、同じ音の繰り返しにも聞こえない。
 *
 * <h2>風の音だけ(粒のある音は入れない)</h2>
 * 最初の版は、葉と葉が当たる「ぱちっ」(短い山の粒)を重ねて葉擦れを作っていたが、ユーザー「バチバチ鳴っている音は葉がぶつかる音に聞こえず、
 * 違和感がある。風の音だけに」(2026-10-15 夜)で外した。今はどの層もなめらかな雑音で、とがった音は無い:
 * <ul>
 *     <li><b>「ざーっ」</b>: 白い雑音の高い帯。風が強いほど帯が上がって明るくなる(1.4 → 3 kHz)。強い風ほど割合も増える。
 *     わずかに(±2 割)ゆらぐ —— 一定の雑音だと機械の音に聞こえる。</li>
 *     <li><b>「ふぉー」</b>: 少し色のついた雑音の中ほどの帯(320 → 1000 Hz)。風の胴。帯の高さはゆっくりさまよう。</li>
 *     <li><b>「ごぉー」</b>: 強い突風だけの低い帯(150 → 450 Hz)。ゆっくり息をするように揺れる。もっと強いと、さらに低いとどろき(90 Hz 以下)が少し。</li>
 *     <li><b>突風の中の息づき</b>: 突風の盛りでも風は一定でなく、毎秒 1〜2 回ほど強弱がある。</li>
 *     <li><b>大きさ</b>: 林の風の音は、風が強いほど大きい(凪とつよい風で 20 dB ほど違う)。大きさは呼び手が {@code level} で渡し、
 *     ここで作る音そのものは風の強さによらず同じくらいの大きさ(層ごとの大きさを計算でそろえ、混ぜ方の割合だけを変える)。</li>
 *     <li><b>遠くの林</b>({@code distant}): 高い音が空気と葉に吸われて減る(「ざーっ」を低めに・少なく、全体に高い音を削る)。</li>
 * </ul>
 * 現実の音の調べものは Vault の 光と風の演出/林の風の音(出典もそこ)。
 *
 * <p><b>Minecraft の型を使わない</b>(素の java で WAV に書き出して聞ける。tools/wind-sound)。音の流し方は {@link WindAudioStream}。
 * 数値はすべて仮決め。
 */
public final class WindSynth {

    public static final int SAMPLE_RATE = 44100;
    /** 操作の値(強さ・大きさ)を寄せ直す単位(サンプル)。 */
    public static final int BLOCK = 64;
    /** 大きさ 1 のときの出力の大きさ(2 乗平均の平方根)。突風の盛り(大きさ 0.9 ほど)で約 −19 dBFS、凪で約 −40 dBFS。 */
    private static final double OUTPUT_RMS = 0.12;

    private static final double TWO_PI = Math.PI * 2.0;
    /** 一様乱数 −1〜1 の標準偏差。 */
    private static final double NOISE_STD = Math.sqrt(1.0 / 3.0);
    /** 色のついた雑音(「ふぉー」「ごぉー」の元)を作る 1 次の低域通過の遮断(Hz)。 */
    private static final double PINK_HZ = 1500.0;

    /**
     * 層の混ぜ方(出来上がりの大きさの割合)。風の強さ 0.1・0.3・0.6・1.0 のときの値で、その間はなめらかにつなぐ。
     * 行は ざーっ・ふぉー・ごぉー・とどろき。
     */
    private static final double[] MIX_W = {0.1, 0.3, 0.6, 1.0};
    private static final double[][] MIX_NEAR = {
            {0.45, 0.55, 0.62, 0.65},
            {0.75, 0.70, 0.62, 0.55},
            {0.10, 0.20, 0.40, 0.55},
            {0.00, 0.02, 0.10, 0.22}};
    private static final double[][] MIX_DISTANT = {
            {0.30, 0.35, 0.40, 0.42},
            {0.75, 0.72, 0.66, 0.60},
            {0.35, 0.45, 0.55, 0.60},
            {0.00, 0.02, 0.08, 0.12}};

    private final boolean stereo;
    private final boolean distant;
    private final int channels;

    private int rng;

    // 操作の値(なめらかに寄せた今の値)。
    private double w;
    private double level;
    private boolean primed;

    // 耳ごとの状態。
    private final Svf[] hiss;
    private final Svf[] body;
    private final Svf[] roar;
    private final double[] pink;
    private final double[] rumbleBrown;
    private final double[] rumbleLow;
    /** 遠くの林の高い音を削る 1 次の低域通過。 */
    private final double[] distantLow;

    /** 「ざーっ」のゆらぎ・「ごぉー」の息・「ふぉー」の高さのさまよい・突風の中の息づき(ゆっくりした雑音)。 */
    private final SlowNoise shimmer;
    private final SlowNoise breathe;
    private final SlowNoise wander;
    private final SlowNoise gusty;

    public WindSynth(boolean stereo, boolean distant, int seed) {
        this.stereo = stereo;
        this.distant = distant;
        this.channels = stereo ? 2 : 1;
        this.rng = seed == 0 ? 0x9E3779B9 : seed;
        this.hiss = new Svf[this.channels];
        this.body = new Svf[this.channels];
        this.roar = new Svf[this.channels];
        this.pink = new double[this.channels];
        this.rumbleBrown = new double[this.channels];
        this.rumbleLow = new double[this.channels];
        this.distantLow = new double[this.channels];
        for (int c = 0; c < this.channels; c++) {
            this.hiss[c] = new Svf();
            this.body[c] = new Svf();
            this.roar[c] = new Svf();
        }
        this.shimmer = new SlowNoise(4.0);
        this.breathe = new SlowNoise(0.7);
        this.wander = new SlowNoise(0.3);
        this.gusty = new SlowNoise(1.5);
    }

    /** 一様乱数の雑音を、中心 {@code hz}・{@code q} の帯域通過(山の高さ Q)に通したときの大きさ(狭い帯の近似)。 */
    private static double bandRms(double hz, double q) {
        return NOISE_STD * Math.sqrt(q * Math.PI * hz / SAMPLE_RATE);
    }

    /** 色のついた雑音({@link #PINK_HZ} の 1 次の低域通過を通し、2 倍したもの)を帯域通過に通したときの大きさ。 */
    private static double pinkBandRms(double hz, double q) {
        return 2.0 * bandRms(hz, q) / Math.sqrt(1.0 + (hz / PINK_HZ) * (hz / PINK_HZ));
    }

    public int channels() {
        return this.channels;
    }

    /**
     * {@code frames} サンプル作って {@code out} へ足さずに書く(ステレオは L R L R …)。{@code targetW} は音の具合を決める風の強さ
     * ({@link com.yayuy.overgrownvegetation.client.CanopyWind#strength} の尺度、0〜1.2)、{@code targetLevel} は大きさ(0〜1 の倍率)。
     * 値は 64 サンプルごとに、今の値から約 60 ミリ秒かけて寄せる(段がつかない)。{@code frames} は {@link #BLOCK} の倍数でなくてよい。
     */
    public void render(float[] out, int offset, int frames, double targetW, double targetLevel) {
        if (!this.primed) {
            this.w = targetW;
            this.level = targetLevel;
            this.primed = true;
        }
        double follow = 1.0 - Math.exp(-BLOCK / (0.06 * SAMPLE_RATE));
        int done = 0;
        while (done < frames) {
            int n = Math.min(BLOCK, frames - done);
            double w0 = this.w;
            double l0 = this.level;
            this.w += (targetW - this.w) * follow;
            this.level += (targetLevel - this.level) * follow;
            block(out, offset + done * this.channels, n, w0, this.w, l0, this.level);
            done += n;
        }
    }

    private void block(float[] out, int offset, int n, double w0, double w1, double l0, double l1) {
        double wm = Math.max(0.0, Math.min(1.2, 0.5 * (w0 + w1)));
        // 帯の高さ。風が強いほど上がる(風の音が明るく・高くなる)。遠くの林は低め。
        double hissHz = (1400.0 + 1600.0 * Math.min(wm, 1.0)) * (this.distant ? 0.65 : 1.0);
        double bodyHz = (320.0 + 700.0 * Math.min(wm, 1.0)) * (1.0 + 0.15 * this.wander.next(n));
        double roarHz = 150.0 + 300.0 * Math.min(wm, 1.0);
        for (int c = 0; c < this.channels; c++) {
            this.hiss[c].set(hissHz, 0.55);
            this.body[c].set(bodyHz, 0.8);
            this.roar[c].set(roarHz, 0.9);
        }
        // 層の混ぜ方。各層を大きさ 1 にそろえたうえで割合を掛け、全体も大きさ 1 に戻す。
        double[][] table = this.distant ? MIX_DISTANT : MIX_NEAR;
        double mixHiss = mix(table[0], wm);
        double mixBody = mix(table[1], wm);
        double mixRoar = mix(table[2], wm);
        double mixRumble = mix(table[3], wm);
        double total = Math.sqrt(mixHiss * mixHiss + mixBody * mixBody + mixRoar * mixRoar + mixRumble * mixRumble);
        double gHiss = mixHiss / bandRms(hissHz, 0.55) / total;
        double gBody = mixBody / pinkBandRms(bodyHz, 0.8) / total;
        // 「ごぉー」は息の揺れ(平均 0.75、ばらつき 0.11)のぶんも戻す。
        double gRoar = mixRoar / (pinkBandRms(roarHz, 0.9) * 0.76) / total;
        // とどろき(茶色の雑音を 90 Hz の低域通過に通したもの)の大きさは約 0.245(係数から計算)。
        double gRumble = mixRumble * 4.08 / total;

        double shimmerGain = 1.0 + 0.2 * this.shimmer.next(n);
        double breath = 0.75 + 0.25 * this.breathe.next(n);
        double gust = 1.0 + 0.18 * Math.min(wm, 1.0) * this.gusty.next(n);
        double distantCut = this.distant ? onePoleCoef(3200.0) : 0.0;
        double rumbleCut = onePoleCoef(90.0);
        double pinkCut = onePoleCoef(PINK_HZ);

        for (int i = 0; i < n; i++) {
            double t = (i + 0.5) / n;
            double amp = (l0 + (l1 - l0) * t) * OUTPUT_RMS * gust;
            // 「ふぉー」「ごぉー」「とどろき」の元の雑音: 左右で共通の分(真ん中に広がる)と、耳ごとの分。
            double common = nextSigned();
            for (int c = 0; c < this.channels; c++) {
                // 「ざーっ」は耳ごとに別の雑音(左右に広がって包む)。
                double white = nextSigned();
                double own = this.stereo ? nextSigned() : common;
                double hissOut = this.hiss[c].band(white) * shimmerGain;
                double colored = this.stereo ? 0.75 * common + 0.66 * own : common;
                this.pink[c] += (colored - this.pink[c]) * pinkCut;
                double bodyOut = this.body[c].band(this.pink[c] * 2.0);
                double roarOut = this.roar[c].band(this.pink[c] * 2.0) * breath;
                // とどろき: 茶色の雑音(積み上げ)をさらに低く。低い音は向きが分からないので左右で同じ雑音。
                this.rumbleBrown[c] = this.rumbleBrown[c] * 0.995 + common * 0.05;
                this.rumbleLow[c] += (this.rumbleBrown[c] - this.rumbleLow[c]) * rumbleCut;
                double s = gHiss * hissOut + gBody * bodyOut + gRoar * roarOut + gRumble * this.rumbleLow[c];
                if (this.distant) {
                    this.distantLow[c] += (s - this.distantLow[c]) * distantCut;
                    s = this.distantLow[c] * 1.15;
                }
                out[offset + i * this.channels + c] = (float) softClip(s * amp);
            }
        }
    }

    /** 混ぜ方の表を、風の強さ {@code w} でなめらかに引く。 */
    private static double mix(double[] row, double w) {
        if (w <= MIX_W[0]) {
            return row[0];
        }
        for (int i = 1; i < MIX_W.length; i++) {
            if (w <= MIX_W[i]) {
                double t = (w - MIX_W[i - 1]) / (MIX_W[i] - MIX_W[i - 1]);
                t = t * t * (3.0 - 2.0 * t);
                return row[i - 1] + (row[i] - row[i - 1]) * t;
            }
        }
        return row[row.length - 1];
    }

    /** 1 次の低域通過の係数(遮断 {@code hz})。 */
    private static double onePoleCoef(double hz) {
        return 1.0 - Math.exp(-TWO_PI * hz / SAMPLE_RATE);
    }

    /** 大きすぎる山だけをなめらかに丸める(ふつうの大きさでは素通し)。 */
    private static double softClip(double x) {
        double a = Math.abs(x);
        if (a < 0.7) {
            return x;
        }
        double over = (a - 0.7) / 0.3;
        double y = 0.7 + 0.3 * (over / (1.0 + over));
        return Math.copySign(Math.min(y, 0.999), x);
    }

    private double nextUnit() {
        int x = this.rng;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        this.rng = x;
        return (x >>> 8) / (double) (1 << 24);
    }

    private double nextSigned() {
        return nextUnit() * 2.0 - 1.0;
    }

    /** 状態変数フィルタ(TPT 型。高い周波数でも安定)。帯域通過(山の高さ Q)を使う。 */
    private static final class Svf {
        private double a1;
        private double a2;
        private double a3;
        private double ic1;
        private double ic2;
        private double lastHz = -1.0;
        private double lastQ = -1.0;

        void set(double hz, double q) {
            if (hz == this.lastHz && q == this.lastQ) {
                return;
            }
            this.lastHz = hz;
            this.lastQ = q;
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

    /**
     * ゆっくり揺れる雑音(だいたい −1〜1)。ブロックごとに 1 つ値を出す。中身は乱数を 2 段の低域通過に通して、ばらつきをそろえたもの。
     */
    private final class SlowNoise {
        private final double rate;
        private double s1;
        private double s2;

        SlowNoise(double hz) {
            this.rate = hz;
        }

        double next(int samples) {
            double coef = 1.0 - Math.exp(-TWO_PI * this.rate * samples / SAMPLE_RATE);
            double x = nextSigned();
            this.s1 += (x - this.s1) * coef;
            this.s2 += (this.s1 - this.s2) * coef;
            // 2 段の低域通過で小さくなったぶんを戻して、ばらつき(標準偏差)を 0.45 にそろえる。
            // 同じ 1 次の低域通過 2 段の分散の倍率は c(1 + a²)/(2 − c)³(a = 1 − c)、一様乱数 −1〜1 の分散は 1/3。
            double a = 1.0 - coef;
            double std = Math.sqrt(coef * (1.0 + a * a) / Math.pow(2.0 - coef, 3.0) / 3.0);
            return Math.max(-1.0, Math.min(1.0, this.s2 * 0.45 / Math.max(std, 1.0E-9)));
        }
    }
}
