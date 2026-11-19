package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.GiantOvergrownSaplingBlock;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue;
import com.yayuy.overgrownvegetation.worldgen.GiantTreePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 巨大樹の計画を別スレッドで作る(2026-11-18 大群の軽量化 B の案 A)の確かめ。
 *
 * <ul>
 *     <li>同じ種なら、生きた世界から読んだ計画と、サーバースレッドで写した地形から読んだ計画が 1 ブロックも違わない。写しの外は読まない。</li>
 *     <li>苗木から育て始めると、計画の間は苗木のまま・計画中として数えられ・同じ苗木からは 2 本目を始めない。
 *     計画中に捨てると(世界を閉じた・チャンクが外れたのと同じ扱い)、木は積まれず、苗木は残り、巨大樹の位置も記録されない。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GiantTreeAsyncGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GiantTreeAsyncGameTests.class);
        }

        private Registration() {
        }
    }

    private GiantTreeAsyncGameTests() {
    }

    /** 確かめの前に読み込んでおく半径(チャンク)。計画が読むのは 7〜9 チャンクまで(11-18 の測り)。 */
    private static final int PRELOAD_CHUNKS = 14; // 11-20: 10 → 14(構造物の根の作り直しで計画の届く範囲が 12 チャンクまで伸び、1 回目の計画がチャンクを読み込んで 2 回目と違った)

    private static GiantTreeFeature.Config giantConfig(GameTestHelper helper) {
        ConfiguredFeature<?, ?> feature = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(OvergrownVegetation.id("overgrown_tree_giant"));
        if (feature == null || !(feature.config() instanceof GiantTreeFeature.Config config)) {
            throw new IllegalStateException("overgrown_tree_giant の設定が無い");
        }
        return config;
    }

    /** 生きた世界と写しで、同じ種の計画の指紋が同じ・写しの外を読まない。 */
    @GameTest(template = "gametest_empty7", batch = "giant_tree_async", timeoutTicks = 200)
    public static void snapshotPlanMatchesLivePlan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GiantTreeFeature.Config config = giantConfig(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
        // 生きた世界の計画は、読み込まれていないチャンクを読むとその場で読み込む(読み込み済みかの答えが計画の途中で変わる)ので、
        // 先に周りを読み込んでおく(同じ tick のうちは読み込まれたまま)。そのうえで生きた世界の計画が 2 回同じ = 地形が止まっていることを確かめ、写しと比べる
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        for (int x = cx - PRELOAD_CHUNKS; x <= cx + PRELOAD_CHUNKS; x++) {
            for (int z = cz - PRELOAD_CHUNKS; z <= cz + PRELOAD_CHUNKS; z++) {
                level.getChunk(x, z);
            }
        }
        long[] live0 = GiantTreeFeature.planDigestForTests(level, origin, config, 7);
        long[] live = GiantTreeFeature.planDigestForTests(level, origin, config, 7);
        long[] snap = GiantTreeFeature.planDigestSnapshotForTests(level, origin, config, 7);
        OvergrownVegetation.LOGGER.info("OV_TREE_ASYNC live {} ms blocks {} stable {} / snapshot capture {} ms chunks {} plan {} ms blocks {} misses {} reach {}",
                live[1] / 1_000_000L, live[2], live0[0] == live[0], snap[4] / 1_000_000L, snap[5], snap[1] / 1_000_000L, snap[2], snap[3], snap[6]);
        if (live0[0] != live[0]) {
            helper.fail("周りを読み込んだ後も生きた世界の計画が 2 回で違う(地形が動いている)");
            return;
        }
        if (snap[3] != 0) {
            helper.fail("写しの外を " + snap[3] + " 回読んだ(写しの半径が足りない)");
            return;
        }
        if (live[0] != snap[0] || live[2] != snap[2]) {
            helper.fail("写しから作った計画が生きた世界のと違う: ブロック " + live[2] + " / " + snap[2]);
            return;
        }
        helper.succeed();
    }

    /** 苗木から育て始めると計画の間は苗木のまま。計画中に捨てると、木は積まれず苗木が残り、位置も記録されない。 */
    @GameTest(template = "gametest_empty7", batch = "giant_tree_async", timeoutTicks = 400)
    public static void saplingWaitsForPlanAndSurvivesDiscard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 3));
        level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        BlockState sapling = ModBlocks.GIANT_OVERGROWN_SAPLING.get().defaultBlockState().setValue(SaplingBlock.STAGE, 1);
        level.setBlock(pos, sapling, Block.UPDATE_ALL);
        GiantOvergrownSaplingBlock block = (GiantOvergrownSaplingBlock) sapling.getBlock();
        int before = GiantTreeGrowthQueue.pendingJobs(level);

        block.advanceTree(level, pos, sapling, RandomSource.create(1));
        if (!level.getBlockState(pos).is(block)) {
            helper.fail("計画の間に苗木が消えた: " + level.getBlockState(pos));
            return;
        }
        if (!GiantTreePlanner.inFlightAt(level, pos) || GiantTreeGrowthQueue.pendingJobs(level) != before + 1) {
            helper.fail("計画中として数えられていない: " + GiantTreeGrowthQueue.pendingJobs(level) + "(前 " + before + ")");
            return;
        }
        // 計画の間にもう一度育っても 2 本目は始めない
        block.advanceTree(level, pos, sapling, RandomSource.create(2));
        if (GiantTreeGrowthQueue.pendingJobs(level) != before + 1 || !level.getBlockState(pos).is(block)) {
            helper.fail("同じ苗木から 2 本目を始めた: " + GiantTreeGrowthQueue.pendingJobs(level));
            return;
        }
        int discarded = GiantTreeGrowthQueue.discardJobsForTests(level);
        if (discarded < 1) {
            helper.fail("計画中の木を捨てられない");
            return;
        }
        // 計画が出来上がる頃(数百 ms)まで待っても、積まれず・苗木は残り・位置は記録されない
        helper.runAfterDelay(100, () -> {
            if (GiantTreeGrowthQueue.pendingJobs(level) != 0) {
                GiantTreeGrowthQueue.discardJobsForTests(level);
                helper.fail("捨てた計画が積まれた");
                return;
            }
            if (!level.getBlockState(pos).is(block)) {
                helper.fail("捨てた後に苗木が残っていない: " + level.getBlockState(pos));
                return;
            }
            if (PhaseData.get(level).hasGiantTreeOriginWithin(pos.getX(), pos.getZ(), 1)) {
                helper.fail("捨てた木の位置が記録された");
                return;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }
}
