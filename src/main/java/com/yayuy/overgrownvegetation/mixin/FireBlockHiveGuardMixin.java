package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.pushback.HiveFireGuard;
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
 * ハイヴノードのまわりでは火が燃え広がらない(10-10 ユーザー指定を 11-18 夜に戻した。{@link HiveFireGuard})。
 * <ul>
 *     <li>空いたマスへ火が飛び移る強さ({@code getIgniteOdds})を 0 に(外から範囲の中へも飛ばない)。</li>
 *     <li>隣のマスが燃え尽きたとき({@code checkBurnOut})、範囲の中ではそこに火を残さない(燃え尽きるのは今までどおり)。</li>
 *     <li>汚染の地面は燃え尽きると燻る灰ブロック。原木が燃え尽きたら幹が焼け切れたかを見る({@link com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn}。11-18 夜)。</li>
 * </ul>
 */
@Mixin(FireBlock.class)
public abstract class FireBlockHiveGuardMixin {

    /** 飛び移れる強さが 0 より大きいときだけ範囲を見る(ほとんどのマスは 0 のまま返る)。 */
    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void overgrown_vegetation$noSpreadNearHive(LevelReader level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() > 0 && level instanceof ServerLevel server && HiveFireGuard.protects(server, pos)) {
            cir.setReturnValue(0);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<BlockState> overgrown_vegetation$burning = new ThreadLocal<>();

    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$burnOut(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face,
                                              CallbackInfo ci) {
        if (!(level instanceof ServerLevel server)) return;
        BlockState state = level.getBlockState(pos);
        boolean hive = HiveFireGuard.protects(server, pos);
        if (hive || com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.scorches(state)) {
            if (random.nextInt(chance) < state.getFlammability(level, pos, face)) {
                if (com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.scorches(state)) {
                    // 汚染の地面は燃え尽きると燻る灰ブロック(10-08 夜のユーザー指定。空気にしない)
                    level.setBlock(pos, com.yayuy.overgrownvegetation.corruption.pushback.SmolderingAshBlock.from(state), 3);
                    com.yayuy.overgrownvegetation.corruption.pushback.LedgerShrink.touch(level, pos,
                            com.yayuy.overgrownvegetation.corruption.pushback.LedgerShrink.Cause.FIRE);
                } else {
                    state.onCaughtFire(level, pos, face, null);
                    level.removeBlock(pos, false); // ハイヴのまわり: 火を残さない
                    if (com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.isWood(state)) {
                        com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.onLogConsumed(server, pos);
                    }
                }
            }
            ci.cancel();
            return;
        }
        overgrown_vegetation$burning.set(com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.isWood(state) ? state : null);
    }

    /** 原木が燃え尽きたら(火が残っても消えても)、幹が焼け切れたかを見る(段 B)。 */
    @Inject(method = "checkBurnOut", at = @At("RETURN"))
    private void overgrown_vegetation$afterBurnOut(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face,
                                                   CallbackInfo ci) {
        BlockState before = overgrown_vegetation$burning.get();
        overgrown_vegetation$burning.remove();
        if (before != null && level instanceof ServerLevel server && !level.getBlockState(pos).is(before.getBlock())) {
            com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.onLogConsumed(server, pos);
        }
    }
}
