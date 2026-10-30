package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 乳鉢(2026-10-16、サバイバル用アイテム。ユーザー指定)。石の器の横に乳棒が置かれている(模型はユーザーの {@code nyuubati.json}、
 * 器は {@code models/block/mortar}、乳棒は {@code models/block/mortar_pestle} を {@code client/MortarRenderer} で描く)。
 * <ul>
 *     <li>すりつぶせる物・薬の材料を持って右クリック → 1 個ずつ入る(器に 6 つまで)。</li>
 *     <li>素手で右クリックを長押し → 入れた物を 1 個ずつすりつぶす(乳棒が器の中で回る)。</li>
 *     <li>紙を持って右クリック → 中身が薬の組み合わせなら、紙 1 枚で薬が 1 つ。</li>
 *     <li>スニークして素手で右クリック → 最後に入れた物を 1 つ取り出す(仮決め)。</li>
 * </ul>
 */
public class MortarBlock extends BaseEntityBlock {

    public static final MapCodec<MortarBlock> CODEC = simpleCodec(MortarBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 5.0D, 14.0D);

    public MortarBlock(BlockBehaviour.Properties properties) {
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

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MortarBlockEntity(pos, state);
    }

    /** 器の中心(ブロックの中の x, z。北向きの模型で (10, 8) ドット)を向きに合わせて回したもの。 */
    public static double[] bowlCenter(BlockState state) {
        double x = 10.0D / 16.0D;
        double z = 8.0D / 16.0D;
        int turns = switch (state.getValue(FACING)) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        for (int i = 0; i < turns; i++) {
            double nx = 1.0D - z;
            z = x;
            x = nx;
        }
        return new double[]{x, z};
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof MortarBlockEntity mortar)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.is(Items.PAPER)) {
            if (level instanceof ServerLevel server) {
                if (mortar.isLockedFor(server, player)) {
                    player.displayClientMessage(Component.translatable("message.overgrown_vegetation.mortar.locked")
                            .withStyle(ChatFormatting.GRAY), true);
                    return ItemInteractionResult.CONSUME;
                }
                ItemStack medicine = mortar.collect(server);
                if (medicine.isEmpty()) {
                    player.displayClientMessage(Component.translatable(mortar.count() == 0
                            ? "message.overgrown_vegetation.mortar.empty" : "message.overgrown_vegetation.mortar.no_recipe")
                            .withStyle(ChatFormatting.GRAY), true);
                    return ItemInteractionResult.CONSUME;
                }
                stack.consume(1, player);
                if (!player.getInventory().add(medicine)) {
                    player.drop(medicine, false);
                }
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.2F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        if (!MortarBlockEntity.accepts(level, stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (mortar.count() >= com.yayuy.overgrownvegetation.crafting.MortarMixingRecipe.MAX_INGREDIENTS) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.mortar.full")
                    .withStyle(ChatFormatting.GRAY), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!level.isClientSide() && mortar.insert(stack)) {
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.5F, 1.6F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof MortarBlockEntity mortar)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide()) {
                ItemStack out = mortar.popLast();
                if (!out.isEmpty() && !player.getInventory().add(out)) {
                    player.drop(out, false);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if (level instanceof ServerLevel server) {
            return mortar.grindStep(server) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        return mortar.count() > 0 ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, java.util.List<Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.mortar.hint").withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof MortarBlockEntity mortar) {
            Containers.dropContents(level, pos, mortar.itemsForDrop());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
