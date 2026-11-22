package com.yayuy.overgrownvegetation.client.atmosphere.sound;

/**
 * <b>突風で巨大樹の幹が軋む音</b> 1 回(v2、2026-11-07。現実の録音に寄せた作り直し。値はすべて仮決め)。
 *
 * <p>参考: Fable が測った録音 3 本(ユーザーが「1・2・3 あたりが良い」。測りは Vault 構想と進捗「巨大樹の目印と生き物」の末尾。
 * 録音は MOD に入れない)。v1(切れ目のない 50〜500 Hz の帯)は「こもったうなり」で、録音の軋みは<b>短い音の粒の列・中高域</b>だった。
 * <ul>
 *     <li><b>軋みの音符の列</b>(録音 1 の芯): 突風 1 回 1.5〜4 秒の間に、毎秒 2〜6 個の短い音符(1 音 0.08〜0.2 秒)。音符は揺れの拍子
 *     (1〜3 秒)の山に寄る。1 音 = 出だしの柔らかいカチッ(帯域 2〜5 kHz の 4 ms、立ち上がりは 1 ms の余弦)+ 倍音の列(基音 250〜500 Hz、
 *     4 kHz まで。1.5〜3 kHz を強め低い倍音は弱く = 録音の重心 1.7〜3.6 kHz に寄せる)。音符ごとに高さを 1〜2 割ずらす。<b>1 音の中では音程を滑らせない</b>
 *     (10-31 の軋み 44 が「動物の鳴き声」になった失敗。出だしの下がりは 6% を約 25 ms だけ、そのあとは 0.3% のゆらぎだけ)。</li>
 *     <li><b>擦れのざらつき</b>(録音 3): 1〜4 kHz の雑音の塊 0.3〜1 秒を、揺れの山で時々(突風が強いほど多く)控えめに重ねる。</li>
 *     <li><b>カチッの列</b>(CC0 の録音。強い突風の変種): 押しが 0.6 を超えると、半分の回で毎秒 10 前後のカチッの列 0.2〜1.4 秒。</li>
 *     <li><b>胴のうなり</b>: 60・85 Hz の帯の雑音を、音符の密度に合わせてごく薄く(録音では風に埋もれて測れない。巨大樹らしさのためだけ)。</li>
 *     <li>遠い外の林: 6 kHz の低域通過と短い薄い響き(0.6 秒)。立ち上がり・終わりは余弦(とがった縁を作らない。memory synth-sound-no-crackle)。</li>
 * </ul>
 * 大きさ 1 で山が 0.5。モノラル。<b>Minecraft の型を使わない</b>({@link #main} で見本の WAV を書き出して聞ける)。
 */
public final class TrunkCreakSynth extends InsectSynth.Prerendered {

    private static final int SAMPLE_RATE = CaveWaterSynth.SAMPLE_RATE;
    private static final double TWO_PI = Math.PI * 2.0;

    private final int seed;
    /** 突風の強さ 0〜1(強いほど長く・音符が多く・少し低く・擦れとカチッの列が出やすい)。 */
    private final double push;

    public TrunkCreakSynth(int seed, double push) {
        this.seed = seed;
        this.push = Math.max(0.0, Math.min(1.0, push));
    }

