package com.yayuy.overgrownvegetation.crafting;

import com.mojang.serialization.Codec;
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
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

import java.util.List;

/**
 * パワードアーマー作成テーブルの「作成」タブのレシピ(順 1 の着手順 5)。
 *
 * <p>枠に並べる形は無く、<b>素材の種類と数だけ</b>を持つ。テーブルの画面で一覧から選び、
 * そろっていれば「作成」で<b>プレイヤーのインベントリから</b>素材を引く
 * (ユーザー指定 2026-10-03「B: 一覧から選ぶ」)。引く処理は {@link PoweredArmorTableCrafting}。
 *
 * <pre>{@code
 * {
 *   "type": "overgrown_vegetation:powered_armor_table",
 *   "label": "gui.overgrown_vegetation.powered_armor_table.part.helmet",   // 一覧の短い名前(翻訳キー。省略で結果の名前)
 *   "sort": 0,                                                              // 一覧の並び(小さい順。同じなら id 順)
 *   "ingredients": [ { "ingredient": { "item": "..." }, "count": 5 }, ... ],
 *   "rebuild_extra": [ ... ],                                               // 設計図から作り直すときに足す素材(省略で無し)
 *   "result": { "id": "...", "count": 1 }
 * }
 * }</pre>
 *
 * <p>{@code rebuild_extra} は設計図のタブの「作り直す」で使う(作成の素材 + これ。仕様「素材のコストは高くなるが
 * 性能を引き継げる」。着手順 6)。
 *
 * <p>独自の {@link RecipeType} なので、バニラの作業台・研究台の 5×5 からは作れない。
 * レシピ本には載せない({@link #isSpecial} = true。載せると分類の無い種類として毎回警告が出る)。
 * 研究の封印はほかのレシピと同じく {@code research.json} の {@code unlocks} にこのレシピの id を書けば効く。
 */
public record PoweredArmorTableRecipe(String label, int sort, List<SizedIngredient> ingredients,
                                      List<SizedIngredient> rebuildExtra, ItemStack result)
        implements Recipe<PoweredArmorTableRecipe.Input> {

    /** 追加の素材の無いレシピ。 */
    public PoweredArmorTableRecipe(String label, int sort, List<SizedIngredient> ingredients, ItemStack result) {
        this(label, sort, ingredients, List.of(), result);
    }

    /** 設計図から作り直すときの素材(作成の素材 + 追加)。 */
    public List<SizedIngredient> rebuildMaterials() {
        List<SizedIngredient> all = new java.util.ArrayList<>(ingredients);
        all.addAll(rebuildExtra);
        return all;
    }

    /** プレイヤーのインベントリ(持ち物 36 マス)を素材の置き場として見る入力。 */
    public record Input(List<ItemStack> stacks) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return stacks.get(index);
        }

        @Override
        public int size() {
            return stacks.size();
        }
    }

    @Override
    public boolean matches(Input input, Level level) {
        return PoweredArmorTableCrafting.hasAll(this, input.stacks());
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
        NonNullList<Ingredient> list = NonNullList.create();
        for (SizedIngredient s : ingredients) {
            list.add(s.ingredient());
        }
        return list;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(ModItems.POWERED_ARMOR_TABLE_ITEM.get());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.POWERED_ARMOR_TABLE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.POWERED_ARMOR_TABLE_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<PoweredArmorTableRecipe> {

        public static final MapCodec<PoweredArmorTableRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("label", "").forGetter(PoweredArmorTableRecipe::label),
                Codec.INT.optionalFieldOf("sort", 0).forGetter(PoweredArmorTableRecipe::sort),
                ExtraCodecs.nonEmptyList(SizedIngredient.NESTED_CODEC.listOf()).fieldOf("ingredients")
                        .forGetter(PoweredArmorTableRecipe::ingredients),
                SizedIngredient.NESTED_CODEC.listOf().optionalFieldOf("rebuild_extra", List.of())
                        .forGetter(PoweredArmorTableRecipe::rebuildExtra),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(PoweredArmorTableRecipe::result)
        ).apply(i, PoweredArmorTableRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, PoweredArmorTableRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PoweredArmorTableRecipe::label,
                        ByteBufCodecs.VAR_INT, PoweredArmorTableRecipe::sort,
                        SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), PoweredArmorTableRecipe::ingredients,
                        SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), PoweredArmorTableRecipe::rebuildExtra,
                        ItemStack.STREAM_CODEC, PoweredArmorTableRecipe::result,
                        PoweredArmorTableRecipe::new);

        @Override
        public MapCodec<PoweredArmorTableRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, PoweredArmorTableRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
