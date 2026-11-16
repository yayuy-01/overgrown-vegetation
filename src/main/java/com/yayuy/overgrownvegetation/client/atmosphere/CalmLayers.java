package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * <b>凪の日の霞の層</b>(天気「凪」の見た目。2026-11-16。ユーザー決定: 風が止まり、空気が重く、霞が層になる)。
 *
 * <p>風の無い日は空気が混ざらないので、霞が決まった高さに薄い層になって溜まる(朝の谷や湖の上の、水平な霞の帯)。ここでは海面から
 * {@code layerBase} ブロック上に 1 枚目、そこから {@code layerGap} ブロックおきに {@code layers} 枚(3 枚まで)。厚みは {@code layerThick}。
 * 描き終えた画面の奥行きから、画素ごとに「視線が各層を通る長さ」を出して霞を重ねる({@code calm_layers.fsh}。濃霧の霧の海と同じ描き方:
 * AFTER_LEVEL で半分の大きさの器へ描いてから画面へ重ねる)。画面全体の処理なので、地形・Mob・粒に同じに掛かる(地形のシェーダーには入れない)。
 * 層はゆっくりうねり、むらがある。手前({@code layerNear} ブロック以内)は掛けない。色は、胞子の濃い所(森のそば)ほど黄緑、離れると白い灰色。
 * 夜は暗い。濃霧の日・雨の間・地下・水の中・シェーダーパック使用中は掛けない。数は節 {@code calmLook} の layer*(仮決め)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class CalmLayers {

    private CalmLayers() {
    }

    private static TextureTarget target;
    private static boolean broken;
    private static final Matrix4f INV = new Matrix4f();
    private static float lastAmount;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        lastAmount = 0.0F;
        if (level == null || !Atmosphere.enabled(level) || !VisualQuality.fog() || WeatherLook.off("layers") || ShaderPacks.active()
                || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.calmParams();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float amount = WeatherLook.calmAmount() * (1.0F - ForestPresence.underground()) * (1.0F - DenseFog.level()) * (1.0F - level.getRainLevel(partial));
        int layers = Math.round(p.f("layers"));
        if (amount <= 0.004F || layers <= 0) {
            return;
        }
        ShaderInstance shader = WeatherShaders.calmLayers();
        ShaderInstance blit = ModShaders.phaseTintBlit();
        if (shader == null || blit == null) {
            return;
        }
        lastAmount = amount;
        try {
            RenderTarget main = mc.getMainRenderTarget();
            int w = Math.max(1, main.width / 2);
            int h = Math.max(1, main.height / 2);
            if (target == null) {
                target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
            } else if (target.width != w || target.height != h) {
                target.resize(w, h, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
            }
            Vec3 cam = event.getCamera().getPosition();
            INV.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
            // 色: 胞子の濃い所(森の中・胞子の空気)ほど黄緑、離れると白い灰色。夜は暗く。
            float sporey = Mth.clamp(Math.max(ForestPresence.overgrown(), SporeAir.level()), 0.0F, 1.0F);
            float[] forest = p.rgb("layerForestColor");
            float[] open = p.rgb("layerOpenColor");
            float night = SunLight.night(level, partial);
            float bright = Mth.lerp(night, 1.0F, p.f("layerNight"));
            Vec3 sun = SunLight.lightDirection(level, partial);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            target.bindWrite(true);
            shader.setSampler("DepthTex", main.getDepthTextureId());
            shader.safeGetUniform("InvProjView").set(INV);
            shader.safeGetUniform("LayA").set((float) cam.y, level.getSeaLevel() + p.f("layerBase"), p.f("layerGap"), p.f("layerThick"));
            shader.safeGetUniform("LayB").set((float) (cam.x % 4096.0), (float) (cam.z % 4096.0), p.f("layerDensity"), p.f("layerWave"));
            shader.safeGetUniform("LayC").set(p.f("layerNear"), Math.max(p.f("layerNear") + 1.0F, p.f("layerFar")), p.f("layerMax"), amount);
            shader.safeGetUniform("LayColor").set(Mth.lerp(sporey, open[0], forest[0]) * bright, Mth.lerp(sporey, open[1], forest[1]) * bright,
                    Mth.lerp(sporey, open[2], forest[2]) * bright, (float) layers);
            shader.safeGetUniform("LaySun").set((float) sun.x, (float) sun.y, (float) sun.z, Mth.lerp(night, 0.18F, 0.3F));
            quad(shader);
            main.bindWrite(true);
            blit.setSampler("TintTex", target.getColorTextureId());
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            quad(blit);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("凪の霞の層で例外が出たので、このセッションでは掛けない", e);
        }
    }

    private static void quad(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    static String describe() {
        return String.format("霞の層 %.2f", lastAmount);
    }
}
