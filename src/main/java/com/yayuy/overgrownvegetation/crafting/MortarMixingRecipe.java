package com.yayuy.overgrownvegetation.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 乳鉢の中の組み合わせを紙で回収して薬にする(2026-10-16、ユーザー指定「すりつぶした物を乳鉢の中で特定の組み合わせにし、紙で回収すると薬になる」)。
 * 中身(1〜6 個、1 個ずつ)と材料が<b>順不同で過不足なく</b>一致したとき(同じ材料を 2 つ書けば 2 個要る)。
 */
public record MortarMixingRecipe(NonNullList<Ingredient> ingredients, ItemStack result) implements Recipe<MortarMixingRecipe.Input> {

    public static final int MAX_INGREDIENTS = 6;

    /** 乳鉢の中身(1 個ずつのスタックの並び)。 */
    public record Input(List<ItemStack> items) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return items.get(index);
        }

        @Override
        public int size() {
            return items.size();
        }
    }

    @Override
    public boolean matches(Input input, Level level) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack s : input.items()) {
            if (!s.isEmpty()) {
                left.add(s);
            }
        }
        if (left.size() != ingredients.size()) {
            return false;
        }
        return assign(left, 0, new boolean[left.size()]);
    }

    /** 材料 i 以降を、まだ使っていない中身に 1 対 1 で当てはめられるか(最大 6 個なので総当たり)。 */
    private boolean assign(List<ItemStack> items, int i, boolean[] used) {
        if (i == ingredients.size()) {
            return true;
        }
        for (int k = 0; k < items.size(); k++) {
            if (!used[k] && ingredients.get(i).test(items.get(k))) {
                used[k] = true;
                if (assign(items, i + 1, used)) {
                    return true;
                }
                used[k] = false;
            }
        }
        return false;
    }

    @Override
    public ItemStack assemble(Input input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return ingredients;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(ModItems.MORTAR_ITEM.get());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.MORTAR_MIXING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.MORTAR_MIXING_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<MortarMixingRecipe> {

        private static final Codec<NonNullList<Ingredient>> INGREDIENTS = Ingredient.CODEC_NONEMPTY.listOf().flatXmap(list -> {
            if (list.isEmpty() || list.size() > MAX_INGREDIENTS) {
                return DataResult.error(() -> "乳鉢の材料は 1〜" + MAX_INGREDIENTS + " 個: " + list.size());
            }
            return DataResult.success(NonNullList.of(Ingredient.EMPTY, list.toArray(Ingredient[]::new)));
        }, DataResult::success);

        public static final MapCodec<MortarMixingRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                INGREDIENTS.fieldOf("ingredients").forGetter(MortarMixingRecipe::ingredients),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(MortarMixingRecipe::result)
        ).apply(i, MortarMixingRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, MortarMixingRecipe> STREAM_CODEC = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC.apply(net.minecraft.network.codec.ByteBufCodecs.list())
                        .map(l -> NonNullList.of(Ingredient.EMPTY, l.toArray(Ingredient[]::new)), l -> l),
                MortarMixingRecipe::ingredients,
                ItemStack.STREAM_CODEC, MortarMixingRecipe::result,
                MortarMixingRecipe::new);

        @Override
        public MapCodec<MortarMixingRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MortarMixingRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
