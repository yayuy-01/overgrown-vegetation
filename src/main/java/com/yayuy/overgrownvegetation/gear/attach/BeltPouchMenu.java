package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.Nullable;

/**
 * 収納付きベルトの小物入れ(2026-11-21 夜【仮】)。脚の防具にベルトが付いている間、キー(既定 B)で開く。枠の数は {@code beltSlots}。
 *
 * <p>中身はベルトのスタックの {@link ModArmorAttach#BELT_CONTENTS}。開いている間は写しの箱で扱い、変わるたびに脚の防具のベルトへ書き戻す。
 * 脚の防具が替わった・ベルトが外れたら閉じる({@link #stillValid}: 開いたときと同じスタックか)。
 */
public class BeltPouchMenu extends AbstractContainerMenu {

    private final int size;
    private final SimpleContainer box;
    /** 書き戻す先(サーバーだけ。クライアントは null)。 */
    @Nullable
    private final Player owner;
    @Nullable
    private final ItemStack legs;

    /** クライアント側(枠の数を知らせから読む)。 */
    public static BeltPouchMenu client(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        return new BeltPouchMenu(id, inv, buf.readVarInt(), null);
    }

    /** サーバー側。 */
    public static BeltPouchMenu server(int id, Inventory inv, Player player) {
        return new BeltPouchMenu(id, inv, configuredSize(), player);
    }

    public static int configuredSize() {
        return Math.max(1, Math.min(9, ArmorAttachments.table().beltSlots));
    }

    private BeltPouchMenu(int id, Inventory inv, int size, @Nullable Player owner) {
        super(ModArmorAttach.BELT_MENU.get(), id);
        this.size = size;
        this.owner = owner;
        this.legs = owner != null ? owner.getItemBySlot(EquipmentSlot.LEGS) : null;
        this.box = new SimpleContainer(size) {
            @Override
            public void setChanged() {
                super.setChanged();
                writeBack();
            }
        };
        if (legs != null) {
            ItemStack belt = belt();
            ItemContainerContents c = belt.get(ModArmorAttach.BELT_CONTENTS.get());
            if (c != null) {
                NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
                c.copyInto(items);
                for (int i = 0; i < size; i++) box.setItem(i, items.get(i));
            }
        }
        int x0 = 8 + (9 - size) / 2 * 18; // 箱の絵のマスに合わせる
        for (int i = 0; i < size; i++) {
            addSlot(new Slot(box, i, x0 + i * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return allowed(stack);
                }
            });
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 50 + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 8 + c * 18, 108));
        }
    }

    public int size() {
        return size;
    }

    /** 小物入れに入れられる物(ベルトそのもの・ベルトの付いた防具・ほかの入れ物の中に入れられない物は入れない)。 */
    public static boolean allowed(ItemStack stack) {
        return !stack.is(ModArmorAttach.STORAGE_BELT.get()) && stack.getItem().canFitInsideContainerItems()
                && ArmorAttachments.count(stack, ModArmorAttach.STORAGE_BELT.get()) == 0; // ベルトの付いた防具も入れない(入れ子)
    }

    private ItemStack belt() {
        if (legs == null) return ItemStack.EMPTY;
        int i = ArmorAttachments.indexOf(legs, ModArmorAttach.STORAGE_BELT.get());
        return i < 0 ? ItemStack.EMPTY : ArmorAttachments.get(legs, i);
    }

    private void writeBack() {
        if (legs == null || owner == null || !stillValid(owner)) return;
        int i = ArmorAttachments.indexOf(legs, ModArmorAttach.STORAGE_BELT.get());
        if (i < 0) return;
        ItemStack belt = ArmorAttachments.get(legs, i);
        java.util.List<ItemStack> items = new java.util.ArrayList<>(size);
        for (int k = 0; k < size; k++) items.add(box.getItem(k));
        if (items.stream().allMatch(ItemStack::isEmpty)) belt.remove(ModArmorAttach.BELT_CONTENTS.get());
        else belt.set(ModArmorAttach.BELT_CONTENTS.get(), ItemContainerContents.fromItems(items));
        ArmorAttachments.replace(legs, i, belt);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack s = slot.getItem();
        ItemStack before = s.copy();
        if (index < size) {
            if (!moveItemStackTo(s, size, size + 36, true)) return ItemStack.EMPTY;
        } else {
            if (!allowed(s) || !moveItemStackTo(s, 0, size, false)) return ItemStack.EMPTY;
        }
        if (s.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        if (legs == null) return true; // クライアント
        return !legs.isEmpty() && player.getItemBySlot(EquipmentSlot.LEGS) == legs
                && ArmorAttachments.count(legs, ModArmorAttach.STORAGE_BELT.get()) > 0;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        writeBack();
    }
}
