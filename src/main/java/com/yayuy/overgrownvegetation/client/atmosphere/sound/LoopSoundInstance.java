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
 * 流し続ける合成の音 1 本の音源(2026-11-01。胞子の溜まりのうなり・水中の低音)。水音({@link WaterSoundInstance})と同じく、ファイルも {@code sounds.json} も無く、
 * 中身は {@link Source#render} がその場で作る(memory procedural-sound-stream)。大きさ・位置は毎 tick {@link Source} から読む(距離による減りは呼び手が volume に入れる)。
 */
@OnlyIn(Dist.CLIENT)
final class LoopSoundInstance extends AbstractTickableSoundInstance {

    /** 音の中身と、大きさ・位置の出どころ。render は音のスレッドから呼ばれる。 */
    interface Source {
        float volume();

        double x();

        double y();

        double z();

        /** この音源をもう止めるか(係が別の音源に替えた・止めた)。 */
        boolean shouldStop(LoopSoundInstance instance);

        void render(float[] out, int frames);
    }

    private final Source source;
    private final ResourceLocation id;

    /** relative = プレイヤーに付いて回る(向きなし。位置は無視)。 */
    LoopSoundInstance(String name, Source source, boolean relative) {
        super(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, name)), SoundSource.AMBIENT,
                SoundInstance.createUnseededRandom());
        this.id = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, name);
        this.source = source;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.relative = relative;
        this.attenuation = SoundInstance.Attenuation.NONE;
        if (!relative) {
            this.x = source.x();
            this.y = source.y();
            this.z = source.z();
        }
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(this.id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
        return new WeighedSoundEvents(this.id, null);
    }

    /** 音のエンジンが流れを作った(鳴らし始めた)か。確かめのログ用。 */
    volatile boolean streaming;

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        this.streaming = true;
        return CompletableFuture.completedFuture(new Stream(this.source));
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (this.source.shouldStop(this)) {
            this.stop();
            return;
        }
        this.volume = this.source.volume();
        if (!this.relative) {
            this.x = this.source.x();
            this.y = this.source.y();
            this.z = this.source.z();
        }
    }

    /** 150 ミリ秒ずつ作って流す(大きさは音源の volume で決めるので、ここは大きさ 1 で作る)。 */
    private static final class Stream implements AudioStream {
        private static final int FRAMES = CaveWaterSynth.SAMPLE_RATE * 150 / 1000;
        private final Source source;
        private final AudioFormat format = new AudioFormat(CaveWaterSynth.SAMPLE_RATE, 16, 1, true, false);
        private final float[] scratch = new float[FRAMES];

        Stream(Source source) {
            this.source = source;
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int size) {
            java.util.Arrays.fill(this.scratch, 0.0F);
            this.source.render(this.scratch, FRAMES);
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
