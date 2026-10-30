package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.block.DecontaminatorBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 除染機({@link DecontaminatorBlockEntity})の GUI。汚染された装備を入れる
 * {@link DecontaminatorBlockEntity#SLOT_COUNT} 個のスロット + プレイヤーインベントリ。
 * 「1% 落とす」までの進行は {@link ContainerData} で同期する。
 */
public class DecontaminatorMenu extends AbstractContainerMenu {

    public static final int SLOT_X = 44;
    public static final int SLOT_Y = 35;
    public static final int SLOT_SPACING = 18;

    public static final int BATTERY_X = 152;
    public static final int BATTERY_Y = 35;

    private static final int SLOTS = DecontaminatorBlockEntity.SLOT_COUNT;
    /** 除染機の枠の数(洗う物 + 電池)。プレイヤーの持ち物はこの後ろ。 */
    private static final int MACHINE = DecontaminatorBlockEntity.CONTAINER_SIZE;

    private final Container container;
    private final ContainerData data;

    /** クライアント側。 */
    public DecontaminatorMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(MACHINE), new SimpleContainerData(DecontaminatorBlockEntity.DATA_COUNT));
    }

    public DecontaminatorMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
        super(ModMenus.DECONTAMINATOR.get(), containerId);
        checkContainerSize(container, MACHINE);
        checkContainerDataCount(data, DecontaminatorBlockEntity.DATA_COUNT);
        this.container = container;
        this.data = data;

        for (int i = 0; i < SLOTS; i++) {
            addSlot(new Slot(container, i, SLOT_X + i * SLOT_SPACING, SLOT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 目詰まりした胞子フィルターも洗える(2026-09-21)
                    return GearContamination.isContaminable(stack)
                            || com.yayuy.overgrownvegetation.spore.SporeFilters.is(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        // 地脈電池の枠(2026-10-24)
        addSlot(new Slot(container, DecontaminatorBlockEntity.SLOT_BATTERY, BATTERY_X, BATTERY_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.BATTERY.get()) || stack.is(ModItems.EMPTY_BATTERY.get());
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }
        addDataSlots(data);
    }

    public int cycleTicks() {
        return data.get(DecontaminatorBlockEntity.DATA_CYCLE);
    }

    public int cycleLength() {
        return data.get(DecontaminatorBlockEntity.DATA_CYCLE_LENGTH);
    }

    /** 電池の残り(秒)。 */
    public int chargeSeconds() {
        return data.get(DecontaminatorBlockEntity.DATA_CHARGE);
    }

    /** 電池の満タン(秒)。 */
    public int capacitySeconds() {
        return data.get(DecontaminatorBlockEntity.DATA_CAPACITY);
    }

    /** 今入っているものの中で一番高い汚染度(%)。画面の見出しに使う。 */
    public float worstContamination() {
        float worst = 0.0F;
        for (int i = 0; i < SLOTS; i++) {
            worst = Math.max(worst, GearContamination.percent(container.getItem(i)));
        }
        return worst;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < MACHINE) {
            // 除染機 → プレイヤーインベントリ
            if (!moveItemStackTo(stack, MACHINE, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // プレイヤーインベントリ → 除染機(電池と、汚染されうる装備だけ)
            if (stack.is(ModItems.BATTERY.get()) || stack.is(ModItems.EMPTY_BATTERY.get())) {
                if (!moveItemStackTo(stack, DecontaminatorBlockEntity.SLOT_BATTERY, DecontaminatorBlockEntity.SLOT_BATTERY + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (GearContamination.isContaminable(stack)
                    || com.yayuy.overgrownvegetation.spore.SporeFilters.is(stack)) {
                if (!moveItemStackTo(stack, 0, SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < MACHINE + 27) {
                if (!moveItemStackTo(stack, MACHINE + 27, slots.size(), false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, MACHINE, MACHINE + 27, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
