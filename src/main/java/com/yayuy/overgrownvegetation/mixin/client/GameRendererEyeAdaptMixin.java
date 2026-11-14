package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.atmosphere.EyeAdaptation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 洞窟の出入りの目の慣れ({@link EyeAdaptation}、2026-11-07)の全画面の補正を、{@code renderLevel} の直後(地形・Mob・粒・手を描いた後、GUI の前)に掛ける。
 * RenderLevelStageEvent の AFTER_LEVEL は手より前なので、手だけ白く飛ばないのを避けてここにした。Sodium・Iris は GameRenderer#render を書き換えない。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererEyeAdaptMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER),
            require = 0)
    private void overgrownvegetation$eyeAdapt(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // 光るもののにじみ(11-13 仕上げ B)を先に: 目の慣れのまぶしさは、にじみの乗った画面に掛かる。
        // 隙間と根元の陰り(簡易 AO、11-14 仕上げ H。既定オフ)は、にじみより先(暗くした上に光の暈が乗る)。
        com.yayuy.overgrownvegetation.client.atmosphere.SimpleAo.renderPost();
        com.yayuy.overgrownvegetation.client.atmosphere.GlowBloom.renderPost();
        EyeAdaptation.renderPost();
    }
}
