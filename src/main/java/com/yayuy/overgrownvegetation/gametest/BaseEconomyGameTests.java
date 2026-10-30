package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.build.BaseConstruction;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 同盟軍の基地の貯蓄と補給値・留守中の計算・仮の陥落(順 2 の着手順 1、2026-10-04、{@link BaseEconomy})。
 *
 * <p>台帳に載せた基地は<b>同じ tick のうちに外す</b>(tick の終わりの精算・陥落の後片付け・除去装置の後付けと混ざらない。
 * テスト場を中心にした基地の後片付けは、隣のテスト場の機関砲台まで空にしてしまう)。汚染の台帳とプレイヤーの有無は差し替えで与え、本物は触らない。
 * 期待値は設計値({@link DesignData#defense()})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseEconomyGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseEconomyGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);

    /** 設備を置く遠い基地の中心(ほかの基地の試験の 25600, 25600 と重ねない。毎回同じ場所)。 */
    private static final BlockPos FAR = new BlockPos(-25600, 64, 25600);

    private static BaseSite addSite(ServerLevel level, BlockPos center, int lv) {
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = data.add(Level.OVERWORLD, center, 0, 4242L, lv, FLAT, true, BaseType.PURIFICATION);
        BaseEconomy.start(data, site, BaseEconomy.today(level.getServer()));
        return site;
    }

    private static void clearOverrides() {
        BaseEconomy.presenceForTests = null;
        BaseEconomy.ledgerForTests = null;
        BaseEconomy.guardedForTests = null;
    }

    /** 回復量・減る量・貯蓄への当て方の数式が設計値どおり。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void formulasFollowDesign(GameTestHelper helper) {
        DefenseTable t = DesignData.defense();
        for (int lv = 1; lv <= 5; lv++) {
            int rec = BaseEconomy.recovery(lv, 0, 0.0D);
            helper.assertTrue(rec == t.baseSupplyStart(lv), "レベル " + lv + " の始まりの補給値が違う: " + rec);
            helper.assertTrue(t.baseStockCap(lv) >= (lv > 1 ? t.baseStockCap(lv - 1) : 0), "レベル " + lv + " で上限が下がる");
        }
        int grown = BaseEconomy.recovery(1, 100, 0.0D);
        int want = (int) Math.round(t.baseSupplyStart(1) * (1.0D + t.baseSupplyGrowthPerDay() * 100));
        helper.assertTrue(grown == want, "100 日目の補給値が違う: " + grown + " / " + want);
        int bonus = BaseEconomy.recovery(1, 0, t.baseConnectionBonus());
        helper.assertTrue(bonus == (int) Math.round(t.baseSupplyStart(1) * (1.0D + t.baseConnectionBonus())), "つながりの割り増しが違う: " + bonus);

        helper.assertTrue(BaseEconomy.apply(5000, 10000, 3000, 0) == 8000, "回復が足されない");
        helper.assertTrue(BaseEconomy.apply(9000, 10000, 3000, 0) == 10000, "上限で止まらない");
        helper.assertTrue(BaseEconomy.apply(12000, 10000, 3000, 0) == 12000, "上限を超えた貯蓄が削られた");
        helper.assertTrue(BaseEconomy.apply(1000, 10000, 1000, 3000) == 0, "0 で止まらない");
        helper.assertTrue(BaseEconomy.apply(10000, 10000, 3000, 3400) == 9600, "差し引きで減らない");

        ServerLevel level = helper.getLevel();
        BaseSite site = addSite(level, FAR, 1);
        try {
            helper.assertTrue(BaseEconomy.decline(site, 5, 0.0D, false) == 0, "周りに汚染が無いのに減る");
            helper.assertTrue(BaseEconomy.decline(site, 5, 1.0D, true) == 0, "プレイヤーがいるのに減る");
            for (long day = 1; day <= 20; day++) {
                int d = BaseEconomy.decline(site, day, 1.0D, false);
                helper.assertTrue(d >= t.baseDeclineMin() && d <= t.baseDeclineMax(), day + " 日目の減る量が範囲の外: " + d);
                helper.assertTrue(d == BaseEconomy.decline(site, day, 1.0D, false), "同じ日の減る量が数えるたびに変わる");
                int half = BaseEconomy.decline(site, day, 0.5D, false);
                helper.assertTrue(Math.abs(half - d / 2.0D) <= 1.0D, "割合 0.5 で半分にならない: " + half + " / " + d);
            }
            helper.assertTrue(site.stock() == (int) Math.round(t.baseStockCap(1) * t.baseStartStockFraction()), "最初の貯蓄が違う: " + site.stock());
        } finally {
            BaseSites.get(level.getServer()).remove(site);
        }
        helper.succeed();
    }

    /** 周りの汚染の割合: 輪は基地のチャンクを数えず、円は数える。除去装置の守りは分母から外せる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void surroundRatioShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSite site = addSite(level, FAR, 1);
        try {
            int[] fp = BaseEconomy.footprintChunks(site);
            DefenseTable.SurroundShape ring = DefenseTable.SurroundShape.RING;
            DefenseTable.SurroundShape circle = DefenseTable.SurroundShape.CIRCLE;
            java.util.function.LongPredicate inside = k -> {
                int x = ChunkPos.getX(k);
                int z = ChunkPos.getZ(k);
                return x >= fp[0] && x <= fp[2] && z >= fp[1] && z <= fp[3];
            };
            java.util.function.LongPredicate outside = k -> !inside.test(k);
            helper.assertTrue(BaseEconomy.surroundRatio(site, ring, 8, k -> true, k -> false) == 1.0D, "全部汚染で輪が 100% でない");
            helper.assertTrue(BaseEconomy.surroundRatio(site, ring, 8, k -> false, k -> false) == 0.0D, "汚染なしで輪が 0% でない");
            helper.assertTrue(BaseEconomy.surroundRatio(site, ring, 8, outside, k -> false) == 1.0D, "輪が基地のチャンクを数えている");
            double c = BaseEconomy.surroundRatio(site, circle, 12, outside, k -> false);
            helper.assertTrue(c > 0.0D && c < 1.0D, "円が基地のチャンクを数えていない: " + c);
            // 東だけ汚染: 輪の 1/4〜1/2
            java.util.function.LongPredicate east = k -> ChunkPos.getX(k) > fp[2];
            double e = BaseEconomy.surroundRatio(site, ring, 8, east, k -> false);
            helper.assertTrue(e > 0.2D && e < 0.5D, "東だけ汚染した輪の割合が変: " + e);
            // 外周から 3 チャンクまでを守り(除去装置の範囲)にすると、残りが全部汚染なら 100%
            java.util.function.LongPredicate near = k -> {
                int x = ChunkPos.getX(k);
                int z = ChunkPos.getZ(k);
                int dx = x < fp[0] ? fp[0] - x : x > fp[2] ? x - fp[2] : 0;
                int dz = z < fp[1] ? fp[1] - z : z > fp[3] ? z - fp[3] : 0;
                return dx * dx + dz * dz <= 9;
            };
            java.util.function.LongPredicate farOnly = k -> !near.test(k);
            double raw = BaseEconomy.surroundRatio(site, ring, 8, farOnly, k -> false);
            double skip = BaseEconomy.surroundRatio(site, ring, 8, farOnly, near);
            helper.assertTrue(raw < 0.9D, "守りの輪を含めた割合が高すぎる: " + raw);
            helper.assertTrue(skip == 1.0D, "守りを分母から外していない: " + skip);
        } finally {
            BaseSites.get(level.getServer()).remove(site);
        }
        helper.succeed();
    }

    /** プレイヤーがいる日は減らず、続いた日の数も数えない。上限で止まる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void presentDaysDoNotDecline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = addSite(level, FAR, 2);
        try {
            BaseEconomy.setStock(data, site, 100);
            long founded = site.foundedDay();
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 1, true, 1.0D);
            int oneDay = BaseEconomy.recovery(2, site.lastDay() - founded, 0.0D);
            helper.assertTrue(site.stock() == 100 + oneDay, "1 日の回復が合わない: " + site.stock() + " / " + (100 + oneDay));
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 4, true, 1.0D);
            helper.assertTrue(site.streak() == 0, "プレイヤーがいたのに続いた日が数えられた: " + site.streak());
            helper.assertTrue(site.lastDecline() == 0, "プレイヤーがいたのに減った: " + site.lastDecline());
            helper.assertTrue(!site.fallen(), "プレイヤーがいたのに陥落した");
            int cap = DesignData.defense().baseStockCap(2);
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 30, true, 0.0D);
            helper.assertTrue(site.stock() == cap, "上限で止まらない: " + site.stock());
        } finally {
            data.remove(site);
        }
        helper.succeed();
    }

    /**
     * 留守中に周りが全部汚染されていると、「減る > 回復」が設計値の日数続いて陥落する。陥落すると、その基地の兵士が消え、
     * 機関砲台と胞子除去装置が空になり、工事も守備兵の補充も始まらない(ユーザー指定 2026-10-04「守備兵が消えて設備が止まる廃墟」)。
     * 兵士はテスト場の中に置く(遠いチャンクのエンティティは見えない)。設備は遠い基地の中に置く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void awayStreakFallsAndEmpties(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = addSite(level, FAR, 1);
        BlockPos turretPos = FAR.offset(2, 0, 2);
        BlockPos purifierPos = FAR.offset(-2, 0, -2);
        SoldierEntity soldier = AllyGameTests.spawnSoldier(helper, SoldierKind.RIFLEMAN, new BlockPos(3, 2, 3));
        try {
            soldier.guard(site.id, helper.absolutePos(new BlockPos(3, 2, 3)), 8);
            level.getChunk(turretPos.getX() >> 4, turretPos.getZ() >> 4);
            level.getChunk(purifierPos.getX() >> 4, purifierPos.getZ() >> 4);
            level.setBlock(turretPos, ModBlocks.GUN_TURRET.get().defaultBlockState(), 3);
            level.setBlock(purifierPos, ModBlocks.SPORE_PURIFIER.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(turretPos) instanceof GunTurretBlockEntity turret)
                    || !(level.getBlockEntity(purifierPos) instanceof SporeDeviceBlockEntity purifier)) {
                helper.fail("遠い基地に機関砲台・胞子除去装置を置けない");
                return;
            }
            turret.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
            purifier.setItem(SporeDeviceBlockEntity.SLOT_BATTERY, com.yayuy.overgrownvegetation.item.BatteryItem.withCharge(com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY));
            purifier.setItem(SporeDeviceBlockEntity.SLOT_FILTER, new ItemStack(ModItems.SPORE_FILTER.get()));
            helper.assertTrue(site.contains(turretPos.getX(), turretPos.getZ(), 1), "機関砲台が基地の外になった");

            int days = DesignData.defense().baseFallStreakDays();
            long day0 = site.lastDay();
            BaseEconomy.settleDays(server, data, site, day0 + days - 1, false, 1.0D);
            helper.assertTrue(!site.fallen(), (days - 1) + " 日で陥落した");
            helper.assertTrue(site.streak() == days - 1, "続いた日の数が違う: " + site.streak());
            helper.assertTrue(site.lastDecline() > site.lastRecovery(), "全部汚染なのに減る量が回復を超えない");
            BaseEconomy.settleDays(server, data, site, day0 + days, false, 1.0D);
            helper.assertTrue(site.fallen(), days + " 日続いても陥落しない");
            BaseEconomy.fallNow(server, data, site, BaseEconomy.Reason.COMMAND); // 後片付けをこの tick で終える
            helper.assertTrue(soldier.isRemoved(), "陥落した基地の兵士が消えない");
            helper.assertTrue(turret.ammoCount() == 0, "機関砲台の弾が残っている: " + turret.ammoCount());
            helper.assertTrue(purifier.getItem(SporeDeviceBlockEntity.SLOT_BATTERY).isEmpty()
                    && purifier.getItem(SporeDeviceBlockEntity.SLOT_FILTER).isEmpty(), "胞子除去装置の電池・フィルターが残っている");
            BaseConstruction.start(data, site, 1, 2);
            helper.assertTrue(!site.building(), "陥落した基地の工事が始まった");
            helper.assertTrue(BaseGarrison.reinforce(level, site) == 0, "陥落した基地に守備兵が足された");
            int before = site.stock();
            BaseEconomy.settleDays(server, data, site, day0 + days + 3, true, 0.0D);
            BaseEconomy.settleTo(server, data, site, BaseEconomy.today(server) + 10);
            helper.assertTrue(site.stock() == before, "陥落した基地の貯蓄が回復した");
            BaseSite copy = BaseSite.reloaded(site);
            helper.assertTrue(copy.fallen() && copy.stock() == site.stock() && copy.foundedDay() == site.foundedDay()
                    && copy.lastDay() == site.lastDay() && copy.lastRatio() == site.lastRatio(), "貯蓄と陥落の欄が保存を通らない");
        } finally {
            level.setBlock(turretPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(purifierPos, Blocks.AIR.defaultBlockState(), 3);
            if (!soldier.isRemoved()) {
                soldier.discard();
            }
            data.remove(site);
        }
        helper.succeed();
    }

    /**
     * 読み込まれていて貯蓄が 0 の基地は、守備兵が全滅したら陥落する(ユーザー指定 10-01「貯蓄の枯渇 + 守備兵の全滅」)。
     * 全滅は名簿で数える(順 2 の着手順 3 から。倒れた兵士が名簿から消える)。基地は遠く、兵士はテスト場の中(AI を切る)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void garrisonLossFallsOnlyWhenEmpty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = addSite(level, FAR, 1);
        SoldierEntity soldier = AllyGameTests.spawnSoldier(helper, SoldierKind.RIFLEMAN, new BlockPos(3, 2, 3));
        try {
            // 全滅は名簿で数える(順 2 の着手順 3。読み込まれていない兵士も数える)。兵士はテスト場の中、基地は遠く
            soldier.setNoAi(true);
            soldier.guard(site.id, helper.absolutePos(new BlockPos(3, 2, 3)), 8);
            BaseGarrison.setRoster(data, site, java.util.List.of(soldier));
            BaseEconomy.setStock(data, site, 0);
            helper.assertTrue(!BaseEconomy.checkGarrison(server, data, site), "守備兵がいるのに陥落した");
            soldier.kill();
            helper.assertTrue(site.rosterSize() == 0, "倒れた守備兵が名簿から消えない");
            // 貯蓄があるうちは、毎 tick の見回りは数えない(貯蓄 0 のときだけ)
            helper.assertTrue(BaseEconomy.checkGarrison(server, data, site), "貯蓄 0 で守備兵が全滅しても陥落しない");
            helper.assertTrue(site.fallen(), "陥落の印が付かない");
        } finally {
            if (!soldier.isRemoved()) {
                soldier.discard();
            }
            data.remove(site);
        }
        helper.succeed();
    }

    /** 同梱の defense.json の alliedBase が Java の既定値と同じ(設計値は 3 か所 —— どちらかだけ直すと食い違う)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bundledDefenseJsonMatchesDefault(GameTestHelper helper) {
        DefenseTable bundled;
        try (java.io.InputStream in = BaseEconomyGameTests.class.getResourceAsStream("/overgrown_vegetation/design/defense.json")) {
            bundled = DefenseTable.fromJson(new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), DefenseTable.Json.class));
        } catch (Exception e) {
            throw new AssertionError("design/defense.json が読めない: " + e);
        }
        DefenseTable def = DefenseTable.DEFAULT;
        for (int lv = 1; lv <= 5; lv++) {
            helper.assertTrue(bundled.baseStockCap(lv) == def.baseStockCap(lv), "レベル " + lv + " の上限が違う");
            helper.assertTrue(bundled.baseSupplyStart(lv) == def.baseSupplyStart(lv), "レベル " + lv + " の補給値が違う");
        }
        helper.assertTrue(bundled.baseSupplyGrowthPerDay() == def.baseSupplyGrowthPerDay()
                && bundled.baseConnectionBonus() == def.baseConnectionBonus()
                && bundled.baseSupplyBaseBonus() == def.baseSupplyBaseBonus()
                && bundled.baseDeclineMin() == def.baseDeclineMin() && bundled.baseDeclineMax() == def.baseDeclineMax()
                && bundled.baseFallStreakDays() == def.baseFallStreakDays()
                && bundled.baseSurroundShape() == def.baseSurroundShape() && bundled.baseSurroundChunks() == def.baseSurroundChunks()
                && bundled.baseSurroundSkipGuarded() == def.baseSurroundSkipGuarded()
                && bundled.basePresenceMargin() == def.basePresenceMargin()
                && bundled.baseStartStockFraction() == def.baseStartStockFraction(), "defense.json の alliedBase が既定値と違う");
        // 世界生成の基地(順 2 の着手順 2、2026-10-04)
        for (BaseType type : BaseType.values()) {
            helper.assertTrue(bundled.baseWorldgenTypeWeight(type.id) == def.baseWorldgenTypeWeight(type.id),
                    "defense.json の worldgenTypeWeights の " + type.id + " が既定値と違う");
        }
        for (int lv = 1; lv <= 5; lv++) {
            helper.assertTrue(bundled.baseWorldgenLevelWeight(lv) == def.baseWorldgenLevelWeight(lv), "レベル " + lv + " の世界生成の重みが違う");
        }
        helper.assertTrue(bundled.baseWorldgenCoreMaxRelief() == def.baseWorldgenCoreMaxRelief()
                && bundled.baseWorldgenAvoidVillageChunks() == def.baseWorldgenAvoidVillageChunks()
                && bundled.baseWorldgenAvoidMansionChunks() == def.baseWorldgenAvoidMansionChunks(), "defense.json の世界生成の値が既定値と違う");
        // 減った守備兵の補充(順 2 の着手順 3、2026-10-04)
        for (SoldierKind kind : SoldierKind.values()) {
            helper.assertTrue(bundled.baseReplenishCost(kind.id) == def.baseReplenishCost(kind.id) && def.baseReplenishCost(kind.id) > 0,
                    "defense.json の replenishCosts の " + kind.id + " が既定値と違う(か、既定値が無い)");
        }
        helper.assertTrue(bundled.baseReplenishHospitalDiscount() == def.baseReplenishHospitalDiscount()
                && bundled.baseReplenishIntervalTicks() == def.baseReplenishIntervalTicks(), "defense.json の補充の割引・間隔が既定値と違う");
        // 整備(順 2 の着手順 4、2026-10-04)
        for (String item : new String[]{"rounds", "redstone", "filter", "repair"}) {
            helper.assertTrue(bundled.baseUpkeepCost(item) == def.baseUpkeepCost(item) && def.baseUpkeepCost(item) > 0,
                    "defense.json の upkeepCosts の " + item + " が既定値と違う(か、既定値が無い)");
        }
        for (String key : new String[]{"roundsPerTurret", "redstonePerPurifier", "filtersPerPurifier", "repairPerLevel"}) {
            helper.assertTrue(bundled.baseUpkeepStock(key) == def.baseUpkeepStock(key) && def.baseUpkeepStock(key) > 0,
                    "defense.json の upkeepStock の " + key + " が既定値と違う(か、既定値が無い)");
        }
        helper.assertTrue(def.baseUpkeepStock("filtersPerPurifier") == 2, "フィルターは除去装置 × 2(ユーザー指定)");
        helper.assertTrue(bundled.baseUpkeepDeviceRepair("turret") == def.baseUpkeepDeviceRepair("turret")
                && bundled.baseUpkeepDeviceRepair("purifier") == def.baseUpkeepDeviceRepair("purifier")
                && bundled.baseUpkeepRestockIntervalTicks() == def.baseUpkeepRestockIntervalTicks()
                && bundled.baseUpkeepTurretRefillBelow() == def.baseUpkeepTurretRefillBelow()
                && bundled.baseUpkeepTurretFill() == def.baseUpkeepTurretFill()
                && bundled.baseUpkeepFuelRefillBelow() == def.baseUpkeepFuelRefillBelow()
                && bundled.baseUpkeepFuelFill() == def.baseUpkeepFuelFill()
                && bundled.baseUpkeepFilterSwapWear() == def.baseUpkeepFilterSwapWear()
                && bundled.baseUpkeepSporesEmptyAt() == def.baseUpkeepSporesEmptyAt()
                && bundled.baseUpkeepFixTicks() == def.baseUpkeepFixTicks()
                && bundled.baseUpkeepEngageRange() == def.baseUpkeepEngageRange(), "defense.json の整備の値が既定値と違う");
        helper.succeed();
    }

    private BaseEconomyGameTests() {
    }
}
