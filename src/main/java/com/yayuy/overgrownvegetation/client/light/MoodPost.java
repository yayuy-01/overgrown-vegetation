package com.yayuy.overgrownvegetation.client.light;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.io.IOException;

/**
 * 画面全体の後処理 1 パス: 色の調子の LUT + 弱いビネット(2026-10-21 試作。入力は {@link MoodState})。
 *
 * <p>世界(手を含む)を描き終えた後・GUI の前に 1 回だけ({@code GameRendererMoodPostMixin} が {@code doEntityOutline} の直後から呼ぶ)。
 * 主画面の色を自前の {@link TextureTarget} へ写し({@code glBlitFramebuffer})、画面いっぱいの四角で主画面へ描き戻す。
 * 地形のシェーダーに頼らないので Sodium でも効く。Iris のシェーダーパックが使われている間は掛けない(パックが自分の色の調子を持つ)。
 *
 * <p>LUT は {@code textures/lut/mood.png}(256×64): 16×16×16 の表を青の段ごとに横へ 16 枚並べた 256×16 を、
 * 上から 外・過繁茂・極相・夜 の 4 段に縦に積んだもの(過繁茂・極相は熱帯の色味。10-22 に冷たい版から替えた)。生成器は {@code tools/lut/GenLut.java}。.mcmeta で補間あり・端で折り返さない。
 * 重み: 外(+ 繁茂の半分)・森(過繁茂 + 繁茂の半分)・極相の 3 枚を混ぜ、夜は「夜の表」へ夜の度合いだけ寄せる。
 */
public final class MoodPost {

    private static final ResourceLocation LUT = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "textures/lut/mood.png");

    /** ビネットの強さ(外・森・極相・夜に足す分)。仮決め。 */
    private static final float VIGNETTE_OUT = 0.15F;
    private static final float VIGNETTE_FOREST = 0.28F;
    private static final float VIGNETTE_CLIMAX = 0.42F;
    private static final float VIGNETTE_NIGHT = 0.10F;

    static ShaderInstance shader;
    private static TextureTarget scratch;
    private static boolean broken;

    /** 開発用: GPU の時間(GL の時刻の問い合わせ。fxprof の受け手の包みではこのパスは見えない)。 */
    public static volatile boolean timing;
    private static final int SLOTS = 4;
    private static int[] q0;
    private static int[] q1;
    private static final boolean[] pending = new boolean[SLOTS];
    private static int slot;
    private static long gpuNanos;
    private static int gpuFrames;

    public static void render(Minecraft mc) {
        if (broken || shader == null || mc.level == null || !(MoodState.lutActive() || MoodState.vignetteActive()) || irisPackActive()) {
            return;
        }
        try {
            draw(mc);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("画面の色の調子(LUT)で例外が出たので、このセッションの間は掛けない", e);
        }
    }

    /** 自前のビネットを今掛けているか(掛けている間はバニラの GUI のビネットを描かない、{@code GuiVanillaVignetteMixin})。 */
    public static boolean drawsVignette(Minecraft mc) {
        return MoodState.vignetteActive() && !broken && shader != null && mc.level != null && !irisPackActive();
    }

    private static void draw(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (scratch == null) {
            scratch = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        } else if (scratch.width != w || scratch.height != h) {
            scratch.resize(w, h, Minecraft.ON_OSX);
        }
        boolean timed = timing && beginTimer();

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(true);

        float overgrowth = MoodState.overgrowth();
        float wOut = MoodState.outside() + 0.5F * overgrowth;
        float wForest = MoodState.forest();
        float wClimax = MoodState.climax();
        float night = MoodState.night() * (1.0F - MoodState.underground());
        float vignette = 0.0F;
        if (MoodState.vignetteActive()) {
            vignette = wOut * VIGNETTE_OUT + wForest * VIGNETTE_FOREST + wClimax * VIGNETTE_CLIMAX + night * VIGNETTE_NIGHT;
        }
        shader.setSampler("Scene", scratch.getColorTextureId());
        shader.setSampler("Lut", mc.getTextureManager().getTexture(LUT).getId());
        shader.safeGetUniform("Weights").set(wOut, wForest, wClimax, night);
        shader.safeGetUniform("Grade").set(MoodState.lutActive() ? 1.0F : 0.0F, vignette, (float) w / Math.max(1, h), MoodState.strength());

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

        if (timed) {
            GL33.glQueryCounter(q1[slot], GL33.GL_TIMESTAMP);
            pending[slot] = true;
            slot = (slot + 1) % SLOTS;
        }
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
        if (pending[slot]) {
            if (GL15.glGetQueryObjecti(q1[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return false;
            }
            gpuNanos += GL33.glGetQueryObjecti64(q1[slot], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q0[slot], GL15.GL_QUERY_RESULT);
            gpuFrames++;
            pending[slot] = false;
        }
        GL33.glQueryCounter(q0[slot], GL33.GL_TIMESTAMP);
        return true;
    }

    /** 開発用: 測った GPU の時間の平均を返して数え直す。 */
    public static String takeStat() {
        String s = gpuFrames == 0 ? "まだ測れていない(mood time on の後で数秒待つ)"
                : String.format("GPU %.3f ms(%d フレームの平均、画面 %d×%d)", gpuNanos / 1.0E6 / gpuFrames, gpuFrames,
                scratch == null ? 0 : scratch.width, scratch == null ? 0 : scratch.height);
        gpuNanos = 0L;
        gpuFrames = 0;
        return s;
    }

    /** Iris のシェーダーパックが使われているか(パックの色の調子と二重にしない)。判定の本体は {@link com.yayuy.overgrownvegetation.client.ShaderPacks}。 */
    public static boolean irisPackActive() {
        return com.yayuy.overgrownvegetation.client.ShaderPacks.active();
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Shaders {
        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                                ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "mood_grade"),
                                DefaultVertexFormat.POSITION),
                        s -> shader = s);
            } catch (IOException e) {
                OvergrownVegetation.LOGGER.error("画面の色の調子(LUT)のシェーダーを読み込めなかったので、掛けない", e);
                shader = null;
            }
        }

        private Shaders() {
        }
    }

    private MoodPost() {
    }
}
