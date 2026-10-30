package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 地脈炉(2026-10-22、地脈技術の 2 段目)。かまど・溶鉱炉・燻製器のレシピを全部 2 倍の速さで焼く、地脈電池で動く炉。
 * 中身は {@link LeyFurnaceBlockEntity}。働いている間は口が青緑に光る(LIT)。電池は持って右クリックでも入れ替えられる
 * ({@code defense/MachineBattery})。
 */
public class LeyFurnaceBlock extends AbstractFurnaceBlock {

    public static final MapCodec<LeyFurnaceBlock> CODEC = simpleCodec(LeyFurnaceBlock::new);

    public LeyFurnaceBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractFurnaceBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyFurnaceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.LEY_FURNACE.get(), LeyFurnaceBlockEntity::serverTick);
    }

    @Override
    protected void openContainer(Level level, BlockPos pos, Player player) {
        if (level.getBlockEntity(pos) instanceof LeyFurnaceBlockEntity furnace) {
            player.openMenu(furnace);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY();
        double z = pos.getZ() + 0.5D;
        if (random.nextDouble() < 0.08D) {
            level.playLocalSound(x, y, z, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 0.25F, 1.6F, false);
        }
        Direction facing = state.getValue(FACING);
        double side = random.nextDouble() * 0.6D - 0.3D;
        double dx = facing.getAxis() == Direction.Axis.X ? facing.getStepX() * 0.52D : side;
        double dz = facing.getAxis() == Direction.Axis.Z ? facing.getStepZ() * 0.52D : side;
        double dy = random.nextDouble() * 6.0D / 16.0D + 0.15D;
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + dx, y + dy, z + dz, facing.getStepX() * 0.02D, 0.01D, facing.getStepZ() * 0.02D);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_furnace").withStyle(ChatFormatting.GRAY));
    }
}
