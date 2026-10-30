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
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

/**
 * 因子の抽出設備のレシピ(順 1 の着手順 7、2026-10-03)— Mob のドロップ {@code count} 個を {@code time} tick かけて因子にする。
 *
 * <pre>{@code
 * { "type": "overgrown_vegetation:factor_extraction",
 *   "ingredient": { "item": "overgrown_vegetation:overgrown_exoskeleton" },
 *   "count": 4, "time": 200,
 *   "result": { "id": "overgrown_vegetation:factor_impact", "count": 1 } }
 * }</pre>
 *
 * <p>いまの素材は今あるドロップの仮置き。着手順 11 で種ごとのドロップができたら、この JSON を差し替えるだけ。
 * 燃料は使わない(時間だけ。Claude 仮決め)。
 */
public record FactorExtractionRecipe(Ingredient ingredient, int count, ItemStack result, int time)
        implements Recipe<SingleRecipeInput> {

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
        return new ItemStack(ModItems.FACTOR_EXTRACTOR_ITEM.get());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.FACTOR_EXTRACTION_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.FACTOR_EXTRACTION_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<FactorExtractionRecipe> {

        public static final MapCodec<FactorExtractionRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(FactorExtractionRecipe::ingredient),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(FactorExtractionRecipe::count),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(FactorExtractionRecipe::result),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("time", 200).forGetter(FactorExtractionRecipe::time)
        ).apply(i, FactorExtractionRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, FactorExtractionRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        Ingredient.CONTENTS_STREAM_CODEC, FactorExtractionRecipe::ingredient,
                        ByteBufCodecs.VAR_INT, FactorExtractionRecipe::count,
                        ItemStack.STREAM_CODEC, FactorExtractionRecipe::result,
                        ByteBufCodecs.VAR_INT, FactorExtractionRecipe::time,
                        FactorExtractionRecipe::new);

        @Override
        public MapCodec<FactorExtractionRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, FactorExtractionRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
