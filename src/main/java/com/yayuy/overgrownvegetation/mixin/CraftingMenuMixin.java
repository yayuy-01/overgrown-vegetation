package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * 研究で未開放のレシピを作業台/インベントリでクラフトできないようにする。
 *
 * <p>{@code CraftingMenu.slotChangedCraftingGrid} は 3x3 の作業台({@link CraftingMenu})と
 * 2x2 のインベントリ({@code InventoryMenu})の両方から呼ばれる共通の静的メソッド。
 * バニラが結果スロットを計算した直後({@code TAIL})に、そのレシピが
 * {@link ResearchLogic#isRecipeLocked} なら結果を空に差し替えてクライアントへ送り直す。
 */
@Mixin(CraftingMenu.class)
public class CraftingMenuMixin {

    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void overgrown_vegetation$gateLockedRecipes(
            AbstractContainerMenu menu, Level level, Player player,
            CraftingContainer craftSlots, ResultContainer resultSlots,
            @Nullable RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
        if (level.isClientSide() || level.getServer() == null || resultSlots.getItem(0).isEmpty()) {
            return;
        }
        CraftingInput input = craftSlots.asCraftInput();
        Optional<RecipeHolder<CraftingRecipe>> opt =
                level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level, recipe);
        if (opt.isEmpty()) {
            return;
        }
        ResourceLocation id = opt.get().id();
        if (!ResearchLogic.isRecipeLocked(player, id)) {
            // 地脈灯 → 忌避灯の改造は、地脈灯の電池を出来上がりへ引き継ぐ(2026-10-24)
            if (com.yayuy.overgrownvegetation.defense.MachineBattery.carryLampBattery(craftSlots, resultSlots.getItem(0))) {
                ItemStack carried = resultSlots.getItem(0);
                menu.setRemoteSlot(0, carried);
                if (player instanceof ServerPlayer sp) {
                    sp.connection.send(new ClientboundContainerSetSlotPacket(
                            menu.containerId, menu.incrementStateId(), 0, carried));
                }
            }
            return;
        }
        resultSlots.setItem(0, ItemStack.EMPTY);
        menu.setRemoteSlot(0, ItemStack.EMPTY);
        if (player instanceof ServerPlayer sp) {
            sp.connection.send(new ClientboundContainerSetSlotPacket(
                    menu.containerId, menu.incrementStateId(), 0, ItemStack.EMPTY));
        }
    }
}
