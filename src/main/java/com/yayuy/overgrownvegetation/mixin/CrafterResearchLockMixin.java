package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.research.Research;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CrafterBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * <b>作り方の鍵をクラフター(自動の作業台)にも</b>(11-18 コードの見直し 第 2 回 ②12・ユーザー決定 5)。作業台・持ち物の 2×2 の鍵
 * ({@link CraftingMenuResearchLockMixin})はプレイヤーの画面の道だけで、クラフターは素通りしていた。クラフターの作り方探し
 * ({@code CrafterBlock.getPotentialResults}。出す処理と画面の見本の両方が使う)で、研究の節が開いていない作り方を「無し」にする。
 * クライアント(サーバーが無い)では何もしない。
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterResearchLockMixin {

    @Inject(method = "getPotentialResults", at = @At("RETURN"), cancellable = true)
    private static void overgrown$researchLock(Level level, CraftingInput input,
                                               CallbackInfoReturnable<Optional<RecipeHolder<CraftingRecipe>>> cir) {
        Optional<RecipeHolder<CraftingRecipe>> found = cir.getReturnValue();
        if (found != null && found.isPresent() && level.getServer() != null
                && com.yayuy.overgrownvegetation.difficulty.ExperimentalRecipes.locked(level.getServer(), found.get().id())) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
