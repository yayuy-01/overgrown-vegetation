package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.drift.DriftCosts;
import com.yayuy.overgrownvegetation.drift.DriftFerry;
import com.yayuy.overgrownvegetation.drift.DriftLaunches;
import com.yayuy.overgrownvegetation.drift.DriftMimicEntity;
import com.yayuy.overgrownvegetation.drift.DriftRaids;
import com.yayuy.overgrownvegetation.drift.SwollenIslands;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDrift;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 漂流する擬態 Mob と腫れた島(2026-11-20)。テスト場 gametest_empty24(24 × 10 × 24、床は相対 y 1)に、相対 y 2〜3 の水の池を張る
 * (水面の段 = 相対 y 3)。陸は y 2〜3 の石 + y 4 の草(水面より 1 段高い)。外周のバリアは岸に数えない。
 * 風は個体ごとに差し替える({@code forcedWind})—— テストの世界の卓越風に左右されない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class DriftMimicGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(DriftMimicGameTests.class);
        }

        private Registration() {
        }
    }

    private DriftMimicGameTests() {
    }

    static final String T = "gametest_empty24";
    static final String BATCH = "drift";
    static final int WY = 3;

    /** 池を張る(全面の水)。 */
    static void pool(GameTestHelper h) {
        for (int x = 0; x < 24; x++)
            for (int z = 0; z < 24; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
                h.setBlock(new BlockPos(x, 3, z), Blocks.WATER);
            }
    }

    /** 陸の箱(x0..x1, z0..z1)。 */
    static void land(GameTestHelper h, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 3, z), Blocks.DIRT);
                h.setBlock(new BlockPos(x, 4, z), Blocks.GRASS_BLOCK);
            }
    }

    static DriftMimicEntity mimic(GameTestHelper h, double x, double z, Vec3 wind) {
        DriftMimicEntity e = h.spawn(ModDrift.DRIFT_MIMIC.get(), new Vec3(x, WY + 0.6D, z));
        e.forcedWind = wind;
        e.stayMaterial = true;
        small(e);
        return e;
    }

    /** テスト場の池は深さ 2 なので、要る深さ・体の半径・鉤の届く距離を池に合わせて縮める(形の 3 倍の値は浅すぎて動けない)。 */
    static void small(DriftMimicEntity e) {
        e.minDepthOverride = 2;
        e.bodyRadiusOverride = 2.3D;
        e.gripReachOverride = 4.5D; // 体の前(半径 + 1 = 3.3)が岸に着いて止まっても届く長さ
    }

    static int countIn(GameTestHelper h, net.minecraft.world.level.block.Block block, int y0, int y1) {
        int n = 0;
        for (int x = 0; x < 24; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = 0; z < 24; z++)
                    if (h.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
        return n;
    }

    /** テスト場の中に中心のある腫れた島の帳面を外す(ほかのテストの島を育てないように)。 */
    static void forgetIslands(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(23, 0, 23));
        for (SwollenIslands.Island i : new ArrayList<>(SwollenIslands.islands(level))) {
            if (i.cx >= Math.min(a.getX(), b.getX()) - 2 && i.cx <= Math.max(a.getX(), b.getX()) + 2
                    && i.cz >= Math.min(a.getZ(), b.getZ()) - 2 && i.cz <= Math.max(a.getZ(), b.getZ()) + 2) {
                SwollenIslands.forgetForTests(level, i);
            }
        }
    }

    /** 植物種(G4 = 拡散の群・植物の系統)に入り、当たりは設計値どおり。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void isPlantSpreader(GameTestHelper h) {
        EntityType<?> t = ModDrift.DRIFT_MIMIC.get();
        h.assertTrue(t.is(ModTags.GROUP_4), "group_4 に入っていない");
        h.assertTrue(t.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
                OvergrownVegetation.id("lineage_plant"))), "植物の系統に入っていない");
        h.assertTrue(Math.abs(t.getWidth() - com.yayuy.overgrownvegetation.data.MobStats.width(DriftMimicEntity.ID, 4.5F)) < 1e-4, "幅が設計値と違う");
        h.succeed();
    }

    /** 岸の無い海では風下へ流れる(横へはずれない)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 200)
    public static void driftsDownwind(GameTestHelper h) {
        pool(h);
        DriftMimicEntity e = mimic(h, 4.5D, 12.5D, new Vec3(1.0D, 0.0D, 0.0D));
        e.seekRadiusOverride = 0;
        Vec3 start = e.position();
        h.runAfterDelay(140, () -> {
            Vec3 p = e.position();
            h.assertTrue(e.isAlive(), "沈んだ・根付いた");
            h.assertTrue(p.x - start.x > 1.5D, "風下へ流れていない: dx = " + (p.x - start.x));
            h.assertTrue(Math.abs(p.z - start.z) < 0.5D, "横へずれた: dz = " + (p.z - start.z));
            h.assertTrue(e.isInWater() && p.y > h.absolutePos(new BlockPos(0, 2, 0)).getY(), "浮いていない: y = " + p.y);
            h.succeed();
        });
    }

    /** 風が岸と平行でも、近くの岸を見つけて漕いで寄り、着いたら根付いて(萌芽体の株を置いて)消える。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 600)
    public static void seeksShoreAndRoots(GameTestHelper h) {
        pool(h);
        land(h, 0, 23, 20, 23);
        DriftMimicEntity e = mimic(h, 4.5D, 11.5D, new Vec3(1.0D, 0.0D, 0.0D));
        h.succeedWhen(() -> {
            h.assertTrue(e.isRemoved(), "まだ根付いていない: " + e.position() + " mode " + e.mode());
            h.assertTrue(countIn(h, ModBlocks.BLIGHT_FERN_ROOTED.get(), 4, 7) >= 1, "岸に萌芽体の株が無い");
            forgetIslands(h);
        });
    }

    /** 岸の手前の浅瀬(深さ 1)には入らず手前で止まり、鉤の届かない岸は断る。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 300)
    public static void stopsBeforeShallows(GameTestHelper h) {
        pool(h);
        shelf(h, 12, 19);
        land(h, 0, 23, 20, 23);
        DriftMimicEntity e = mimic(h, 12.5D, 5.5D, Vec3.ZERO);
        double[] maxZ = {e.getZ()};
        int shelfZ = h.absolutePos(new BlockPos(0, 0, 12)).getZ();
        h.onEachTick(() -> maxZ[0] = Math.max(maxZ[0], e.getZ()));
        h.runAfterDelay(260, () -> {
            h.assertTrue(e.isAlive(), "鉤が届かないのに根付いた");
            h.assertTrue(maxZ[0] + 2.3D < shelfZ + 0.5D, "浅瀬に入った: 体の前 " + (maxZ[0] + 2.3D) + "(浅瀬 " + shelfZ + ")");
            h.assertTrue(e.refusedCount() >= 1, "届かない岸を断っていない");
            h.assertTrue(countIn(h, ModBlocks.BLIGHT_FERN_ROOTED.get(), 4, 7) == 0, "株を置いた");
            e.discard();
            h.succeed();
        });
    }

    /** 浅瀬の手前からでも、鉤の房が届く岸はつかんで根付く(浅瀬には入らない)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 400)
    public static void gripsShoreAcrossShallows(GameTestHelper h) {
        pool(h);
        shelf(h, 16, 19);
        land(h, 0, 23, 20, 23);
        DriftMimicEntity e = mimic(h, 12.5D, 5.5D, Vec3.ZERO);
        e.gripReachOverride = 9.0D;
        double[] maxZ = {e.getZ()};
        int shelfZ = h.absolutePos(new BlockPos(0, 0, 16)).getZ();
        h.onEachTick(() -> { if (!e.isRemoved()) maxZ[0] = Math.max(maxZ[0], e.getZ()); });
        h.succeedWhen(() -> {
            h.assertTrue(e.isRemoved(), "まだつかんでいない: " + h.relativeVec(e.position()));
            h.assertTrue(countIn(h, ModBlocks.BLIGHT_FERN_ROOTED.get(), 4, 7) >= 1, "岸に株が無い");
            h.assertTrue(maxZ[0] + 2.3D < shelfZ + 0.5D, "浅瀬に入った");
            forgetIslands(h);
        });
    }

    /** 浅瀬(z0..z1 の深さ 1。床の上に石を 1 段)。 */
    static void shelf(GameTestHelper h, int z0, int z1) {
        for (int x = 0; x < 24; x++)
            for (int z = z0; z <= z1; z++) h.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
    }

    /** 小さい島に根付くと腫れた島の帳面に載り、育つと肉と水ぶくれの山になる。 */
    @GameTest(template = T, batch = "drift_island", timeoutTicks = 400)
    public static void smallIslandSwells(GameTestHelper h) {
        pool(h);
        land(h, 11, 13, 11, 13);
        DriftMimicEntity e = mimic(h, 5.5D, 12.5D, Vec3.ZERO);
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(new BlockPos(12, WY, 12));
        h.succeedWhen(() -> {
            h.assertTrue(e.isRemoved(), "まだ根付いていない: " + e.position());
            SwollenIslands.Island found = null;
            for (SwollenIslands.Island i : SwollenIslands.islands(level)) {
                if (Math.abs(i.cx - c.getX()) <= 1 && Math.abs(i.cz - c.getZ()) <= 1) found = i;
            }
            h.assertTrue(found != null, "腫れた島の帳面に載っていない");
            h.assertTrue(found.radius < 3.0D, "島の大きさが違う: " + found.radius);
            found.maxHeight = 5; // テスト場の天井の下に収める
            int placed = SwollenIslands.growNow(level, found);
            h.assertTrue(placed > 0, "計画を作れない・置けない: " + placed);
            int flesh = countIn(h, OrganBlocks.ORGAN_FLESH.get(), 2, 9) + countIn(h, OrganBlocks.ORGAN_HYPHA_FLOOR.get(), 2, 9);
            int blister = countIn(h, OrganBlocks.CAVE_BLISTER.get(), 2, 9);
            h.assertTrue(flesh >= 4, "肉が少ない: " + flesh);
            h.assertTrue(blister >= 2, "水ぶくれが少ない: " + blister);
        });
    }

    /** 押し返しの効く岸(地脈の杭の範囲)には根付かず、その岸を断って流れ続ける。 */
    @GameTest(template = T, batch = "drift_ward", timeoutTicks = 300)
    public static void guardedShoreRefused(GameTestHelper h) {
        pool(h);
        land(h, 9, 15, 20, 23);
        BlockPos stake = new BlockPos(12, 5, 22);
        h.setBlock(stake, ModPushBack.LEY_STAKE.get().defaultBlockState());
        ((com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlockEntity) h.getBlockEntity(stake)).setEnergy(100000);
        DriftMimicEntity e = mimic(h, 12.5D, 14.5D, Vec3.ZERO);
        h.runAfterDelay(240, () -> {
            try {
                h.assertTrue(e.isAlive(), "押し返しの岸に根付いた(消えた)");
                h.assertTrue(e.refusedCount() >= 1, "岸を断っていない");
                h.assertTrue(countIn(h, ModBlocks.BLIGHT_FERN_ROOTED.get(), 4, 7) == 0, "押し返しの岸に株を置いた");
                h.succeed();
            } finally {
                h.setBlock(stake, Blocks.AIR); // 杭を外す(ほかのテストを守らない)
                e.discard();
            }
        });
    }

    /** 流すときに値段を払う。払えなければ流さない。風下に陸か開けた海がある岸だけを選ぶ。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 40)
    public static void launchPaysAndPicksShore(GameTestHelper h) {
        pool(h);
        land(h, 0, 23, 20, 23);
        ServerLevel level = h.getLevel();
        BlockPos water = h.absolutePos(new BlockPos(12, WY, 18));
        double[] paid = {0};
        DriftCosts.Payer prev = DriftCosts.payer;
        DriftMimicEntity ok, refused;
        try {
            DriftCosts.payer = (l, amount, reason) -> {
                paid[0] += amount;
                return true;
            };
            ok = DriftLaunches.launch(level, water);
            DriftCosts.payer = (l, amount, reason) -> false;
            refused = DriftLaunches.launch(level, water);
        } finally {
            DriftCosts.payer = prev;
        }
        h.assertTrue(ok != null, "払えたのに流さない");
        h.assertTrue(Math.abs(paid[0] - DriftCosts.launchPrice()) < 1e-9, "払った額が違う: " + paid[0]);
        h.assertTrue(refused == null, "払えないのに流した");
        ok.discard();
        // 岸の選び: 陸から沖へ(-z)吹く風なら選ぶ・陸へ(+z)吹く風なら選ばない(入り江と同じ)
        h.assertTrue(DriftLaunches.siteAt(level, water.getX(), water.getZ(), water.getY(), 0.0D, -1.0D, 2) != null, "沖へ吹く岸を選ばない");
        h.assertTrue(DriftLaunches.siteAt(level, water.getX(), water.getZ(), water.getY(), 0.0D, 1.0D, 2) == null, "陸へ吹く岸を選んだ");
        h.succeed();
    }

    /** 森の予算から払う: 流すと予算が値段ぶん減り、足りなければ流さない(司令塔の作り直し ① の予算)。 */
    @GameTest(template = T, batch = "drift_budget", timeoutTicks = 40)
    public static void launchSpendsForestBudget(GameTestHelper h) {
        pool(h);
        ServerLevel level = h.getLevel();
        BlockPos water = h.absolutePos(new BlockPos(12, WY, 12));
        com.yayuy.overgrownvegetation.director.ForestDirector dir = com.yayuy.overgrownvegetation.director.ForestDirector.get(level);
        boolean enabled = com.yayuy.overgrownvegetation.data.DesignData.director().enabled;
        double before = dir.available(level);
        DriftCosts.Payer prev = DriftCosts.payer;
        try {
            DriftCosts.payer = DriftCosts.FOREST;
            double price = DriftCosts.launchPrice();
            h.assertTrue(price > 0, "値段が 0");
            dir.setBudget(level, price * 3);
            DriftMimicEntity e = DriftLaunches.launch(level, water);
            h.assertTrue(e != null, "予算があるのに流さない");
            double left = dir.available(level);
            if (enabled) {
                h.assertTrue(Math.abs(left - price * 2) < 1e-6, "予算の減りが値段と違う: 残り " + left + "(期待 " + price * 2 + ")");
                h.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEconomy.paid(e) - price) < 1e-9, "払った額を個体に書いていない");
                dir.setBudget(level, price * 0.5);
                h.assertTrue(DriftLaunches.launch(level, water) == null, "予算が足りないのに流した");
            }
            e.discard();
            h.succeed();
        } finally {
            DriftCosts.payer = prev;
            dir.setBudget(level, before);
        }
    }

    /**
     * 島の拠点への襲撃: 拠点のまわりが水なら、攻め手を流れ木もどきに乗せて運ぶ(1 体に 6 席まで)。攻め手の値段に乗せる分を足して
     * 森の予算から払い、着いたら島に降ろす。
     */
    @GameTest(template = T, batch = "drift_budget", timeoutTicks = 600)
    public static void islandRaidFerriesAttackers(GameTestHelper h) {
        pool(h);
        land(h, 12, 12, 12, 12);
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(new BlockPos(12, WY, 12));
        com.yayuy.overgrownvegetation.director.ForestDirector dir = com.yayuy.overgrownvegetation.director.ForestDirector.get(level);
        double before = dir.available(level);
        boolean enabled = com.yayuy.overgrownvegetation.data.DesignData.director().enabled;
        List<net.minecraft.world.entity.Mob> sent = new ArrayList<>();
        DriftMimicEntity[] boat = {null};
        int n;
        double spent;
        try {
            DriftRaids.ringForTests = new int[]{9, 11, c.getY(), 2};
            DriftRaids.onFerryForTests = e -> {
                e.forcedWind = Vec3.ZERO;
                e.stayMaterial = true;
                small(e);
                boat[0] = e;
            };
            dir.setBudget(level, 500.0D);
            n = DriftRaids.ferry(level, c.getX() + 0.5D, c.getZ() + 0.5D, level.getRandom(), 3, 1.0D, (p, r) -> {
                var m = com.yayuy.overgrownvegetation.registry.ModG1.PARASITIZED_ZOMBIE.get().create(level);
                if (m == null) return null;
                m.moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 0.0F, 0.0F);
                m.setInvulnerable(true);
                level.addFreshEntity(m);
                return m;
            }, sent::add);
            spent = 500.0D - dir.available(level);
        } finally {
            DriftRaids.ringForTests = null;
            DriftRaids.onFerryForTests = null;
            dir.setBudget(level, before);
        }
        h.assertTrue(n == 3 && sent.size() == 3, "3 体を運んでいない: " + n);
        h.assertTrue(boat[0] != null && sent.stream().allMatch(m -> m.getVehicle() == boat[0]), "流れ木もどきに乗っていない");
        if (enabled) {
            double want = 3 * 1.0D + DriftCosts.ferryPrice(sent);
            h.assertTrue(Math.abs(spent - want) < 1e-6, "払った額が違う: " + spent + "(期待 " + want + ")");
        }
        int landTop = h.absolutePos(new BlockPos(0, 5, 0)).getY();
        h.succeedWhen(() -> {
            h.assertTrue(boat[0].isRemoved(), "まだ島に着いていない: " + h.relativeVec(boat[0].position()));
            for (var m : sent) {
                h.assertTrue(m.getVehicle() == null && m.getY() >= landTop - 0.5D, "島に降ろしていない: " + h.relativeVec(m.position()));
            }
            forgetIslands(h);
            for (var m : sent) m.discard();
        });
    }

    /** 乗せて運ぶ: 乗せる分も払い、岸へまっすぐ漕いで、着いたら乗せた Mob を陸に降ろす。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 600)
    public static void ferryDropsRidersOnShore(GameTestHelper h) {
        pool(h);
        land(h, 0, 23, 20, 23);
        ServerLevel level = h.getLevel();
        // 乗せるのは寄生種(G1 の寄生ゾンビ)。バニラの敵対 Mob は植物側の Mob を狙うので、乗せると降りて殴りかかる(11-20 確かめた)
        var husk = h.spawn(com.yayuy.overgrownvegetation.registry.ModG1.PARASITIZED_ZOMBIE.get(), new Vec3(12.5D, 5.0D, 21.5D));
        husk.setInvulnerable(true);
        List<net.minecraft.world.entity.Mob> riders = new ArrayList<>(List.of(husk));
        double[] paid = {0};
        DriftCosts.Payer prev = DriftCosts.payer;
        DriftMimicEntity e;
        try {
            DriftCosts.payer = (l, amount, reason) -> {
                paid[0] += amount;
                return true;
            };
            e = DriftFerry.ferry(level, new DriftFerry.Route(h.absolutePos(new BlockPos(6, WY, 6)), h.absolutePos(new BlockPos(16, WY, 20))), riders);
        } finally {
            DriftCosts.payer = prev;
        }
        h.assertTrue(e != null, "運べない");
        e.forcedWind = Vec3.ZERO;
        e.stayMaterial = true;
        small(e);
        h.assertTrue(Math.abs(paid[0] - DriftCosts.ferryPrice(List.of(husk))) < 1e-9, "乗せる分の払いが違う: " + paid[0] + "(期待 " + DriftCosts.ferryPrice(List.of(husk)) + ")");
        h.assertTrue(husk.getVehicle() == e && riders.isEmpty(), "乗っていない");
        int landTop = h.absolutePos(new BlockPos(0, 5, 0)).getY();
        String[] off = {null};
        h.onEachTick(() -> {
            if (off[0] == null && husk.getVehicle() == null) {
                off[0] = "降りた tick " + h.getTick() + " 乗り物 " + (e.isRemoved() ? "消えた" : "生きている") + " " + h.relativeVec(e.position())
                        + " 降りた所 " + h.relativeVec(husk.position()) + " 根付いた所 " + DriftMimicEntity.lastRoot + " 外れた " + husk.isRemoved() + " " + husk.getRemovalReason() + " 体力 " + husk.getHealth()
                        + " 乗り物の客 " + e.getPassengers() + " 水 " + husk.isInWater() + " 目が水 " + husk.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) + " 標的 " + husk.getTarget();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(e.isRemoved(), "まだ着いていない: " + h.relativeVec(e.position()) + " / " + off[0]);
            h.assertTrue(husk.getVehicle() == null, "降ろしていない");
            h.assertTrue(husk.getY() >= landTop - 0.5D, "陸に降ろしていない: y = " + husk.getY() + " / " + off[0]);
            forgetIslands(h);
        });
    }
}
