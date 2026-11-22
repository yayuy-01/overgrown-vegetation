package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import net.minecraft.client.sounds.AudioStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/**
 * 風の音を<b>その場で作って流す</b>音の流れ(ファイルを読まない)。バニラの流す音(音楽と同じ道)が 1 つ鳴り終わるたびに次を読みに来るので、
 * そのつど {@link WindSynth} で {@value #BUFFER_MS} ミリ秒ぶん作って渡す。
 *
 * <h2>見えている風と音を揃える</h2>
 * 読みに来た分がすぐ鳴るわけではない: 流す側は 4 つ先まで積んでおくので、今作った音が鳴るのは前に積んだ 3 つ(約 0.45 秒)の後。
 * そこで、その鳴る時刻の風を使う —— 突風は決まった速さで進むので、少し先の風は今わかる。
 * 先の風は描画のスレッドが毎 tick {@link ForestWindSound#window()} に 1.5 秒先まで並べて置き、ここ(音のスレッド)はそれを読むだけ
 * (風そのものには触らない。スレッドをまたいで同じ物を書き換えない)。
 */
@OnlyIn(Dist.CLIENT)
final class WindAudioStream implements AudioStream {

    static final int BUFFER_MS = 150;
    private static final int BUFFER_FRAMES = WindSynth.SAMPLE_RATE * BUFFER_MS / 1000;
    private static final double BUFFER_SECONDS = BUFFER_FRAMES / (double) WindSynth.SAMPLE_RATE;

    private final boolean far;
    private final WindSynth synth;
    private final AudioFormat format;
    private final float[] scratch;
    private int reads;
    private long lastLog;
    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、流れの読まれ方をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    WindAudioStream(boolean far, int seed) {
        this.far = far;
        // まわりの音はステレオ(左右に広がって包む)。遠くの音は向きを付けるのでモノラル(ステレオの音には向きが付かない)。
        this.synth = new WindSynth(!far, far, seed);
        this.format = new AudioFormat(WindSynth.SAMPLE_RATE, 16, this.synth.channels(), true, false);
        this.scratch = new float[BUFFER_FRAMES * this.synth.channels()];
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    @Override
    public ByteBuffer read(int size) {
        // この分が鳴り始めるまでの遅れ: 最初に積む 4 つは 0・1・2・3 個ぶん、そのあとは 3 個ぶん。
        double delay = Math.min(this.reads, 3) * BUFFER_SECONDS;
        this.reads++;
        ForestWindSound.Window window = ForestWindSound.window();
        int channels = this.synth.channels();
        for (int done = 0; done < BUFFER_FRAMES; done += WindSynth.BLOCK) {
            int n = Math.min(WindSynth.BLOCK, BUFFER_FRAMES - done);
            double ahead = delay + (done + n * 0.5) / WindSynth.SAMPLE_RATE;
            this.synth.render(this.scratch, done * channels, n, window.w(this.far, ahead), window.level(this.far, ahead));
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(this.scratch.length * 2);
        for (float v : this.scratch) {
            buffer.putShort((short) Math.round(Math.max(-1.0F, Math.min(1.0F, v)) * 32767.0F));
        }
        buffer.flip();
        if (DEV_LOG && this.reads % 200 == 0) {
            long now = System.nanoTime();
            // 200 回読むのにかかった時間が 200 × 0.15 秒 = 30 秒に近ければ、途切れずに流れている。
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("WindAudioStream {} 読んだ回数 {}・直近 200 回 {} 秒",
                    this.far ? "遠く" : "まわり", this.reads, this.lastLog == 0L ? "-" : String.format("%.1f", (now - this.lastLog) / 1.0E9));
            this.lastLog = now;
        }
        return buffer;
    }

    @Override
    public void close() {
    }
}
