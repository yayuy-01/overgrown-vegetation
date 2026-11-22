package com.yayuy.overgrownvegetation.survival.decon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.registry.ModDecon;
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
 * <b>除染の変換表</b>の 1 行(2026-11-14 サバイバルの背骨 段 3a、決定 31)。「覆われた○○」1 つを洗うと {@code result} と、
 * 平均 {@code spores} 個の胞子(整数部は必ず、端数は確率)になる。手洗いと地脈の洗い場で同じ表を使う({@link Decon})。
 *
 * <p>データパックのレシピ(種類 {@code overgrown_vegetation:decontamination})なので、mod マネージャー 2 の作り方の画面と
 * バニラの {@code /reload} で変えられる。レシピの本には出さない({@link #isSpecial})。
 * <pre>{"type": "overgrown_vegetation:decontamination", "ingredient": {"item": "overgrown_vegetation:overgrown_beef"},
 *  "result": {"id": "minecraft:beef", "count": 1}, "spores": 0.5}</pre>
 */
public record DeconRecipe(Ingredient ingredient, ItemStack result, float spores) implements Recipe<SingleRecipeInput> {

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
    public RecipeSerializer<?> getSerializer() {
        return ModDecon.RECIPE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModDecon.RECIPE_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<DeconRecipe> {

        private static final MapCodec<DeconRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(DeconRecipe::ingredient),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(DeconRecipe::result),
                Codec.floatRange(0.0F, 64.0F).optionalFieldOf("spores", 0.0F).forGetter(DeconRecipe::spores)
        ).apply(i, DeconRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, DeconRecipe> STREAM_CODEC = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, DeconRecipe::ingredient,
                ItemStack.STREAM_CODEC, DeconRecipe::result,
                ByteBufCodecs.FLOAT, DeconRecipe::spores,
                DeconRecipe::new);

        @Override
        public MapCodec<DeconRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, DeconRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
