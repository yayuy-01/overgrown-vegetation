package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>覆われた砂・砂利は落ちない</b>(2026-11-01 ユーザー報告「海底で砂・砂利が覆われないことが多発」の直し。設定上は「根が張って固まる」)。
 * 覆いは元のブロックの上の記録なので、落ちると記録は元の場所に残り(資格を失って捨てられる)、落ちた先の砂は素のままになっていた。
 * 落ちる砂は {@code onPlace}・{@code updateShape} で予約した tick の {@code tick} で落下の実体になるので、そこで止める。
 * 覆いを剥がした(ハサミ・工兵の整備)・汚染が戻ったあとは、次に隣が変わったときに普通に落ちる。
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockCoveredMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$stayWhenCovered(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (OvergrownLayerData.isCovered(level, pos)) {
            ci.cancel();
        }
    }
}
