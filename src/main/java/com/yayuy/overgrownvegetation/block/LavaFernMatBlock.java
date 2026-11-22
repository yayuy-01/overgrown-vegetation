package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Tilt;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * <b>溶岩の浮き草</b> {@code lava_fern_mat}(2026-11-12 ネザーの先駆けの植物 3)。溶岩の際に浮かぶシダの敷物(アカウキクサ)。
 * 睡蓮の葉と同じく、溶岩の源の 1 つ上の空気に置く薄い敷物。燃えず、溶岩で壊れない(溶岩が流れ込めない)。光らない。
 * 段階 2 で下の溶岩が殻になっても殻の上に残り、段階 4 で殻が水になればそのまま水面に浮く。
 *
 * <p><b>乗ると沈む</b>(ユーザー決定 = 大きなドリップリーフと同じ動き): 乗って 10 tick で傾き({@code TILT=full})、
 * 100 tick 当たり判定が無くなってから戻る。Mob にも効く。模型は平ら(none・unstable)と傾き(full)の 2 つ。
 */
public class LavaFernMatBlock extends Block implements LiquidBlockContainer {


    /** none = 平ら / unstable = 乗られて傾きかけ(まだ立てる)/ full = 沈んで当たり判定なし。partial は使わない。 */
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Tilt> TILT = BlockStateProperties.TILT;

    /** 傾くまで(大きなドリップリーフの 10 tick)と、沈んでいる長さ(100 tick)。 */
    public static final int TILT_DELAY = 10;
    public static final int SUNK_TICKS = 100;

    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);
    private static final VoxelShape OUTLINE = Block.box(0.0, 0.0, 0.0, 16.0, 1.5, 16.0);

    public LavaFernMatBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TILT, Tilt.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TILT);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(TILT) == Tilt.FULL ? Shapes.empty() : SHAPE;
    }

    /** 下が溶岩の源・殻(冷えた殻・熱の結晶を含む殻)・水の源なら置ける。自分の所に液体があってはいけない。 */
    public static boolean canStandOn(LevelReader level, BlockPos below) {
        FluidState f = level.getFluidState(below);
        if (f.isSource() && (f.is(Fluids.LAVA) || f.is(Fluids.WATER))) return true;
        BlockState s = level.getBlockState(below);
        return s.is(ModBlocks.COOLED_CRUST.get()) || s.is(ModBlocks.HEAT_CRYSTAL_CRUST.get());
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && canStandOn(level, pos.below());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    // ---- 溶岩が流れ込めない(流れに壊されない) ----

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }

    // ---- 乗ると沈む ----

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide || state.getValue(TILT) != Tilt.NONE) return;
        if (!entity.onGround() || entity.position().y < pos.getY() + 0.03) return; // 上に立っている物だけ
        level.setBlock(pos, state.setValue(TILT, Tilt.UNSTABLE), Block.UPDATE_CLIENTS);
        level.scheduleTick(pos, this, TILT_DELAY);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Tilt tilt = state.getValue(TILT);
        if (tilt == Tilt.UNSTABLE) {
            level.setBlock(pos, state.setValue(TILT, Tilt.FULL), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.BIG_DRIPLEAF_TILT_DOWN, SoundSource.BLOCKS, 1.0F, 0.8F + random.nextFloat() * 0.2F);
            level.scheduleTick(pos, this, SUNK_TICKS);
        } else if (tilt != Tilt.NONE) {
            level.setBlock(pos, state.setValue(TILT, Tilt.NONE), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.BIG_DRIPLEAF_TILT_UP, SoundSource.BLOCKS, 1.0F, 0.8F + random.nextFloat() * 0.2F);
        }
    }
}
