package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link OvergrownChestBlock} の中身。バニラの {@link ChestBlockEntity} そのもの(27 スロット・蓋の開閉アニメ・
 * ラージチェスト結合)で、BlockEntityType だけ専用({@link ModBlockEntities#OVERGROWN_CHEST})。
 */
public class OvergrownChestBlockEntity extends ChestBlockEntity {

    public OvergrownChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERGROWN_CHEST.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.overgrown_chest");
    }
}
