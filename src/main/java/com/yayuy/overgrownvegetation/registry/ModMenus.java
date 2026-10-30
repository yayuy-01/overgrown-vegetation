package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.menu.DecontaminatorMenu;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.menu.ResearchTableMenu;
import com.yayuy.overgrownvegetation.menu.SiegeCannonMenu;
import com.yayuy.overgrownvegetation.menu.SporeDeviceMenu;
import com.yayuy.overgrownvegetation.menu.TargetStorageMenu;
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

    /** ターゲットブロックの収納(ラージチェスト 4 つ分。追加で渡すデータは無し)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<TargetStorageMenu>> TARGET_STORAGE =
            MENUS.register("target_storage",
                    () -> IMenuTypeExtension.create(TargetStorageMenu::new));

    /** パワードアーマー作成テーブル(順 1 の着手順 5。追加で渡すデータは無し —— バニラの作業台と同じ素の MenuType)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<PoweredArmorTableMenu>> POWERED_ARMOR_TABLE =
            MENUS.register("powered_armor_table",
                    () -> new MenuType<>(PoweredArmorTableMenu::new, FeatureFlags.VANILLA_SET));

    /** 因子の抽出設備(順 1 の着手順 7。追加で渡すデータは無し)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<com.yayuy.overgrownvegetation.menu.FactorExtractorMenu>> FACTOR_EXTRACTOR =
            MENUS.register("factor_extractor",
                    () -> new MenuType<>(com.yayuy.overgrownvegetation.menu.FactorExtractorMenu::new, FeatureFlags.VANILLA_SET));

    /** 同盟軍の基地の管理端末(順 2 の着手順 5。開いたときの基地の様子・品の一覧をバッファで渡す)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<com.yayuy.overgrownvegetation.menu.BaseTerminalMenu>> BASE_TERMINAL =
            MENUS.register("base_terminal",
                    () -> IMenuTypeExtension.create(com.yayuy.overgrownvegetation.menu.BaseTerminalMenu::new));

    /** 地脈炉(2026-10-22。バニラのかまどの窓を仮で使う。追加で渡すデータは無し)。 */
    public static final DeferredHolder<MenuType<?>, MenuType<com.yayuy.overgrownvegetation.menu.LeyFurnaceMenu>> LEY_FURNACE =
            MENUS.register("ley_furnace",
                    () -> new MenuType<>(com.yayuy.overgrownvegetation.menu.LeyFurnaceMenu::new, FeatureFlags.VANILLA_SET));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    private ModMenus() {
    }
}
