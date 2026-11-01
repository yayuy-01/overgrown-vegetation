package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.atmosphere.sound.UnderwaterSound;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.UnderwaterAmbientSoundInstances;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 過繁茂の水に潜っている間、バニラの水中の環境音(ambient.underwater.loop と additions)を {@link UnderwaterSound#vanillaScale} 倍に下げる(2026-11-01、仮決め 0.3)。
 * 代わりに {@code UnderwaterSound} のこもった低音が鳴る。音のエンジンは鳴らし始めにも毎 tick にも getVolume を読むので、ここで掛ければ鳴っている音にも効く。
 */
@Mixin(AbstractSoundInstance.class)
public abstract class UnderwaterAmbientVolumeMixin {

    @Inject(method = "getVolume", at = @At("RETURN"), cancellable = true)
    private void overgrownVegetation$quietVanillaUnderwater(CallbackInfoReturnable<Float> cir) {
        Object self = this;
        if (self instanceof UnderwaterAmbientSoundInstances.UnderwaterAmbientSoundInstance || self instanceof UnderwaterAmbientSoundInstances.SubSound) {
            float scale = UnderwaterSound.vanillaScale();
            if (scale < 1.0F) {
                cir.setReturnValue(cir.getReturnValueF() * scale);
            }
        }
    }
}
