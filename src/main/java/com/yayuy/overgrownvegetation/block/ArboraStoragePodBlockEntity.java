package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** {@link ArboraStoragePodBlock} の中身。27 スロット(樽と同じ)。 */
public class ArboraStoragePodBlockEntity extends RandomizableContainerBlockEntity {

    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

    public ArboraStoragePodBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ARBORA_STORAGE_POD.get(), pos, state);
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
        return Component.translatable("container.overgrown_vegetation.arbora_storage_pod");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    /**
     * {@code stack} を空きスロットへ入れる。入り切らなければ残りを返す
     * ({@code nest/NestDeaths} と {@code entity/ArboraHaulGoal} が使う)。
     *
     * <p><b>先にルートテーブルを展開すること。</b> 生成直後の貯蔵胞は「ルートテーブルを抱えたまま
     * 中身が空」という状態で、そこへ直に入れても<b>プレイヤーが最初に開けた瞬間に
     * 戦利品の抽選で上書きされて消える</b> —— アルボラが運び込んだはずの物が中に無い、という
     * 2026-09-20 のユーザー報告の原因。展開すると抽選が済んでルートテーブルは外れる。
     */
    public ItemStack tryInsert(ItemStack stack) {
        unpackLootTable(null);
        ItemStack rest = stack;
        for (int i = 0; i < items.size() && !rest.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty()) {
                items.set(i, rest.copy());
                rest = ItemStack.EMPTY;
                break;
            }
            if (ItemStack.isSameItemSameComponents(slot, rest)) {
                int room = Math.min(slot.getMaxStackSize(), getMaxStackSize()) - slot.getCount();
                if (room > 0) {
                    int moved = Math.min(room, rest.getCount());
                    slot.grow(moved);
                    rest.shrink(moved);
                }
            }
        }
        setChanged();
        return rest;
    }

    /** 空きスロットが 1 つでもあるか。抽選前だと全部空に見えるので、こちらも先に展開する。 */
    public boolean hasRoom() {
        unpackLootTable(null);
        for (ItemStack slot : items) {
            if (slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
