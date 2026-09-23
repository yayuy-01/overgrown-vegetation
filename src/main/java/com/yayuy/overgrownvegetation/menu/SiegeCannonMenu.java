package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * 砲台({@link SiegeCannonBlockEntity})の<b>弾薬庫</b>の GUI。スニーク + 右クリックで開く。
 * 砲弾スロット・火薬スロット・装薬(1 発に使う火薬の数)だけ。
 *
 * <p>照準はこの画面では行わない — 砲台に乗って一人称で狙う({@code CannonSight})。現在の仰角・方位は
 * 参考として {@link ContainerData} 越しに読めるが、変更はできない。
 *
 * <p>装薬の増減ボタンは独自パケットを使わず、バニラの {@code ServerboundContainerButtonClickPacket}
 * → {@link #clickMenuButton} で受ける(研究台の「解析」と同じ)。
 */
public class SiegeCannonMenu extends AbstractContainerMenu {

    /** GUI の幅・高さ({@code SiegeCannonScreen} と共有)。 */
    public static final int WIDTH = 176;
    public static final int HEIGHT = 180;
    public static final int SHELL_X = 62;
    public static final int SHELL_Y = 30;
    public static final int POWDER_X = 98;
    public static final int POWDER_Y = 30;
    public static final int INV_X = 8;
    public static final int INV_Y = 98;
    public static final int HOTBAR_Y = 156;

    // --- ボタン id ---
    public static final int BUTTON_CHARGE_DOWN = 30;
    public static final int BUTTON_CHARGE_UP = 31;

    private final Container container;
    private final ContainerData data;
    private final BlockPos pos;
    /** サーバー側だけ持つ(クライアントでは null)。 */
    @Nullable
    private final SiegeCannonBlockEntity cannon;

    /** クライアント側。 */
    public SiegeCannonMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, new SimpleContainer(2), new SimpleContainerData(SiegeCannonBlockEntity.DATA_COUNT),
                buf.readBlockPos(), null);
    }

    public SiegeCannonMenu(int containerId, Inventory inventory, Container container, ContainerData data,
                           BlockPos pos, @Nullable SiegeCannonBlockEntity cannon) {
        super(ModMenus.SIEGE_CANNON.get(), containerId);
        checkContainerSize(container, 2);
        checkContainerDataCount(data, SiegeCannonBlockEntity.DATA_COUNT);
        this.container = container;
        this.data = data;
        this.pos = pos;
        this.cannon = cannon;

        addSlot(new Slot(container, SiegeCannonBlockEntity.SLOT_SHELL, SHELL_X, SHELL_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.SIEGE_SHELL.get());
            }
        });
        addSlot(new Slot(container, SiegeCannonBlockEntity.SLOT_POWDER, POWDER_X, POWDER_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.GUNPOWDER);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INV_X + col * 18, HOTBAR_Y));
        }
        addDataSlots(data);
    }

    public BlockPos pos() {
        return pos;
    }

    public int elevationTenths() {
        return data.get(SiegeCannonBlockEntity.DATA_ELEVATION);
    }

    public int azimuthTenths() {
        return data.get(SiegeCannonBlockEntity.DATA_AZIMUTH);
    }

    public int charge() {
        return Math.max(1, data.get(SiegeCannonBlockEntity.DATA_CHARGE));
    }

    public int maxCharge() {
        return Math.max(1, data.get(SiegeCannonBlockEntity.DATA_MAX_CHARGE));
    }

    public int reloadTicks() {
        return data.get(SiegeCannonBlockEntity.DATA_RELOAD);
    }

    public int reloadTotal() {
        return data.get(SiegeCannonBlockEntity.DATA_RELOAD_TOTAL);
    }

    public boolean hasShell() {
        return container.getItem(SiegeCannonBlockEntity.SLOT_SHELL).is(ModItems.SIEGE_SHELL.get());
    }

    public int powderCount() {
        ItemStack powder = container.getItem(SiegeCannonBlockEntity.SLOT_POWDER);
        return powder.is(Items.GUNPOWDER) ? powder.getCount() : 0;
    }

    public boolean canFire() {
        return reloadTicks() <= 0 && hasShell() && powderCount() >= charge();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (cannon == null) {
            return false;
        }
        if (id == BUTTON_CHARGE_DOWN) {
            cannon.adjustCharge(-1);
            return true;
        }
        if (id == BUTTON_CHARGE_UP) {
            cannon.adjustCharge(1);
            return true;
        }
        return false;
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
        if (index < 2) {
            if (!moveItemStackTo(stack, 2, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (stack.is(ModItems.SIEGE_SHELL.get())) {
                if (!moveItemStackTo(stack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.is(Items.GUNPOWDER)) {
                if (!moveItemStackTo(stack, 1, 2, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < 2 + 27) {
                if (!moveItemStackTo(stack, 2 + 27, slots.size(), false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 2, 2 + 27, false)) {
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
