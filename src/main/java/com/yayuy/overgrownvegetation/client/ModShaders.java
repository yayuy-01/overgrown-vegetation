package com.yayuy.overgrownvegetation.client;

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
 * この MOD の描画用シェーダー(バニラのシェーダーは上書きしない。自分の物を描く型にだけ使う)。
 * 中身は {@code assets/overgrown_vegetation/shaders/core/}(GLSL は ASCII だけで書く —— 日本語のコメントで落ちるドライバがある)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModShaders {

    /** 巨大樹のシルエット({@link GiantTreeSilhouettes})。読み込めなかったら null(シルエットは描かない)。 */
    static ShaderInstance giantSilhouette;

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "giant_silhouette"),
                            DefaultVertexFormat.POSITION_COLOR),
                    shader -> giantSilhouette = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("巨大樹のシルエットのシェーダーを読み込めなかったので、シルエットは描かない", e);
            giantSilhouette = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "gpu_spores"),
                            DefaultVertexFormat.POSITION),
                    shader -> gpuSpores = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("漂う胞子の束のシェーダーを読み込めなかったので、漂う胞子はバニラの粒のままにする", e);
            gpuSpores = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "gpu_motes"),
                            DefaultVertexFormat.POSITION),
                    shader -> gpuMotes = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("光る胞子の束のシェーダーを読み込めなかったので、光る胞子は CPU で描く", e);
            gpuMotes = null;
        }
    }

    /** 光の筋の中の光る胞子の束({@link GpuSpores})。読み込めなかったら null(CPU で描く)。 */
    static ShaderInstance gpuMotes;

    /** 漂う胞子の束({@link GpuSpores})。読み込めなかったら null(バニラの粒に戻す)。 */
    static ShaderInstance gpuSpores;
    private ModShaders() {
    }
}
