package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.base.plan.Surface;
import com.yayuy.overgrownvegetation.base.road.AlliedRoad;
import com.yayuy.overgrownvegetation.base.road.BaseRoads;
import com.yayuy.overgrownvegetation.base.road.RoadLedger;
import com.yayuy.overgrownvegetation.base.road.RoadPlanner;
import com.yayuy.overgrownvegetation.base.road.RoadTerrain;
import com.yayuy.overgrownvegetation.base.road.RoadWorks;
import com.yayuy.overgrownvegetation.data.BaseRoadConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 道とつながり(順 2 の着手順 7、2026-10-08、{@link BaseRoads})。
 *
 * <p>基地は遠い決まった座標に台帳だけで載せ(チャンクは作らない —— 道の台帳はブロックを読まない)、同じ tick のうちに外す。
 * 道のマスも台帳へ直接書き、終わったら消す。汚染の台帳と除去装置の守りは {@link BaseEconomy} の差し替えで与える(本物は触らない)。
 * プレイヤーの置く・壊すはテスト場の中のブロックと、本物のイベントで確かめる。期待値は設計値({@link DesignData#defense()})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseRoadGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseRoadGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);

    /** 台帳だけの基地を並べる遠い場所(ほかの基地の試験の座標と重ねない)。 */
    private static final int X0 = -30000;
    private static final int Z0 = -30000;
    private static final int Y = 64;

    private static BaseSite addSite(MinecraftServer server, int x, int z, int rotation, BaseType type) {
        BaseSites data = BaseSites.get(server);
        BaseSite site = data.add(Level.OVERWORLD, new BlockPos(x, Y, z), rotation, 4242L, 1, FLAT, true, type);
        BaseEconomy.start(data, site, BaseEconomy.today(server));
        return site;
    }

    /** x を先に、次に z を進める L 字の道(幅 1)。 */
    private static void lay(RoadLedger ledger, LongArrayList laid, int x0, int z0, int x1, int z1) {
        int x = x0;
        int z = z0;
        put(ledger, laid, x, z);
        while (x != x1) {
            x += Integer.signum(x1 - x);
            put(ledger, laid, x, z);
        }
        while (z != z1) {
            z += Integer.signum(z1 - z);
            put(ledger, laid, x, z);
        }
    }

    private static void put(RoadLedger ledger, LongArrayList laid, int x, int z) {
        long p = BlockPos.asLong(x, Y, z);
        if (ledger.add(p, false)) {
            laid.add(p);
        }
    }

    private static void cleanup(MinecraftServer server, LongArrayList laid, BaseSite... sites) {
        RoadLedger ledger = RoadLedger.get(server);
        for (int i = 0; i < laid.size(); i++) {
            ledger.remove(laid.getLong(i));
        }
        for (BaseSite s : sites) {
            if (s != null) {
                BaseSites.get(server).remove(s);
            }
        }
        BaseEconomy.ledgerForTests = null;
        BaseEconomy.guardedForTests = null;
        BaseRoads.recomputeNow(server);
    }

    private static String ids(List<BaseSite> sites) {
        StringBuilder sb = new StringBuilder("[");
        for (BaseSite s : sites) {
            sb.append(" #").append(s.id);
        }
        return sb.append(" ]").toString();
    }

    /** 基地の口 = 正門から外へ伸びる幅 7 の道。計画が塗った道の先の端と同じ所で止まり、向きに合わせて回る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void portFollowsGateRoad(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BaseSite south = addSite(server, X0, Z0, 0, BaseType.PURIFICATION);
        BaseSite west = addSite(server, X0 + 600, Z0, 1, BaseType.PURIFICATION);
        try {
            for (BaseSite site : List.of(south, west)) {
                BasePlan plan = site.plan(site.level());
                int end = BasePlanner.gateRoadEnd(plan.lobes);
                helper.assertTrue(plan.surfaceAt(0, end) == Surface.ROAD, "#" + site.id + " 口の先の端が道でない: z " + end);
                helper.assertTrue(end + 1 >= BasePlan.GRID || plan.surfaceAt(0, end + 1) != Surface.ROAD,
                        "#" + site.id + " 計画の道が口の先の端より先まで伸びている: z " + end);
                long[] cols = BaseRoads.portColumns(site);
                int width = 2 * BasePlanner.GATE_ROAD_HALF + 1;
                helper.assertTrue(cols.length == width * (end - BasePlanner.GATE_ROAD_START + 1), "#" + site.id + " 口の列の数が違う: " + cols.length);
                LongOpenHashSet set = new LongOpenHashSet(cols);
                BlockPos far = site.toWorld(BasePlanner.GATE_ROAD_HALF, 0, end);
                BlockPos near = site.toWorld(-BasePlanner.GATE_ROAD_HALF, 0, BasePlanner.GATE_ROAD_START);
                helper.assertTrue(set.contains(ChunkPos.asLong(far.getX(), far.getZ())) && set.contains(ChunkPos.asLong(near.getX(), near.getZ())),
                        "#" + site.id + " 口の角が入っていない");
                BlockPos endPos = BaseRoads.portEnd(site, Y);
                helper.assertTrue(set.contains(ChunkPos.asLong(endPos.getX(), endPos.getZ())), "#" + site.id + " 口の先の端の真ん中が口に無い");
            }
            // 向き 0 は南(+z)へ、向き 1 は西(−x)へ口が伸びる
            BlockPos s = BaseRoads.portEnd(south, Y);
            helper.assertTrue(s.getX() == X0 && s.getZ() > Z0, "向き 0 の口が南に無い: " + s);
            BlockPos w = BaseRoads.portEnd(west, Y);
            helper.assertTrue(w.getZ() == Z0 && w.getX() < X0 + 600, "向き 1 の口が西に無い: " + w);
        } finally {
            cleanup(server, new LongArrayList(), south, west);
        }
        helper.succeed();
    }

    /**
     * 直接つながった基地だけ数える(ユーザー指定 10-08): A—B—C(B は補給基地)なら A は B だけ・B は A と C・C は B だけ。
     * 補給基地とつながった側は +60%、補給基地の側は 1 本 +15%。ほかの基地の口の外で道がつながれば(分かれ道)、それは直接のつながり。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void directLinksAndSupplyBonus(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DefenseTable t = DesignData.defense();
        RoadLedger ledger = RoadLedger.get(server);
        LongArrayList laid = new LongArrayList();
        BaseSite a = addSite(server, X0, Z0 + 1000, 0, BaseType.PURIFICATION);
        BaseSite b = addSite(server, X0 + 400, Z0 + 1000, 0, BaseType.SUPPLY);
        BaseSite c = addSite(server, X0 + 800, Z0 + 1000, 0, BaseType.DEFENSE);
        try {
            BaseEconomy.ledgerForTests = k -> false;
            BaseEconomy.guardedForTests = k -> false;
            BlockPos ea = BaseRoads.portEnd(a, Y);
            BlockPos eb = BaseRoads.portEnd(b, Y);
            BlockPos ec = BaseRoads.portEnd(c, Y);
            // A → B: 口の先から南へ下りて東へ、B の口の先へ上る(x = B の口の真ん中)
            lay(ledger, laid, ea.getX(), ea.getZ() + 1, ea.getX(), ea.getZ() + 28);
            lay(ledger, laid, ea.getX(), ea.getZ() + 28, eb.getX(), ea.getZ() + 28);
            lay(ledger, laid, eb.getX(), ea.getZ() + 28, eb.getX(), eb.getZ() + 1);
            // B → C: B の口の東の端から(A の道とは 3 マス離して並べる)
            int bx = eb.getX() + BasePlanner.GATE_ROAD_HALF;
            lay(ledger, laid, bx, eb.getZ() + 1, bx, eb.getZ() + 18);
            lay(ledger, laid, bx, eb.getZ() + 18, ec.getX(), eb.getZ() + 18);
            lay(ledger, laid, ec.getX(), eb.getZ() + 18, ec.getX(), ec.getZ() + 1);
            BaseRoads.recomputeNow(server);

            helper.assertTrue(BaseRoads.linked(server, a).equals(List.of(b)), "A のつながりが B だけでない: " + ids(BaseRoads.linked(server, a)));
            helper.assertTrue(BaseRoads.linked(server, b).equals(List.of(a, c)), "B のつながりが A と C でない: " + ids(BaseRoads.linked(server, b)));
            helper.assertTrue(BaseRoads.linked(server, c).equals(List.of(b)), "C のつながりが B だけでない(B を通り抜けた?): " + ids(BaseRoads.linked(server, c)));
            helper.assertTrue(Math.abs(BaseRoads.bonus(server, a) - t.baseSupplyBaseBonus()) < 1e-9, "補給基地とつながった A の割り増しが違う: " + BaseRoads.bonus(server, a));
            helper.assertTrue(Math.abs(BaseRoads.bonus(server, b) - 2 * t.baseConnectionBonus()) < 1e-9, "補給基地 B の割り増しが違う: " + BaseRoads.bonus(server, b));
            int rec = BaseEconomy.todaysRecovery(server, a);
            int want = BaseEconomy.recovery(a.level(), BaseEconomy.today(server) - a.foundedDay(), t.baseSupplyBaseBonus());
            helper.assertTrue(rec == want, "今日の回復量に割り増しが入っていない: " + rec + " / " + want);

            // 分かれ道: B の口の外で A の道と C の道をつなぐと、A と C も直接つながる
            int fork = ea.getZ() + 18;
            lay(ledger, laid, eb.getX() + 1, fork, bx - 1, fork);
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).equals(List.of(b, c)), "分かれ道で A と C がつながらない: " + ids(BaseRoads.linked(server, a)));
        } finally {
            cleanup(server, laid, a, b, c);
        }
        helper.succeed();
    }

    /** 切れる: 道のチャンクが過繁茂(除去装置に守られていない)/ 道のアスファルトが苔に覆われた。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void overgrowthAndMossCutRoads(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        RoadLedger ledger = RoadLedger.get(server);
        LongArrayList laid = new LongArrayList();
        BaseSite a = addSite(server, X0, Z0 + 2000, 0, BaseType.PURIFICATION);
        BaseSite b = addSite(server, X0 + 400, Z0 + 2000, 0, BaseType.ARSENAL);
        try {
            BlockPos ea = BaseRoads.portEnd(a, Y);
            BlockPos eb = BaseRoads.portEnd(b, Y);
            lay(ledger, laid, ea.getX(), ea.getZ() + 1, ea.getX(), ea.getZ() + 28);
            lay(ledger, laid, ea.getX(), ea.getZ() + 28, eb.getX(), ea.getZ() + 28);
            lay(ledger, laid, eb.getX(), ea.getZ() + 28, eb.getX(), eb.getZ() + 1);
            int midX = (ea.getX() + eb.getX()) / 2;
            long midCell = BlockPos.asLong(midX, Y, ea.getZ() + 28);
            long midChunk = ChunkPos.asLong(midX >> 4, (ea.getZ() + 28) >> 4);

            BaseEconomy.ledgerForTests = k -> false;
            BaseEconomy.guardedForTests = k -> false;
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).equals(List.of(b)), "汚染の無い道でつながらない");

            BaseEconomy.ledgerForTests = k -> k == midChunk;
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).isEmpty(), "過繁茂のチャンクを通る道が切れない");
            helper.assertTrue(BaseRoads.bonus(server, a) == 0.0D, "切れた道の割り増しが残る");

            BaseEconomy.guardedForTests = k -> k == midChunk;
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).equals(List.of(b)), "除去装置が守るチャンクで道が切れた");

            BaseEconomy.ledgerForTests = k -> false;
            ledger.setCovered(midCell, true);
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).isEmpty(), "苔に覆われたマスで道が切れない");
            ledger.setCovered(midCell, false);
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).equals(List.of(b)), "苔を剥がしてもつながらない");
        } finally {
            cleanup(server, laid, a, b);
        }
        helper.succeed();
    }

    /**
     * プレイヤーの道: 置いた道のブロックは台帳へ・ほかのブロックは入れない / 壊すと消える / 苔に覆われる・剥がされると印が変わる /
     * 汚染で置き換わると消える(確かめは tick の終わり)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void playerRoadBlocksAreTracked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        RoadLedger ledger = RoadLedger.get(server);
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        BlockPos p1 = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos p2 = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos p3 = helper.absolutePos(new BlockPos(4, 2, 2));
        BlockPos stone = helper.absolutePos(new BlockPos(2, 2, 4));
        BlockState asphalt = BaseBlocks.ASPHALT.get().defaultBlockState();
        BlockState slab = BaseBlocks.ASPHALT_SLAB.get().defaultBlockState();
        level.setBlock(p1, asphalt, Block.UPDATE_ALL);
        level.setBlock(p2, slab, Block.UPDATE_ALL);
        level.setBlock(p3, asphalt, Block.UPDATE_ALL);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Runnable undo = () -> {
            for (BlockPos p : List.of(p1, p2, p3, stone)) {
                OvergrownLayerData.uncover(level, p);
                ledger.remove(p.asLong());
            }
        };
        BlockState floor = level.getBlockState(p1.below());
        for (BlockPos p : List.of(p1, p2, p3, stone)) {
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, p), floor, player));
        }
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(ledger.has(p1.asLong()) && ledger.has(p2.asLong()) && ledger.has(p3.asLong()), "置いた道のブロックが台帳に入らない");
                helper.assertFalse(ledger.has(stone.asLong()), "道でないブロックが台帳に入った");

                level.setBlock(p2, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, p2, slab, player));
                OvergrownLayerData.cover(level, p3);
                helper.assertTrue(ledger.isCovered(p3.asLong()), "苔に覆われた印が付かない");
                OvergrownLayerData.uncover(level, p3);
                helper.assertFalse(ledger.isCovered(p3.asLong()), "苔を剥がした印が消えない");
                CorruptionRegistry.placeCorrupted(level, p1, asphalt, ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            } catch (RuntimeException e) {
                undo.run();
                throw e;
            }
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertFalse(ledger.has(p2.asLong()), "壊した道のブロックが台帳に残る");
                    helper.assertFalse(ledger.has(p1.asLong()), "汚染で置き換わった道のブロックが台帳に残る");
                    helper.assertTrue(ledger.has(p3.asLong()), "触っていない道のブロックが台帳から消えた");
                } finally {
                    undo.run();
                }
                helper.succeed();
            });
        });
    }

    // ---------- 同盟軍の道(②: 道筋・工事・敷く) ----------

    private static RoadTerrain flat() {
        return new RoadTerrain() {
            @Override
            public int ground(int x, int z) {
                return 64;
            }

            @Override
            public int surface(int x, int z) {
                return 64;
            }
        };
    }

    private static RoadPlanner.Request request(int sx, int sz, int gx, int gz, List<int[]> obstacles, RoadTerrain terrain) {
        return new RoadPlanner.Request(sx, sz, 0, 1, gx, gz, 0, 1, obstacles, k -> false, terrain, BaseRoadConfig.DEFAULT);
    }

    /** 道筋: 崖の台地を回り込み、基地の箱を通らず、坂は 4 ブロックで 1 段(半段ずつ)まで、点は 1 ブロックおき。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void plannerAvoidsCliffsAndKeepsSlope(GameTestHelper helper) {
        RoadTerrain plateau = new RoadTerrain() {
            @Override
            public int ground(int x, int z) {
                return x >= 150 && x <= 250 && z >= -300 && z <= 80 ? 90 : 64;
            }

            @Override
            public int surface(int x, int z) {
                return ground(x, z);
            }
        };
        int[] base = {-60, -200, 60, -10};
        RoadPlanner.Plan p = RoadPlanner.plan(request(0, 10, 400, 10, List.of(base), plateau));
        helper.assertTrue(p != null, "台地を回り込む道筋が見つからない");
        int n = p.length();
        helper.assertTrue(Math.hypot(p.xs8()[0] / 8.0D - 0.5D, p.zs8()[0] / 8.0D - 10.5D) < 2.0D, "道の頭が出発点に無い");
        helper.assertTrue(Math.hypot(p.xs8()[n - 1] / 8.0D - 400.5D, p.zs8()[n - 1] / 8.0D - 10.5D) < 2.0D, "道の尻が行き先に無い");
        for (int i = 0; i < n; i++) {
            int x = Math.floorDiv(p.xs8()[i], 8);
            int z = Math.floorDiv(p.zs8()[i], 8);
            // 丸めた角が台地の縁を 1〜2 ブロックかすめるのは切り通しで済む。深く入らず、道の高さも上がらない
            helper.assertFalse(x >= 152 && x <= 248 && z >= -298 && z <= 78, "崖の台地に上った: " + x + ", " + z);
            helper.assertTrue(p.h2()[i] == 2 * 65, "平らな所で道の高さが変わった(台地の角で盛り上がった?): " + i + " で " + p.h2()[i]);
            helper.assertFalse(x > base[0] + 2 && x < base[2] - 2 && z > base[1] + 2 && z < base[3] - 2, "基地の箱を通った: " + x + ", " + z);
            if (i + 1 < n) {
                helper.assertTrue(Math.abs(p.h2()[i + 1] - p.h2()[i]) <= 1, "坂が急すぎる: " + i + " で " + p.h2()[i] + " → " + p.h2()[i + 1]);
                double step = Math.hypot(p.xs8()[i + 1] - p.xs8()[i], p.zs8()[i + 1] - p.zs8()[i]) / 8.0D;
                helper.assertTrue(step < 1.5D, "点が飛んでいる: " + i + " で " + step);
            }
        }
        helper.succeed();
    }

    /** 川(幅 12)は橋で渡る(橋の上は水面より下げない)。広い湖(幅 60、回り込めない)は渡らない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void plannerBridgesRiversButNotLakes(GameTestHelper helper) {
        RoadPlanner.Plan river = RoadPlanner.plan(request(0, 10, 400, 10, List.of(), band(190, 201)));
        helper.assertTrue(river != null, "幅 12 の川を渡る道筋が見つからない");
        int bridges = 0;
        for (int i = 0; i < river.length(); i++) {
            if ((river.flags()[i] & RoadPlanner.BRIDGE) != 0) {
                bridges++;
                helper.assertTrue(river.h2()[i] >= 2 * (63 + 1), "橋が水面より低い: " + river.h2()[i]);
            }
        }
        helper.assertTrue(bridges >= 10, "川の上が橋になっていない: " + bridges);
        helper.assertTrue(RoadPlanner.plan(request(0, 10, 400, 10, List.of(), band(180, 239))) == null, "幅 60 の湖を渡った");
        helper.succeed();
    }

    /**
     * 正門の先が池でも、橋で渡れる所に陸があれば道を引く(最後の区間が橋)。両端の路面の高さ(建てた基地の正門の道の高さ)を渡せば、
     * そこから坂の上限を守って始まり・終わる(ノイズの地形と数ブロック違っても段差を作らない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void plannerHandlesWaterGatesAndPinnedEnds(GameTestHelper helper) {
        RoadTerrain pond = new RoadTerrain() {
            @Override
            public int ground(int x, int z) {
                return x >= 380 && x <= 420 && z >= 11 && z <= 30 ? 58 : 64;
            }

            @Override
            public int surface(int x, int z) {
                return x >= 380 && x <= 420 && z >= 11 && z <= 30 ? 63 : 64;
            }
        };
        // 行き先の基地は正門(z = 10)の後ろ。池の向こう岸(z ≥ 31)の陸から橋で正門の道の先へ渡るしかない
        int[] goalBase = {370, -100, 430, 10};
        RoadPlanner.Plan p = RoadPlanner.plan(request(0, 10, 400, 10, List.of(goalBase), pond));
        helper.assertTrue(p != null, "正門の先が池の基地へ道を引けない");
        int bridges = 0;
        for (int i = Math.max(0, p.length() - 30); i < p.length(); i++) {
            if ((p.flags()[i] & RoadPlanner.BRIDGE) != 0) {
                bridges++;
            }
        }
        helper.assertTrue(bridges >= 10, "池の上が橋になっていない: " + bridges);

        RoadPlanner.Plan q = RoadPlanner.plan(new RoadPlanner.Request(0, 10, 0, 1, 400, 10, 0, 1, List.of(), k -> false, flat(),
                BaseRoadConfig.DEFAULT, 70, 60));
        helper.assertTrue(q != null, "平らな地面で道筋が見つからない");
        int n = q.length();
        helper.assertTrue(q.h2()[0] == 2 * 70 && q.h2()[n - 1] == 2 * 60, "両端が渡した高さでない: " + q.h2()[0] + " / " + q.h2()[n - 1]);
        helper.assertTrue(q.h2()[n / 2] == 2 * 65, "真ん中が地形の高さでない: " + q.h2()[n / 2]);
        for (int i = 0; i + 1 < n; i++) {
            helper.assertTrue(Math.abs(q.h2()[i + 1] - q.h2()[i]) <= 1, "両端の坂が急すぎる: " + i);
        }
        helper.succeed();
    }

    /** x が [x0, x1] の帯(z はどこまでも)が水(水面 63・水底 58)の地形。 */
    private static RoadTerrain band(int x0, int x1) {
        return new RoadTerrain() {
            @Override
            public int ground(int x, int z) {
                return x >= x0 && x <= x1 ? 58 : 64;
            }

            @Override
            public int surface(int x, int z) {
                return x >= x0 && x <= x1 ? 63 : 64;
            }
        };
    }

    /** 同盟軍の道: 正門の道の先から先へ引かれ、仕上がるまではつながりに数えず、仕上がったら両方の基地がつながる。マスは途切れず、灯りは左右交互。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void alliedRoadLinksBasesWhenFinished(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        RoadLedger ledger = RoadLedger.get(server);
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        BaseSite a = addSite(server, X0, Z0 + 3000, 0, BaseType.PURIFICATION);
        BaseSite b = addSite(server, X0 + 400, Z0 + 3000, 0, BaseType.DEFENSE);
        AlliedRoad r = null;
        try {
            BaseEconomy.ledgerForTests = k -> false;
            BaseEconomy.guardedForTests = k -> false;
            RoadPlanner.Plan p = RoadPlanner.plan(RoadWorks.request(level, a, b, cfg, flat()));
            helper.assertTrue(p != null, "平らな地面で道筋が見つからない");
            r = ledger.addRoad(a.id, b.id, p, 0L);
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).isEmpty(), "工事の始まっていない道でつながった");
            RoadWorks.finish(server, r);
            BaseRoads.recomputeNow(server);
            helper.assertTrue(BaseRoads.linked(server, a).equals(List.of(b)) && BaseRoads.linked(server, b).equals(List.of(a)),
                    "仕上がった道でつながらない: " + ids(BaseRoads.linked(server, a)));
            helper.assertTrue(RoadWorks.cutAt(level, r, cfg) == null && RoadWorks.describeWorks(server, a) == null,
                    "切れていない仕上がった道が管理端末に出る");
            // 道の真ん中のチャンクが過繁茂になると切れ、管理端末にどこで切れたかが出る
            int mid = r.length() / 2;
            long midChunk = ChunkPos.asLong(r.pointX(mid) >> 4, r.pointZ(mid) >> 4);
            BaseEconomy.ledgerForTests = k -> k == midChunk;
            BaseRoads.recomputeNow(server);
            BlockPos cutAt = RoadWorks.cutAt(level, r, cfg);
            helper.assertTrue(cutAt != null && ChunkPos.asLong(cutAt.getX() >> 4, cutAt.getZ() >> 4) == midChunk, "切れた所が違う: " + cutAt);
            helper.assertTrue(RoadWorks.describeWorks(server, a) != null, "切れた道が管理端末に出ない");
            helper.assertTrue(BaseRoads.linked(server, a).isEmpty(), "過繁茂のチャンクで同盟軍の道が切れない");
            BaseEconomy.ledgerForTests = k -> false;

            AlliedRoad.Cells cells = r.cells(cfg.width, cfg.lampSpacing);
            int asphalt = 0;
            int lamps = 0;
            LongOpenHashSet cols = new LongOpenHashSet();
            for (int c = 0; c < cells.size(); c++) {
                if (cells.kind(c) == AlliedRoad.ASPHALT) {
                    asphalt++;
                    cols.add(ChunkPos.asLong(BlockPos.getX(cells.pos()[c]), BlockPos.getZ(cells.pos()[c])));
                } else if (cells.kind(c) == AlliedRoad.LAMP) {
                    lamps++;
                }
            }
            helper.assertTrue(asphalt >= r.length() * (cfg.width - 1), "アスファルトが少ない(幅が足りない): " + asphalt + " / 点 " + r.length());
            int wantLamps = (r.length() - cfg.lampSpacing / 2) / cfg.lampSpacing;
            helper.assertTrue(Math.abs(lamps - wantLamps) <= 1, "妨害灯の数が違う: " + lamps + " / " + wantLamps);
            // 真ん中の線のどの点の列も、アスファルトのマスに入っている(途切れない)
            for (int i = 0; i < r.length(); i++) {
                helper.assertTrue(cols.contains(ChunkPos.asLong(r.pointX(i), r.pointZ(i))), "道の真ん中の線がアスファルトでない: " + i);
            }
        } finally {
            if (r != null) {
                ledger.removeRoad(r);
            }
            cleanup(server, new LongArrayList(), a, b);
        }
        helper.succeed();
    }

    /** 工事は両方の基地から 1 日 blocksPerDay ずつ、1 ブロックごとに貯蓄 costPerBlock。貯蓄が上限の minStockFraction を下回る基地は進めない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void constructionPaysFromBothEnds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        RoadLedger ledger = RoadLedger.get(server);
        BaseSites data = BaseSites.get(server);
        DefenseTable t = DesignData.defense();
        BaseRoadConfig cfg = t.baseRoad();
        BaseSite a = addSite(server, X0, Z0 + 4000, 0, BaseType.PURIFICATION);
        BaseSite b = addSite(server, X0 + 600, Z0 + 4000, 0, BaseType.DEFENSE);
        AlliedRoad r = null;
        try {
            BaseEconomy.ledgerForTests = k -> false;
            BaseEconomy.guardedForTests = k -> false;
            RoadPlanner.Plan p = RoadPlanner.plan(RoadWorks.request(level, a, b, cfg, flat()));
            helper.assertTrue(p != null && p.length() > 3 * cfg.blocksPerDay, "道筋が見つからない・短すぎる");
            r = ledger.addRoad(a.id, b.id, p, 1_000_000L);
            int cap = t.baseStockCap(a.level());
            int floor = (int) Math.ceil(cap * cfg.minStockFraction);
            int stockA = a.stock();
            int stockB = b.stock();
            RoadWorks.advance(server, r.lastClock() + 24000L);
            helper.assertTrue(Math.abs(r.builtA() - cfg.blocksPerDay) < 1e-6 && Math.abs(r.builtB() - cfg.blocksPerDay) < 1e-6,
                    "1 日で進んだ長さが違う: " + r.builtA() + " / " + r.builtB());
            int paid = (int) Math.floor(cfg.blocksPerDay * cfg.costPerBlock);
            helper.assertTrue(stockA - a.stock() == paid && stockB - b.stock() == paid,
                    "払った貯蓄が違う: " + (stockA - a.stock()) + " / " + (stockB - b.stock()) + "(" + paid + ")");

            // A は下限の 40 上しか無い → 10 ブロックだけ進んで下限で止まる。B はそのまま 1 日分
            BaseEconomy.setStock(data, a, floor + 40);
            double beforeA = r.builtA();
            double beforeB = r.builtB();
            RoadWorks.advance(server, r.lastClock() + 24000L);
            helper.assertTrue(Math.abs(r.builtA() - beforeA - 40.0D / cfg.costPerBlock) < 0.5D, "下限までの分だけ進まない: " + (r.builtA() - beforeA));
            helper.assertTrue(a.stock() == floor, "下限より下まで払った: " + a.stock() + " / " + floor);
            helper.assertTrue(Math.abs(r.builtB() - beforeB - cfg.blocksPerDay) < 1e-6, "B が 1 日分進まない");

            // 下限より下の基地は進めない
            beforeA = r.builtA();
            RoadWorks.advance(server, r.lastClock() + 24000L);
            helper.assertTrue(r.builtA() == beforeA, "貯蓄が下限の基地が工事を進めた");
        } finally {
            if (r != null) {
                ledger.removeRoad(r);
            }
            cleanup(server, new LongArrayList(), a, b);
        }
        helper.succeed();
    }

    /** 敷く: アスファルト(幅 3)・道の上の木は幹ごと切る・中身のあるブロックは触らない・妨害灯は柱 2 段の上。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void materializeLaysRoadAndLamp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        RoadLedger ledger = RoadLedger.get(server);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int floorY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        int n = 30;
        int[] xs8 = new int[n];
        int[] zs8 = new int[n];
        int[] h2 = new int[n];
        for (int i = 0; i < n; i++) {
            xs8[i] = (o.getX() - 9 + i) * 8 + 4;
            zs8[i] = (o.getZ() + 3) * 8 + 4;
            h2[i] = 2 * (floorY + 1);
        }
        AlliedRoad r = ledger.addRoad(-1, -2, new RoadPlanner.Plan(xs8, zs8, h2, new byte[n]), 0L);
        try {
            RoadWorks.finish(server, r);
            for (int y = 2; y <= 6; y++) {
                helper.setBlock(new BlockPos(3, y, 3), Blocks.OAK_LOG);
            }
            helper.setBlock(new BlockPos(5, 2, 3), Blocks.CHEST);
            int placed = RoadWorks.materializeWithin(level, r, net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                    helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(6, 6, 6))));
            helper.assertTrue(placed > 0, "何も置かなかった");
            for (int x = 0; x <= 6; x++) {
                for (int z = 2; z <= 4; z++) {
                    helper.assertBlockPresent(BaseBlocks.ASPHALT.get(), new BlockPos(x, 1, z));
                }
            }
            for (int y = 2; y <= 6; y++) {
                helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(3, y, 3));
            }
            helper.assertBlockPresent(Blocks.CHEST, new BlockPos(5, 2, 3));
            helper.assertBlockPresent(BaseBlocks.REINFORCED_CONCRETE_WALL.get(), new BlockPos(3, 2, 5));
            helper.assertBlockPresent(BaseBlocks.REINFORCED_CONCRETE_WALL.get(), new BlockPos(3, 3, 5));
            helper.assertBlockPresent(BaseBlocks.JAMMING_LAMP.get(), new BlockPos(3, 4, 5));
            // 2 回目は何も置かない(置いてある)
            int again = RoadWorks.materializeWithin(level, r, net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                    helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(6, 6, 6))));
            helper.assertTrue(again == 0, "置いてあるのにまた置いた: " + again);
        } finally {
            ledger.removeRoad(r);
        }
        helper.succeed();
    }

    /** 同梱の defense.json の alliedBase.road が既定値と同じ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bundledRoadJsonMatchesDefault(GameTestHelper helper) {
        DefenseTable bundled;
        try (java.io.InputStream in = BaseRoadGameTests.class.getResourceAsStream("/overgrown_vegetation/design/defense.json")) {
            bundled = DefenseTable.fromJson(new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), DefenseTable.Json.class));
        } catch (Exception e) {
            throw new AssertionError("design/defense.json が読めない: " + e);
        }
        helper.assertTrue(bundled.baseRoad().sameAs(BaseRoadConfig.DEFAULT), "defense.json の alliedBase.road が既定値と違う");
        helper.succeed();
    }
}
