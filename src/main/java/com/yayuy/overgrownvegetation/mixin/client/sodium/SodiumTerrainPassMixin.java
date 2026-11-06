package com.yayuy.overgrownvegetation.mixin.client.sodium;

import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium が地形の層を描き始めるとき、その層が半透明(水・ガラス)かを {@link SodiumTerrain} に知らせる(2026-11-06 陸の日光の照り)。
 * 前は uniform を流すときの GL のブレンドの状態で決めていたが、ほかの MOD が GlStateManager を通さずにブレンドを入れたままにすると
 * 陸(固い層)まで半透明扱いになり、水面の照りが草の斜面に出た(ユーザーの画面)。Sodium の型は参照しない(@Pseudo)。
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass", remap = false)
public abstract class SodiumTerrainPassMixin {

    @Shadow
    public abstract boolean isTranslucent();

    @Inject(method = "startDrawing", at = @At("HEAD"), require = 0)
    private void ov$notePass(CallbackInfo ci) {
        SodiumTerrain.notePass(this.isTranslucent());
    }

    @Inject(method = "endDrawing", at = @At("TAIL"), require = 0)
    private void ov$endPass(CallbackInfo ci) {
        SodiumTerrain.notePass(null);
    }
}
