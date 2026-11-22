package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.atmosphere.RainLook;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.WaterDropParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 雨の跳ね・水しぶき({@code rain}・{@code splash} の粒)を白で描く(2026-11-06 雨の見え方、{@link RainLook#WHITE_WATER})。 */
@Mixin(WaterDropParticle.class)
public abstract class WaterDropParticleWhiteMixin {

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$white(CallbackInfoReturnable<ParticleRenderType> cir) {
        if (RainLook.whiteParticles()) {
            cir.setReturnValue(RainLook.WHITE_WATER);
        }
    }
}
