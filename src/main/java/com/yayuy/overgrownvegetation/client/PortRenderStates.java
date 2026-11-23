package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 【1.20.1 だけの物・G5 の助け手】{@code RenderStateShard} の決まった部品の窓口。
 * NeoForge 1.21.1 はアクセス変換でこれらを public にしているが、1.20.1 では protected なので、RenderType を継いだここから出す
 * (本流で {@code RenderStateShard.X} と書いていた所を {@code PortRenderStates.X} にする)。このクラス自体は作らない。
 */
public final class PortRenderStates extends RenderType {

    public static final RenderStateShard.ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER = RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER;
    public static final RenderStateShard.ShaderStateShard RENDERTYPE_TEXT_SHADER = RenderStateShard.RENDERTYPE_TEXT_SHADER;
    public static final RenderStateShard.ShaderStateShard RENDERTYPE_LIGHTNING_SHADER = RenderStateShard.RENDERTYPE_LIGHTNING_SHADER;
    public static final RenderStateShard.ShaderStateShard POSITION_COLOR_SHADER = RenderStateShard.POSITION_COLOR_SHADER;
    public static final RenderStateShard.TransparencyStateShard NO_TRANSPARENCY = RenderStateShard.NO_TRANSPARENCY;
    public static final RenderStateShard.TransparencyStateShard TRANSLUCENT_TRANSPARENCY = RenderStateShard.TRANSLUCENT_TRANSPARENCY;
    public static final RenderStateShard.TransparencyStateShard LIGHTNING_TRANSPARENCY = RenderStateShard.LIGHTNING_TRANSPARENCY;
    public static final RenderStateShard.CullStateShard NO_CULL = RenderStateShard.NO_CULL;
    public static final RenderStateShard.LightmapStateShard LIGHTMAP = RenderStateShard.LIGHTMAP;
    public static final RenderStateShard.OverlayStateShard OVERLAY = RenderStateShard.OVERLAY;
    public static final RenderStateShard.OutputStateShard PARTICLES_TARGET = RenderStateShard.PARTICLES_TARGET;
    public static final RenderStateShard.WriteMaskStateShard COLOR_WRITE = RenderStateShard.COLOR_WRITE;

    private PortRenderStates() {
        super("overgrown_vegetation_port_states", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 0, false, false, () -> {
        }, () -> {
        });
    }
}
