package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * 虫・爬虫類の声 1 つの音源(モノラル・その場所に置き、距離で線形に減る)。水音({@link WaterSoundInstance})と同じく、ファイルも {@code sounds.json} も無く、
 * 流す音の中身は {@link Stream} がその場で {@link InsectSynth} から作る(memory procedural-sound-stream)。大きさ・鳴く/黙るは {@link ForestInsectSound} が決める。
 * ヤモリの 1 回の鳴きは鳴き終えると流れを閉じ(read が null)、音源が止まる。
 */
@OnlyIn(Dist.CLIENT)
final class InsectSoundInstance extends AbstractTickableSoundInstance {

    private static final ResourceLocation CRICKET = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "forest_cricket");
    private static final ResourceLocation GECKO = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "forest_gecko");

    private final ForestInsectSound.Voice voice;
    /** 流れを作った(音のエンジンが鳴らし始めた)か。 */
    volatile boolean started;

    InsectSoundInstance(ForestInsectSound.Voice voice) {
        super(SoundEvent.createVariableRangeEvent(voice.cricket != null ? CRICKET : GECKO), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.voice = voice;
        this.looping = voice.cricket != null;
        this.delay = 0;
        this.volume = voice.volume;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.x = voice.x;
        this.y = voice.y;
        this.z = voice.z;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        ResourceLocation id = this.voice.cricket != null ? CRICKET : GECKO;
        // 線形に減って 0 になる距離: コオロギ 32・ヤモリ 24 ブロック(仮決め。鳴らす距離の上限 20・12 より少し遠く)。
        this.sound = new Sound(id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, this.voice.cricket != null ? 32 : 24);
        return new WeighedSoundEvents(id, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        this.started = true;
        return CompletableFuture.completedFuture(new Stream(this.voice));
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (this.voice.removed) {
            this.stop();
            return;
        }
        this.volume = this.voice.volume;
    }

    /** 声をその場で作って流す(100 ミリ秒ずつ。大きさは音源の volume で決めるので、ここは大きさ 1 で作る)。 */
    private static final class Stream implements AudioStream {
        private static final int FRAMES = InsectSynth.SAMPLE_RATE / 10;
        private final ForestInsectSound.Voice voice;
        private final AudioFormat format = new AudioFormat(InsectSynth.SAMPLE_RATE, 16, 1, true, false);
        private final float[] scratch = new float[FRAMES];

        Stream(ForestInsectSound.Voice voice) {
            this.voice = voice;
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int size) {
            java.util.Arrays.fill(this.scratch, 0.0F);
            if (this.voice.cricket != null) {
                this.voice.cricket.setSinging(this.voice.singing);
                this.voice.cricket.render(this.scratch, 0, FRAMES, 1.0);
            } else {
                if (this.voice.gecko.done()) {
                    return null;
                }
                this.voice.gecko.render(this.scratch, 0, FRAMES, 1.0);
            }
            ByteBuffer buffer = BufferUtils.createByteBuffer(FRAMES * 2);
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
}
