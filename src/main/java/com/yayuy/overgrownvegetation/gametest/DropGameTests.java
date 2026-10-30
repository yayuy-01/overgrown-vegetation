package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import com.yayuy.overgrownvegetation.item.ContaminatedFoodItem;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Mob のドロップ(順 1 の着手順 11、2026-10-04)のテスト。ドロップ表は同梱の JSON を読んで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class DropGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(DropGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "drops";
    private static final String NS = "overgrown_vegetation:";

    private static String lootText(String mob) {
        try (InputStream in = DropGameTests.class.getResourceAsStream(
                "/data/overgrown_vegetation/loot_table/entities/" + mob + ".json")) {
            if (in == null) {
                return null;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.toString();
        } catch (Exception e) {
            throw new AssertionError(mob + " のドロップ表が読めない: " + e);
        }
    }

    private static void expectDrop(GameTestHelper helper, String mob, String item) {
        String text = lootText(mob);
        helper.assertTrue(text != null, "ドロップ表が無い: " + mob);
        helper.assertTrue(text.contains("\"" + NS + item + "\""), mob + " が " + item + " を落とさない");
    }

    /**
     * グループ共通: G1 寄生菌糸(6 体)/ G2 節足の殻(節足動物の 5 体。魚の 2 体は落とさない —— ユーザー指定)/ G3 捕食者の腱(8 体)/
     * G4 胞子腺の組織。
     * G5 の適応の記録は見ない —— 10-12 の mod マネージャーの保存で 7 種のドロップ表から消え、10-16 ユーザー指定で
     * 「ユーザーが作り直すので戻さない」。作り直したら種ごとの記録をここで確かめ直す(前の対応: ファルキムネス grasp・スクティムネス shield・
     * アクムネス aim・バティムネス water・エレボムネス dark・ヴォラムネス devour・フォディムネス dig → {@code adaptation_record_<記録>})。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void groupCommonDrops(GameTestHelper helper) {
        for (String m : List.of("parasitized_cow", "parasitized_sheep", "parasitized_pig", "parasitized_zombie",
                "parasitized_creeper", "parasitized_spider")) {
            expectDrop(helper, m, "parasitic_mycelium");
        }
        for (String m : List.of("protophasma", "arthropleura", "spelaeopleura", "lithopleura", "stenodictya_lobata")) {
            expectDrop(helper, m, "arthropod_shell");
        }
        for (String fish : List.of("rhizodus", "xenacanthus")) {
            helper.assertFalse(lootText(fish).contains(NS + "arthropod_shell"), fish + "(魚)が節足の殻を落とす");
        }
        for (String m : List.of("phalangiotarbus", "meganeura", "coelurosauravus", "petrolacosaurus", "ianthasaurus",
                "hylonomus", "orthacanthus", "megarachne")) {
            expectDrop(helper, m, "predator_tendon");
        }
        for (String m : List.of("arbora", "phyllosens", "velophyll", "sporophyll", "overgrown_shielder", "pedicula",
                "overgrown_nexus", "blight_fern")) {
            expectDrop(helper, m, "spore_gland_tissue");
        }
        helper.succeed();
    }

    /**
     * G1 の汚染されたバニラ素材: 牛は生の牛肉の代わりに汚染された肉(革は残る)、豚・羊も汚染された肉、羊は汚染された羊毛、
     * ゾンビは腐った胞子肉、クモは汚染された糸、クリーパーは胞子嚢(火薬も残る)。羊は毛を刈られていなくても自分の表を使う。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void parasitizedVanillaDrops(GameTestHelper helper) {
        String cow = lootText("parasitized_cow");
        helper.assertFalse(cow.contains("\"minecraft:beef\""), "寄生された牛がまだ生の牛肉を落とす");
        helper.assertTrue(cow.contains(NS + "contaminated_meat") && cow.contains("\"minecraft:leather\""),
                "寄生された牛のドロップが違う");
        expectDrop(helper, "parasitized_pig", "contaminated_meat");
        expectDrop(helper, "parasitized_sheep", "contaminated_meat");
        expectDrop(helper, "parasitized_sheep", "contaminated_wool");
        expectDrop(helper, "parasitized_zombie", "rotten_spore_flesh");
        expectDrop(helper, "parasitized_spider", "contaminated_string");
        expectDrop(helper, "parasitized_creeper", "spore_sac");
        helper.assertTrue(lootText("parasitized_creeper").contains("\"minecraft:gunpowder\""), "寄生されたクリーパーが火薬を落とさなくなった");

        var sheep = ModEntities.PARASITIZED_SHEEP.get().create(helper.getLevel());
        helper.assertTrue(sheep != null && !sheep.isSheared(), "寄生された羊が作れない");
        helper.assertTrue(sheep.getLootTable().equals(ModEntities.PARASITIZED_SHEEP.get().getDefaultLootTable()),
                "毛のある寄生された羊が自分のドロップ表を使っていない: " + sheep.getLootTable());
        sheep.discard();
        helper.succeed();
    }

    /** 汚染された食べ物: 確率 1 なら胞子感染が掛かり、0 なら掛からない。食べ物として食べられる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void contaminatedFoodInfects(GameTestHelper helper) {
        // プレイヤーに掛けるとチャンクの胞子濃度まで足すので、バニラの豚で確かめる(寄生された Mob には掛からない)
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 3));
        RandomSource random = RandomSource.create(1L);
        helper.assertFalse(ContaminatedFoodItem.infectAfterEating(pig, 0.0D, random), "確率 0 で感染した");
        helper.assertTrue(ContaminatedFoodItem.infectAfterEating(pig, 1.0D, random), "確率 1 で感染しない");
        helper.assertTrue(pig.hasEffect(ModMobEffects.SPORE_INFECTION), "胞子感染の効果が付いていない");
        for (Item food : List.of(ModItems.CONTAMINATED_MEAT.get(), ModItems.ROTTEN_SPORE_FLESH.get())) {
            helper.assertTrue(new ItemStack(food).getFoodProperties(null) != null, "食べ物になっていない: " + food);
            helper.assertTrue(food instanceof ContaminatedFoodItem c && c.infectChance() > 0.0D, "感染の確率が無い: " + food);
        }
        pig.discard();
        helper.succeed();
    }

    /** 因子の元の差し替え: 衝撃 ← 捕食者の腱 / 刺突 ← ファランギオタルブスの牙 / 爆発 ← 胞子嚢 / 毒 ← 寄生菌糸 / 胞子 ← 胞子腺の組織。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void factorSourcesUseNewDrops(GameTestHelper helper) {
        List<RecipeHolder<FactorExtractionRecipe>> recipes = helper.getLevel().getServer().getRecipeManager()
                .getAllRecipesFor(ModRecipes.FACTOR_EXTRACTION_TYPE.get());
        Map<Item, Item> expected = Map.of(
                ModItems.FACTOR_IMPACT.get(), ModItems.PREDATOR_TENDON.get(),
                ModItems.FACTOR_PIERCE.get(), ModItems.PHALANGIOTARBUS_FANG.get(),
                ModItems.FACTOR_BLAST.get(), ModItems.SPORE_SAC.get(),
                ModItems.FACTOR_TOXIN.get(), ModItems.PARASITIC_MYCELIUM.get(),
                ModItems.FACTOR_SPORE.get(), ModItems.SPORE_GLAND_TISSUE.get());
        for (Map.Entry<Item, Item> e : expected.entrySet()) {
            RecipeHolder<FactorExtractionRecipe> r = recipes.stream()
                    .filter(h -> h.value().result().is(e.getKey())).findFirst().orElse(null);
            helper.assertTrue(r != null, "抽出レシピが無い: " + e.getKey());
            helper.assertTrue(r.value().ingredient().test(new ItemStack(e.getValue())),
                    e.getKey() + " の素材が " + e.getValue() + " でない");
        }
        helper.succeed();
    }

    /** 研究台の研究ポイント: 同梱の research.json と Java の既定値の両方に新しいドロップが載っている。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void newDropsGiveResearchPoints(GameTestHelper helper) {
        JsonObject points;
        try (InputStream in = DropGameTests.class.getResourceAsStream("/overgrown_vegetation/design/research.json")) {
            points = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("itemPoints");
        } catch (Exception e) {
            throw new AssertionError("research.json が読めない: " + e);
        }
        for (Item item : List.of(ModItems.PARASITIC_MYCELIUM.get(), ModItems.ARTHROPOD_SHELL.get(), ModItems.PREDATOR_TENDON.get(),
                ModItems.SPORE_GLAND_TISSUE.get(), ModItems.SPORE_SAC.get(), ModItems.ADAPTATION_RECORD_DIG.get(),
                ModItems.CONTAMINATED_MEAT.get(), ModItems.CONTAMINATED_STRING.get(),
                ModItems.LEY_FLOWER.get(), ModItems.LEY_CRYSTAL_SHARD.get())) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
            helper.assertTrue(points.has(id) && points.get(id).getAsInt() > 0, "research.json に研究ポイントが無い: " + id);
            helper.assertTrue(ResearchTable.DEFAULT.pointsFor(new ItemStack(item)) == points.get(id).getAsInt(),
                    "Java の既定値と research.json の研究ポイントが違う: " + id);
        }
        helper.succeed();
    }

    private DropGameTests() {
    }
}
