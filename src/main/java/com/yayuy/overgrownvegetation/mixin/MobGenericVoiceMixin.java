package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.infect.GenericInfectionVoice;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 【1.20.1 だけのファイル】汎用の感染体の声の、鳴き声の分({@link LivingEntityGenericVoiceMixin} の続き)。
 * 本流は {@code Mob#playAmbientSound} が {@code LivingEntity#makeSound} を呼ぶので 1 つの Mixin で済むが、1.20.1 の {@code playAmbientSound} は
 * {@code this.playSound(音, 大きさ, 高さ)} を直に呼ぶ。その呼び出しは {@code Mob} の中にあるので、{@code Mob} を狙う Mixin を分けた。
 */
@Mixin(Mob.class)
public abstract class MobGenericVoiceMixin {

    @WrapOperation(method = "playAmbientSound",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void overgrown_vegetation$crushGenericAmbient(Mob self, SoundEvent sound, float volume, float pitch, Operation<Void> original) {
        if (sound != null && GenericInfectionVoice.play(self, sound, volume)) {
            return;
        }
        original.call(self, sound, volume, pitch);
    }
}
