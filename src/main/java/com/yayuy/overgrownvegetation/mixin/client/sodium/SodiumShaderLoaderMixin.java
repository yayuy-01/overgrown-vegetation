package com.yayuy.overgrownvegetation.mixin.client.sodium;

import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium の地形のシェーダーの中身を、この MOD の演出入りに差し替える(2026-10-22、{@link SodiumTerrain})。
 * Sodium の型は参照しない(Sodium をコンパイルの依存に入れずに済み、Sodium が無ければ {@code OvergrownMixinPlugin} が外す)。
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public abstract class SodiumShaderLoaderMixin {

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ov$sodiumSource(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        String patched = SodiumTerrain.patchSource(name);
        if (patched != null) {
            cir.setReturnValue(patched);
        }
    }
}
