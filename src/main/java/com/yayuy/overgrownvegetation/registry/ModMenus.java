package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODのコンテナ({@link MenuType})の登録。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, OvergrownVegetation.MODID);

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    private ModMenus() {
    }
}
