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
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "morning_mist_cloud"),
                            DefaultVertexFormat.POSITION_COLOR),
                    shader -> morningMistCloud = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("朝霧の雲のシェーダーを読み込めなかったので、霧の雲は描かない(霞は出る)", e);
            morningMistCloud = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_sky_mask"),
                            DefaultVertexFormat.POSITION),
                    shader -> cloudSkyMask = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("遠くの雲の空の型のシェーダーを読み込めなかったので、遠くの雲は空と一緒に描く(前の描き方)", e);
            cloudSkyMask = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cave_mist"),
                            DefaultVertexFormat.POSITION_COLOR),
                    shader -> caveMist = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("地下の霧のシェーダーを読み込めなかったので、地下の霧は描かない", e);
            caveMist = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cave_mist_fall"),
                            DefaultVertexFormat.POSITION_COLOR),
                    shader -> caveMistFall = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("地下の霧の滝のシェーダーを読み込めなかったので、霧の滝は描かない", e);
            caveMistFall = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "voxel_clouds"),
                            // 面の中の位置(UV)を持つ(10-17 深夜、輪郭のやわらかさ)。
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    shader -> voxelClouds = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("ボクセルの雲のシェーダーを読み込めなかったので、バニラの雲のままにする", e);
            voxelClouds = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cirrus"),
                            DefaultVertexFormat.POSITION),
                    shader -> cirrus = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("巻雲のシェーダーを読み込めなかったので、巻雲は描かない", e);
            cirrus = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "virga"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    shader -> virga = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("尾流雲のシェーダーを読み込めなかったので、尾流雲は描かない", e);
            virga = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_wake"),
                            DefaultVertexFormat.POSITION_COLOR),
                    shader -> cloudWake = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("雲を引く演出のシェーダーを読み込めなかったので、雲は引かない", e);
            cloudWake = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_hole"),
                            DefaultVertexFormat.POSITION_COLOR),
                    shader -> cloudHole = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("雲の穴の輪のシェーダーを読み込めなかったので、輪は描かない", e);
            cloudHole = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "sky_dome"),
                            DefaultVertexFormat.POSITION),
                    shader -> skyDome = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("空の光のシェーダーを読み込めなかったので、空の光は描かない", e);
            skyDome = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "far_ground"),
                            DefaultVertexFormat.POSITION),
                    shader -> farGround = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("雲の下の地面のシェーダーを読み込めなかったので、雲の切れ目の下は霧の色のままにする", e);
            farGround = null;
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

    static ShaderInstance voxelClouds;

    /** 巻雲({@link Cirrus})。読み込めなかったら null(巻雲は描かない)。 */
    static ShaderInstance cirrus;

    /** 尾流雲({@link Virga})。読み込めなかったら null(尾流雲は描かない)。 */
    static ShaderInstance virga;

    /** 雲を引く演出({@link CloudWake})。読み込めなかったら null。 */
    static ShaderInstance cloudWake;

    /** 雲の穴の水蒸気の輪({@link CloudHoles})。読み込めなかったら null。 */
    static ShaderInstance cloudHole;

    /** 空の光({@link SkyDome})。読み込めなかったら null。 */
    static ShaderInstance skyDome;

    /** 雲の海の切れ目の下の地面({@link FarGround})。読み込めなかったら null。 */
    static ShaderInstance farGround;

    /** 朝霧の雲の板({@link MorningMistView})。読み込めなかったら null。 */
    static ShaderInstance morningMistCloud;
    /** 遠くの雲を地形の後に描くときの、空の画素だけを通す奥行きの型(2026-10-24、VoxelClouds)。 */
    static ShaderInstance cloudSkyMask;

    /** 地下の霧の板({@link CaveMist})。読み込めなかったら null。 */
    static ShaderInstance caveMist;

    /** 地下の霧の滝({@link CaveMist})。読み込めなかったら null。 */
    static ShaderInstance caveMistFall;

    private ModShaders() {
    }
}
