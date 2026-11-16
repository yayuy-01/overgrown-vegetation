package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.client.sky.SkyColors;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 空の色(2026-11-13 シェーダーの仕上げ A、{@link SkyColors})。バニラの空の円盤を描いた直後(太陽・月・星の前)に、時刻の色の空を重ねる。
 * バニラの扇形の朝焼けは、その空が出ている分だけ薄くする(空の描画の中でだけ。霧の色・雲が読む {@code getSunriseColor} はそのまま)。
 *
 * <p>{@code require = 0}: ほかの MOD が空の描画を差し替えていて差せなくても起動は止めない(そのときはバニラの空のまま)。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyColorMixin {

    @Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;unbind()V", ordinal = 0, shift = At.Shift.AFTER),
            require = 0)
    private void overgrown_vegetation$skyColors(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy,
                                                Runnable skyFogSetup, CallbackInfo ci) {
        SkyColors.draw(frustumMatrix, projectionMatrix, partialTick, camera);
    }

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"), require = 0)
    private float[] overgrown_vegetation$fadeSunriseFan(DimensionSpecialEffects effects, float timeOfDay, float partialTick, Operation<float[]> original) {
        return SkyColors.fadeFan(original.call(effects, timeOfDay, partialTick));
    }

    /** 濃霧(天気。11-16)の日は、星を霧に沈める(空を霧の色で満たしても、星は後から描かれて透けて見えた)。 */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"), require = 0)
    private float overgrown_vegetation$fogStars(net.minecraft.client.multiplayer.ClientLevel level, float partialTick, Operation<Float> original) {
        return original.call(level, partialTick) * (1.0F - com.yayuy.overgrownvegetation.client.atmosphere.DenseFog.skyFillPublic());
    }
}
