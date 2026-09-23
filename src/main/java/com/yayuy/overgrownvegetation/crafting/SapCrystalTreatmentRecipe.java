package com.yayuy.overgrownvegetation.crafting;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

/**
 * 鍛冶台で、武器・道具・防具に樹液結晶を打ち込む処理。
 * 打ち込まれた装備は「胞子汚染」({@link GearContamination})の進みが
 * {@code effects.json} の {@code gearContamination.sapCrystalRateFactor}(既定 0.1 = 10 分の 1)倍になる。
 *
 * <h2>置き方</h2>
 * <p>テンプレート枠は<b>空のまま</b>、中央(ベース)に装備、右(追加材)に樹液結晶。
 * バニラの鍛冶レシピと違って専用テンプレートを要らなくするため、
 * {@link #isTemplateIngredient} は常に false を返し、{@link #matches} でテンプレート枠が空であることを要求する。
 *
 * <h2>対象</h2>
 * <p>ベースは {@link GearContamination#isContaminable} が true のもの全部
 * (素材つきの道具・防具・盾・弓・クロスボウ・トライデント・メイス、および {@code ModTags.SPORE_CONTAMINABLE})。
 * アイテムを列挙しないので、後から足した装備やバニラの装備にもそのまま効く。
 * すでに処理済みのものは {@link #matches} で弾く(二重に打ち込んでも意味が無いため)。
 *
 * <p>結果は元の装備のコピーなので、耐久・エンチャント・既に溜まっている汚染度はそのまま残る。
 */
public class SapCrystalTreatmentRecipe implements SmithingRecipe {

    private final Ingredient addition;

    public SapCrystalTreatmentRecipe(Ingredient addition) {
        this.addition = addition;
    }

    @Override
    public boolean matches(SmithingRecipeInput input, Level level) {
        return input.template().isEmpty()
                && GearContamination.isContaminable(input.base())
                && !GearContamination.isSapCrystalTreated(input.base())
                && this.addition.test(input.addition());
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
        ItemStack result = input.base().copyWithCount(1);
        GearContamination.setSapCrystalTreated(result);
        return result;
    }

    /** 中身が入力次第なので、レシピ本には出さない({@link #isSpecial})。 */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    /** 専用テンプレートは使わない。テンプレート枠には何も置けない。 */
    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return GearContamination.isContaminable(stack);
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return this.addition.test(stack);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.SAP_CRYSTAL_TREATMENT_SERIALIZER.get();
    }

    @Override
    public boolean isIncomplete() {
        return this.addition.isEmpty();
    }

    public static class Serializer implements RecipeSerializer<SapCrystalTreatmentRecipe> {

        private static final MapCodec<SapCrystalTreatmentRecipe> CODEC = RecordCodecBuilder.mapCodec(
                instance -> instance.group(
                        Ingredient.CODEC.fieldOf("addition").forGetter(r -> r.addition)
                ).apply(instance, SapCrystalTreatmentRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, SapCrystalTreatmentRecipe> STREAM_CODEC =
                Ingredient.CONTENTS_STREAM_CODEC.map(SapCrystalTreatmentRecipe::new, r -> r.addition);

        @Override
        public MapCodec<SapCrystalTreatmentRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SapCrystalTreatmentRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
