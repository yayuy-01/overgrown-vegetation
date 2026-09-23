package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.menu.DecontaminatorMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import com.yayuy.overgrownvegetation.spore.SporeFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link DecontaminatorBlock} の中身。入れておいた装備の「胞子汚染」
 * ({@link GearContamination})を、{@code effects.json} の
 * {@code gearContamination.decontaminationTicksPerPercent}(既定 40 tick = 2 秒)ごとに 1% ずつ落とす。
 *
 * <p>スロットは {@link #SLOT_COUNT} 個で、入っているものは<b>同時に</b>処理される
 * (1 個あたりの速さは変わらない)。汚染が残っている装備が 1 つでも入っていれば稼働中
 * ({@code lit=true})になり、全部落としきると止まる。
 *
 * <p><b>目詰まりした胞子フィルターも洗える</b>(ユーザー指定 2026-09-21)。
 * 装備の汚染と同じサイクルで耐久を {@code purifier.filterRepairPerCycle}(既定 2)ずつ戻す ——
 * 耐久 256 なら 128 サイクル、およそ 4 分半で洗い上がる。
 * これで胞子除去装置の維持費は「フィルターを作り直す」から「<b>洗って回す</b>」に変わる。
 *
 * <p>ホッパー対応: 汚染された装備と目詰まりしたフィルターはどの面からでも入れられ、
 * <b>済んだものだけ</b>取り出せる。
 */
public class DecontaminatorBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {

    public static final int SLOT_COUNT = 5;
    private static final int[] ALL_SLOTS = {0, 1, 2, 3, 4};

    public static final int DATA_CYCLE = 0;
    public static final int DATA_CYCLE_LENGTH = 1;
    public static final int DATA_COUNT = 2;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    /** 「1% 落とす」までの進行 tick。 */
    private int cycleTicks;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_CYCLE -> cycleTicks;
                case DATA_CYCLE_LENGTH -> ticksPerPercent();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == DATA_CYCLE) {
                cycleTicks = value;
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public DecontaminatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DECONTAMINATOR.get(), pos, state);
    }

    private static int ticksPerPercent() {
        EffectTable.GearContaminationSpec spec = GearContamination.spec();
        return Math.max(1, spec.decontaminationTicksPerPercent());
    }

    /** 処理するものが入っているか(= 稼働中)。汚染の残った装備か、目詰まりしたフィルター。 */
    public boolean isWorking() {
        for (ItemStack stack : items) {
            if (needsWork(stack)) {
                return true;
            }
        }
        return false;
    }

    /** そのアイテムにまだやることがあるか。 */
    private static boolean needsWork(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return GearContamination.percent(stack) > 0.0F || SporeFilters.needsWashing(stack);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DecontaminatorBlockEntity be) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        boolean working = be.isWorking();
        if (working) {
            be.cycleTicks++;
            if (be.cycleTicks >= ticksPerPercent()) {
                be.cycleTicks = 0;
                be.stepDown();
                be.setChanged();
            }
        } else if (be.cycleTicks != 0) {
            be.cycleTicks = 0;
            be.setChanged();
        }

        if (state.getValue(DecontaminatorBlock.LIT) != working) {
            level.setBlock(pos, state.setValue(DecontaminatorBlock.LIT, working), 3);
        }
    }

    /** 入っているものを 1 サイクルぶん進める。装備は汚染 −1%、フィルターは耐久を戻す。 */
    private void stepDown() {
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            if (GearContamination.percent(stack) > 0.0F) {
                GearContamination.add(stack, -1.0D);
            } else if (SporeFilters.needsWashing(stack)) {
                SporeFilters.wash(stack, SporeFilters.repairPerCycle());
            }
        }
    }

    // --- Container ---

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.decontaminator");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new DecontaminatorMenu(containerId, inventory, this, data);
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
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return GearContamination.isContaminable(stack) || SporeFilters.is(stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    /**
     * ホッパーから入れられるのは<b>まだやることがあるもの</b>だけ
     * (済んだものを押し込み続けないように)。
     */
    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return canPlaceItem(slot, stack) && needsWork(stack);
    }

    /** 取り出せるのは済んだものだけ(汚染 0%、フィルターは耐久が戻りきったもの)。 */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return !needsWork(stack);
    }

    // --- NBT ---

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        cycleTicks = tag.getInt("CycleTicks");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("CycleTicks", cycleTicks);
    }
}
