package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.registry.ModLey;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
  * <b>地脈の杭</b>(押し返し 2026-11-18 夜 ユーザー決定・同じ夜の直しで「範囲の場」に)。電池の地脈の力(FE)がある限り、
 * 水平 {@code ring.stakeRadius}【仮 12】の中では、胞子の発芽で始まる汚染も、這ってくる汚染も起きない({@link RingWards})。
 * FE は受け取りだけの口(換気機と同じ)。
 */
public class LeyStakeBlock extends BaseEntityBlock {
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 16, 11);

    public LeyStakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyStakeBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModPushBack.LEY_STAKE_BE.get(), LeyStakeBlockEntity::serverTick);
    }

    /** 壊した(別のブロックになった)ら留守の覚えを忘れる(11-23。チャンクが外れたときは呼ばれない)。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) AbsentWards.forget(server, pos, false);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 1.20.1: useItemOn・useWithoutItem が無いので use 1 つで(線の巻きは PASS で巻きの側へ。素手の扱いは本流と同じく利き手だけ)。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(ModLey.WIRE.get())) return InteractionResult.PASS; // 線の巻きの側で扱う
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof LeyStakeBlockEntity be) {
            player.displayClientMessage(be.info((ServerLevel) level), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
