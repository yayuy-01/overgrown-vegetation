package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import com.yayuy.overgrownvegetation.corruption.CaveGrowth;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 巨大樹の葉と汚染の根(2026-11-16 ユーザー「巨大樹を遠くから見えるようにする機能で、同じブロックの根のアーチやコルダイテスの葉が遠くで浮いて見える」)。
 * <ol>
 *   <li>影絵のタグは巨大樹専用のブロックだけを持ち、コルダイテスの葉・汚染の根は入らない。見た目が同じ 2 つは「過繁茂の葉」「巨大樹の太い木部」で同じに扱われる。</li>
 *   <li>巨大樹(苗木・世界生成が使う configured_feature {@code overgrown_tree_giant})の葉は巨大樹の葉。</li>
 *   <li>汚染が実際に生やす根(洞窟の垂れる根)は汚染の根で、影絵の木部のブロックを 1 つも含まない。</li>
 * </ol>
 * 影絵そのもの(クライアント)はここでは見られないので、Claude 用クライアントの撮り比べで見る(DEV_NOTES「巨大樹の葉と汚染の根」)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GiantCanopyLeavesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GiantCanopyLeavesGameTests.class);
        }

        private Registration() {
        }
    }

    /** 影絵のタグは巨大樹だけ、見た目が同じものは同じタグで同じに扱われる。 */
    @GameTest(template = "gametest_empty7", batch = "giant_canopy_leaves", timeoutTicks = 20)
    public static void tagsSeparateGiantTreeFromLookalikes(GameTestHelper helper) {
        BlockState giantLeaf = ModBlocks.GIANT_CANOPY_LEAVES.get().defaultBlockState();
        BlockState cordaitesLeaf = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        BlockState giantLog = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        BlockState rootLog = ModBlocks.ROOT_ARCH_LOG.get().defaultBlockState();

        // 影絵: 巨大樹の葉・原木・形成層・硬化した幹だけ。
        expect(helper, giantLeaf.is(ModTags.GIANT_SILHOUETTE_LEAVES), "巨大樹の葉が影絵の葉に入っていない");
        expect(helper, !cordaitesLeaf.is(ModTags.GIANT_SILHOUETTE_LEAVES), "コルダイテスの葉が影絵の葉に入っている");
        expect(helper, giantLog.is(ModTags.GIANT_SILHOUETTE_WOOD), "巨大樹の原木が影絵の木部に入っていない");
        expect(helper, ModBlocks.GIANT_OVERGROWN_CAMBIUM.get().defaultBlockState().is(ModTags.GIANT_SILHOUETTE_WOOD), "形成層が影絵の木部に入っていない");
        expect(helper, ModBlocks.HARDENED_GIANT_TRUNK.get().defaultBlockState().is(ModTags.GIANT_SILHOUETTE_WOOD), "硬化した幹が影絵の木部に入っていない");
        expect(helper, !rootLog.is(ModTags.GIANT_SILHOUETTE_WOOD), "汚染の根が影絵の木部に入っている");
        expect(helper, !giantLeaf.is(ModTags.GIANT_SILHOUETTE_WOOD) && !rootLog.is(ModTags.GIANT_SILHOUETTE_LEAVES), "葉と木部のタグが混ざっている");

        // 見た目が同じものは同じに扱う(演出・判定はこのタグで見る)。
        expect(helper, giantLeaf.is(ModTags.OVERGROWN_LEAVES) && cordaitesLeaf.is(ModTags.OVERGROWN_LEAVES), "過繁茂の葉のタグに 2 つの葉が揃っていない");
        expect(helper, giantLog.is(ModTags.GIANT_WOOD) && rootLog.is(ModTags.GIANT_WOOD), "巨大樹の太い木部のタグに原木と汚染の根が揃っていない");
        expect(helper, giantLeaf.getBlock() instanceof OvergrownLeavesBlock, "巨大樹の葉がコルダイテスの葉と同じクラスでない(instanceof の判定が外れる)");
        expect(helper, rootLog.getBlock() instanceof RotatedPillarBlock, "汚染の根が向きを持たない");
        expect(helper, TreeCanopyGroundFinder.isTreePart(giantLeaf) && TreeCanopyGroundFinder.isTreePart(rootLog), "新しいブロックが木の一部に数えられない(洞窟の判定で岩の天井になる)");

        // 既存のタグ: コルダイテスの葉・巨大樹の原木と同じ所に入っている。
        expectSameTags(helper, giantLeaf, cordaitesLeaf, "巨大樹の葉");
        expectSameTags(helper, rootLog, giantLog, "汚染の根");

        // アイテムは持たない(ルートテーブルで元のアイテムを落とす)。
        expect(helper, ModBlocks.GIANT_CANOPY_LEAVES.get().asItem() == Items.AIR, "巨大樹の葉がアイテムを持っている");
        expect(helper, ModBlocks.ROOT_ARCH_LOG.get().asItem() == Items.AIR, "汚染の根がアイテムを持っている");
        helper.succeed();
    }

    /** 巨大樹(苗木・世界生成)の葉は巨大樹の葉、幹は巨大樹の原木。 */
    @GameTest(template = "gametest_empty7", batch = "giant_canopy_leaves", timeoutTicks = 20)
    public static void giantTreeFeatureUsesGiantCanopyLeaves(GameTestHelper helper) {
        ConfiguredFeature<?, ?> feature = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(OvergrownVegetation.id("overgrown_tree_giant"));
        if (feature == null || !(feature.config() instanceof GiantTreeFeature.Config config)) {
            helper.fail("configured_feature overgrown_tree_giant が巨大樹の設定で読み込まれていない");
            return;
        }
        expect(helper, config.leavesState().is(ModBlocks.GIANT_CANOPY_LEAVES.get()), "巨大樹の葉でない: " + config.leavesState());
        expect(helper, config.trunkState().is(ModBlocks.GIANT_OVERGROWN_LOG.get()), "巨大樹の幹が巨大樹の原木でない: " + config.trunkState());
        helper.succeed();
    }

    /** 汚染が生やす根(洞窟の天井から垂れる根)は汚染の根で、影絵の木部を 1 つも含まない。 */
    @GameTest(template = "gametest_empty7", batch = "giant_canopy_leaves", timeoutTicks = 40)
    public static void corruptionRootsAreNotSilhouetteWood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = helper.absolutePos(new BlockPos(3, 60, 3));
        int r = 14;
        int built = 0;
        int rootBlocks = 0;
        int silhouetteBlocks = 0;
        try {
            for (long seed = 1; seed <= 12 && built < 3; seed++) {
                clear(level, host, r);
                level.setBlock(host, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                if (!CaveGrowth.buildHangingRootTip(level, host, RandomSource.create(seed), CaveGrowth.Mode.JUNGLE)) {
                    continue;
                }
                built++;
                for (BlockPos p : BlockPos.betweenClosed(host.offset(-r, -r, -r), host.offset(r, 1, r))) {
                    BlockState s = level.getBlockState(p);
                    if (s.is(ModBlocks.ROOT_ARCH_LOG.get())) {
                        rootBlocks++;
                    }
                    if (s.is(ModTags.GIANT_SILHOUETTE_WOOD) || s.is(ModTags.GIANT_SILHOUETTE_LEAVES)) {
                        silhouetteBlocks++;
                    }
                }
            }
        } finally {
            clear(level, host, r);
        }
        if (built == 0) {
            helper.fail("垂れる根を 1 本も生やせなかった");
            return;
        }
        expect(helper, rootBlocks > 0, "垂れる根に汚染の根が無い(" + built + " 本)");
        expect(helper, silhouetteBlocks == 0, "垂れる根に影絵のブロックが " + silhouetteBlocks + " 個ある");
        helper.succeed();
    }

    private static void clear(ServerLevel level, BlockPos host, int r) {
        for (BlockPos p : BlockPos.betweenClosed(host.offset(-r, -r, -r), host.offset(r, 1, r))) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** {@code a} が {@code b} の入っているこの MOD・バニラの道具のタグにすべて入っているか(新しく作ったタグ・影絵のタグは除く)。 */
    private static void expectSameTags(GameTestHelper helper, BlockState a, BlockState b, String what) {
        b.getTags().forEach(tag -> {
            if (tag.equals(ModTags.GIANT_SILHOUETTE_WOOD) || tag.equals(ModTags.GIANT_SILHOUETTE_LEAVES) || tag.equals(BlockTags.LOGS)
                    || tag.equals(BlockTags.LOGS_THAT_BURN) || tag.equals(BlockTags.OVERWORLD_NATURAL_LOGS)) {
                return;
            }
            if (!a.is(tag)) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(what + " がタグ " + tag.location() + " に入っていない");
            }
        });
    }

    private static void expect(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(message);
        }
    }

    private GiantCanopyLeavesGameTests() {
    }
}
