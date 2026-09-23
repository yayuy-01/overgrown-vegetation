package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 「過繫茂したドア」。木のドアが汚染されると(上下 2 マスまとめて)これになる。
 * 蔦とコケに覆われているので、開けようとしても確率で開かない。ハサミで剥がすか置き直せば開けやすくなるが、
 * 時間が経つとまた覆われる。共通の挙動は {@link OvergrownContainers} を参照。
 *
 * <p>上下 2 マスの {@link OvergrownContainers#OVERGROWTH} は常に同じ値にする
 * ({@link #setBothHalves})。バニラの {@link DoorBlock#updateShape} は相方の状態をそのまま
 * コピーするので、片方を普通に {@code setBlock} すれば形状更新で揃うが、明示的に両方書く方が確実。
 *
 * <p>開閉の可否はサーバーだけが決める。クライアント側の {@code useWithoutItem} は(覆われているときは)
 * 状態を先読みで書き換えず {@link InteractionResult#SUCCESS} だけ返す — バニラのようにクライアントが
 * 先に開けてしまうと、サーバーが「開かなかった」と判断したときに同期がずれるため。
 */
public class OvergrownDoorBlock extends DoorBlock {

    public OvergrownDoorBlock(Properties properties) {
        super(BlockSetType.OAK, properties);
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
        // 置き直したドアはきれいな状態(段階 0)から始まる。
        return state == null ? null : state.setValue(OvergrownContainers.OVERGROWTH, 0);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        boolean opening = !state.getValue(OPEN);
        if (opening && state.getValue(OvergrownContainers.OVERGROWTH) > 0) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            if (!OvergrownContainers.tryOpen(level, pos, state, player)) {
                return InteractionResult.CONSUME;
            }
        }
        return super.useWithoutItem(state, level, pos, player, hitResult);
    }

    @Override
    public void setOpen(@Nullable Entity entity, Level level, BlockState state, BlockPos pos, boolean open) {
        // Mob(ゾンビ・村人など)がドアを開けるときも同じ確率で引っかかる。閉じるのは常に可。
        if (open && !state.getValue(OPEN) && !level.isClientSide
                && !OvergrownContainers.tryOpen(level, pos, state, null)) {
            return;
        }
        super.setOpen(entity, level, state, pos, open);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        ItemInteractionResult sheared = OvergrownContainers.tryShear(stack, state, level, pos, player, hand,
                OvergrownDoorBlock::setBothHalves);
        if (sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return sheared;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // 上下どちらのマスがランダムティックを受けても再繁茂が進む(段階は両方揃える)。
        OvergrownContainers.regrow(state, level, pos, random, OvergrownDoorBlock::setBothHalves);
    }

    /** 上下 2 マスの覆われ具合をまとめて書き換える。 */
    public static void setBothHalves(Level level, BlockPos pos, BlockState state, int overgrowth) {
        level.setBlock(pos, state.setValue(OvergrownContainers.OVERGROWTH, overgrowth), Block.UPDATE_ALL);
        if (!state.hasProperty(HALF)) {
            return;
        }
        BlockPos otherPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState other = level.getBlockState(otherPos);
        if (other.is(state.getBlock()) && other.hasProperty(OvergrownContainers.OVERGROWTH)
                && other.getValue(OvergrownContainers.OVERGROWTH) != overgrowth) {
            level.setBlock(otherPos, other.setValue(OvergrownContainers.OVERGROWTH, overgrowth), Block.UPDATE_ALL);
        }
    }
}
