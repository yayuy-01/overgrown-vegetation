package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.block.FactorExtractorBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 因子の抽出設備のコンテナ(順 1 の着手順 7、2026-10-03)。0 = 入れる枠、1 = 出る枠、2〜37 = 持ち物。
 * 位置はバニラのかまど(燃料の枠を除いた形)。画面は除染装置と同じ灰色(ユーザー指定)。
 */
public class FactorExtractorMenu extends AbstractContainerMenu {

    public static final int INPUT_X = 56;
    public static final int OUTPUT_X = 116;
    public static final int SLOT_Y = 35;

    private final Container container;
    private final ContainerData data;
    private final Level level;

    /** クライアント側。 */
    public FactorExtractorMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(2), new SimpleContainerData(FactorExtractorBlockEntity.DATA_COUNT));
    }

    public FactorExtractorMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
        super(ModMenus.FACTOR_EXTRACTOR.get(), containerId);
        checkContainerSize(container, 2);
        checkContainerDataCount(data, FactorExtractorBlockEntity.DATA_COUNT);
        this.container = container;
        this.data = data;
        this.level = inventory.player.level();
        container.startOpen(inventory.player);
        this.addSlot(new Slot(container, FactorExtractorBlockEntity.INPUT, INPUT_X, SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return FactorExtractorBlockEntity.recipeFor(FactorExtractorMenu.this.level, stack) != null;
            }
        });
        this.addSlot(new Slot(container, FactorExtractorBlockEntity.OUTPUT, OUTPUT_X, SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }
        this.addDataSlots(data);
    }

    public int progress() {
        return data.get(FactorExtractorBlockEntity.DATA_PROGRESS);
    }

    public int total() {
        return data.get(FactorExtractorBlockEntity.DATA_TOTAL);
    }

    public Level level() {
        return level;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        int invStart = 2;
        int hotbarStart = invStart + 27;
        int end = hotbarStart + 9;
        if (index < invStart) {
            if (!this.moveItemStackTo(stack, invStart, end, index == FactorExtractorBlockEntity.OUTPUT)) {
                return ItemStack.EMPTY;
            }
        } else if (FactorExtractorBlockEntity.recipeFor(level, stack) != null
                && this.moveItemStackTo(stack, FactorExtractorBlockEntity.INPUT, FactorExtractorBlockEntity.INPUT + 1, false)) {
            // 入れる枠へ
        } else if (index < hotbarStart) {
            if (!this.moveItemStackTo(stack, hotbarStart, end, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, invStart, hotbarStart, false)) {
            return ItemStack.EMPTY;
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
        return this.container.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }
}
