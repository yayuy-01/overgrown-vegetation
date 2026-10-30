package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.purify.HiveFireGuard;
import com.yayuy.overgrownvegetation.purify.VanillaFire;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;

/**
 * ハイヴノードのまわり(水平 512)では火が燃え広がらない(2026-10-10、ユーザー指定。{@link HiveFireGuard})。
 * <ul>
 *     <li>範囲: 記録したハイヴノードから水平 511 は中・513 は外、真上 200 も中。記録を外すと範囲も消える。</li>
 *     <li>空いたマスへ飛び移る強さ({@code FireBlock#getIgniteOdds})が範囲の中では 0、外ではバニラの値。</li>
 *     <li>範囲の中でバニラの燃える物(オークの板)・この MOD の植物が燃え尽きても、そのマスに火が残らない(20 回ずつ。バニラなら年齢 0 で半分は残る)。</li>
 * </ul>
 * GameTest のサーバーは火の広がりが切られている(doFireTick)ので、火の更新を待たずにそれぞれの口を直接呼ぶ。
 * ハイヴノードの記録はワールドに 1 つ(フェーズの台帳)なので、足した記録と「記録を始めたか」は同じ tick のうちに戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FireSpreadGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FireSpreadGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "fire_spread";
    private static final int TRIALS = 20;

    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void noFireSpreadNearHiveNode(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData phase = PhaseData.get(level);
        boolean recordedBefore = phase.hiveNodesRecordedForTests();
        BlockPos node = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos planks = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos air = planks.above();
        BlockPos grass = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos plant = grass.above();
        Block overgrownGrass = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("overgrown_grass_block"));
        Block overgrownGrowth = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("overgrown_growth"));
        RandomSource random = RandomSource.create(20261010L);
        boolean outsideBefore = HiveFireGuard.protects(level, node);
        try {
            phase.recordHiveNode(node);
            // 範囲
            helper.assertTrue(HiveFireGuard.protects(level, node), "ハイヴノードの真上で燃え広がる");
            helper.assertTrue(HiveFireGuard.protects(level, node.east(511)), "水平 511 で燃え広がる");
            helper.assertTrue(HiveFireGuard.protects(level, node.above(200).south(300)), "真上寄り(高さ +200)で燃え広がる(高さは問わないはず)");
            helper.assertTrue(outsideBefore || !HiveFireGuard.protects(level, node.east(513)), "水平 513 まで燃え広がらない(範囲が広すぎる)");

            // 空いたマスへ飛び移らない
            level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(air, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(igniteOdds(level, air) == 0, "範囲の中で板の上の空いたマスへ火が飛び移れる: " + igniteOdds(level, air));

            // 燃え尽きたマスに火が残らない(バニラの燃える物・この MOD の植物)
            for (int i = 0; i < TRIALS; i++) {
                level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                boolean handled = VanillaFire.onBurnOut(level, planks, 1, random, 0, Direction.UP);
                helper.assertTrue(handled, "範囲の中のオークの板をバニラに任せた(火が残りうる)");
                helper.assertTrue(level.getBlockState(planks).isAir(), "範囲の中で燃え尽きた板のマスが空気でない: " + level.getBlockState(planks));
            }
            level.setBlock(grass, overgrownGrass.defaultBlockState(), Block.UPDATE_ALL);
            for (int i = 0; i < TRIALS; i++) {
                level.setBlock(plant, overgrownGrowth.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(level.getBlockState(plant).is(overgrownGrowth), "汚染の草が置けない(支えが無い?)");
                VanillaFire.consume(level, plant, random, 0);
                helper.assertTrue(!level.getBlockState(plant).is(Blocks.FIRE), "範囲の中で燃え尽きた汚染の草のマスに火が残った");
            }

            // 記録を外すと範囲も消える(ほかのハイヴノードの範囲にたまたま入っていなければ)
            phase.removeHiveNode(node);
            if (!outsideBefore) {
                helper.assertTrue(!HiveFireGuard.protects(level, node), "ハイヴノードを外しても範囲が残った");
                level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(igniteOdds(level, air) > 0, "範囲の外で板の上の空いたマスへ火が飛び移れない(バニラの値が出ない)");
                helper.assertTrue(!VanillaFire.onBurnOut(level, planks, 1, random, 0, Direction.UP), "範囲の外のオークの板をバニラに任せない");
            }
            helper.succeed();
        } finally {
            phase.removeHiveNode(node);
            phase.restoreHiveNodesRecordedForTests(recordedBefore);
            level.setBlock(planks, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(plant, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** {@code FireBlock#getIgniteOdds(LevelReader, BlockPos)}(private)を、mixin が付いたまま呼ぶ。 */
    private static int igniteOdds(ServerLevel level, BlockPos pos) {
        try {
            Method m = FireBlock.class.getDeclaredMethod("getIgniteOdds", LevelReader.class, BlockPos.class);
            m.setAccessible(true);
            return (int) m.invoke(Blocks.FIRE, level, pos);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FireBlock#getIgniteOdds を呼べない", e);
        }
    }

    private FireSpreadGameTests() {
    }
}
