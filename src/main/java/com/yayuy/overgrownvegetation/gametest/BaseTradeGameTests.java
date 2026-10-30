package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.base.build.AllyTrust;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.build.BaseTrade;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.data.BaseTradeConfig;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.menu.BaseTerminalMenu;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 信頼・寄付・取引(順 2 の着手順 5、2026-10-07)の試験。期待値は設計値({@link DesignData#defense()} の {@code baseTrade()})から引く。
 * 基地は台帳に載せるだけ(建てない)。信頼は偽プレイヤーの UUID(試験ごとに違う)で持つので、ほかの試験と混ざらない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseTradeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseTradeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);

    /** 試験ごとに別の中心(台帳に載せるだけなので、建物とは重ならない)。 */
    private static BlockPos far(int n) {
        return new BlockPos(-26624 - n * 1024, 64, 25600);
    }

    private static BaseSite addSite(ServerLevel level, int n, BaseType type) {
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = data.add(Level.OVERWORLD, far(n), 0, 4343L, 1, FLAT, true, type);
        BaseEconomy.start(data, site, BaseEconomy.today(level.getServer()));
        return site;
    }

    private static BaseTradeConfig cfg() {
        return DesignData.defense().baseTrade();
    }

    private static int count(Player player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static SimpleContainer box(ItemStack... stacks) {
        SimpleContainer box = new SimpleContainer(9);
        for (int i = 0; i < stacks.length; i++) {
            box.setItem(i, stacks[i]);
        }
        return box;
    }

    private static int indexOf(List<BaseTradeConfig.Offer> offers, String item, int minTrustAtLeast) {
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).item().equals(item) && offers.get(i).minTrust() >= minTrustAtLeast) {
                return i;
            }
        }
        return -1;
    }

    /** 値打ちの決め方: 表 → 研究ポイント → 食べ物(害のある物は 0)。軍票と表に無い物は 0。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void valueRules(GameTestHelper helper) {
        BaseTradeConfig c = cfg();
        helper.assertTrue(BaseTrade.unitValue(new ItemStack(Items.IRON_INGOT)) == c.values.get("minecraft:iron_ingot"), "鉄インゴットの値打ちが表と違う");
        helper.assertTrue(BaseTrade.unitValue(new ItemStack(AllyItems.MILITARY_SCRIP.get())) == 0.0D, "軍票を受け取る");
        helper.assertTrue(BaseTrade.unitValue(new ItemStack(Items.DIRT)) == 0.0D, "土を受け取る");
        double bread = Items.BREAD.getDefaultInstance().get(DataComponents.FOOD).nutrition() * c.foodValuePerNutrition;
        helper.assertTrue(Math.abs(BaseTrade.unitValue(new ItemStack(Items.BREAD)) - bread) < 1e-9, "パンの値打ちが満腹度 × 係数でない");
        helper.assertTrue(BaseTrade.unitValue(new ItemStack(Items.ROTTEN_FLESH)) == 0.0D, "腐った肉(害のある食べ物)を受け取る");
        helper.assertTrue(BaseTrade.unitValue(new ItemStack(Items.SPIDER_EYE)) == 0.0D, "クモの目(害のある食べ物)を受け取る");
        ItemStack fiber = new ItemStack(ModItems.PLANT_FIBER.get());
        double fiberWant = DesignData.research().pointsFor(fiber) * c.researchPointValue;
        helper.assertTrue(fiberWant > 0.0D && BaseTrade.unitValue(fiber) == fiberWant, "繊維質(研究ポイントのある物)の値打ちが違う");
        // 汚染された肉は食べ物としては受け取らない(研究ポイントがあればその値打ち、無ければ 0)。
        // GameTest のサーバーは run/config の写しを読むので、写しに汚染された素材の研究ポイントが無いと 0 になる
        ItemStack meat = new ItemStack(ModItems.CONTAMINATED_MEAT.get());
        helper.assertTrue(BaseTrade.unitValue(meat) == DesignData.research().pointsFor(meat) * c.researchPointValue,
                "汚染された肉が食べ物として数えられた: " + BaseTrade.unitValue(meat));
        helper.assertTrue(BaseTrade.accepts(new ItemStack(Items.IRON_INGOT)) && !BaseTrade.accepts(new ItemStack(Items.DIRT)), "受け取るかの判定が違う");
        helper.succeed();
    }

    /** 寄付: 値打ちが貯蓄へ、信頼は値打ち × trustPerValue を 1 日の上限で切った分、お礼の軍票は値打ち ÷ scripValue × donationThanks(端数は持ち越し)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void donationAddsStockTrustAndThanks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseTradeConfig c = cfg();
        BaseSite site = addSite(level, 0, BaseType.PURIFICATION);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        AllyTrust trust = AllyTrust.get(server);
        try {
            BaseEconomy.setStock(BaseSites.get(server), site, 0);
            SimpleContainer box = box(new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.DIRT, 5));
            double value = 64 * BaseTrade.unitValue(new ItemStack(Items.IRON_INGOT));
            BaseTrade.Outcome out = BaseTrade.donate(server, site, p, box);
            helper.assertTrue(out.ok(), "寄付できない: " + out.result());
            helper.assertTrue(box.getItem(0).isEmpty() && box.getItem(1).getCount() == 5, "受け取る物だけを取り出していない");
            int cap = DesignData.defense().baseStockCap(1);
            helper.assertTrue(site.stock() == Math.min(cap, (int) Math.floor(value)), "値打ちが貯蓄に入っていない: " + site.stock());
            double wantTrust = Math.min(value * c.trustPerValue, c.trustDailyCap);
            helper.assertTrue(Math.abs(trust.trust(p.getUUID()) - wantTrust) < 1e-6, "信頼が違う: " + trust.trust(p.getUUID()) + " / " + wantTrust);
            double thanks = value / c.scripValue * c.donationThanks;
            int wantScrip = (int) Math.floor(thanks + 1e-9);
            helper.assertTrue(BaseTrade.countScrip(p) == wantScrip, "お礼の軍票が違う: " + BaseTrade.countScrip(p) + " / " + wantScrip);
            helper.assertTrue(Math.abs(trust.scripRemainder(p.getUUID()) - (thanks - wantScrip)) < 1e-6, "軍票の端数を持ち越していない");
            helper.assertTrue(c.donationThanks < 1.0D, "寄付のお礼が売るのと同じか多い(ユーザー指定「売るより少ない」)");
            helper.assertTrue(BaseTrade.donate(server, site, p, new SimpleContainer(9)).result() == BaseTrade.Result.NOTHING, "空の枠で寄付できた");
        } finally {
            trust.forget(p.getUUID());
            BaseSites.get(server).remove(site);
        }
        helper.succeed();
    }

    /** 1 日の上限と取引の段: 信頼が取引の段に届くまで売り買いできない。翌日はまた上がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void dailyCapAndTradeLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseTradeConfig c = cfg();
        BaseSite site = addSite(level, 1, BaseType.PURIFICATION);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        AllyTrust trust = AllyTrust.get(server);
        try {
            SimpleContainer box = box(new ItemStack(Items.IRON_INGOT, 16));
            helper.assertTrue(BaseTrade.sell(server, site, p, box).result() == BaseTrade.Result.LOCKED, "信頼 0 で売れた");
            helper.assertTrue(box.getItem(0).getCount() == 16, "売れなかったのに枠の物が消えた");
            p.getInventory().add(new ItemStack(AllyItems.MILITARY_SCRIP.get(), 64));
            helper.assertTrue(BaseTrade.buy(server, site, p, 0).result() == BaseTrade.Result.LOCKED, "信頼 0 で買えた");

            BaseTrade.Outcome big = BaseTrade.donate(server, site, p, box(new ItemStack(Items.DIAMOND, 64)));
            helper.assertTrue(big.ok() && Math.abs(big.trust() - Math.min(c.trustDailyCap, 64 * BaseTrade.unitValue(new ItemStack(Items.DIAMOND)) * c.trustPerValue)) < 1e-6,
                    "1 日の上限で切られていない: +" + big.trust());
            double now = trust.trust(p.getUUID());
            helper.assertTrue(BaseTrade.donate(server, site, p, box(new ItemStack(Items.DIAMOND, 64))).trust() == 0.0D || now < c.trustDailyCap,
                    "上限に達した日にまだ上がる");
            if (c.trustDailyCap < c.trustTrade) {
                helper.assertTrue(BaseTrade.sell(server, site, p, box(new ItemStack(Items.IRON_INGOT, 1))).result() == BaseTrade.Result.LOCKED,
                        "1 日で取引の段に届いた(「何度か寄付してから」にならない)");
            }
            long today = BaseEconomy.today(server);
            double before = trust.trust(p.getUUID());
            double more = trust.gain(p.getUUID(), 1000.0D, today + 1, c);
            helper.assertTrue(Math.abs(more - Math.min(c.trustDailyCap, c.trustMax - before)) < 1e-6, "翌日に上がらない: +" + more);

            trust.set(p.getUUID(), c.trustTrade, c);
            BaseTrade.Outcome sold = BaseTrade.sell(server, site, p, box(new ItemStack(Items.IRON_INGOT, 1)));
            helper.assertTrue(sold.ok(), "取引の段で売れない: " + sold.result());
        } finally {
            trust.forget(p.getUUID());
            BaseSites.get(server).remove(site);
        }
        helper.succeed();
    }

    /** 売却と購入: 売ると値打ち ÷ scripValue の軍票、買うと値段の軍票と品の値打ちの貯蓄が減る。足りなければ買えない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void sellAndBuy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseTradeConfig c = cfg();
        BaseSite site = addSite(level, 2, BaseType.PURIFICATION);
        BaseSites data = BaseSites.get(server);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        AllyTrust trust = AllyTrust.get(server);
        try {
            trust.set(p.getUUID(), c.trustGoods, c);
            BaseEconomy.setStock(data, site, 0);
            double value = 64 * BaseTrade.unitValue(new ItemStack(Items.IRON_INGOT));
            BaseTrade.Outcome sold = BaseTrade.sell(server, site, p, box(new ItemStack(Items.IRON_INGOT, 64)));
            int wantScrip = (int) Math.floor(value / c.scripValue + 1e-9);
            helper.assertTrue(sold.ok() && BaseTrade.countScrip(p) == wantScrip, "売った軍票が違う: " + BaseTrade.countScrip(p) + " / " + wantScrip);
            helper.assertTrue(site.stock() == (int) Math.floor(value), "売った値打ちが貯蓄に入っていない: " + site.stock());
            helper.assertTrue(sold.scrip() > 0 && sold.trust() == 0.0D, "売っても信頼が上がった");

            List<BaseTradeConfig.Offer> offers = BaseTrade.offers(site);
            int idx = indexOf(offers, "overgrown_vegetation:autocannon_round", 0);
            helper.assertTrue(idx >= 0, "機関砲弾が品に無い");
            BaseTradeConfig.Offer o = offers.get(idx);
            ItemStack goods = BaseTrade.stackOf(o);
            int cost = BaseTrade.cost(o, goods, c);
            int price = c.price(cost, trust.trust(p.getUUID()));
            helper.assertTrue(price <= wantScrip, "試験の前提: 売った軍票で弾が買えない(" + price + ")");
            helper.assertTrue(value / c.scripValue > 0 && cost / (double) c.scripValue < price, "売り戻すと得をする値段");
            int stockBefore = site.stock();
            BaseTrade.Outcome bought = BaseTrade.buy(server, site, p, idx);
            helper.assertTrue(bought.ok(), "買えない: " + bought.result());
            helper.assertTrue(BaseTrade.countScrip(p) == wantScrip - price, "払った軍票が違う");
            helper.assertTrue(site.stock() == stockBefore - cost, "品の値打ちの分だけ貯蓄が減っていない: " + site.stock());
            helper.assertTrue(count(p, goods.getItem()) == o.count(), "品を受け取っていない");

            BaseEconomy.setStock(data, site, 0);
            int scripBefore = BaseTrade.countScrip(p);
            helper.assertTrue(BaseTrade.buy(server, site, p, idx).result() == BaseTrade.Result.NO_STOCK, "貯蓄が足りないのに買えた");
            helper.assertTrue(BaseTrade.countScrip(p) == scripBefore, "買えなかったのに軍票が減った");

            BaseEconomy.setStock(data, site, 1000);
            p.getInventory().clearContent();
            helper.assertTrue(BaseTrade.buy(server, site, p, idx).result() == BaseTrade.Result.NO_SCRIP, "軍票が無いのに買えた");

            trust.set(p.getUUID(), c.trustTrade, c);
            int goodsIdx = -1;
            for (int i = 0; i < offers.size(); i++) {
                if (offers.get(i).minTrust() > c.trustTrade) {
                    goodsIdx = i;
                    break;
                }
            }
            if (goodsIdx >= 0) {
                p.getInventory().add(new ItemStack(AllyItems.MILITARY_SCRIP.get(), 64));
                helper.assertTrue(BaseTrade.buy(server, site, p, goodsIdx).result() == BaseTrade.Result.LOCKED, "信頼が足りない品を買えた");
            }
        } finally {
            trust.forget(p.getUUID());
            BaseSites.get(server).remove(site);
        }
        helper.succeed();
    }

    /** 野戦病院だけ回復アイテム(治癒のポーション)を売る(ユーザー指定 10-01「回復アイテムを買える」)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hospitalSellsHealing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSite hospital = addSite(level, 3, BaseType.HOSPITAL);
        BaseSite defense = addSite(level, 4, BaseType.DEFENSE);
        try {
            ItemStack healing = ItemStack.EMPTY;
            for (BaseTradeConfig.Offer o : BaseTrade.offers(hospital)) {
                ItemStack s = BaseTrade.stackOf(o);
                PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
                if (pc != null && pc.is(Potions.HEALING)) {
                    healing = s;
                }
            }
            helper.assertTrue(!healing.isEmpty() && healing.is(Items.POTION), "野戦病院に治癒のポーションが無い");
            for (BaseTradeConfig.Offer o : BaseTrade.offers(defense)) {
                helper.assertTrue(o.potion() == null, "野戦病院でない基地がポーションを売る: " + o.potion());
                helper.assertTrue(!BaseTrade.stackOf(o).isEmpty(), "品のアイテムが無い: " + o.item());
            }
        } finally {
            BaseSites.get(level.getServer()).remove(hospital);
            BaseSites.get(level.getServer()).remove(defense);
        }
        helper.succeed();
    }

    /** 陥落した基地とは何もできない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void fallenBaseRefuses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseTradeConfig c = cfg();
        BaseSite site = addSite(level, 5, BaseType.PURIFICATION);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        AllyTrust trust = AllyTrust.get(server);
        try {
            trust.set(p.getUUID(), c.trustMax, c);
            BaseEconomy.fall(server, BaseSites.get(server), site, BaseEconomy.Reason.GARRISON_LOST);
            helper.assertTrue(site.fallen(), "試験の前提: 陥落しない");
            SimpleContainer box = box(new ItemStack(Items.IRON_INGOT, 8));
            helper.assertTrue(BaseTrade.donate(server, site, p, box).result() == BaseTrade.Result.FALLEN, "陥落した基地に寄付できた");
            helper.assertTrue(BaseTrade.sell(server, site, p, box).result() == BaseTrade.Result.FALLEN, "陥落した基地に売れた");
            helper.assertTrue(BaseTrade.buy(server, site, p, 0).result() == BaseTrade.Result.FALLEN, "陥落した基地で買えた");
            helper.assertTrue(box.getItem(0).getCount() == 8, "断ったのに枠の物が消えた");
        } finally {
            trust.forget(p.getUUID());
            BaseSites.get(server).remove(site);
        }
        helper.succeed();
    }

    /**
     * 管理端末の画面: タブが合っているときだけボタンが効く・渡す枠は受け取る物だけ・閉じると枠の物が持ち物へ戻る。
     * 閉じたときの返却はバニラがサーバーのプレイヤーにだけ行うので、偽プレイヤーではなく NeoForge の FakePlayer で見る
     * (メモリ「GameTest の偽プレイヤーは ServerPlayer でない」)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void terminalMenuButtons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSite site = addSite(level, 6, BaseType.PURIFICATION);
        Player p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("ov_trade_menu_test".getBytes()), "ov_trade_menu_test"));
        p.getInventory().clearContent();
        AllyTrust trust = AllyTrust.get(server);
        try {
            BaseEconomy.setStock(BaseSites.get(server), site, 0);
            BaseTerminalMenu menu = new BaseTerminalMenu(1, p.getInventory(),
                    ContainerLevelAccess.create(level, helper.absolutePos(new BlockPos(1, 2, 1))), BaseTerminalMenu.Snapshot.of(server, site));
            helper.assertTrue(!menu.slots.get(BaseTerminalMenu.GIVE_START).isActive(), "状態のタブで渡す枠が見えている");
            helper.assertTrue(!menu.clickMenuButton(p, BaseTerminalMenu.BUTTON_DONATE), "状態のタブで寄付が効いた");
            helper.assertTrue(menu.clickMenuButton(p, BaseTerminalMenu.TAB_DONATE) && menu.tab() == BaseTerminalMenu.TAB_DONATE, "寄付のタブにならない");
            helper.assertTrue(menu.slots.get(BaseTerminalMenu.GIVE_START).isActive(), "寄付のタブで渡す枠が見えない");
            helper.assertTrue(!menu.slots.get(BaseTerminalMenu.GIVE_START).mayPlace(new ItemStack(Items.DIRT))
                    && menu.slots.get(BaseTerminalMenu.GIVE_START).mayPlace(new ItemStack(Items.IRON_INGOT)), "渡す枠が受け取らない物を入れる");
            menu.giveSlots().setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            helper.assertTrue(menu.clickMenuButton(p, BaseTerminalMenu.BUTTON_DONATE), "寄付のボタンが効かない");
            helper.assertTrue(site.stock() == (int) Math.floor(32 * BaseTrade.unitValue(new ItemStack(Items.IRON_INGOT))), "画面の寄付が貯蓄に入っていない");
            helper.assertTrue(menu.get(BaseTerminalMenu.D_LAST_CODE) == BaseTerminalMenu.ACTION_DONATE * 16 + BaseTrade.Result.OK.ordinal(),
                    "結果が画面へ送られていない");
            helper.assertTrue(menu.get(BaseTerminalMenu.D_STOCK) == site.stock(), "貯蓄が画面へ送られていない");
            menu.giveSlots().setItem(0, new ItemStack(Items.IRON_INGOT, 5));
            int before = count(p, Items.IRON_INGOT);
            menu.removed(p);
            helper.assertTrue(count(p, Items.IRON_INGOT) == before + 5, "閉じても枠の物が持ち物へ戻らない");
        } finally {
            trust.forget(p.getUUID());
            BaseSites.get(server).remove(site);
        }
        helper.succeed();
    }

    /** 同梱の defense.json の alliedBase.trade が Java の既定値と同じ(設計値は 3 か所 —— どちらかだけ直すと食い違う)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bundledTradeJsonMatchesDefault(GameTestHelper helper) {
        DefenseTable bundled;
        try (java.io.InputStream in = BaseTradeGameTests.class.getResourceAsStream("/overgrown_vegetation/design/defense.json")) {
            bundled = DefenseTable.fromJson(new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), DefenseTable.Json.class));
        } catch (Exception e) {
            throw new AssertionError("design/defense.json が読めない: " + e);
        }
        BaseTradeConfig b = bundled.baseTrade();
        BaseTradeConfig d = BaseTradeConfig.DEFAULT;
        helper.assertTrue(b.trustPerValue == d.trustPerValue && b.trustDailyCap == d.trustDailyCap && b.trustMax == d.trustMax
                && b.trustTrade == d.trustTrade && b.trustGoods == d.trustGoods && b.trustEscort == d.trustEscort
                && b.trustReinforce == d.trustReinforce && b.discounts.equals(d.discounts) && b.scripValue == d.scripValue
                && b.donationThanks == d.donationThanks && b.buyMarkup == d.buyMarkup && b.foodValuePerNutrition == d.foodValuePerNutrition
                && b.researchPointValue == d.researchPointValue, "defense.json の alliedBase.trade が既定値と違う");
        helper.assertTrue(b.values.equals(d.values) && b.deny.equals(d.deny) && b.offers.equals(d.offers),
                "defense.json の値打ちの表・受け取らない物・品が既定値と違う");
        helper.assertTrue(d.trustTrade > d.trustDailyCap, "既定値: 1 日で取引の段に届く(「何度か寄付してから」にならない)");
        helper.succeed();
    }
}
