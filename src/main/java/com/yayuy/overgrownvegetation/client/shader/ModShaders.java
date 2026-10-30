package com.yayuy.overgrownvegetation.client.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * この MOD の自前の描画用シェーダー(バニラの地形のシェーダーの上書きは {@code assets/minecraft/shaders/core/} にあり、ここでは登録しない)。
 * 中身は {@code assets/overgrown_vegetation/shaders/core/}(GLSL は ASCII だけ —— 日本語のコメントで落ちるドライバがある)。
 * 資源の読み直し(F3+T)でも呼ばれ直すので、受け取った物を毎回差し替える。どれも {@link OwnShaderInstance}(シェーダーパック使用中に設定で描けるように)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModShaders {

    /** 光の筋の中の光る胞子の束({@code client/atmosphere/ShaftMotes})。読み込めなかったら null(光る胞子は描かない)。 */
    private static ShaderInstance gpuMotes;

    public static ShaderInstance gpuMotes() {
        return gpuMotes;
    }

    /** 過繁茂のバイオームに漂う環境の胞子の束({@code client/atmosphere/AmbientSpores}、2026-10-30 夜)。読み込めなかったら null(描かない)。 */
    private static ShaderInstance ambientSpores;

    public static ShaderInstance ambientSpores() {
        return ambientSpores;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "gpu_motes"),
                            DefaultVertexFormat.POSITION),
                    shader -> gpuMotes = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("光る胞子の束のシェーダーを読み込めなかったので、光る胞子は描かない", e);
            gpuMotes = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "ambient_spores"),
                            DefaultVertexFormat.POSITION),
                    shader -> ambientSpores = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("環境の胞子のシェーダーを読み込めなかったので、環境の胞子は描かない", e);
            ambientSpores = null;
        }
    }

    private ModShaders() {
    }
}
