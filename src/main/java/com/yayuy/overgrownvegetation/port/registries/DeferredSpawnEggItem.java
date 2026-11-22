package com.yayuy.overgrownvegetation.port.registries;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.util.function.Supplier;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code DeferredSpawnEggItem}。中身は Forge の {@link ForgeSpawnEggItem}(作りは同じ)。 */
public class DeferredSpawnEggItem extends ForgeSpawnEggItem {

    public DeferredSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> type, int backgroundColor, int highlightColor, Properties props) {
        super(type, backgroundColor, highlightColor, props);
    }
}
