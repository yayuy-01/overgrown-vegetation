package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link OvergrownBarrelBlock} の中身。バニラの {@code BarrelBlockEntity} は BlockEntityType が
 * {@code BlockEntityType.BARREL} に固定されていて別ブロックに使えないので、同じ挙動
 * (27 スロット・開閉音・{@code OPEN} プロパティの更新・ルートテーブル)をここに写した。
 */
public class OvergrownBarrelBlockEntity extends RandomizableContainerBlockEntity {

    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

    private final ContainerOpenersCounter openersCounter = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            playSound(state, SoundEvents.BARREL_OPEN);
            updateBlockState(state, true);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            playSound(state, SoundEvents.BARREL_CLOSE);
            updateBlockState(state, false);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int previous, int now) {
        }

        @Override
        protected boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu menu && menu.getContainer() == OvergrownBarrelBlockEntity.this;
        }
    };

    public OvergrownBarrelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERGROWN_BARREL.get(), pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
    }

    @Override
    public int getContainerSize() {
        return 27;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.overgrown_barrel");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    @Override
    public void startOpen(Player player) {
        if (!remove && !player.isSpectator()) {
            openersCounter.incrementOpeners(player, getLevel(), getBlockPos(), getBlockState());
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (!remove && !player.isSpectator()) {
            openersCounter.decrementOpeners(player, getLevel(), getBlockPos(), getBlockState());
        }
    }

    public void recheckOpen() {
        if (!remove) {
            openersCounter.recheckOpeners(getLevel(), getBlockPos(), getBlockState());
        }
    }

    private void updateBlockState(BlockState state, boolean open) {
        if (level != null && state.hasProperty(BarrelBlock.OPEN)) {
            level.setBlock(getBlockPos(), state.setValue(BarrelBlock.OPEN, open), 3);
        }
    }

    private void playSound(BlockState state, SoundEvent sound) {
        if (level == null) {
            return;
        }
        Vec3i normal = state.getValue(BarrelBlock.FACING).getNormal();
        double x = worldPosition.getX() + 0.5D + normal.getX() / 2.0D;
        double y = worldPosition.getY() + 0.5D + normal.getY() / 2.0D;
        double z = worldPosition.getZ() + 0.5D + normal.getZ() / 2.0D;
        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.1F + 0.9F);
    }
}
