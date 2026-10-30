package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableRecipe;
import com.yayuy.overgrownvegetation.crafting.SapCrystalTreatmentRecipe;
import com.yayuy.overgrownvegetation.crafting.SizedCraftingRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 研究台の 5×5 作業台専用レシピ({@link SizedCraftingRecipe})の {@link RecipeType} と
 * {@link RecipeSerializer}。バニラのクラフトには影響しない。
 *
 * <p>鍛冶台の樹液結晶処理({@link SapCrystalTreatmentRecipe})はバニラの {@code minecraft:smithing}
 * レシピタイプに相乗りするので、{@link RecipeType} は増やさず {@link RecipeSerializer} だけ足す。
 */
public final class ModRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, OvergrownVegetation.MODID);
    public static final DeferredRegister<RecipeType<?>> TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, OvergrownVegetation.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, SizedCraftingRecipe.Serializer> SIZED_CRAFTING_SERIALIZER =
            SERIALIZERS.register("sized_crafting", SizedCraftingRecipe.Serializer::new);

    public static final DeferredHolder<RecipeType<?>, RecipeType<SizedCraftingRecipe>> SIZED_CRAFTING_TYPE =
            TYPES.register("sized_crafting", () -> RecipeType.simple(OvergrownVegetation.id("sized_crafting")));

    public static final DeferredHolder<RecipeSerializer<?>, SapCrystalTreatmentRecipe.Serializer> SAP_CRYSTAL_TREATMENT_SERIALIZER =
            SERIALIZERS.register("sap_crystal_treatment", SapCrystalTreatmentRecipe.Serializer::new);

    /** パワードアーマー作成テーブルの「作成」タブ(素材の種類と数だけ。順 1 の着手順 5)。 */
    public static final DeferredHolder<RecipeSerializer<?>, PoweredArmorTableRecipe.Serializer> POWERED_ARMOR_TABLE_SERIALIZER =
            SERIALIZERS.register("powered_armor_table", PoweredArmorTableRecipe.Serializer::new);

    public static final DeferredHolder<RecipeType<?>, RecipeType<PoweredArmorTableRecipe>> POWERED_ARMOR_TABLE_TYPE =
            TYPES.register("powered_armor_table", () -> RecipeType.simple(OvergrownVegetation.id("powered_armor_table")));

    /** 因子の抽出設備(ドロップ N 個 → 因子 1 個を時間をかけて。順 1 の着手順 7)。 */
    public static final DeferredHolder<RecipeSerializer<?>, com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe.Serializer> FACTOR_EXTRACTION_SERIALIZER =
            SERIALIZERS.register("factor_extraction", com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe.Serializer::new);

    public static final DeferredHolder<RecipeType<?>, RecipeType<com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe>> FACTOR_EXTRACTION_TYPE =
            TYPES.register("factor_extraction", () -> RecipeType.simple(OvergrownVegetation.id("factor_extraction")));

    /** 乳鉢ですりつぶす(2026-10-16)。 */
    public static final DeferredHolder<RecipeSerializer<?>, com.yayuy.overgrownvegetation.crafting.GrindingRecipe.Serializer> GRINDING_SERIALIZER =
            SERIALIZERS.register("grinding", com.yayuy.overgrownvegetation.crafting.GrindingRecipe.Serializer::new);

    public static final DeferredHolder<RecipeType<?>, RecipeType<com.yayuy.overgrownvegetation.crafting.GrindingRecipe>> GRINDING_TYPE =
            TYPES.register("grinding", () -> RecipeType.simple(OvergrownVegetation.id("grinding")));

    /** 乳鉢の中の組み合わせを紙で回収して薬に(2026-10-16)。 */
    public static final DeferredHolder<RecipeSerializer<?>, com.yayuy.overgrownvegetation.crafting.MortarMixingRecipe.Serializer> MORTAR_MIXING_SERIALIZER =
            SERIALIZERS.register("mortar_mixing", com.yayuy.overgrownvegetation.crafting.MortarMixingRecipe.Serializer::new);

    public static final DeferredHolder<RecipeType<?>, RecipeType<com.yayuy.overgrownvegetation.crafting.MortarMixingRecipe>> MORTAR_MIXING_TYPE =
            TYPES.register("mortar_mixing", () -> RecipeType.simple(OvergrownVegetation.id("mortar_mixing")));

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
        TYPES.register(modBus);
    }

    private ModRecipes() {
    }
}
