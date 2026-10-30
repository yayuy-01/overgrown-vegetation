package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.block.LeyFurnaceBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 地脈炉の窓(2026-10-22。ユーザー指定「画面はバニラのかまどの窓を仮で使う」)。枠の並びも絵もバニラのかまどのまま、
 * 燃料の枠に入るのは地脈電池だけ・焼く物の枠へのシフトクリックは燻製・溶鉱・精錬のどれかで焼ける物。
 * 炎の絵は電池の残り(値は {@link LeyFurnaceBlockEntity} の窓用の値で秒に直してある)。レシピブックはかまどのもの(精錬のレシピだけ出る)。
 */
public class LeyFurnaceMenu extends AbstractFurnaceMenu {

    /** クライアント側(空の入れ物で開く)。 */
    public LeyFurnaceMenu(int containerId, Inventory inventory) {
        super(ModMenus.LEY_FURNACE.get(), RecipeType.SMELTING, RecipeBookType.FURNACE, containerId, inventory);
    }

    public LeyFurnaceMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
        super(ModMenus.LEY_FURNACE.get(), RecipeType.SMELTING, RecipeBookType.FURNACE, containerId, inventory, container, data);
    }

    @Override
    protected boolean isFuel(ItemStack stack) {
        return stack.is(ModItems.BATTERY.get());
    }

    @Override
    protected boolean canSmelt(ItemStack stack) {
        return LeyFurnaceBlockEntity.canCook(level, stack);
    }
}
