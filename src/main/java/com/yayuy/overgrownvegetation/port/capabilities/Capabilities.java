package com.yayuy.overgrownvegetation.port.capabilities;

import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code net.neoforged.neoforge.capabilities.Capabilities} のうち、本流が使う物。
 * 登録は {@link RegisterCapabilitiesEvent}、引くのは {@link OvCaps}({@code level.getCapability(...)} は port-rewrite.pl が書き換える)。
 * Forge の Capability({@link ForgeCapabilities})にもつなぐので、ほかの MOD の線・管からも見える。
 */
public final class Capabilities {

    private Capabilities() {
    }

    public static final class EnergyStorage {
        private EnergyStorage() {
        }

        public static final BlockCapability<IEnergyStorage, Direction> BLOCK = new BlockCapability<>(ForgeCapabilities.ENERGY);
        public static final ItemCapability<IEnergyStorage, Void> ITEM = new ItemCapability<>(ForgeCapabilities.ENERGY);
    }

    public static final class ItemHandler {
        private ItemHandler() {
        }

        public static final BlockCapability<IItemHandler, Direction> BLOCK = new BlockCapability<>(ForgeCapabilities.ITEM_HANDLER);
        public static final ItemCapability<IItemHandler, Void> ITEM = new ItemCapability<>(ForgeCapabilities.ITEM_HANDLER);
    }
}
