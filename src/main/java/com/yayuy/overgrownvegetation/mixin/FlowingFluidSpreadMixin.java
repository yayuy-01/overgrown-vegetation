package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.perf.FluidSpreadShortcut;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 水・溶岩の広がりの近道({@link FluidSpreadShortcut}、2026-10-06 夜)。
 *
 * <p>{@code FlowingFluid#getSpread} は横の各マスで {@code getNewLiquid(level, 横のマス, 横のブロック)} を計算してから
 * {@code canPassThrough(level, その液体の種類, pos, state, 向き, 横のマス, 横のブロック, 横の液体)} を調べる。
 * {@code canPassThrough} は {@code !isSourceBlockOfThisType(横の液体) && canPassThroughWall(…) && canHoldFluid(…, 液体の種類)} で、
 * 液体の種類を使うのは {@code canHoldFluid} の {@code LiquidBlockContainer} の枝だけ。それ以外の枝で偽と決まるマスでは
 * {@code getNewLiquid} の答えは使われないので、計算せずに空の液体を返す(同じ項で {@code canPassThrough} が偽になる)。
 * 横の液体は {@code getSpread} が {@code 横のブロック.getFluidState()} を使っているので同じものを取り直す。
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidSpreadMixin {

    @Shadow
    private boolean isSourceBlockOfThisType(FluidState state) {
        throw new AssertionError();
    }

    @Shadow
    private boolean canPassThroughWall(Direction direction, BlockGetter level, BlockPos pos, BlockState state, BlockPos spreadPos, BlockState spreadState) {
        throw new AssertionError();
    }

    @Shadow
    private boolean canHoldFluid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        throw new AssertionError();
    }

    @WrapOperation(method = "getSpread",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FlowingFluid;getNewLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState overgrown_vegetation$skipBlockedNeighbour(FlowingFluid self, Level level, BlockPos spreadPos, BlockState spreadState,
                                                               Operation<FluidState> original,
                                                               @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) BlockState state) {
        if (!FluidSpreadShortcut.enabled()) {
            return original.call(self, level, spreadPos, spreadState);
        }
        Direction direction = Direction.fromDelta(spreadPos.getX() - pos.getX(), spreadPos.getY() - pos.getY(), spreadPos.getZ() - pos.getZ());
        boolean blocked = direction != null
                && (isSourceBlockOfThisType(spreadState.getFluidState())
                || !canPassThroughWall(direction, level, pos, state, spreadPos, spreadState)
                || (!(spreadState.getBlock() instanceof LiquidBlockContainer) && !canHoldFluid(level, spreadPos, spreadState, Fluids.EMPTY)));
        FluidSpreadShortcut.count(blocked);
        if (!blocked) {
            return original.call(self, level, spreadPos, spreadState);
        }
        if (FluidSpreadShortcut.checking()) {
            FluidState real = original.call(self, level, spreadPos, spreadState);
            boolean couldPass = !isSourceBlockOfThisType(spreadState.getFluidState())
                    && canPassThroughWall(direction, level, pos, state, spreadPos, spreadState)
                    && canHoldFluid(level, spreadPos, spreadState, real.getType());
            FluidSpreadShortcut.verified(!couldPass);
            return real;
        }
        return Fluids.EMPTY.defaultFluidState();
    }
}
