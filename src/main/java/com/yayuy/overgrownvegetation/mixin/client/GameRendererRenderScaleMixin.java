package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.RenderScale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 描画解像度の縮小({@link RenderScale}、2026-10-23): 世界は {@code renderLevel} の頭から手の前までを縮めた描画先へ描き、
 * 手の前で主画面へ拡大して戻す(手・GUI・HUD は等倍)。抜けた所で戻し忘れないよう {@code renderLevel} の終わりでも戻す。
 */
@Mixin(GameRenderer.class)
public class GameRendererRenderScaleMixin {

    @Shadow
    @Final
    Minecraft minecraft;

    @Inject(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void overgrown_vegetation$beginScaledWorld(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        RenderScale.beginWorld(this.minecraft);
    }

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand"))
    private void overgrown_vegetation$endScaledWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        RenderScale.endWorld(this.minecraft);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void overgrown_vegetation$endScaledWorldFailsafe(DeltaTracker deltaTracker, CallbackInfo ci) {
        RenderScale.endWorld(this.minecraft);
    }
}
