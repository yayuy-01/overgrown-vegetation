package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.research.Research;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * <b>作り方の鍵</b>(2026-11-17 研究台と研究ツリー 決定 20)。研究ツリーの節が開いていない作り方は、作業台と持ち物の 2×2 で結果が出ない。
 *
 * <p>バニラの「レシピ本に無い作り方は作れない」({@code doLimitedCrafting})と同じ所 ── {@code CraftingMenu.slotChangedCraftingGrid} の
 * {@code ResultContainer.setRecipeUsed} が false を返すと結果の枠が空になる ── に、研究の鍵を足す。持ち物の 2×2({@code InventoryMenu})も
 * 同じ関数を呼ぶ。クラフター(自動の作業台)は通らない(プレイヤーがいない)ので鍵は掛からない。
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuResearchLockMixin {

    @WrapOperation(
            method = "slotChangedCraftingGrid",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/ResultContainer;setRecipeUsed(Lnet/minecraft/world/level/Level;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/crafting/RecipeHolder;)Z"))
    private static boolean overgrown$researchLock(ResultContainer result, Level level, ServerPlayer player, RecipeHolder<?> recipe,
                                                 Operation<Boolean> original) {
        if (level.getServer() != null && Research.recipeLocked(level.getServer(), recipe.id())) {
            return false;
        }
        return original.call(result, level, player, recipe);
    }
}
