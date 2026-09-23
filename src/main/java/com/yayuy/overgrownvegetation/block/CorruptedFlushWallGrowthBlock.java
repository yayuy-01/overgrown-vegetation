package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@link CorruptedWallGrowthBlock} のうち、壁の外へせり出さず表面に薄く張り付く見た目のもの。
 *
 * <p>コライダーもモデル(壁面ぴったりの1枚板)に合わせて、壁側の面から1ピクセル分の
 * 薄い直方体にしてある。それ以外の当たり判定・設置ロジックは親クラスと同じ
 * (=バニラの蔦と同様、支えている面が無くなると剥がれ落ちる)。
 */
public class CorruptedFlushWallGrowthBlock extends CorruptedWallGrowthBlock {

    public static final MapCodec<CorruptedFlushWallGrowthBlock> CODEC = simpleCodec(CorruptedFlushWallGrowthBlock::new);

    private static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 15.0, 16.0, 16.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 1.0);
    private static final VoxelShape WEST_SHAPE = Block.box(15.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 0.0, 0.0, 1.0, 16.0, 16.0);

    public CorruptedFlushWallGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }
}
