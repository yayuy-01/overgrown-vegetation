package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 大群の描画の重さを切り分ける確かめ用の切り替え(2026-11-18 大群の軽量化 C)。devbridge の {@code crowd anim|draw|sink off|on} で動かす。
 * ふだんはどれも false で、何も変わらない。
 * <ul>
 *   <li>{@link #skipAnim}: この MOD の GeckoLib Mob({@code LodGeoModel})のキーフレームの計算を飛ばし、骨をレストポーズにして描く
 *       (頂点の書き出し・送り込みだけの重さが残る)。</li>
 *   <li>{@link #skipDraw}: 同じ Mob を描かない(描画の全部の重さが消える)。</li>
 *   <li>{@link #sink}: アニメも頂点の計算もするが、頂点を捨てる(GPU へ送らない。送り込みと描く重さだけが消える)。</li>
 * </ul>
 */
public final class CrowdRenderDev {

    public static volatile boolean skipAnim;
    public static volatile boolean skipDraw;
    public static volatile boolean sink;

    /** 何も溜めない頂点の受け口。 */
    public static final VertexConsumer NOWHERE = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    };

    public static final MultiBufferSource SINK = type -> NOWHERE;

    private CrowdRenderDev() {
    }
}
