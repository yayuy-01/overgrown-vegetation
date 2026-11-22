package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.RaidSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 襲撃の湧かせる所(2026-11-21 F7 までの通しの放置の 2 回目): 森の方角が海の上を向く拠点(島ではない)でも、角度を広げて陸の側に湧かせる
 * ({@link RaidSpawns#findHiddenSpotWidening})。1 回目の放置では、森の方角 ±40° がすべて海で、送り込みが 1 体も湧かなかった。
 * 全周が水(島)なら見つからないまま(呼ぶ側が流れ木もどきで運ぶ)。
 *
 * <p>空を開けたテスト場で組む(天井のバリアがあると高さの地図がそこを答える)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RaidSpotGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(RaidSpotGameTests.class);
        }

        private Registration() {
        }
    }

    private RaidSpotGameTests() {
    }

    static final String T = "gametest_empty24";

    /** x < landFrom は水面(2〜3 段)、それより東は陸(石・土・草)。 */
    static void halfSea(GameTestHelper h, int landFrom) {
        for (int x = 0; x < 24; x++)
            for (int z = 0; z < 24; z++) {
                if (x < landFrom) {
                    h.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
                    h.setBlock(new BlockPos(x, 3, z), Blocks.WATER);
                } else {
                    h.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                    h.setBlock(new BlockPos(x, 3, z), Blocks.DIRT);
                    h.setBlock(new BlockPos(x, 4, z), Blocks.GRASS_BLOCK);
                }
            }
    }

    /** 森の方角(西)が海: 決めた扇(±40°)では見つからず、広げると東の陸に湧く。足元は水でない固い地面。 */
    @GameTest(template = T, batch = "raid_spot", timeoutTicks = 20, skyAccess = true)
    public static void seaFacingBaseSpawnsOnLand(GameTestHelper h) {
        halfSea(h, 12);
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(new BlockPos(12, 4, 12));
        double cx = c.getX() + 0.5D, cz = c.getZ() + 0.5D;
        double west = Math.PI; // 森の方角 = 海の側
        // 広げない探し方では、西 ±40° の 4〜10 ブロックはどこも水なので見つからない(直す前の 1 回目の放置と同じ)
        h.assertTrue(RaidSpawns.findHiddenSpot(level, cx, cz, west, 40.0D, 4.0D, 10.0D, 0.0D, 24, level.getRandom()) == null,
                "海の扇の中に湧かせる所があった(テスト場の作りが崩れている)");
        long[] widened0 = RaidSpawns.lastWidened.clone();
        for (int i = 0; i < 8; i++) {
            BlockPos p = RaidSpawns.findHiddenSpotWidening(level, cx, cz, west, 40.0D, 4.0D, 10.0D, 0.0D, 12, level.getRandom());
            h.assertTrue(p != null, "広げても湧かせる所が見つからない(" + i + " 回目)");
            // 位置は真ん中からのずれ(絶対座標)で書く。陸の側かは足元のブロックで見る(海の側は水しか無い)
            String at = "真ん中から (" + (p.getX() - c.getX()) + ", " + (p.getY() - c.getY()) + ", " + (p.getZ() - c.getZ()) + ")";
            h.assertTrue(level.getFluidState(p).isEmpty() && level.getFluidState(p.below()).isEmpty(), "水の上に湧いた: " + at);
            h.assertTrue(level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK), "足元が陸の地面ではない: " + at + " " + level.getBlockState(p.below()));
            h.assertTrue(Math.cos(Math.atan2(p.getZ() + 0.5D - cz, p.getX() + 0.5D - cx) - west) < Math.cos(Math.toRadians(40.0D)),
                    "森の方角の扇(海)の中に湧いた: " + at);
        }
        h.assertTrue(RaidSpawns.lastWidened[0] == widened0[0], "決めた扇の中で見つかった(海のはず)");
        h.assertTrue(RaidSpawns.lastWidened[1] + RaidSpawns.lastWidened[2] - widened0[1] - widened0[2] == 8, "広げた段で見つかった数が合わない");
        h.succeed();
    }

    /** 全周が水(島の拠点)なら、広げても見つからない(呼ぶ側の流れ木もどきに任せる)。 */
    @GameTest(template = T, batch = "raid_spot", timeoutTicks = 20, skyAccess = true)
    public static void islandStillFindsNothing(GameTestHelper h) {
        halfSea(h, 24);
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(new BlockPos(12, 4, 12));
        long none0 = RaidSpawns.lastWidened[3];
        BlockPos p = RaidSpawns.findHiddenSpotWidening(level, c.getX() + 0.5D, c.getZ() + 0.5D, Math.PI, 40.0D, 4.0D, 10.0D, 0.0D, 12, level.getRandom());
        h.assertTrue(p == null, "全周が水なのに湧かせる所が見つかった: " + (p == null ? "" : h.relativePos(p)));
        h.assertTrue(RaidSpawns.lastWidened[3] == none0 + 1, "見つからなかった数を数えていない");
        h.succeed();
    }
}
