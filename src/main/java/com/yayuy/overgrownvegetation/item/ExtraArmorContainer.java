package com.yayuy.overgrownvegetation.item;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 追加防具の 4 枠を、持ち物の画面の枠({@link ExtraArmorSlot})から触るための入れ物。
 * 中身は持たず、<b>読み書きのたびにプレイヤーのアタッチメントへ行く</b>({@link ExtraArmor#of})。
 * 画面(InventoryMenu)はプレイヤーができた瞬間に作られ、保存の読み込みはその後でアタッチメントを差し替えるため。
 */
public final class ExtraArmorContainer implements Container {

    private final Player player;

    public ExtraArmorContainer(Player player) {
        this.player = player;
    }

    @Override
    public int getContainerSize() {
        return ExtraArmor.SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return ExtraArmor.of(player).get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = getItem(slot);
        if (stack.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = stack.split(amount);
        if (stack.isEmpty()) {
            setItem(slot, ItemStack.EMPTY);
        }
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = getItem(slot);
        setItem(slot, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ExtraArmor.of(player).set(slot, stack);
        // 防御力を付け直し、サーバーなら見た目のために周りへ配る(2026-10-15)。
        ExtraArmor.syncAttributes(player);
        ExtraArmor.broadcast(player);
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
        // アタッチメントは保存のときに丸ごと書き出されるので、ここで印を付ける必要は無い。
    }

    @Override
    public boolean stillValid(Player who) {
        return who == player;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            setItem(i, ItemStack.EMPTY);
        }
    }
}
