package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.atmosphere.RainLook;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * 雨と雪を風に靡かせ、白で描く(2026-11-06 雨の見え方、{@link RainLook})。バニラの {@code renderSnowAndRain} の
 * 柱の頂点を高さに比例して風上へずらし、シェーダーを白い雨({@code rain_white})に替える。雨の跳ね・音はそのまま。
 * {@link RainLook#active} が偽(シェーダーパック使用中・設定で切った・シェーダーを読めなかった)なら何もしない。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRainLookMixin {

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void overgrown_vegetation$rainPrepare(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ,
                                                 CallbackInfo ci) {
        RainLook.prepare(partialTick, camX, camZ);
    }

    // 見直し 第 2 回 ④5(11-18): @Redirect は同じ呼び出しをほかの MOD も差し替えると起動で落ちる。@WrapOperation(重ねられる)・require = 0
    // (当たらなければ雨の見た目の差し替えだけ無くなる)にした。中身は同じ。
    @WrapOperation(method = "renderSnowAndRain", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"))
    private void overgrown_vegetation$rainShader(Supplier<ShaderInstance> shader, Operation<Void> original) {
        ShaderInstance white = ModShaders.rainWhite();
        if (RainLook.active() && white != null) {
            original.call((Supplier<ShaderInstance>) () -> white);
        } else {
            original.call(shader);
        }
    }

    @WrapOperation(method = "renderSnowAndRain", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"))
    private void overgrown_vegetation$rainTexture(int unit, ResourceLocation texture, Operation<Void> original) {
        RainLook.noteTexture(texture);
        original.call(unit, texture);
    }

    @WrapOperation(method = "renderSnowAndRain", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;vertex(DDD)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    // 1.20.1: 本流の addVertex(float×3) は vertex(double×3)(同じくカメラからの相対の位置)。ずらしの計算は float のまま
    private VertexConsumer overgrown_vegetation$rainVertex(BufferBuilder builder, double x, double y, double z, Operation<VertexConsumer> original) {
        float fx = (float) x, fy = (float) y, fz = (float) z;
        return original.call(builder, (double) RainLook.shiftX(fx, fy, fz), y, (double) RainLook.shiftZ(fx, fy, fz));
    }
}
