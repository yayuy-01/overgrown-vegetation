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
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * <b>洞窟の出入りの目の慣れ</b>(明順応・暗順応、2026-11-07。ユーザーの考え: 洞窟から出た瞬間の視覚効果で、外と洞窟のつなぎ目を隠す。数は設計値 {@code eyeAdapt}、すべて仮決め)。
 *
 * <ul>
 *     <li><b>今の明るさ</b>(EV = 2 を底とする対数)はカメラの所の空の光・昼の明るさ・ブロックの光から出す(画面を読み戻さない。毎 tick)。</li>
 *     <li><b>目が慣れている明るさ</b> {@link #adapted} は今の明るさを遅れて追う(明るくなる向き lightSeconds・暗くなる向き darkSeconds)。
 *     差が大きいほど強く出るので、暗い所に長くいたほど出るときにまぶしく、口を少し出入りしただけならほとんど出ない。</li>
 *     <li><b>出るとき(まぶしさ {@link #glare})</b>: 地形・Mob・手まで描いた後の画面へ、全画面の露出の補正を掛ける({@code eye_adapt.fsh}: 明るさの上乗せ・明るい所のにじみ・
 *     白へ寄せてコントラストを下げる・彩度を抜く)。GUI は掛けない({@code GameRendererEyeAdaptMixin} が renderLevel の直後に呼ぶ)。</li>
 *     <li><b>入るとき(暗さ {@link #dark})</b>: 地下モードの床の持ち上げ(ambient = 岩の輪郭)を絞り({@link #ambientScale})、画面の暗い所をさらに沈める。点光源と光る画素は残る。</li>
 *     <li><b>つなぎ目を隠す</b>: まぶしさ・暗さが hideFrom を超えている間、地下モードの寄せの時定数を hideSeconds まで縮める({@link #transitionSeconds})。
 *     白が引いたときには霧とシェーダーがもう地上の見た目になっている。</li>
 *     <li>Iris のシェーダーパック使用中は掛けない(パックが自分の露出の補正を持つ・この MOD の地下モードもパックでは描かない)。空の光の無い次元(ネザー)も掛けない。</li>
 * </ul>
 */
public final class EyeAdaptation {

    /** 確かめ用: 真なら切る(devbridge の {@code wind cavedev adapt off})。 */
    static volatile boolean devOff;

    /** 今の明るさ(EV)と、目が慣れている明るさ(EV)。NaN = まだ測っていない。 */
    private static float scene = Float.NaN;
    private static float adapted = Float.NaN;
    /** まぶしさ・暗さ(0〜1)。 */
    private static float glare;
    private static float dark;
    private static long lastNanos;
    private static boolean broken;
    private static TextureTarget copy;
    private static int copyMipLevels;

    private EyeAdaptation() {
    }

    private static boolean on() {
        return !devOff && DesignData.atmosphere().eyeAdapt().enabled() && VisualQuality.caveLight() && !ShaderPacks.active();
    }

    /** 慣れを今の明るさにそろえる(ワールドに入ったとき・確かめ用)。 */
    static void reset() {
        adapted = scene;
        glare = 0.0F;
        dark = 0.0F;
    }

    static void clear() {
        scene = Float.NaN;
        adapted = Float.NaN;
        glare = 0.0F;
        dark = 0.0F;
        lastNanos = 0L;
    }

    /** 毎 tick({@link Atmosphere} から): カメラの所の今の明るさ。 */
    static void tick(ClientLevel level, Vec3 eye) {
        if (!on() || !level.dimensionType().hasSkyLight()) {
            clear();
            return;
        }
        AtmosphereEye.EyeAdaptParams p = DesignData.atmosphere().eyeAdapt();
        BlockPos at = BlockPos.containing(eye);
        float sky = level.getBrightness(LightLayer.SKY, at) / 15.0F;
        float block = level.getBrightness(LightLayer.BLOCK, at) / 15.0F;
        // 昼の明るさ: getSkyDarken は夜 0.2 〜 昼 1.0(雨で下がる)。夜は nightSky まで落とす(夜に洞窟を出てもほとんどまぶしくない)。
        float day = Mth.clamp((level.getSkyDarken(1.0F) - 0.2F) / 0.8F, 0.0F, 1.0F);
        float daylight = Mth.lerp(day, p.nightSky(), 1.0F);
        float lum = Math.max(Math.max(sky * sky * daylight, block * block * p.blockWeight()), p.floor());
        var player = Minecraft.getInstance().player;
        if (player != null && player.hasEffect(MobEffects.NIGHT_VISION)) {
            // 暗視の間は暗い所も明るく見えている(慣れの差を作らない)
            lum = Math.max(lum, 0.5F);
        }
        float ev = (float) (Math.log(lum) / Math.log(2.0));
        if (Float.isNaN(scene) || Float.isNaN(adapted)) {
            adapted = ev;
        }
        scene = ev;
    }

    /** 毎フレーム(描画の前、{@link #renderPost} から): 慣れを今の明るさへ寄せ、まぶしさ・暗さを出す。 */
    private static void update() {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Mth.clamp((now - lastNanos) / 1.0E9F, 0.0F, 0.25F);
        lastNanos = now;
        if (Float.isNaN(scene) || Float.isNaN(adapted)) {
            glare = 0.0F;
            dark = 0.0F;
            return;
        }
        AtmosphereEye.EyeAdaptParams p = DesignData.atmosphere().eyeAdapt();
        if (!Minecraft.getInstance().isPaused()) {
            float tau = scene > adapted ? p.lightSeconds() : p.darkSeconds();
            adapted += (scene - adapted) * (1.0F - (float) Math.exp(-dt / tau));
        }
        float diff = scene - adapted;
        glare = SunLight.smooth(Mth.clamp((diff - p.deadband()) / p.range(), 0.0F, 1.0F));
        dark = SunLight.smooth(Mth.clamp((-diff - p.deadband()) / p.range(), 0.0F, 1.0F));
    }

    /** まぶしさ(0〜1)。 */
    public static float glare() {
        return glare;
    }

    /** 暗さ(0〜1)。 */
    public static float dark() {
        return dark;
    }

    /** 地下モードの床の持ち上げ(ambient)に掛ける倍率(入った直後は darkAmbient、慣れると 1)。 */
    public static float ambientScale() {
        if (dark <= 0.0F || !on()) {
            return 1.0F;
        }
        return Mth.lerp(dark, 1.0F, DesignData.atmosphere().eyeAdapt().darkAmbient());
    }

    /** 地下モードの寄せの時定数(秒): まぶしさ・暗さが hideFrom を超えている間は hideSeconds まで縮める({@link CaveLights#tick} から)。 */
    static float transitionSeconds(float normal) {
        if (!on()) {
            return normal;
        }
        AtmosphereEye.EyeAdaptParams p = DesignData.atmosphere().eyeAdapt();
        float k = SunLight.smooth(Mth.clamp((Math.max(glare, dark) - p.hideFrom() * 0.5F) / Math.max(0.01F, p.hideFrom() * 0.5F), 0.0F, 1.0F));
        return Mth.lerp(k, normal, Math.min(normal, p.hideSeconds()));
    }

    /**
     * 全画面の露出の補正(GameRenderer の renderLevel の直後 = 地形・Mob・粒・手を描いた後、GUI の前。Mixin {@code GameRendererEyeAdaptMixin} から)。
     * 主画面を写した物(縮めた段 = にじみ用)を読んで主画面へ描き戻す。まぶしさも暗さも無いときは何もしない。
     */
    public static void renderPost() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || broken) {
            return;
        }
        if (!on()) {
            glare = 0.0F;
            dark = 0.0F;
            return;
        }
        update();
        AtmosphereEye.EyeAdaptParams p = DesignData.atmosphere().eyeAdapt();
        // 暗い所を沈めるのは地下モードの間だけ(家の中に入ったときなどは掛けない。11-07 仮決め)。まぶしさは世界全体(暗い家から昼の外へ出ても少し出る)
        float shadow = dark * p.darkShadow() * CaveLights.mix();
        if (glare <= 0.002F && shadow <= 0.002F) {
            return;
        }
        ShaderInstance shader = ModShaders.eyeAdapt();
        if (shader == null) {
            return;
        }
        try {
            draw(mc, shader, p, shadow);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("目の慣れの画面の補正で例外が出たので、このセッションでは掛けない", e);
        }
    }

    private static void draw(Minecraft mc, ShaderInstance shader, AtmosphereEye.EyeAdaptParams p, float shadow) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (copy == null) {
            copy = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            copyMipLevels = 0;
        } else if (copy.width != w || copy.height != h) {
            copy.resize(w, h, Minecraft.ON_OSX);
            copyMipLevels = 0;
        }
        // 主画面の色を写して縮めた段を作る(にじみは縮めた段 3〜5 を読む)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(copy.getColorTextureId());
        if (copyMipLevels == 0) {
            copyMipLevels = 6;
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, copyMipLevels);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(previous);

        main.bindWrite(true);
        shader.setSampler("SceneTex", copy.getColorTextureId());
        shader.safeGetUniform("EyeA").set(glare, p.glareExposure(), p.glareBloom(), p.glareWash());
        shader.safeGetUniform("EyeB").set(p.glareDesaturate(), shadow, 0.0F, 0.0F);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(() -> shader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 確かめ用の一行。 */
    static String describe() {
        return String.format("目の慣れ %s 今 %.2f EV・慣れ %.2f EV・まぶしさ %.2f・暗さ %.2f・床の倍率 %.2f", on() ? "オン" : "オフ", scene, adapted, glare, dark,
                ambientScale());
    }
}
