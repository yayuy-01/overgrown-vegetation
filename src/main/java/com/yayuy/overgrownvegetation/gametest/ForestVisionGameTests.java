package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.VisionTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseWatch;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestVision;
import com.yayuy.overgrownvegetation.director.ScoutSearch;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 森の視界(戦場の霧)と探りの偵察(2026-11-20 司令塔の作り直し 区切り 2)。世界の台帳・予算・探りの偵察の保存データを触るので 1 つの束で順に。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ForestVisionGameTests {

    private static final String BATCH = "director_vision";
    /** 森を置く遠くの升(ほかのテストの台帳とぶつからない所)。 */
    private static final int FX = 250_000, FZ = 250_000;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterForestVisionGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(ForestVisionGameTests.class);
        }

        private Registration() {
        }
    }

    private ForestVisionGameTests() {
    }

    private static List<ChunkPos> plantForest(ServerLevel level, int cx, int cz, int half) {
        List<ChunkPos> out = new ArrayList<>();
        for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) out.add(new ChunkPos(cx + dx, cz + dz));
        PhaseData.get(level).markOvergrownChunks(level, out);
        return out;
    }

    private static void forget(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) PhaseData.get(level).forgetOvergrownChunk(c);
    }

    /** 森の縁から 64・偵察の周り 32・フィロセンスの周りは見え、それより外は見えない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void visionCoversForestScoutsAndWatchers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VisionTable t = ForestVision.table();
        List<ChunkPos> forest = plantForest(level, FX, FZ, 0);
        ScoutSearch search = ScoutSearch.get(level.getServer());
        try {
            int edgeX = FX * 16 + 16; // 森の升の東の縁
            int z = FZ * 16 + 8;
            helper.assertTrue(ForestVision.sees(level, new BlockPos(edgeX + (int) t.forestEdgeBlocks - 2, 64, z)), "森の縁から 64 の内が見えない");
            BlockPos far = new BlockPos(edgeX + (int) t.forestEdgeBlocks + 40, 64, z);
            helper.assertTrue(!ForestVision.sees(level, far), "森の縁から遠い所が見える");
            // 偵察の周り 32
            ScoutSearch.Explorer e = search.addExplorerForTests(far.getX() + 20, far.getZ());
            helper.assertTrue(ForestVision.sees(level, far), "偵察の周りが見えない");
            search.explorers().remove(e);
            helper.assertTrue(!ForestVision.sees(level, far), "偵察がいなくなっても見える");
            // フィロセンス(育ち切った監視塔)の周り
            BaseMemory memory = BaseMemory.createForTests();
            double day = 1.0D;
            long chunk = ChunkPos.asLong(far.getX() >> 4, far.getZ() >> 4);
            memory.addPresence(chunk, 100.0D, day);
            memory.update(day);
            helper.assertTrue(!memory.bases().isEmpty(), "テストの拠点ができない");
            helper.assertTrue(!ForestVision.nearWatcher(memory, far, t.watcherBlocks), "監視塔が無いのに見える");
            ForestVision.addGrownWatcherForTests(memory.bases().get(0), far.offset(10, 0, 0));
            helper.assertTrue(ForestVision.nearWatcher(memory, far, t.watcherBlocks), "フィロセンスの周りが見えない");
            helper.succeed();
        } finally {
            forget(level, forest);
            search.clearForTests();
        }
    }

    /** 視界の外にいるプレイヤーの升は覚えず、視界の中なら覚える(拠点の記憶の見張り)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void basesOnlyLearnedInsideVision(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseMemory memory = BaseMemory.createForTests();
        BlockPos at = new BlockPos(FX * 16 + 2000, 64, FZ * 16 + 8);
        long chunk = ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4);
        helper.assertTrue(!BaseWatch.observe(level, memory, at, 1.0D, 1.0D) && memory.presenceAt(chunk) == 0.0D, "視界の外で覚えた");
        List<ChunkPos> forest = plantForest(level, (at.getX() >> 4) + 2, at.getZ() >> 4, 0);
        try {
            helper.assertTrue(BaseWatch.observe(level, memory, at, 1.0D, 1.0D) && memory.presenceAt(chunk) > 0.0D, "視界の中なのに覚えない");
            helper.succeed();
        } finally {
            forget(level, forest);
        }
    }

    /** 偵察がプレイヤーに殺されたら、その所が手がかりになり、そちらへ偵察が足される。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 40)
    public static void killedScoutDrawsMoreScouts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ScoutSearch search = ScoutSearch.get(level.getServer());
        search.clearForTests();
        ForestDirector dir = ForestDirector.get(level);
        double before = dir.available(level);
        List<ChunkPos> forest = plantForest(level, FX, FZ, 1);
        ServerPlayer killer = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("0fe55a2e-0000-4000-8000-00000000a1d0"), "vision_test"));
        try {
            AbstractParasitizedMonster m = (AbstractParasitizedMonster) helper.spawn(
                    com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickG1(level.getRandom()).entityType(), new BlockPos(3, 2, 3));
            m.goalSelector.removeAllGoals(g -> true);
            m.fate().scout().beginExplore(m.getX() + 50, m.getZ(), false);
            helper.assertTrue(m.fate().scout().active(), "探りの使いが始まらない");
            m.hurt(level.damageSources().playerAttack(killer), 10000.0F);
            helper.assertTrue(!m.isAlive(), "偵察が倒れない");
            boolean clue = search.clues().stream().anyMatch(c -> c.kind == ScoutSearch.ClueKind.KILLED
                    && Math.hypot(c.x - m.getX(), c.z - m.getZ()) < 1.0D);
            helper.assertTrue(clue, "殺された所が手がかりにならない");
            helper.assertTrue(search.extraOnKill() == ForestVision.table().explorersOnKill, "足す偵察の数が違う: " + search.extraOnKill());
            dir.setBudget(level, 100000.0D);
            helper.assertTrue(search.launch(level, 1.0D, level.getRandom()), "偵察を出せない");
            ScoutSearch.Explorer e = search.explorers().get(search.explorers().size() - 1);
            helper.assertTrue(e.kind == ScoutSearch.ClueKind.KILLED && Math.hypot(e.tx - m.getX(), e.tz - m.getZ()) < 1.0D,
                    "殺された所へ偵察が向かわない: " + e.kind + " (" + e.tx + ", " + e.tz + ")");
            helper.succeed();
        } finally {
            forget(level, forest);
            search.clearForTests();
            dir.setBudget(level, before);
        }
    }

    /** A 夜の明かり: 地表の明かりがあれば数え、明かりを消すと数えない。B 汚れ: 汚れた装備・最近森にいたことで強くなり、無ければ 0。 */
    @GameTest(template = "gametest_empty7", batch = "director_vision_light", timeoutTicks = 120)
    public static void lightAndTrailClues(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VisionTable.Json j = new VisionTable.Json();
        j.lightSampleRadius = 2.0D;
        j.lightSamples = 40;
        j.lightMinLevel = 10;
        VisionTable t = VisionTable.fromJson(j);
        BlockPos center = helper.absolutePos(new BlockPos(3, 1, 3));
        // 床の上に何も無い = 暗い(ブロックの光 0)
        helper.assertTrue(ScoutSearch.lightsAround(level, center, t, RandomSource.create(1)) == 0, "明かりが無いのに数えた");
        ServerPlayer p = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("0fe55a2e-0000-4000-8000-00000000a1d1"), "trail_test"));
        p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        helper.assertTrue(ScoutSearch.trailStrength(p, null, 5.0D) == 0.0D, "汚れも森の滞在も無いのに跡が出る");
        helper.assertTrue(ScoutSearch.trailStrength(p, 5.0D, 5.0D) == 1.0D, "森から出たばかりなのに跡が弱い");
        helper.assertTrue(ScoutSearch.trailStrength(p, 5.0D, 5.0D + ForestVision.table().trailDays) == 0.0D, "日がたっても跡が残る");
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        com.yayuy.overgrownvegetation.survival.decon.GearDirt.setDirt(helmet, 1000);
        p.setItemSlot(EquipmentSlot.HEAD, helmet);
        double s = ScoutSearch.trailStrength(p, null, 5.0D);
        helper.assertTrue(s > 0.0D, "汚れた装備で跡が出ない");
        p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY); // 汚れを落とした = 跡が消える
        helper.assertTrue(ScoutSearch.trailStrength(p, null, 5.0D) == 0.0D, "汚れを落としても跡が出る");

        // 明かり: 高さ地図のいちばん上のブロック(空の見える地表)を明かりに替える。光の伝わりは次の tick 以降なので、置いてから待つ。
        // 終わったら元のブロックへ戻す(テスト場の外にかかることがあるので)
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> was = new java.util.HashMap<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                BlockPos top = new BlockPos(x, level.getChunkAt(new BlockPos(x, 0, z)).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15), z);
                was.put(top, level.getBlockState(top));
                level.setBlock(top, Blocks.SEA_LANTERN.defaultBlockState(), 3);
            }
        }
        helper.runAfterDelay(10, () -> {
            int lit = ScoutSearch.lightsAround(level, center, t, RandomSource.create(1));
            BlockPos c0 = was.keySet().iterator().next();
            int h0 = level.getChunkAt(c0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, c0.getX() & 15, c0.getZ() & 15);
            helper.assertTrue(lit >= 2, "地表の明かりを数えない: " + lit + "(置いた " + c0 + "・高さ " + h0 + "・その上の光 "
                    + level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, new BlockPos(c0.getX(), h0 + 1, c0.getZ())) + "・置いた所 "
                    + level.getBlockState(c0) + ")");
            for (BlockPos q : was.keySet()) level.setBlock(q, Blocks.STONE.defaultBlockState(), 3);
            helper.runAfterDelay(40, () -> { // 暗くなる側の光の伝わりは遅いことがある
                int dark = ScoutSearch.lightsAround(level, center, t, RandomSource.create(1));
                was.forEach((q, st) -> level.setBlock(q, st, 3));
                helper.assertTrue(dark == 0, "明かりを消しても数える: " + dark);
                helper.succeed();
            });
        });
    }

    /** D 広がる探し方: 手がかりが無くても、方角を順に塗りつぶし、いつか遠くのプレイヤーのそばを通る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void sweepEventuallyFindsFarPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ScoutSearch search = ScoutSearch.get(level.getServer());
        search.clearForTests();
        ForestDirector dir = ForestDirector.get(level);
        double before = dir.available(level);
        VisionTable t = ForestVision.table();
        List<ChunkPos> forest = plantForest(level, FX, FZ, 2);
        try {
            dir.setBudget(level, 10000000.0D);
            search.pinCenterForTests(level, FX * 16 + 8, FZ * 16 + 8, 40.0D); // テストのワールドの台帳にはほかのテストの升も混ざるので、森の真ん中を決め打ち
            double[] c = search.forestCenter(level);
            helper.assertTrue(c != null, "森の真ん中が分からない");
            // 隠れたプレイヤー: 適当な方角(どの区切りの真ん中でもない)、森の縁から 3 歩先
            double ang = 2.3D;
            double dist = c[2] + t.sweepStepBlocks * 3.0D;
            double px = c[0] + Math.cos(ang) * dist, pz = c[1] + Math.sin(ang) * dist;
            boolean found = false;
            double day = 0.0D;
            search.move(level, day);
            for (int step = 0; step < 40000 && !found; step++) {
                if (search.explorers().isEmpty()) helper.assertTrue(search.launch(level, day, level.getRandom()), "偵察を出せない");
                day += 0.01D; // 1 歩 24 ブロック(偵察の周り 32 より短い)
                search.move(level, day);
                // 偵察の周り 32 は見える。プレイヤーのいる所が見えたら見つけた
                found = search.anyExplorerWithin(px, pz, t.scoutBlocks);
            }
            helper.assertTrue(found, "広がる探し方で遠くのプレイヤーを見つけられない");
            int explored = 0;
            for (double r : search.sweepReach()) if (r > 0.0D) explored++;
            helper.assertTrue(explored >= 2, "方角を塗りつぶしていない: " + explored);
            helper.succeed();
        } finally {
            forget(level, forest);
            search.clearForTests();
            dir.setBudget(level, before);
        }
    }
}
