package com.yayuy.overgrownvegetation.crafting;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

/**
 * 乳鉢ですりつぶす(2026-10-16、ユーザー指定「乳鉢にアイテムを入れて、プレイヤーが右クリック長押しですりつぶすと、すりつぶした物になる」)。
 * 1 個 → 1 個。{@code time} は長押しの tick(0 なら {@code effects.json} の {@code medicine.grindTicks})。
 */
public record GrindingRecipe(Ingredient ingredient, ItemStack result, int time) implements Recipe<SingleRecipeInput> {

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return ingredient.test(input.item());
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
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
        return NonNullList.of(Ingredient.EMPTY, ingredient);
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
        return ModRecipes.GRINDING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.GRINDING_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<GrindingRecipe> {

        public static final MapCodec<GrindingRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(GrindingRecipe::ingredient),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(GrindingRecipe::result),
                com.mojang.serialization.Codec.INT.optionalFieldOf("time", 0).forGetter(GrindingRecipe::time)
        ).apply(i, GrindingRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, GrindingRecipe> STREAM_CODEC = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, GrindingRecipe::ingredient,
                ItemStack.STREAM_CODEC, GrindingRecipe::result,
                ByteBufCodecs.VAR_INT, GrindingRecipe::time,
                GrindingRecipe::new);

        @Override
        public MapCodec<GrindingRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, GrindingRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
