package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 作業台で<b>兜 + 口覆いの布 → 布の付いた兜</b>(2026-11-23 夜 ユーザー決定「装飾品は作業台で兜と布を合わせると付く形」)。
 * 格子のどこに置いてもよい(兜 1 つと布 1 つだけ)。付け方の決まり(部位・防護マスクには付けない・同じ物は 1 つ)は
 * {@link ArmorAttachments#fits} のまま。布のフィルターの状態は兜の側へ移る({@link ArmorAttachments#attach})。
 *
 * <p><b>外し方</b>(11-23 夜。ユーザーが任せた所を全体管理２の案 A で): 布の付いた兜を作業台に<b>1 つだけ</b>置くと、布の無い兜ができ、
 * 布は格子に残る(フィルターの状態を連れて戻る)。ほかの付け物はそのまま。どちらも実験的要素の「防具の付け物」のオン・オフに関わらず使える。
 */
public final class MouthClothRecipe extends CustomRecipe {

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<MouthClothRecipe>> SERIALIZER =
            ModRecipes.SERIALIZERS.register("mouth_cloth_helmet", () -> new SimpleCraftingRecipeSerializer<>(MouthClothRecipe::new));

    public MouthClothRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** 構築子から呼んで、登録の入れ物へ載せる。 */
    public static void init() {
    }

    /** 兜と布を 1 つずつ見つける(ほかの物があれば null)。[0] = 兜、[1] = 布。 */
    private static ItemStack[] find(CraftingInput input) {
        ItemStack armor = ItemStack.EMPTY, cloth = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(ModArmorAttach.MOUTH_CLOTH.get())) {
                if (!cloth.isEmpty()) return null;
                cloth = s;
            } else if (ArmorAttachments.canHold(s)) {
                if (!armor.isEmpty()) return null;
                armor = s;
            } else {
                return null;
            }
        }
        if (armor.isEmpty() || cloth.isEmpty() || !ArmorAttachments.fits(armor, cloth)) return null;
        return new ItemStack[]{armor, cloth};
    }

    private static int freeSlot(ItemStack armor) {
        for (int i = 0; i < ArmorAttachments.slotCount(); i++) {
            if (ArmorAttachments.get(armor, i).isEmpty()) return i;
        }
        return -1;
    }

    /** 外すとき: 布の付いた兜が 1 つだけ(ほかは空)なら、その格子の番号。違えば -1。 */
    private static int removal(CraftingInput input) {
        int at = -1;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            if (at >= 0 || !ArmorAttachments.hasMouthCloth(s)
                    || ArmorAttachments.indexOf(s, ModArmorAttach.MOUTH_CLOTH.get()) < 0) return -1;
            at = i;
        }
        return at;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (removal(input) >= 0) return true;
        ItemStack[] f = find(input);
        return f != null && freeSlot(f[0]) >= 0;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int r = removal(input);
        if (r >= 0) {
            ItemStack out = input.getItem(r).copyWithCount(1);
            ArmorAttachments.detach(out, ArmorAttachments.indexOf(out, ModArmorAttach.MOUTH_CLOTH.get()));
            return out;
        }
        ItemStack[] f = find(input);
        if (f == null) return ItemStack.EMPTY;
        ItemStack out = f[0].copyWithCount(1);
        int slot = freeSlot(out);
        return slot >= 0 && ArmorAttachments.attach(out, slot, f[1]) ? out : ItemStack.EMPTY;
    }

    /** 外したとき、布は兜のあった格子に残る。 */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> rest = super.getRemainingItems(input);
        int r = removal(input);
        if (r >= 0) {
            ItemStack armor = input.getItem(r).copyWithCount(1);
            rest.set(r, ArmorAttachments.detach(armor, ArmorAttachments.indexOf(armor, ModArmorAttach.MOUTH_CLOTH.get())));
        }
        return rest;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER.get();
    }

}
