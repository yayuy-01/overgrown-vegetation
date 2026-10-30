package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.item.CanvasBackpackItem;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 帆布の背嚢の画面(2026-10-16、㊱)。並びはバニラの 2 段のチェスト({@link MenuType#GENERIC_9x2})と同じなので、クライアントはバニラの画面で開く。
 * <ul>
 *     <li>中身は変わるたびに背嚢の {@code minecraft:container} へ書き戻す。</li>
 *     <li>背嚢の中に背嚢(と、中に物を入れられない品)は入れられない。</li>
 *     <li>開いている間、元の背嚢を動かせない(手に持って開いたときはその枠を押せない・数字キーで入れ替えられない)。
 *     元の場所から背嚢が無くなったら閉じる。</li>
 * </ul>
 */
public class BackpackMenu extends AbstractContainerMenu {

    /** 背負っている背嚢を開いた。 */
    public static final int WORN = -1;
    /** 左手の背嚢を開いた。 */
    public static final int OFFHAND = 40;
    private static final int ROWS = 2;

    private final ItemStack backpack;
    private final int inventorySlot;
    private final SimpleContainer container;
    private final Player player;
    /** 動かせない元の背嚢の、この画面の中の枠の番号(画面に無ければ -1)。 */
    private final int lockedMenuSlot;

    public BackpackMenu(int id, Inventory inventory, ItemStack backpack, int inventorySlot) {
        super(MenuType.GENERIC_9x2, id);
        this.backpack = backpack;
        this.inventorySlot = inventorySlot;
        this.player = inventory.player;
        this.container = new SimpleContainer(CanvasBackpackItem.SIZE);
        var items = CanvasBackpackItem.contents(backpack);
        for (int i = 0; i < items.size(); i++) {
            container.setItem(i, items.get(i));
        }
        container.addListener(c -> save());
        container.startOpen(player);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(container, col + row * 9, 8 + col * 18, 18 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return !(stack.getItem() instanceof CanvasBackpackItem) && stack.getItem().canFitInsideContainerItems();
                    }
                });
            }
        }
        int offset = (ROWS - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + offset));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 161 + offset));
        }
        if (inventorySlot >= 0 && inventorySlot < 9) {
            lockedMenuSlot = ROWS * 9 + 27 + inventorySlot;
        } else if (inventorySlot >= 9 && inventorySlot < 36) {
            lockedMenuSlot = ROWS * 9 + inventorySlot - 9;
        } else {
            lockedMenuSlot = -1;
        }
    }

    private void save() {
        java.util.List<ItemStack> items = new java.util.ArrayList<>(CanvasBackpackItem.SIZE);
        for (int i = 0; i < container.getContainerSize(); i++) {
            items.add(container.getItem(i).copy());
        }
        CanvasBackpackItem.setContents(backpack, items);
    }

    /** 元の背嚢がまだ元の場所にあるか。 */
    private boolean backpackInPlace(Player p) {
        if (inventorySlot == WORN) {
            return ExtraArmor.worn(p, ExtraArmor.Part.BODY) == backpack;
        }
        if (inventorySlot == OFFHAND) {
            return p.getOffhandItem() == backpack;
        }
        return p.getInventory().getItem(inventorySlot) == backpack;
    }

    @Override
    public boolean stillValid(Player p) {
        return p.isAlive() && backpackInPlace(p);
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player p) {
        if (slotId == lockedMenuSlot && lockedMenuSlot >= 0) {
            return;
        }
        if (type == ClickType.SWAP && (button == inventorySlot || (button == OFFHAND && inventorySlot == OFFHAND))) {
            return;
        }
        super.clicked(slotId, button, type, p);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (index == lockedMenuSlot || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        int size = ROWS * 9;
        if (index < size) {
            if (!moveItemStackTo(stack, size, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, size, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        save();
        container.stopOpen(p);
    }

    public SimpleContainer container() {
        return container;
    }
}
