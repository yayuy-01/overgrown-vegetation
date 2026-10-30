package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.menu.DecontaminatorMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
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
 * <p>2026-10-24(地脈技術の 3 段目)から<b>地脈電池で動く</b>({@link #SLOT_BATTERY} の枠の品そのもの。地脈炉と同じ)。働いている間 1 tick に 1 減る
 * (満タンで 1 時間。地脈灯と同じ・仮)。電池を持って右クリックでも入れ替えられる。同盟軍の基地の除染機({@link #baseSupplied()})は電池なしで動く。
 * 前のワールドの除染機は電池なし(止まっている)で読み込まれる。
 *
 * <p>ホッパー対応: 汚染された装備と目詰まりしたフィルターはどの面からでも入れられ、
 * <b>済んだものだけ</b>取り出せる。電池も入れられ、使い切った電池だけ抜ける。
 */
public class DecontaminatorBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, MachineBattery.Holder {

    /** 洗う物の枠の数(0〜4)。 */
    public static final int SLOT_COUNT = 5;
    /** 地脈電池の枠(2026-10-24)。 */
    public static final int SLOT_BATTERY = 5;
    /** 中身の枠の数(洗う物 + 電池)。 */
    public static final int CONTAINER_SIZE = 6;
    private static final int[] ALL_SLOTS = {0, 1, 2, 3, 4, SLOT_BATTERY};

    public static final int DATA_CYCLE = 0;
    public static final int DATA_CYCLE_LENGTH = 1;
    /** 電池の残りと満タン(秒。窓へ送る値は short なので)。 */
    public static final int DATA_CHARGE = 2;
    public static final int DATA_CAPACITY = 3;
    public static final int DATA_COUNT = 4;

    private NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
    /** 同盟軍の基地が置いた除染機(電池なしで動く)。 */
    private boolean baseSupplied;
    /** 「1% 落とす」までの進行 tick。 */
    private int cycleTicks;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_CYCLE -> cycleTicks;
                case DATA_CYCLE_LENGTH -> ticksPerPercent();
                case DATA_CHARGE -> baseSupplied ? BatteryItem.CAPACITY / 20 : (batteryCharge() + 19) / 20;
                case DATA_CAPACITY -> BatteryItem.CAPACITY / 20;
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

    /** 処理するものが入っているか。汚染の残った装備か、目詰まりしたフィルター。 */
    public boolean hasWork() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (needsWork(items.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** 稼働中か: 洗う物があり、電池が残っている(基地の除染機は電池なしで)。 */
    public boolean isWorking() {
        return hasWork() && MachineBattery.hasEnergy(this);
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
            MachineBattery.drain(be, 1);
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
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = items.get(i);
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
        if (slot == SLOT_BATTERY) {
            return isBattery(stack);
        }
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
        if (slot == SLOT_BATTERY) {
            return stack.is(ModItems.EMPTY_BATTERY.get()); // 使い切った電池だけ抜ける
        }
        return !needsWork(stack);
    }

    // --- 電池(5 の枠の品そのもの) ---

    private static boolean isBattery(ItemStack stack) {
        return stack.is(ModItems.BATTERY.get()) || stack.is(ModItems.EMPTY_BATTERY.get());
    }

    @Override
    public int batteryCharge() {
        ItemStack s = items.get(SLOT_BATTERY);
        return s.is(ModItems.BATTERY.get()) ? BatteryItem.charge(s) : 0;
    }

    @Override
    public void setBatteryCharge(int charge) {
        items.set(SLOT_BATTERY, BatteryItem.stackFor(charge));
    }

    @Override
    public boolean hasBattery() {
        return isBattery(items.get(SLOT_BATTERY));
    }

    @Override
    public boolean canSwapBattery() {
        return items.get(SLOT_BATTERY).isEmpty() || hasBattery();
    }

    @Override
    public void setHasBattery(boolean has) {
        if (!has) {
            items.set(SLOT_BATTERY, ItemStack.EMPTY);
        }
    }

    @Override
    public void batteryChanged() {
        setChanged();
    }

    @Override
    public boolean baseSupplied() {
        return baseSupplied;
    }

    /** 同盟軍の基地の除染機にする(電池なしで動く)。 */
    public void setBaseSupplied(boolean supplied) {
        if (baseSupplied != supplied) {
            baseSupplied = supplied;
            setChanged();
        }
    }

    // --- NBT ---

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        cycleTicks = tag.getInt("CycleTicks");
        baseSupplied = tag.getBoolean("BaseSupplied");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("CycleTicks", cycleTicks);
        tag.putBoolean("BaseSupplied", baseSupplied);
    }
}
