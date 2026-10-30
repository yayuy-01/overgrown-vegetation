package com.yayuy.overgrownvegetation.covered;

import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.block.OvergrownDoorBlock;
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
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.ItemAbilities;

import javax.annotation.Nullable;

/**
 * 覆われたブロックのうち<b>手で動かすもの</b>(扉・トラップドア・フェンスゲート・ボタン・レバー)の、
 * 「蔦とコケに絡まって時々動かない」仕様(2026-10-05、ユーザー要望「過繫茂した扉の時折開かない仕様を持たせて」)。
 *
 * <p>仕組みも数値も<b>過繫茂したドア({@link OvergrownDoorBlock})と同じ</b>({@link OvergrownContainers}):
 * <ul>
 *     <li>覆われ具合 {@link OvergrownContainers#OVERGROWTH}(0〜3)に比例して動かない
 *     (最大 {@code overgrownContainerStuckChanceAtMax}、既定 75%)。<b>置いたときから最大</b>(2026-10-05 夜、ユーザー指定
 *     「最初から開かなくなったりする状態に」。以前は 0 から育った。0〜2 の古いものはランダムティックで育つ)。</li>
 *     <li><b>ハサミで植物を剥がすと元のブロックに戻る</b>(覆われる前のバニラの扉など。同じく夜のユーザー指定。以前は覆われ具合が 0 に戻った)。
 *     扉は上下とも、向き・開閉・蝶番・電源はそのまま({@link #shear})。過繫茂したドア(この MOD の木の扉)は今までどおり 0 に戻る。</li>
 *     <li>閉じる・戻す(ボタンが戻る・レバーを戻す)のは常にできる。レッドストーンでは引っかからない。
 *     寄生植物装備の 3 段階目は必ず動かせる。扉だけは Mob(村人・ゾンビ)が開けるときも引っかかる。</li>
 *     <li>開くかどうかはサーバーだけが決める —— 引っかかりうるときは、クライアントは状態を先読みで変えず成功だけ返す。</li>
 * </ul>
 *
 * <p>見た目は覆われ具合で<b>変わらない</b>(覆われたブロックの苔は元から全面。過繫茂したドアは段階で苔が増える)。
 * 鉄の扉・鉄のトラップドアは手で開かないので、ここではなくバニラの種類のまま作る({@link CoveredBlocks})。
 */
public final class CoveredInteractables {

    private CoveredInteractables() {
    }

    /**
     * 動かそうとした(開ける・押す・倒す)ときの関門。{@code null} = 元の処理へ進めてよい、それ以外 = その結果で終える。
     *
     * @param activating 開ける・押す向きか(閉じる・戻す向きは引っかからない)
     */
    @Nullable
    private static InteractionResult gate(BlockState state, Level level, BlockPos pos, Player player,
                                          boolean activating, String message) {
        if (!activating || state.getValue(OvergrownContainers.OVERGROWTH) <= 0) {
            return null;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return OvergrownContainers.tryOpen(level, pos, state, player, message) ? null : InteractionResult.CONSUME;
    }

    /** 置いたブロックも覆われ具合は最大(最初から時々動かない。2026-10-05 夜、ユーザー指定)。 */
    @Nullable
    private static BlockState placedOvergrown(@Nullable BlockState placed) {
        return placed == null ? null : overgrown(placed);
    }

    /**
     * ハサミで植物を剥がすと<b>元のブロック</b>(覆われる前のバニラの扉・ボタンなど)に戻る(2026-10-05 夜、ユーザー指定)。
     * 覆われ具合に関係なく剥がせる(苔は元から全面に見えている)。ハサミの耐久・音・苔の破片は過繫茂したドアを剥がすときと同じ
     * ({@link OvergrownContainers#shearEffects})。ハサミでなければ普通の操作へ。
     */
    private static ItemInteractionResult shear(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand) {
        if (!stack.canPerformAction(ItemAbilities.SHEARS_HARVEST) || CoveredBlocks.vanillaOf(state.getBlock()) == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        revert(level, pos, state);
        OvergrownContainers.shearEffects(stack, level, pos, player, hand);
        return ItemInteractionResult.CONSUME;
    }

    /** 覆われたブロックを元のブロックへ戻す(扉は上下とも。向き・開閉・蝶番・電源はそのまま)。押されたボタンが戻る予定の tick も移す。 */
    private static void revert(Level level, BlockPos pos, BlockState state) {
        int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
        if (state.hasProperty(DoorBlock.HALF)) {
            BlockPos otherPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState other = level.getBlockState(otherPos);
            if (other.is(state.getBlock())) {
                level.setBlock(otherPos, CoveredBlocks.vanillaState(other), flags);
            }
        }
        BlockState vanilla = CoveredBlocks.vanillaState(state);
        level.setBlock(pos, vanilla, flags);
        CoveredBlocks.carryScheduledTick(level, pos, state, vanilla);
    }

    private static boolean growing(BlockState state) {
        return state.getValue(OvergrownContainers.OVERGROWTH) < OvergrownContainers.MAX_LEVEL;
    }

    private static BlockState overgrown(BlockState state) {
        return state.setValue(OvergrownContainers.OVERGROWTH, OvergrownContainers.MAX_LEVEL);
    }

    /** 覆われた木・銅の扉。上下 2 マスの段階は揃える({@link OvergrownDoorBlock#setBothHalves})。 */
    public static class Door extends DoorBlock {

        public Door(BlockSetType type, Properties properties) {
            super(type, properties);
            registerDefaultState(overgrown(defaultBlockState()));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(OvergrownContainers.OVERGROWTH);
        }

        @Override
        @Nullable
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return placedOvergrown(super.getStateForPlacement(context));
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            InteractionResult stuck = type().canOpenByHand()
                    ? gate(state, level, pos, player, !state.getValue(OPEN), OvergrownContainers.STUCK_MESSAGE)
                    : null;
            return stuck != null ? stuck : super.useWithoutItem(state, level, pos, player, hit);
        }

        @Override
        public void setOpen(@Nullable Entity entity, Level level, BlockState state, BlockPos pos, boolean open) {
            // Mob(村人・ゾンビ)が開けるときも同じ確率で引っかかる。閉じるのは常に可(過繫茂したドアと同じ)。
            if (open && !state.getValue(OPEN) && !level.isClientSide
                    && !OvergrownContainers.tryOpen(level, pos, state, null)) {
                return;
            }
            super.setOpen(entity, level, state, pos, open);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                  Player player, InteractionHand hand, BlockHitResult hit) {
            ItemInteractionResult sheared = shear(stack, state, level, pos, player, hand);
            return sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    ? sheared : super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return growing(state);
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            OvergrownContainers.regrow(state, level, pos, random, OvergrownDoorBlock::setBothHalves);
        }
    }

    /** 覆われた木・銅のトラップドア。 */
    public static class TrapDoor extends TrapDoorBlock {

        public TrapDoor(BlockSetType type, Properties properties) {
            super(type, properties);
            registerDefaultState(overgrown(defaultBlockState()));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(OvergrownContainers.OVERGROWTH);
        }

        @Override
        @Nullable
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return placedOvergrown(super.getStateForPlacement(context));
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            InteractionResult stuck = getType().canOpenByHand()
                    ? gate(state, level, pos, player, !state.getValue(OPEN), OvergrownContainers.STUCK_MESSAGE)
                    : null;
            return stuck != null ? stuck : super.useWithoutItem(state, level, pos, player, hit);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                  Player player, InteractionHand hand, BlockHitResult hit) {
            ItemInteractionResult sheared = shear(stack, state, level, pos, player, hand);
            return sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    ? sheared : super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return growing(state);
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            OvergrownContainers.regrow(state, level, pos, random, OvergrownContainers::setSingle);
        }
    }

