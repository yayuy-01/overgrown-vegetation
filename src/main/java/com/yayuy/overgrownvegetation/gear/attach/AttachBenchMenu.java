package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 付け物の台の画面(2026-11-21 夜)。枠 0 = 防具、枠 1〜3 = その防具の付け物の枠、その後ろが持ち物 36 枠。
 *
 * <p>付け物の枠は別の入れ物を持たず、置いた防具のコンポーネントを直に読み書きする({@link View})。中身の正本が 1 つなので、物が増えたり消えたりしない。
 * 防具を取り出すと、付け物は防具に付いたまま一緒に出る。閉じると置いた防具は持ち物へ戻る(作業台と同じ)。
 */
public class AttachBenchMenu extends AbstractContainerMenu {

    public static final int ARMOR = 0;
    public static final int FIRST_ATTACH = 1;
    public static final int INV_START = 4;
    public static final int INV_END = 40;
    /** 画面の枠の位置(1 行の箱の絵の 9 マスのうち、防具 = 2 マス目・付け物 = 5〜7 マス目)。 */
    public static final int ARMOR_X = 26, ATTACH_X = 80, ROW_Y = 18;

    private final ContainerLevelAccess access;
    private net.minecraft.world.entity.player.Player owner;
    final SimpleContainer armorBox = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            // 枠の数を減らした後の防具を置いたら、超えた所の付け物を持ち主へ返す(11-22 見直し 16)
            if (owner != null && !owner.level().isClientSide) ArmorAttachments.giveBack(owner, ArmorAttachments.takeOverflow(getItem(0)));
            AttachBenchMenu.this.slotsChanged(this);
        }
    };
    private final View view = new View();

    /** クライアント側(画面を開く知らせから)。 */
    public AttachBenchMenu(int id, Inventory inv) {
        this(id, inv, ContainerLevelAccess.NULL);
    }

    public AttachBenchMenu(int id, Inventory inv, ContainerLevelAccess access) {
        super(ModArmorAttach.BENCH_MENU.get(), id);
        this.access = access;
        this.owner = inv.player;
        addSlot(new Slot(armorBox, 0, ARMOR_X, ROW_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ArmorAttachments.canHold(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            addSlot(new Slot(view, i, ATTACH_X + i * 18, ROW_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return idx < ArmorAttachments.slotCount() && mayAttach(stack) && ArmorAttachments.fits(armor(), stack);
                }

                @Override
                public boolean isActive() {
                    return idx < ArmorAttachments.slotCount() && !armor().isEmpty();
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
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

    /** 付け物の台の付け物の枠か(枠の物は防具から作った写し。{@link MouthClothItem} の入れ替えを止める)。 */
    public static boolean isAttachmentSlot(Slot slot) {
        return slot.container instanceof View;
    }

    /**
     * この台で付けてよいか(11-23 夜)。実験的要素の「防具の付け物」がオフのワールドでは、口覆いの布だけ付けられ、ほかは外すだけ
     * (もう付いている物は消さず、効き目も残す。ユーザー決定)。
     */
    private boolean mayAttach(ItemStack stack) {
        return stack.is(ModArmorAttach.MOUTH_CLOTH.get()) || AttachGate.on(owner.level());
    }

    /** 台に置いた防具(無ければ空)。 */
    public ItemStack armor() {
        return armorBox.getItem(0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        if (index == ARMOR) {
            ItemStack s = slot.getItem();
            if (!moveItemStackTo(s, INV_START, INV_END, true)) return ItemStack.EMPTY;
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (index < INV_START) { // 付け物を外して持ち物へ
            ItemStack out = view.removeItem(index - FIRST_ATTACH, 1);
            if (out.isEmpty()) return ItemStack.EMPTY;
            if (!moveItemStackTo(out, INV_START, INV_END, true)) {
                view.setItem(index - FIRST_ATTACH, out); // 入らなければ付け直す
            }
            return ItemStack.EMPTY;
        }
        ItemStack s = slot.getItem();
        if (armor().isEmpty() && ArmorAttachments.canHold(s)) { // 防具を台へ
            if (moveItemStackTo(s, ARMOR, ARMOR + 1, false)) slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (!armor().isEmpty() && ArmorAttachments.isAttachment(s) && mayAttach(s)) { // 付け物を空いた枠へ 1 つ
            for (int i = 0; i < ArmorAttachments.slotCount(); i++) {
                if (ArmorAttachments.attach(armor(), i, s)) {
                    s.shrink(1);
                    slot.setChanged();
                    return ItemStack.EMPTY;
                }
            }
            return ItemStack.EMPTY;
        }
        // 持ち物と道具の段のあいだ
        boolean moved = index < INV_END - 9 ? moveItemStackTo(s, INV_END - 9, INV_END, false) : moveItemStackTo(s, INV_START, INV_END - 9, false);
        if (moved) slot.setChanged();
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModArmorAttach.BENCH.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, pos) -> clearContainer(player, armorBox));
    }

    /** 付け物の枠 3 つ。台に置いた防具のコンポーネントを読み書きする。 */
    final class View implements Container {
        @Override
        public int getContainerSize() {
            return 3;
        }

        @Override
        public boolean isEmpty() {
            return ArmorAttachments.list(armor()).stream().allMatch(ItemStack::isEmpty);
        }

        @Override
        public ItemStack getItem(int slot) {
            return armor().isEmpty() ? ItemStack.EMPTY : ArmorAttachments.peek(armor(), slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if (amount <= 0 || armor().isEmpty()) return ItemStack.EMPTY;
            ItemStack out = ArmorAttachments.detach(armor(), slot);
            setChanged();
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return armor().isEmpty() ? ItemStack.EMPTY : ArmorAttachments.detach(armor(), slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (armor().isEmpty()) return;
            ItemStack cur = ArmorAttachments.peek(armor(), slot);
            if (!cur.isEmpty() && !stack.isEmpty() && ItemStack.isSameItemSameTags(cur, stack)) return; // 変わらない
            if (!cur.isEmpty()) ArmorAttachments.detach(armor(), slot);
            if (!stack.isEmpty()) ArmorAttachments.attach(armor(), slot, stack);
            setChanged();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            armorBox.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
        }
    }
}
