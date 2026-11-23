package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CoveredSandToGrass;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
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
 * 覆った砂・砂利(2026-11-01 ユーザー報告「海底で砂・砂利が覆われないことが多発」と仕様「時間がたつと覆われた草ブロックに」)。
 * テスト場の {@value #SKY} 段上の空中で試し、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CoveredSandGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterCoveredSandGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(CoveredSandGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 44;

    /**
     * 石の上の砂を 2 つ(片方だけ覆う)。下の石を更新ありで消すと、覆っていない砂は落ち、覆った砂は落ちずに覆われたまま残る
     * (前は落ちて、記録は元の場所に残り捨てられ、落ちた先の砂は素のままだった)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void coveredSandDoesNotFall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, SKY, 3));
        BlockPos coveredSand = base.above();
        BlockPos bareBase = base.east(2);
        BlockPos bareSand = bareBase.above();
        level.setBlock(base, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(bareBase, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(coveredSand, Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(bareSand, Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        OvergrownLayerData.cover(level, coveredSand);
        level.setBlock(base, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(bareBase, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(10, () -> {
            boolean coveredStayed = level.getBlockState(coveredSand).is(Blocks.SAND) && OvergrownLayerData.isCovered(level, coveredSand);
            boolean bareFell = !level.getBlockState(bareSand).is(Blocks.SAND);
            for (int y = -20; y <= 1; y++) {
                for (BlockPos p : new BlockPos[]{coveredSand.above(y), bareSand.above(y)}) {
                    OvergrownLayerData.uncover(level, p);
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (!bareFell) {
                helper.fail("覆っていない砂が落ちなかった(落下の更新が起きていない。テストの作りの誤り)");
            } else if (!coveredStayed) {
                helper.fail("覆った砂が落ちた / 覆いの記録が消えた");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * 覆った砂(上が水)・砂利(上が空気)は時刻が来ると過繁茂の草ブロックになり、覆いの記録は消える。上が石の砂利は待つ。
     * 砂岩(上が空気。11-02 ユーザー指定)も草ブロックになり、加工品の切り出した砂岩は時刻が書かれない(仮決め)。
     * 覆ったときの時刻は settleMinSeconds〜settleMaxSeconds の間に書かれる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredSandTurnsToGrass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sand = helper.absolutePos(new BlockPos(1, SKY, 3));
        BlockPos gravel = sand.east(2);
        BlockPos blocked = sand.east(4);
        BlockPos sandstone = sand.south(2);
        BlockPos cut = sandstone.east(2);
        level.setBlock(sandstone, Blocks.SANDSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(cut, Blocks.CUT_SANDSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(sand, Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(sand.above(), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(gravel, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(blocked, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(blocked.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        long now = level.getGameTime();
        for (BlockPos p : new BlockPos[]{sand, gravel, blocked, sandstone, cut}) {
            OvergrownLayerData.cover(level, p);
        }
        long due = CoveredSandToGrass.dueTick(level, sand);
        var design = DesignData.cover();
        boolean dueInWindow = due >= now + Math.round(design.settleMinSeconds() * 20) && due <= now + Math.round(design.settleMaxSeconds() * 20) + 1;
        boolean cutNotScheduled = CoveredSandToGrass.dueTick(level, cut) == Long.MIN_VALUE;
        boolean sandstoneScheduled = CoveredSandToGrass.dueTick(level, sandstone) != Long.MIN_VALUE;
        for (BlockPos p : new BlockPos[]{sand, gravel, blocked, sandstone}) {
            CoveredSandToGrass.schedule(level, p, now); // 時刻を今にする
        }
        CoveredSandToGrass.sweep(level);
        var grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get();
        boolean sandDone = level.getBlockState(sand).is(grass) && !OvergrownLayerData.isCovered(level, sand)
                && CoveredSandToGrass.dueTick(level, sand) == Long.MIN_VALUE;
        boolean gravelDone = level.getBlockState(gravel).is(grass);
        boolean sandstoneDone = sandstoneScheduled && level.getBlockState(sandstone).is(grass) && !OvergrownLayerData.isCovered(level, sandstone);
        boolean blockedWaits = level.getBlockState(blocked).is(Blocks.GRAVEL) && OvergrownLayerData.isCovered(level, blocked)
                && CoveredSandToGrass.dueTick(level, blocked) != Long.MIN_VALUE;
        for (BlockPos p : new BlockPos[]{sand, gravel, blocked, sand.above(), blocked.above(), sandstone, cut}) {
            OvergrownLayerData.uncover(level, p);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (!dueInWindow) {
            helper.fail("覆ったときの時刻が settleMinSeconds〜settleMaxSeconds の外: " + (due - now) + " tick 先");
        } else if (!sandDone) {
            helper.fail("水の下の覆った砂が草ブロックにならない / 記録が残った");
        } else if (!gravelDone) {
            helper.fail("覆った砂利が草ブロックにならない");
        } else if (!sandstoneDone) {
            helper.fail("覆った砂岩が草ブロックにならない");
        } else if (!cutNotScheduled) {
            helper.fail("切り出した砂岩に時刻が書かれた(加工品は入れない)");
        } else if (!blockedWaits) {
            helper.fail("上が石の砂利が置き換わった / 待つ記録が消えた");
        } else {
            helper.succeed();
        }
    }

    /**
     * 過繁茂の草ブロックは上がふさがると過繁茂の土になる(11-02 ユーザー指定。バニラの草ブロックと同じく上のブロックの不透明度で見る)。
     * 上が石・水 1 マスなら土、上がガラス・草なら草ブロックのまま。上が石の土は何度ランダムティックしても草にならない(往復しない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void overgrownGrassTurnsToDirtWhenCovered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get();
        var dirt = ModBlocks.OVERGROWN_DIRT.get();
        BlockPos origin = helper.absolutePos(new BlockPos(1, SKY, 1));
        BlockState[] aboves = {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.GLASS.defaultBlockState(), Blocks.GRASS.defaultBlockState()};
        boolean[] expectDirt = {true, true, false, false};
        String[] names = {"石", "水", "ガラス", "草"};
        BlockPos coveredDirt = origin.south(3);
        String failure = null;
        for (int i = 0; i < aboves.length; i++) {
            BlockPos p = origin.east(i * 2);
            level.setBlock(p, grass.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(p.above(), aboves[i], Block.UPDATE_CLIENTS);
        }
        level.setBlock(coveredDirt, dirt.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(coveredDirt.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int i = 0; i < aboves.length; i++) {
            BlockPos p = origin.east(i * 2);
            level.getBlockState(p).randomTick(level, p, level.random);
            boolean isDirt = level.getBlockState(p).is(dirt);
            boolean isGrass = level.getBlockState(p).is(grass);
            if (failure == null && (expectDirt[i] ? !isDirt : !isGrass)) {
                failure = "上が" + names[i] + "の草ブロックが" + (expectDirt[i] ? "土にならない" : "草ブロックのままでない") + ": " + level.getBlockState(p);
            }
        }
        for (int n = 0; n < 200; n++) {
            level.getBlockState(coveredDirt).randomTick(level, coveredDirt, level.random);
        }
        if (failure == null && !level.getBlockState(coveredDirt).is(dirt)) {
            failure = "上が石の土が草ブロックに戻った";
        }
        for (int dx = -1; dx <= 8; dx++) {
            for (int dz = -1; dz <= 4; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        if (failure != null) {
            helper.fail(failure);
        } else {
            helper.succeed();
        }
    }

    private CoveredSandGameTests() {
    }
}
