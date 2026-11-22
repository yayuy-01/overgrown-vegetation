package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 「過繫茂したチェスト」。チェストが汚染されると中身を保ったままこれになる
 * ({@link OvergrownContainers#placeConverted})。ラージチェストも組める(相手はこのブロック同士のみ)。
 * 開けるときに確率で開かない/ハサミで剥がす/時間で再繁茂、の挙動は {@link OvergrownContainers}。
 * 描画は {@link com.yayuy.overgrownvegetation.client.OvergrownChestRenderer}(バニラのチェストモデルを
 * 緑がかった色で描き、覆われ具合に応じて蔦・コケのオーバーレイを重ねる)。
 */
public class OvergrownChestBlock extends ChestBlock {

    public OvergrownChestBlock(Properties properties) {
        super(properties, ModBlockEntities.OVERGROWN_CHEST::get);
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
        return new OvergrownChestBlockEntity(pos, state);
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && !OvergrownContainers.tryOpen(level, pos, state, player)) {
            return InteractionResult.CONSUME;
        }
        return super.use(state, level, pos, player, InteractionHand.MAIN_HAND, hitResult);
    }

    @Nullable
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult sheared = OvergrownContainers.tryShear(stack, state, level, pos, player, hand,
                OvergrownContainers::setSingle);
        if (sheared != null) {
            return sheared;
        }
        return null;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        OvergrownContainers.regrow(state, level, pos, random, OvergrownContainers::setSingle);
    }

    /** 1.20.1: 1.21 の useItemOn → useWithoutItem(主の手だけ)の順を use 1 つで呼ぶ。useItemOn の null = 1.21 の PASS_TO_DEFAULT_BLOCK_INTERACTION。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult r = useItemOn(player.getItemInHand(hand), state, level, pos, player, hand, hitResult);
        if (r != null) {
            return r;
        }
        return hand == InteractionHand.MAIN_HAND ? useWithoutItem(state, level, pos, player, hitResult) : InteractionResult.PASS;
    }
}
