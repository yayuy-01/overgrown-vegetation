package com.yayuy.overgrownvegetation.port.capabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code IBlockCapabilityProvider<T, C>}(ブロックエンティティの無いブロックの口)。 */
@FunctionalInterface
public interface IBlockCapabilityProvider<T, C> {
    @Nullable
    T getCapability(Level level, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, C context);
}
