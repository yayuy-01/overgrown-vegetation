package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableCrafting;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableRecipe;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorWorkshop;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprint;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprintItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorEffects;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorTree;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * パワードアーマー本体・スキルツリー・設計図(順 1 の着手順 6、2026-10-03)のテスト。
 *
 * <p>支払いと効き目の数は {@code gear.json} の {@code poweredArmor}(GameTest は run/config の写しを読み、無ければ
 * Java の既定値)から引く —— 決め打ちにすると mod マネージャーや手で値を変えたときに落ちる
 * ([[gametest-derive-from-design-values]])。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PoweredArmorGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PoweredArmorGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "poweredArmor";

    private static GearTable.PoweredArmor cfg() {
        return DesignData.gear().poweredArmor();
    }

    private static int countOf(Player player, Item item) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 支払いの素材を持ち物へ(段 {@code level} のぶんを {@code times} 回)。 */
    private static void giveCost(Player player, GearTable.LevelCost cost, int branch, int times) {
        for (var mat : PoweredArmorWorkshop.materials(cost, branch)) {
            ItemStack one = mat.getItems()[0].copy();
            one.setCount(mat.count() * times);
            player.getInventory().add(one);
        }
    }

    private static void setPoints(Player player, int points) {
        player.setData(ModAttachments.RESEARCH, new ResearchState(points, List.of()));
    }

    /** 1 段上げると研究ポイントと素材が引かれ、2 段目は素材が足りなければ何も引かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void upgradeSpendsPointsAndMaterials(GameTestHelper helper) {
        GearTable.LevelCost lv1 = cfg().cost(1);
        GearTable.LevelCost lv2 = cfg().cost(2);
        helper.assertTrue(lv1 != null && lv2 != null, "段が 2 つ以上ない");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        int points = lv1.points() + lv2.points();
        setPoints(player, points);
        giveCost(player, lv1, PoweredArmorTree.DEFENSE, 1);
        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_HELMET.get());
        PoweredArmorWorkshop.Result r = PoweredArmorWorkshop.upgrade(player, armor, PoweredArmorTree.DEFENSE);
        helper.assertTrue(r == PoweredArmorWorkshop.Result.OK, "1 段目が上がらない: " + r);
        helper.assertTrue(PoweredArmorItem.tree(armor).defense() == 1, "防御が 1 でない: " + PoweredArmorItem.tree(armor));
        int left = player.getData(ModAttachments.RESEARCH).points();
        helper.assertTrue(left == points - lv1.points(), "研究ポイントの減りが違う: " + left);
        Item mat = PoweredArmorWorkshop.materials(lv1, PoweredArmorTree.DEFENSE).get(0).getItems()[0].getItem();
        helper.assertTrue(countOf(player, mat) == 0, "素材が残っている: " + countOf(player, mat));

        r = PoweredArmorWorkshop.upgrade(player, armor, PoweredArmorTree.DEFENSE);
        helper.assertTrue(r == PoweredArmorWorkshop.Result.MISSING, "素材が無いのに MISSING でない: " + r);
        helper.assertTrue(player.getData(ModAttachments.RESEARCH).points() == left, "素材が無いのに研究ポイントが減った");
        helper.assertTrue(PoweredArmorItem.tree(armor).defense() == 1, "素材が無いのに段が上がった");
        helper.succeed();
    }

    /**
     * 支払いの適応の記録は本ごとに決まっている(順 1 の着手順 11、ユーザー指定): 強靭の記録を持っていても防御は上がらず、
     * 強靭なら上がる。本ごとのタグはユーザー指定どおり(防御 ← 盾・喰らい / 強靭 ← 狙い・掴み / 耐久 ← 水・闇・掘削)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void recordsArePerBranch(GameTestHelper helper) {
        GearTable.LevelCost lv1 = cfg().cost(1);
        Item defense = PoweredArmorWorkshop.materials(lv1, PoweredArmorTree.DEFENSE).get(0).getItems()[0].getItem();
        Item toughness = PoweredArmorWorkshop.materials(lv1, PoweredArmorTree.TOUGHNESS).get(0).getItems()[0].getItem();
        helper.assertTrue(defense != toughness, "防御と強靭の支払いが同じ素材");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        setPoints(player, lv1.points() * 2);
        giveCost(player, lv1, PoweredArmorTree.TOUGHNESS, 1);
        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_CHESTPLATE.get());
        helper.assertTrue(PoweredArmorWorkshop.upgrade(player, armor, PoweredArmorTree.DEFENSE) == PoweredArmorWorkshop.Result.MISSING,
                "強靭の記録で防御が上がった");
        helper.assertTrue(PoweredArmorWorkshop.upgrade(player, armor, PoweredArmorTree.TOUGHNESS) == PoweredArmorWorkshop.Result.OK,
                "強靭の記録で強靭が上がらない");

        helper.assertTrue(new ItemStack(ModItems.ADAPTATION_RECORD_SHIELD.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_DEFENSE)
                && new ItemStack(ModItems.ADAPTATION_RECORD_DEVOUR.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_DEFENSE),
                "防御のタグに盾・喰らいが無い");
        helper.assertTrue(new ItemStack(ModItems.ADAPTATION_RECORD_AIM.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_TOUGHNESS)
                && new ItemStack(ModItems.ADAPTATION_RECORD_GRASP.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_TOUGHNESS),
                "強靭のタグに狙い・掴みが無い");
        helper.assertTrue(new ItemStack(ModItems.ADAPTATION_RECORD_WATER.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_DURABILITY)
                && new ItemStack(ModItems.ADAPTATION_RECORD_DARK.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_DURABILITY)
                && new ItemStack(ModItems.ADAPTATION_RECORD_DIG.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_DURABILITY),
                "耐久のタグに水・闇・掘削が無い");
        helper.assertFalse(new ItemStack(ModItems.ADAPTATION_RECORD_SHIELD.get()).is(com.yayuy.overgrownvegetation.ModTags.ADAPTATION_RECORDS_TOUGHNESS),
                "盾の記録が強靭のタグにも入っている");
        helper.succeed();
    }

    /**
     * 同梱の gear.json を読んだ支払いが Java の既定値と同じで、本ごとの素材が空でない(着手順 11 の {@code branches} を
     * JSON から読む道を通す —— 実行時の run/config の写しには {@code poweredArmor} が無く、既定値しか試されないため)。
     * 装飾品の数値({@code accessories})も既定値と同じ。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void bundledGearJsonMatchesDefault(GameTestHelper helper) {
        GearTable bundled;
        try (java.io.InputStream in = PoweredArmorGameTests.class.getResourceAsStream("/overgrown_vegetation/design/gear.json")) {
            bundled = GearTable.fromJson(new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), GearTable.Json.class));
        } catch (Exception e) {
            throw new AssertionError("design/gear.json が読めない: " + e);
        }
        GearTable.PoweredArmor json = bundled.poweredArmor();
        GearTable.PoweredArmor def = GearTable.DEFAULT_POWERED_ARMOR;
        helper.assertTrue(json.maxLevel() == def.maxLevel(), "段の数が違う: " + json.maxLevel() + " / " + def.maxLevel());
        for (int lv = 1; lv <= def.maxLevel(); lv++) {
            helper.assertTrue(json.cost(lv).points() == def.cost(lv).points(), "Lv" + lv + " の研究ポイントが違う");
            for (int b = 0; b < PoweredArmorTree.BRANCHES; b++) {
                List<GearTable.ItemCost> a = json.cost(lv).items(b);
                helper.assertTrue(!a.isEmpty(), "Lv" + lv + " の本 " + b + " の素材が空(branches が読めていない?)");
                helper.assertTrue(a.equals(def.cost(lv).items(b)),
                        "Lv" + lv + " の本 " + b + " の素材が既定値と違う: " + a + " / " + def.cost(lv).items(b));
            }
        }
        helper.assertTrue(bundled.accessories().equals(GearTable.DEFAULT_ACCESSORIES),
                "gear.json の accessories が既定値と違う: " + bundled.accessories());
        helper.succeed();
    }

    /** 研究ポイントが足りなければ上がらない。最大の段からは上がらない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void upgradeNeedsPointsAndStopsAtMax(GameTestHelper helper) {
        GearTable.LevelCost lv1 = cfg().cost(1);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        setPoints(player, Math.max(0, lv1.points() - 1));
        giveCost(player, lv1, PoweredArmorTree.TOUGHNESS, 1);
        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_BOOTS.get());
        PoweredArmorWorkshop.Result r = PoweredArmorWorkshop.upgrade(player, armor, PoweredArmorTree.TOUGHNESS);
        if (lv1.points() > 0) {
            helper.assertTrue(r == PoweredArmorWorkshop.Result.NO_POINTS, "ポイントが足りないのに NO_POINTS でない: " + r);
        }
        int max = cfg().maxLevel();
        PoweredArmorItem.setTree(armor, new PoweredArmorTree(0, max, 0));
        setPoints(player, 100000);
        r = PoweredArmorWorkshop.upgrade(player, armor, PoweredArmorTree.TOUGHNESS);
        helper.assertTrue(r == PoweredArmorWorkshop.Result.MAX, "最大の段から MAX でない: " + r);
        helper.assertTrue(PoweredArmorItem.tree(armor).toughness() == max, "最大を超えた");
        helper.succeed();
    }

    /** 耐久のツリーで最大耐久が増える(1 段ごとに durabilityPerLevel)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void durabilityTreeRaisesMaxDamage(GameTestHelper helper) {
        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_CHESTPLATE.get());
        int base = armor.getMaxDamage();
        helper.assertTrue(base > 0, "耐久が無い");
        PoweredArmorItem.setTree(armor, new PoweredArmorTree(0, 0, 2));
        int expect = (int) Math.round(base * (1.0D + 2 * cfg().durabilityPerLevel()));
        helper.assertTrue(armor.getMaxDamage() == expect, "耐久 2 段の最大耐久が違う: " + armor.getMaxDamage() + " / " + expect);
        PoweredArmorItem.setTree(armor, PoweredArmorTree.NONE);
        helper.assertTrue(armor.getMaxDamage() == base, "段を戻しても耐久が戻らない");
        helper.assertTrue(!armor.has(com.yayuy.overgrownvegetation.registry.ModDataComponents.POWERED_ARMOR_TREE.get()),
                "全部 0 のツリーでコンポーネントが残っている");
        helper.succeed();
    }

    private static Husk armoredHusk(GameTestHelper helper, PoweredArmorTree tree) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 3));
        husk.setNoAi(true);
        Item[] pieces = {ModItems.POWERED_ARMOR_HELMET.get(), ModItems.POWERED_ARMOR_CHESTPLATE.get(),
                ModItems.POWERED_ARMOR_LEGGINGS.get(), ModItems.POWERED_ARMOR_BOOTS.get()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            ItemStack s = new ItemStack(pieces[i]);
            PoweredArmorItem.setTree(s, tree);
            husk.setItemSlot(slots[i], s);
        }
        return husk;
    }

    private static DamageSource modDamage(GameTestHelper helper, String id) {
        return helper.getLevel().damageSources().source(ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id(id)));
    }

    /**
     * 防具を貫通する攻撃(アクムネスの棘)に、防御と強靭の両方が掛け算で効く。実際に被害を与えて体力の減りを測る
     * (防具は貫通するので減りは素の被害 × 2 つの倍率だけになる)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void defenseAndToughnessReducePiercingDamage(GameTestHelper helper) {
        int lv = cfg().maxLevel();
        Husk husk = armoredHusk(helper, new PoweredArmorTree(lv, lv, 0));
        DamageSource spine = modDamage(helper, "acumnes_spine");
        double expectFactor = (1.0D - Math.min(0.9D, 4 * lv * cfg().defensePerLevel()))
                * (1.0D - Math.min(0.9D, 4 * lv * cfg().piercingPerLevel()));
        float factor = PoweredArmorEffects.damageFactor(husk, spine);
        helper.assertTrue(Math.abs(factor - expectFactor) < 1e-4, "倍率が違う: " + factor + " / " + expectFactor);
        helper.runAfterDelay(2, () -> {
            float before = husk.getHealth();
            husk.hurt(spine, 10.0F);
            float lost = before - husk.getHealth();
            helper.assertTrue(Math.abs(lost - 10.0D * expectFactor) < 0.05D,
                    "体力の減りが違う: " + lost + " / " + (10.0D * expectFactor));
            husk.discard();
            helper.succeed();
        });
    }

    /** ふつうの攻撃には防御だけが効き、強靭は効かない(強靭は貫通する攻撃だけ)。奈落(/kill)には何も効かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void toughnessOnlyAffectsPiercing(GameTestHelper helper) {
        int lv = cfg().maxLevel();
        Husk husk = armoredHusk(helper, new PoweredArmorTree(0, lv, 0));
        float plain = PoweredArmorEffects.damageFactor(husk, helper.getLevel().damageSources().generic());
        helper.assertTrue(plain == 1.0F, "強靭がふつうの攻撃に効いた: " + plain);
        float voidDmg = PoweredArmorEffects.damageFactor(husk, helper.getLevel().damageSources().fellOutOfWorld());
        helper.assertTrue(voidDmg == 1.0F, "奈落に効いた: " + voidDmg);
        Husk bare = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 2, 1));
        helper.assertTrue(PoweredArmorEffects.damageFactor(bare, modDamage(helper, "acumnes_spine")) == 1.0F,
                "着ていないのに効いた");
        husk.discard();
        bare.discard();
        helper.succeed();
    }

    /** 設計図へ写せる(白紙・同じ部位だけ)。作り直すと素材 + 追加の素材が引かれ、ツリーを持った装備が出て、設計図は残る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void blueprintCopyAndRebuildKeepTree(GameTestHelper helper) {
        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_HELMET.get());
        PoweredArmorTree tree = new PoweredArmorTree(2, 1, 3);
        PoweredArmorItem.setTree(armor, tree);
        ItemStack print = new ItemStack(ModItems.POWERED_ARMOR_BLUEPRINT.get());
        helper.assertTrue(PoweredArmorWorkshop.copy(armor, print) == PoweredArmorWorkshop.Result.OK, "白紙に写せない");
        PoweredArmorBlueprint bp = PoweredArmorBlueprintItem.data(print);
        helper.assertTrue(bp != null && bp.part().equals("helmet") && bp.tree().equals(tree), "写した中身が違う: " + bp);
        helper.assertTrue(PoweredArmorWorkshop.copy(new ItemStack(ModItems.POWERED_ARMOR_BOOTS.get()), print)
                == PoweredArmorWorkshop.Result.WRONG_PART, "違う部位の設計図に上書きできた");

        RecipeHolder<PoweredArmorTableRecipe> recipe =
                PoweredArmorWorkshop.rebuildRecipe(helper.getLevel().getServer().getRecipeManager(), bp);
        helper.assertTrue(recipe != null, "兜の作成レシピが見つからない");
        helper.assertTrue(!recipe.value().rebuildExtra().isEmpty(), "作り直しの追加の素材が無い");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (var mat : recipe.value().rebuildMaterials()) {
            ItemStack one = mat.getItems()[0].copy();
            one.setCount(mat.count());
            player.getInventory().add(one);
        }
        // 1 個だけ抜くと足りない
        ItemStack last = player.getInventory().items.stream().filter(s -> !s.isEmpty() && s.getCount() > 1)
                .findFirst().orElse(ItemStack.EMPTY);
        helper.assertTrue(!last.isEmpty(), "2 個以上の素材が無い");
        last.shrink(1);
        helper.assertTrue(PoweredArmorWorkshop.rebuild(player, print, recipe) == PoweredArmorWorkshop.Result.MISSING,
                "1 個足りないのに作り直せた");
        last.grow(1);
        helper.assertTrue(PoweredArmorWorkshop.rebuild(player, print, recipe) == PoweredArmorWorkshop.Result.OK, "作り直せない");
        ItemStack made = player.getInventory().items.stream().filter(s -> s.is(ModItems.POWERED_ARMOR_HELMET.get()))
                .findFirst().orElse(ItemStack.EMPTY);
        helper.assertTrue(!made.isEmpty(), "作り直した兜が持ち物に無い");
        helper.assertTrue(PoweredArmorItem.tree(made).equals(tree), "作り直した兜のツリーが違う: " + PoweredArmorItem.tree(made));
        helper.assertTrue(PoweredArmorBlueprintItem.data(print) != null, "作り直したら設計図が消えた");
        int sunlight = countOf(player, ModItems.SUNLIGHT_ALLOY.get());
        helper.assertTrue(sunlight == 0, "素材が残っている: " + sunlight);
        helper.succeed();
    }

    /** 4 部位の作成レシピがテーブルの種類で入っていて、一覧の名前と作り直しの追加がある。白紙の設計図のレシピもある。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void armorRecipesExist(GameTestHelper helper) {
        var manager = helper.getLevel().getServer().getRecipeManager();
        List<RecipeHolder<PoweredArmorTableRecipe>> list = PoweredArmorTableCrafting.recipes(manager);
        Item[] pieces = {ModItems.POWERED_ARMOR_HELMET.get(), ModItems.POWERED_ARMOR_CHESTPLATE.get(),
                ModItems.POWERED_ARMOR_LEGGINGS.get(), ModItems.POWERED_ARMOR_BOOTS.get()};
        for (int i = 0; i < pieces.length; i++) {
            Item piece = pieces[i];
            RecipeHolder<PoweredArmorTableRecipe> h = list.stream().filter(r -> r.value().result().is(piece))
                    .findFirst().orElse(null);
            helper.assertTrue(h != null, "作成レシピが無い: " + piece);
            helper.assertTrue(!h.value().label().isEmpty() && !h.value().rebuildExtra().isEmpty(),
                    "一覧の名前か作り直しの追加が無い: " + h.id());
        }
        helper.assertTrue(list.size() >= 4 && list.get(0).value().result().is(pieces[0]), "一覧の先頭が兜でない");
        helper.assertTrue(manager.byKey(OvergrownVegetation.id("powered_armor_blueprint")).isPresent(), "白紙の設計図のレシピが無い");
        helper.succeed();
    }

    /**
     * メニュー: 作業枠はスキルツリー・設計図のタブでだけ見え、改造の番号はスキルツリーのタブでだけ効く。
     * 閉じると作業枠の中身は持ち物へ返る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void menuWorkSlotFollowsTabs(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.POWERED_ARMOR_TABLE.get().defaultBlockState());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2.0, 1.5)));
        BlockPos abs = helper.absolutePos(rel);
        AbstractContainerMenu opened = helper.getLevel().getBlockState(abs).getMenuProvider(helper.getLevel(), abs)
                .createMenu(1, player.getInventory(), player);
        helper.assertTrue(opened instanceof PoweredArmorTableMenu, "作成テーブルのメニューでない");
        PoweredArmorTableMenu menu = (PoweredArmorTableMenu) opened;
        helper.assertFalse(menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).isActive(), "作成のタブで作業枠が見えている");
        menu.clickMenuButton(player, PoweredArmorTableMenu.TAB_TREE);
        helper.assertTrue(menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).isActive(), "スキルツリーのタブで作業枠が見えない");
        helper.assertFalse(menu.getSlot(PoweredArmorTableMenu.PRINT_SLOT).isActive(), "スキルツリーのタブで設計図の枠が見えている");

        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_LEGGINGS.get());
        helper.assertTrue(menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).mayPlace(armor), "作業枠にパワードアーマーを置けない");
        helper.assertFalse(menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).mayPlace(new ItemStack(ModItems.SUNLIGHT_ALLOY.get())),
                "作業枠に合金を置けた");
        menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).set(armor);
        GearTable.LevelCost lv1 = cfg().cost(1);
        setPoints(player, lv1.points());
        giveCost(player, lv1, PoweredArmorTree.DURABILITY, 1);
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_UPGRADE + PoweredArmorTree.DURABILITY),
                "スキルツリーのタブで改造の番号が効かない");
        helper.assertTrue(PoweredArmorItem.tree(menu.workStack()).durability() == 1, "作業枠の装備の耐久が上がっていない");
        menu.clickMenuButton(player, PoweredArmorTableMenu.TAB_CRAFT);
        helper.assertFalse(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_UPGRADE), "作成のタブで改造の番号が効いた");

        // 閉じたときの返却はバニラの clearContainer が「サーバー側のプレイヤー」にしか返さないので、FakePlayer で見る
        // (テスト用の偽プレイヤー makeMockPlayer は ServerPlayer ではない)。
        net.minecraft.server.level.ServerPlayer fake =
                net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        fake.getInventory().clearContent();
        fake.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2.0, 1.5)));
        PoweredArmorTableMenu fakeMenu = (PoweredArmorTableMenu) helper.getLevel().getBlockState(abs)
                .getMenuProvider(helper.getLevel(), abs).createMenu(2, fake.getInventory(), fake);
        fakeMenu.getSlot(PoweredArmorTableMenu.WORK_SLOT).set(new ItemStack(ModItems.POWERED_ARMOR_LEGGINGS.get()));
        fakeMenu.removed(fake);
        helper.assertTrue(countOf(fake, ModItems.POWERED_ARMOR_LEGGINGS.get()) == 1, "閉じても作業枠の装備が持ち物へ返らない");
        helper.assertTrue(fakeMenu.workStack().isEmpty(), "閉じても作業枠に残っている");
        fake.getInventory().clearContent();
        helper.succeed();
    }

    private PoweredArmorGameTests() {
    }
}
