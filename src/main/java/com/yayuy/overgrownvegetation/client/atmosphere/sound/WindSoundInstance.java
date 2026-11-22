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

import java.util.concurrent.CompletableFuture;

/**
 * 風の音の 1 つの音源(まわりの葉擦れ = ステレオで真ん中、または遠くの突風 = モノラルで向きつき)。
 *
 * <p>音のファイルは無い。{@code sounds.json} にも載せず、ここで「流す音」({@link Sound} の stream)を自分で作って返し、
 * 流れの中身は {@link #getStream} で {@link WindAudioStream} を渡す(NeoForge が足した口。音楽と同じ流す道を通る)。
 * 字幕は出さない(葉擦れはいつも鳴っている環境音なので、バニラのバイオームの環境音と同じ扱い)。
 * 大きさ・位置は毎 tick {@link ForestWindSound} が決める(遅れなし)。音の中身(風の強さ)は流れの側で先の風を読む。
 */
@OnlyIn(Dist.CLIENT)
final class WindSoundInstance extends AbstractTickableSoundInstance {

    private static final ResourceLocation NEAR_ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "canopy_wind_near");
    private static final ResourceLocation FAR_ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "canopy_wind_far");
    private static int seeds = 1;

    private final boolean far;
    private final ResourceLocation id;

    WindSoundInstance(boolean far) {
        super(SoundEvent.createVariableRangeEvent(far ? FAR_ID : NEAR_ID), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.far = far;
        this.id = far ? FAR_ID : NEAR_ID;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        // まわりの音は聞く人に付いて回る(真ん中)。遠くの音は世界の中の位置(毎 tick 動かす)。どちらも距離では弱めない(大きさはこちらで決める)。
        this.relative = !far;
        this.attenuation = SoundInstance.Attenuation.NONE;
    }

    boolean isFar() {
        return this.far;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(this.id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
        return new WeighedSoundEvents(this.id, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new WindAudioStream(this.far, 0x51ED2700 + seeds++ * 7919));
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (ForestWindSound.shouldStop(this)) {
            this.stop();
            return;
        }
        this.volume = ForestWindSound.volume(this.far);
        if (this.far) {
            this.x = ForestWindSound.farX();
            this.y = ForestWindSound.farY();
            this.z = ForestWindSound.farZ();
        }
    }
}
