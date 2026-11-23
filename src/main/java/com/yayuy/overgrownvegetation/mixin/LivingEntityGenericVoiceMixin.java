package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.infect.GenericInfectionVoice;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 汎用の感染体の声(2026-11-15 ユーザー決定 K6: 元の Mob の声を G1 と同じ加工で鳴らす)。鳴き声・被弾・死の声はバニラでは
 * {@code LivingEntity#makeSound} 1 か所を通る(鳴き声は {@code Mob#playAmbientSound}、被弾は {@code playHurtSound}、死は {@code hurt} の中)。
 * 足音・攻撃音・技の音は {@code playSound} を直に呼ぶので巻き込まない。変わった Mob なら元の音を鳴らさず、加工した音だけを鳴らす(二重に鳴らさない)。
 * {@code makeSound} を上書きして直に鳴らすほかの MOD の Mob と、クライアントだけで鳴る音(ハチの羽音・ドラゴンのうなり)は漏れる。
 *
 * <p>【1.20.1】{@code makeSound} は 1.20.5 で足された口で、1.20.1 には無い。1.20.1 は 3 か所で {@code this.playSound(音, 大きさ, 高さ)} を直に呼ぶので、
 * その呼び出しを 1 つずつ包む: 被弾は {@code playHurtSound}、死は {@code hurt} の中(1 か所だけ)。鳴き声は {@code Mob} の物なので
 * {@link MobGenericVoiceMixin}(1.20.1 だけのファイル)が包む。大きさは呼び出しに渡る値({@code getSoundVolume()})をそのまま使う。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGenericVoiceMixin {

    @WrapOperation(method = {"playHurtSound", "hurt"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void overgrown_vegetation$crushGenericVoice(LivingEntity self, SoundEvent sound, float volume, float pitch, Operation<Void> original) {
        if (sound != null && GenericInfectionVoice.play(self, sound, volume)) {
            return;
        }
        original.call(self, sound, volume, pitch);
    }
}
