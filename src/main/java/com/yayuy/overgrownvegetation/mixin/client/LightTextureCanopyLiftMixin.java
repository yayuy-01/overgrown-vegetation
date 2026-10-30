package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.client.atmosphere.CanopyLift;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 過繁茂の森の中では、葉に削られた空の光をライトマップで少し持ち上げる(林床の持ち上げ。強さと理由は {@link CanopyLift})。
 *
 * <p>{@code LightTexture#updateLightTexture} の 16×16 の表で、空の光の段 i の明るさを読む {@code getBrightness(dimensionType, i)}(1 つ目。
 * 2 つ目はブロックの光)を、小数の段へ寄せた明るさに差し替える。ライトマップで変えるので、地形・Mob・粒・アイテムが同じ明るさになり、
 * 地形のシェーダーを置き換える Sodium でも効く。見た目だけ(サーバーの明るさ・湧き・作物は変わらない)。
 */
@Mixin(LightTexture.class)
public class LightTextureCanopyLiftMixin {

    @WrapOperation(
            method = "updateLightTexture",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F",
                    ordinal = 0))
    private float overgrown_vegetation$liftSkyUnderCanopy(DimensionType dimension, int level, Operation<Float> original) {
        float lift = CanopyLift.skyLift();
        if (lift <= 0.001F || level >= 15) {
            return original.call(dimension, level);
        }
        return CanopyLift.liftedSkyBrightness(dimension, level, lift);
    }
}
