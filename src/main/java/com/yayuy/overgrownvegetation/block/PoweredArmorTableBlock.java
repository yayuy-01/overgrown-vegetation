package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 「パワードアーマー作成テーブル」(順 1 の着手順 5、2026-10-03)。右クリックで
 * {@link PoweredArmorTableMenu} を開く。作成・設計図・スキルツリー・装着をタブで切り替える 1 台。
 *
 * <p>中身を持たないので、バニラの作業台・鍛冶台と同じくブロックエンティティは無し
 * ({@link #getMenuProvider} で開く)。見た目はバニラの鍛冶台の絵の仮置き(研究台が作業台の絵なのと同じ扱い)。
 */
public class PoweredArmorTableBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<PoweredArmorTableBlock> CODEC = simpleCodec(PoweredArmorTableBlock::new);
    private static final Component TITLE = Component.translatable("container.overgrown_vegetation.powered_armor_table");

    public PoweredArmorTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            player.openMenu(state.getMenuProvider(level, pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((containerId, inventory, player) ->
                new PoweredArmorTableMenu(containerId, inventory, ContainerLevelAccess.create(level, pos)), TITLE);
    }
}
