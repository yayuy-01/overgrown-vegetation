package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.field.CaveWallCreep;
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 汚染がチャンクの境で止まらないこと(2026-11-04 ユーザー決定): 洞窟の壁を這う汚染(地下の b)・地表の縁の門(A)・穴埋め(B)・芽の点の待ち行列(C)。
 * テスト場から遠いチャンクを読み込んで、その場で組み立てて回す(1 tick の中で終わらせる)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CorruptionEdgeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterCorruptionEdgeGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(CorruptionEdgeGameTests.class);
        }

        private Registration() {
        }
    }

    private CorruptionEdgeGameTests() {
    }

    private static final int RUN_SALT = new java.util.Random().nextInt(1 << 16);
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();

    /** 実行ごとに違う、遠いチャンク(東隣も使う)。 */
    private static ChunkPos fresh() {
        int base = 1_000_000 + (RUN_SALT % 2000) * 64 + NEXT.getAndIncrement() * 8; // 世界の縁(±3000 万ブロック)の内側
        return new ChunkPos(base, -base);
    }

    /** 遠いチャンクとまわり 3×3 を強制読み込みにする(getChunk だけでは getChunkNow・hasChunk が読み込みと見ない)。 */
    private static ChunkPos forced(GameTestHelper helper) {
        ChunkPos a = fresh();
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) helper.getLevel().setChunkForced(a.x + dx, a.z + dz, true);
        return a;
    }

    private static void unforce(ServerLevel level, ChunkPos a) {
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(a.x + dx, a.z + dz, false);
    }

    private static boolean dirty(ServerLevel level, BlockPos p) {
        return OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p));
    }

    /**
     * 洞窟の壁を這う汚染: チャンクの境をまたぐ石の中のトンネル(長さ 10)の西の端の床を汚染して回すと、東のチャンクの床まで這っていく。
     * 床の下の石(空気に面していない)・天井の上の石には広がらない。
     */
    @GameTest(template = "gametest_empty7")
    public static void caveWallCreepFollowsTunnelAcrossChunks(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        helper.runAfterDelay(20, () -> caveWallCreepFollowsTunnelAcrossChunksBody(helper, a));
    }

    private static void caveWallCreepFollowsTunnelAcrossChunksBody(GameTestHelper helper, ChunkPos a) {
        ServerLevel level = helper.getLevel();
        level.getChunk(a.x, a.z);
        level.getChunk(a.x + 1, a.z);
        int x0 = a.getMinBlockX() + 10, z0 = a.getMinBlockZ() + 4, y0 = 100;
        // 石の箱 x0..x0+11 × y0..y0+8 × z0..z0+4、中に y0+3..y0+4・z0+2 のトンネル(x0+1..x0+10)
        for (int x = x0; x <= x0 + 11; x++)
            for (int y = y0; y <= y0 + 8; y++)
                for (int z = z0; z <= z0 + 4; z++) {
                    boolean tunnel = x >= x0 + 1 && x <= x0 + 10 && z == z0 + 2 && (y == y0 + 3 || y == y0 + 4);
                    level.setBlock(new BlockPos(x, y, z), tunnel ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
        BlockPos start = new BlockPos(x0 + 1, y0 + 2, z0 + 2);
        BlockState st = level.getBlockState(start);
        BlockState corrupted = CorruptionRegistry.corrupt(level, start, st, level.random);
        if (!CorruptionRegistry.noChange(st, corrupted)) CorruptionRegistry.placeCorrupted(level, start, st, corrupted, Block.UPDATE_ALL);
        helper.assertTrue(dirty(level, start), "テストの前提: 元の床が汚染されない");
        CaveWallCreep.seedForTests(level, start);
        helper.assertTrue(CaveWallCreep.frontierSize(level) > 0, "元として拾われない: " + CaveWallCreep.whyNotSeed(level, start));
        BlockPos far = new BlockPos(x0 + 10, y0 + 2, z0 + 2); // 東のチャンクの床
        helper.assertTrue((far.getX() >> 4) == a.x + 1, "テストの前提: 東の端が隣のチャンクにない");
        for (int i = 0; i < 400 && !dirty(level, far); i++) CaveWallCreep.runForTests(level, 5);
        helper.assertTrue(dirty(level, far), "東のチャンクの床まで這わない");
        for (int x = x0 + 1; x <= x0 + 10; x++) {
            helper.assertTrue(!dirty(level, new BlockPos(x, y0 + 1, z0 + 2)), "床の下の空気に面していない石に広がった: x " + x);
            helper.assertTrue(!dirty(level, new BlockPos(x, y0 + 7, z0 + 2)), "天井の上の空気に面していない石に広がった: x " + x);
        }
        unforce(helper.getLevel(), a);
        helper.succeed();
    }

    /** 地表の縁の門: 過繁茂にしたチャンクの東隣(汚染されていない)へ、縁の浸食が境を越えて数ブロック入り、隣の真ん中までは行かない。 */
    @GameTest(template = "gametest_empty7")
    public static void surfaceEdgeCrossesChunkBorder(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        helper.runAfterDelay(20, () -> surfaceEdgeCrossesChunkBorderBody(helper, a));
    }

    private static void surfaceEdgeCrossesChunkBorderBody(GameTestHelper helper, ChunkPos a) {
        ServerLevel level = helper.getLevel();
        ChunkPos b = new ChunkPos(a.x + 1, a.z);
        level.getChunk(a.x, a.z);
        level.getChunk(b.x, b.z);
        level.getChunk(a.x + 2, a.z);
        OvergrownBiomeConverter.forceOvergrown(level, a); // A を過繁茂のバイオームに(地表は下で東の端だけ塗る。2026-11-14 四角い塗りを消した)
        // A の東の端の列を全部汚染する(模様の塗り残しで境が途切れないように)
        for (int z = a.getMinBlockZ(); z <= a.getMaxBlockZ(); z++) {
            int x = a.getMaxBlockX();
            BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
            BlockState s = level.getBlockState(g);
            if (!dirty(level, g)) {
                BlockState c = CorruptionRegistry.corrupt(level, g, s, level.random);
                if (!CorruptionRegistry.noChange(s, c)) CorruptionRegistry.placeCorrupted(level, g, s, c, Block.UPDATE_ALL);
            }
        }
        for (int i = 0; i < 80; i++) OvergrownBiomeCreep.frontierForTest(level, b);
        int near = 0, deep = 0;
        java.util.Set<Integer> depths = new java.util.HashSet<>();
        for (int z = b.getMinBlockZ(); z <= b.getMaxBlockZ(); z++) {
            int reach = -1;
            for (int lx = 0; lx < 16; lx++) {
                int x = b.getMinBlockX() + lx;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                boolean c = false;
                for (int k = 0; k < 3 && !c; k++) c = dirty(level, new BlockPos(x, y - k, z));
                if (!c) continue;
                if (lx <= 5) near++;
                if (lx >= 8) deep++;
                reach = Math.max(reach, lx);
            }
            depths.add(reach);
        }
        helper.assertTrue(near > 0, "縁の浸食が隣のチャンクへ入らない(境で止まった)");
        helper.assertTrue(deep == 0, "縁の浸食が隣のチャンクの真ん中を越えた: " + deep + " 列");
        helper.assertTrue(depths.size() >= 2, "縁がまっすぐ(どの行も同じ深さ)" + depths);
        unforce(helper.getLevel(), a);
        helper.succeed();
    }

    /** 穴埋め: 過繁茂のチャンクの中で 4 方を汚染に囲まれた未汚染の列は、1 回のフロンティア浸食で確率と待ちを無視して埋まる。 */
    @GameTest(template = "gametest_empty7")
    public static void enclosedHoleIsFilled(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        helper.runAfterDelay(20, () -> enclosedHoleIsFilledBody(helper, a));
    }

    private static void enclosedHoleIsFilledBody(GameTestHelper helper, ChunkPos a) {
        ServerLevel level = helper.getLevel();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(a.x + dx, a.z + dz);
        OvergrownBiomeConverter.forceOvergrown(level, a); // A を過繁茂のバイオームに(穴のまわりは下で塗る。2026-11-14 四角い塗りを消した)
        int x = a.getMiddleBlockX(), z = a.getMiddleBlockZ();
        // 真ん中の列と 4 方を作り直す: 4 方は汚染、真ん中は素の草
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) {
            int nx = x + d[0], nz = z + d[1];
            BlockPos g = new BlockPos(nx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1, nz);
            BlockState s = level.getBlockState(g);
            if (!dirty(level, g)) {
                BlockState c = CorruptionRegistry.corrupt(level, g, s, level.random);
                if (!CorruptionRegistry.noChange(s, c)) CorruptionRegistry.placeCorrupted(level, g, s, c, Block.UPDATE_ALL);
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int k = 0; k < 3; k++) level.setBlock(new BlockPos(x, y + 1 + k, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos hole = new BlockPos(x, y, z);
        level.setBlock(hole, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        OvergrownLayerData.uncover(level, hole);
        helper.assertTrue(!dirty(level, hole), "テストの前提: 穴が汚染済み");
        OvergrownBiomeCreep.frontierForTest(level, a);
        helper.assertTrue(dirty(level, hole), "囲まれた穴が 1 回で埋まらない");
        unforce(helper.getLevel(), a);
        helper.succeed();
    }

    /** 芽の点の待ち行列: 同時に走る上限を超えた点は捨てずに待ち、空きができると走る。 */
    @GameTest(template = "gametest_empty7")
    public static void overflowSproutPointsRunLater(GameTestHelper helper) {
        ChunkPos a = forced(helper);
        helper.runAfterDelay(20, () -> overflowSproutPointsRunLaterBody(helper, a));
    }

    private static void overflowSproutPointsRunLaterBody(GameTestHelper helper, ChunkPos a) {
        ServerLevel level = helper.getLevel();
        int max = DesignData.sporeField().sproutMaxActive();
        level.getChunk(a.x, a.z);
        BlockPos o = new BlockPos(a.getMiddleBlockX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.getMiddleBlockX(), a.getMiddleBlockZ()), a.getMiddleBlockZ());
        SporeSprouts.clearForTests(level);
        try {
            for (int i = 0; i < max + 5; i++) SporeSprouts.addPointForTests(level, o.offset(i % 7, 0, i / 7 % 7));
            int[] c = SporeSprouts.countsForTests(level);
            helper.assertTrue(c[0] == max && c[1] >= 1, "上限を超えた点が待たない: 走る " + c[0] + "・待つ " + c[1]);
            int waiting = c[1];
            SporeSprouts.clearActiveForTests(level);
            SporeSprouts.drainWaitingForTests(level);
            c = SporeSprouts.countsForTests(level);
            helper.assertTrue(c[0] >= 1 && c[1] < waiting, "空きができても待ちの点が走らない: 走る " + c[0] + "・待つ " + c[1]);
        } finally {
            SporeSprouts.clearForTests(level);
        }
        unforce(helper.getLevel(), a);
        helper.succeed();
    }
}
