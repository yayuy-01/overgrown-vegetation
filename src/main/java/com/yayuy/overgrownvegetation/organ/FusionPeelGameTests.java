package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 枯れた乗っ取りの器官を剥がすとき、器官が置いていない物(先にあった巨大樹の木・覆われた建材・汚染の地面)を消さない
 * (2026-11-20 20 構造物の調べの続き: 剥がしが設計図のマスにあるこの MOD のブロックを全部消し、巨大樹の幹が切れて上が浮いていた)。
 * 1 tick の予算で分けて剥がし、最後まで剥がし終わることも見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FusionPeelGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FusionPeelGameTests.class);
        }

        private Registration() {
        }
    }

    private FusionPeelGameTests() {
    }

    @GameTest(template = "gametest_empty7", batch = "fusion_peel", timeoutTicks = 40)
    public static void peelKeepsBlocksTheOrganDidNotPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(3, 2, 3));
        // 器官の設計図: 袋 3 マス・蔦 1 マス。うち 2 マスには器官より先に別の物がある
        long[] entries = {
                OrganBlueprint.pack(0, 0, 0, OrganBlueprint.M_SAC, 0),
                OrganBlueprint.pack(1, 0, 0, OrganBlueprint.M_SAC, 0),
                OrganBlueprint.pack(0, 1, 0, OrganBlueprint.M_SAC, 0),
                OrganBlueprint.pack(0, 0, 1, OrganBlueprint.M_VINE, 0),
        };
        level.setBlock(a, OrganBlocks.ORGAN_SAC.get().defaultBlockState(), 3);               // 器官が置いた
        level.setBlock(a.east(), ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState(), 3); // 先にあった巨大樹の幹
        level.setBlock(a.above(), ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState(), 3);    // 先にあった汚染のブロック
        level.setBlock(a.south(), OrganBlocks.ORGAN_VINE.get().defaultBlockState(), 3);      // 器官が置いた
        ForestOrgans.Organ o = new ForestOrgans.Organ(-77, OrganKind.PHASE, true, 0, 1, 0, a.getX(), a.getZ(), 0L);
        o.y = a.getY();
        o.fusion = entries;
        o.takeover = new int[]{a.getX(), a.getY(), a.getZ(), 1, 0, 0};
        o.state = ForestOrgans.State.WITHERED;
        OrganBlueprint bp = OrganBlueprint.fromEntries(entries, 1);
        // 1 tick に 1 マスずつ(予算で分かれても最後まで剥がし終わる)
        for (int i = 0; i < 10 && !o.fusionCleaned; i++) OrganWorld.cleanFusion(level, o, bp, 1);
        helper.assertTrue(o.fusionCleaned, "剥がし終わらなかった");
        helper.assertTrue(level.getBlockState(a).isAir(), "器官の袋が残った: " + level.getBlockState(a));
        helper.assertTrue(level.getBlockState(a.south()).isAir(), "器官の蔦が残った: " + level.getBlockState(a.south()));
        helper.assertTrue(level.getBlockState(a.east()).is(ModBlocks.GIANT_OVERGROWN_LOG.get()), "巨大樹の幹を消した: " + level.getBlockState(a.east()));
        helper.assertTrue(level.getBlockState(a.above()).is(ModBlocks.OVERGROWN_BLOCK.get()), "汚染のブロックを消した: " + level.getBlockState(a.above()));
        for (BlockPos p : new BlockPos[]{a, a.east(), a.above(), a.south()}) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }
}
