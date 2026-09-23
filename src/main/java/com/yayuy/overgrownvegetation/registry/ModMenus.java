package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.menu.DecontaminatorMenu;
import com.yayuy.overgrownvegetation.menu.ResearchTableMenu;
import com.yayuy.overgrownvegetation.menu.SiegeCannonMenu;
import com.yayuy.overgrownvegetation.menu.SporeDeviceMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODのコンテナ({@link MenuType})の登録。現状は研究台のみ。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, OvergrownVegetation.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<ResearchTableMenu>> RESEARCH_TABLE =
            MENUS.register("research_table",
                    () -> IMenuTypeExtension.create(ResearchTableMenu::new));

    /** 胞子除去装置 / 胞子拡散装置(共用。種類はバッファで渡す)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<SporeDeviceMenu>> SPORE_DEVICE =
            MENUS.register("spore_device",
                    () -> IMenuTypeExtension.create(SporeDeviceMenu::new));

    /** 除染機(追加で渡すデータは無し)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<DecontaminatorMenu>> DECONTAMINATOR =
            MENUS.register("decontaminator",
                    () -> IMenuTypeExtension.create((containerId, inventory, buf) ->
                            new DecontaminatorMenu(containerId, inventory)));

    /** 砲台(ブロック座標をバッファで渡す — クライアントの照準補助が砲台の位置を必要とするため)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<SiegeCannonMenu>> SIEGE_CANNON =
            MENUS.register("siege_cannon",
                    () -> IMenuTypeExtension.create(SiegeCannonMenu::new));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    private ModMenus() {
    }
}
