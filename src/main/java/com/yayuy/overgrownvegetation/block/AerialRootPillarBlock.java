package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 根付いた気根の柱(S3、2026-11-01)。気根のカーテン({@link AerialRootBlock})が地面に届いた先。
 * 当たり判定は満ちた 1 ブロック(通れない)だが、絵は根の間が抜けている(cutout・{@code noOcclusion})。
 * {@link AerialRootBlock#KIND} を同じ名前で持ち、真上の気根と同じ束 / 1 本の絵になる。落とす物は無い(ユーザー指定)。
 *
 * <p>支え(2026-11-03、相談役の仮決め): 真上が気根のカーテンか柱(林冠のすぐ下に 1 段だけの柱はカーテンと同じく下向きの面が固いブロック)。
 * 上のカーテンを切ると、カーテンが上から順に崩れ、最後に柱も消える(地面に切り株として残らない)。
 */
public class AerialRootPillarBlock extends Block {

    public static final MapCodec<AerialRootPillarBlock> CODEC = simpleCodec(AerialRootPillarBlock::new);
    public static final EnumProperty<AerialRootBlock.Kind> KIND = AerialRootBlock.KIND;

    public AerialRootPillarBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, AerialRootBlock.Kind.BUNDLE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        return above.getBlock() instanceof AerialRootBlock || above.is(this) || above.isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
