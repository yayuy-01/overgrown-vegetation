package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 葉どうしの面の省き({@link OvergrownLeavesBlock#faceCull} = 2、2026-10-24)は 2 つ先の葉を見るので、葉が置かれた・壊れたときは
 * 2 ブロック先まで組み直す(バニラの {@code setBlockDirty} は 1 ブロック先の区画まで。区画の境目で古い面・穴が残る)。
 * {@code blockChanged} は Sodium が上書きしないので、Sodium でもここを通る({@code setBlocksDirty} は Sodium のものが呼ばれる)。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererLeafCullMixin {

    @Inject(method = "blockChanged", at = @At("TAIL"), require = 0)
    private void overgrownvegetation$leafCullDirty(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags,
                                                   CallbackInfo ci) {
        if (OvergrownLeavesBlock.faceCull == 2
                && (oldState.getBlock() instanceof OvergrownLeavesBlock || newState.getBlock() instanceof OvergrownLeavesBlock)) {
            ((LevelRenderer) (Object) this).setBlocksDirty(pos.getX() - 2, pos.getY() - 2, pos.getZ() - 2, pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2);
        }
    }
}
