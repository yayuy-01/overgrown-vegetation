package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.shader.PortRender;

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
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * <b>光るもののにじみ</b>(ブルーム。2026-11-13 シェーダーの仕上げ B。ユーザー決定: 既定でオン・低スペック向けに切れる・強さは見本の「中 1.6」)。
 *
 * <p>理由: 夜と洞窟は暗い方針(11-13 ユーザー「夜はこれくらいでよい」)。暗いままでも、光の点(光る胞子・地脈・光る蔓・松明・溶岩)に柔らかい暈があると
 * 「暗いのに美しい」。今まであったのは洞窟を出るときの目の慣れのまぶしさ({@link EyeAdaptation} の glareBloom)だけで、ふだんの光源には暈が無かった。
 *
 * <h2>やり方</h2>
 * 地形・Mob・粒・手を描いた後、GUI の前({@code GameRendererEyeAdaptMixin}。目の慣れより先)に、
 * <ol>
 *     <li>画面を 4 分の 1 の大きさへ写して縮めた段を作る(まわりの明るさを読むため)。</li>
 *     <li>4 分の 1 の大きさの器へ「光る画素」だけを拾う({@code bloom_bright.fsh})。画面には「何が光を出しているか」の記録が無いので、
 *     いちばん明るい色の成分がしきい値より上で、まわり(画面の幅の 1/20 ほどでぼかした明るさ)よりずっと明るい画素を光る物とみなす。
 *     松明の炎・ランタン・光る胞子は暗がりの中の小さな明るい点で、日の当たった壁・照らされた床は違う。空の画素(奥行きの無い所)はもっと厳しく
 *     (ぽつんとした点 = 空を背にした光る胞子・月は通り、夕焼けの帯は通らない)。</li>
 *     <li>拾った絵の縮めた段 3 つ(狭い・中・広い)を足して、画面へスクリーン合成で重ねる({@code bloom_composite.fsh})。</li>
 * </ol>
 * カメラのまわりが明るい間は掛けない: 暗さの門 = (カメラの所の空の光/15)² × 昼の明るさ が {@code darkFrom} より下で全部・{@code darkTo} より上で 0
 * (昼の外は 0、夜・洞窟・濃い林冠の下は 1。ブロックの光は数えない = 松明のそばでもにじむ)。門が 0 のフレームは何も描かない(重さ 0)。
 *
 * <p>掛けない場合: 設定で切った(見え方の設定「光のにじみ」・design の bloom.enabled)・シェーダーパック使用中。数は design の節 {@code bloom}(強さのほかは仮決め)。
 * 見本: {@code tools/bloom/BloomMock.java}(同じ決まりを写真に当てる)。
 */
public final class GlowBloom {

    private GlowBloom() {
    }

    /** 確かめ用: 真なら切る。 */
    static volatile boolean devOff;

    private static boolean broken;
    private static TextureTarget small;
    private static TextureTarget glow;
    private static boolean smallMips;
    private static boolean glowMips;
    /** 暗さの門(0〜1。時間でならす)。 */
    private static float gate;
    private static long lastNanos;
    private static String devLast = "";

    private static boolean on() {
        return !devOff && DesignData.atmosphere().bloom().enabled() && VisualQuality.glowBloom() && !ShaderPacks.active();
    }

