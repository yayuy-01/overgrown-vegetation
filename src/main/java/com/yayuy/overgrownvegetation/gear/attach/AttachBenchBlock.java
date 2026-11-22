package com.yayuy.overgrownvegetation.gear.attach;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * <b>付け物の台</b>(2026-11-21 夜 ユーザー「作業台のような専用の台で」)。右クリックで画面を開き、防具を置くと枠 3 つが見え、付け物をはめる・外す。
 * 作業台と同じく中に物を残さない(閉じると防具は持ち物へ戻る)。名前・形・作り方は【仮】。
 */
public class AttachBenchBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<AttachBenchBlock> CODEC = simpleCodec(AttachBenchBlock::new);
    private static final Component TITLE = Component.translatable("container.overgrown_vegetation.attachment_bench");

    public AttachBenchBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        player.openMenu(state.getMenuProvider(level, pos));
        return InteractionResult.CONSUME;
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inv, p) -> new AttachBenchMenu(id, inv, ContainerLevelAccess.create(level, pos)), TITLE);
    }
}
