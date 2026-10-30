package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.light.DynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

/**
 * Mob・プレイヤー・落ちている物などの明るさに動く光({@link DynamicLights})を足す(2026-10-15、琥珀の角灯)。
 * {@code getPackedLightCoords} は final なので、描き方ごとの上書き(ブレイズなど)もここを通る。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererDynamicLightMixin {

    // 2026-10-20: エンティティごと・毎フレーム。動く光が無ければ位置も作らない(答えは同じ)
    @ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
    private int overgrown_vegetation$dynamicLight(int packed, @Local(argsOnly = true) Entity entity, @Local(argsOnly = true) float partialTicks) {
        if (!DynamicLights.any()) {
            return packed;
        }
        return DynamicLights.brighten(packed, BlockPos.containing(entity.getLightProbePosition(partialTicks)));
    }
}
