package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.shader.PortRender;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * <b>加算の光の描き方</b>(光の筋・木漏れ日・光る胞子が共通に使う。2026-10-30 作り直し。元は parked の {@code CanopyLight} の末尾)。
 * どれも過去に踏んだ罠の直し:
 * <ul>
 *     <li>{@link #buffers()}: その場で描き切る自前の器。{@code mc.renderBuffers().bufferSource()} は Iris が入っていると(パック無しでも)ワールドを
 *     描いている間 Iris のまとめ描きの器に替わり、カメラからの位置で作った頂点が動いている間 1 フレームずれた(地面すれすれの木漏れ日が沈む・浮く。2026-10-22)。</li>
 *     <li>{@link #drawToMainTarget()}: 「最高(ファビュラス)」では AFTER_PARTICLES の描き先が粒の層で、合成が a == 0 の画素を捨てるので、加算の薄い所が消えて
 *     帯の輪郭が八角形の縁に見えた(2026-10-24)。描く間だけ主画面へ。</li>
 *     <li>{@link #renderType()}: パック使用中は Complementary が POSITION_COLOR の色を {@code flat} で受け、帯が三角の市松になったので雷のシェーダー(2026-10-23)。</li>
 * </ul>
 * 型は色だけの頂点・加算(雷と同じ足し方)・両面・深さは読むが書かない。光の量(ブロックの明るさ)は変えない。
 */
final class AddLight {

    private static MultiBufferSource.BufferSource buffers;
    private static RenderType renderType;
    private static RenderType packRenderType;

    static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new BufferBuilder(1 << 18));
        }
        return buffers;
    }

    /** 「最高」で加算の光を主画面へ直に描く。描き先を替えたら true({@link #restoreTarget} に渡す)。ファンシー以下・パック使用中は何もしない。 */
    static boolean drawToMainTarget() {
        if (!Minecraft.useShaderTransparency() || ShaderPacks.active()) {
            return false;
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        return true;
    }

    /** {@link #drawToMainTarget} で替えた描き先を粒の層へ戻す(この後のほかの MOD の AFTER_PARTICLES のため)。 */
    static void restoreTarget(boolean switched) {
        if (switched) {
            PortRender.Shards.PARTICLES_TARGET.setupRenderState();
        }
    }

    static RenderType renderType() {
        if (ShaderPacks.active()) {
            if (packRenderType == null) {
                packRenderType = create("_canopy_light_pack", PortRender.Shards.RENDERTYPE_LIGHTNING_SHADER);
            }
            return packRenderType;
        }
        if (renderType == null) {
            renderType = create("_canopy_light", PortRender.Shards.POSITION_COLOR_SHADER);
        }
        return renderType;
    }

    private static RenderType create(String name, RenderStateShard.ShaderStateShard shader) {
        return RenderType.create(
                OvergrownVegetation.MODID + name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                16384,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(shader)
                        .setTransparencyState(PortRender.Shards.LIGHTNING_TRANSPARENCY)
                        .setCullState(PortRender.Shards.NO_CULL)
                        .setWriteMaskState(PortRender.Shards.COLOR_WRITE)
                        .createCompositeState(false));
    }

    private AddLight() {
    }
}
