package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 【1.20.1 だけの物・G5 の助け手】1.21 の {@code VertexConsumer#addVertex(x, y, z, int argb, u, v, overlay, light, nx, ny, nz)}(色が ARGB の int 1 つ)を、
 * 1.20.1 の {@code vertex(x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz)}(色が float 4 つ。中で endVertex まで済む)で書く。
 */
public final class PortVertex {

    private PortVertex() {
    }

    public static void addVertex(VertexConsumer vc, float x, float y, float z, int argb, float u, float v, int packedOverlay, int packedLight,
                                 float normalX, float normalY, float normalZ) {
        vc.vertex(x, y, z, (argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F,
                u, v, packedOverlay, packedLight, normalX, normalY, normalZ);
    }
}
