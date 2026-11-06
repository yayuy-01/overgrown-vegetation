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
import org.spongepowered.asm.mixin.injection.Redirect;
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

    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"))
    private void overgrown_vegetation$rainShader(Supplier<ShaderInstance> shader) {
        ShaderInstance white = ModShaders.rainWhite();
        if (RainLook.active() && white != null) {
            RenderSystem.setShader(() -> white);
        } else {
            RenderSystem.setShader(shader);
        }
    }

    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"))
    private void overgrown_vegetation$rainTexture(int unit, ResourceLocation texture) {
        RainLook.noteTexture(texture);
        RenderSystem.setShaderTexture(unit, texture);
    }

    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer overgrown_vegetation$rainVertex(BufferBuilder builder, float x, float y, float z) {
        return builder.addVertex(RainLook.shiftX(x, y, z), y, RainLook.shiftZ(x, y, z));
    }
}
