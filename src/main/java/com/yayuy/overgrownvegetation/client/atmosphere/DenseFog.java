package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * <b>濃霧</b>の見た目(天気の 1 つ。2026-11-16。ユーザー決定: たまに雨上がりや朝方に世界中が濃霧に包まれる。本物の多重散乱は重いので、
 * 前方散乱・光の柔らかいにじみ・高さで薄くなる・木々の間の光の筋を組み合わせて近づける。絵を見て詰める)。
 *
 * <p>理由: 「美しいが怖い」E(霧の中のシルエット)の舞台。見通しが落ち、遠征に危ない朝になる。胞子の濃さは変えない(サーバー側の決まり)。
 *
 * <h2>第 1 版(この版)の作り</h2>
 * サーバーの濃霧の度合い({@link WeatherClient#fog}。立ち上がりはサーバーで掛けてある)を 5 秒おきの知らせの間だけ寄せて持ち、今ある霧の口へ足す:
 * <ul>
 *     <li><b>距離の霞</b>({@link #hazeK}): {@code MistView.renderFog} の「距離で濃くなる霞」(胞子の空気と同じ印)へ足す。地形・Mob・粒・雲が同じ霞になる
 *     (11-01 の決まり)。見通し {@code visibility} ブロックで 95% が霧。洞窟の中は足さない。</li>
 *     <li><b>空</b>({@link #skyFill}): 空の丸天井・雲・空の色を霧の色で満たす({@code MistView.whiteSky} へ足す)。</li>
 *     <li><b>色</b>({@link #tint}): 霧の色は昼は白い灰色、夜は暗い青灰色。<b>前方散乱</b>: 太陽(月)の方を向くほど霧が明るく暖かく光る
 *     (画面全体の色を、見ている向きで変える。画素ごとではない)。</li>
 *     <li><b>高さ</b>({@link #heightFactor}): カメラが霧の層の上({@code topAbove} = 海面からの高さ)へ出ると、{@code topFade} 段かけて晴れる。
 *     画素ごとではないので、高い所から見下ろしても下に霧の海は見えない(第 2 版の候補)。</li>
 * </ul>
 * 数は design の節 {@code denseFog}(すべて仮決め)。確かめは {@code /ov weather fog 1}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class DenseFog {

    private DenseFog() {
    }

    private static float value;
    private static boolean primed;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !WeatherClient.received()) {
            value = 0.0F;
            primed = false;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        float target = Mth.clamp(WeatherClient.fog(), 0.0F, 1.0F);
        if (!primed) {
            // ワールドに入った直後の最初の 1 通は、その場の値へ一気に合わせる。
            value = target;
            primed = true;
            return;
        }
        // 5 秒ごとの知らせの間を寄せるだけ(立ち上がりはサーバーの値に入っている)。時定数 約 3 秒。
        value += (target - value) * 0.017F;
        if (Math.abs(target - value) < 0.002F) {
            value = target;
        }
    }

    /** 濃霧の度合い(0〜1。高さを掛ける前)。 */
    public static float level() {
        return VisualQuality.fog() ? value : 0.0F;
    }

    /** カメラの高さの分(霧の層の中 1、層の上 0)。 */
    static float heightFactor() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return 0.0F;
        }
        AtmosphereEye.DenseFogParams p = DesignData.atmosphere().denseFog();
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        float top = level.getSeaLevel() + p.topAbove();
        return 1.0F - SunLight.smooth(Mth.clamp((float) ((y - top) / Math.max(1.0F, p.topFade())), 0.0F, 1.0F));
    }

    /** カメラの所の濃霧の度合い(0〜1。高さ込み)。 */
    public static float amount() {
        float v = level();
        return v <= 0.001F ? 0.0F : v * heightFactor();
    }

    /** 距離の霞の濃さ(1 ブロックあたり)。見通し visibility ブロックで 95% が霧(exp(−3))。 */
    static float hazeK() {
        float a = amount();
        return a <= 0.001F ? 0.0F : a * 3.0F / Math.max(8.0F, DesignData.atmosphere().denseFog().visibility());
    }

    /** 同じ値(ミキシンから読む用)。 */
    public static float skyFillPublic() {
        return skyFill();
    }

    /** 空の丸天井を霧の色で満たす割合(0〜1)。 */
    static float skyFill() {
        return Mth.clamp(amount() * DesignData.atmosphere().denseFog().skyFill(), 0.0F, 1.0F);
    }

    /**
     * 霧の色を、今の霧の色 {@code rgb}(長さ 3、書き換える)へ混ぜる。{@code look} = 見ている向き。
     * 昼の色 → 夜は明るさを落とす。太陽(夜は月)の方を向くほど、暖かい色を足す(前方散乱)。
     */
    static void tint(ClientLevel level, float partialTick, Vector3f look, float[] rgb) {
        float a = amount();
        if (a <= 0.001F) {
            return;
        }
        AtmosphereEye.DenseFogParams p = DesignData.atmosphere().denseFog();
        float night = SunLight.night(level, partialTick);
        float bright = Mth.lerp(night, 1.0F, p.nightBrightness()) * (1.0F - 0.25F * level.getRainLevel(partialTick));
        float r = p.red() * bright;
        float g = p.green() * bright;
        float b = p.blue() * bright;
        Vec3 sun = SunLight.lightDirection(level, partialTick);
        float facing = (float) Math.max(0.0, look.x() * sun.x + look.y() * sun.y + look.z() * sun.z);
        // 低い太陽ほど暖かく強く(朝の霧)。夜(月)は弱く青白く。
        float low = 1.0F - Mth.clamp((float) sun.y, 0.0F, 1.0F);
        float glow = p.scatter() * (float) Math.pow(facing, p.scatterPower()) * Mth.lerp(night, 0.6F + 0.4F * low, 0.25F);
        r += glow * Mth.lerp(night, 1.0F, 0.55F);
        g += glow * Mth.lerp(night, 0.86F + 0.1F * (1.0F - low), 0.62F);
        b += glow * Mth.lerp(night, 0.62F + 0.3F * (1.0F - low), 0.75F);
        rgb[0] = Mth.lerp(a, rgb[0], Math.min(1.0F, r));
        rgb[1] = Mth.lerp(a, rgb[1], Math.min(1.0F, g));
        rgb[2] = Mth.lerp(a, rgb[2], Math.min(1.0F, b));
    }

    // ------------------------------------------------------------------ 霧の海(第 2 版。11-16 ユーザー「作る」)

    /** 霧の層の上の端の近くで霧が薄くなる深さ(ブロック)・上の端のうねりの高さ(ブロック)。どちらも仮決め。 */
    private static final float SEA_SOFT = 14.0F;
    private static final float SEA_WAVE = 5.0F;
    private static com.mojang.blaze3d.pipeline.TextureTarget seaTarget;
    private static boolean seaBroken;
    private static final org.joml.Matrix4f INV = new org.joml.Matrix4f();

    /** 霧の海の度合い(0〜1): カメラが霧の層の上へ出た分。層の中にいる間は 0(距離の霞が受け持つ)。 */
    static float seaAmount() {
        float v = level();
        return v <= 0.001F ? 0.0F : v * (1.0F - heightFactor());
    }

    /**
     * 霧の海: 高い所(丘・巨大樹の上)から見下ろすと、下に霧が溜まって見える。カメラが霧の層の上にいる間、描き終えた画面の奥行きから、
     * 画素ごとに「視線が霧の層の中を通る長さ」を出して霧を重ねる({@code fog_sea.fsh})。地形・Mob・粒にも同じに掛かる(画面全体の処理なので)。
     * 描くのは世界を描き終えた所(AFTER_LEVEL。手の前。この後では画面の奥行きが消されている)。半分の大きさの器へ描いてから画面へ重ねる
     * (画面を描き先にしたまま、その奥行きは読めない)。シェーダーパック使用中・地下では掛けない。
     */
    @SubscribeEvent
    public static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL || seaBroken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float amount = seaAmount() * (1.0F - ForestPresence.underground());
        if (level == null || amount <= 0.002F || com.yayuy.overgrownvegetation.client.shader.ShaderPacks.active()
                || event.getCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) {
            return;
        }
        net.minecraft.client.renderer.ShaderInstance shader = com.yayuy.overgrownvegetation.client.shader.ModShaders.fogSea();
        net.minecraft.client.renderer.ShaderInstance blit = com.yayuy.overgrownvegetation.client.shader.ModShaders.phaseTintBlit();
        if (shader == null || blit == null) {
            return;
        }
        try {
            AtmosphereEye.DenseFogParams p = DesignData.atmosphere().denseFog();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            com.mojang.blaze3d.pipeline.RenderTarget main = mc.getMainRenderTarget();
            int w = Math.max(1, main.width / 2);
            int h = Math.max(1, main.height / 2);
            if (seaTarget == null) {
                seaTarget = new com.mojang.blaze3d.pipeline.TextureTarget(w, h, false, Minecraft.ON_OSX);
                seaTarget.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            } else if (seaTarget.width != w || seaTarget.height != h) {
                seaTarget.resize(w, h, Minecraft.ON_OSX);
                seaTarget.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
            Vec3 cam = event.getCamera().getPosition();
            INV.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
            float night = SunLight.night(level, partial);
            float bright = Mth.lerp(night, 1.0F, p.nightBrightness()) * (1.0F - 0.25F * level.getRainLevel(partial));
            Vec3 sun = SunLight.lightDirection(level, partial);
            float low = 1.0F - Mth.clamp((float) sun.y, 0.0F, 1.0F);
            float glow = p.scatter() * Mth.lerp(night, 0.6F + 0.4F * low, 0.25F);
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            seaTarget.bindWrite(true);
            shader.setSampler("DepthTex", main.getDepthTextureId());
            shader.safeGetUniform("InvProjView").set(INV);
            shader.safeGetUniform("SeaA").set((float) cam.y, level.getSeaLevel() + p.topAbove(), 3.0F / Math.max(8.0F, p.visibility()), amount);
            shader.safeGetUniform("SeaB").set((float) (cam.x % 4096.0), (float) (cam.z % 4096.0), SEA_SOFT, SEA_WAVE);
            shader.safeGetUniform("SeaColor").set(p.red() * bright, p.green() * bright, p.blue() * bright, glow);
            shader.safeGetUniform("SeaSun").set((float) sun.x, (float) sun.y, (float) sun.z, p.scatterPower());
            shader.safeGetUniform("SeaGlow").set(Mth.lerp(night, 1.0F, 0.55F), Mth.lerp(night, 0.86F + 0.1F * (1.0F - low), 0.62F),
                    Mth.lerp(night, 0.62F + 0.3F * (1.0F - low), 0.75F), 0.0F);
            quad(shader);
            main.bindWrite(true);
            blit.setSampler("TintTex", seaTarget.getColorTextureId());
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ZERO, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
            quad(blit);
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        } catch (RuntimeException e) {
            seaBroken = true;
            OvergrownVegetation.LOGGER.error("濃霧の霧の海で例外が出たので、このセッションでは掛けない", e);
        }
    }

    private static void quad(net.minecraft.client.renderer.ShaderInstance shader) {
        com.mojang.blaze3d.systems.RenderSystem.setShader(() -> shader);
        com.mojang.blaze3d.vertex.BufferBuilder buffer = com.mojang.blaze3d.vertex.Tesselator.getInstance()
                .begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION);
        buffer.addVertex(-1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(-1.0F, 1.0F, 0.0F);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    // ------------------------------------------------------------------ 霧の中の太陽(11-16 ユーザー「ぼかす」)

    /**
     * 霧の中の太陽・月: 四角い絵は霧に沈めて({@code LevelRendererSkyColorMixin} が空の「雨の度合い」へ霧を足す)、代わりに、その方向へ
     * ぼやけた丸い光を足す(加算。光の筋と同じ描き先)。真ん中が明るく、縁へ消える 2 重の丸。昼は暖かい白、夜(月)は青白く弱い。
     * {@link Atmosphere} の加算の光の所から呼ぶ。
     */
    static void renderSun(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        float a = amount();
        ClientLevel level = Minecraft.getInstance().level;
        if (a <= 0.01F || level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = SunLight.lightDirection(level, partial);
        if (sun.y < -0.05) {
            return;
        }
        float night = SunLight.night(level, partial);
        float rise = Mth.clamp((float) (sun.y + 0.05) / 0.12F, 0.0F, 1.0F);
        float strength = a * rise * Mth.lerp(night, 0.55F, 0.16F) * (1.0F - level.getRainLevel(partial));
        if (strength <= 0.004F) {
            return;
        }
        float r = Mth.lerp(night, 1.0F, 0.62F);
        float g = Mth.lerp(night, 0.93F, 0.72F);
        float b = Mth.lerp(night, 0.78F, 0.9F);
        float dist = 300.0F;
        float x = (float) sun.x * dist, y = (float) sun.y * dist, z = (float) sun.z * dist;
        net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = AddLight.buffers();
        com.mojang.blaze3d.vertex.VertexConsumer c = buffers.getBuffer(AddLight.renderType());
        // 視線ではなく太陽の方向に直交する面に描く(画面の端でも丸い)。
        sunDisc(c, x, y, z, sun, dist * 0.26F, r, g, b, strength * 0.55F);
        sunDisc(c, x, y, z, sun, dist * 0.10F, r, g, b, strength);
        buffers.endBatch(AddLight.renderType());
    }

    private static void sunDisc(com.mojang.blaze3d.vertex.VertexConsumer c, float x, float y, float z, Vec3 dir, float radius, float r, float g, float b, float alpha) {
        double ux = -dir.z, uz = dir.x;
        double ul = Math.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-4) {
            ux = 1.0;
            uz = 0.0;
            ul = 1.0;
        }
        ux /= ul;
        uz /= ul;
        // v = dir × u(前は x と z の符号が逆で、太陽が高いと丸がつぶれた)。
        double vx = dir.y * uz, vy = dir.z * ux - dir.x * uz, vz = -dir.y * ux;
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2.0 / n;
            double a1 = (i + 1) * Math.PI * 2.0 / n;
            float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
            c.addVertex((float) (x + ux * c0 + vx * s0), (float) (y + vy * s0), (float) (z + uz * c0 + vz * s0)).setColor(r, g, b, 0.0F);
            c.addVertex((float) (x + ux * c1 + vx * s1), (float) (y + vy * s1), (float) (z + uz * c1 + vz * s1)).setColor(r, g, b, 0.0F);
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format("濃霧 %.2f(届いた値 %.2f・高さ %.2f・霞 %.4f/ブロック・空 %.2f・霧の海 %.2f)", level(), WeatherClient.fog(), heightFactor(), hazeK(), skyFill(), seaAmount()) + " / " + FogHalos.describe();
    }
}
