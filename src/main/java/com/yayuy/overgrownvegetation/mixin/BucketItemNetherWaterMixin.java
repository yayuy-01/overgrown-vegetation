package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.corruption.NetherWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 過繁茂したネザーでは水バケツの水が蒸発しない(2026-10-02 ユーザー指定)。判定は {@link NetherWater#allowed}。
 * {@code BucketItem#emptyContents} は {@code FluidType#isVaporizedOnPlacement} のあとに
 * {@code level.dimensionType().ultraWarm()} をもう一度直接見るので、その呼び出しも差し替える。
 */
@Mixin(BucketItem.class)
public class BucketItemNetherWaterMixin {

    @WrapOperation(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            // 5 引数の emptyContents は Forge が足した物(SRG の名前が無い)ので remap = false。中の ultraWarm はバニラなので remap する。
            remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/dimension/DimensionType;ultraWarm()Z", remap = true))
    private boolean overgrown_vegetation$keepWaterInOvergrownNether(DimensionType type, Operation<Boolean> original,
                                                                    @Local(argsOnly = true) Level level,
                                                                    @Local(argsOnly = true) BlockPos pos) {
        if (NetherWater.allowed(level, pos)) {
            return false;
        }
        return original.call(type);
    }
}
