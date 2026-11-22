package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.AerialRootBlock;
import com.yayuy.overgrownvegetation.block.SporeSacBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.TreeSideGrowth;
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

import java.util.List;

/**
 * 側面・底面に生えるもの(2026-11-01): S3 が垂れて地面で根付いた柱になる
 * (届かない根は先で止まる・柱を抜くと上が先に戻る)/ S6 の小さな袋がランダムティックで大きく育ち、大のまま止まる。
 * テスト場(gametest_empty7)は床が相対 y=1、天井のバリアが y=8 あたり。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SideGrowthGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SideGrowthGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void aerialRootHangsAndRootsIntoPillar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 林冠の代わりの原木(y=6)。床は y=1 なので、y=5 から下へ 4 段の空気。
        helper.setBlock(new BlockPos(2, 6, 2), ModBlocks.OVERGROWN_LOG.get());
        helper.setBlock(new BlockPos(5, 6, 5), ModBlocks.OVERGROWN_LOG.get());
        int toGround = TreeSideGrowth.hangRoot(level, helper.absolutePos(new BlockPos(2, 5, 2)), 2, true, AerialRootBlock.Kind.BUNDLE);
        int partWay = TreeSideGrowth.hangRoot(level, helper.absolutePos(new BlockPos(5, 5, 5)), 2, false, AerialRootBlock.Kind.SINGLE);
        if (toGround != 4 || partWay != 2) {
            helper.fail("段の数が違う: 地面まで " + toGround + "(4 のはず)・途中 " + partWay + "(2 のはず)");
            return;
        }
        for (int y = 3; y <= 5; y++) {
            BlockState s = helper.getBlockState(new BlockPos(2, y, 2));
            if (!s.is(ModBlocks.AERIAL_ROOT.get()) || s.getValue(AerialRootBlock.TIP)) {
                helper.fail("地面まで届く根の y=" + y + " が中の気根でない: " + s);
                return;
            }
        }
        BlockState pillar = helper.getBlockState(new BlockPos(2, 2, 2));
        if (!pillar.is(ModBlocks.AERIAL_ROOT_PILLAR.get()) || pillar.getValue(AerialRootBlock.KIND) != AerialRootBlock.Kind.BUNDLE) {
            helper.fail("地面の上が根付いた柱でない: " + pillar);
            return;
        }
        BlockState mid = helper.getBlockState(new BlockPos(5, 5, 5));
        BlockState tip = helper.getBlockState(new BlockPos(5, 4, 5));
        if (!mid.is(ModBlocks.AERIAL_ROOT.get()) || mid.getValue(AerialRootBlock.TIP) || !tip.is(ModBlocks.AERIAL_ROOT.get())
                || !tip.getValue(AerialRootBlock.TIP) || !helper.getBlockState(new BlockPos(5, 3, 5)).isAir()) {
            helper.fail("途中で止まる根が 中 → 先 → 空気 でない: " + mid + " / " + tip);
            return;
        }
        // 柱を抜くと、真上の根が先に戻る
        level.setBlock(helper.absolutePos(new BlockPos(2, 2, 2)), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockState above = helper.getBlockState(new BlockPos(2, 3, 2));
        if (!above.is(ModBlocks.AERIAL_ROOT.get()) || !above.getValue(AerialRootBlock.TIP)) {
            helper.fail("柱を抜いても真上が先にならない: " + above);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void sporeSacGrowsFromSmallToLarge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 5, 3), ModBlocks.OVERGROWN_LOG.get());
        BlockPos rel = new BlockPos(3, 4, 3);
        BlockPos abs = helper.absolutePos(rel);
        if (!TreeSideGrowth.placeSac(level, abs, SporeSacBlock.Stage.SMALL)) {
            helper.fail("袋を吊れない");
            return;
        }
        RandomSource random = RandomSource.create(11);
        int ticks = 0;
        while (ticks < 100 && helper.getBlockState(rel).getValue(SporeSacBlock.STAGE) == SporeSacBlock.Stage.SMALL) {
            BlockState s = level.getBlockState(abs);
            if (!s.isRandomlyTicking()) {
                helper.fail("小さな袋がランダムティックを受けない");
                return;
            }
            s.randomTick(level, abs, random);
            ticks++;
        }
        BlockState grown = helper.getBlockState(rel);
        if (grown.getValue(SporeSacBlock.STAGE) != SporeSacBlock.Stage.LARGE) {
            helper.fail("100 回のランダムティックで大きくならない: " + grown);
            return;
        }
        if (grown.isRandomlyTicking()) {
            helper.fail("大きな袋がまだランダムティックを受ける(大のまま止まるはず)");
            return;
        }
        // 支え(上の原木)を抜くと落ちる
        helper.setBlock(new BlockPos(3, 5, 3), Blocks.AIR);
        if (!helper.getBlockState(rel).isAir()) {
            helper.fail("支えを抜いても袋が残った");
            return;
        }
        OvergrownVegetation.LOGGER.info("胞子嚢の袋: {} 回目のランダムティックで大きくなった(確率 {})", ticks, SporeSacBlock.GROW_CHANCE);
        helper.succeed();
    }
}
