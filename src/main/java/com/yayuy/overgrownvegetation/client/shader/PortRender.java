package com.yayuy.overgrownvegetation.client.shader;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * <b>1.20.1 だけの助け手(G6 描画 2、2026-11-23)</b>。本流(1.21.1)の描画の API の代わりを 1 か所に置く。本流には無いクラス。
 * <ul>
 *     <li>{@link #modelView}: 1.21 の {@code RenderLevelStageEvent#getModelViewMatrix()}(カメラの回転)。1.20.1 の事件はそれを
 *     {@code getPoseStack()} に入れて渡すが、<b>AFTER_LEVEL だけは投影の側の積み(揺れ・吐き気の歪み)を渡す</b>(1.20.1 の
 *     {@code GameRenderer#renderLevel})。それで同じフレームの前の段で覚えた物を使う。</li>
 *     <li>{@link #pushStageModelView}: 1.21 は AFTER_ENTITIES〜AFTER_WEATHER の間、RenderSystem の視点行列にカメラの回転を掛けておく
 *     (1.21 の {@code LevelRenderer#renderLevel} の 1001〜1243 行)。1.20.1 は雲・天気の間だけ。自前の器({@code AddLight#buffers})や
 *     バニラのシェーダーで描く所は、この間だけ 1.21 と同じ行列にする。</li>
 *     <li>{@link #setDefaultUniforms}: 1.21 の {@code ShaderInstance#setDefaultUniforms}。中身は 1.20.1 の
 *     {@code VertexBuffer#_drawWithShader} の uniform の所と同じ(1.20.1 のバニラのシェーダーが読む {@code IViewRotMat} も入れる)。</li>
 *     <li>{@link #begin}: 1.21 の {@code Tesselator#begin}(1.20.1 は 1 つの器を {@code getBuilder()} で借りて {@code begin})。</li>
 *     <li>{@link #acquireBuilder}・{@link #releaseBuilder}: 1.21 の {@code new ByteBufferBuilder(n)}・{@code close()}。1.20.1 の
 *     {@code BufferBuilder} は作ると {@code n × 6} バイトを取り、<b>放す手段が無い</b>ので、作り直さずに使い回す。</li>
 *     <li>{@link Shards}: 1.20.1 では protected の {@link RenderStateShard} の値(1.21 は public)。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PortRender {

    /** このフレームのカメラの回転(AFTER_LEVEL より前の段の {@code getPoseStack().last().pose()})。 */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final ConcurrentLinkedQueue<BufferBuilder> POOL = new ConcurrentLinkedQueue<>();

    /** どの段の受け手よりも先に、カメラの回転を覚える。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            VIEW.set(event.getPoseStack().last().pose());
        }
    }

    /** 1.21 の {@code event.getModelViewMatrix()}(カメラの回転。毎回新しい行列)。 */
    public static Matrix4f modelView(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return new Matrix4f(event.getPoseStack().last().pose());
        }
        return new Matrix4f(VIEW);
    }

    /** 1.21 でこの段の間 RenderSystem の視点行列にカメラの回転が入っていて、1.20.1 では入っていない段か。 */
    private static boolean differs(RenderLevelStageEvent.Stage stage) {
        return stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES || stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS
                || stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
    }

    /**
     * RenderSystem の視点行列を、この段の 1.21 の値(カメラの回転)にする。積んだら true({@link #popStageModelView} に渡す)。
     * AFTER_WEATHER は 1.20.1 でも入っている・AFTER_SKY・地形の段・AFTER_LEVEL は 1.21 でも単位行列なので、何もしない。
     */
    public static boolean pushStageModelView(RenderLevelStageEvent event) {
        if (!differs(event.getStage())) {
            return false;
        }
        PoseStack stack = RenderSystem.getModelViewStack();
        stack.pushPose();
        stack.last().pose().set(event.getPoseStack().last().pose());
        RenderSystem.applyModelViewMatrix();
        return true;
    }

    public static void popStageModelView(boolean pushed) {
        if (pushed) {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** 1.21 の {@code shader.setDefaultUniforms(mode, modelView, projection, window)}。 */
    public static void setDefaultUniforms(ShaderInstance shader, VertexFormat.Mode mode, Matrix4f modelView, Matrix4f projection, Window window) {
        for (int i = 0; i < 12; i++) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.GLINT_ALPHA != null) {
            shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }
        if (shader.SCREEN_SIZE != null) {
            shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        }
        if (shader.LINE_WIDTH != null && (mode == VertexFormat.Mode.LINES || mode == VertexFormat.Mode.LINE_STRIP)) {
            shader.LINE_WIDTH.set(RenderSystem.getShaderLineWidth());
        }
        RenderSystem.setupShaderLights(shader);
    }

    /** 1.21 の {@code Tesselator.getInstance().begin(mode, format)}。前の描きかけが残っていたら捨てる(1.21 は毎回新しい器なので残らない)。 */
    public static BufferBuilder begin(VertexFormat.Mode mode, VertexFormat format) {
        return begin(Tesselator.getInstance().getBuilder(), mode, format);
    }

    /** 1.21 の {@code new BufferBuilder(bytes, mode, format)}。 */
    public static BufferBuilder begin(BufferBuilder builder, VertexFormat.Mode mode, VertexFormat format) {
        if (builder.building()) {
            BufferBuilder.RenderedBuffer left = builder.endOrDiscardIfEmpty();
            if (left != null) {
                left.release();
            }
        }
        builder.begin(mode, format);
        return builder;
    }

    /** 1.21 の {@code new ByteBufferBuilder(bytes)}。使い終わったら {@link #releaseBuilder}(器は足りなければ自分で伸びる)。 */
    public static BufferBuilder acquireBuilder(int bytes) {
        BufferBuilder b = POOL.poll();
        return b != null ? b : new BufferBuilder(Math.max(256, bytes / 6));
    }

    /** 1.21 の {@code ByteBufferBuilder#close()}。描きかけは捨て、器を次に回す。 */
    public static void releaseBuilder(BufferBuilder builder) {
        if (builder == null) {
            return;
        }
        if (builder.building()) {
            BufferBuilder.RenderedBuffer left = builder.endOrDiscardIfEmpty();
            if (left != null) {
                left.release();
            }
        }
        builder.discard();
        POOL.offer(builder);
    }

    /** 1.20.1 では protected の描き方の部品(1.21 は public)。継いだクラスの中からだけ読めるので、ここで読み出して渡す。 */
    public static final class Shards extends RenderStateShard {
        public static final RenderStateShard.ShaderStateShard POSITION_COLOR_SHADER = RenderStateShard.POSITION_COLOR_SHADER;
        public static final RenderStateShard.ShaderStateShard RENDERTYPE_LIGHTNING_SHADER = RenderStateShard.RENDERTYPE_LIGHTNING_SHADER;
        public static final RenderStateShard.TransparencyStateShard LIGHTNING_TRANSPARENCY = RenderStateShard.LIGHTNING_TRANSPARENCY;
        public static final RenderStateShard.TransparencyStateShard TRANSLUCENT_TRANSPARENCY = RenderStateShard.TRANSLUCENT_TRANSPARENCY;
        public static final RenderStateShard.CullStateShard NO_CULL = RenderStateShard.NO_CULL;
        public static final RenderStateShard.WriteMaskStateShard COLOR_WRITE = RenderStateShard.COLOR_WRITE;
        public static final RenderStateShard.LayeringStateShard VIEW_OFFSET_Z_LAYERING = RenderStateShard.VIEW_OFFSET_Z_LAYERING;
        public static final RenderStateShard.OutputStateShard PARTICLES_TARGET = RenderStateShard.PARTICLES_TARGET;
        public static final RenderStateShard.OutputStateShard WEATHER_TARGET = RenderStateShard.WEATHER_TARGET;

        private Shards() {
            super("overgrown_vegetation_port_shards", () -> {
            }, () -> {
            });
        }
    }

    private PortRender() {
    }
}