    @Override
    float[] build() {
        InsectSynth.Rng r = new InsectSynth.Rng(this.seed);
        double dur = 1.5 + 1.5 * this.push + 1.0 * r.next01();
        double sway = 1.0 + 2.0 * r.next01();
        double swayPhase = r.next01() * TWO_PI;
        int n = (int) (dur * SAMPLE_RATE);
        int total = n + (int) (1.2 * SAMPLE_RATE);
        double[] x = new double[total];
        double[] density = new double[total];
        // ---- 軋みの音符の列
        double center = 420.0 - 110.0 * this.push + 60.0 * r.nextSigned();
        double rate = 2.0 + 4.0 * (0.3 + 0.7 * this.push) * (0.7 + 0.3 * r.next01());
        double t = 0.05 + 0.15 * r.next01();
        while (t < dur - 0.1) {
            double swing = 0.5 + 0.5 * Math.sin(TWO_PI * t / sway + swayPhase);
            // 揺れの山に寄せて間引く(谷では 3 割ほどしか鳴らない)。
            if (r.next01() < 0.3 + 0.7 * swing) {
                double edge = InsectSynth.envAD(t / dur, 0.15, 0.2);
                double amp = edge * (0.55 + 0.45 * swing) * (0.75 + 0.25 * r.next01());
                double f0 = center * (1.0 + (0.1 + 0.1 * r.next01()) * (r.next01() < 0.5 ? -1.0 : 1.0));
                double len = 0.08 + 0.12 * r.next01();
                note(x, density, (int) (t * SAMPLE_RATE), len, f0, amp, r);
            }
            t += (1.0 / rate) * (0.55 + 0.9 * r.next01());
        }
        // ---- 擦れのざらつき(揺れの山で時々)
        InsectSynth.Band scrapeLo = new InsectSynth.Band(1600.0, 1.2);
        InsectSynth.Band scrapeHi = new InsectSynth.Band(3000.0, 1.5);
        for (double peak = (Math.PI / 2.0 - swayPhase) / TWO_PI * sway; peak < dur; peak += sway) {
            if (peak < 0.1 || r.next01() > 0.35 + 0.4 * this.push) {
                continue;
            }
            double len = 0.3 + 0.7 * r.next01();
            int s0 = (int) (Math.max(0.0, peak - len * 0.5) * SAMPLE_RATE);
            int sn = (int) (len * SAMPLE_RATE);
            double amp = 0.22 * (0.7 + 0.3 * r.next01());
            for (int i = 0; i < sn && s0 + i < total; i++) {
                double u = i / (double) sn;
                double e = InsectSynth.envAD(u, 0.3, 0.4) * (0.7 + 0.3 * Math.sin(TWO_PI * 7.0 * u * len));
                double w = r.gauss() * 0.4;
                x[s0 + i] += amp * e * (scrapeLo.band(w) + 0.7 * scrapeHi.band(w));
                density[s0 + i] += 0.3 * e;
            }
        }
        // ---- カチッの列(強い突風の変種)
        if (this.push > 0.6 && r.next01() < 0.5) {
            double at = 0.3 + Math.max(0.0, dur - 1.0) * r.next01();
            double len = 0.2 + 1.2 * r.next01();
            double ct = at;
            while (ct < Math.min(dur, at + len)) {
                click(x, (int) (ct * SAMPLE_RATE), 0.45 * InsectSynth.envAD((ct - at) / len, 0.2, 0.3), r);
                ct += 0.1 * (0.6 + 0.8 * r.next01());
            }
        }
        // ---- 胴のうなり(ごく薄く)
        InsectSynth.Band g1 = new InsectSynth.Band(60.0, 2.0);
        InsectSynth.Band g2 = new InsectSynth.Band(85.0, 2.5);
        double dens = 0.0;
        double k = 1.0 - Math.exp(-1.0 / (0.25 * SAMPLE_RATE));
        for (int i = 0; i < total; i++) {
            dens += (Math.min(1.0, density[i] * 4.0) - dens) * k;
            double w = r.gauss() * 0.3;
            x[i] += 0.12 * dens * (g1.band(w) + 0.7 * g2.band(w));
        }
        // ---- 遠い外の林
        x = CaveWaterSynth.lowpass(CaveWaterSynth.lowpass(x, 6000.0), 6000.0);
        double[] send = new double[total];
        for (int i = 0; i < total; i++) {
            send[i] = x[i] * 0.12;
        }
        double[] wet = CaveWaterSynth.lowpass(CaveWaterSynth.CaveReverb.render(send, 0.6, 0.8, 0.5, 0.012), 3000.0);
        double[] out = new double[total];
        for (int i = 0; i < total; i++) {
            out[i] = x[i] + wet[i];
        }
        return CaveWaterSynth.normalize(CaveWaterSynth.fadeOut(out, 0.3), 0.5);
    }

