package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 光る輪郭とファビュラスの後処理(private)。描画解像度の縮小で、世界を描く間だけ縮めた大きさに合わせる(2026-10-23、{@code client.RenderScale})。 */
@Mixin(LevelRenderer.class)
public interface LevelRendererPostChainAccessor {

    @Accessor("entityEffect")
    PostChain ov$entityEffect();

    @Accessor("transparencyChain")
    PostChain ov$transparencyChain();
}
