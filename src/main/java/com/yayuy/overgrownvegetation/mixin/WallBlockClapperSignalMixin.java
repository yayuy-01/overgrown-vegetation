package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.defense.ClapperNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 鳴子が鳴ったとき、紐を結んだ柱(WallBlock)からレッドストーン信号 15 を出す(2026-10-15 ユーザー指定「結んだ柱から信号」)。
 * バニラの WallBlock は {@code getSignal} を持たないので、ここで<b>足す</b>(すべてのブロックが通る場所は重くなるので避けた)。
 * 鳴っていなければ {@link ClapperNetwork#signalAt} がすぐ 0 を返す。{@code isSignalSource} は変えないので、
 * レッドストーンの粉の線が柱へ向かって伸びる見た目は変わらない(信号はそのまま届く)。
 * WallBlock を継がない MOD の柵・塀は信号を出さない(知っている穴)。
 */
@Mixin(WallBlock.class)
public abstract class WallBlockClapperSignalMixin {

    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return ClapperNetwork.signalAt(level, pos);
    }
}
