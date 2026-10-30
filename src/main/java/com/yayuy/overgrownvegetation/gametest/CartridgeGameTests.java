package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.FactorExtractorBlockEntity;
import com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorWorkshop;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.item.CartridgeContents;
import com.yayuy.overgrownvegetation.item.CartridgeFactor;
import com.yayuy.overgrownvegetation.item.CartridgeItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorEffects;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
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
 * カートリッジ・因子・因子の抽出設備(順 1 の着手順 7・8、2026-10-03)のテスト。
 * 容量・1 個あたりの効き目は {@code gear.json} の {@code poweredArmor} から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CartridgeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CartridgeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "cartridge";

    private static GearTable.PoweredArmor cfg() {
        return DesignData.gear().poweredArmor();
    }

    private static ItemStack cartridgeWith(CartridgeFactor factor, int n) {
        return PoweredArmorWorkshop.cartridgeItem(new CartridgeContents(Map.of(factor.id(), n)));
    }

    /** 因子は容量まで入り、あふれた分は因子の枠に残る。いっぱいなら入らない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void insertStopsAtCapacity(GameTestHelper helper) {
        int cap = cfg().cartridgeCapacity();
        ItemStack cart = new ItemStack(ModItems.CARTRIDGE.get());
        ItemStack factors = new ItemStack(ModItems.FACTOR_IMPACT.get(), cap + 2);
        helper.assertTrue(PoweredArmorWorkshop.insertFactors(cart, factors) == PoweredArmorWorkshop.Result.OK, "入らない");
        helper.assertTrue(CartridgeItem.contents(cart).count(CartridgeFactor.IMPACT) == cap, "容量まで入っていない: "
                + CartridgeItem.contents(cart));
        helper.assertTrue(factors.getCount() == 2, "あふれた分が残っていない: " + factors.getCount());
        helper.assertTrue(PoweredArmorWorkshop.insertFactors(cart, factors) == PoweredArmorWorkshop.Result.FULL,
                "いっぱいなのに FULL でない");
        helper.assertTrue(PoweredArmorWorkshop.insertFactors(cart, new ItemStack(Items.DIRT))
                == PoweredArmorWorkshop.Result.NO_FACTOR, "因子でないものを入れられた");
        helper.succeed();
    }

    /** 付けるとカートリッジは減って装備が中身を持つ。付け替えると前のものが戻る。外すと中身ごと戻る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void attachSwapDetach(GameTestHelper helper) {
        ItemStack armor = new ItemStack(ModItems.POWERED_ARMOR_HELMET.get());
        ItemStack first = cartridgeWith(CartridgeFactor.IMPACT, 4);
        ItemStack old = PoweredArmorWorkshop.attach(armor, first);
        helper.assertTrue(old != null && old.isEmpty(), "最初の取り付けで何か返った: " + old);
        helper.assertTrue(first.isEmpty(), "付けたカートリッジが残っている");
        helper.assertTrue(PoweredArmorItem.cartridge(armor) != null
                && PoweredArmorItem.cartridge(armor).count(CartridgeFactor.IMPACT) == 4, "装備が中身を持っていない");

        ItemStack second = cartridgeWith(CartridgeFactor.PIERCE, 2);
        old = PoweredArmorWorkshop.attach(armor, second);
        helper.assertTrue(old != null && CartridgeItem.contents(old).count(CartridgeFactor.IMPACT) == 4,
                "付け替えで前のカートリッジが戻らない: " + old);
        helper.assertTrue(PoweredArmorItem.cartridge(armor).count(CartridgeFactor.PIERCE) == 2, "付け替えた中身が違う");

        ItemStack back = PoweredArmorWorkshop.detach(armor);
        helper.assertTrue(back != null && CartridgeItem.contents(back).count(CartridgeFactor.PIERCE) == 2, "外した中身が違う");
        helper.assertTrue(!armor.has(ModDataComponents.POWERED_ARMOR_CARTRIDGE.get()), "外したのに装備に残っている");
        helper.assertTrue(PoweredArmorWorkshop.detach(armor) == null, "付いていないのに外せた");
        helper.succeed();
    }

    private static Husk huskWithCartridges(GameTestHelper helper, BlockPos pos, int pieces, CartridgeFactor factor, int each) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, pos);
        husk.setNoAi(true);
        Item[] items = {ModItems.POWERED_ARMOR_HELMET.get(), ModItems.POWERED_ARMOR_CHESTPLATE.get(),
                ModItems.POWERED_ARMOR_LEGGINGS.get(), ModItems.POWERED_ARMOR_BOOTS.get()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            ItemStack s = new ItemStack(items[i]);
            if (i < pieces) {
                s.set(ModDataComponents.POWERED_ARMOR_CARTRIDGE.get(), new CartridgeContents(Map.of(factor.id(), each)));
            }
            husk.setItemSlot(slots[i], s);
        }
        return husk;
    }

    /**
     * 4 部位のカートリッジの効き目は足し算(部位ごと最大 20%、合計 80%。ユーザー指定)。
     * 効くのは種類の合う攻撃だけ(近接の因子は飛び道具に効かない)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void cartridgesAddUpAcrossPieces(GameTestHelper helper) {
        int cap = cfg().cartridgeCapacity();
        double per = cfg().factorPerItem();
        Husk four = huskWithCartridges(helper, new BlockPos(2, 2, 2), 4, CartridgeFactor.IMPACT, cap);
        float melee = PoweredArmorEffects.damageFactor(four, helper.getLevel().damageSources().source(DamageTypes.MOB_ATTACK));
        double expect4 = 1.0D - Math.min(0.9D, 4 * cap * per);
        helper.assertTrue(Math.abs(melee - expect4) < 1e-4, "4 部位の足し算が違う: " + melee + " / " + expect4);
        float arrow = PoweredArmorEffects.damageFactor(four, helper.getLevel().damageSources().source(DamageTypes.ARROW));
        helper.assertTrue(arrow == 1.0F, "近接の因子が飛び道具に効いた: " + arrow);

        Husk two = huskWithCartridges(helper, new BlockPos(4, 2, 4), 2, CartridgeFactor.PIERCE, cap);
        float pierce = PoweredArmorEffects.damageFactor(two, helper.getLevel().damageSources().source(DamageTypes.ARROW));
        double expect2 = 1.0D - Math.min(0.9D, 2 * cap * per);
        helper.assertTrue(Math.abs(pierce - expect2) < 1e-4, "2 部位の刺突が違う: " + pierce + " / " + expect2);
        float spine = PoweredArmorEffects.damageFactor(two, helper.getLevel().damageSources().source(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                        OvergrownVegetation.id("acumnes_spine"))));
        helper.assertTrue(Math.abs(spine - expect2) < 1e-4, "アクムネスの棘に刺突が効かない: " + spine);
        four.discard();
        two.discard();
        helper.succeed();
    }

    /** 抽出設備: レシピの数だけそろうと時間をかけて因子になる。足りなければ進まない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void extractorTurnsDropsIntoFactors(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.FACTOR_EXTRACTOR.get().defaultBlockState());
        FactorExtractorBlockEntity be = (FactorExtractorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        helper.assertTrue(be != null, "抽出設備のブロックエンティティが無い");
        // 素材はレシピから引く(着手順 11 で仮置きのドロップから差し替えたので、決め打ちにしない)
        RecipeHolder<FactorExtractionRecipe> impact = helper.getLevel().getServer().getRecipeManager()
                .getAllRecipesFor(ModRecipes.FACTOR_EXTRACTION_TYPE.get()).stream()
                .filter(h -> h.value().result().is(ModItems.FACTOR_IMPACT.get())).findFirst().orElse(null);
        helper.assertTrue(impact != null, "衝撃の因子の抽出レシピが無い");
        ItemStack input = new ItemStack(impact.value().ingredient().getItems()[0].getItem(), 9);
        RecipeHolder<FactorExtractionRecipe> holder = FactorExtractorBlockEntity.recipeFor(helper.getLevel(), input);
        helper.assertTrue(holder != null, "衝撃の因子の素材で抽出レシピが引けない");
        FactorExtractionRecipe r = holder.value();
        be.setItem(FactorExtractorBlockEntity.INPUT, input);
        for (int i = 0; i < r.time() * 2; i++) {
            be.step(helper.getLevel());
        }
        ItemStack out = be.getItem(FactorExtractorBlockEntity.OUTPUT);
        helper.assertTrue(out.is(r.result().getItem()) && out.getCount() == 2 * r.result().getCount(),
                "2 回ぶん出ていない: " + out);
        int left = 9 - 2 * r.count();
        helper.assertTrue(be.getItem(FactorExtractorBlockEntity.INPUT).getCount() == left, "素材の減りが違う");
        if (left < r.count()) {
            for (int i = 0; i < r.time(); i++) {
                helper.assertFalse(be.step(helper.getLevel()), "素材が足りないのに動いた");
            }
        }
        helper.assertFalse(be.canPlaceItem(FactorExtractorBlockEntity.INPUT, new ItemStack(Items.DIRT)), "土を入れられた");
        helper.assertFalse(be.canPlaceItem(FactorExtractorBlockEntity.OUTPUT, input), "出る枠に入れられた");
        helper.assertTrue(be.getSlotsForFace(Direction.DOWN)[0] == FactorExtractorBlockEntity.OUTPUT, "下から出る枠を抜けない");
        helper.assertTrue(be.getSlotsForFace(Direction.UP)[0] == FactorExtractorBlockEntity.INPUT, "上から入れる枠へ入らない");
        helper.succeed();
    }

    /** 装着タブの流れ: 因子を入れる → 付ける → 外す(ボタンの番号で)。装着のタブ以外では効かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void fitTabFlow(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.POWERED_ARMOR_TABLE.get().defaultBlockState());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2.0, 1.5)));
        BlockPos abs = helper.absolutePos(rel);
        AbstractContainerMenu opened = helper.getLevel().getBlockState(abs).getMenuProvider(helper.getLevel(), abs)
                .createMenu(1, player.getInventory(), player);
        PoweredArmorTableMenu menu = (PoweredArmorTableMenu) opened;
        menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).set(new ItemStack(ModItems.POWERED_ARMOR_CHESTPLATE.get()));
        menu.getSlot(PoweredArmorTableMenu.ATTACH_SLOT).set(new ItemStack(ModItems.CARTRIDGE.get()));
        menu.getSlot(PoweredArmorTableMenu.FACTOR_SLOT).set(new ItemStack(ModItems.FACTOR_SPORE.get(), 3));
        helper.assertFalse(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_INSERT), "作成のタブで入れられた");
        menu.clickMenuButton(player, PoweredArmorTableMenu.TAB_FIT);
        helper.assertTrue(menu.getSlot(PoweredArmorTableMenu.ATTACH_SLOT).isActive()
                && menu.getSlot(PoweredArmorTableMenu.WORK_SLOT).isActive(), "装着のタブで枠が見えない");
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_INSERT), "因子を入れられない");
        helper.assertTrue(CartridgeItem.contents(menu.attachStack()).count(CartridgeFactor.SPORE) == 3, "入った数が違う");
        helper.assertTrue(menu.factorStack().isEmpty(), "因子の枠が空にならない");
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_ATTACH), "付けられない");
        helper.assertTrue(menu.attachStack().isEmpty(), "付けたのにカートリッジの枠に残っている");
        helper.assertTrue(PoweredArmorItem.cartridge(menu.workStack()) != null, "装備にカートリッジが付いていない");
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_DETACH), "外せない");
        helper.assertTrue(CartridgeItem.contents(menu.attachStack()).count(CartridgeFactor.SPORE) == 3, "外した中身が違う");
        helper.assertFalse(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_DETACH), "付いていないのに外せた");
        helper.succeed();
    }

    /** 5 種の抽出レシピ・カートリッジ・抽出設備のレシピがあり、同梱の研究ツリーの枠が抽出設備とカートリッジを封印する。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void recipesAndResearchExist(GameTestHelper helper) {
        var manager = helper.getLevel().getServer().getRecipeManager();
        List<RecipeHolder<FactorExtractionRecipe>> list = manager.getAllRecipesFor(ModRecipes.FACTOR_EXTRACTION_TYPE.get());
        for (CartridgeFactor f : CartridgeFactor.values()) {
            helper.assertTrue(list.stream().anyMatch(h -> h.value().result().is(f.item())), "抽出レシピが無い: " + f.id());
        }
        helper.assertTrue(manager.byKey(OvergrownVegetation.id("cartridge")).isPresent(), "カートリッジのレシピが無い");
        helper.assertTrue(manager.byKey(OvergrownVegetation.id("factor_extractor")).isPresent(), "抽出設備のレシピが無い");

        JsonObject nodes;
        try (InputStream in = CartridgeGameTests.class.getResourceAsStream("/overgrown_vegetation/design/research.json")) {
            nodes = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("nodes");
        } catch (Exception e) {
            throw new AssertionError("research.json を読めない: " + e);
        }
        boolean extractor = false;
        boolean cartridge = false;
        for (String id : nodes.keySet()) {
            JsonArray unlocks = nodes.getAsJsonObject(id).getAsJsonArray("unlocks");
            if (unlocks == null) {
                continue;
            }
            for (JsonElement u : unlocks) {
                extractor |= u.getAsString().equals("factor_extractor");
                cartridge |= u.getAsString().equals("cartridge");
            }
        }
        helper.assertTrue(extractor && cartridge, "研究ツリーが抽出設備かカートリッジを封印していない");
        helper.succeed();
    }

    private CartridgeGameTests() {
    }
}
