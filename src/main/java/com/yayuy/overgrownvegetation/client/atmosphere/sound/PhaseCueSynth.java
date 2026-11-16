package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import javax.sound.sampled.AudioFormat;

/**
 * フェーズが上がったときの合図の音の<b>中身</b>(2026-11-16。流し方は {@link PhaseCueSound})。Minecraft の型を使わない
 * (素の java で WAV に書き出して聞ける: {@code java -cp build/classes/java/main ...PhaseCueSynth <出力フォルダ>})。
 * 地鳴りはなめらかな低い雑音だけ(粒・クリックなし)。ざわめきは了承済みの風の合成 {@link WindSynth} を 1 回のうねりにしたもの。数は仮決め。
 */
public final class PhaseCueSynth {

    static final int RATE = WindSynth.SAMPLE_RATE;

    private PhaseCueSynth() {
    }

    /** 地鳴りの長さ(秒)と、時刻 t(秒)の大きさ(0〜1)。 */
    static final double RUMBLE_SECONDS = 6.0;

    static double rumbleEnvelope(double t) {
        if (t <= 0.0 || t >= RUMBLE_SECONDS) {
            return 0.0;
        }
        double up = Math.min(1.0, t / 1.4);
        double down = Math.min(1.0, (RUMBLE_SECONDS - t) / 3.2);
        return smooth(up) * smooth(down);
    }

    /** ざわめきの長さ(秒)と、時刻 t(秒)の大きさ(0〜1)。 */
    static final double RUSTLE_SECONDS = 6.5;

    static double rustleEnvelope(double t) {
        if (t <= 0.0 || t >= RUSTLE_SECONDS) {
            return 0.0;
        }
        double up = Math.min(1.0, t / 1.8);
        double down = Math.min(1.0, (RUSTLE_SECONDS - t) / 3.0);
        return smooth(up) * smooth(down);
    }

    private static double smooth(double x) {
        return x * x * (3.0 - 2.0 * x);
    }

    /** 低い雑音(地鳴り)。白い雑音を 2 段の低域通過(約 95 Hz)と 1 段の高域通過(約 28 Hz)に通し、ゆっくり息をさせる。 */
    static final class Rumble {
        private int rng;
        private double lp1;
        private double lp2;
        private double hp;
        private double breath;
        private double breathTarget;
        private int breathLeft;
        private long frame;

        Rumble(int seed) {
            this.rng = seed == 0 ? 0x2545F491 : seed;
        }

        private double next() {
            this.rng ^= this.rng << 13;
            this.rng ^= this.rng >>> 17;
            this.rng ^= this.rng << 5;
            return this.rng / 2147483648.0;
        }

        void render(float[] out, int offset, int frames) {
            double a = 1.0 - Math.exp(-2.0 * Math.PI * 95.0 / RATE);
            double h = 1.0 - Math.exp(-2.0 * Math.PI * 28.0 / RATE);
            for (int i = 0; i < frames; i++) {
                if (this.breathLeft-- <= 0) {
                    this.breathTarget = 0.75 + 0.25 * next();
                    this.breathLeft = RATE / 3;
                }
                this.breath += (this.breathTarget - this.breath) * 0.00012;
                double white = next();
                this.lp1 += (white - this.lp1) * a;
                this.lp2 += (this.lp1 - this.lp2) * a;
                this.hp += (this.lp2 - this.hp) * h;
                double v = (this.lp2 - this.hp) * 9.0 * this.breath * rumbleEnvelope(this.frame / (double) RATE);
                out[offset + i] = (float) (v / (1.0 + Math.abs(v) * 0.6));
                this.frame++;
            }
        }
    }

    /** 見本を WAV に書き出す(Minecraft 無しで動く部分だけ): {@code java PhaseCueSound.java <出力フォルダ>}。 */
    public static void main(String[] args) throws java.io.IOException {
        java.io.File dir = new java.io.File(args.length > 0 ? args[0] : ".");
        float[] rumble = new float[(int) (RUMBLE_SECONDS * RATE)];
        new Rumble(12345).render(rumble, 0, rumble.length);
        writeWav(new java.io.File(dir, "phase_cue_rumble.wav"), rumble);
        float[] rustle = new float[(int) (RUSTLE_SECONDS * RATE)];
        WindSynth wind = new WindSynth(false, true, 54321);
        for (int done = 0; done < rustle.length; done += WindSynth.BLOCK) {
            int n = Math.min(WindSynth.BLOCK, rustle.length - done);
            double e = rustleEnvelope((done + n * 0.5) / RATE);
            wind.render(rustle, done, n, 0.55 + 0.45 * e, e);
        }
        writeWav(new java.io.File(dir, "phase_cue_rustle.wav"), rustle);
        for (float[] x : new float[][]{rumble, rustle}) {
            double sum = 0.0;
            double peak = 0.0;
            for (float v : x) {
                sum += v * v;
                peak = Math.max(peak, Math.abs(v));
            }
            System.out.printf("rms %.1f dBFS, peak %.1f dBFS%n", 10.0 * Math.log10(sum / x.length), 20.0 * Math.log10(peak));
        }
    }

    private static void writeWav(java.io.File file, float[] x) throws java.io.IOException {
        byte[] data = new byte[x.length * 2];
        for (int i = 0; i < x.length; i++) {
            int s = Math.round(Math.max(-1.0F, Math.min(1.0F, x[i])) * 32767.0F);
            data[i * 2] = (byte) s;
            data[i * 2 + 1] = (byte) (s >> 8);
        }
        javax.sound.sampled.AudioInputStream in = new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(data), new AudioFormat(RATE, 16, 1, true, false), x.length);
        javax.sound.sampled.AudioSystem.write(in, javax.sound.sampled.AudioFileFormat.Type.WAVE, file);
    }
}
