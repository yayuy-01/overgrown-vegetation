package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 研究で未開放の鍛冶レシピを、鍛冶台で完成させられないようにする。
 *
 * <h2>なぜ {@code SmithingMenu} ではなく親を狙うのか</h2>
 * <p>判定に要る {@code player} と {@code resultSlots} は親の {@link ItemCombinerMenu} が持っている。
 * Mixin の {@code @Shadow} は<b>対象クラス自身に宣言されたフィールドしか解決できない</b>ので、
 * {@code SmithingMenu} を狙うと「@Shadow field player was not located」で起動ごと落ちる。
 * そこでフィールドの持ち主である親を狙い、鍛冶台のときだけ動くように {@code instanceof} で絞る
 * (親は金床・砥石にも使われるため)。
 *
 * <p>{@code slotsChanged} は入力スロットが変わるたびに {@code createResult()} を呼ぶ入口なので、
 * その直後({@code TAIL})に成果を空へ戻せば、未開放のレシピは成果スロットに出てこない。
 * どのレシピが使われたかは {@code ResultContainer#getRecipeUsed} から取れる
 * ({@code SmithingMenu#createResult} が {@code setRecipeUsed} で入れている)。
 * 成果スロットの同期は、メニューが毎 tick 行う {@code broadcastChanges} がそのまま拾う。
 *
 * <p>鍛冶台はメニューを開いているプレイヤーが必ず居るので、作業台と同じ<b>プレイヤー個人</b>の
 * 判定でよい(かまどは {@link AbstractFurnaceBlockEntityMixin} を参照)。
 */
@Mixin(ItemCombinerMenu.class)
public class ItemCombinerMenuMixin {

    @Shadow
    @Final
    protected Player player;

    @Shadow
    @Final
    protected ResultContainer resultSlots;

    @Inject(method = "slotsChanged", at = @At("TAIL"))
    private void overgrown_vegetation$gateLockedRecipes(Container inventory, CallbackInfo ci) {
        if (!((Object) this instanceof SmithingMenu)
                || this.resultSlots.getItem(0).isEmpty()
                || this.player.level().isClientSide()) {
            return;
        }
        RecipeHolder<?> used = this.resultSlots.getRecipeUsed();
        if (used == null || !ResearchLogic.isRecipeLocked(this.player, used.id())) {
            return;
        }
        this.resultSlots.setItem(0, ItemStack.EMPTY);
        this.resultSlots.setRecipeUsed(null);
    }
}
