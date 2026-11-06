package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * 自分で光る板(加算・奥行きを書かない)の描き方(2026-11-05、ファルキムネスの飛ぶ斬撃・地走りの光の帯)。
 * バニラの {@code RenderType.lightning()} は奥行きを書くので、重ねた光の板が互いを隠して、光の帯の中に暗い縞が出た
 * (同じ面の 2 枚が奥行きを取り合う・手前の板が奥の板を消す)。奥行きは読む(地形の陰には隠れる)が書かない。
 * {@code RenderType} の部品(シェーダー・重ね方など)は protected なので、それを使うためだけに継ぐ(作らない)。
 */
public final class GlowRenderTypes extends RenderType {

    /** 位置 + 色。加算(雷と同じ重ね方)・両面・奥行きは書かない。 */
    public static final RenderType ADDITIVE = create("overgrown_vegetation_glow", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1536, false, true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    private GlowRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort,
                            Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
    }
}
