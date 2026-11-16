package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * <b>フェーズが上がったときの合図の音</b> 2 つ(2026-11-16。鳴らすのは {@code client/atmosphere/PhaseCue})。どちらもファイルは無く、その場で作って流す
 * ({@link WindSoundInstance} と同じ道)。1 回鳴って終わる(鳴りっぱなしにしない)。
 *
 * <ul>
 *     <li><b>地鳴り</b>({@link #rumble}): 地の底からの低い音。なめらかな低い雑音(40〜110 Hz あたり)だけで、粒・クリックは入れない
 *     (memory synth-sound-no-crackle)。約 6 秒で、ゆっくり立ち上がってゆっくり消える。聞く人に付いて回る(向きなし)。</li>
 *     <li><b>ざわめき</b>({@link #rustle}): 汚染の森の方角から、風も無いのに葉と幹が一斉にざわめく。新しい音色は作らず、
 *     ユーザーが了承済みの風の音の合成({@link WindSynth} の「遠くの林」、強い風の具合)を 1 回のうねりにして使う。約 6 秒。向きつき(モノラル)。</li>
 * </ul>
 * 音の種類は「環境音」。字幕は出さない。大きさ・長さは仮決め(耳で確かめていない。見本の wav は {@link PhaseCueSynth#main} で書き出せる)。
 */
public final class PhaseCueSound {

    private static final ResourceLocation RUMBLE_ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "phase_cue_rumble");
    private static final ResourceLocation RUSTLE_ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "phase_cue_rustle");
    private static final int RATE = PhaseCueSynth.RATE;
    private static final int BUFFER_FRAMES = RATE * 150 / 1000;
    private static int seeds = 1;

    private PhaseCueSound() {
    }

    /** 地鳴りを 1 回鳴らす(聞く人の真ん中)。 */
    public static void rumble(float volume) {
        if (volume > 0.0F) {
            Minecraft.getInstance().getSoundManager().play(new Instance(true, volume, 0.0, 0.0, 0.0));
        }
    }

    /** ざわめきを 1 回鳴らす(世界の中の位置 x y z から)。 */
    public static void rustle(float volume, double x, double y, double z) {
        if (volume > 0.0F) {
            Minecraft.getInstance().getSoundManager().play(new Instance(false, volume, x, y, z));
        }
    }

    /** 1 回で終わる音の流れ。終わったら空を返す(流す側がそこで止まる)。 */
    private static final class Stream implements AudioStream {
        private final boolean rumble;
        private final PhaseCueSynth.Rumble low;
        private final WindSynth wind;
        private final AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        private final float[] scratch = new float[BUFFER_FRAMES];
        private long frame;

        Stream(boolean rumble, int seed) {
            this.rumble = rumble;
            this.low = rumble ? new PhaseCueSynth.Rumble(seed) : null;
            this.wind = rumble ? null : new WindSynth(false, true, seed);
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int size) {
            double seconds = this.rumble ? PhaseCueSynth.RUMBLE_SECONDS : PhaseCueSynth.RUSTLE_SECONDS;
            if (this.frame >= (long) (seconds * RATE)) {
                return BufferUtils.createByteBuffer(0);
            }
            if (this.rumble) {
                this.low.render(this.scratch, 0, BUFFER_FRAMES);
            } else {
                for (int done = 0; done < BUFFER_FRAMES; done += WindSynth.BLOCK) {
                    int n = Math.min(WindSynth.BLOCK, BUFFER_FRAMES - done);
                    double e = PhaseCueSynth.rustleEnvelope((this.frame + done + n * 0.5) / RATE);
                    // 風の具合は強め(葉の「ざーっ」と幹の「ごぉー」が両方出る)。大きさだけをうねらせる。
                    this.wind.render(this.scratch, done, n, 0.55 + 0.45 * e, e);
                }
            }
            this.frame += BUFFER_FRAMES;
            ByteBuffer buffer = BufferUtils.createByteBuffer(this.scratch.length * 2);
            for (float v : this.scratch) {
                buffer.putShort((short) Math.round(Math.max(-1.0F, Math.min(1.0F, v)) * 32767.0F));
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() {
        }
    }

    private static final class Instance extends AbstractSoundInstance {
        private final boolean rumble;
        private final ResourceLocation id;

        Instance(boolean rumble, float volume, double x, double y, double z) {
            super(SoundEvent.createVariableRangeEvent(rumble ? RUMBLE_ID : RUSTLE_ID), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.rumble = rumble;
            this.id = rumble ? RUMBLE_ID : RUSTLE_ID;
            this.looping = false;
            this.delay = 0;
            this.volume = volume;
            this.relative = rumble;
            this.x = x;
            this.y = y;
            this.z = z;
            // 大きさはこちらで決める(距離では弱めない)。ざわめきは位置から向きだけが付く。
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            this.sound = new Sound(this.id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
            return new WeighedSoundEvents(this.id, null);
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new Stream(this.rumble, 0x3C6EF372 + seeds++ * 7919));
        }
    }
}
