package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.CoverCreepChain;
import com.yayuy.overgrownvegetation.corruption.CoverCreepData;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 這い上がる苔の連鎖(2026-11-03 ユーザー指定): (a) 覆われた・汚染ブロックになったマスの上と横の<b>既にある</b>建築ブロックに記録が付く、
 * (b) 最終段の時間が過ぎると覆いになり、その上と横へまた記録が付く。時間は記録の時刻をずらして進める({@link CoverCreepData#ageForTest})。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CoverCreepChainGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CoverCreepChainGameTests.class);
        }

        private Registration() {
        }
    }

    /** (a) 覆った板材の上・横の板材に記録(時刻は今・向きは覆ったマスの側)。下・地形の土には付かない。汚染ブロックになったときも同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void creepSpreadsToExistingNeighbours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos above = a.above(), east = a.east(), below = a.below(), west = a.west();
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(4, 3, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(2, 3, 3), Blocks.DIRT);
        // 汚染ブロックの元: 空中の overgrown_block の上の板材
        BlockPos ground = helper.absolutePos(new BlockPos(1, 4, 1));
        helper.setBlock(new BlockPos(1, 4, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 5, 1), Blocks.OAK_PLANKS);
        try {
            if (!OvergrownLayerData.cover(level, a)) {
                helper.fail("板材を覆えない");
                return;
            }
            long now = level.getGameTime();
            long up = CoverCreepData.get(level, above), side = CoverCreepData.get(level, east);
            if (up == Long.MIN_VALUE || CoverCreepData.sides(up) != 1 << Direction.DOWN.ordinal() || CoverCreepData.startTick(up) != now) {
                helper.fail("上の板材の記録: " + up);
                return;
            }
            if (side == Long.MIN_VALUE || CoverCreepData.sides(side) != 1 << Direction.WEST.ordinal() || CoverCreepData.startTick(side) != now) {
                helper.fail("横の板材の記録: " + side);
                return;
            }
            if (CoverCreepData.get(level, below) != Long.MIN_VALUE || CoverCreepData.get(level, west) != Long.MIN_VALUE) {
                helper.fail("下の板材か横の土に記録が付いた");
                return;
            }
            CorruptionRegistry.placeCorrupted(level, ground, Blocks.STONE.defaultBlockState(),
                    ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            long onGround = CoverCreepData.get(level, ground.above());
            if (onGround == Long.MIN_VALUE || CoverCreepData.sides(onGround) != 1 << Direction.DOWN.ordinal()) {
                helper.fail("汚染ブロックの上の板材の記録: " + onGround);
                return;
            }
            helper.succeed();
        } finally {
            OvergrownLayerData.uncover(level, a);
            helper.setBlock(new BlockPos(3, 4, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(4, 3, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(1, 5, 1), Blocks.AIR);
        }
    }

    /**
     * 剥がしたマスはしばらく付かない(2026-11-03 ユーザー指定): 剥がすとそのマスの記録が消え、猶予の間は隣が覆われても・置いても記録が付かない。
     * 猶予({@link CoverCreepData#PEEL_GRACE_TICKS})が過ぎれば付く。
     */
    @GameTest(template = "gametest_empty7")
    public static void peeledCellIsSkippedForAWhile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos b = a.above(), side = a.east();
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(4, 4, 3), Blocks.OAK_PLANKS);
        try {
            OvergrownLayerData.cover(level, a);
            OvergrownLayerData.cover(level, b);
            OvergrownLayerData.uncover(level, b);
            if (CoverCreepData.get(level, b) != Long.MIN_VALUE) {
                helper.fail("剥がしたマスに記録が残った");
                return;
            }
            // 隣(b の東)を覆う → b は西に覆いを持つが、猶予の間は付かない。下の a からの連鎖も、置いたときも付かない
            OvergrownLayerData.cover(level, b.east());
            CoverCreepData.spreadFrom(level, a);
            boolean placedB = CoverCreepData.placed(level, b, level.getBlockState(b));
            if (CoverCreepData.get(level, b) != Long.MIN_VALUE || placedB) {
                helper.fail("猶予の間に記録が付いた(置いたとき " + placedB + ")");
                return;
            }
            // 猶予が過ぎた(剥がした時刻を昔にする)→ 付く
            var chunk = level.getChunkAt(b);
            chunk.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.COVER_PEELED.get())
                    .put(b.asLong(), level.getGameTime() - CoverCreepData.PEEL_GRACE_TICKS - 1);
            CoverCreepData.spreadFrom(level, a);
            long value = CoverCreepData.get(level, b);
            if (value == Long.MIN_VALUE || CoverCreepData.sides(value) != 1 << Direction.DOWN.ordinal()) {
                helper.fail("猶予が過ぎても付かない: " + value);
                return;
            }
            helper.succeed();
        } finally {
            OvergrownLayerData.uncover(level, a);
            OvergrownLayerData.uncover(level, b.east());
            helper.setBlock(new BlockPos(3, 4, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(4, 4, 3), Blocks.AIR);
        }
    }

    /**
     * ガラス板・色付きガラス板は置き換えず記録で覆い(色付きは 11-03 から建築タブ扱い)、テラコッタは overgrown_dirt にならない(覆いか同じ形の過繁茂ブロック)。
     * 透けるブロックの覆いは面の下 5〜6 割(描画の割合の決まりを確かめる)。
     */
    @GameTest(template = "gametest_empty7")
    public static void glassPanesAndTerracottaAreCovered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.level.block.Block[] panes = {Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS_PANE, Blocks.IRON_BARS};
        for (int i = 0; i < panes.length; i++) {
            BlockPos rel = new BlockPos(1 + 2 * i, 3, 1);
            helper.setBlock(rel, panes[i]);
            BlockPos p = helper.absolutePos(rel);
            var before = level.getBlockState(p);
            var after = CorruptionRegistry.corrupt(level, p, before, level.getRandom());
            boolean covered = OvergrownLayerData.isCovered(level, p);
            OvergrownLayerData.uncover(level, p);
            if (after != before || !covered) {
                helper.fail(panes[i] + ": 置き換え " + after + "・覆い " + covered);
                return;
            }
        }
        net.minecraft.world.level.block.Block[] terracotta = {Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.WHITE_STAINED_GLASS};
        for (int i = 0; i < terracotta.length; i++) {
            BlockPos rel = new BlockPos(1 + i, 3, 4);
            helper.setBlock(rel, terracotta[i]);
            BlockPos p = helper.absolutePos(rel);
            var before = level.getBlockState(p);
            if (!CorruptionRegistry.coversInPlace(before)) {
                helper.fail(terracotta[i] + " が建築タブ扱い(記録で覆う一覧)に無い");
                return;
            }
            var after = CorruptionRegistry.corrupt(level, p, before, level.getRandom());
            boolean covered = OvergrownLayerData.isCovered(level, p);
            OvergrownLayerData.uncover(level, p);
            if (after.is(ModBlocks.OVERGROWN_DIRT.get()) || after == before && !covered) {
                helper.fail(terracotta[i] + ": 置き換え " + after + "・覆い " + covered);
                return;
            }
        }
        for (int x = -20; x <= 20; x += 3) {
            float h = com.yayuy.overgrownvegetation.corruption.CoverLook.seeThroughMossHeight(x, 64, x * 7);
            if (h < 0.5F - 1e-4F || h > 0.6F + 1e-4F) {
                helper.fail("透けるブロックの苔の高さが 5〜6 割でない: " + h);
                return;
            }
            float h2 = com.yayuy.overgrownvegetation.corruption.CoverLook.seeThroughMossHeight(x, 65, x * 7, 1);
            float h3 = com.yayuy.overgrownvegetation.corruption.CoverLook.seeThroughMossHeight(x, 66, x * 7, 2);
            if (h2 < 0.1F - 1e-4F || h2 > 0.2F + 1e-4F || Math.abs(h3 - 0.1F) > 1e-4F) {
                helper.fail("重なった透けるブロックの苔の高さ: 2 段目 " + h2 + "・3 段目 " + h3);
                return;
            }
        }
        if (Math.abs(com.yayuy.overgrownvegetation.corruption.CoverLook.clipTop(0.0F, 1.0F, 0.55F) - 0.55F) > 1e-5F) {
            helper.fail("clipTop");
            return;
        }
        helper.succeed();
    }

    /**
     * (b) 段階が尽きた板材は覆いになり、その上の板材へ連鎖する。覆いにできない土(覆いの横に置いた)は最終段で止まる。
     * {@code /ov corruption false} の間は進まない。
     */
    @GameTest(template = "gametest_empty7")
    public static void creepTurnsIntoCoverAndChains(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos b = a.above(), c = b.above(), dirt = a.east();
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(3, 5, 3), Blocks.OAK_PLANKS);
        long full = CoverCreepChain.durationTicks();
        var server = level.getServer();
        try {
            OvergrownLayerData.cover(level, a);
            helper.setBlock(new BlockPos(4, 3, 3), Blocks.DIRT);
            if (!CoverCreepData.placed(level, dirt, level.getBlockState(dirt))) {
                helper.fail("覆いの横に置いた土に記録が付かない");
                return;
            }
            // 尽きる前は覆わない
            CoverCreepData.ageForTest(level, b, full - 40);
            CoverCreepChain.sweep(level);
            if (OvergrownLayerData.isCovered(level, b)) {
                helper.fail("段階が尽きる前に覆った");
                return;
            }
            CoverCreepData.ageForTest(level, b, 41);
            CoverCreepData.ageForTest(level, dirt, full + 1);
            // 汚染がオフの間は進まない
            String failure = null;
            try {
                CorruptionSwitch.set(server, false);
                CoverCreepChain.sweep(level);
                if (OvergrownLayerData.isCovered(level, b)) {
                    failure = "汚染がオフなのに覆った";
                }
            } finally {
                CorruptionSwitch.set(server, true);
            }
            if (failure != null) {
                helper.fail(failure);
                return;
            }
            CoverCreepChain.sweep(level);
            long onC = CoverCreepData.get(level, c);
            if (!OvergrownLayerData.isCovered(level, b) || CoverCreepData.get(level, b) != Long.MIN_VALUE) {
                helper.fail("段階が尽きた板材が覆われない(記録 " + CoverCreepData.get(level, b) + ")");
                return;
            }
            if (onC == Long.MIN_VALUE || CoverCreepData.sides(onC) != 1 << Direction.DOWN.ordinal()
                    || CoverCreepData.startTick(onC) != level.getGameTime()) {
                helper.fail("覆った板材の上へ連鎖しない: " + onC);
                return;
            }
            if (OvergrownLayerData.isCovered(level, dirt) || CoverCreepData.get(level, dirt) == Long.MIN_VALUE) {
                helper.fail("覆いにできない土: 覆われた " + OvergrownLayerData.isCovered(level, dirt) + "・記録 " + CoverCreepData.get(level, dirt));
                return;
            }
            helper.succeed();
        } finally {
            OvergrownLayerData.uncover(level, a);
            OvergrownLayerData.uncover(level, b);
            OvergrownLayerData.uncover(level, c);
            helper.setBlock(new BlockPos(3, 5, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(3, 4, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(4, 3, 3), Blocks.AIR);
        }
    }
}
