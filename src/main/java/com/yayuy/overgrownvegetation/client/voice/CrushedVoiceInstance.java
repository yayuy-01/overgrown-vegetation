package com.yayuy.overgrownvegetation.client.voice;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 任意の SoundEvent を<b>鳴らすときに加工して</b>鳴らす 1 回きりの音(2026-11-14)。音のファイルは足さない。
 *
 * <p>バニラの {@code resolve} で、その SoundEvent の変種を 1 つ選ぶ(バニラの Mob の声もほかの MOD の声も同じ道)。
 * 選んだ変種を、同じ音量・高さ・届く距離のまま次のどれかで鳴らす。
 * <ol>
 *     <li>1 回だけ加工して持っていれば({@link CrushedVoiceCache})、ふつうの効果音の枠で(流す音の 8 本を使わない)。</li>
 *     <li>初めての変種なら、加工が済むのを待ってから同じ変種で鳴らし直す(数十〜百ミリ秒遅れる。黙らせない)。</li>
 *     <li>差し込みが効いていない・長すぎる音・待っても済まないときは、「流す音」に作り替え、バニラが開いた流れを {@link CrushedAudioStream} に包む。</li>
 * </ol>
 * 字幕はバニラの元の声の字幕が出る(「牛が鳴く」など。本物の {@link WeighedSoundEvents} を返すため)。
 */
@OnlyIn(Dist.CLIENT)
public final class CrushedVoiceInstance extends AbstractSoundInstance {

    private final int mode;
    private final double lo, hi;
    private final double strength;
    private final boolean small;
    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、どちらの方式で鳴らしたかをログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    /** 加工を待つのは 2 回まで(それでも済まなければ流して鳴らす)。 */
    private static final int MAX_WAITS = 2;

    /**
     * @param event    鳴らす SoundEvent の場所(例 minecraft:entity.cow.ambient)
     * @param mode     加工の版 0〜3({@link VoiceCrusher})
     * @param small    小型の Mob の帯(700〜4200 Hz)にする。false は中型(350〜2600 Hz)
     * @param strength 加工の強さ(1 = 試聴用と同じ)
     */
    public CrushedVoiceInstance(ResourceLocation event, SoundSource source, double x, double y, double z,
                                float volume, float pitch, int mode, boolean small, float strength, long seed) {
        super(event, source, RandomSource.create(seed));
        this.x = x;
        this.y = y;
        this.z = z;
        this.volume = volume;
        this.pitch = pitch;
        this.mode = mode;
        this.lo = small ? VoiceCrusher.SMALL_LO : VoiceCrusher.MID_LO;
        this.hi = small ? VoiceCrusher.SMALL_HI : VoiceCrusher.MID_HI;
        this.small = small;
        this.strength = strength;
        this.sourceArg = source;
        this.strengthArg = strength;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.relative = false;
    }

    /** 加工が済むのを待って鳴らし直した回数(待ちの繰り返しを止める)。 */
    private int waits;
    /** 鳴らし直すとき、前に選んだ変種(say1〜3 など)をそのまま使う。 */
    @Nullable
    private Sound forced;
    private final SoundSource sourceArg;
    private final float strengthArg;

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents events;
        if (this.forced != null) {
            events = manager.getSoundEvent(this.location);
            this.sound = this.forced;
        } else {
            events = super.resolve(manager);
        }
        Sound picked = this.sound;
        if (events != null && picked != null && picked != SoundManager.EMPTY_SOUND && picked != SoundManager.INTENTIONALLY_EMPTY_SOUND
                && !picked.shouldStream()) {
            // 1 回だけ加工して持った方があれば、ふつうの効果音の枠で鳴らす(流す音の 8 本を使わない)。
            ResourceLocation held = CrushedVoiceCache.staticLocation(picked.getLocation(), picked.getPath(), this.mode, this.small, this.strength);
            if (held == null && this.waits < MAX_WAITS) {
                // まだ加工中: 今回は鳴らさず(バニラの「わざと空の音」= 字幕も出ない)、済んだらすぐ同じ変種で鳴らし直す。
                // 流す方式に落とさないので、初めての声を群れが一度に鳴らしても流す音の 8 本で黙る声が出ない(数十〜百ミリ秒遅れる)。
                java.util.concurrent.CompletableFuture<?> building = CrushedVoiceCache.building(picked.getLocation(), this.mode, this.small, this.strength);
                if (building != null) {
                    CrushedVoiceInstance again = new CrushedVoiceInstance(this.location, this.sourceArg, this.x, this.y, this.z,
                            this.volume, this.pitch, this.mode, this.small, this.strengthArg, 0L);
                    again.waits = this.waits + 1;
                    again.forced = picked;
                    building.whenComplete((buffer, error) -> net.minecraft.client.Minecraft.getInstance().execute(
                            () -> net.minecraft.client.Minecraft.getInstance().getSoundManager().play(again)));
                    this.sound = SoundManager.INTENTIONALLY_EMPTY_SOUND;
                    if (DEV_LOG) {
                        OvergrownVegetation.LOGGER.info("CrushedVoice 加工が済んでから鳴らす {} 版 {}", picked.getLocation(), this.mode);
                    }
                    return events;
                }
            }
            this.sound = new Sound(held != null ? held : picked.getLocation(), picked.getVolume(), picked.getPitch(), picked.getWeight(),
                    Sound.Type.FILE, held == null, false, picked.getAttenuationDistance());
            if (DEV_LOG) {
                OvergrownVegetation.LOGGER.info("CrushedVoice {} {} 版 {}", held != null ? "持った方で鳴らす" : "流して鳴らす", picked.getLocation(), this.mode);
            }
        }
        return events;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        String name = sound.getLocation().toString();
        CrushedVoiceCache.checkHook(soundBuffers);
        return soundBuffers.getStream(sound.getPath(), looping).thenApply(inner -> {
            try {
                VoiceCrusher crusher = new VoiceCrusher(inner.getFormat().getSampleRate(), this.mode, this.lo, this.hi, this.strength);
                return CrushedAudioStream.open(inner, crusher, name);
            } catch (IOException e) {
                OvergrownVegetation.LOGGER.warn("声の加工: 元の流れを読めなかった({}): {}", name, e.toString());
                throw new CompletionException(e);
            }
        });
    }
}
