package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * フェーズ計測機(2026-09-19)。紙を持って右クリックすると紙を 1 枚消費し、
 * 現在のフェーズ・ポイント・収入・過繫茂チャンク累計を書いた「フェーズ記録紙」が出てくる
 * (紙のアイテムに名前と説明文を付けたもの。読むのはツールチップ)。
 * 手持ちの「フェーズ計測器」({@code item/PhaseMeterItem})はクリエイティブ専用のままで、
 * サバイバルではこの機械を研究で作る。
 */
public class PhaseRecorderBlock extends Block {

    public static final MapCodec<PhaseRecorderBlock> CODEC = simpleCodec(PhaseRecorderBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public PhaseRecorderBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
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
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!stack.is(Items.PAPER)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemInteractionResult.SUCCESS;
        }
        ItemStack report = writeReport(serverLevel);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        if (!player.getInventory().add(report)) {
            player.drop(report, false);
        }
        level.playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.1F);
        return ItemInteractionResult.CONSUME;
    }

    /** いまの状態を書いた記録紙を作る。 */
    public static ItemStack writeReport(ServerLevel level) {
        PhaseData data = PhaseData.get(level);
        PhaseTable phases = DesignData.phases();
        int phase = data.getPhase();
        long points = data.getPoints();
        long chunks = data.getOvergrownChunks();
        long day = level.getDayTime() / 24000L + 1L;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.translatable("item.overgrown_vegetation.phase_report.day", day)
                .withStyle(ChatFormatting.GRAY));
        if (phase <= 0) {
            lore.add(Component.translatable("item.overgrown_vegetation.phase_report.phase0",
                    data.getStartAdvancementCount(), phases.startAdvancements()).withStyle(ChatFormatting.GRAY));
        } else {
            lore.add(Component.translatable("item.overgrown_vegetation.phase_report.phase",
                    Component.translatableWithFallback("message.overgrown_vegetation.phase_name." + phase, "Phase " + phase))
                    .withStyle(ChatFormatting.GREEN));
            long need = phases.pointsToAdvance(phase + 1);
            if (need != Long.MAX_VALUE) {
                int percent = (int) Math.min(100L, points * 100L / Math.max(1L, need));
                lore.add(Component.translatable("item.overgrown_vegetation.phase_report.points",
                        String.format("%,d", points), String.format("%,d", need), percent).withStyle(ChatFormatting.GRAY));
            } else {
                lore.add(Component.translatable("item.overgrown_vegetation.phase_report.points_only",
                        String.format("%,d", points)).withStyle(ChatFormatting.GRAY));
            }
            double perTick = phases.pointsPerTickWithChunks(chunks);
            lore.add(Component.translatable("item.overgrown_vegetation.phase_report.income",
                    String.format("%,.0f", perTick * 1200.0D)).withStyle(ChatFormatting.GRAY));
        }
        lore.add(Component.translatable("item.overgrown_vegetation.phase_report.chunks",
                String.format("%,d", chunks)).withStyle(ChatFormatting.GRAY));
        if (data.isFrozen()) {
            lore.add(Component.translatable("item.overgrown_vegetation.phase_report.frozen").withStyle(ChatFormatting.AQUA));
        }

        ItemStack report = new ItemStack(Items.PAPER);
        report.set(DataComponents.CUSTOM_NAME,
                Component.translatable("item.overgrown_vegetation.phase_report.name", day).withStyle(ChatFormatting.YELLOW));
        report.set(DataComponents.LORE, new ItemLore(lore));
        return report;
    }
}
