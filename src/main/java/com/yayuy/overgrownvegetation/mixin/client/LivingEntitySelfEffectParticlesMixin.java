package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.yayuy.overgrownvegetation.client.SelfEffectParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 一人称のとき、自分に付いた胞子感染の効果の粒を出さない(2026-11-06。判定は {@link SelfEffectParticles})。
 * {@code tickEffects} の中で効果の粒を出す 1 か所({@code Level#addParticle})だけを包む。クライアントのワールドのときだけ判定する。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySelfEffectParticlesMixin {

    @WrapWithCondition(
            method = "tickEffects",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean overgrown_vegetation$hideOwnSporeParticles(Level level, ParticleOptions particle, double x, double y, double z,
                                                              double dx, double dy, double dz) {
        return !level.isClientSide() || SelfEffectParticles.shouldSpawn((LivingEntity) (Object) this, particle);
    }
}
