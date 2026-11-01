package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.yayuy.overgrownvegetation.client.light.DynamicLights;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 粒の明るさに動く光({@link DynamicLights})を足す(2026-11-01 地下モードの試作)。getLightColor を上書きして自分で光る粒は通らない。
 */
@Mixin(Particle.class)
public abstract class ParticleDynamicLightMixin {

    @Shadow
    protected double x;
    @Shadow
    protected double y;
    @Shadow
    protected double z;

    @ModifyReturnValue(method = "getLightColor", at = @At("RETURN"))
    private int overgrown_vegetation$dynamicLight(int packed) {
        if (!DynamicLights.any()) {
            return packed;
        }
        return DynamicLights.brighten(packed, new Vec3(this.x, this.y, this.z));
    }
}
