package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/** 空と雲のシェーダー(2026-11-08 雲の段階 1。{@code client.shader.ModShaders} とは分けて、雲の分だけここで登録する)。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SkyShaders {

    private static ShaderInstance voxelClouds;
    private static ShaderInstance cirrus;

    /** 巻雲({@link Cirrus}、11-09 段階 2a)。読み込めなかったら null(描かない)。 */
    public static ShaderInstance cirrus() {
        return cirrus;
    }

    /** ボクセルの雲({@link VoxelClouds})。読み込めなかったら null(バニラの雲のまま)。 */
    public static ShaderInstance voxelClouds() {
        return voxelClouds;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "voxel_clouds"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    shader -> voxelClouds = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("ボクセルの雲のシェーダーを読み込めなかったので、雲はバニラのまま", e);
            voxelClouds = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cirrus"), DefaultVertexFormat.POSITION),
                    shader -> cirrus = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("巻雲のシェーダーを読み込めなかったので、巻雲は描かない", e);
            cirrus = null;
        }
    }

    private SkyShaders() {
    }
}
