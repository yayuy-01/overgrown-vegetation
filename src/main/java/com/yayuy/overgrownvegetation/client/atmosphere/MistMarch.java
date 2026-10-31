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
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * <b>霧の細部</b>(第 3 段「霧」の品質 HIGH だけ、10-30 夜。方式 B の後半)。近く {@code fogMarch.distance}(48)ブロックの霧を、
 * 画面の {@code resolution}(半分)の大きさで 1 本ずつレイマーチして重ねる({@code mist_march.fsh} → {@code mist_composite.fsh})。
 *
 * <ul>
 *     <li>密度は土台({@link MistFog}・{@code ov_fog.glsl})と同じ層 × 風で流れる 3D の濃淡。近くの霧に流れる濃淡が出る。</li>
 *     <li><b>受け渡し</b>(10-30 夜 相談セッションの答え): 近く 48 はレイマーチが全部、その先は土台の閉じた式。{@code fadeFrom}(40)〜48 で直線に混ぜる
 *     (土台は {@code OvFogH} の 2 つの距離で自分の分を引く)。奥行きバッファにいる Mob・不透明の粒も近くの霧で霞む。</li>
 *     <li><b>霧の中の光の筋</b>: 歩ごとに林冠の日向の地図({@link CanopySunMap}。地形の影と同じ地図)を引き、日の当たる所だけ太陽の方へ強く散らす(Henyey-Greenstein)。</li>
 *     <li>描く段階は AFTER_WEATHER(地形・半透明・粒・天気の後、手の前)。主画面の奥行きを写して読み、主画面へ (ONE, SRC_ALPHA) で重ねる。
 *     「最高」では主画面へ描いて天気の層へ戻す({@link AddLight} と同じ罠)。半透明(水)の奥行きは「最高」では主画面に無い(水の手前の霧は土台が持つ)。</li>
 *     <li>シェーダーパック使用中・品質 HIGH 以外・霧が無いときは描かない(土台が近くも積む)。</li>
 * </ul>
 * 重さは GL の時刻の問い合わせで測る({@link #takeStat}、devbridge の {@code fog time})。数値は {@code atmosphere.json} の節 {@code fogMarch}(すべて仮決め)。
 */
public final class MistMarch {

    private static TextureTarget depthCopy;
    private static TextureTarget half;
    private static boolean broken;
    /** このフレームに描くか(AFTER_SKY で決め、土台の受け渡しの距離と揃える)。 */
    private static boolean drawThisFrame;
    /** 開発用: 描くのを止める(撮り比べ)。 */
    static volatile boolean devOff;

    /** 開発用: GPU の時間(GL の時刻の問い合わせ)。 */
    static volatile boolean timing;
    private static final int SLOTS = 4;
    private static int[] q0;
    private static int[] q1;
    private static final boolean[] PENDING = new boolean[SLOTS];
    private static int slot;
    private static long gpuNanos;
    private static int gpuFrames;

    private MistMarch() {
    }

    /** 土台がレイマーチに近くを任せるか(AFTER_SKY、{@link MistFog#upload} の前に {@link #prepare} で決めた値)。 */
    static boolean willDraw() {
        return drawThisFrame;
    }

    /** AFTER_SKY で今フレーム描くかを決める。 */
    static void prepare(boolean on) {
        on = on && VisualQuality.fogDetail();
        drawThisFrame = on && !broken && !devOff && VisualQuality.atmosphereTier() == 0 && !ShaderPacks.active()
                && ModShaders.mistMarch() != null && ModShaders.mistComposite() != null && MistFog.active();
    }

    /** AFTER_WEATHER({@link Atmosphere} から)。 */
    static void render(RenderLevelStageEvent event) {
        if (!VisualQuality.fogDetail()) {
            return;
        }
        if (!drawThisFrame) {
            return;
        }
        try {
            draw(event);
        } catch (RuntimeException e) {
            broken = true;
            drawThisFrame = false;
            OvergrownVegetation.LOGGER.error("霧の細部(レイマーチ)で例外が出たので、このセッションでは描かない(土台の霧だけ)", e);
        }
    }

    private static void draw(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        AtmosphereTable.MarchParams mp = DesignData.atmosphere().fogMarch();
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        int hw = Math.max(1, Math.round(w * mp.resolution()));
        int hh = Math.max(1, Math.round(h * mp.resolution()));
        if (depthCopy == null) {
            depthCopy = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        }
        if (main.isStencilEnabled() && !depthCopy.isStencilEnabled()) {
            depthCopy.enableStencil();
        }
        if (depthCopy.width != w || depthCopy.height != h) {
            depthCopy.resize(w, h, Minecraft.ON_OSX);
        }
        if (half == null) {
            half = new TextureTarget(hw, hh, false, Minecraft.ON_OSX);
            half.setFilterMode(GL11.GL_NEAREST);
        } else if (half.width != hw || half.height != hh) {
            half.resize(hw, hh, Minecraft.ON_OSX);
            half.setFilterMode(GL11.GL_NEAREST);
        }
        boolean timed = timing && beginTimer();

        depthCopy.copyDepthFrom(main);

        ShaderInstance march = ModShaders.mistMarch();
        ShaderInstance composite = ModShaders.mistComposite();
        Vec3 cam = event.getCamera().getPosition();
        MistFog.setUniforms(march, cam);
        Matrix4f inv = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
        march.safeGetUniform("InvViewProj").set(inv);
        march.setSampler("DepthTex", depthCopy.getDepthTextureId());
        march.setSampler("OvFogMap", MistMap.textureId());
        boolean sunMap = CanopySunMap.sporeTexture >= 0;
        march.setSampler("SunMap", sunMap ? CanopySunMap.sporeTexture : MistMap.textureId());
        float noiseScale = mp.noiseScale();
        march.safeGetUniform("MarchA").set((float) mp.steps(), mp.distance(), mp.fadeFrom(), mp.noise());
        march.safeGetUniform("MarchB").set(noiseScale, mp.sunScatter(), mp.scatterG(), 0.0F);
        float t = (float) (Atmosphere.time(event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0);
        march.safeGetUniform("MarchC").set((float) wrap((cam.x - MistFog.driftX()) / noiseScale, 64.0),
                (float) wrap(cam.y / noiseScale + t * 0.02, 64.0), (float) wrap((cam.z - MistFog.driftZ()) / noiseScale, 64.0), 0.0F);
        float[] sa = CanopySunMap.SPORE_SUN_A;
        float[] ma = CanopySunMap.SPORE_MAP_A;
        march.safeGetUniform("MarchD").set(sunMap ? sa[3] : 0.0F, sunMap ? 1.0F : 0.0F, 0.0F, 0.0F);
        march.safeGetUniform("SunA").set(sa[0], sa[1], 0.0F, 0.0F);
        march.safeGetUniform("MapA").set(ma[0], ma[1], ma[2], ma[3]);

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        half.bindWrite(true);
        quad(march);

        // 主画面へ重ねる。「最高」では今の描き先は天気の層なので、主画面へ描いてから戻す。
        main.bindWrite(true);
        composite.setSampler("MarchTex", half.getColorTextureId());
        composite.setSampler("DepthTex", depthCopy.getDepthTextureId());
        composite.safeGetUniform("Size").set((float) hw, (float) hh, 0.05F, mc.gameRenderer.getDepthFar());
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        quad(composite);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        if (Minecraft.useShaderTransparency()) {
            RenderStateShard.WEATHER_TARGET.setupRenderState();
        }

        if (timed) {
            GL33.glQueryCounter(q1[slot], GL33.GL_TIMESTAMP);
            PENDING[slot] = true;
            slot = (slot + 1) % SLOTS;
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

    private static double wrap(double v, double period) {
        return v - Math.floor(v / period) * period;
    }

    /** 古い問い合わせの結果を拾ってから、今の枠の始まりを打つ。枠が空いていなければ今回は測らない。 */
    private static boolean beginTimer() {
        if (q0 == null) {
            q0 = new int[SLOTS];
            q1 = new int[SLOTS];
            for (int i = 0; i < SLOTS; i++) {
                q0[i] = GL15.glGenQueries();
                q1[i] = GL15.glGenQueries();
            }
        }
        if (PENDING[slot]) {
            if (GL15.glGetQueryObjecti(q1[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return false;
            }
            gpuNanos += GL33.glGetQueryObjecti64(q1[slot], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q0[slot], GL15.GL_QUERY_RESULT);
            gpuFrames++;
            PENDING[slot] = false;
        }
        GL33.glQueryCounter(q0[slot], GL33.GL_TIMESTAMP);
        return true;
    }

    /** 開発用: 測った GPU の時間の平均を返して数え直す。 */
    static String takeStat() {
        String s = gpuFrames == 0 ? "まだ測れていない(fog time on の後で数秒待つ)"
                : String.format("GPU %.3f ms(%d フレームの平均、主画面 %d×%d・霧 %d×%d)", gpuNanos / 1.0E6 / gpuFrames, gpuFrames,
                depthCopy == null ? 0 : depthCopy.width, depthCopy == null ? 0 : depthCopy.height, half == null ? 0 : half.width,
                half == null ? 0 : half.height);
        gpuNanos = 0L;
        gpuFrames = 0;
        return s;
    }
}
