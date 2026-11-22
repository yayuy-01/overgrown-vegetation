package com.yayuy.overgrownvegetation.survival.decon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/**
 * <b>除染の変換表</b>の 1 行(2026-11-14 サバイバルの背骨 段 3a、決定 31)。「覆われた○○」1 つを洗うと {@code result} と、
 * 平均 {@code spores} 個の胞子(整数部は必ず、端数は確率)になる。手洗いと地脈の洗い場で同じ表を使う({@link Decon})。
 *
 * <p>データパックのレシピ(種類 {@code overgrown_vegetation:decontamination})なので、mod マネージャー 2 の作り方の画面と
 * バニラの {@code /reload} で変えられる。レシピの本には出さない({@link #isSpecial})。
 * <pre>{"type": "overgrown_vegetation:decontamination", "ingredient": {"item": "overgrown_vegetation:overgrown_beef"},
 *  "result": {"item": "minecraft:beef", "count": 1}, "spores": 0.5}</pre>
 *
 * <p>1.20.1: レシピは自分の id を持つ(RecipeHolder が無い)。材料は 1 枠の {@link Container}(1.21 の SingleRecipeInput の代わり。
 * 0 番の枠を見る)。読み書きは Codec でなく json と通信の手書き(D の DataPort が出す {@code "result": {"item", "count"}} の形)。
 */
public record DeconRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result, float spores) implements Recipe<Container> {

    @Override
    public boolean matches(Container input, Level level) {
        return ingredient.test(input.getItem(0));
    }

    @Override
    public ItemStack assemble(Container input, RegistryAccess registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registries) {
        return result;
    }

    @Override
    public ResourceLocation getId() {
        return id;
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

        /** 1.21 の Codec と同じ決まり: 材料は空でない・結果は空でない・胞子は 0〜64(省けば 0)。 */
        @Override
        public DeconRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.getNonNull(json, "ingredient"), false);
            if (ingredient.isEmpty()) {
                throw new JsonParseException("decontamination " + id + ": ingredient is empty");
            }
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            if (result.isEmpty()) {
                throw new JsonParseException("decontamination " + id + ": result is empty");
            }
            float spores = GsonHelper.getAsFloat(json, "spores", 0.0F);
            if (!(spores >= 0.0F && spores <= 64.0F)) {
                throw new JsonParseException("decontamination " + id + ": spores out of range 0..64: " + spores);
            }
            return new DeconRecipe(id, ingredient, result, spores);
        }

        @Override
        public DeconRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient ingredient = Ingredient.fromNetwork(buf);
            ItemStack result = buf.readItem();
            float spores = buf.readFloat();
            return new DeconRecipe(id, ingredient, result, spores);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, DeconRecipe recipe) {
            recipe.ingredient().toNetwork(buf);
            buf.writeItem(recipe.result());
            buf.writeFloat(recipe.spores());
        }
    }
}
