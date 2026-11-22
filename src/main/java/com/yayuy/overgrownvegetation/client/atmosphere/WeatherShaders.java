package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/** 天気の見た目の残り(2026-11-16)のシェーダー: 凪の霞の層 {@code calm_layers}({@link CalmLayers})・アーク雲 {@code arcus}({@link ArcusCloud})。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WeatherShaders {

    private WeatherShaders() {
    }

    private static ShaderInstance calmLayers;
    private static ShaderInstance arcus;

    /** 凪の霞の層。読み込めなかったら null(掛けない)。 */
    static ShaderInstance calmLayers() {
        return calmLayers;
    }

    /** アーク雲。読み込めなかったら null(描かない)。 */
    static ShaderInstance arcus() {
        return arcus;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "calm_layers"), DefaultVertexFormat.POSITION),
                    shader -> calmLayers = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("凪の霞の層のシェーダーを読み込めなかったので、層は掛けない", e);
            calmLayers = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "arcus"), DefaultVertexFormat.POSITION_COLOR),
                    shader -> arcus = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("アーク雲のシェーダーを読み込めなかったので、アーク雲は描かない", e);
            arcus = null;
        }
    }
}
