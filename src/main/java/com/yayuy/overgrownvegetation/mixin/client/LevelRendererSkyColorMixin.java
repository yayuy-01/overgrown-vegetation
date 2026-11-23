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
    private void overgrown_vegetation$skyColors(com.mojang.blaze3d.vertex.PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy,
                                                Runnable skyFogSetup, CallbackInfo ci) {
        // 1.20.1: renderSky の 1 つ目は PoseStack(本流の frustumMatrix = 空の箱を描く時の行列 = poseStack.last().pose())
        SkyColors.draw(poseStack.last().pose(), projectionMatrix, partialTick, camera);
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

    /**
     * 濃霧(天気。11-16 ユーザー「霧の中の太陽はぼかす」)の日は、太陽・月の絵を霧に沈める(四角い絵がそのまま見えていた)。
     * 空を描くときの「雨の度合い」(太陽・月・星の濃さを 1 − 雨 で決めている)へ、霧で満ちた空の割合を足す。太陽のまわりの光は、
     * 霧の色の前方散乱と光のにじみが受け持つ。
     */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"), require = 0)
    private float overgrown_vegetation$fogSun(net.minecraft.client.multiplayer.ClientLevel level, float partialTick, Operation<Float> original) {
        float rain = original.call(level, partialTick);
        return 1.0F - (1.0F - rain) * (1.0F - com.yayuy.overgrownvegetation.client.atmosphere.DenseFog.skyFillPublic());
    }
}
