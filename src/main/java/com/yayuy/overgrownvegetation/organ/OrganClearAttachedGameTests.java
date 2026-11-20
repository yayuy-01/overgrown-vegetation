package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 器官が木を払うとき、その木に付いていた草・垂れ根・苔も一緒に払う(2026-11-20 ユーザー「周囲の木にあった草とか垂れ根とかがめっちゃ浮いてる」)。
 * 円の外へはみ出した葉の下の垂れ根・幹の横の蔦が消え、残る木に付いた物と林床の草は残る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OrganClearAttachedGameTests {
    private OrganClearAttachedGameTests() {
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OrganClearAttachedGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty24", batch = "organ_clear_attached", timeoutTicks = 100)
    public static void clearingTakesPlantsAttachedToTheTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        BlockPos c = helper.absolutePos(new BlockPos(12, 1, 12));
        int gy = c.getY() - 1;
        ForestDirector dir = ForestDirector.get(level);
        PhaseData pd = PhaseData.get(level);
        double budgetBefore = dir.available(level);
        long pointsBefore = pd.getPoints();
        int id = -1, span = 0;
        try {
            ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 7, 0, c.getX(), c.getZ());
            id = o.id;
            OrganBlueprint bp = OrganWorld.blueprintNow(o);
            int r = OrganClearing.radius(bp, true);
            span = r + 10;
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    level.setBlock(new BlockPos(c.getX() + dx, gy, c.getZ() + dz), ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), 2);
                    for (int y = gy + 1; y <= gy + 30; y++) level.setBlock(new BlockPos(c.getX() + dx, y, c.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            BlockState log = ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            BlockState hang = ModBlocks.DANGLING_ROOTS.get().defaultBlockState();
            BlockState moss = ModBlocks.HANGING_MOSS.get().defaultBlockState();
            // A: 円の縁の内側の木。葉が円の外へ 4 マスはみ出し、その先の下に垂れ根が 2 段、幹の外側の横に苔
            BlockPos a = c.offset(r - 1, 0, 0);
            for (int y = 1; y <= 8; y++) level.setBlock(a.atY(gy + y), log, 2);
            for (int i = 1; i <= 4; i++) level.setBlock(a.atY(gy + 8).offset(i, 0, 0), leaf, 2);
            BlockPos h1 = a.atY(gy + 7).offset(4, 0, 0), h2 = h1.below(), side = a.atY(gy + 5).offset(1, 0, 0);
            level.setBlock(h1, hang, 2);
            level.setBlock(h2, hang, 2);
            level.setBlock(side, moss, 2);
            // 幹の根元のわきの林床の草(残る)
            BlockPos grass = a.atY(gy + 1).offset(1, 0, 0);
            level.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), 2);
            // C: 円の外の木(残る)。その葉の下の垂れ根も残る
            BlockPos f = c.offset(0, 0, -(r + 4));
            for (int y = 1; y <= 8; y++) level.setBlock(f.atY(gy + y), log, 2);
            level.setBlock(f.atY(gy + 8).offset(0, 0, -1), leaf, 2);
            BlockPos keep = f.atY(gy + 7).offset(0, 0, -1);
            level.setBlock(keep, hang, 2);

            helper.assertTrue(OrganClearing.isAttached(level.getBlockState(h1)), "垂れ根を「木に付いた物」と見ていない: " + level.getBlockState(h1));
            OrganClearing.clearNow(level, o, bp);
            helper.assertTrue(o.cleared, "払い終わらない");
            helper.assertTrue(level.getBlockState(a.atY(gy + 4)).isAir(), "円の中の木の幹が残る");
            helper.assertTrue(level.getBlockState(h1).isAir() && level.getBlockState(h2).isAir(), "払った葉の下の垂れ根が浮いて残る: " + level.getBlockState(h1) + " / " + level.getBlockState(h2));
            helper.assertTrue(level.getBlockState(side).isAir(), "払った幹の横の苔が浮いて残る: " + level.getBlockState(side));
            helper.assertTrue(level.getBlockState(grass).is(Blocks.SHORT_GRASS), "林床の草まで払った");
            helper.assertTrue(level.getBlockState(f.atY(gy + 4)).is(ModBlocks.OVERGROWN_LOG.get()), "円の外の木まで払った");
            helper.assertTrue(!level.getBlockState(keep).isAir(), "残る木に付いた垂れ根まで払った");
            helper.succeed();
        } finally {
            if (id >= 0) data.remove(id);
            dir.setBudget(level, budgetBefore);
            if (pd.getPoints() < pointsBefore) pd.addPointsUnscaledForTests(level, pointsBefore - pd.getPoints());
            if (span > 0) {
                for (int dx = -span; dx <= span; dx++) {
                    for (int dz = -span; dz <= span; dz++) {
                        for (int y = gy - 12; y <= gy + 40; y++) {
                            BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                            if (y > gy || !level.getBlockState(p).isAir()) level.setBlock(p, y > gy ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }
}
