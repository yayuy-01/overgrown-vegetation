package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 器官のブロックを汚染が置き換えない・芯が壊れたら持ち主の器官だけが枯れる(2026-11-20 建物用の器官を MOD の構造物で試した調べ:
 * 深くへの汚染 {@code OvergrownBiomeCreep.deepenChunk} と建材の汚染 {@code BuildCorrosion.corrodeAt} が芯を過繁茂ブロックに置き換え、
 * 芽吹いたばかりの器官が枯れていた。どちらの道も {@link CorruptionRegistry#canCorrupt} を通る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OrganCorruptionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OrganCorruptionGameTests.class);
        }

        private Registration() {
        }
    }

    private OrganCorruptionGameTests() {
    }

    /** 器官のブロック(枯れた状態も含む全部の状態)は汚染の対象にならない。同じ場所の石は対象になる(場所のせいで通っていないことの確かめ)。 */
    @GameTest(template = "gametest_empty7", batch = "organ_corruption", timeoutTicks = 40)
    public static void corruptionSkipsOrganBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos p = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.assertTrue(CorruptionRegistry.canCorrupt(level, p, Blocks.STONE.defaultBlockState()), "この場所では石も汚染できない(確かめにならない)");
        List<Block> blocks = List.of(OrganBlocks.ORGAN_CORE.get(), OrganBlocks.ORGAN_SAC.get(), OrganBlocks.ORGAN_STALK.get(), OrganBlocks.ORGAN_EGG_MASS.get(),
                OrganBlocks.ORGAN_VEIN.get(), OrganBlocks.ORGAN_VINE.get(), OrganBlocks.ROOT_CRYSTAL_BLOCK.get(), OrganBlocks.ROOT_CRYSTAL_CLUSTER.get());
        for (Block b : blocks) {
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                helper.assertTrue(!CorruptionRegistry.canCorrupt(level, p, s), "器官のブロックを汚染できる: " + s);
            }
        }
        helper.succeed();
    }

    /**
     * 重なった 2 つの器官で、A の芯を壊すと A だけが枯れる。A の芯は B の真ん中の真上に置く
     * (前は箱にかかる器官のうち横にいちばん近い物 = B を枯らしていた)。
     */
    @GameTest(template = "gametest_empty7", batch = "organ_corruption", timeoutTicks = 40)
    public static void brokenCoreWithersItsOwnerOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        BlockPos base = helper.absolutePos(new BlockPos(1, 2, 3));
        ForestOrgans.Organ a = organ(level, data, base, new long[]{
                OrganBlueprint.pack(0, 0, 0, OrganBlueprint.M_SAC, 0),
                OrganBlueprint.pack(3, 0, 0, OrganBlueprint.M_CORE, 0)});
        ForestOrgans.Organ b = organ(level, data, base.east(3), new long[]{
                OrganBlueprint.pack(0, 2, 0, OrganBlueprint.M_CORE, 0)});
        BlockPos coreA = base.east(3), coreB = base.east(3).above(2);
        try {
            level.setBlock(base, OrganBlocks.ORGAN_SAC.get().defaultBlockState(), 3);
            level.setBlock(coreA, OrganBlocks.ORGAN_CORE.get().defaultBlockState(), 3);
            level.setBlock(coreB, OrganBlocks.ORGAN_CORE.get().defaultBlockState(), 3);
            level.destroyBlock(coreA, false);
            helper.assertTrue(a.state == ForestOrgans.State.WITHERED, "芯を壊した器官が枯れない");
            helper.assertTrue(b.state != ForestOrgans.State.WITHERED, "芯を壊していない隣の器官が枯れた");
        } finally {
            data.remove(a.id);
            data.remove(b.id);
            for (BlockPos q : new BlockPos[]{base, coreA, coreB}) level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    private static ForestOrgans.Organ organ(ServerLevel level, ForestOrgans data, BlockPos at, long[] entries) {
        ForestOrgans.Organ o = data.plant(level, OrganKind.PHASE, true, 0, 1, 0, at.getX(), at.getZ());
        o.y = at.getY();
        o.fusion = entries;
        o.takeover = new int[]{at.getX(), at.getY(), at.getZ(), 0, 1, 0};
        o.fusionChecked = true;
        o.state = ForestOrgans.State.ALIVE;
        return o;
    }
}
