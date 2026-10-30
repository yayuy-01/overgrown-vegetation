package com.yayuy.overgrownvegetation.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 1 ブロックで寝られる物の土台(2026-10-16、㊹ 寝袋・⑮ 天幕)。NeoForge の「ベッドの代わり」の決まりに合わせる:
 * {@link #isBed} を真にする・バニラの起き上がりが {@code BedBlock.FACING} を直に読むので向きを持つ・使用中は {@link #OCCUPIED}。
 * 右クリックでバニラの {@code Player#startSleepInBed} を呼ぶ(昼・夜の判定、敵の判定、復活地点はバニラの流れ。寝袋はイベントで変える)。
 */
public abstract class SleepingMatBlock extends HorizontalDirectionalBlock {

    public static final BooleanProperty OCCUPIED = BlockStateProperties.OCCUPIED;

    protected SleepingMatBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH).setValue(OCCUPIED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OCCUPIED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable LivingEntity sleeper) {
        return true;
    }

    @Override
    public void setBedOccupied(BlockState state, Level level, BlockPos pos, LivingEntity sleeper, boolean occupied) {
        if (state.hasProperty(OCCUPIED)) {
            level.setBlock(pos, state.setValue(OCCUPIED, occupied), Block.UPDATE_ALL);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.CONSUME;
        }
        if (state.getValue(OCCUPIED)) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.bedroll.occupied").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.SUCCESS;
        }
        player.startSleepInBed(pos).ifLeft(problem -> {
            if (problem.getMessage() != null) {
                player.displayClientMessage(problem.getMessage(), true);
            }
        });
        return InteractionResult.SUCCESS;
    }
}
