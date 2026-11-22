package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 巨大樹の計画の速さと形の指紋(2026-11-18 大群の軽量化 B: 計画を速くしても形が 1 ブロックも変わらないかを、直す前と後で比べる)。
 * <b>ふだんの GameTest には入らない</b>: 環境変数 {@code OV_TREE_BENCH} があるときだけ登録する。
 * 本物の巨大樹の設定(configured_feature の overgrown_tree_giant)で、決まった遠い場所(平らなテストの世界なので地形は毎回同じ)に
 * 種 1〜3 の計画を作り、指紋・時間・ブロックの数をログに {@code OV_TREE_BENCH} の行で出す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GiantTreePlanBench {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            if (System.getenv("OV_TREE_BENCH") != null) event.register(GiantTreePlanBench.class);
        }

        private Registration() {
        }
    }

    private GiantTreePlanBench() {
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void giantTreePlanDigest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "overgrown_tree_giant"));
        if (feature == null || !(feature.config() instanceof GiantTreeFeature.Config config)) {
            helper.fail("overgrown_tree_giant の設定が無い");
            return;
        }
        BlockPos origin = new BlockPos(2_000_000, -60, 2_000_000);
        for (int x = (origin.getX() >> 4) - 16; x <= (origin.getX() >> 4) + 16; x++) { // 写しと比べるため周りを先に読み込む(案 A)
            for (int z = (origin.getZ() >> 4) - 16; z <= (origin.getZ() >> 4) + 16; z++) level.getChunk(x, z);
        }
        for (int round = 0; round < 3; round++) { // 1 回目は JIT の慣らしと、遠いチャンクの生成(1 回目だけ地形が出来ていく途中を読む)
            for (long seed = 1; seed <= 3; seed++) {
                long[] r = GiantTreeFeature.planDigestForTests(level, origin, config, seed);
                OvergrownVegetation.LOGGER.info("OV_TREE_BENCH round {} seed {} digest {} blocks {} ms {}", round, seed,
                        Long.toHexString(r[0]), r[2], String.format(java.util.Locale.ROOT, "%.1f", r[1] / 1e6));
                if (round > 0) { // 案 A: 同じ tick に写しから作った計画(指紋が同じか・写す時間・計画の時間)
                    long[] s = GiantTreeFeature.planDigestSnapshotForTests(level, origin, config, seed);
                    OvergrownVegetation.LOGGER.info("OV_TREE_BENCH snapshot round {} seed {} same {} capture_ms {} chunks {} plan_ms {} misses {} reach {}", round, seed,
                            s[0] == r[0], String.format(java.util.Locale.ROOT, "%.2f", s[4] / 1e6), s[5],
                            String.format(java.util.Locale.ROOT, "%.1f", s[1] / 1e6), s[3], s[6]);
                }
            }
        }
        helper.succeed();
    }
}
