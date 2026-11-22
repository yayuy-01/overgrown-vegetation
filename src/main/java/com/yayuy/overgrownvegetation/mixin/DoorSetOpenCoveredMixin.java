package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.CoveredInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Mob(村人・襲撃者など)が覆われた扉を開けるときも時々引っかかる(2026-10-05〜10-30 の覆われた扉 covered_ の {@code setOpen} の続き。
 * 2026-10-30 に作り直し側 overgrown_vegetation/mod から持ってきた)。
 * Mob の扉の開け閉め({@code InteractWithDoor}・{@code DoorInteractGoal})はここを通り、プレイヤーの右クリックは通らない
 * ({@code CoveredInteractions} のイベントが受ける)。閉じるのは常にできる。地形の描画ではない。
 */
@Mixin(DoorBlock.class)
public abstract class DoorSetOpenCoveredMixin {

    @Inject(method = "setOpen", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$stuckWhenCovered(@Nullable Entity entity, Level level, BlockState state, BlockPos pos, boolean open,
                                                       CallbackInfo ci) {
        if (open && !level.isClientSide && CoveredInteractions.mobOpenBlocked(entity, level, state, pos, true, level.random.nextDouble())) {
            ci.cancel();
        }
    }
}
