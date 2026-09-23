package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.menu.ResearchTableMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ResearchTableBlock} の中身。5×5 作業台のグリッドはメニュー側の
 * {@link net.minecraft.world.inventory.TransientCraftingContainer}(閉じると中身はプレイヤーに戻る)
 * なので、この BE 自体はインベントリを持たず {@link MenuProvider} だけを担う。
 */
public class ResearchTableBlockEntity extends BlockEntity implements MenuProvider {

    public ResearchTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESEARCH_TABLE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.overgrown_vegetation.research_table");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ResearchTableMenu(containerId, inventory,
                ContainerLevelAccess.create(level, worldPosition),
                com.yayuy.overgrownvegetation.research.ResearchLogic.analyzeSlotCount(player));
    }
}
