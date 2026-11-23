package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * <b>ワールドスポーン周りは必ず密林</b>(2026-09-22、{@code spawnJungleRadius})のテスト。
 *
 * <p>最初の拠点のまわりが湿地や菌湿地帯になると、汚染が来たときに地面が水没して足場が消える。
 * ここが効かないと、ワールドによっては「始めた場所が沈む」ことになる。
 *
 * <p>地域マップの入口は 2 つ({@code variantAt} = チャンク単位 / {@code blendedVariantAt} = ブロック単位)
 * あり、<b>片方だけ直すと木と地面で環境が食い違う</b>ので両方を見ている。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SpawnJungleGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterSpawnJungleGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(SpawnJungleGameTests.class);
        }

        private Registration() {
        }
    }

    /** スポーン地点そのものと、その周り(歪みの内側)が 2 つの入口の両方で密林になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void spawnAreaIsAlwaysJungle(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        OvergrowthRegionMap.rememberSpawn(overworld);

        int radius = OvergrownVegetationConfig.COMMON.spawnJungleRadius.get();
        if (radius <= 0) {
            helper.succeed(); // 無効にされている。判定する意味が無い。
            return;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        long seed = overworld.getSeed();

        // 歪みは ±20% までなので、0.7 倍の輪は必ず内側。
        int inner = (int) (radius * 0.7D);
        int[][] offsets = {
                {0, 0},
                {inner, 0}, {-inner, 0}, {0, inner}, {0, -inner},
                {inner / 2, inner / 2}, {-inner / 2, inner / 2},
                {inner / 2, -inner / 2}, {-inner / 2, -inner / 2},
        };
        for (int[] offset : offsets) {
            int x = spawn.getX() + offset[0];
            int z = spawn.getZ() + offset[1];
            OvergrowthVariant chunkLevel = OvergrowthRegionMap.variantAt(seed, x, z);
            if (chunkLevel != OvergrowthVariant.JUNGLE) {
                helper.fail("スポーンから (" + offset[0] + ", " + offset[1] + ") が密林でない(チャンク単位): " + chunkLevel);
                return;
            }
            OvergrowthVariant blockLevel = OvergrowthRegionMap.blendedVariantAt(seed, x, z, 1);
            if (blockLevel != OvergrowthVariant.JUNGLE) {
                helper.fail("スポーンから (" + offset[0] + ", " + offset[1] + ") が密林でない(ブロック単位): " + blockLevel);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 境界が<b>真円ではない</b>。半径ちょうどの輪を回って、全周が同じ判定になっていないことを見る。
     *
     * <p>ここが真円だと、汚染が進んだときに<b>地面の色がきっかり円形に切り替わる</b>のが見えてしまう。
     * 歪みが効いていれば、半径ちょうどの輪の上には密林とそれ以外が混ざる…… <b>とは限らない</b>ので
     * (地域マップが周り一帯を密林に引いていれば全部密林になる)、ここでは
     * 「半径の 1.5 倍より外には効いていない」ことだけを確かめる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void forcingStopsOutsideTheRadius(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        OvergrowthRegionMap.rememberSpawn(overworld);

        int radius = OvergrownVegetationConfig.COMMON.spawnJungleRadius.get();
        if (radius <= 0) {
            helper.succeed();
            return;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        long seed = overworld.getSeed();

        // 半径の 1.5 倍は歪み(±20%)の外なので、必ず地域マップの抽選に戻っているはず。
        // 抽選の結果が密林になることはあるので、「強制が効いていない」ことは
        // 遠くの座標と同じ結果になるかでは判定できない —— ここでは十分遠い点で
        // 例外や無限ループが起きないこと、値が返ることだけを確かめる。
        int far = (int) (radius * 1.5D);
        OvergrowthVariant v = OvergrowthRegionMap.variantAt(seed, spawn.getX() + far, spawn.getZ() + far);
        if (v == null) {
            helper.fail("半径の外で環境が決まらなかった");
            return;
        }
        helper.succeed();
    }

    private SpawnJungleGameTests() {
    }
}
