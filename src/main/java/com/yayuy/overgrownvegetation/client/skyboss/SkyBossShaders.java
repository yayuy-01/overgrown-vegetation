package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * 空の巨大生物を描くシェーダー {@code shaders/core/sky_boss}(2026-10-18)。バニラの {@code rendertype_entity_cutout_no_cull} と同じ入力で、
 * <ul>
 *   <li><b>遠い所だけカメラへ寄せる</b>: 体(全長 約 2100 ブロック)が奥のクリップ面(描画距離 × 64)より長いので、{@code SqueezeStart} より遠い頂点を、
 *       その頂点の見える向きのままカメラへ寄せる(距離を直線で縮める)。向きが変わらないので<b>絵はそのまま</b>で、遠い所の奥行きだけが縮む。
 *       近い所は本当の奥行きなので、近くの雲・地形と正しく前後する(前は体ごと寄せて縮めていたので、雲の前に描かれて小さく見えた)。</li>
 *   <li>霧の代わりに、本当の距離で霞(霧の色)を混ぜる。</li>
 * </ul>
 * 登録は {@code ModShaders} と別にした(あちらは雲のセッションがよく触るので)。読み込めなければ null で、その時はふつうの描き方に戻る。
 *
 * <p>10-19 から光る層 {@code shaders/core/sky_boss_glow}: 同じ寄せ方で体をもう一度、電紋の型({@code textures/entity/sky_glaucus_flow.png})で描き、
 * 光る所だけ足す(加算・奥行きは書かず、先に描いた体と同じ深さで重なる)。光り方の値は {@link SkyBossGlow}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SkyBossShaders {

    static ShaderInstance shader;
    private static RenderType renderType;
    private static ResourceLocation renderTypeTexture;
    /** 光る層(10-19)。 */
    static ShaderInstance glowShader;
    private static RenderType glowType;
    static final ResourceLocation FLOW_TEXTURE = OvergrownVegetation.id("textures/entity/sky_glaucus_flow.png");

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new com.yayuy.overgrownvegetation.client.OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "sky_boss"),
                            DefaultVertexFormat.NEW_ENTITY),
                    s -> shader = s);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("空の巨大生物のシェーダーを読み込めなかったので、ふつうの描き方にする(遠い所が切れることがある)", e);
            shader = null;
        }
        try {
            event.registerShader(new com.yayuy.overgrownvegetation.client.OwnShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "sky_boss_glow"),
                            DefaultVertexFormat.NEW_ENTITY),
                    s -> glowShader = s);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("空の巨大生物の光る層のシェーダーを読み込めなかったので、電紋は光らない", e);
            glowShader = null;
        }
    }

    /** いま体を描いている型(まだ作っていなければ null)。光る層より先に描き終えるため。 */
    static RenderType mainType() {
        return renderType;
    }

    /**
     * Iris のシェーダーパック使用中に、自前のシェーダーで描けない(設定はオンだが Iris の書き込みの止めを外せない)とき true。
     * そのときはバニラのエンティティの描き方(体ごと寄せて縮める・GeckoLib の既定の型)で描き、光る層は静止の発光の絵({@link #staticGlowType})にする
     * (2026-10-23 ユーザー決定: 方式は自前のシェーダーのまま、効かないときだけこの A 案)。
     */
    static boolean vanillaFallback() {
        return com.yayuy.overgrownvegetation.client.ShaderPacks.ownEffects()
                && com.yayuy.overgrownvegetation.client.OwnShaderInstance.unmaskBroken();
    }

    private static final ResourceLocation STATIC_GLOW_TEXTURE = OvergrownVegetation.id("dynamic/sky_glaucus_glow_static");
    private static RenderType staticGlowType;
    private static boolean staticGlowFailed;

    /**
     * バニラの発光(eyes = 加算・色だけ書く。Iris のパックでは entities_eyes に替わる)で、電紋の型から作った静止の絵を描く型。
     * 光の先の流れ・脈は出せないので、光る間は線の全体と避雷針・扇の先・蕾の芯が柔らかい色で光る。作れなければ null。
     */
    private static RenderType staticGlowType() {
        if (staticGlowType == null && !staticGlowFailed) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                com.mojang.blaze3d.platform.NativeImage flow;
                try (java.io.InputStream in = mc.getResourceManager().open(FLOW_TEXTURE)) {
                    flow = com.mojang.blaze3d.platform.NativeImage.read(in);
                }
                int soft = SkyBossGlow.SOFT;
                int sr = (soft >> 16) & 0xFF;
                int sg = (soft >> 8) & 0xFF;
                int sb = soft & 0xFF;
                for (int y = 0; y < flow.getHeight(); y++) {
                    for (int x = 0; x < flow.getWidth(); x++) {
                        int abgr = flow.getPixelRGBA(x, y);
                        int a = (abgr >>> 24) & 0xFF;
                        int g = (abgr >> 8) & 0xFF;
                        float lit = 0.0F;
                        if (a > 191) {
                            lit = 0.55F + 0.45F * g / 255.0F; // 線(光の型の太さ)
                        } else if (a > 25 && ((abgr >> 16) & 0xFF) > 51) {
                            lit = 0.9F; // 避雷針・扇の先・蕾の芯
                        }
                        // 加算なので明るさは色に焼く(透明度は使われない)
                        flow.setPixelRGBA(x, y, lit <= 0.0F ? 0 : 0xFF000000
                                | (Math.round(sb * lit) << 16) | (Math.round(sg * lit) << 8) | Math.round(sr * lit));
                    }
                }
                mc.getTextureManager().register(STATIC_GLOW_TEXTURE, new net.minecraft.client.renderer.texture.DynamicTexture(flow));
                staticGlowType = RenderType.eyes(STATIC_GLOW_TEXTURE);
            } catch (IOException | RuntimeException e) {
                staticGlowFailed = true;
                OvergrownVegetation.LOGGER.error("空の巨大生物の静止の発光の絵を作れなかったので、バニラの描き方では光らせない", e);
            }
        }
        return staticGlowType;
    }

    /** 光る層の型(シェーダーが無ければ null)。体と同じ頂点の形(GeckoLib が書く)。加算・色だけ書く・裏も描く。 */
    static RenderType glowType() {
        if (vanillaFallback()) {
            return staticGlowType();
        }
        if (glowShader == null || shader == null) {
            return null;
        }
        if (glowType == null) {
            glowType = RenderType.create(OvergrownVegetation.MODID + "_sky_boss_glow", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS, 262144, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> glowShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(FLOW_TEXTURE, false, false))
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return glowType;
    }

    /** 光る層を描く前に、寄せ方・霞と光り方を渡す。 */
    static void setGlowUniforms(float squeezeStart, float squeezeLimit, float squeezeMax, float hazeFull, float hazeMax, float time) {
        if (glowShader == null) {
            return;
        }
        glowShader.safeGetUniform("SqueezeStart").set(squeezeStart);
        glowShader.safeGetUniform("SqueezeLimit").set(squeezeLimit);
        glowShader.safeGetUniform("SqueezeMax").set(squeezeMax);
        glowShader.safeGetUniform("HazeFull").set(hazeFull);
        glowShader.safeGetUniform("HazeMax").set(hazeMax);
        glowShader.safeGetUniform("GlowFront").set(SkyBossGlow.front);
        glowShader.safeGetUniform("GlowLevel").set(SkyBossGlow.level);
        glowShader.safeGetUniform("GlowPulse").set(SkyBossGlow.pulse);
        glowShader.safeGetUniform("RodGlow").set(SkyBossGlow.rod);
        glowShader.safeGetUniform("TipGlow").set(SkyBossGlow.tip);
        glowShader.safeGetUniform("BudGlow").set(SkyBossGlow.bud);
        glowShader.safeGetUniform("GlowFlicker").set(SkyBossGlow.flicker);
        glowShader.safeGetUniform("GlowTime").set(time);
        int g = SkyBossGlow.GLOW;
        int s = SkyBossGlow.SOFT;
        glowShader.safeGetUniform("GlowColor").set(((g >> 16) & 0xFF) / 255.0F, ((g >> 8) & 0xFF) / 255.0F, (g & 0xFF) / 255.0F);
        glowShader.safeGetUniform("SoftColor").set(((s >> 16) & 0xFF) / 255.0F, ((s >> 8) & 0xFF) / 255.0F, (s & 0xFF) / 255.0F);
    }

    /** このテクスチャで描く型(シェーダーが無ければ null)。 */
    static RenderType renderType(ResourceLocation texture) {
        if (shader == null || vanillaFallback()) {
            return null;
        }
        if (renderType == null || !texture.equals(renderTypeTexture)) {
            renderTypeTexture = texture;
            renderType = RenderType.create(OvergrownVegetation.MODID + "_sky_boss", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS, 262144, true, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> shader))
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setOverlayState(RenderStateShard.OVERLAY)
                            .createCompositeState(true));
        }
        return renderType;
    }

    /** 1 回描く前に、寄せ方と霞を渡す(すべて本当の距離、ブロック)。 */
    static void setUniforms(float squeezeStart, float squeezeLimit, float squeezeMax, float hazeFull, float hazeMax, float[] hazeColor) {
        if (shader == null) {
            return;
        }
        shader.safeGetUniform("SqueezeStart").set(squeezeStart);
        shader.safeGetUniform("SqueezeLimit").set(squeezeLimit);
        shader.safeGetUniform("SqueezeMax").set(squeezeMax);
        shader.safeGetUniform("HazeFull").set(hazeFull);
        shader.safeGetUniform("HazeMax").set(hazeMax);
        shader.safeGetUniform("HazeColor").set(hazeColor[0], hazeColor[1], hazeColor[2]);
    }

    private SkyBossShaders() {
    }
}
