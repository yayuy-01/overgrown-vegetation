package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.atmosphere.RainFeel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 雨音を雨の強さで変える(2026-11-12、雨の種類。ユーザー選択「音: 雨音の大きさと質」)。
 * バニラの {@code LevelRenderer.tickRain} が鳴らす雨音(屋根の下の {@code weather.rain.above} も)の音量と高さに、カメラの所の雨の強さ
 * ({@link RainFeel})の倍率を掛ける: 小雨はさらさらと小さく高め、大雨はざあざあと大きく低め。水しぶきの粒の数はバニラが雨の強さ
 * ({@code getRainLevel}、{@code LevelRainAboveCloudsMixin} で強さを掛けてある)の 2 乗で決めるので、そのまま強弱が付く。
 * 当たらなくても雨音がバニラのままになるだけ(require = 0)。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRainSoundMixin {

    @ModifyArg(method = "tickRain", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
            index = 3)
    private float overgrown_vegetation$rainVolume(float volume) {
        return volume * RainFeel.soundVolume();
    }

    @ModifyArg(method = "tickRain", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
            index = 4)
    private float overgrown_vegetation$rainPitch(float pitch) {
        return pitch * RainFeel.soundPitch();
    }
}
