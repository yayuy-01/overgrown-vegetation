package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 描画先の絵と奥行きのテクスチャ(protected)。描画解像度の縮小で主画面の中身を入れ替える(2026-10-23、{@code client.RenderScale})。 */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {

    @Accessor("colorTextureId")
    void ov$setColorTextureId(int id);

    @Accessor("depthBufferId")
    void ov$setDepthBufferId(int id);
}
