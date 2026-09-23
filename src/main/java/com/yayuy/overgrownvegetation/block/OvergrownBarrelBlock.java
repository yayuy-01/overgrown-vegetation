package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 「過繫茂した樽」。樽が汚染されると中身を保ったままこれになる({@link OvergrownContainers#placeConverted})。
 * 開けるときに確率で開かない/ハサミで剥がす/時間で再繁茂、の挙動は {@link OvergrownContainers}。
 * 中身は {@link OvergrownBarrelBlockEntity}(バニラの樽と同じ 27 スロット)。
 */
public class OvergrownBarrelBlock extends BarrelBlock {

    public OvergrownBarrelBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(OvergrownContainers.OVERGROWTH, OvergrownContainers.MAX_LEVEL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OvergrownContainers.OVERGROWTH);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(OvergrownContainers.OVERGROWTH, 0);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OvergrownBarrelBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!OvergrownContainers.tryOpen(level, pos, state, player)) {
            return InteractionResult.CONSUME;
        }
        if (level.getBlockEntity(pos) instanceof OvergrownBarrelBlockEntity barrel) {
            player.openMenu(barrel);
            player.awardStat(Stats.OPEN_BARREL);
            PiglinAi.angerNearbyPiglins(player, true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // バニラの BarrelBlock#tick は BarrelBlockEntity にしか反応しないので、こちらの BlockEntity 用に置き換える。
        if (level.getBlockEntity(pos) instanceof OvergrownBarrelBlockEntity barrel) {
            barrel.recheckOpen();
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        ItemInteractionResult sheared = OvergrownContainers.tryShear(stack, state, level, pos, player, hand,
                OvergrownContainers::setSingle);
        if (sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return sheared;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        OvergrownContainers.regrow(state, level, pos, random, OvergrownContainers::setSingle);
    }
}
