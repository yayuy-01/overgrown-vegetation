package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.field.MyceliumSeep;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 菌糸の染み下り(2026-11-07)。テスト場から遠いチャンク(と周り)を強制読み込みにし、平らな世界の上に石の塔(チャンクいっぱい、地表 y = 40)を建て、
 * 中に入り口の無い空洞を浅い所・真ん中・深い所に掘ってから、本物の板の組み立てと処理({@link MyceliumSeep#runChunkForTests})で回す。
 * 台帳は触らない(「載った暦」を渡す)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MyceliumSeepGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MyceliumSeepGameTests.class);
        }

        private Registration() {
        }
    }

    private MyceliumSeepGameTests() {
    }

    private static final int RUN_SALT = new java.util.Random().nextInt(1 << 16);
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();
    private static final int TOP_BLOCK = 40;
    /** 空洞(3×3×3、チャンクの真ん中)の床の y。 */
    private static final int SHALLOW = 25, MIDDLE = 0, DEEP = -45;

    private static ChunkPos fresh() {
        int base = 1_200_000 + (RUN_SALT % 2000) * 64 + NEXT.getAndIncrement() * 8;
        return new ChunkPos(base, base);
    }

    private static ChunkPos forced(GameTestHelper helper) {
        ChunkPos a = fresh();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) helper.getLevel().setChunkForced(a.x + dx, a.z + dz, true);
        return a;
    }

    /**
     * 自分と周り 8 チャンクが読み込み済み(getChunkNow が返す)になってから {@code body} を回す(2026-11-08、念のため)。
     * 染み下りは周り 8 チャンクが読み込まれていないと板を作らない(重い回で 20 tick では足りないと、板 0 枚のまま落ちうる)。
     * なお湖の底のテストの揺れ(cave-adapt の回)の本当の原因は別で、天井から下がった水中の根が床の上の水を埋め、床を「水に面していない」と
     * 見ていたこと(本体の {@code MyceliumSeep#isWet} で直した。24 本並べて 2 回とも 0 件)。
     */
    private static void whenLoaded(GameTestHelper helper, ChunkPos a, int waited, Runnable body) {
        ServerLevel level = helper.getLevel();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(a.x + dx, a.z + dz);
        if (waited >= 20 && com.yayuy.overgrownvegetation.util.LoadedChunks.neighbourhoodLoaded(level, a.x, a.z)) {
            body.run();
        } else if (waited >= 300) {
            helper.fail("テストの前提: 強制読み込みしたチャンクが 300 tick たっても読み込み済みにならない");
        } else {
            helper.runAfterDelay(5, () -> whenLoaded(helper, a, waited + 5, body));
        }
    }

    private static void unforce(ServerLevel level, ChunkPos a) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(a.x + dx, a.z + dz, false);
    }

    private static boolean dirty(ServerLevel level, BlockPos p) {
        return OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p));
    }

    /** 石の塔と、入り口の無い空洞 3 つを作る。 */
    private static void build(ServerLevel level, ChunkPos a) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(a.x + dx, a.z + dz);
        int minY = level.getMinBuildHeight() + 1; // 岩盤の上
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = a.getMinBlockX(); x <= a.getMaxBlockX(); x++)
            for (int z = a.getMinBlockZ(); z <= a.getMaxBlockZ(); z++)
                for (int y = minY; y <= TOP_BLOCK; y++) level.setBlock(new BlockPos(x, y, z), stone, Block.UPDATE_CLIENTS);
        for (int floor : new int[]{SHALLOW, MIDDLE, DEEP}) {
            for (int x = 6; x <= 8; x++)
                for (int z = 6; z <= 8; z++)
                    for (int y = floor + 1; y <= floor + 3; y++)
                        level.setBlock(new BlockPos(a.getMinBlockX() + x, y, a.getMinBlockZ() + z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** 空洞の床の真ん中・天井の真ん中・床の 2 段下(空気に面していない)。 */
    private static BlockPos floorOf(ChunkPos a, int floor) {
        return new BlockPos(a.getMinBlockX() + 7, floor, a.getMinBlockZ() + 7);
    }

    private static BlockPos ceilingOf(ChunkPos a, int floor) {
        return new BlockPos(a.getMinBlockX() + 7, floor + 4, a.getMinBlockZ() + 7);
    }

    private static long ticks(double days) {
        return (long) (days * 24000.0D);
    }

    /** 前線の計算(純粋): 経った割合どおりに、地表から底までを同じ日数で下げる。 */
    @GameTest(template = "gametest_empty7")
    public static void seepFrontMovesAtColumnSpeed(GameTestHelper helper) {
        double days = DesignData.sporeField().seepDays();
        helper.assertTrue(MyceliumSeep.targetPpm(0, days) == 0, "経っていないのに進む");
        helper.assertTrue(MyceliumSeep.targetPpm(ticks(days), days) == MyceliumSeep.SCALE, "設計の日数で底に届かない");
        helper.assertTrue(MyceliumSeep.targetPpm(ticks(days * 3), days) == MyceliumSeep.SCALE, "底を越える");
        int half = MyceliumSeep.targetPpm(ticks(days / 2), days);
        // 地表 100 の山の下と地表 0 の平地の下で、半分の日数で半分の深さ
        int mountain = MyceliumSeep.frontY(100, -64, half), plain = MyceliumSeep.frontY(0, -64, half);
        helper.assertTrue(Math.abs(mountain - (100 - 82)) <= 1, "山の下の半分の前線: " + mountain);
        helper.assertTrue(Math.abs(plain - (0 - 32)) <= 1, "平地の下の半分の前線: " + plain);
        helper.assertTrue(MyceliumSeep.frontY(100, -64, MyceliumSeep.SCALE) == -64, "底まで届いた前線: " + MyceliumSeep.frontY(100, -64, MyceliumSeep.SCALE));
        helper.succeed();
    }

    /**
     * 半分の日数: 前線(地表 38 から底へ 103 段の半分 ≒ y −12)より上の閉じた空洞(浅い・真ん中)の床と天井は汚染され、下の深い空洞はきれいなまま。
     * 空気に面していない石(床の 2 段下・塔の真ん中の奥)は変わらない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seepCorruptsOnlyAboveFrontAndAirFacing(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        whenLoaded(helper, a, 0, () -> {
            ServerLevel level = helper.getLevel();
            build(level, a);
            long now = 1_000_000L;
            double days = DesignData.sporeField().seepDays();
            int jobs = MyceliumSeep.runChunkForTests(level, a, now - ticks(days / 2), now);
            helper.assertTrue(jobs > 0, "板が 1 枚も作られない");
            for (int floor : new int[]{SHALLOW, MIDDLE}) {
                helper.assertTrue(dirty(level, floorOf(a, floor)), "前線より上の閉じた空洞の床が汚染されない: y " + floor);
                helper.assertTrue(dirty(level, ceilingOf(a, floor)), "前線より上の閉じた空洞の天井が汚染されない: y " + (floor + 4));
                helper.assertTrue(!dirty(level, floorOf(a, floor).below(2)), "空気に面していない石が汚染された: y " + (floor - 2));
            }
            helper.assertTrue(!dirty(level, floorOf(a, DEEP)), "前線より下の空洞の床が汚染された");
            helper.assertTrue(!dirty(level, ceilingOf(a, DEEP)), "前線より下の空洞の天井が汚染された");
            helper.assertTrue(!dirty(level, new BlockPos(a.getMinBlockX() + 3, 10, a.getMinBlockZ() + 3)), "塔の奥の石が汚染された");
            int p = MyceliumSeep.progressPpm(level.getChunkAt(floorOf(a, 0)));
            helper.assertTrue(Math.abs(p - MyceliumSeep.SCALE / 2) < MyceliumSeep.SCALE / 100, "進みが半分でない: " + p);
            unforce(level, a);
            helper.succeed();
        });
    }

    /**
     * 水に面したブロック(2026-11-08): 前線より上の、水で満たした閉じた部屋の床(水にだけ面している)は汚染され、
     * 溶岩で満たした部屋の床(溶岩にだけ面している)は変わらない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seepCorruptsLakeBottomButNotLavaFloor(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        whenLoaded(helper, a, 0, () -> {
            ServerLevel level = helper.getLevel();
            build(level, a);
            int wetFloor = 8, lavaFloor = 14;
            for (int x = 2; x <= 4; x++)
                for (int z = 2; z <= 4; z++)
                    for (int dy = 1; dy <= 3; dy++) {
                        level.setBlock(new BlockPos(a.getMinBlockX() + x, wetFloor + dy, a.getMinBlockZ() + z), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                        level.setBlock(new BlockPos(a.getMinBlockX() + x + 8, lavaFloor + dy, a.getMinBlockZ() + z), Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
            BlockPos wet = new BlockPos(a.getMinBlockX() + 3, wetFloor, a.getMinBlockZ() + 3);
            BlockPos hot = new BlockPos(a.getMinBlockX() + 11, lavaFloor, a.getMinBlockZ() + 3);
            long now = 1_000_000L;
            int jobs = MyceliumSeep.runChunkForTests(level, a, now - ticks(DesignData.sporeField().seepDays() / 2), now);
            helper.assertTrue(jobs > 0, "板が 1 枚も作られない");
            if (DesignData.sporeField().seepWaterFacing()) {
                helper.assertTrue(dirty(level, wet), "水にだけ面した湖の底が汚染されない: " + level.getBlockState(wet) + " 板 " + jobs + " 進み " + MyceliumSeep.progressPpm(level.getChunkAt(wet)) + " | " + MyceliumSeep.whyForTests(level, wet));
            }
            helper.assertTrue(!dirty(level, hot), "溶岩にだけ面した床が汚染された: " + level.getBlockState(hot));
            helper.assertTrue(level.getBlockState(hot.above()).is(Blocks.LAVA), "溶岩が書き換えられた: " + level.getBlockState(hot.above()));
            unforce(level, a);
            helper.succeed();
        });
    }

    /** 巨大樹の根のまわり(2026-11-08): 幹の中心から半径の内のチャンクは前線が {@code seepRootSpeed} 倍の速さ(半分の日数で底まで)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seepIsFasterAroundGiantTreeRoots(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        whenLoaded(helper, a, 0, () -> {
            ServerLevel level = helper.getLevel();
            build(level, a);
            var t = DesignData.sporeField();
            com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
            int ox = a.getMiddleBlockX() + 20, oz = a.getMiddleBlockZ();
            long packed = ((long) ox << 32) | (oz & 0xFFFFFFFFL);
            pd.recordGiantTreeOrigin(ox, oz);
            int jobs;
            try {
                long now = 3_000_000L;
                jobs = MyceliumSeep.runChunkForTests(level, a, now - ticks(t.seepDays() / t.seepRootSpeed()), now);
            } finally {
                pd.forgetGiantTreeForTests(packed);
            }
            helper.assertTrue(jobs > 0, "板が作られない");
            int p = MyceliumSeep.progressPpm(level.getChunkAt(floorOf(a, 0)));
            if (t.seepRootSpeed() > 1.0D && t.seepRootRadius() >= 20) {
                helper.assertTrue(p == MyceliumSeep.SCALE, "根のまわりなのに、" + t.seepRootSpeed() + " 倍の速さで底まで届かない: " + p);
                helper.assertTrue(dirty(level, floorOf(a, DEEP)), "根のまわりの深い空洞が汚染されない");
            }
            unforce(level, a);
            helper.succeed();
        });
    }

    /**
     * 追いつきでも植物が生える(2026-11-08、境目の帯のセッションが見つけた「天井まで汚染されたのに植物 0」の直し)。
     * 前線より先にほかの経路(壁を這う汚染など。植物を生やさない)が空洞の壁を汚染していた場合も、一度に全部の日数を追いつかせると、
     * 前線が通るときに植物の入口が通り、空洞に植物(動きを止めないブロック)が生える。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seepGrowsPlantsOnCatchUpEvenIfCaveWasCorruptedFirst(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        whenLoaded(helper, a, 0, () -> {
            ServerLevel level = helper.getLevel();
            build(level, a);
            // 真ん中と深い空洞の壁・床・天井を先に汚染しておく(植物は生やさない = 壁を這う汚染と同じ)
            int pre = 0;
            for (int floor : new int[]{MIDDLE, DEEP})
                for (int x = 5; x <= 9; x++)
                    for (int z = 5; z <= 9; z++)
                        for (int y = floor; y <= floor + 4; y++) {
                            BlockPos p = new BlockPos(a.getMinBlockX() + x, y, a.getMinBlockZ() + z);
                            BlockState s = level.getBlockState(p);
                            if (s.isAir()) continue;
                            BlockState c = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, p, s, level.random);
                            if (!com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.noChange(s, c)) {
                                com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.placeCorrupted(level, p, s, c, Block.UPDATE_CLIENTS);
                            }
                            if (dirty(level, p)) pre++;
                        }
            int before = 0;
            for (int floor : new int[]{MIDDLE, DEEP})
                for (int x = 6; x <= 8; x++)
                    for (int z = 6; z <= 8; z++)
                        for (int y = floor + 1; y <= floor + 3; y++) {
                            BlockState s = level.getBlockState(new BlockPos(a.getMinBlockX() + x, y, a.getMinBlockZ() + z));
                            if (!s.isAir()) before++;
                        }
            helper.assertTrue(before == 0, "テストの前提: 先に汚染しただけで空洞に何か置かれた: " + before);
            helper.assertTrue(pre > 0 && dirty(level, floorOf(a, MIDDLE)) && dirty(level, ceilingOf(a, DEEP)), "テストの前提: 空洞の壁を先に汚染できない(" + pre + ")");
            double share = DesignData.sporeField().seepPlantShare();
            long now = 7_000_000L;
            MyceliumSeep.runChunkForTests(level, a, now - ticks(DesignData.sporeField().seepDays() * 2), now);
            int plants = 0;
            for (int floor : new int[]{MIDDLE, DEEP})
                for (int x = 6; x <= 8; x++)
                    for (int z = 6; z <= 8; z++)
                        for (int y = floor + 1; y <= floor + 3; y++) {
                            BlockState s = level.getBlockState(new BlockPos(a.getMinBlockX() + x, y, a.getMinBlockZ() + z));
                            if (!s.isAir() && !s.blocksMotion()) plants++;
                        }
            if (share > 0.0D && com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.growthChance.get() > 0.2D
                    && com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
                helper.assertTrue(plants > 0, "先に汚染された空洞に、追いつきで植物が 1 つも生えない");
            }
            unforce(level, a);
            helper.succeed();
        });
    }

    /** 過繁茂でない(台帳に載っていない)チャンクの下は、何日経っても変わらない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seepLeavesNonOvergrownChunkClean(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        whenLoaded(helper, a, 0, () -> {
            ServerLevel level = helper.getLevel();
            build(level, a);
            int jobs = MyceliumSeep.runChunkForTests(level, a, -1L, 10_000_000L);
            helper.assertTrue(jobs == 0, "過繁茂でないのに板が作られた: " + jobs);
            for (int floor : new int[]{SHALLOW, MIDDLE, DEEP}) {
                helper.assertTrue(!dirty(level, floorOf(a, floor)), "過繁茂でない所の空洞の床が汚染された: y " + floor);
                helper.assertTrue(!dirty(level, ceilingOf(a, floor)), "過繁茂でない所の空洞の天井が汚染された: y " + (floor + 4));
            }
            unforce(level, a);
            helper.succeed();
        });
    }

    /**
     * 読み込まれていなかった分の追いつき: いきなり全部の日数が経ったチャンクは、何枚もの板に分けて底まで進む。深い空洞まで汚染され、奥の石は変わらない。
     * その後に回しても新しい板は作られない(底まで済み)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seepCatchesUpInSlabsToBottom(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        whenLoaded(helper, a, 0, () -> {
            ServerLevel level = helper.getLevel();
            build(level, a);
            long now = 5_000_000L;
            int jobs = MyceliumSeep.runChunkForTests(level, a, now - ticks(DesignData.sporeField().seepDays() * 2), now);
            int slab = DesignData.sporeField().seepSlabBlocks();
            helper.assertTrue(jobs >= (103 / slab), "板に分けていない: " + jobs + " 枚(板 " + slab + " 段)");
            for (int floor : new int[]{SHALLOW, MIDDLE, DEEP})
                helper.assertTrue(dirty(level, floorOf(a, floor)) && dirty(level, ceilingOf(a, floor)), "底まで進んだのに空洞が汚染されない: y " + floor);
            helper.assertTrue(!dirty(level, floorOf(a, DEEP).below(2)), "空気に面していない深い石が汚染された");
            helper.assertTrue(MyceliumSeep.progressPpm(level.getChunkAt(floorOf(a, 0))) == MyceliumSeep.SCALE, "進みが底まででない");
            helper.assertTrue(MyceliumSeep.runChunkForTests(level, a, now - 1, now + ticks(10)) == 0, "底まで済んだのに板が作られた");
            unforce(level, a);
            helper.succeed();
        });
    }
}
