package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 巨大樹の根の作り直し(2026-11-07、決定 4・5): 空中の足場(オークの板)に小さな巨大樹の根だけを計画し、
 * (1) 構造物(板・丸石の塀)のマスに根を置かない、(2) 下が空いた根のマスが無い(斜めの支柱にならず真下へ垂れる)、
 * (3) 根が足場の縁から地面まで下りて這う、を見る。ワールドには置かない(計画だけ)。テスト場の上の空中に台を作り、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GiantTreeRootGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGiantTreeRootGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(GiantTreeRootGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int FLOOR_R = 26;
    private static final int PLATFORM_R = 7;
    private static final int PLATFORM_LIFT = 10;
    private static final int WALL_X = 15;

    @GameTest(template = "gametest_empty7", batch = "giant_tree_roots", timeoutTicks = 100)
    public static void rootsHangStraightAndKeepStructures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 205, 0));
        int floorY = base.getY();
        int platY = floorY + PLATFORM_LIFT;
        try {
            // 地面(土・上は草)、空中の足場(板 2 枚)、地面の上に丸石の塀(高さ 2)。
            fill(level, base.offset(-FLOOR_R, -3, -FLOOR_R), base.offset(FLOOR_R, -1, FLOOR_R), Blocks.DIRT.defaultBlockState());
            fill(level, base.offset(-FLOOR_R, 0, -FLOOR_R), base.offset(FLOOR_R, 0, FLOOR_R), Blocks.GRASS_BLOCK.defaultBlockState());
            fill(level, base.offset(-PLATFORM_R, PLATFORM_LIFT - 1, -PLATFORM_R), base.offset(PLATFORM_R, PLATFORM_LIFT, PLATFORM_R),
                    Blocks.OAK_PLANKS.defaultBlockState());
            fill(level, base.offset(WALL_X, 1, -FLOOR_R), base.offset(WALL_X, 2, FLOOR_R), Blocks.COBBLESTONE.defaultBlockState());

            BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            if (log.hasProperty(RotatedPillarBlock.AXIS)) {
                log = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
            }
            GiantTreeFeature.Config config = new GiantTreeFeature.Config(log, Blocks.OAK_LEAVES.defaultBlockState(),
                    16, 3, 1, 6, 22, 1, 0, 0, 1, 0, 8, 2, 2);
            BlockPos origin = new BlockPos(base.getX(), platY + 1, base.getZ());

            int floating = 0;
            int onStructure = 0;
            int onFloorOutside = 0;
            String firstFloat = null;
            for (long seed = 1; seed <= 4; seed++) {
                LongOpenHashSet cells = GiantTreeFeature.planRootsForTests(level, origin, config, seed);
                for (long k : cells) {
                    BlockPos p = BlockPos.of(k);
                    BlockState here = level.getBlockState(p);
                    if (here.is(Blocks.OAK_PLANKS) || here.is(Blocks.COBBLESTONE)) {
                        onStructure++;
                    }
                    BlockPos below = p.below();
                    boolean overFloor = Math.max(Math.abs(p.getX() - base.getX()), Math.abs(p.getZ() - base.getZ())) < FLOOR_R - 1;
                    // 地面の外(テスト場の縁から空へ落ちた根)は垂らして終わるのが正しいので数えない。
                    // 2026-11-09: 構造物の縁から垂れる根はうねる(横へずれる所は斜め下の根に支えられる)。真下にも斜め下(3×3)にも
                    // 根が無く、真下が空いているマスだけを「浮き」と数える(離れて浮いたかけら)。
                    boolean diagSupport = false;
                    // 2026-11-18: 構造物の縁から外へ張り出して弧で下りる根は、出だしがほぼ水平(斜め下 5×5 の根に支えられる)。
                    for (int ox = -2; ox <= 2 && !diagSupport; ox++) {
                        for (int oz = -2; oz <= 2; oz++) {
                            if (cells.contains(below.offset(ox, 0, oz).asLong())) {
                                diagSupport = true;
                                break;
                            }
                        }
                    }
                    if (overFloor && !diagSupport) {
                        BlockState b = level.getBlockState(below);
                        if (b.isAir() || b.canBeReplaced()) {
                            floating++;
                            if (firstFloat == null) {
                                firstFloat = p.subtract(base).toShortString();
                            }
                        }
                    }
                    int dx = p.getX() - base.getX();
                    int dz = p.getZ() - base.getZ();
                    if (p.getY() == floorY + 1 && Math.max(Math.abs(dx), Math.abs(dz)) > PLATFORM_R + 1) {
                        onFloorOutside++;
                    }
                }
            }
            if (onStructure > 0) {
                helper.fail("構造物(板・丸石)のマスに根が " + onStructure + " 個");
                return;
            }
            if (floating > 0) {
                helper.fail("下が空いた根のマスが " + floating + " 個(最初 " + firstFloat + ")");
                return;
            }
            if (onFloorOutside == 0) {
                helper.fail("根が足場の縁から地面まで下りていない");
                return;
            }
            OvergrownVegetation.LOGGER.info("GiantTreeRootGameTests: 種 4 つで 浮き 0・構造物 0・足場の外の地面の根 {} マス",
                    onFloorOutside);
            helper.succeed();
        } finally {
            fill(level, base.offset(-FLOOR_R - 2, -3, -FLOOR_R - 2), base.offset(FLOOR_R + 2, PLATFORM_LIFT + 2, FLOOR_R + 2),
                    Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * 第 2 段(2026-11-07): 石の塊(上は草)の中に広い洞窟の広間(高さ 7)を作り、上に小さな巨大樹の根を計画する。
     * 深い根が広間の天井を貫いて柱・横切り・垂れのどれかになること、広間に置いた板(廃坑の支え)のマスに根が無いことを見る。
     */
    @GameTest(template = "gametest_empty7", batch = "giant_tree_roots", timeoutTicks = 100)
    public static void deepRootsPierceCaveCeiling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 110, 0));
        int r = 22;
        try {
            fill(level, base.offset(-r, 0, -r), base.offset(r, 23, r), Blocks.STONE.defaultBlockState());
            fill(level, base.offset(-r, 24, -r), base.offset(r, 24, r), Blocks.GRASS_BLOCK.defaultBlockState());
            fill(level, base.offset(-r + 1, 8, -r + 1), base.offset(r - 1, 14, r - 1), Blocks.AIR.defaultBlockState());
            // 廃坑の支えのような板の柱(構造物)を広間に 4 本。
            for (int[] q : new int[][] {{6, 6}, {-6, 6}, {6, -6}, {-6, -6}}) {
                fill(level, base.offset(q[0], 8, q[1]), base.offset(q[0], 14, q[1]), Blocks.OAK_PLANKS.defaultBlockState());
            }
            BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            if (log.hasProperty(RotatedPillarBlock.AXIS)) {
                log = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
            }
            GiantTreeFeature.Config config = new GiantTreeFeature.Config(log, Blocks.OAK_LEAVES.defaultBlockState(),
                    16, 3, 1, 6, 22, 1, 0, 0, 1, 0, 8, 2, 2);
            BlockPos origin = base.offset(0, 25, 0);
            int shown = 0;
            int inCave = 0;
            for (long seed = 1; seed <= 4; seed++) {
                int[] counts = new int[6];
                LongOpenHashSet cells = GiantTreeFeature.planRootsForTests(level, origin, config, seed, true, counts);
                shown += counts[2] + counts[3] + counts[4];
                for (long k : cells) {
                    BlockPos p = BlockPos.of(k);
                    if (level.getBlockState(p).is(Blocks.OAK_PLANKS)) {
                        helper.fail("板(構造物)のマスに根 " + p.subtract(base).toShortString());
                        return;
                    }
                    int dy = p.getY() - base.getY();
                    if (dy >= 8 && dy <= 14 && level.getBlockState(p).isAir()) {
                        inCave++;
                    }
                }
            }
            if (shown == 0 || inCave == 0) {
                helper.fail("深い根が洞窟に出ていない(柱・横切り・垂れ " + shown + "、広間の根のマス " + inCave + ")");
                return;
            }
            OvergrownVegetation.LOGGER.info("GiantTreeRootGameTests: 洞窟に出た所 {}・広間の根のマス {}", shown, inCave);
            helper.succeed();
        } finally {
            fill(level, base.offset(-r - 1, 0, -r - 1), base.offset(r + 1, 26, r + 1), Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * 巣の球(2026-11-07): 石の塊(地面)の 12 段上に板の足場を浮かせ、その上に木を置いたつもりで巣の球を計画する。
     * (1) 球の中心が足場ではなく下の自然の地面の高さ、(2) 地面に埋めた石レンガの部屋(構造物)のマスを上書きも掘りもしない、を見る。
     */
    @GameTest(template = "gametest_empty7", batch = "giant_tree_roots", timeoutTicks = 100)
    public static void hiveChamberSitsOnGroundAndKeepsStructures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 60, 0));
        int r = 20;
        try {
            fill(level, base.offset(-r, 0, -r), base.offset(r, 30, r), Blocks.STONE.defaultBlockState());
            int groundTop = base.getY() + 30;
            fill(level, base.offset(-8, 42, -8), base.offset(8, 42, 8), Blocks.OAK_PLANKS.defaultBlockState());
            // 地面の中に埋まった石レンガの部屋(球の中に入る所)。
            fill(level, base.offset(3, 24, 3), base.offset(8, 29, 8), Blocks.STONE_BRICKS.defaultBlockState());
            BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            GiantTreeFeature.Config config = new GiantTreeFeature.Config(log, Blocks.OAK_LEAVES.defaultBlockState(),
                    16, 3, 1, 6, 22, 1, 0, 0, 1, 0, 8, 2, 2);
            long[] center = {Long.MIN_VALUE};
            LongOpenHashSet cells = GiantTreeFeature.planHiveForTests(level, base.offset(0, 43, 0), config, 1L, center);
            if (center[0] == Long.MIN_VALUE || cells.isEmpty()) {
                helper.succeed(); // 設定で巣の球が切ってある
                return;
            }
            int cy = BlockPos.getY(center[0]);
            if (cy > groundTop) {
                helper.fail("巣の球の中心が地面より上(" + (cy - groundTop) + " 段)= 足場の高さに置いている");
                return;
            }
            for (long k : cells) {
                if (level.getBlockState(BlockPos.of(k)).is(Blocks.STONE_BRICKS) || level.getBlockState(BlockPos.of(k)).is(Blocks.OAK_PLANKS)) {
                    helper.fail("巣の球が構造物のマスを使う " + BlockPos.of(k).subtract(base).toShortString());
                    return;
                }
            }
            OvergrownVegetation.LOGGER.info("GiantTreeRootGameTests: 巣の球の中心は地面から {} 段・マス {}", cy - groundTop, cells.size());
            helper.succeed();
        } finally {
            fill(level, base.offset(-r - 1, 0, -r - 1), base.offset(r + 1, 44, r + 1), Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * 構造物の上の巨大樹(2026-11-09 ユーザー報告): 地面(土)の上に石英レンガの塔(25×25・高さ 28)を建て、屋上を汚染の皮
     * (overgrown_bricks 1 枚。汚染されたブロックは自然の地面の仲間に入る)で覆い、その上に木を置いたつもりで根を計画する。
     * (1) 根が屋上の皮を置き換えない(潜らない)、(2) 塔の外壁の外に垂れる根があり、上と下で列がずれている(うねる)、を見る。
     */
    @GameTest(template = "gametest_empty7", batch = "giant_tree_roots", timeoutTicks = 100)
    public static void rootsOnStructureStayOnTopAndSway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 150, 0));
        int r = 30;
        int t = 12;
        try {
            fill(level, base.offset(-r, 0, -r), base.offset(r, 2, r), Blocks.DIRT.defaultBlockState());
            fill(level, base.offset(-t, 3, -t), base.offset(t, 30, t), Blocks.QUARTZ_BRICKS.defaultBlockState());
            fill(level, base.offset(-t, 31, -t), base.offset(t, 31, t), ModBlocks.OVERGROWN_BRICKS.get().defaultBlockState());
            BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            GiantTreeFeature.Config config = new GiantTreeFeature.Config(log, Blocks.OAK_LEAVES.defaultBlockState(),
                    16, 3, 1, 6, 22, 1, 0, 0, 1, 0, 8, 2, 2);
            BlockPos origin = base.offset(0, 32, 0);
            int buried = 0;
            String firstBuried = null;
            java.util.Set<Long> low = new java.util.HashSet<>();
            java.util.Set<Long> high = new java.util.HashSet<>();
            int outside = 0;
            for (long seed = 1; seed <= 4; seed++) {
                LongOpenHashSet cells = GiantTreeFeature.planRootsForTests(level, origin, config, seed, true, new int[6]);
                for (long k : cells) {
                    BlockPos p = BlockPos.of(k);
                    if (level.getBlockState(p).is(ModBlocks.OVERGROWN_BRICKS.get())) {
                        buried++;
                        if (firstBuried == null) {
                            firstBuried = p.subtract(base).toShortString() + " 種 " + seed;
                        }
                    }
                    int dx = p.getX() - base.getX();
                    int dz = p.getZ() - base.getZ();
                    int dy = p.getY() - base.getY();
                    if (Math.max(Math.abs(dx), Math.abs(dz)) > t && dy >= 6 && dy <= 28) {
                        outside++;
                        long col = BlockPos.asLong(p.getX(), 0, p.getZ()) ^ (seed << 60);
                        if (dy == 9) {
                            low.add(col);
                        } else if (dy == 25) {
                            high.add(col);
                        }
                    }
                }
            }
            if (buried > 0) {
                helper.fail("根が屋上の汚染の皮を " + buried + " マス置き換えた(潜った。最初 " + firstBuried + ")");
                return;
            }
            if (outside == 0) {
                helper.fail("塔の外壁の外に垂れる根が無い");
                return;
            }
            if (!low.isEmpty() && low.equals(high)) {
                helper.fail("外へ垂れる根が真っすぐ(上と下で列が同じ)");
                return;
            }
            OvergrownVegetation.LOGGER.info("GiantTreeRootGameTests: 構造物の上 潜り 0・外壁の外の根 {} マス・列(下 {} / 上 {})",
                    outside, low.size(), high.size());
            helper.succeed();
        } finally {
            fill(level, base.offset(-r - 1, 0, -r - 1), base.offset(r + 1, 34, r + 1), Blocks.AIR.defaultBlockState());
        }
    }

    private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            level.setBlock(p, state, Block.UPDATE_CLIENTS);
        }
    }
}
