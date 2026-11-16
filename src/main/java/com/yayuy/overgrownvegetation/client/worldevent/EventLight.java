package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

/**
 * ワールドイベントの<b>加算の光</b>の描き方(色だけの頂点・足し算・両面・奥行きは読むが書かない)。{@code client/atmosphere/AddLight} と同じ作りの写し
 * (あちらはパッケージの中だけの物なので。踏んだ罠の説明はあちらにある: 自前の器でその場で描き切る・「最高」では主画面へ直に描く)。
 */
final class EventLight {

    private static MultiBufferSource.BufferSource buffers;
    private static RenderType renderType;

    static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
        }
        return buffers;
    }

    static boolean drawToMainTarget() {
        if (!Minecraft.useShaderTransparency() || ShaderPacks.active()) {
            return false;
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        return true;
    }

    static void restoreTarget(boolean switched) {
        if (switched) {
            RenderStateShard.PARTICLES_TARGET.setupRenderState();
        }
    }

    static RenderType renderType() {
        if (renderType == null) {
            renderType = RenderType.create(
                    OvergrownVegetation.MODID + "_world_event_light",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    8192,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(ShaderPacks.active() ? RenderStateShard.RENDERTYPE_LIGHTNING_SHADER : RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return renderType;
    }

    /**
     * 地形と同じ霧の掛かり具合(1 = 霧なし、0 = 霧に沈んだ)。色だけの頂点のシェーダーは霧を受けないので、足し算の光はこの分だけ弱める。
     */
    static float fogKeep(float distance) {
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        lastFogStart = start;
        lastFogEnd = end;
        if (!(end > start) || end > 4096.0F || start < -4096.0F) {
            // 霧が切ってある・値が普通でない(ほかの描画が入れた値)ときは、描画距離の端でだけ消す。
            float far = Minecraft.getInstance().gameRenderer.getRenderDistance();
            return Mth.clamp((far - distance) / Math.max(8.0F, far * 0.2F), 0.0F, 1.0F);
        }
        return 1.0F - Mth.clamp((distance - start) / (end - start), 0.0F, 1.0F);
    }

    static float lastFogStart;
    static float lastFogEnd;

    private EventLight() {
    }
}
