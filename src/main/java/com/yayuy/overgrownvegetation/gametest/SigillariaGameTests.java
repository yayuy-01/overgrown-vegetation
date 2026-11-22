package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SigillariaConeBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModFeatures;
import com.yayuy.overgrownvegetation.worldgen.SigillariaTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/**
 * 封印木(2026-11-01): 木が生えて原木・葉(芯と外)・球果が揃う / 球果を壊すと自分を落とす。
 *
 * <p>木は高さ 16〜24 でテスト場(7×7、天井 y=8 にバリア)に入らないので、テスト場の {@value #SKY} 段上の空中に生やし、
 * 確かめたら周りの箱を空気に戻す(隣のテスト場の上空にはみ出すが、ほかのテストは天井より下しか使わない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SigillariaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SigillariaGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;
    /** 木を数え・片付ける箱の水平の半径と高さ(腕は最大でおよそ 9 ブロック横へ出る)。 */
    private static final int REACH = 12, TALL = 34;

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void armTreeHasLogsLeavesAndCones(GameTestHelper helper) {
        growAndCheck(helper, false);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void pillarTreeHasLogsLeavesAndCones(GameTestHelper helper) {
        growAndCheck(helper, true);
    }

    private static void growAndCheck(GameTestHelper helper, boolean pillar) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, SKY, 3));
        ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(Registries.CONFIGURED_FEATURE,
                ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "sigillaria_tree"));
        ConfiguredFeature<?, ?> configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(key);
        if (configured == null || !(configured.config() instanceof SigillariaTreeFeature.Config base)) {
            helper.fail("configured_feature sigillaria_tree が無い");
            return;
        }
        SigillariaTreeFeature.Config config = base.fixedForTests(pillar);
        boolean placed = false;
        int logs = 0, branches = 0, leaves = 0, fringe = 0, cones = 0, logsOffTrunk = 0, maxY = origin.getY();
        String badCone = null;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        try {
            placed = ModFeatures.SIGILLARIA_TREE.get().place(new FeaturePlaceContext<>(Optional.empty(), level,
                    level.getChunkSource().getGenerator(), RandomSource.create(level.getGameTime() * 31 + origin.asLong()), origin, config));
            for (int dy = 0; dy <= TALL; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        BlockState s = level.getBlockState(p);
                        if (s.is(ModBlocks.SIGILLARIA_LOG.get())) {
                            logs++;
                            maxY = Math.max(maxY, p.getY());
                            // 幹の列から外れた原木 = 腕(根元の十字の原木は 11-02 に外した)
                            if (dx != 0 || dz != 0) logsOffTrunk++;
                        } else if (s.is(ModBlocks.SIGILLARIA_BRANCH.get())) {
                            branches++;
                            logsOffTrunk++;
                        } else if (s.is(ModBlocks.SIGILLARIA_LEAVES.get())) {
                            leaves++;
                        } else if (s.is(ModBlocks.SIGILLARIA_LEAVES_FRINGE.get())) {
                            fringe++;
                        } else if (s.is(ModBlocks.SIGILLARIA_CONE.get())) {
                            cones++;
                            Direction facing = s.getValue(SigillariaConeBlock.FACING);
                            if (!level.getBlockState(p.relative(facing.getOpposite())).is(ModBlocks.SIGILLARIA_LOG.get())) {
                                badCone = p.toShortString();
                            }
                        }
                    }
                }
            }
        } finally {
            // 片付け(封印木のブロックだけ空気に。形の更新は出さない)
            for (int dy = 0; dy <= TALL; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        BlockState s = level.getBlockState(p);
                        if (s.is(ModBlocks.SIGILLARIA_LOG.get()) || s.is(ModBlocks.SIGILLARIA_BRANCH.get()) || s.is(ModBlocks.SIGILLARIA_LEAVES.get())
                                || s.is(ModBlocks.SIGILLARIA_LEAVES_FRINGE.get()) || s.is(ModBlocks.SIGILLARIA_CONE.get())) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
        }
        String summary = (pillar ? "柱" : "腕") + "の型: 原木 " + logs + "・枝の継ぎ目 " + branches + "・芯の葉 " + leaves + "・外の葉 " + fringe + "・球果 " + cones
                + "・幹から外れた原木 " + logsOffTrunk + "・高さ " + (maxY - origin.getY() + 1);
        int minHeight = pillar ? config.pillarMinHeight() : config.armMinHeight();
        if (!placed) {
            helper.fail("生えなかった(" + summary + ")");
        } else if (logs < minHeight || leaves < 8 || fringe < 8 || cones < 1) {
            helper.fail("揃っていない: " + summary);
        } else if (badCone != null) {
            helper.fail("原木に付いていない球果 " + badCone + ": " + summary);
        } else if (pillar ? logsOffTrunk != 0 : logsOffTrunk < 3) {
            helper.fail("型の形が違う: " + summary);
        } else if (!pillar && com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.branchJointTaper.get() && branches == 0) {
            helper.fail("腕先が細くなっていない(枝の継ぎ目が無い): " + summary);
        } else if (maxY - origin.getY() + 1 < minHeight * 0.75) {
            helper.fail("低すぎる: " + summary);
        } else {
            helper.succeed();
        }
    }

    /** 球果を壊すと球果のアイテムが落ちる。支えの原木を壊しても球果は落ちて(消えて)アイテムになる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void coneDropsItselfWhenBrokenOrUnsupported(GameTestHelper helper) {
        BlockPos log1 = new BlockPos(2, 2, 3), cone1 = new BlockPos(2, 2, 2);
        BlockPos log2 = new BlockPos(5, 2, 3), cone2 = new BlockPos(5, 2, 2);
        BlockState log = ModBlocks.SIGILLARIA_LOG.get().defaultBlockState();
        BlockState cone = ModBlocks.SIGILLARIA_CONE.get().defaultBlockState().setValue(SigillariaConeBlock.FACING, Direction.NORTH);
        helper.setBlock(log1, log);
        helper.setBlock(cone1, cone);
        helper.setBlock(log2, log);
        helper.setBlock(cone2, cone);
        if (!cone.canSurvive(helper.getLevel(), helper.absolutePos(cone1))) {
            helper.fail("北向きの球果が南の原木に付いていられない");
            return;
        }
        helper.getLevel().destroyBlock(helper.absolutePos(cone1), true);
        helper.getLevel().destroyBlock(helper.absolutePos(log2), false); // 支えを消す → 球果は形の更新で落ちる
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(ModBlocks.SIGILLARIA_CONE.get(), cone1);
            helper.assertBlockNotPresent(ModBlocks.SIGILLARIA_CONE.get(), cone2);
            helper.assertItemEntityCountIs(ModBlocks.SIGILLARIA_CONE.get().asItem(), cone1, 1.5, 1);
            helper.assertItemEntityCountIs(ModBlocks.SIGILLARIA_CONE.get().asItem(), cone2, 1.5, 1);
        });
    }
}
