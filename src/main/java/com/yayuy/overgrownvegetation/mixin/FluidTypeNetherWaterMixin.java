package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.NetherWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 過繁茂したネザーでは水が蒸発しない(2026-10-02 ユーザー指定)。判定は {@link NetherWater#allowed}。
 * 液体の型ごとの「置くと蒸発するか」の入口(バケツ・ほかの MOD の液体の入れ物が使う)。
 */
@Mixin(value = FluidType.class, remap = false)
public class FluidTypeNetherWaterMixin {

    @Inject(method = "isVaporizedOnPlacement", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$keepWaterInOvergrownNether(Level level, BlockPos pos, FluidStack stack,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        if (NetherWater.allowed(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
