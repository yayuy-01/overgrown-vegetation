package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.FallenLogBlock;
import com.yayuy.overgrownvegetation.block.LycopsidPlantBlock;
import com.yayuy.overgrownvegetation.block.MedullosaBlock;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.FloorGrowth;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 林床(2026-11-01): 汚染した草の面に小葉類の敷物と株が生え、泥炭が水辺に斑に出る / 倒木が地面に沿って寝て棚状の菌が付く /
 * メドゥロサが 2〜3 段で立つ。テスト場の上の空中に台を作って試し、終わったら台ごと空気に戻す(テスト場は密林とは限らないので
 * {@link FloorGrowth#runAsJungle} の中で)。隣のテスト場(12〜13 ブロックおき)の木生シダ・封印木(空中 40 段)と重ならない高さにする。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ForestFloorGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ForestFloorGameTests.class);
        }

        private Registration() {
        }
    }

    /** 1 チャンク分の草の面。真ん中に 2×2 の池(水辺の判定で泥炭が出る)。 */
    private static final int PLANE = 16;

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void floorPlantsGrowOnCorruptedGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(-4, 70, -4));
        int[] n = new int[13];
        int baseline = 0;
        try {
            // 林床を足す前(密林でない扱い = 今までの下草だけ)の立つ下草の数。同じ面で 1 回測ってから作り直す(決めること 1)
            build(level, origin, PLANE, true);
            FloorGrowth.runAsJungle(false, () -> {
                for (int dx = 0; dx < PLANE; dx++) {
                    for (int dz = 0; dz < PLANE; dz++) {
                        BlockPos pos = origin.offset(dx, 0, dz);
                        if (level.getBlockState(pos).is(ModBlocks.OVERGROWN_GRASS_BLOCK.get())) {
                            CorruptionSpreader.growSurfacePlants(level, pos, level.getBlockState(pos), CorruptionSpreader.surfaceGrowthChance(level, pos));
                        }
                    }
                }
            });
            for (int dx = 0; dx < PLANE; dx++) {
                for (int dz = 0; dz < PLANE; dz++) {
                    BlockState s = level.getBlockState(origin.offset(dx, 1, dz));
                    if (s.getBlock() instanceof BushBlock && !s.is(ModBlocks.OVERGROWN_SAPLING.get())) baseline++;
                }
            }
            clear(level, origin.offset(-8, -2, -8), origin.offset(PLANE + 8, 24, PLANE + 8));
            build(level, origin, PLANE, true);
            FloorGrowth.runAsJungle(() -> {
                for (int dx = 0; dx < PLANE; dx++) {
                    for (int dz = 0; dz < PLANE; dz++) {
                        BlockPos pos = origin.offset(dx, 0, dz);
                        BlockState host = level.getBlockState(pos);
                        if (!host.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get())) {
                            continue;
                        }
                        host = FloorGrowth.beforePlants(level, pos, host);
                        if (!host.is(Blocks.WATER)) {
                            CorruptionSpreader.growSurfacePlants(level, pos, host, CorruptionSpreader.surfaceGrowthChance(level, pos));
                        }
                    }
                }
            });
            // 0 敷物 1 敷物付きの株 2 株 3 立ち上がる 4 メドゥロサ 5 ほかの立つ下草 6 苔だけ・空き 7 泥炭 8 泥炭の水たまり 9 倒木 10 芽・屑 11 苗木など
            for (int dx = 0; dx < PLANE; dx++) {
                for (int dz = 0; dz < PLANE; dz++) {
                    BlockPos ground = origin.offset(dx, 0, dz);
                    BlockState g = level.getBlockState(ground);
                    if (g.is(ModBlocks.PEAT.get())) n[7]++;
                    if (g.is(Blocks.WATER) && level.getBlockState(ground.below()).is(ModBlocks.PEAT_POOL_BOTTOM.get())) n[8]++;
                    BlockState s = level.getBlockState(ground.above());
                    if (s.is(ModBlocks.LYCOPSID_CARPET.get())) n[0]++;
                    else if (s.getBlock() instanceof LycopsidPlantBlock) n[s.getValue(LycopsidPlantBlock.CARPET) ? 1 : 2]++;
                    else if (s.is(ModBlocks.LYCOPSID_UPRIGHT.get())) n[3]++;
                    else if (s.is(ModBlocks.MEDULLOSA_TRUNK.get())) n[4]++;
                    else if (s.is(ModBlocks.FALLEN_LOG.get()) || g.is(ModBlocks.FALLEN_LOG.get())) n[9]++;
                    else if (s.is(ModBlocks.PEAT_SPROUTS.get())) n[10]++;
                    else if (s.is(ModBlocks.PEAT_LITTER.get())) n[12]++;
                    else if (s.is(ModBlocks.OVERGROWN_SAPLING.get())) n[11]++;
                    else if (s.getBlock() instanceof BushBlock) n[5]++;
                    else n[6]++;
                }
            }
        } finally {
            clear(level, origin.offset(-8, -2, -8), origin.offset(PLANE + 8, 24, PLANE + 8));
        }
        int carpets = n[0] + n[1];
        int lycopsids = n[1] + n[2] + n[3];
        int crosses = n[1] + n[2] + n[3] + n[5] + n[10];
        String summary = String.format("敷物 %d(株付き %d)・株 %d・立ち上がる %d・メドゥロサ %d・ほかの立つ下草 %d・空き %d・泥炭 %d・水たまり %d・倒木 %d・芽 %d・屑 %d・苗木 %d / 立つ下草の合計 %d(林床の前 %d)",
                carpets, n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9], n[10], n[12], n[11], crosses, baseline);
        OvergrownVegetation.LOGGER.info("林床 1 チャンク: {}", summary);
        // 泥炭になるはずのマス: 斑の中で池から 5 以内(水辺)。斑は座標で決まるので面ごとに数が違う → 同じ式で数えて突き合わせる
        int expectedPeat = 0;
        int patches = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.floorPeatPatches.get();
        for (int dx = 0; dx < PLANE; dx++) {
            for (int dz = 0; dz < PLANE; dz++) {
                boolean pond = (dx == PLANE / 2 || dx == PLANE / 2 - 1) && (dz == PLANE / 2 || dz == PLANE / 2 - 1);
                int ddx = Math.max(0, Math.max(PLANE / 2 - 1 - dx, dx - PLANE / 2));
                int ddz = Math.max(0, Math.max(PLANE / 2 - 1 - dz, dz - PLANE / 2));
                BlockPos pos = origin.offset(dx, 0, dz);
                if (!pond && ddx <= 5 && ddz <= 5 && FloorGrowth.inPeatPatch(level.getSeed(), pos.getX(), pos.getZ(), patches)) expectedPeat++;
            }
        }
        summary += " / 泥炭になるはずのマス " + expectedPeat;
        OvergrownVegetation.LOGGER.info("林床 泥炭: {}", summary);
        int cells = PLANE * PLANE - 4;
        if (carpets < cells * 0.3 || carpets > cells * 0.75) {
            helper.fail("敷物の割合が 5〜6 割から遠い: " + summary);
        } else if (lycopsids < 8 || lycopsids > 60) {
            helper.fail("小葉類の株の数がおかしい: " + summary);
        } else if (n[7] + n[8] < expectedPeat - n[9] - 2) {
            helper.fail("池のそばの斑が泥炭にならない: " + summary);
        } else {
            helper.succeed();
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void fallenLogLiesAlongGroundWithFungi(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(-6, 100, -6));
        int size = 20;
        StringBuilder log = new StringBuilder();
        int good = 0;
        try {
            for (int t = 0; t < 6; t++) {
                build(level, origin, size, false);
                RandomSource random = RandomSource.create(0x5DEECE66DL * (t + 7) + 0x9E3779B97F4A7C15L);
                boolean[] placed = new boolean[1];
                FloorGrowth.runAsJungle(() -> placed[0] = FloorGrowth.placeFallenLog(level, origin.offset(size / 2, 0, size / 2), random));
                int logs = 0, roots = 0, fungi = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                java.util.Set<net.minecraft.core.Direction.Axis> axes = new java.util.HashSet<>();
                for (int dx = 0; dx < size; dx++) {
                    for (int dz = 0; dz < size; dz++) {
                        for (int dy = -1; dy <= 3; dy++) {
                            BlockState s = level.getBlockState(origin.offset(dx, dy, dz));
                            if (s.is(ModBlocks.FALLEN_LOG.get())) {
                                if (s.getValue(FallenLogBlock.ROOT)) {
                                    roots++;
                                } else {
                                    logs++;
                                    axes.add(s.getValue(FallenLogBlock.AXIS));
                                    minY = Math.min(minY, dy);
                                    maxY = Math.max(maxY, dy);
                                }
                            } else if (s.is(ModBlocks.BRACKET_FUNGUS.get())) {
                                fungi++;
                            }
                        }
                    }
                }
                log.append(String.format("[%d: 置いた %s・幹 %d・根の皿 %d・菌 %d・高さ %d〜%d] ", t, placed[0], logs, roots, fungi, minY, maxY));
                if (placed[0] && (logs < 3 || logs > 9 || axes.size() != 1 || (fungi < 1 && minY == 1) || (roots != 0 && roots != 3) || maxY > 1 || minY < 0)) {
                    helper.fail("倒木の形がおかしい: " + log);
                    return;
                }
                if (placed[0]) good++;
                clear(level, origin.offset(-2, -2, -2), origin.offset(size + 2, 6, size + 2));
            }
        } finally {
            clear(level, origin.offset(-2, -2, -2), origin.offset(size + 2, 6, size + 2));
        }
        OvergrownVegetation.LOGGER.info("倒木: {}", log);
        if (good < 5) {
            helper.fail("平らな草の面で倒木が置けない: " + log);
        } else {
            helper.succeed();
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void medullosaStandsTwoOrThreeTall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(-2, 130, -2));
        int size = 11;
        int twos = 0, threes = 0;
        try {
            build(level, origin, size, false);
            RandomSource random = RandomSource.create(0x2545F4914F6CDD1DL);
            for (int i = 0; i < 9; i++) {
                BlockPos pos = origin.offset(1 + (i % 3) * 4, 1, 1 + (i / 3) * 4);
                boolean[] ok = new boolean[1];
                FloorGrowth.runAsJungle(() -> ok[0] = FloorGrowth.growMedullosa(level, pos, random));
                if (!ok[0]) {
                    helper.fail("空いた所に立たなかった: " + i);
                    return;
                }
                BlockState base = level.getBlockState(pos);
                if (!base.is(ModBlocks.MEDULLOSA_TRUNK.get()) || !base.getValue(MedullosaBlock.FLAG)) {
                    helper.fail("根元が幹(根元の絵)でない: " + base);
                    return;
                }
                int height = 1;
                while (level.getBlockState(pos.above(height)).is(ModBlocks.MEDULLOSA_TRUNK.get())) height++;
                BlockState crown = level.getBlockState(pos.above(height));
                if (!crown.is(ModBlocks.MEDULLOSA_CROWN.get())) {
                    helper.fail("幹の上が冠でない: " + crown);
                    return;
                }
                height++;
                if (height != 2 && height != 3 || crown.getValue(MedullosaBlock.FLAG) != (height == 3)) {
                    helper.fail("高さ " + height + " / 冠 " + crown);
                    return;
                }
                if (height == 2) twos++; else threes++;
            }
            // 根元を壊すと上が順に崩れる(次の tick)
            BlockPos first = origin.offset(1, 1, 1);
            level.setBlock(first, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            int t2 = twos, t3 = threes;
            helper.runAfterDelay(4, () -> {
                boolean left = false;
                for (int dy = 1; dy <= 3; dy++) left |= !level.getBlockState(first.above(dy)).isAir();
                clear(level, origin.offset(-2, -1, -2), origin.offset(size + 2, 6, size + 2));
                OvergrownVegetation.LOGGER.info("メドゥロサ: 高さ 2 = {}・高さ 3 = {}", t2, t3);
                if (left) {
                    helper.fail("根元を壊しても上が残った");
                } else if (t2 == 0 || t3 == 0) {
                    helper.fail("高さ 2 と 3 の両方が出ない: " + t2 + " / " + t3);
                } else {
                    helper.succeed();
                }
            });
        } catch (RuntimeException e) {
            clear(level, origin.offset(-2, -1, -2), origin.offset(size + 2, 6, size + 2));
            throw e;
        }
    }

    /** {@code origin} を北西の隅にした size×size の草の面(下に土)。pond なら真ん中 2×2 を水にする。 */
    private static void build(ServerLevel level, BlockPos origin, int size, boolean pond) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), ModBlocks.OVERGROWN_DIRT.get().defaultBlockState(), flags);
                boolean water = pond && (dx == size / 2 || dx == size / 2 - 1) && (dz == size / 2 || dz == size / 2 - 1);
                level.setBlock(origin.offset(dx, 0, dz), water ? Blocks.WATER.defaultBlockState() : ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), flags);
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos from, BlockPos to) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
            }
        }
    }

    private ForestFloorGameTests() {
    }
}