    /** 1 つの音符: 出だしのカチッ + 倍音の列(出だしだけ 6% 上から約 25 ms で下りる。そのあとは 0.3% のゆらぎだけ)。 */
    private static void note(double[] x, double[] density, int start, double len, double f0, double amp, InsectSynth.Rng r) {
        click(x, start, 0.35 * amp, r);
        int n = (int) (len * SAMPLE_RATE);
        int partials = Math.max(1, (int) (4000.0 / f0));
        double[] w = new double[partials];
        double[] ph = new double[partials];
        double sum = 0.0;
        for (int k = 0; k < partials; k++) {
            double f = f0 * (k + 1);
            double formant = Math.exp(-Math.pow(Math.log(f / 2200.0), 2.0) / 0.35);
            w[k] = Math.pow(k + 1, -0.5) * (0.12 + 1.3 * formant) * (0.8 + 0.4 * r.next01());
            ph[k] = r.next01() * TWO_PI;
            sum += w[k];
        }
        double drift = 0.0;
        for (int i = 0; i < n && start + i < x.length; i++) {
            double ts = i / (double) SAMPLE_RATE;
            // 大きさ: 3 ms の余弦で立ち上がり、20 ms で 0.65 へ落ち着き、終わり 25 ms を余弦で。
            double env = ts < 0.003 ? InsectSynth.smooth(ts / 0.003) : (0.65 + 0.35 * Math.exp(-(ts - 0.003) / 0.02)) * (1.0 - 0.4 * ts / len);
            double tail = (len - ts) < 0.025 ? InsectSynth.smooth((len - ts) / 0.025) : 1.0;
            drift += (r.nextSigned() * 0.003 - drift) * 0.002;
            double f = f0 * (1.0 + 0.06 * Math.exp(-ts / 0.008) + drift);
            double s = 0.0;
            for (int k = 0; k < partials; k++) {
                ph[k] += TWO_PI * f * (k + 1) / SAMPLE_RATE;
                s += w[k] * Math.sin(ph[k]);
            }
            double e = env * tail * amp;
            x[start + i] += e * s / sum * 0.9;
            density[start + i] += e;
        }
    }

    /** 柔らかいカチッ: 2〜4 kHz の帯の雑音 4 ms(1 ms の余弦で立ち上がり、3 ms で消える)。 */
    private static void click(double[] x, int start, double amp, InsectSynth.Rng r) {
        InsectSynth.Band b = new InsectSynth.Band(2400.0 + 1600.0 * r.next01(), 1.4);
        int n = (int) (0.004 * SAMPLE_RATE);
        int rise = (int) (0.001 * SAMPLE_RATE);
        for (int i = 0; i < n && start + i < x.length; i++) {
            double e = i < rise ? InsectSynth.smooth(i / (double) rise) : 1.0 - InsectSynth.smooth((i - rise) / (double) (n - rise));
            x[start + i] += amp * e * b.band(r.gauss()) * 1.6;
        }
    }

    /**
     * 見本の WAV を書く。{@code javac -d <tmp> TrunkCreakSynth.java CaveWaterSynth.java InsectSynth.java WindSynth.java BubbleWaterSynth.java} のあと
     * {@code java -cp <tmp> com.yayuy.overgrownvegetation.client.atmosphere.sound.TrunkCreakSynth <出力フォルダ>}。
     * v2_weak_1・2 = 鳴り出しの強さの突風、v2_strong_1・2 = 強い突風(カチッの列の変種が出る種を含む)。どれも大きさ 1(ゲームでは距離で小さくなる)。
     */
    public static void main(String[] args) throws java.io.IOException {
        java.io.File dir = new java.io.File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();
        write(new java.io.File(dir, "trunk_creak_v2_weak_1.wav"), all(new TrunkCreakSynth(101, 0.15)));
        write(new java.io.File(dir, "trunk_creak_v2_weak_2.wav"), all(new TrunkCreakSynth(202, 0.3)));
        write(new java.io.File(dir, "trunk_creak_v2_strong_1.wav"), all(new TrunkCreakSynth(303, 0.8)));
        write(new java.io.File(dir, "trunk_creak_v2_strong_2.wav"), all(new TrunkCreakSynth(404, 1.0)));
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
        double sum = 0.0;
        for (float v : x) {
            peak = Math.max(peak, Math.abs(v));
            sum += v * (double) v;
        }
        double rms = Math.sqrt(sum / Math.max(1, x.length));
        System.out.printf("%s: %.1f 秒 山 %.3f 山と平均の差 %.1f dB%n", f.getName(), x.length / (double) SAMPLE_RATE, peak,
                20.0 * Math.log10(peak / Math.max(1e-9, rms)));
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
