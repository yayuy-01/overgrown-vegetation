package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.WallFormGrowthBlock;
import com.yayuy.overgrownvegetation.corruption.WallPlantGrowth;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 壁の植物を場所で選ぶ(2026-11-08、{@link WallPlantGrowth})。テスト場の中に石の箱(屋根あり = 空の光 0 = 洞窟)を作り、
 * 壁の前のマスで 400 回引いて、出たものの種類を数える。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WallPlantGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterWallPlantGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(WallPlantGameTests.class);
        }

        private Registration() {
        }
    }

    /** 壁の前のマス(壁は南 z+1、植物は北を向く)。 */
    private static final BlockPos AT = new BlockPos(3, 4, 3);

    /** x 1〜5・y 2〜7・z 1〜5 の石の殻(中は空気)。壁 = AT の南。ceiling = AT の真上も石。 */
    private static void box(GameTestHelper helper, boolean ceiling) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 5; x++) {
            for (int y = 2; y <= 7; y++) {
                for (int z = 1; z <= 5; z++) {
                    boolean shell = x == 1 || x == 5 || y == 2 || y == 7 || z == 1 || z == 5;
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)),
                            shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        level.setBlock(helper.absolutePos(AT.south()), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (ceiling) {
            level.setBlock(helper.absolutePos(AT.above()), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** 400 回引いて、id(form つき)ごとの回数。何も置かないは "none"。 */
    private static Map<String, Integer> roll(GameTestHelper helper) {
        Map<String, Integer> n = new HashMap<>();
        RandomSource r = RandomSource.create(11L);
        BlockPos at = helper.absolutePos(AT);
        for (int i = 0; i < 400; i++) {
            BlockState s = WallPlantGrowth.pick(helper.getLevel(), at, r, Direction.NORTH);
            String key = s == null ? "none" : s.getBlock().getDescriptionId()
                    + (s.hasProperty(WallFormGrowthBlock.FORM) ? "#" + s.getValue(WallFormGrowthBlock.FORM) : "");
            n.merge(key, 1, Integer::sum);
        }
        return n;
    }

    private static String id(Block b) {
        return b.getDescriptionId();
    }

    /** 屋根の下の暗い壁: 灰緑の垂れ苔・胞子嚢・菌の棚だけ。どれも出る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void darkWallUnderCeilingGetsCavePlants(GameTestHelper helper) {
        box(helper, true);
        helper.runAfterDelay(20, () -> {
            Map<String, Integer> n = roll(helper);
            String moss = id(ModBlocks.HANGING_MOSS.get()) + "#2", sacs = id(ModBlocks.WALL_SPORE_SACS.get()), bracket = id(ModBlocks.WALL_BRACKET_FUNGUS.get());
            for (String k : n.keySet()) {
                if (!k.equals(moss) && !k.equals(sacs) && !k.equals(bracket)) {
                    helper.fail("洞窟に洞窟以外の植物: " + k + " " + n);
                    return;
                }
            }
            if (!n.containsKey(moss) || !n.containsKey(sacs) || !n.containsKey(bracket)) {
                helper.fail("洞窟の 3 種のどれかが出ない: " + n);
                return;
            }
            helper.succeed();
        });
    }

    /** 真上が空気の暗い壁: 垂れ苔は出ず(何も置かない)、胞子嚢と菌の棚は出る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void hangingMossNeedsSolidAbove(GameTestHelper helper) {
        box(helper, false);
        helper.runAfterDelay(20, () -> {
            Map<String, Integer> n = roll(helper);
            for (String k : n.keySet()) {
                if (k.startsWith(id(ModBlocks.HANGING_MOSS.get()))) {
                    helper.fail("真上が空気なのに垂れ苔: " + n);
                    return;
                }
            }
            if (!n.containsKey("none") || !n.containsKey(id(ModBlocks.WALL_SPORE_SACS.get()))) {
                helper.fail("置かない回・胞子嚢が無い: " + n);
                return;
            }
            helper.succeed();
        });
    }

    /** 水のそばの壁: 長い垂れ苔・壁シダ・大きく垂れる壁シダだけ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void wetWallGetsFernsAndLongMoss(GameTestHelper helper) {
        box(helper, true);
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(2, 3, 2)), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(20, () -> {
            Map<String, Integer> n = roll(helper);
            String moss = id(ModBlocks.HANGING_MOSS.get()) + "#0", fern = id(ModBlocks.WALL_FERN.get()), large = id(ModBlocks.WALL_FERN_LARGE.get());
            for (String k : n.keySet()) {
                if (!k.equals(moss) && !k.equals(fern) && !k.equals(large)) {
                    helper.fail("湿った壁に別の植物: " + k + " " + n);
                    return;
                }
            }
            if (!n.containsKey(fern) || !n.containsKey(large)) {
                helper.fail("壁シダのどちらかが出ない: " + n);
                return;
            }
            helper.succeed();
        });
    }

    /** 手前の床にイワヒバがあり真下が床なら、床から続く最下段の絵(form 1)を選ぶ条件が立つ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void lycopsidFloorInFrontIsDetected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = new BlockPos(3, 2, 3);
        level.setBlock(helper.absolutePos(at.south()), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(helper.absolutePos(at.north()), ModBlocks.SELAGINELLA.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        boolean with = WallPlantGrowth.lycopsidFloorInFront(level, helper.absolutePos(at), Direction.NORTH);
        level.setBlock(helper.absolutePos(at.north()), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        boolean without = WallPlantGrowth.lycopsidFloorInFront(level, helper.absolutePos(at), Direction.NORTH);
        if (!with || without) {
            helper.fail("床の小葉類の判定: あり " + with + " / なし " + without);
            return;
        }
        helper.succeed();
    }
}
