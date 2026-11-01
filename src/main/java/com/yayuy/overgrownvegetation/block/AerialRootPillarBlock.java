package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 根付いた気根の柱(S3、2026-11-01)。気根のカーテン({@link AerialRootBlock})が地面に届いた先。
 * 当たり判定は満ちた 1 ブロック(通れない)だが、絵は根の間が抜けている(cutout・{@code noOcclusion})。
 * {@link AerialRootBlock#KIND} を同じ名前で持ち、真上の気根と同じ束 / 1 本の絵になる。落とす物は無い(ユーザー指定)。
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
}