    /** 覆われたフェンスゲート。 */
    public static class FenceGate extends FenceGateBlock {

        public FenceGate(WoodType type, Properties properties) {
            super(type, properties);
            registerDefaultState(overgrown(defaultBlockState()));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(OvergrownContainers.OVERGROWTH);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return placedOvergrown(super.getStateForPlacement(context));
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            InteractionResult stuck = gate(state, level, pos, player, !state.getValue(OPEN), OvergrownContainers.STUCK_MESSAGE);
            return stuck != null ? stuck : super.useWithoutItem(state, level, pos, player, hit);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                  Player player, InteractionHand hand, BlockHitResult hit) {
            ItemInteractionResult sheared = shear(stack, state, level, pos, player, hand);
            return sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    ? sheared : super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return growing(state);
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            OvergrownContainers.regrow(state, level, pos, random, OvergrownContainers::setSingle);
        }
    }

    /** 覆われたボタン。押せないときは「動かない」。 */
    public static class Button extends ButtonBlock {

        public Button(BlockSetType type, int ticksToStayPressed, Properties properties) {
            super(type, ticksToStayPressed, properties);
            registerDefaultState(overgrown(defaultBlockState()));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(OvergrownContainers.OVERGROWTH);
        }

        @Override
        @Nullable
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return placedOvergrown(super.getStateForPlacement(context));
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            InteractionResult stuck = gate(state, level, pos, player, !state.getValue(POWERED), OvergrownContainers.STUCK_MOVE_MESSAGE);
            return stuck != null ? stuck : super.useWithoutItem(state, level, pos, player, hit);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                  Player player, InteractionHand hand, BlockHitResult hit) {
            ItemInteractionResult sheared = shear(stack, state, level, pos, player, hand);
            return sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    ? sheared : super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return growing(state);
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            OvergrownContainers.regrow(state, level, pos, random, OvergrownContainers::setSingle);
        }
    }

    /** 覆われたレバー。倒せないときは「動かない」。戻すのは常にできる。 */
    public static class Lever extends LeverBlock {

        public Lever(Properties properties) {
            super(properties);
            registerDefaultState(overgrown(defaultBlockState()));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(OvergrownContainers.OVERGROWTH);
        }

        @Override
        @Nullable
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return placedOvergrown(super.getStateForPlacement(context));
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            InteractionResult stuck = gate(state, level, pos, player, !state.getValue(POWERED), OvergrownContainers.STUCK_MOVE_MESSAGE);
            return stuck != null ? stuck : super.useWithoutItem(state, level, pos, player, hit);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                  Player player, InteractionHand hand, BlockHitResult hit) {
            ItemInteractionResult sheared = shear(stack, state, level, pos, player, hand);
            return sheared != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    ? sheared : super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return growing(state);
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            OvergrownContainers.regrow(state, level, pos, random, OvergrownContainers::setSingle);
        }
    }
}
