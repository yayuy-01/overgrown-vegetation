package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.RenderPhaseHook;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 測る道具のための区切り({@link RenderPhaseHook}。ふだんは何もしない)。2026-10-20。 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPhaseMarkMixin {

    @Inject(method = "setupRender", at = @At("HEAD"), require = 0)
    private void overgrownvegetation$markSetupStart(CallbackInfo ci) {
        RenderPhaseHook.mark("見える区画の更新の始め");
    }

    @Inject(method = "setupRender", at = @At("TAIL"), require = 0)
    private void overgrownvegetation$markSetupEnd(CallbackInfo ci) {
        RenderPhaseHook.mark("見える区画の更新の終わり");
    }

    @Inject(method = "compileSections", at = @At("TAIL"), require = 0)
    private void overgrownvegetation$markCompileEnd(CallbackInfo ci) {
        RenderPhaseHook.mark("組み立ての依頼・送り込みの終わり");
    }
}
