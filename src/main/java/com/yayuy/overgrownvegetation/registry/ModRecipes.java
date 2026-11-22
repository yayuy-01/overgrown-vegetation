package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredRegister;

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

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
        TYPES.register(modBus);
    }

    private ModRecipes() {
    }
}
