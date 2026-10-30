package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * シェーダーに渡したテクスチャ(private)。バニラの地形のシェーダーへ渡した自前のテクスチャを、Sodium の地形のシェーダーへも結ぶ
 * (2026-10-22、{@code client.shader.SodiumTerrain})。
 */
@Mixin(ShaderInstance.class)
public interface ShaderInstanceSamplerAccessor {

    @Accessor("samplerMap")
    Map<String, Object> ov$samplerMap();
}
