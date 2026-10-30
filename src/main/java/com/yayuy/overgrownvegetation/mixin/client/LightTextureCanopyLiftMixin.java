package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.client.CanopyLight;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 過繁茂の森の中では、葉に削られた空の光をライトマップで少し持ち上げる(2026-10-17 ユーザー「木の密度を少しだけ上げて、その状態で木漏れ日や
 * 差し込む光が美しく見えれば完璧」。強さと理由は {@link CanopyLight#skyLift})。
 *
 * <p>{@code LightTexture#updateLightTexture} の 16×16 の表で、空の光の段 i の明るさを読む {@code getBrightness(dimensionType, i)}(1 つ目。
 * 2 つ目はブロックの光)を、小数の段へ寄せた明るさに差し替える。ライトマップで変えるので、地形・Mob・粒・アイテムが同じ明るさになり、
 * 地形のシェーダーを置き換える Sodium でも効く。見た目だけ(サーバーの明るさ・湧き・作物は変わらない)。
 * 極相の昼の暗さ({@link LightTextureClimaxMixin}、空の光の強さに掛ける)とは別の所なので両方効く。
 */
@Mixin(LightTexture.class)
public class LightTextureCanopyLiftMixin {

    @WrapOperation(
            method = "updateLightTexture",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F",
                    ordinal = 0))
    private float overgrown_vegetation$liftSkyUnderCanopy(DimensionType dimension, int level, Operation<Float> original) {
        float lift = CanopyLight.skyLift();
        if (lift <= 0.001F || level >= 15) {
            return original.call(dimension, level);
        }
        return CanopyLight.liftedSkyBrightness(dimension, level, lift);
    }
}
