package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 研究で未開放の精錬レシピを、かまど・溶鉱炉・燻製器で進ませないようにする。
 *
 * <p>{@code canBurn} は「このレシピを今 1 回ぶん焼けるか」の判定で、点火判定と調理進行判定の
 * 両方から呼ばれる共通の入口。ここで false にすれば、火が点かず、進行度も 0 に戻る
 * (材料は消費されない)。
 *
 * <h2>誰の研究で判定するか</h2>
 * <p>かまどは作業台と違って「作った人」が決まらない(ホッパー投入もあるし、無人でも動く)。
 * なので<b>接続中のプレイヤーが 1 人でも開放していれば通す</b>という判定にしている
 * ({@link ResearchLogic#isRecipeLockedForAll})。シングルプレイなら本人の判定そのもの。
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public class AbstractFurnaceBlockEntityMixin {

    @Inject(method = "canBurn", at = @At("HEAD"), cancellable = true)
    private static void overgrown_vegetation$gateLockedRecipes(
            RegistryAccess registryAccess, @Nullable RecipeHolder<?> recipe,
            NonNullList<ItemStack> inventory, int maxStackSize, AbstractFurnaceBlockEntity furnace,
            CallbackInfoReturnable<Boolean> cir) {
        if (recipe == null) {
            return;
        }
        Level level = furnace.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server != null && ResearchLogic.isRecipeLockedForAll(server, recipe.id())) {
            cir.setReturnValue(false);
        }
    }
}
