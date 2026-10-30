package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.yayuy.overgrownvegetation.client.FastChunkDraw;
import com.yayuy.overgrownvegetation.client.LayerProbe;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地形の区画ごとの {@code glBindVertexArray} を、決まった器に頂点のバッファを差し替えるだけにする(2026-10-20)。中身は {@link FastChunkDraw}。
 * 葉の層を測る口 {@link LayerProbe}(2026-10-24、開発用。hook が無ければ何もしない)もここから呼ぶ。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFastChunkDrawMixin {

    @Unique
    private static boolean overgrownvegetation$probeRedraw;

    @Shadow
    private void renderSectionLayer(RenderType renderType, double x, double y, double z, Matrix4f frustrumMatrix, Matrix4f projectionMatrix) {
        throw new AssertionError();
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), require = 0)
    private void overgrownvegetation$beginLayer(RenderType renderType, double x, double y, double z, Matrix4f frustrumMatrix, Matrix4f projectionMatrix,
                                                CallbackInfo ci) {
        FastChunkDraw.beginLayer();
        LayerProbe.Hook hook = LayerProbe.hook;
        if (hook != null && renderType == RenderType.cutoutMipped()) {
            hook.begin(overgrownvegetation$probeRedraw);
        }
    }

    @Inject(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;setupRenderState()V", shift = At.Shift.AFTER),
            require = 0)
    private void overgrownvegetation$afterSetup(RenderType renderType, double x, double y, double z, Matrix4f frustrumMatrix, Matrix4f projectionMatrix,
                                                CallbackInfo ci) {
        LayerProbe.Hook hook = LayerProbe.hook;
        if (hook != null && renderType == RenderType.cutoutMipped()) {
            hook.afterSetup(overgrownvegetation$probeRedraw);
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"), require = 0)
    private void overgrownvegetation$endLayer(RenderType renderType, double x, double y, double z, Matrix4f frustrumMatrix, Matrix4f projectionMatrix,
                                              CallbackInfo ci) {
        LayerProbe.Hook hook = LayerProbe.hook;
        if (hook == null || renderType != RenderType.cutoutMipped()) {
            return;
        }
        boolean redraw = overgrownvegetation$probeRedraw;
        hook.end(redraw);
        if (!redraw && hook.wantsRedraw()) {
            overgrownvegetation$probeRedraw = true;
            try {
                renderSectionLayer(renderType, x, y, z, frustrumMatrix, projectionMatrix);
            } finally {
                overgrownvegetation$probeRedraw = false;
            }
        }
    }

    @Redirect(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;bind()V"), require = 0)
    private void overgrownvegetation$bindSection(VertexBuffer buffer) {
        if (!FastChunkDraw.bind(buffer)) {
            buffer.bind();
        }
    }
}
