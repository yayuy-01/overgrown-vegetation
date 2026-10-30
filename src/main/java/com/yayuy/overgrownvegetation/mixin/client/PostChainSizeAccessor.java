package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 後処理が今どの大きさに合わせてあるか(private)。描画解像度の縮小の確かめ用(2026-10-23、{@code client.RenderScale})。 */
@Mixin(PostChain.class)
public interface PostChainSizeAccessor {

    @Accessor("screenWidth")
    int ov$screenWidth();

    @Accessor("screenHeight")
    int ov$screenHeight();
}
