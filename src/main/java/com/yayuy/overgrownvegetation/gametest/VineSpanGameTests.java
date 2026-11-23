package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.corruption.VineSpan;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
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

/**
 * 壁と壁の隙間に渡る太い蔦(2026-11-07、{@link VineSpan})。テスト場の 7×7 の中に、x = 0 と x = 6 の石の壁を立てて間(幅 6)に渡す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VineSpanGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterVineSpanGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(VineSpanGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos WALL_A = new BlockPos(0, 7, 3);
    private static final BlockPos WALL_B = new BlockPos(6, 7, 3);
    /** 置くテスト用の低い弦(テスト場の上 y = 8 あたりはバリアの天井なので、糸の上に 2 マス空くように低くする)。 */
    private static final BlockPos LOW_A = new BlockPos(0, 4, 3);
    private static final BlockPos LOW_B = new BlockPos(6, 4, 3);

    private static void arena(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                for (int y = 2; y <= 7; y++) {
                    BlockState s = x == 0 || x == 6 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), s, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** 置けて、歩ける撚り糸の上面の段差が 1 以内(跳べば渡れる)、真ん中の列に芯があり、端が壁に食い込み、描画の蔦の印は置かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanIsWalkableAndAnchored(GameTestHelper helper) {
        arena(helper);
        ServerLevel level = helper.getLevel();
        VineSpan.Result r = VineSpan.build(level, helper.absolutePos(LOW_A), helper.absolutePos(LOW_B), RandomSource.create(7L));
        if (!r.ok()) {
            helper.fail("置けない: " + r.message());
            return;
        }
        if (r.maxStep() > 1) {
            helper.fail("上面の段差が 1 を超える: " + r.maxStep());
            return;
        }
        // 葉と植物は何 tick かに分けて置く(VineSpanPlacer)ので、置き終わるのを待ってから見る
        helper.runAfterDelay(PLACE_WAIT, () -> checkWalkable(helper, level, r));
    }

    /** 置き終わるのを待つ tick(テスト場の組は数十マスなので 1〜2 tick で置き終わる。余裕を見て)。 */
    private static final int PLACE_WAIT = 20;

    private static void checkWalkable(GameTestHelper helper, ServerLevel level, VineSpan.Result r) {
        // 糸は汚染された葉だけ(11-07 ユーザー決定「蔦は草ブロックだけで構成されたものにしよう」): 真ん中の列に葉があり、ほかの蔦のブロックは無い
        boolean mid = false;
        for (int y = 2; y <= 7; y++) for (int z = 0; z <= 6; z++) {
            if (level.getBlockState(helper.absolutePos(new BlockPos(3, y, z))).is(ModBlocks.OVERGROWN_LEAVES.get())) {
                mid = true;
            }
        }
        if (!mid) {
            helper.fail("真ん中(x = 3)に葉の芯が無い");
            return;
        }
        for (int x = 1; x <= 5; x++) for (int y = 2; y <= 7; y++) for (int z = 0; z <= 6; z++) {
            BlockState s = level.getBlockState(helper.absolutePos(new BlockPos(x, y, z)));
            if (s.blocksMotion() && !s.is(ModBlocks.OVERGROWN_LEAVES.get())) {
                helper.fail("葉でない固いブロックが糸にある: " + s);
                return;
            }
        }
        if (!level.getBlockState(helper.absolutePos(LOW_A)).is(ModBlocks.OVERGROWN_LEAVES.get())
                || !level.getBlockState(helper.absolutePos(LOW_B)).is(ModBlocks.OVERGROWN_LEAVES.get())) {
            helper.fail("両端の壁に葉で食い込んでいない");
            return;
        }
        // 幅 6 の細い隙間は歩ける糸 1 本 + 垂れる糸 1 本(11-07 ユーザー決定「隙間の大きさに合わせて本数を変える」)
        if (r.walkable() != 1 || r.strands() != 2) {
            helper.fail("細い隙間は歩ける 1 本 + 垂れる 1 本のはず: 歩ける " + r.walkable() + " 全部 " + r.strands());
            return;
        }
        int marks = 0;
        for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(-1, 2, -1)), helper.absolutePos(new BlockPos(7, 11, 7)))) {
            if (level.getBlockEntity(p) instanceof CorruptedLianaBlockEntity) {
                marks++;
            }
        }
        if (marks != 0) {
            helper.fail("描画の蔦の印は置かない(11-07 ユーザー決定)のに " + marks + " 個");
            return;
        }
        helper.succeed();
    }

    /** 置いた組は記録に残り(葉では木の葉と見分けがつかないので)、すぐ近くの弦は「近くに太い蔦がある」になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanIsRecordedForSpacing(GameTestHelper helper) {
        arena(helper);
        ServerLevel level = helper.getLevel();
        VineSpan.Result r = VineSpan.build(level, helper.absolutePos(LOW_A), helper.absolutePos(LOW_B), RandomSource.create(9L));
        if (!r.ok()) {
            helper.fail("置けない: " + r.message());
            return;
        }
        if (!com.yayuy.overgrownvegetation.corruption.VineSpanRecord.get(level)
                .near(helper.absolutePos(new BlockPos(0, 4, 5)), helper.absolutePos(new BlockPos(6, 4, 5)), 16)) {
            helper.fail("置いた組が記録に無い(近くの弦が近くと判定されない)");
            return;
        }
        helper.succeed();
    }

    /** 上下に近い組は「近い」(11-07 ユーザー決定「重なりを抑える」: 上下も vineSpanSpacing で見る。前は上下 6 で、高さ違いに積み重なった)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanStackedAboveIsNear(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.corruption.VineSpanRecord rec = com.yayuy.overgrownvegetation.corruption.VineSpanRecord.get(helper.getLevel());
        BlockPos a = helper.absolutePos(new BlockPos(0, 100, 3));
        BlockPos b = helper.absolutePos(new BlockPos(20, 100, 3));
        rec.add(a, b);
        if (!rec.near(a.above(12), b.above(12), 16)) {
            helper.fail("12 上の弦が近いと判定されない(上下が 16 で見られていない)");
            return;
        }
        if (rec.near(a.above(40), b.above(40), 16)) {
            helper.fail("40 上の弦まで近いと判定された");
            return;
        }
        helper.succeed();
    }

    /** 汚染された洞窟の壁(洞窟の壁を這う汚染で汚染したマス)からも太い蔦が出る(11-07 ユーザー決定「洞窟にも出す」)。確率はテストの間だけ 1、間隔は 0。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanGrowsFromCorruptedCaveWall(GameTestHelper helper) {
        arena(helper);
        // 隙間の判定は弦の下に 5 マス以上の空きを見るので、テスト場の床より下(y -4〜1)も掘り下げて壁を伸ばす
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) for (int y = -4; y <= 1; y++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)),
                    x == 0 || x == 6 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        ServerLevel level = helper.getLevel();
        var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
        double chance = cfg.vineSpanCaveChance.get();
        int spacing = cfg.vineSpanSpacing.get();
        boolean grown = false;
        try {
            cfg.vineSpanCaveChance.set(0.0D);
            for (int y = 2; y <= 7; y++) for (int z = 0; z <= 6; z++) {
                com.yayuy.overgrownvegetation.spore.field.CaveWallCreep.corruptForTests(level, helper.absolutePos(new BlockPos(6, y, z)), RandomSource.create(1L));
            }
            cfg.vineSpanCaveChance.set(1.0D);
            cfg.vineSpanSpacing.set(0);
            int before = com.yayuy.overgrownvegetation.corruption.VineSpan.CAVE_GROWN;
            for (int z = 2; z <= 4 && !grown; z++) {
                com.yayuy.overgrownvegetation.spore.field.CaveWallCreep.corruptForTests(level, helper.absolutePos(new BlockPos(0, 4, z)), RandomSource.create(3L + z));
                grown = com.yayuy.overgrownvegetation.corruption.VineSpan.CAVE_GROWN > before;
            }
        } finally {
            cfg.vineSpanCaveChance.set(chance);
            cfg.vineSpanSpacing.set(spacing);
        }
        if (!grown) {
            helper.fail("汚染された洞窟の壁から太い蔦が出ない: " + com.yayuy.overgrownvegetation.corruption.VineSpan.LAST_FAIL);
            return;
        }
        // 葉は何 tick かに分けて置くので、置き終わるのを待ってから見る
        helper.runAfterDelay(PLACE_WAIT, () -> {
            boolean mid = false;
            for (int y = 2; y <= 7; y++) for (int z = 0; z <= 6; z++) {
                mid |= level.getBlockState(helper.absolutePos(new BlockPos(3, y, z))).is(ModBlocks.OVERGROWN_LEAVES.get());
            }
            if (!mid) {
                helper.fail("出たはずの太い蔦の芯(葉)が真ん中に無い");
                return;
            }
            helper.succeed();
        });
    }

    /** 幅ごとの本数(仮決め: 6〜11 は 1 本、12〜19 は 2 本、20〜29 は 3 本、30 以上は 4 本)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanStrandCountFollowsWidth(GameTestHelper helper) {
        int[][] cases = {{8, 1}, {11, 1}, {12, 2}, {19, 2}, {20, 3}, {29, 3}, {30, 4}, {40, 4}};
        for (int[] c : cases) {
            int got = VineSpan.walkCountFor(c[0] + 0.5D);
            if (got != c[1]) {
                helper.fail("幅 " + c[0] + " の本数が " + got + "(" + c[1] + " のはず)");
                return;
            }
        }
        helper.succeed();
    }

    /** 天井が低くて糸の上に 2 マス空かないなら、主の糸を細くしても足りないので組ごと置かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanSkipsLowCeiling(GameTestHelper helper) {
        arena(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 7, z)), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        VineSpan.Result r = VineSpan.build(level, helper.absolutePos(LOW_A), helper.absolutePos(LOW_B), RandomSource.create(7L));
        if (r.ok() || !r.message().contains("天井")) {
            helper.fail("天井の低い所に置いた: " + r.message());
            return;
        }
        helper.succeed();
    }

    /** 隙間は見つかるが、汚染されていない壁なら自然には出さない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void vineSpanGapNeedsCorruptedWalls(GameTestHelper helper) {
        arena(helper);
        ServerLevel level = helper.getLevel();
        VineSpan.Gap g = VineSpan.findGap(level, helper.absolutePos(WALL_A), RandomSource.create(1L), false);
        if (g == null || g.b().getX() != helper.absolutePos(WALL_B).getX()) {
            helper.fail("隙間が見つからない: " + g);
            return;
        }
        if (VineSpan.findGap(level, helper.absolutePos(WALL_A), RandomSource.create(1L), true) != null) {
            helper.fail("汚染されていない壁で隙間を返した");
            return;
        }
        helper.succeed();
    }
}
