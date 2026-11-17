package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.perf.PlantPushThin;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 植物側の押し合いを間引く({@link PlantPushThin}、2026-11-18 大群のための軽量化 B の ②b。<b>試し: 既定は切ってある</b>)。
 * {@code LivingEntity#aiStep} の中の {@code pushEntities()} の呼び出しを包む(種ごとの上書き(SmallCrawler など)ごと止められる)。
 */
@Mixin(LivingEntity.class)
public abstract class PlantPushThinMixin {

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;pushEntities()V"))
    private void overgrown_vegetation$thinPlantPush(LivingEntity self, Operation<Void> original) {
        if (!PlantPushThin.skip(self)) {
            original.call(self);
        }
    }
}
