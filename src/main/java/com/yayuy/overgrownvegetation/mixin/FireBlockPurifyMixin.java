package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.purify.HiveFireGuard;
import com.yayuy.overgrownvegetation.purify.VanillaFire;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ふつうの火で汚染を焼く({@link VanillaFire}、2026-10-08 夜。10-09 朝に燃え尽き方も足した)。
 * <ul>
 *     <li>火の更新の頭: 汚染された地面を元のブロック・焼けた土に変えることがあり、汚染の上・そばの火は消えやすい。消したときはバニラの残りの処理
 *     (燃え広がり・次の予約)を飛ばす。</li>
 *     <li>隣のマスを燃やすところ({@code checkBurnOut}): この MOD が扱うマスはバニラの「消すか火にする」の代わりに、元のブロックへ戻す・焼けた土にする・
 *     宙に残った木を燃やす({@link VanillaFire#onBurnOut})。ハイヴノードのまわりでは、どのマスも燃えた後に火を残さない。</li>
 *     <li>空いたマスへ火が飛び移る強さ({@code getIgniteOdds}): ハイヴノードのまわりでは 0(燃え広がらない。{@link HiveFireGuard}、2026-10-10)。</li>
 * </ul>
 */
@Mixin(FireBlock.class)
public abstract class FireBlockPurifyMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$purifyFire(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                                 CallbackInfo ci) {
        if (VanillaFire.onFireTick(level, pos, random)) {
            ci.cancel();
        }
    }

    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$burnOut(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face,
                                              CallbackInfo ci) {
        if (VanillaFire.onBurnOut(level, pos, chance, random, age, face)) {
            ci.cancel();
        }
    }

    /** 飛び移れる強さが 0 より大きいときだけ範囲を見る(ほとんどのマスは燃える物が隣に無く 0 のまま返る)。 */
    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void overgrown_vegetation$noSpreadNearHive(LevelReader level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() > 0 && level instanceof ServerLevel server && HiveFireGuard.protects(server, pos)) {
            cir.setReturnValue(0);
        }
    }
}
