package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.infect.GenericInfectionVoice;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 汎用の感染体の声(2026-11-15 ユーザー決定 K6: 元の Mob の声を G1 と同じ加工で鳴らす)。鳴き声・被弾・死の声はバニラでは
 * {@code LivingEntity#makeSound} 1 か所を通る(鳴き声は {@code Mob#playAmbientSound}、被弾は {@code playHurtSound}、死は {@code hurt} の中)。
 * 足音・攻撃音・技の音は {@code playSound} を直に呼ぶので巻き込まない。変わった Mob なら元の音を鳴らさず、加工した音だけを鳴らす(二重に鳴らさない)。
 * {@code makeSound} を上書きして直に鳴らすほかの MOD の Mob と、クライアントだけで鳴る音(ハチの羽音・ドラゴンのうなり)は漏れる。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGenericVoiceMixin {

    @Shadow
    protected abstract float getSoundVolume();

    @Inject(method = "makeSound", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$crushGenericVoice(@Nullable SoundEvent sound, CallbackInfo ci) {
        if (sound != null && GenericInfectionVoice.play((LivingEntity) (Object) this, sound, getSoundVolume())) {
            ci.cancel();
        }
    }
}
