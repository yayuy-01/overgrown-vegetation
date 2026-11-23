package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.DenseFog;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WorldEventLooks;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.joml.Matrix4f;

/**
 * <b>日食</b>(2026-11-16。ユーザー決定: <b>夜と同じ真っ暗</b>・星が出る・地平線だけ夕焼け色。<b>敵対 Mob の動きは変えない</b> = 見た目だけ)。
 * 理由(設定): 月が太陽を隠す、ただの空の出来事(星系で形が変わる匂わせ)。
 *
 * <h2>見た目だけ、の守り方</h2>
 * 暗くするのはクライアントの描画が読む値だけ: {@code ClientLevel} の {@code getSkyDarken(float)}(ライトマップ)・{@code getSkyColor}・
 * {@code getCloudColor}・{@code getStarBrightness}({@code mixin/client/ClientLevelEclipseMixin})と、霧の色、この MOD の太陽光の強さ
 * ({@code SunLight.daylight})。サーバーの空の暗さ(湧き・Mob の昼夜の判定が読む {@code Level.getSkyDarken()})には触らない。
 *
 * <h2>進み</h2>
 * 月(四角。太陽の絵の芯の 1.67 倍)が、太陽の上を同じ速さでまっすぐ横切る。重なりが芯の幅を超えた所から真っ暗 —— 1.67 倍だと全体の 4 分の 1
 * (約 6 分のうち約 1.5 分)が真っ暗になる。暗さ {@link #dark} は重なりの 3 乗(半分隠れてもまだ明るく、最後に急に暗くなる。本物の日食と同じ)。
 *
 * <h2>描く物</h2>
 * 空の段(太陽の後、雲の前)で: 太陽を隠す月(その時の空の色の四角)、真っ暗な間のコロナ(月のまわりの白い光)、地平線の夕焼け色の帯
 * ({@code shaders/core/ov_eclipse}。ぐるり一周、太陽の側が少し明るい)。値は {@code worldEvents.eclipse}(仮決め)。確かめは {@code /ov event start eclipse <進み>}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class Eclipse {

    private Eclipse() {
    }

    /** 月の大きさ ÷ 太陽の芯の大きさ。(M − S) ÷ (M + S) = 真っ暗な時間の割合 = 0.25。 */
    private static final float MOON_RATIO = 5.0F / 3.0F;
    private static boolean broken;

    /** 月の真ん中のずれ(太陽の芯の幅を 1 として。−(M+S)/2 = 触れ始め、0 = 真ん中、+ で通り過ぎる)。起きていなければ NaN。 */
    private static float offset(float partialTick) {
        if (!WorldEventClient.is(WorldEventKind.ECLIPSE)) {
            return Float.NaN;
        }
        float len = WorldEventClient.lengthTicks();
        if (len <= 0.0F) {
            return Float.NaN;
        }
        float u = WorldEventClient.elapsedTicks(partialTick) / len;
        return (u * 2.0F - 1.0F) * (MOON_RATIO + 1.0F) * 0.5F;
    }

    /** 太陽の隠れ具合(0〜1。1 = すっかり隠れた)。 */
    public static float covered(float partialTick) {
        float o = offset(partialTick);
        if (Float.isNaN(o)) {
            return 0.0F;
        }
        return Mth.clamp((MOON_RATIO + 1.0F) * 0.5F - Math.abs(o), 0.0F, 1.0F);
    }

    /** 暗さ(0 = いつもの昼、1 = 夜と同じ)。描画のあちこちが毎フレーム何度も読む(軽い式だけ)。 */
    public static float dark(float partialTick) {
        float c = covered(partialTick);
        if (c <= 0.0F) {
            return 0.0F;
        }
        return c * c * c * Mth.clamp(DesignData.sky().events.eclipse.darkness, 0.0F, 1.0F);
    }

    /** 今のフレームの暗さ(部分 tick を持たない読み手用)。 */
    public static float dark() {
        return dark(Minecraft.getInstance().getFrameTime());
    }

    /** 霧の色: いちばん最後に、夜の色(真っ暗な間は地平の夕焼けを少し映した暗い色)へ寄せる。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float d = dark((float) event.getPartialTick());
        if (d <= 0.001F) {
            return;
        }
        float[] f = DesignData.sky().events.eclipse.fogColor;
        float under = ForestPresence.underground();
        float k = d * (1.0F - under);
        event.setRed(Mth.lerp(k, event.getRed(), Math.min(event.getRed(), WorldEventSky.rgb(f, 0))));
        event.setGreen(Mth.lerp(k, event.getGreen(), Math.min(event.getGreen(), WorldEventSky.rgb(f, 1))));
        event.setBlue(Mth.lerp(k, event.getBlue(), Math.min(event.getBlue(), WorldEventSky.rgb(f, 2))));
    }

    /** 空の段で、遠くの雲より先に(太陽はもう描かれている)。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken) {
            return;
        }
        float partial = event.getPartialTick();
        float o = offset(partial);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (Float.isNaN(o) || level == null || ShaderPacks.active() || event.getCamera().getFluidInCamera() != FogType.NONE
                || !level.dimensionType().hasSkyLight()) {
            return;
        }
        try {
            draw(event, level, partial, o);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("日食で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level, float partial, float o) {
        WorldEventLooks.Eclipse p = DesignData.sky().events.eclipse;
        float covered = covered(partial);
        float d = dark(partial);
        Vec3 sun = SunLight.sunDirection(level, partial);
        // 雨・濃霧の日は太陽も月も見えない(暗くなるだけ)。
        float seen = (1.0F - level.getRainLevel(partial)) * (1.0F - DenseFog.skyFillPublic());

        // 1: 地平線の夕焼け色(足し算)。
        ShaderInstance shader = WorldEventShaders.eclipse();
        float glow = d * p.horizonStrength * (1.0F - 0.7F * level.getRainLevel(partial)) * (1.0F - DenseFog.skyFillPublic()) * (1.0F - ForestPresence.underground());
        if (shader != null && glow > 0.003F) {
            shader.safeGetUniform("InvViewProj").set(new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose()).invert());
            shader.safeGetUniform("Glow").set(WorldEventSky.rgb(p.horizonColor, 0), WorldEventSky.rgb(p.horizonColor, 1), WorldEventSky.rgb(p.horizonColor, 2), glow);
            shader.safeGetUniform("Band").set(Math.max(0.01F, p.horizonHeight), p.horizonToward, 0.0F, 0.0F);
            shader.safeGetUniform("SunFlat").set((float) sun.x, (float) sun.z, 0.0F, 0.0F);
            WorldEventSky.fullscreenAdd(shader);
        }
        if (seen <= 0.02F || sun.y < -0.05) {
            return;
        }

        // 2: 月とコロナ。太陽の絵と同じ向きの四角(辺は世界の Z 軸と、それに直交する向き)。距離 100 の所に描く。
        final float dist = 100.0F;
        float sx = (float) sun.x, sy = (float) sun.y, sz = (float) sun.z;
        // e1 = 世界の Z 軸、e2 = e1 × 太陽の向き(太陽が動く面の中)。
        float e2x = -sy, e2y = sx, e2z = 0.0F;
        float size = Math.max(4.0F, p.sunSize);
        float moon = size * MOON_RATIO * 0.5F;
        // 月は斜めに横切る(e1 へ 0.94、e2 へ 0.34)。
        float mx = o * size * 0.94F, my = o * size * 0.34F;
        float cx = sx * dist + e2x * my, cy = sy * dist + e2y * my, cz = sz * dist + mx;

        WorldEventSky.pushSkyView(event);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();

        // コロナ: すっかり隠れる少し前から。月の後ろに、やわらかい白い光(16 方向。長さを不ぞろいにして筋に見せる)。
        float corona = Mth.clamp((covered - 0.93F) / 0.07F, 0.0F, 1.0F) * p.corona * seen;
        if (corona > 0.01F) {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            // 太陽の真ん中(月ではなく)を中心に。
            float ox = sx * dist, oy = sy * dist, oz = sz * dist;
            int n = 24;
            for (int layer = 0; layer < 2; layer++) {
                float base = layer == 0 ? size * 2.6F : size * 1.25F;
                float alpha = (layer == 0 ? 0.28F : 0.75F) * corona;
                for (int i = 0; i < n; i++) {
                    double a0 = i * Math.PI * 2.0 / n;
                    double a1 = (i + 1) * Math.PI * 2.0 / n;
                    float r0 = base * (0.75F + 0.5F * spike(i, layer));
                    float r1 = base * (0.75F + 0.5F * spike((i + 1) % n, layer));
                    float c0 = (float) Math.cos(a0) * r0, s0 = (float) Math.sin(a0) * r0;
                    float c1 = (float) Math.cos(a1) * r1, s1 = (float) Math.sin(a1) * r1;
                    b.vertex(ox, oy, oz).color(1.0F, 0.97F, 0.90F, alpha).endVertex();
                    b.vertex(ox + e2x * s0, oy + e2y * s0, oz + c0).color(1.0F, 0.97F, 0.90F, 0.0F).endVertex();
                    b.vertex(ox + e2x * s1, oy + e2y * s1, oz + c1).color(1.0F, 0.97F, 0.90F, 0.0F).endVertex();
                    b.vertex(ox, oy, oz).color(1.0F, 0.97F, 0.90F, alpha).endVertex();
                }
            }
            BufferUploader.drawWithShader(b.end());
        }

        // 月: その時の空の色の四角で、太陽の絵を覆う(新月は空と同じ色で、太陽に掛かった所だけ見える)。
        Vec3 sky = level.getSkyColor(event.getCamera().getPosition(), partial);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float r = (float) sky.x, g = (float) sky.y, bl = (float) sky.z;
        b.vertex(cx - e2x * moon, cy - e2y * moon, cz - moon).color(r, g, bl, seen).endVertex();
        b.vertex(cx + e2x * moon, cy + e2y * moon, cz - moon).color(r, g, bl, seen).endVertex();
        b.vertex(cx + e2x * moon, cy + e2y * moon, cz + moon).color(r, g, bl, seen).endVertex();
        b.vertex(cx - e2x * moon, cy - e2y * moon, cz + moon).color(r, g, bl, seen).endVertex();
        BufferUploader.drawWithShader(b.end());

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        WorldEventSky.popSkyView();
    }

    private static float spike(int i, int layer) {
        int n = (i * 7919 + layer * 104729) ^ 0x5bd1e995;
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format("日食: 隠れ具合 %.2f・暗さ %.2f%s", covered(0.0F), dark(0.0F), broken ? "・例外で止めた" : "");
    }
}
