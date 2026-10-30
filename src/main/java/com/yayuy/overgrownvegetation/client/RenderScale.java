package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.light.MoodPost;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import com.yayuy.overgrownvegetation.mixin.client.LevelRendererPostChainAccessor;
import com.yayuy.overgrownvegetation.mixin.client.PostChainSizeAccessor;
import com.yayuy.overgrownvegetation.mixin.client.RenderTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 描画解像度の縮小(2026-10-23、軽量化の段階 2。設定は {@link OvergrownClientConfig.Client#renderScale})。
 *
 * <p>世界(地形・Mob・粒・自前の演出)だけを縮めた描画先へ描き、手の前で主画面へ線形補間で拡大して戻す。手・GUI・HUD・色の調子({@link MoodPost})は等倍のまま。
 * 1650 級では地形のシェーダーの塗りつぶしの量で詰まる見込みなので、画素の数をそのまま減らす。
 *
 * <p><b>入れ替えるのは主画面の中身だけ</b>: {@code Minecraft#getMainRenderTarget()} の物はそのままに、世界を描く間だけその FBO・絵・奥行きの番号と大きさを
 * 縮めた描画先({@link #scaled})のものへ差し替える。後処理(光る輪郭・ファビュラス)は作るときに主画面の物を握り、DH などほかの MOD も主画面の番号を読むので、
 * 物を替えるより中身を替える方が、どれも縮めた絵を見る。後処理の大きさは世界を描く間に合わせ直す({@link #fitChains})。
 * スペクテイターの見え方の後処理と、ぼかし(GUI)は世界の外なので等倍のまま。
 *
 * <p>掛けない: 100% のとき・ワールドの外・Iris のシェーダーパックを使っているとき(パックが自分の描画先を持つ)。
 * 開発用の {@link #devScale}(テストクライアントの {@code rscale})は 2.0 まで許し、1080p の窓で 4K の塗りつぶしの重さを測る。
 */
public final class RenderScale {

    /** 開発用: 0 より大きければ設定の代わりにこの倍率(0.25〜2.0)。 */
    public static volatile float devScale;

    private static TextureTarget scaled;
    private static boolean active;
    private static boolean chainsScaled;
    private static boolean broken;

    private static int savedFbo;
    private static int savedColor;
    private static int savedDepth;
    private static int savedWidth;
    private static int savedHeight;
    private static int savedViewWidth;
    private static int savedViewHeight;
    private static int savedFilter;

    /** 開発用: 縮めて描いたフレームの数。 */
    public static long scaledFrames;

    /** 今の倍率(1 = 縮めない)。 */
    public static float scale() {
        float dev = devScale;
        if (dev > 0.0F) {
            return Math.max(0.25F, Math.min(2.0F, dev));
        }
        if (!OvergrownClientConfig.SPEC.isLoaded()) {
            return 1.0F;
        }
        return OvergrownClientConfig.CLIENT.renderScale.get() / 100.0F;
    }

    private static boolean wanted(Minecraft mc) {
        return !broken && mc.level != null && Math.abs(scale() - 1.0F) > 0.005F && !MoodPost.irisPackActive();
    }

    /** 世界を描き始める直前(GameRenderer#render の renderLevel の前)。 */
    public static void beginWorld(Minecraft mc) {
        if (active) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        if (!wanted(mc)) {
            if (chainsScaled) {
                fitChains(mc, main.width, main.height);
                chainsScaled = false;
            }
            return;
        }
        try {
            float s = scale();
            int max = RenderSystem.maxSupportedTextureSize();
            // 偶数に丸める: 幅が奇数(1280 × 0.66 = 845)だと Sodium + Iris + DH で空の真ん中に縦の線が 1 本出た(2026-10-23)
            int w = Math.max(2, Math.min(max, Math.round(main.width * s / 2.0F) * 2));
            int h = Math.max(2, Math.min(max, Math.round(main.height * s / 2.0F) * 2));
            if (scaled == null) {
                scaled = new TextureTarget(w, h, true, Minecraft.ON_OSX);
                scaled.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            } else if (scaled.width != w || scaled.height != h) {
                scaled.resize(w, h, Minecraft.ON_OSX);
            }
            if (main.isStencilEnabled() && !scaled.isStencilEnabled()) {
                scaled.enableStencil();
            }

            savedFbo = main.frameBufferId;
            savedColor = main.getColorTextureId();
            savedDepth = main.getDepthTextureId();
            savedWidth = main.width;
            savedHeight = main.height;
            savedViewWidth = main.viewWidth;
            savedViewHeight = main.viewHeight;
            savedFilter = main.filterMode;
            put(main, scaled.frameBufferId, scaled.getColorTextureId(), scaled.getDepthTextureId(),
                    scaled.width, scaled.height, scaled.viewWidth, scaled.viewHeight, scaled.filterMode);
            active = true;
            main.bindWrite(true);
            fitChains(mc, w, h);
            chainsScaled = true;
            scaledFrames++;
        } catch (RuntimeException e) {
            broken = true;
            if (active) {
                endWorld(mc);
            }
            OvergrownVegetation.LOGGER.error("描画解像度の縮小で例外が出たので、このセッションの間は等倍で描く", e);
        }
    }

    /** 世界を描き終えた所(手の前)。縮めた絵を主画面へ拡大して戻す。 */
    public static void endWorld(Minecraft mc) {
        if (!active) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        int fbo = main.frameBufferId;
        int w = main.width;
        int h = main.height;
        put(main, savedFbo, savedColor, savedDepth, savedWidth, savedHeight, savedViewWidth, savedViewHeight, savedFilter);
        active = false;

        GlStateManager._disableScissorTest();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, main.width, main.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        main.bindWrite(true);
    }

    /** 世界を縮めて描いている最中か。 */
    public static boolean active() {
        return active;
    }

    private static void put(RenderTarget t, int fbo, int color, int depth, int w, int h, int vw, int vh, int filter) {
        t.frameBufferId = fbo;
        ((RenderTargetAccessor) t).ov$setColorTextureId(color);
        ((RenderTargetAccessor) t).ov$setDepthBufferId(depth);
        t.width = w;
        t.height = h;
        t.viewWidth = vw;
        t.viewHeight = vh;
        t.filterMode = filter;
    }

    /**
     * 世界の中で使う後処理(光る輪郭・ファビュラス)の大きさを主画面の今の大きさへ合わせる。{@code PostChain#resize} は主画面の物の今の大きさを読むので、
     * 縮めた中身に入れ替えた後に呼ぶ。合っていれば何もしない(作り直しは画面の大きさ・倍率・描画設定が変わったときだけ)。
     */
    private static void fitChains(Minecraft mc, int w, int h) {
        LevelRendererPostChainAccessor lr = (LevelRendererPostChainAccessor) mc.levelRenderer;
        fit(lr.ov$entityEffect(), w, h);
        fit(lr.ov$transparencyChain(), w, h);
    }

    private static void fit(PostChain chain, int w, int h) {
        if (chain == null) {
            return;
        }
        PostChainSizeAccessor size = (PostChainSizeAccessor) chain;
        if (size.ov$screenWidth() != w || size.ov$screenHeight() != h) {
            chain.resize(w, h);
        }
    }

    /** 開発用の 1 行。 */
    public static String status(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        return String.format("RSCALE 倍率 %.2f(開発用 %.2f)・主画面 %d×%d・縮めた描画先 %s・縮めたフレーム %d%s", scale(), devScale, main.width, main.height,
                scaled == null ? "なし" : scaled.width + "×" + scaled.height, scaledFrames, broken ? "・例外で止めた" : "");
    }

    private RenderScale() {
    }
}
