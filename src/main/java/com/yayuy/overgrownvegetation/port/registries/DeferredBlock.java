package com.yayuy.overgrownvegetation.port.registries;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code DeferredBlock} と同じ名前・同じ使い方。 */
public class DeferredBlock<T extends Block> extends DeferredHolder<Block, T> implements ItemLike {

    protected DeferredBlock(ResourceKey<Block> key, RegistryObject<T> forge) {
        super(key, forge);
    }

    @Override
    public Item asItem() {
        return get().asItem();
    }

    public ItemStack toStack() {
        return toStack(1);
    }

    public ItemStack toStack(int count) {
        return new ItemStack(this, count);
    }
}
