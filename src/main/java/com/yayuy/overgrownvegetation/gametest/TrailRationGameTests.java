package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 携行食(2026-10-16、サバイバル用アイテム ㊷。ユーザー指定)。
 * <ul>
 *     <li>どの生肉 + 薬草(10-16 から薬草 4 つ。小さな花は外した)で燻製肉の元 1、魚では作れない({@link #baseFromAnyRawMeatAndHerb})。</li>
 *     <li>燻製肉の元は燻製器でだけ携行食になる(かまど・焚き火では焼けない)({@link #onlySmokerCooksTheBase})。</li>
 *     <li>携行食は満腹度 10・隠し満腹度 10・32 個まで・焼き昆布と同じ速さ({@link #rationFoodValues})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TrailRationGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(TrailRationGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "trail_ration";

    private static ItemStack craft(ServerLevel level, Item a, Item b) {
        CraftingInput input = CraftingInput.of(2, 1, List.of(new ItemStack(a), new ItemStack(b)));
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(h -> h.value().assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void baseFromAnyRawMeatAndHerb(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (Item meat : List.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT)) {
            ItemStack out = craft(level, meat, ModItems.MOONLIGHT_HERB.get());
            if (!out.is(ModItems.TRAIL_RATION_BASE.get()) || out.getCount() != 1) {
                helper.fail(meat + " + 月光草で燻製肉の元 1 にならない: " + out);
                return;
            }
        }
        if (!craft(level, Items.COD, ModItems.MOONLIGHT_HERB.get()).isEmpty()) {
            helper.fail("魚でも燻製肉の元ができた");
            return;
        }
        if (!craft(level, Items.BEEF, Items.WHEAT).isEmpty()) {
            helper.fail("薬草でない物(小麦)でも燻製肉の元ができた");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void onlySmokerCooksTheBase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SingleRecipeInput base = new SingleRecipeInput(new ItemStack(ModItems.TRAIL_RATION_BASE.get()));
        ItemStack smoked = level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, base, level)
                .map(h -> h.value().assemble(base, level.registryAccess())).orElse(ItemStack.EMPTY);
        if (!smoked.is(ModItems.TRAIL_RATION.get())) {
            helper.fail("燻製器で携行食にならない: " + smoked);
            return;
        }
        if (level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, base, level).isPresent()
                || level.getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, base, level).isPresent()) {
            helper.fail("かまどか焚き火でも焼けてしまう(燻製器だけのはず)");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rationFoodValues(GameTestHelper helper) {
        ItemStack ration = new ItemStack(ModItems.TRAIL_RATION.get());
        FoodProperties food = ration.get(DataComponents.FOOD);
        FoodProperties kelp = new ItemStack(Items.DRIED_KELP).get(DataComponents.FOOD);
        if (food == null || food.nutrition() != 10 || Math.abs(food.saturation() - 10.0F) > 1.0E-3F) {
            helper.fail("満腹度 10・隠し満腹度 10 でない: " + food);
            return;
        }
        if (kelp == null || Math.abs(food.eatSeconds() - kelp.eatSeconds()) > 1.0E-3F) {
            helper.fail("食べる速さが焼き昆布と違う: " + food.eatSeconds() + " 秒(焼き昆布 " + (kelp == null ? "?" : kelp.eatSeconds()) + ")");
            return;
        }
        if (ration.getMaxStackSize() != 32) {
            helper.fail("32 個まで重ならない: " + ration.getMaxStackSize());
            return;
        }
        if (new ItemStack(ModItems.TRAIL_RATION_BASE.get()).get(DataComponents.FOOD) != null) {
            helper.fail("燻製肉の元が食べられる(食べられないはず)");
            return;
        }
        helper.succeed();
    }

    private TrailRationGameTests() {
    }
}
