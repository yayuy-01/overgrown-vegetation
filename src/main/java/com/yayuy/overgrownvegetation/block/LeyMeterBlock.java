package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 地脈計(2026-10-24、地脈技術の 4 段目。Vault「序盤の遊び方」やること 8)。電池の要らない観測の計器。
 * その場の地脈の流れの強さ(線への近さ 0〜100%、汲み上げ器の効率と同じ尺度 {@link LeyPumpBlockEntity#efficiencyAt})を
 * 文字盤の針で示す(針は {@link com.yayuy.overgrownvegetation.client.LeyMeterRenderer})。F1 の前の前兆の間は針が小刻みに揺れ、
 * 右クリックの表示に「不安定」が付く。置くと文字盤が置いた人の方を向く。中身は {@link LeyMeterBlockEntity}。
 * 後で胞子警報器へ改造する予定(今は作らない)。
 */
public class LeyMeterBlock extends BaseEntityBlock {

    public static final MapCodec<LeyMeterBlock> CODEC = simpleCodec(LeyMeterBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE_NS = Shapes.or(Block.box(3, 0, 4, 13, 2, 12), Block.box(2, 2, 6, 14, 15, 10));
    private static final VoxelShape SHAPE_EW = Shapes.or(Block.box(4, 0, 3, 12, 2, 13), Block.box(6, 2, 2, 10, 15, 14));

    public LeyMeterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
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
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyMeterBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.LEY_METER.get(), LeyMeterBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player && level instanceof ServerLevel
                && level.getBlockEntity(pos) instanceof LeyMeterBlockEntity meter) {
            meter.refreshNow();
            report(player, meter);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof LeyMeterBlockEntity meter) {
            meter.refreshNow();
            report(player, meter);
            level.playSound(null, pos, SoundEvents.COPPER_HIT, SoundSource.BLOCKS, 0.6F, 1.6F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 「地脈の流れ: 72%(安定)」。前兆の間は今の揺れた値と「不安定」。 */
    static void report(Player player, LeyMeterBlockEntity meter) {
        boolean unstable = meter.isUnstable();
        int percent = (int) Math.round(meter.reading() * 100.0D);
        ChatFormatting color = percent >= 60 ? ChatFormatting.AQUA : percent >= 25 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY;
        Component state = Component.translatable(unstable
                        ? "message.overgrown_vegetation.ley_meter.unstable" : "message.overgrown_vegetation.ley_meter.stable")
                .withStyle(unstable ? ChatFormatting.GOLD : color);
        player.displayClientMessage(Component.translatable("message.overgrown_vegetation.ley_meter.reading", percent, state)
                .withStyle(color), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_meter").withStyle(ChatFormatting.GRAY));
    }
}
