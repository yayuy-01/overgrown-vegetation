package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.client.light.DynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mob・プレイヤー・落ちている物などの明るさに動く光({@link DynamicLights})を足す(2026-10-15 琥珀の角灯、11-01 地下モードの試作で戻した)。
 * {@code getPackedLightCoords} は final なので、描き方ごとの上書き(ブレイズなど)も一人称の手に持った物もここを通る。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererDynamicLightMixin {

    // エンティティごと・毎フレーム。動く光が無ければ位置も作らない
    @ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
    private int overgrown_vegetation$dynamicLight(int packed, @Local(argsOnly = true) Entity entity, @Local(argsOnly = true) float partialTicks) {
        if (!DynamicLights.any()) {
            return packed;
        }
        return DynamicLights.brighten(packed, entity.getLightProbePosition(partialTicks));
    }
}
