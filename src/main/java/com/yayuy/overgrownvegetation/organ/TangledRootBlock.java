package com.yayuy.overgrownvegetation.organ;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 絡む根(2026-11-20 崖の汚染 A1 太い縄の滝)。何本もの根が絡んだ塊で、マングローブの根のようにすき間が透ける(カットアウト)。
 * 器官の「絡む根」(器官の 9 つの 8)と共通の 1 つのブロック。崖の縄・急斜面の這う網・器官の井戸の根で使う。
 *
 * <p>当たりは全面(上に乗れる・足場になる)。<b>登れる</b>のは、横から体を押し付けたとき(クモと同じ「横にぶつかっている間」。
 * {@link com.yayuy.overgrownvegetation.corruption.cliff.RootClimb})。植物側の Mob はプレイヤーより速く登る。
 * 枯れの状態({@link #WITHERED})は器官で使うときのため(崖の縄は枯れない)。絵は仮(Fable の器官の案 A の絡む根を透かした物)。
 */
public class TangledRootBlock extends OrganTissueBlock {

    public static final MapCodec<TangledRootBlock> CODEC = simpleCodec(TangledRootBlock::new);

    public TangledRootBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** マングローブの根と同じく、縦に重なった絡む根どうしの面は描かない(横の面は透けて重なりが見える)。 */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction dir) {
        return adjacent.is(this) && dir.getAxis() == Direction.Axis.Y;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
