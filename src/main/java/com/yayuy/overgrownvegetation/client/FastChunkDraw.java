package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.yayuy.overgrownvegetation.mixin.client.VertexBufferAccessor;
import java.util.List;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * 地形の区画を描くとき、区画ごとに頂点の器(VAO)を切り替えず、決まった器 1 つに区画の頂点のバッファだけを差し替えて描く(2026-10-20)。
 *
 * <p>バニラ({@code LevelRenderer#renderSectionLayer})は区画 × 層ごとに {@code glBindVertexArray} を呼ぶ。描画距離 16 のジャングルで
 * 1 フレームに約 2000 回あり、ドライバの中の重さが描画のスレッドのネイティブの標本の 8 割(止まっていても FPS の床)になっていた。
 * 頂点の並び方(BLOCK の形式)を決まった器に 1 回だけ書いておき({@code glVertexAttribFormat})、区画ごとには {@code glBindVertexBuffer} で
 * 頂点のバッファだけを付け替える。索引は全部の区画で共通の四角形の索引({@code RenderSystem.getSequentialBuffer(QUADS)})なので、層の始めに 1 回付ける。
 * 描く頂点・索引・順番はバニラと同じ(見た目は変わらない)。
 *
 * <p>使わずにバニラのまま描く: GL 4.3(か ARB_vertex_attrib_binding)が無い・形式が BLOCK でない・自分の索引を持つ区画(半透明の並べ直し済み)。
 * 描画のスレッドだけで使う。devbridge の {@code fastdraw on|off} で切り替えて比べる。
 */
public final class FastChunkDraw {
    public static volatile boolean enabled = true;

    private static int supported = -1; // -1 まだ調べていない・0 使えない・1 使える
    private static int vao;
    /** この層で、決まった器を今付けているか(バニラの器を付けたら false)。 */
    private static boolean bound;
    public static long fastBinds;
    public static long vanillaBinds;

    /** {@code renderSectionLayer} の始め。 */
    public static void beginLayer() {
        bound = false;
    }

    /**
     * バニラの {@code vertexBuffer.bind()} の代わり。続く {@code vertexBuffer.draw()} はバニラのまま(索引の数と型は区画のものを使う)。
     *
     * @return false ならバニラの {@code bind()} を呼ぶ
     */
    public static boolean bind(VertexBuffer buffer) {
        if (!enabled || !supported()) {
            return false;
        }
        VertexBufferAccessor access = (VertexBufferAccessor) buffer;
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        if (access.overgrown_vegetation$format() != DefaultVertexFormat.BLOCK || access.overgrown_vegetation$sequentialIndices() != quads) {
            bound = false;
            vanillaBinds++;
            return false;
        }
        if (!bound) {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            quads.bind(1); // 共通の四角形の索引をこの器に付ける(区画を送ったときに足りる大きさは確保済み。1 なら作り直さない)
            bound = true;
        }
        GL43C.glBindVertexBuffer(0, access.overgrown_vegetation$vertexBufferId(), 0L, DefaultVertexFormat.BLOCK.getVertexSize());
        fastBinds++;
        return true;
    }

    private static boolean supported() {
        if (supported < 0) {
            supported = 0;
            try {
                GLCapabilities caps = GL.getCapabilities();
                if (caps.OpenGL43 || caps.GL_ARB_vertex_attrib_binding) {
                    vao = createVao();
                    supported = 1;
                }
            } catch (Throwable t) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.warn("地形の区画を決まった器で描けない(バニラのまま): {}", t.toString());
            }
        }
        return supported == 1;
    }

    /** BLOCK の形式を書いた器。VertexFormatElement.Usage の付け方(正規化・整数)をそのまま写す。 */
    private static int createVao() {
        VertexFormat format = DefaultVertexFormat.BLOCK;
        int id = GL30C.glGenVertexArrays();
        GlStateManager._glBindVertexArray(id);
        List<VertexFormatElement> elements = format.getElements();
        for (int i = 0; i < elements.size(); i++) {
            VertexFormatElement e = elements.get(i);
            int offset = format.getOffset(e);
            int type = e.type().glType();
            GL20C.glEnableVertexAttribArray(i);
            switch (e.usage()) {
                case NORMAL, COLOR -> GL43C.glVertexAttribFormat(i, e.count(), type, true, offset);
                case UV -> {
                    if (type == GL11C.GL_FLOAT) {
                        GL43C.glVertexAttribFormat(i, e.count(), type, false, offset);
                    } else {
                        GL43C.glVertexAttribIFormat(i, e.count(), type, offset);
                    }
                }
                default -> GL43C.glVertexAttribFormat(i, e.count(), type, false, offset);
            }
            GL43C.glVertexAttribBinding(i, 0);
        }
        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        return id;
    }

    private FastChunkDraw() {
    }
}
