package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceKind;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMenus;
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

/**
 * 胞子装置({@link SporeDeviceBlockEntity})の GUI。燃料スロット(レッドストーンダスト)と「胞子」スロット
 * (除去装置 = 取り出し口、拡散装置 = 投入口)+ プレイヤーインベントリ。稼働の残り時間と産出サイクルの
 * 進行は {@link ContainerData} で同期する。
 */
public class SporeDeviceMenu extends AbstractContainerMenu {

    public static final int FUEL_X = 56;
    public static final int FUEL_Y = 35;
    public static final int SPORE_X = 116;
    public static final int SPORE_Y = 35;
    /** 胞子フィルターのスロット(除去装置のみ使う)。 */
    public static final int FILTER_X = 56;
    public static final int FILTER_Y = 17;

    private final Container container;
    private final ContainerData data;
    private final SporeDeviceKind kind;

    /** クライアント側(サーバーから送られた種類だけを受け取る)。 */
    public SporeDeviceMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, new SimpleContainer(SporeDeviceBlockEntity.SLOT_COUNT),
                new SimpleContainerData(SporeDeviceBlockEntity.DATA_COUNT),
                SporeDeviceKind.byOrdinal(buf.readVarInt()));
    }

    public SporeDeviceMenu(int containerId, Inventory inventory, Container container, ContainerData data, SporeDeviceKind kind) {
        super(ModMenus.SPORE_DEVICE.get(), containerId);
        checkContainerSize(container, SporeDeviceBlockEntity.SLOT_COUNT);
        checkContainerDataCount(data, SporeDeviceBlockEntity.DATA_COUNT);
        this.container = container;
        this.data = data;
        this.kind = kind;

        addSlot(new Slot(container, SporeDeviceBlockEntity.SLOT_FUEL, FUEL_X, FUEL_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.REDSTONE);
            }
        });
        addSlot(new Slot(container, SporeDeviceBlockEntity.SLOT_SPORE, SPORE_X, SPORE_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return kind == SporeDeviceKind.DIFFUSER && stack.is(ModItems.SPORE.get());
            }
        });
        addSlot(new Slot(container, SporeDeviceBlockEntity.SLOT_FILTER, FILTER_X, FILTER_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return kind == SporeDeviceKind.PURIFIER && stack.is(ModItems.SPORE_FILTER.get());
            }

            @Override
            public boolean isActive() {
                return kind == SporeDeviceKind.PURIFIER;   // 拡散装置では隠す
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

    public SporeDeviceKind kind() {
        return kind;
    }

    public int remainingTicks() {
        return data.get(SporeDeviceBlockEntity.DATA_REMAINING);
    }

    public int ticksPerFuel() {
        return data.get(SporeDeviceBlockEntity.DATA_TICKS_PER_FUEL);
    }

    public int cycleTicks() {
        return data.get(SporeDeviceBlockEntity.DATA_CYCLE);
    }

    public int cycleLength() {
        return data.get(SporeDeviceBlockEntity.DATA_CYCLE_LENGTH);
    }

    /**
     * 除去装置が<b>詰まっている</b>(「胞子」の取り出し口が満杯 → 強制停止)か。
     * スロットの中身はクライアントにも同期されているので、{@link SporeDeviceBlockEntity#isJammed()} と
     * 同じ条件をここで見れば専用の {@code ContainerData} を増やさずに済む。
     */
    /** 除去装置に<b>フィルターが挿さっていない</b>か(挿すまで動かない)。 */
    public boolean needsFilter() {
        return kind == SporeDeviceKind.PURIFIER
                && container.getItem(SporeDeviceBlockEntity.SLOT_FILTER).isEmpty();
    }

    public boolean isJammed() {
        if (kind != SporeDeviceKind.PURIFIER) {
            return false;
        }
        ItemStack out = container.getItem(SporeDeviceBlockEntity.SLOT_SPORE);
        return !out.isEmpty() && out.getCount() >= out.getMaxStackSize();
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
        int deviceSlots = SporeDeviceBlockEntity.SLOT_COUNT;
        if (index < deviceSlots) {
            // 装置 → プレイヤーインベントリ
            if (!moveItemStackTo(stack, deviceSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // プレイヤーインベントリ → 装置(入る種類だけ)
            if (stack.is(Items.REDSTONE)) {
                if (!moveItemStackTo(stack, SporeDeviceBlockEntity.SLOT_FUEL,
                        SporeDeviceBlockEntity.SLOT_FUEL + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (kind == SporeDeviceKind.DIFFUSER && stack.is(ModItems.SPORE.get())) {
                if (!moveItemStackTo(stack, SporeDeviceBlockEntity.SLOT_SPORE,
                        SporeDeviceBlockEntity.SLOT_SPORE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (kind == SporeDeviceKind.PURIFIER && stack.is(ModItems.SPORE_FILTER.get())) {
                if (!moveItemStackTo(stack, SporeDeviceBlockEntity.SLOT_FILTER,
                        SporeDeviceBlockEntity.SLOT_FILTER + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < deviceSlots + 27) {
                if (!moveItemStackTo(stack, deviceSlots + 27, slots.size(), false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, deviceSlots, deviceSlots + 27, false)) {
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
