package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;

/**
 * サバイバル用アイテムの前提の素材(2026-10-15): 亜鉛・真鍮・媒介結晶。
 * <ul>
 *     <li>Create と同じ {@code c:} タグに入っている(ユーザー指定「Create の亜鉛も使える」。{@link #zincAndBrassAreInCommonTags})。</li>
 *     <li>真鍮は銅 + 亜鉛(タグ)で 2 個、媒介結晶はアメジストの欠片 + グロウストーンダスト、原石を焼くと亜鉛インゴット
 *     ({@link #materialRecipesWork})。研究ツリーに載せていないので最初から作れる。</li>
 *     <li>鉱石は石のつるはし以上で掘れ、世界生成の地物とバイオームの修正が登録されている({@link #zincOreGenerates})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SurvivalMaterialGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SurvivalMaterialGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_materials";

    private static TagKey<Item> itemTag(String path) {
        return ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", path));
    }

    private static TagKey<Block> blockTag(String path) {
        return BlockTags.create(ResourceLocation.fromNamespaceAndPath("c", path));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void zincAndBrassAreInCommonTags(GameTestHelper helper) {
        record ItemCase(Item item, String tag) {
        }
        List<ItemCase> items = List.of(
                new ItemCase(ModItems.ZINC_INGOT.get(), "ingots/zinc"),
                new ItemCase(ModItems.ZINC_NUGGET.get(), "nuggets/zinc"),
                new ItemCase(ModItems.RAW_ZINC.get(), "raw_materials/zinc"),
                new ItemCase(ModItems.ZINC_ORE_ITEM.get(), "ores/zinc"),
                new ItemCase(ModItems.DEEPSLATE_ZINC_ORE_ITEM.get(), "ores/zinc"),
                new ItemCase(ModItems.ZINC_BLOCK_ITEM.get(), "storage_blocks/zinc"),
                new ItemCase(ModItems.RAW_ZINC_BLOCK_ITEM.get(), "storage_blocks/raw_zinc"),
                new ItemCase(ModItems.BRASS_INGOT.get(), "ingots/brass"),
                new ItemCase(ModItems.BRASS_NUGGET.get(), "nuggets/brass"),
                new ItemCase(ModItems.BRASS_BLOCK_ITEM.get(), "storage_blocks/brass"),
                new ItemCase(ModItems.ZINC_INGOT.get(), "ingots"));
        for (ItemCase c : items) {
            if (!new ItemStack(c.item()).is(itemTag(c.tag()))) {
                helper.fail(c.item() + " が #c:" + c.tag() + " に入っていない");
                return;
            }
        }
        if (!ModBlocks.ZINC_ORE.get().defaultBlockState().is(blockTag("ores/zinc"))
                || !ModBlocks.ZINC_BLOCK.get().defaultBlockState().is(blockTag("storage_blocks/zinc"))) {
            helper.fail("亜鉛のブロックが #c: のブロックのタグに入っていない");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void materialRecipesWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack brass = craft(level, 2, 1, List.of(new ItemStack(Items.COPPER_INGOT), new ItemStack(ModItems.ZINC_INGOT.get())));
        if (!brass.is(ModItems.BRASS_INGOT.get()) || brass.getCount() != 2) {
            helper.fail("銅 + 亜鉛で真鍮 2 個にならない: " + brass);
            return;
        }
        ItemStack crystal = craft(level, 2, 1, List.of(new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.GLOWSTONE_DUST)));
        if (!crystal.is(ModItems.MEDIUM_CRYSTAL.get())) {
            helper.fail("アメジストの欠片 + グロウストーンダストで媒介結晶にならない: " + crystal);
            return;
        }
        SingleRecipeInput raw = new SingleRecipeInput(new ItemStack(ModItems.RAW_ZINC.get()));
        ItemStack ingot = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, raw, level)
                .map(h -> h.value().assemble(raw, level.registryAccess())).orElse(ItemStack.EMPTY);
        if (!ingot.is(ModItems.ZINC_INGOT.get())) {
            helper.fail("亜鉛の原石を焼いても亜鉛インゴットにならない: " + ingot);
            return;
        }
        helper.succeed();
    }

    private static ItemStack craft(ServerLevel level, int w, int h, List<ItemStack> grid) {
        CraftingInput input = CraftingInput.of(w, h, grid);
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(holder -> holder.value().assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void zincOreGenerates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (Block ore : List.of(ModBlocks.ZINC_ORE.get(), ModBlocks.DEEPSLATE_ZINC_ORE.get())) {
            if (!ore.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE) || !ore.defaultBlockState().is(BlockTags.NEEDS_STONE_TOOL)
                    || !ore.defaultBlockState().requiresCorrectToolForDrops()) {
                helper.fail(ore + " がつるはし・石以上の決まりに入っていない");
                return;
            }
            if (!new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(ore.defaultBlockState())
                    || new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(ore.defaultBlockState())) {
                helper.fail(ore + " は石のつるはしで掘れて木のつるはしでは掘れないはず");
                return;
            }
        }
        ResourceLocation id = OvergrownVegetation.id("zinc_ore");
        if (!level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).containsKey(id)
                || !level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).containsKey(id)) {
            helper.fail("亜鉛鉱石の地物が登録されていない");
            return;
        }
        if (!level.registryAccess().registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS)
                .containsKey(ResourceKey.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, id))) {
            helper.fail("亜鉛鉱石のバイオームの修正が登録されていない");
            return;
        }
        helper.succeed();
    }

    private SurvivalMaterialGameTests() {
    }
}
