package com.yayuy.overgrownvegetation.td;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * ターゲットブロックの収納(2026-09-25、ユーザー指定「ターゲットブロックにラージチェスト 4 つ分のインベントリを用意し、TD 中は mob から
 * ドロップしたアイテムをそこに自動的に入れる。インベントリが埋まった際は mob からそのままドロップする」)。
 *
 * <p>{@value #SLOTS} マス = ラージチェスト 1 つ({@value #PAGE_SLOTS} マス)× {@value #PAGES} ページ。画面はページを切り替えて
 * 1 ページずつ見せる({@code menu/TargetStorageMenu})。ホッパーなどは NeoForge のアイテムの能力で全マスに触れる
 * ({@code registry/ModCapabilities})。入れ物のブロック(チェスト)と同じく、ターゲットブロックが無くなると中身は散らばる
 * ({@link TargetBlock#onRemove})。攻め手のドロップを入れるのは {@link TdLootCollector}。
 */
public final class TargetStorage implements Container {

    /** 1 ページ = ラージチェスト 1 つ。 */
    public static final int PAGE_SLOTS = 54;
    /** ページ数(ユーザー指定「ラージチェスト 4 つ分」)。 */
    public static final int PAGES = 4;
    public static final int SLOTS = PAGE_SLOTS * PAGES;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final TargetBlockEntity owner;

    TargetStorage(TargetBlockEntity owner) {
        this.owner = owner;
    }

    /**
     * 入れられるだけ入れる(同じ物の山に足してから空きへ。{@code startSlot} から順に回る)。渡した山は変えない。
     *
     * @return 入りきらなかった残り(全部入れば空)
     */
    public ItemStack insert(ItemStack stack, int startSlot) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = stack.copy();
        int start = Math.floorMod(startSlot, SLOTS);
        boolean changed = false;
        for (int n = 0; n < SLOTS && !rest.isEmpty(); n++) {
            ItemStack here = this.items.get((start + n) % SLOTS);
            if (here.isEmpty() || !ItemStack.isSameItemSameComponents(here, rest)) {
                continue;
            }
            int move = Math.min(rest.getCount(), getMaxStackSize(here) - here.getCount());
            if (move > 0) {
                here.grow(move);
                rest.shrink(move);
                changed = true;
            }
        }
        for (int n = 0; n < SLOTS && !rest.isEmpty(); n++) {
            int slot = (start + n) % SLOTS;
            if (this.items.get(slot).isEmpty()) {
                this.items.set(slot, rest.split(getMaxStackSize(rest)));
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
        return rest;
    }

    // ------------------------------------------------------------------ Container

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SLOTS ? this.items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack out = ContainerHelper.removeItem(this.items, slot, amount);
        if (!out.isEmpty()) {
            setChanged();
        }
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        this.items.set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    @Override
    public void setChanged() {
        this.owner.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this.owner, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
        setChanged();
    }

    // ------------------------------------------------------------------ 保存

    void load(CompoundTag tag, HolderLookup.Provider registries) {
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, registries);
    }

    void save(CompoundTag tag, HolderLookup.Provider registries) {
        ContainerHelper.saveAllItems(tag, this.items, registries);
    }
}
