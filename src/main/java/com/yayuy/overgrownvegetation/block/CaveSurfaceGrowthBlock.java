package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 洞窟の床・壁・天井のどこにでも取り付く、塊状の汚染植物(微光の胞子嚢・苔の塊)。
 *
 * <p>レバー・ボタンと同じ {@link FaceAttachedHorizontalDirectionalBlock} で、{@code face}(floor / wall /
 * ceiling)と {@code facing} で取り付き面を持つ。取り付いている面のブロックが無くなると剥がれ落ちる。
 * {@link com.yayuy.overgrownvegetation.corruption.CaveGrowth} が湿地・菌湿地帯の洞窟の露出面へ生やす。
 *
 * <p>当たり判定は床向きの箱 {@code floorShape} を基準に、壁・天井向きへ回した箱を作る
 * (幅は共通、厚みだけ取り付き面から測る)。
 */
public class CaveSurfaceGrowthBlock extends FaceAttachedHorizontalDirectionalBlock {

    public static final MapCodec<CaveSurfaceGrowthBlock> CODEC = simpleCodec(
            props -> new CaveSurfaceGrowthBlock(props, 3.0D, 7.0D));

    private final VoxelShape floorShape;
    private final VoxelShape ceilingShape;
    private final VoxelShape northShape;
    private final VoxelShape southShape;
    private final VoxelShape westShape;
    private final VoxelShape eastShape;

    /**
     * @param inset     箱の縁の内側への寄せ(px)。0 で 16 px 幅、3 で 10 px 幅
     * @param thickness 取り付き面からの厚み(px)
     */
    public CaveSurfaceGrowthBlock(BlockBehaviour.Properties properties, double inset, double thickness) {
        super(properties);
        double a = inset;
        double b = 16.0D - inset;
        double t = thickness;
        this.floorShape = Block.box(a, 0.0D, a, b, t, b);
        this.ceilingShape = Block.box(a, 16.0D - t, a, b, 16.0D, b);
        // face=wall, facing=north は南側のブロックに取り付いて北を向く(support = pos.south())。
        this.northShape = Block.box(a, a, 16.0D - t, b, b, 16.0D);
        this.southShape = Block.box(a, a, 0.0D, b, b, t);
        this.westShape = Block.box(16.0D - t, a, a, 16.0D, b, b);
        this.eastShape = Block.box(0.0D, a, a, t, b, b);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** 取り付き先の面の向き({@code direction} = 取り付き先ブロックから見てこのブロックがある方向)から状態を作る。 */
    public BlockState stateFor(Direction exposedDirection, Direction horizontalFacing) {
        BlockState state = this.defaultBlockState();
        return switch (exposedDirection) {
            case UP -> state.setValue(FACE, AttachFace.FLOOR).setValue(FACING, horizontalFacing);
            case DOWN -> state.setValue(FACE, AttachFace.CEILING).setValue(FACING, horizontalFacing);
            default -> state.setValue(FACE, AttachFace.WALL).setValue(FACING, exposedDirection);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> floorShape;
            case CEILING -> ceilingShape;
            default -> switch (state.getValue(FACING)) {
                case SOUTH -> southShape;
                case WEST -> westShape;
                case EAST -> eastShape;
                default -> northShape;
            };
        };
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 取り付いている面のブロックが無くなったら剥がれる(ドロップはルートテーブル任せ = 通常は無し)。
        if (getConnectedDirection(state).getOpposite() == direction && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canAttach(level, pos, getConnectedDirection(state).getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }
}
