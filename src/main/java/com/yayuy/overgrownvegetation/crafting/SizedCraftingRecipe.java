package com.yayuy.overgrownvegetation.crafting;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;

/**
 * 「研究台の 5×5 作業台」でのみ作れる、最大 5×5 の定形クラフトレシピ。
 *
 * <p>{@link ShapedRecipePattern} を再利用しているが、{@code OvergrownVegetation} コンストラクタで
 * {@link ShapedRecipePattern#setCraftingSize}{@code (5, 5)} を呼んでいるので 5 行 5 列まで解釈できる。
 * 独自の {@link RecipeType}({@code overgrown_vegetation:sized_crafting})なので、
 * バニラの作業台({@code RecipeType.CRAFTING})には一切影響しない。
 */
public record SizedCraftingRecipe(String group, ShapedRecipePattern pattern, ItemStack result)
        implements Recipe<CraftingInput> {

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return pattern.matches(input);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= pattern.width() && height >= pattern.height();
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return pattern.ingredients();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.SIZED_CRAFTING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.SIZED_CRAFTING_TYPE.get();
    }

    /** 作れるのは研究台の 5×5 作業台だけなので、加工台のアイコンも研究台にする。 */
    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(com.yayuy.overgrownvegetation.registry.ModItems.RESEARCH_TABLE_ITEM.get());
    }

    @Override
    public String getGroup() {
        return group;
    }

    public int width() {
        return pattern.width();
    }

    public int height() {
        return pattern.height();
    }

    public static final class Serializer implements RecipeSerializer<SizedCraftingRecipe> {

        private static final MapCodec<SizedCraftingRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                com.mojang.serialization.Codec.STRING.optionalFieldOf("group", "").forGetter(SizedCraftingRecipe::group),
                ShapedRecipePattern.MAP_CODEC.forGetter(SizedCraftingRecipe::pattern),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(SizedCraftingRecipe::result)
        ).apply(i, SizedCraftingRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, SizedCraftingRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, SizedCraftingRecipe::group,
                        ShapedRecipePattern.STREAM_CODEC, SizedCraftingRecipe::pattern,
                        ItemStack.STREAM_CODEC, SizedCraftingRecipe::result,
                        SizedCraftingRecipe::new);

        @Override
        public MapCodec<SizedCraftingRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SizedCraftingRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
