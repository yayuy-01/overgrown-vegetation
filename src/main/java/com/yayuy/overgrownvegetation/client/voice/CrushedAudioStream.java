package com.yayuy.overgrownvegetation.client.voice;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.sounds.AudioStream;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 元の音の流れ(任意の SoundEvent を開いた ogg の流れ)を包み、読み出した 16 ビット PCM に {@link VoiceCrusher} を掛けて返す(2026-11-14)。
 *
 * <ul>
 *     <li>ステレオの元音はモノラルにまとめる(位置つきで鳴らすため。ステレオの音には向きが付かない)。サンプリング周波数は元のまま。</li>
 *     <li><b>先読み</b>: 作るとき(音のスレッドの外、元の流れが開いた IO のスレッド)に最大 {@value #LOOKAHEAD_SECONDS} 秒【仮】を読む。
 *         Mob の声(たいてい 1〜2 秒)はここで末尾まで届くので、試聴用の {@code HoarseVoice} と同じく音全体を見て
 *         つぶれの前の山・雑音の揃え・途切れの位置・最後の大きさを決められる。</li>
 *     <li>先読みで届かない長い音(ループ・音楽など)は、先読みで決めた値のまま続きを流して処理する(状態は持ち越す。大きさは切り詰める)。</li>
 *     <li>最後の大きさ: 加工した音の山を元の音の山に揃える(バニラの Mob どうしの大きさの釣り合いを崩さない。試聴用は山を 0.89 に揃えていた)。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class CrushedAudioStream implements AudioStream {

    /** 先読みの上限(秒)【仮】。 */
    static final double LOOKAHEAD_SECONDS = 4.0;
    /** 加工した音の山の上限(音割れさせない)。 */
    private static final float MAX_PEAK = 0.98F;
    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、開いた音の様子をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private final AudioStream inner;
    private final AudioFormat format;
    private final int innerChannels;
    private final VoiceCrusher crusher;
    private final float gain;
    private ByteBuffer pending;
    private boolean ended;
    private boolean failed;

    private CrushedAudioStream(AudioStream inner, AudioFormat innerFormat, VoiceCrusher crusher, float[] head, int length, boolean ended, String name) {
        this.inner = inner;
        this.innerChannels = innerFormat.getChannels();
        this.format = new AudioFormat(innerFormat.getSampleRate(), 16, 1, true, false);
        this.crusher = crusher;
        this.ended = ended;

        float peakIn = peak(head, length);
        float g = 1.0F;
        try {
            g = shape(head, length, innerFormat.getSampleRate(), crusher, ended);
        } catch (RuntimeException e) {
            // 加工で落ちても音は止めない(pumpBuffers が捕まえるのは IOException だけ)。素の音を流す。
            OvergrownVegetation.LOGGER.warn("声の加工に失敗した({}): {}", name, e.toString());
            this.failed = true;
        }
        this.gain = g;
        this.pending = toPcm(head, length, this.failed ? 1.0F : g);
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CrushedAudioStream {} 版 {}: 元 {} Hz {} ch・先読み {} フレーム({} 秒)・末尾まで{}・山 {} → 倍率 {}",
                    name, crusher.mode(), (int) innerFormat.getSampleRate(), this.innerChannels, length,
                    String.format("%.2f", length / innerFormat.getSampleRate()), ended ? "届いた" : "届かない",
                    String.format("%.3f", peakIn), String.format("%.3f", g));
        }
    }

    /**
     * 元の流れを包む。元が 16 ビットの PCM でなければ包まずにそのまま返す。先読みするので、音のスレッドの外で呼ぶ。
     *
     * @param name ログ用(音の場所)
     */
    public static AudioStream open(AudioStream inner, VoiceCrusher crusher, String name) throws IOException {
        AudioFormat f = inner.getFormat();
        if (f.getSampleSizeInBits() != 16 || f.getChannels() < 1 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            OvergrownVegetation.LOGGER.warn("声の加工: 16 ビット PCM でないので素のまま流す({}: {})", name, f);
            return inner;
        }
        int ch = f.getChannels();
        int maxFrames = (int) (f.getSampleRate() * LOOKAHEAD_SECONDS);
        int chunkBytes = (int) f.getSampleRate() * 2 * ch; // 1 秒ぶんずつ読む(Channel が求めるのと同じ量)
        float[] head = new float[Math.max(1, (int) f.getSampleRate())];
        int length = 0;
        boolean ended = false;
        while (length < maxFrames) {
            ByteBuffer b = inner.read(chunkBytes);
            if (b == null || !b.hasRemaining()) {
                ended = true;
                break;
            }
            int frames = b.remaining() / (2 * ch);
            if (length + frames > head.length) {
                head = java.util.Arrays.copyOf(head, Math.max(head.length * 2, length + frames));
            }
            readMono(b, ch, head, length, frames);
            length += frames;
        }
        return new CrushedAudioStream(inner, f, crusher, head, length, ended, name);
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (this.pending != null) {
            ByteBuffer out = slice(this.pending, Math.max(2, size & ~1));
            if (!this.pending.hasRemaining()) {
                this.pending = null;
            }
            return out;
        }
        if (this.ended) {
            return BufferUtils.createByteBuffer(0);
        }
        // 先読みで届かなかった長い音の続き。
        ByteBuffer b = this.inner.read(Math.max(2, size) * this.innerChannels);
        if (b == null || !b.hasRemaining()) {
            this.ended = true;
            return BufferUtils.createByteBuffer(0);
        }
        int frames = b.remaining() / (2 * this.innerChannels);
        float[] x = new float[frames];
        readMono(b, this.innerChannels, x, 0, frames);
        if (!this.failed) {
            try {
                this.crusher.process(x, 0, frames);
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.warn("声の加工に失敗した(続き): {}", e.toString());
                this.failed = true;
            }
        }
        return toPcm(x, frames, this.failed ? 1.0F : this.gain);
    }

    @Override
    public void close() throws IOException {
        this.inner.close();
    }

    /**
     * 先読みした分(短い声なら全部)を加工し、縁を付けて、最後に掛ける倍率を返す(加工した山を元の山に揃える。上限 {@value #MAX_PEAK})。
     * 流す方式と、1 回だけ加工して持つ方式({@link CrushedVoiceCache})の両方で使う。
     */
    static float shape(float[] head, int length, float sampleRate, VoiceCrusher crusher, boolean ended) {
        float peakIn = peak(head, length);
        crusher.prepare(head, length, ended);
        crusher.process(head, 0, length);
        if (crusher.mode() == VoiceCrusher.MODE_DRY) {
            return 1.0F;
        }
        float g = 1.0F;
        float peakOut = peak(head, length);
        if (peakOut > 1e-6F) {
            g = Math.min(peakIn / peakOut, MAX_PEAK / peakOut);
        }
        fade(head, length, sampleRate, ended);
        return g;
    }

    static void readMono(ByteBuffer b, int ch, float[] out, int at, int frames) {
        for (int i = 0; i < frames; i++) {
            float s = 0;
            for (int c = 0; c < ch; c++) {
                s += b.getShort() / 32768.0F;
            }
            out[at + i] = s / ch;
        }
    }

    static ByteBuffer toPcm(float[] x, int n, float gain) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(n * 2);
        for (int i = 0; i < n; i++) {
            float v = Math.max(-1.0F, Math.min(1.0F, x[i] * gain));
            buffer.putShort((short) Math.round(v * 32767.0F));
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer slice(ByteBuffer src, int max) {
        int n = Math.min(max, src.remaining());
        ByteBuffer out = BufferUtils.createByteBuffer(n);
        ByteBuffer part = src.duplicate();
        part.limit(part.position() + n);
        out.put(part);
        out.flip();
        src.position(src.position() + n);
        return out;
    }

    private static float peak(float[] x, int n) {
        float m = 0;
        for (int i = 0; i < n; i++) {
            m = Math.max(m, Math.abs(x[i]));
        }
        return m;
    }

    /** 試聴用の fade(x, 0.003, 0.03) と同じ縁。末尾は届いたときだけ。 */
    private static void fade(float[] y, int len, float sr, boolean tail) {
        int na = Math.min(len, (int) (0.003 * sr)), nr = Math.min(len, (int) (0.03 * sr));
        for (int i = 0; i < na; i++) {
            y[i] *= (float) (0.5 - 0.5 * Math.cos(Math.PI * i / na));
        }
        if (tail) {
            for (int i = 0; i < nr; i++) {
                y[len - 1 - i] *= (float) (0.5 - 0.5 * Math.cos(Math.PI * i / nr));
            }
        }
    }
}
