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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * 水音の音源(モノラル・水面の重心に置いて向きを付ける)。風の音({@link WindSoundInstance})と同じく、ファイルも {@code sounds.json} も無く、
 * 流す音の中身は {@link Stream} がその場で {@link WaterSynth} から作る(memory procedural-sound-stream)。大きさ・位置は毎 tick {@link ForestWaterSound} が決める。
 */
@OnlyIn(Dist.CLIENT)
final class WaterSoundInstance extends AbstractTickableSoundInstance {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "forest_water");
    private static int seeds = 1;

    WaterSoundInstance() {
        super(SoundEvent.createVariableRangeEvent(ID), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = ForestWaterSound.x();
        this.y = ForestWaterSound.y();
        this.z = ForestWaterSound.z();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(ID, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
        return new WeighedSoundEvents(ID, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new Stream(0x3A7E0000 + seeds++ * 7919));
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (ForestWaterSound.shouldStop(this)) {
            this.stop();
            return;
        }
        this.volume = ForestWaterSound.gain();
        this.x = ForestWaterSound.x();
        this.y = ForestWaterSound.y();
        this.z = ForestWaterSound.z();
    }

    /** 水音をその場で作って流す(150 ミリ秒ずつ。大きさは音源の volume で決めるので、ここは大きさ 1 で作る)。 */
    private static final class Stream implements AudioStream {
        private static final int FRAMES = WaterSynth.SAMPLE_RATE * 150 / 1000;
        private final WaterSynth synth;
        private final AudioFormat format = new AudioFormat(WaterSynth.SAMPLE_RATE, 16, 1, true, false);
        private final float[] scratch = new float[FRAMES];

        Stream(int seed) {
            this.synth = new WaterSynth(seed);
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int size) {
            this.synth.render(this.scratch, 0, FRAMES, ForestWaterSound.flow, 1.0);
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
