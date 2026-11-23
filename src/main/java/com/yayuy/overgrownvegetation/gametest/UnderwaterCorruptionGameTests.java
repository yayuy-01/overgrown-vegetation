package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 水中のブロック汚染の抜け(2026-11-01 夜、ユーザー「川底で砂岩・砂・土・粘土が素のまま残る。海では砂利が汚染されない」)。
 *
 * <p>過繁茂の地域の浸食(OvergrownBiomeCreep)は列の頂から的を選ぶ。頂が水の上にかぶさった林冠の葉・胞子昆布の柱だと、
 * 前はその下の水底が的にならなかった。もう 1 つ、覆った地表(砂・砂利・砂岩など)を「未汚染」と数えて深部浸食が始まらなかった。
 * テスト場の {@value #SKY} 段上の空中にガラスの水槽を作って試し、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class UnderwaterCorruptionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterUnderwaterCorruptionGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(UnderwaterCorruptionGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;
    /** 水底(y 1)の上の水の深さ。水面の上 2 段に林冠を置く。 */
    private static final int DEPTH = 5;
    private static final Block[] FLOORS = {Blocks.SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.CLAY, Blocks.DIRT, Blocks.STONE};

    /**
     * 水底に砂・砂利・砂岩・粘土・土・石を並べ、奥の 2 列の上に過繁茂の葉の林冠、1 列に胞子昆布の柱を立てて、列ごとの浸食を回す。
     * 水底の 25 マスが全部、置き換わるか覆われる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void creepReachesEveryWaterFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        build(level, origin);
        for (int round = 0; round < 12; round++) {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    OvergrownBiomeCreep.creepColumnForTest(level, origin.getX() + x, origin.getZ() + z);
                }
            }
        }
        int done = 0;
        StringBuilder left = new StringBuilder();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                BlockPos p = origin.offset(x, 1, z);
                BlockState s = level.getBlockState(p);
                if (OvergrownLayerData.isCorruptedCell(level, p, s) || underFallenLog(level, p)) {
                    done++;
                } else {
                    left.append(' ').append(x).append(',').append(z).append('=').append(s.getBlock().getDescriptionId())
                            .append(z >= 4 ? "(林冠の下)" : x == 3 && z == 2 ? "(昆布)" : "").append('[').append(column(level, p)).append(']');
                }
            }
        }
        clear(level, origin);
        if (done < 25) {
            helper.fail("水底 25 マスのうち汚染されたのは " + done + "。残り:" + left);
        } else {
            helper.succeed();
        }
    }

    /** 深部浸食の入口: 覆った砂の地表は「済み」に数える(覆っていない砂は「まだ」)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredSurfaceCountsAsDone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, SKY, 3));
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos sand = origin.above();
        level.setBlock(sand, Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        boolean bare = OvergrownBiomeCreep.surfaceDoneForTest(level, sand.getX(), sand.getZ());
        OvergrownLayerData.cover(level, sand);
        boolean covered = OvergrownBiomeCreep.surfaceDoneForTest(level, sand.getX(), sand.getZ());
        OvergrownLayerData.uncover(level, sand);
        level.setBlock(sand, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (bare) {
            helper.fail("覆っていない砂の地表を「済み」と数えた");
        } else if (!covered) {
            helper.fail("覆った砂の地表を「まだ」と数えた(深部浸食が始まらない)");
        } else {
            helper.succeed();
        }
    }

    /**
     * 真上に倒木が横たわったマス(11-23 揺れるテストの調べ)。倒木(FloorGrowth)は汚染した床から 2〜3 マス横へ倒れ、まだ素の隣の床に載ることがある。
     * 載った床は表に出ていないので列ごとの浸食の的にしない(深部浸食が拾う。幹の根元と同じ)。倒木の起点は座標で決まるので、テスト場の置かれた所しだいで
     * 2〜3 マスが一直線に残って落ちていた(新しいワールドで 40 回中 2 回)。
     */
    static boolean underFallenLog(ServerLevel level, BlockPos floor) {
        return level.getBlockState(floor.above()).is(ModBlocks.FALLEN_LOG.get());
    }

    /** 揺れの調べ(11-23): 水底の 1 つ上から林冠の少し上までの列の中身(空気・水は略す)。 */
    private static String column(ServerLevel level, BlockPos floor) {
        StringBuilder b = new StringBuilder();
        for (int y = floor.getY() + 1; y <= floor.getY() + DEPTH + 12; y++) {
            BlockState s = level.getBlockState(new BlockPos(floor.getX(), y, floor.getZ()));
            if (s.isAir() || s.is(Blocks.WATER)) continue;
            b.append(' ').append(y - floor.getY()).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
        }
        return b.toString();
    }

    private static void build(ServerLevel level, BlockPos origin) {
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                level.setBlock(origin.offset(x, 1, z), wall ? Blocks.STONE.defaultBlockState()
                        : FLOORS[(x + z) % FLOORS.length].defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 2; y <= DEPTH + 1; y++) {
                    level.setBlock(origin.offset(x, y, z), wall ? Blocks.GLASS.defaultBlockState() : water, Block.UPDATE_CLIENTS);
                }
                if (!wall && z >= 4) {
                    // 水面の 2 段上に梢(過繁茂の葉。汚染済みの立方体)
                    level.setBlock(origin.offset(x, DEPTH + 3, z), ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        // 胞子昆布の柱(水を含む・汚染済み。列の頂になる)
        for (int y = 2; y <= DEPTH; y++) {
            level.setBlock(origin.offset(3, y, 2), ModBlocks.SPORE_KELP_STALK.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void clear(ServerLevel level, BlockPos origin) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = DEPTH + 4; y >= 0; y--) {
                    BlockPos p = origin.offset(x, y, z);
                    OvergrownLayerData.uncover(level, p);
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    private UnderwaterCorruptionGameTests() {
    }
}
