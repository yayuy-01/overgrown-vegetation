package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>根付いた萌芽体</b>(2026-10-23 胞子の場と風 第 2 段 F)。萌芽体(id はラフモデルが来るまで blight_fern)が湿った所へ歩いて
 * 根を下ろした姿。上へ胞子を噴き(見た目は胞子腺の噴き出しを流用)、置かれた所から汚染の根を伸ばす({@link SporeSprouts#addRootedPoint}、旧 汚染シダと同じ半径まで広げ続ける)。
 * その升の放出は胞子の場の「萌芽体の数」で増える(【決定】10-22 追記 3 の (1))。壊すと数が 1 減る。
 * 見た目(2026-10-23 萌芽体のモデル)は歩く萌芽体と同じ模型を GeckoLib で描く({@link BlightFernRootedBlockEntity})。
 * ブロックの絵 blight_fern_rooted.png は持ち物のアイコンと壊したときの粒だけ(仮、docs/TEXTURE_TODO.md)。
 */
public class BlightFernRootedBlock extends Block implements net.minecraft.world.level.block.EntityBlock,
        net.minecraft.world.level.block.SimpleWaterloggedBlock {

    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 12.0D, 13.0D);
    /** 水の中に根付いた株(2026-10-24 追記 18: 萌芽体は水底でも根付く)。 */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty WATERLOGGED =
            net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

    public BlightFernRootedBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
    }

    /** 置く所が水源なら水の中の姿に(萌芽体が根付く・芽を置く所から)。 */
    public BlockState stateFor(LevelReader level, BlockPos pos) {
        return this.defaultBlockState().setValue(WATERLOGGED, level.getFluidState(pos).getType() == net.minecraft.world.level.material.Fluids.WATER);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return stateFor(context.getLevel(), context.getClickedPos());
    }

    @Override
    protected net.minecraft.world.level.material.FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? net.minecraft.world.level.material.Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
                                     BlockPos pos, BlockPos facingPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, net.minecraft.world.level.material.Fluids.WATER, net.minecraft.world.level.material.Fluids.WATER.getTickDelay(level));
        }
        return facing == Direction.DOWN && !canSurvive(state, level, pos)
                ? (state.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState())
                : super.updateShape(state, facing, facingState, level, pos, facingPos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            SporeSprouts.addRootedPoint(server, pos);
        }
    }

    /**
     * プレイヤーが壊したときだけ、その升の萌芽体の数を 1 減らす(汚染の書き換え・足元が崩れて消えたときは減らさない ——
     * 根付いた萌芽体のまわりは自分の根で汚染されるので、書き換えで消えるたびに減ると数がすぐ 0 になる。テストクライアントで確認)。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.player.Player player) {
        if (!level.isClientSide) {
            SporeFieldServer.onSproutRemoved(pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // 胞子の噴き出しは胞子嚢がしぼむ瞬間に描画側から(BlightFernRootedPuffs。10-30 に parked/ へ退避、10-31 に戻した)

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlightFernRootedBlockEntity(pos, state);
    }

    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.ENTITYBLOCK_ANIMATED;
    }
}
