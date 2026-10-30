package com.yayuy.overgrownvegetation.crafting;

import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * パワードアーマー作成テーブルの「作成」の中身。素材は<b>プレイヤーの持ち物 36 マス</b>から引く
 * (防具・左手は見ない —— 着ている物を素材に取られないように)。
 *
 * <p>そろっているかは<b>持ち物の写しから実際に順に引いてみて</b>判定する。2 つの素材が同じアイテムに
 * 当たるレシピ(例: 同じ合金を別の行で 2 回)で、1 つの山を二重に数えないため。
 * 画面(クライアント)の「持っている数」も同じ数え方を使う。
 */
public final class PoweredArmorTableCrafting {

    /** 作った結果。画面には返さない(素材が減ったのは持ち物の同期で見える)。 */
    public enum Result { OK, NO_TABLE, UNKNOWN, LOCKED, MISSING }

    /** このレシピの種類を一覧の並び(sort → id)で全部。サーバーとクライアントで同じ順になる。 */
    public static List<RecipeHolder<PoweredArmorTableRecipe>> recipes(RecipeManager manager) {
        List<RecipeHolder<PoweredArmorTableRecipe>> list =
                new ArrayList<>(manager.getAllRecipesFor(ModRecipes.POWERED_ARMOR_TABLE_TYPE.get()));
        list.sort(Comparator.comparingInt((RecipeHolder<PoweredArmorTableRecipe> h) -> h.value().sort())
                .thenComparing(h -> h.id().toString()));
        return list;
    }

    /** {@code stacks} のうち {@code ingredient} に合うものの合計(表示用)。 */
    public static int count(Ingredient ingredient, List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && ingredient.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 素材が全部そろっているか({@code stacks} は変えない)。 */
    public static boolean hasAll(PoweredArmorTableRecipe recipe, List<ItemStack> stacks) {
        return hasAll(recipe.ingredients(), stacks);
    }

    /** 素材の並びが全部そろっているか({@code stacks} は変えない)。ツリーの支払い・設計図の作り直しも使う。 */
    public static boolean hasAll(List<SizedIngredient> materials, List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack s : stacks) {
            copy.add(s.copy());
        }
        return take(materials, copy);
    }

    /** 持ち物から素材を引く(そろっていることを {@link #hasAll} で確かめてから呼ぶ)。空になった枠は空にする。 */
    public static void takeFromInventory(Player player, List<SizedIngredient> materials) {
        List<ItemStack> items = player.getInventory().items;
        take(materials, items);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        player.getInventory().setChanged();
    }

    /** 結果を持ち物へ。入らなければ足元へ落とす。 */
    public static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** {@code stacks} から素材を上の行から順に引く。足りなければ false(そのとき {@code stacks} は途中まで減っている)。 */
    private static boolean take(List<SizedIngredient> materials, List<ItemStack> stacks) {
        for (SizedIngredient need : materials) {
            int left = need.count();
            for (ItemStack s : stacks) {
                if (left <= 0) {
                    break;
                }
                if (!s.isEmpty() && need.ingredient().test(s)) {
                    int n = Math.min(left, s.getCount());
                    s.shrink(n);
                    left -= n;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 画面の「作成」ボタン(サーバー側)。テーブルを開いていること・研究で封印されていないことを確かめてから作る。
     */
    public static Result craft(ServerPlayer player, ResourceLocation recipeId) {
        if (!(player.containerMenu instanceof PoweredArmorTableMenu menu) || !menu.stillValid(player)) {
            return Result.NO_TABLE;
        }
        RecipeHolder<?> holder = player.server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof PoweredArmorTableRecipe recipe)) {
            return Result.UNKNOWN;
        }
        if (ResearchLogic.isRecipeLocked(player, recipeId)) {
            return Result.LOCKED;
        }
        Result result = craft(player, recipe);
        if (result == Result.OK) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS,
                    1.0F, 1.0F);
            menu.broadcastChanges();
        }
        return result;
    }

    /** テーブルと研究の確かめを除いた本体(GameTest から直接呼ぶ)。結果は持ち物へ、入らなければ足元へ。 */
    public static Result craft(Player player, PoweredArmorTableRecipe recipe) {
        if (!hasAll(recipe, player.getInventory().items)) {
            return Result.MISSING;
        }
        takeFromInventory(player, recipe.ingredients());
        give(player, recipe.result().copy());
        return Result.OK;
    }

    private PoweredArmorTableCrafting() {
    }
}