    /** カメラの所の暗さの門の目標(0〜1)。 */
    private static float gateTarget(ClientLevel level, Minecraft mc, AtmosphereEye.BloomParams p) {
        BlockPos at = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition());
        float sky = level.dimensionType().hasSkyLight() ? level.getBrightness(LightLayer.SKY, at) / 15.0F : 0.0F;
        // 昼の明るさ: getSkyDarken は夜 0.2 〜 昼 1.0(雨で下がる)。
        float day = Mth.clamp((level.getSkyDarken(1.0F) - 0.2F) / 0.8F, 0.0F, 1.0F);
        float lum = sky * sky * day;
        float dark = 1.0F - SunLight.smooth(Mth.clamp((lum - p.darkFrom()) / Math.max(0.001F, p.darkTo() - p.darkFrom()), 0.0F, 1.0F));
        // 濃霧(天気。11-16)の中では、昼でも明かりと霧越しの太陽に暈を付ける(霧の中の光の柔らかいにじみ。0.7 は仮決め)。
        return Math.max(dark, 0.7F * DenseFog.amount());
    }

    /** GameRenderer の renderLevel の直後({@code GameRendererEyeAdaptMixin} から。目の慣れより先)。 */
    public static void renderPost() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || broken || !on()) {
            gate = 0.0F;
            lastNanos = 0L;
            return;
        }
        AtmosphereEye.BloomParams p = DesignData.atmosphere().bloom();
        boolean first = lastNanos == 0L;
        long now = System.nanoTime();
        float dt = first ? 1.0F : Mth.clamp((now - lastNanos) / 1.0E9F, 0.0F, 0.25F);
        lastNanos = now;
        gate = ForestPresence.approach(gate, gateTarget(level, mc, p), dt, first ? 0.0F : 0.6F);
        if (gate <= 0.004F || p.strength() <= 0.0F) {
            devLast = String.format("光のにじみ 門 %.2f(描かない)", gate);
            return;
        }
        ShaderInstance bright = ModShaders.bloomBright();
        ShaderInstance composite = ModShaders.bloomComposite();
        if (bright == null || composite == null) {
            return;
        }
        try {
            draw(mc, bright, composite, p);
            devLast = String.format("光のにじみ 門 %.2f 強さ %.2f", gate, p.strength());
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("光のにじみで例外が出たので、このセッションでは掛けない", e);
        }
    }

    private static TextureTarget target(TextureTarget t, int w, int h) {
        if (t == null) {
            return new TextureTarget(w, h, false, Minecraft.ON_OSX);
        }
        if (t.width != w || t.height != h) {
            t.resize(w, h, Minecraft.ON_OSX);
        }
        return t;
    }

    /** 器の色の絵に縮めた段を作る(初めての大きさなら、段の数とぼかして読む設定も入れる)。 */
    private static void mipmaps(TextureTarget t, boolean ready) {
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(t.getColorTextureId());
        if (!ready) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 5);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(previous);
    }

    private static void quad(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
        BufferBuilder buffer = PortRender.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.vertex(-1.0F, -1.0F, 0.0F).endVertex();
        buffer.vertex(1.0F, -1.0F, 0.0F).endVertex();
        buffer.vertex(1.0F, 1.0F, 0.0F).endVertex();
        buffer.vertex(-1.0F, 1.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void draw(Minecraft mc, ShaderInstance bright, ShaderInstance composite, AtmosphereEye.BloomParams p) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = Math.max(1, main.width / 4);
        int h = Math.max(1, main.height / 4);
        boolean resized = small == null || small.width != w || small.height != h;
        small = target(small, w, h);
        glow = target(glow, w, h);
        if (resized) {
            smallMips = false;
            glowMips = false;
        }
        // 1: 画面を 4 分の 1 へ写して縮めた段を作る(まわりの明るさ)。
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, small.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        mipmaps(small, smallMips);
        smallMips = true;

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        // 2: 光る画素を拾う。
        glow.bindWrite(true);
        bright.setSampler("SceneTex", main.getColorTextureId());
        bright.setSampler("SmallTex", small.getColorTextureId());
        bright.setSampler("DepthTex", main.getDepthTextureId());
        bright.safeGetUniform("BloomA").set(p.threshold(), p.knee(), p.contrastFrom(), p.contrastTo());
        bright.safeGetUniform("BloomB").set(p.skyContrastFrom(), p.skyContrastTo(), gate, 0.0F);
        quad(bright);
        mipmaps(glow, glowMips);
        glowMips = true;
        // 3: 画面へスクリーン合成で重ねる。
        main.bindWrite(true);
        composite.setSampler("GlowTex", glow.getColorTextureId());
        composite.safeGetUniform("BloomC").set(p.strength(), 0.0F, 0.0F, 0.0F);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        quad(composite);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 確かめ用の一行。 */
    public static String describe() {
        return devLast;
    }
}
