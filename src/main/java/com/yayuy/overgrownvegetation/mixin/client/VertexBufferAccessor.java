package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 頂点のバッファの中身(private)。地形の区画を決まった器で描く(2026-10-20、{@code client.FastChunkDraw})。 */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {

    @Accessor("vertexBufferId")
    int overgrown_vegetation$vertexBufferId();

    @Accessor("format")
    VertexFormat overgrown_vegetation$format();

    @Accessor("sequentialIndices")
    RenderSystem.AutoStorageIndexBuffer overgrown_vegetation$sequentialIndices();
}
