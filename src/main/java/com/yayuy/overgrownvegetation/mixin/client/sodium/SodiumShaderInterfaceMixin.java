package com.yayuy.overgrownvegetation.mixin.client.sodium;

import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium が地形の層を描き始めるとき(プログラムを結んだ後)に、この MOD の uniform とテクスチャを流す(2026-10-22、{@link SodiumTerrain})。
 * Sodium の uniform は {@code glUniform*} なので、ここより前(AFTER_SKY)では渡せない。
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface", remap = false)
public abstract class SodiumShaderInterfaceMixin {

    @Inject(method = "setupState", at = @At("TAIL"), require = 0)
    private void ov$sodiumUniforms(CallbackInfo ci) {
        SodiumTerrain.upload();
    }
}
