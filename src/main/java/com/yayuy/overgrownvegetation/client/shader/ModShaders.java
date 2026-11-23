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

    /** 水中の浮遊物の束({@code client/atmosphere/WaterMotes}、2026-11-13)。読み込めなかったら null(バニラの水中の粒のまま)。 */
    private static ShaderInstance waterMotes;

    public static ShaderInstance waterMotes() {
        return waterMotes;
    }

    /** 霧の細部のレイマーチと重ね({@code client/atmosphere/MistMarch}、2026-10-30 夜)。読み込めなかったら null(細部は描かない)。 */
    private static ShaderInstance mistMarch;
    private static ShaderInstance mistComposite;

    public static ShaderInstance mistMarch() {
        return mistMarch;
    }

    public static ShaderInstance mistComposite() {
        return mistComposite;
    }

    /** 遠くの雲を地形の後に描くときの型({@code client/sky/VoxelClouds}、2026-11-23 parked から戻した)。読み込めなかったら null(前の描き方)。 */
    private static ShaderInstance cloudSkyMask;

    public static ShaderInstance cloudSkyMask() {
        return cloudSkyMask;
    }

    /** 霧の向こうの巨大樹のシルエット({@code client/atmosphere/GiantSilhouettes}、2026-10-31)。読み込めなかったら null(描かない)。 */
    private static ShaderInstance giantSilhouette;

    public static ShaderInstance giantSilhouette() {
        return giantSilhouette;
    }

    /** 夕方の光の粒({@code client/atmosphere/DuskMotes}、2026-10-31)。読み込めなかったら null(描かない)。 */
    private static ShaderInstance duskMotes;

    public static ShaderInstance duskMotes() {
        return duskMotes;
    }

    /**
     * 登る蔓({@code client/ClimbingVineRenderer}、2026-11-05 光の点検 6)。バニラの文字のシェーダーに地形と同じ林冠の光({@code ov_light.glsl} の
     * {@code ovCanopyLight})を足したもの。uniform は {@code atmosphere/CanopySunMap#upload} が地形と一緒に流す。読み込めなかったら null(文字のシェーダーに戻す)。
     */
    private static ShaderInstance lianaCanopyLit;

    public static ShaderInstance lianaCanopyLit() {
        return lianaCanopyLit;
    }

    /**
     * 白い雨と水滴({@code client/atmosphere/RainLook}、2026-11-06 雨の見え方)。バニラの粒のシェーダーの、絵の色相を抜いて白 × 時刻の色にしたもの。
     * 読み込めなかったら null(雨と滴はバニラの青のまま)。
     */
    private static ShaderInstance rainWhite;

    public static ShaderInstance rainWhite() {
        return rainWhite;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "rain_white"),
                            DefaultVertexFormat.PARTICLE),
                    shader -> rainWhite = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("白い雨のシェーダーを読み込めなかったので、雨と水滴はバニラの青のまま", e);
            rainWhite = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "liana_canopy_lit"),
                            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                    shader -> lianaCanopyLit = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("登る蔓のシェーダーを読み込めなかったので、蔓は文字のシェーダーで描く(林冠の影は付かない)", e);
            lianaCanopyLit = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "mist_march"),
                            DefaultVertexFormat.POSITION),
                    shader -> mistMarch = shader);
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "mist_composite"),
                            DefaultVertexFormat.POSITION),
                    shader -> mistComposite = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("霧の細部のシェーダーを読み込めなかったので、細部は描かない(土台の霧だけ)", e);
            mistMarch = null;
            mistComposite = null;
        }
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_sky_mask"),
                            DefaultVertexFormat.POSITION),
                    shader -> cloudSkyMask = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("遠くの雲の型のシェーダーを読み込めなかったので、遠くの雲は空と一緒に描く(前の描き方)", e);
            cloudSkyMask = null;
        }
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
        try {
            event.registerShader(new OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "water_motes"),
                            DefaultVertexFormat.POSITION),
                    shader -> waterMotes = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("水中の浮遊物のシェーダーを読み込めなかったので、バニラの水中の粒のままにする", e);
            waterMotes = null;
        }
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
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "dusk_motes"),
                            DefaultVertexFormat.POSITION),
                    shader -> duskMotes = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("夕方の光の粒のシェーダーを読み込めなかったので、光の粒は描かない", e);
            duskMotes = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "eye_adapt"),
                            DefaultVertexFormat.POSITION),
                    shader -> eyeAdapt = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("目の慣れのシェーダーを読み込めなかったので、洞窟の出入りの画面の補正は掛けない", e);
            eyeAdapt = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "bloom_bright"),
                            DefaultVertexFormat.POSITION),
                    shader -> bloomBright = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "bloom_composite"),
                            DefaultVertexFormat.POSITION),
                    shader -> bloomComposite = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("光のにじみのシェーダーを読み込めなかったので、にじみは掛けない", e);
            bloomBright = null;
            bloomComposite = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "ao_compute"),
                            DefaultVertexFormat.POSITION),
                    shader -> aoCompute = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "ao_composite"),
                            DefaultVertexFormat.POSITION),
                    shader -> aoComposite = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("簡易 AO のシェーダーを読み込めなかったので、隙間と根元の陰りは掛けない", e);
            aoCompute = null;
            aoComposite = null;
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "phase_tint"),
                            DefaultVertexFormat.POSITION),
                    shader -> phaseTint = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "phase_tint_blit"),
                            DefaultVertexFormat.POSITION),
                    shader -> phaseTintBlit = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "fog_sea"),
                            DefaultVertexFormat.POSITION),
                    shader -> fogSea = shader);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("フェーズの合図の空のにじみのシェーダーを読み込めなかったので、にじみは掛けない", e);
            phaseTint = null;
            phaseTintBlit = null;
        }
    }

    /** 洞窟の出入りの目の慣れの全画面の補正({@code client/atmosphere/EyeAdaptation}、2026-11-07)。読み込めなかったら null(掛けない)。シェーダーパック使用中は使わない。 */
    private static ShaderInstance eyeAdapt;

    public static ShaderInstance eyeAdapt() {
        return eyeAdapt;
    }

    /** 光るもののにじみ({@code client/atmosphere/GlowBloom}、2026-11-13 仕上げ B)。光る画素を拾う・画面へ重ねるの 2 本。読み込めなかったら null(掛けない)。 */
    private static ShaderInstance bloomBright;
    private static ShaderInstance bloomComposite;

    public static ShaderInstance bloomBright() {
        return bloomBright;
    }

    public static ShaderInstance bloomComposite() {
        return bloomComposite;
    }

    /** 隙間と根元の陰り(簡易 AO。{@code client/atmosphere/SimpleAo}、2026-11-14 仕上げ H)。読み込めなかったら null(掛けない)。 */
    private static ShaderInstance aoCompute;
    private static ShaderInstance aoComposite;

    public static ShaderInstance aoCompute() {
        return aoCompute;
    }

    public static ShaderInstance aoComposite() {
        return aoComposite;
    }

    /** フェーズが上がったときの合図の、空の黄緑のにじみ({@code client/atmosphere/PhaseCue}、2026-11-16)。読み込めなかったら null(掛けない)。 */
    private static ShaderInstance phaseTint;

    private static ShaderInstance phaseTintBlit;
    /** 濃霧を上から見た霧の海({@code client/atmosphere/DenseFog}、2026-11-16)。重ねるのは phaseTintBlit(色とアルファをそのまま重ねるだけの物)。 */
    private static ShaderInstance fogSea;

    public static ShaderInstance fogSea() {
        return fogSea;
    }

    public static ShaderInstance phaseTint() {
        return phaseTint;
    }

    public static ShaderInstance phaseTintBlit() {
        return phaseTintBlit;
    }

    private ModShaders() {
    }
}
