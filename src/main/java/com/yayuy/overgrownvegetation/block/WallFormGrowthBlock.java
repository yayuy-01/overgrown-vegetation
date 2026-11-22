package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 壁の植物のうち、同じ id で絵だけ違う変種({@link #FORM})を持つもの(2026-11-08)。
 * <ul>
 *     <li>{@link Shape#FLUSH} 壁を這う小葉類 {@code wall_lycopsid}: 0 = 扇、1 = 床から続く最下段、2 = ヒカゲノカズラ。壁に貼る 1 枚の板。</li>
 *     <li>{@link Shape#HANGING} 垂れ苔 {@code hanging_moss}: 0 = 長い(地上・湿った壁)、1 = 短い(地上)、2 = 洞窟の灰緑。上の縁から手前へ 22.5° 傾けた板。</li>
 * </ul>
 * 付き方と剥がれ方は {@link CorruptedWallGrowthBlock} と同じ。手で置いたときは 0 番。
 */
public class WallFormGrowthBlock extends CorruptedWallGrowthBlock {

    public static final IntegerProperty FORM = IntegerProperty.create("form", 0, 2);

    public enum Shape { FLUSH, HANGING }

    private static final VoxelShape[] FLUSH_SHAPES = {
            Block.box(0, 0, 15, 16, 16, 16), Block.box(0, 0, 0, 16, 16, 1), Block.box(15, 0, 0, 16, 16, 16), Block.box(0, 0, 0, 1, 16, 16)};
    // 上の縁(y 16)から垂れて、下の端が壁から約 6 px 離れる
    private static final VoxelShape[] HANGING_SHAPES = {
            Block.box(0, 2, 10, 16, 16, 16), Block.box(0, 2, 0, 16, 16, 6), Block.box(10, 2, 0, 16, 16, 16), Block.box(0, 2, 0, 6, 16, 16)};

    private final Shape shape;

    public WallFormGrowthBlock(Shape shape, BlockBehaviour.Properties properties) {
        super(properties);
        this.shape = shape;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FORM, 0));
    }

    public static WallFormGrowthBlock flush(BlockBehaviour.Properties properties) {
        return new WallFormGrowthBlock(Shape.FLUSH, properties);
    }

    public static WallFormGrowthBlock hanging(BlockBehaviour.Properties properties) {
        return new WallFormGrowthBlock(Shape.HANGING, properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape[] s = shape == Shape.FLUSH ? FLUSH_SHAPES : HANGING_SHAPES;
        return switch (state.getValue(FACING)) {
            case SOUTH -> s[1];
            case WEST -> s[2];
            case EAST -> s[3];
            default -> s[0];
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FORM);
    }
}
