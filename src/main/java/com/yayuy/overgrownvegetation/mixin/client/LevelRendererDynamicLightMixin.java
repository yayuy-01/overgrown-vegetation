package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.light.DynamicLights;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

/**
 * ブロックの面の明るさに動く光({@link DynamicLights})を足す(2026-10-15、琥珀の角灯)。ブロック・水・NeoForge の光の計算は
 * すべてここを通る。チャンクを組み立てる別スレッドから呼ばれるので、読むのは差し替え済みの光源の一覧だけ。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDynamicLightMixin {

    // 2026-10-20: 区画の組み立てでブロックの面ごとに呼ばれるので、呼ぶたびに入れ物を作る @Inject(cancellable) をやめた(答えは同じ)
    @ModifyReturnValue(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"))
    private static int overgrown_vegetation$dynamicLight(int packed, @Local(argsOnly = true) BlockPos pos) {
        return DynamicLights.brighten(packed, pos);
    }
}
