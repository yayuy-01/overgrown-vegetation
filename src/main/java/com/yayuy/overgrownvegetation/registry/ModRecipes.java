package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
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

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
        TYPES.register(modBus);
    }

    private ModRecipes() {
    }
}
