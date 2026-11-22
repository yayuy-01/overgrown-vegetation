package com.yayuy.overgrownvegetation.port.capabilities;

import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code RegisterCapabilitiesEvent}(MOD のバス)。{@code port.PortSetup} が共通の準備で 1 回投げる
 * (登録の後なので {@code X_BE.get()} を呼んでよい)。登録した物は {@link OvCaps} の表に入る。
 */
public class RegisterCapabilitiesEvent extends Event implements IModBusEvent {

    public RegisterCapabilitiesEvent() {
    }

    public <T, C, BE extends BlockEntity> void registerBlockEntity(BlockCapability<T, C> cap, BlockEntityType<BE> type,
                                                                   ICapabilityProvider<? super BE, C, T> provider) {
        OvCaps.addBlockEntity(cap, type, provider);
    }

    public <T, C> void registerBlock(BlockCapability<T, C> cap, IBlockCapabilityProvider<T, C> provider, Block... blocks) {
        for (Block b : blocks) {
            OvCaps.addBlock(cap, b, provider);
        }
    }

    public <T, C> void registerItem(ItemCapability<T, C> cap, ICapabilityProvider<ItemStack, C, T> provider, ItemLike... items) {
        for (ItemLike i : items) {
            OvCaps.addItem(cap, i.asItem(), provider);
        }
    }
}
