package com.yayuy.overgrownvegetation.port.energy;

import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import com.yayuy.overgrownvegetation.port.component.OvItemData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code ComponentEnergyStorage}(アイテムのデータに FE をためる)。
 * 値は {@link OvItemData}(アイテムの NBT)に書く。引数の並びは NeoForge と同じ。
 */
public class ComponentEnergyStorage implements IEnergyStorage {

    protected final ItemStack parent;
    protected final DataComponentType<Integer> energyComponent;
    protected final int capacity;
    protected final int maxReceive;
    protected final int maxExtract;

    public ComponentEnergyStorage(ItemStack parent, DataComponentType<Integer> energyComponent, int capacity, int maxReceive, int maxExtract) {
        this.parent = parent;
        this.energyComponent = energyComponent;
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
    }

    public ComponentEnergyStorage(ItemStack parent, DataComponentType<Integer> energyComponent, int capacity, int maxTransfer) {
        this(parent, energyComponent, capacity, maxTransfer, maxTransfer);
    }

    public ComponentEnergyStorage(ItemStack parent, DataComponentType<Integer> energyComponent, int capacity) {
        this(parent, energyComponent, capacity, capacity, capacity);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (!canReceive() || toReceive <= 0) {
            return 0;
        }
        int energy = getEnergyStored();
        int received = Math.min(capacity - energy, Math.min(maxReceive, toReceive));
        if (!simulate && received > 0) {
            setEnergy(energy + received);
        }
        return received;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (!canExtract() || toExtract <= 0) {
            return 0;
        }
        int energy = getEnergyStored();
        int extracted = Math.min(energy, Math.min(maxExtract, toExtract));
        if (!simulate && extracted > 0) {
            setEnergy(energy - extracted);
        }
        return extracted;
    }

    @Override
    public int getEnergyStored() {
        int v = OvItemData.getOrDefault(parent, energyComponent, 0);
        return Math.max(0, Math.min(capacity, v));
    }

    protected void setEnergy(int energy) {
        OvItemData.set(parent, energyComponent, Math.max(0, Math.min(capacity, energy)));
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    @Override
    public boolean canExtract() {
        return maxExtract > 0;
    }

    @Override
    public boolean canReceive() {
        return maxReceive > 0;
    }
}
