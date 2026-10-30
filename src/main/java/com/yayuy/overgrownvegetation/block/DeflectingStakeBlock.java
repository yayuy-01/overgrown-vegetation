package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.defense.StakeWards;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 受け流しの杭(2026-10-16、⑭)。クロロフィライトの杭が、広がってくる汚染のエネルギーを先に吸って止める。吸うほどすり減る。
 * 4 本で四角く囲むと囲いになる({@link StakeWards})。すり減りは {@link #WEAR}(0〜3、3 の次で折れる)。
 * 置かれている杭を数えるため、読み込み・取り外しで登録する小さなブロックエンティティを持つ({@link DeflectingStakeBlockEntity})。
 */
public class DeflectingStakeBlock extends BaseEntityBlock {

    public static final MapCodec<DeflectingStakeBlock> CODEC = simpleCodec(DeflectingStakeBlock::new);
    public static final int MAX_WEAR = 3;
    public static final IntegerProperty WEAR = IntegerProperty.create("wear", 0, MAX_WEAR);
    private static final VoxelShape SHAPE = Block.box(6.0D, 0.0D, 6.0D, 10.0D, 16.0D, 10.0D);

    public DeflectingStakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WEAR, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEAR);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeflectingStakeBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player) {
            StakeWards.register(level, pos);
            StakeWards.Ward ward = StakeWards.wardAt(level, pos);
            if (ward != null) {
                player.displayClientMessage(Component.translatable("message.overgrown_vegetation.deflecting_stake.ward",
                        ward.maxX() - ward.minX() + 1, ward.maxZ() - ward.minZ() + 1).withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.deflecting_stake").withStyle(ChatFormatting.GRAY));
    }
}
