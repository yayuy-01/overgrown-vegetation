package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.atmosphere.RainLook;
import net.minecraft.client.particle.DripParticle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 水の滴(葉の滴・鍾乳石・垂れる根。{@code dripping_water}・{@code falling_water}・鍾乳石の水)を白で描く(2026-11-06 雨の見え方、
 * ユーザー「木の葉や洞窟の根から出る水滴が青だとおかしい」)。白い雨のシェーダーは頂点の色(バニラの青)を使わない。溶岩・蜂蜜などはそのまま。
 */
@Mixin(DripParticle.class)
public abstract class DripParticleWhiteMixin {

    @Shadow
    @Final
    private Fluid type;

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$white(CallbackInfoReturnable<ParticleRenderType> cir) {
        if (this.type.isSame(Fluids.WATER) && RainLook.whiteParticles()) {
            cir.setReturnValue(RainLook.WHITE_WATER);
        }
    }
}
