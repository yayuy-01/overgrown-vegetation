package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.LedgerSpread;
import com.yayuy.overgrownvegetation.corruption.pushback.AshLimeBandBlock;
import com.yayuy.overgrownvegetation.corruption.pushback.LedgerShrink;
import com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlockEntity;
import com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.corruption.pushback.VentGuard;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 押し返す用の道具と方法(2026-11-18 夜)。地面の割合が 3 割【仮】を切ると台帳から外れて面積の点の率が下がる・猶予の間は外れない・
 * 換気機の範囲で発芽しない・帯の線は越えられず回数で消える・杭は FE がある間だけ範囲を守る・燻る灰と焼けた荒地・枯らし薬。期待値は設計値({@code push_back.json})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PushBackGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PushBackGameTests.class);
        }

        private Registration() {
        }
    }

    private PushBackGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "push_back";

    // ---------------------------------------------------------------- 1. 森の面積が減る

    /** 遠くのチャンクの地表を全部汚染の地面にして、時刻 since で台帳に載せる。 */
    private static LevelChunk overgrownChunk(ServerLevel level, PhaseData pd, int cx, int cz, long since) {
        LevelChunk chunk = level.getChunk(cx, cz);
        BlockState grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState();
        // 地形に頼らない: y = 100 に汚染の地面の平らな層を敷き、その上を 16 段空ける(テスト場の世界は平ら。前の実行の層もここで上書き)
        int top = 100;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(new BlockPos(cx * 16 + x, top, cz * 16 + z), grass, 2);
                for (int y = top + 1; y <= top + 16; y++) level.setBlock(new BlockPos(cx * 16 + x, y, cz * 16 + z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        pd.forgetOvergrownChunk(chunk.getPos());
        pd.devBulkMark(new long[]{chunk.getPos().toLong()}, since);
        return chunk;
    }

    /** 地表の {@code n} 列を土に戻す(汚染を取り除いた)。 */
    private static void clearColumns(ServerLevel level, LevelChunk chunk, int n) {
        int done = 0;
        for (int x = 0; x < 16 && done < n; x++) {
            for (int z = 0; z < 16 && done < n; z++, done++) {
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                level.setBlock(new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z), Blocks.DIRT.defaultBlockState(), 2);
            }
        }
    }

    /** 地面の割合が 3 割【仮】を切ると台帳から外れ、面積の点の率(pointsPerTick)が下がる。3 割より上なら外れない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void shareBelowThresholdLeavesLedger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        PushBackTable.Shrink t = DesignData.pushBack().shrink;
        long old = pd.clock(level.getServer()) - (long) ((t.graceDays + 1) * 24000L);
        LevelChunk chunk = overgrownChunk(level, pd, 630_001, 630_001, old);
        ChunkPos pos = chunk.getPos();
        try {
            // 3 割より少し上まで取り除く → 外れない
            int keepAbove = (int) Math.ceil(256 * t.threshold) + 4;
            clearColumns(level, chunk, 256 - keepAbove);
            double share = LedgerShrink.groundShare(chunk, t.minColumns);
            helper.assertTrue(share >= t.threshold, "割合の数え方が違う: " + share);
            helper.assertTrue(!LedgerShrink.checkNowForTests(level, pos, LedgerShrink.Cause.REMOVED), "3 割より上なのに外れた: " + share);
            int size = pd.overgrownLedgerSize();
            double rate = DesignData.phases().pointsPerTick(size);
            // 3 割を切るまで取り除く → 外れる
            clearColumns(level, chunk, 256);
            helper.assertTrue(LedgerShrink.checkNowForTests(level, pos, LedgerShrink.Cause.REMOVED), "3 割を切ったのに外れない");
            helper.assertTrue(!pd.isOvergrownChunk(pos), "台帳に残っている");
            helper.assertTrue(pd.overgrownLedgerSize() == size - 1, "台帳の大きさが減っていない");
            helper.assertTrue(DesignData.phases().pointsPerTick(pd.overgrownLedgerSize()) <= rate, "面積の点の率が下がっていない");
            helper.succeed();
        } finally {
            pd.forgetOvergrownChunk(pos);
        }
    }

    /** 台帳に載って猶予(graceDays)の間は、地面が 0% でも外れない(発芽したての芽はまだ広がる途中)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void freshChunkIsNotForgotten(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        LevelChunk chunk = overgrownChunk(level, pd, 630_005, 630_001, pd.clock(level.getServer()));
        ChunkPos pos = chunk.getPos();
        try {
            clearColumns(level, chunk, 256);
            helper.assertTrue(!LedgerShrink.checkNowForTests(level, pos, LedgerShrink.Cause.REMOVED), "猶予の間に外れた");
            helper.assertTrue(pd.isOvergrownChunk(pos), "猶予の間に台帳から消えた");
            helper.succeed();
        } finally {
            pd.forgetOvergrownChunk(pos);
        }
    }

    // ---------------------------------------------------------------- 2. 筒の火
    // 胞子焼きの筒の火で汚染の土が灰になるテスト(burnerScorchesGroundToAsh)は、11-21 に筒ごと消した(装備の作り直し)。

    /**
     * 火で地面の割合が 3 割を切ると焼けた荒地になる: 台帳から外れ・バイオームが焼けた荒地・その間は汚染の関門も発芽も断る(防火帯)。
     * fire.burntDays【仮 1 日】たつと世界生成のときのバイオームへ戻り、防火帯が解ける。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void fireMakesBurntLandForADay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        PushBackTable.Shrink t = DesignData.pushBack().shrink;
        long old = pd.clock(level.getServer()) - (long) ((t.graceDays + 1) * 24000L);
        LevelChunk chunk = overgrownChunk(level, pd, 630_009, 630_001, old);
        ChunkPos pos = chunk.getPos();
        BlockPos inside = pos.getMiddleBlockPosition(level.getSeaLevel());
        try {
            // 火で焼いた跡(燻る灰)にする
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                level.setBlock(new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z), ModPushBack.SMOLDERING_ASH.get().defaultBlockState(), 2);
            }
            helper.assertTrue(LedgerShrink.checkNowForTests(level, pos, LedgerShrink.Cause.FIRE), "焼いたのに台帳から外れない");
            helper.assertTrue(com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.isBurnt(level, inside), "焼けた荒地にならない");
            helper.assertTrue(level.getBiome(inside).is(com.yayuy.overgrownvegetation.registry.ModBiomes.BURNT_WASTELAND), "バイオームが焼けた荒地でない");
            helper.assertTrue(!CorruptionRegistry.canCorrupt(level, inside.above(40), Blocks.STONE.defaultBlockState()), "焼けた荒地に汚染が入る");
            long[] rejected = LedgerSpread.acceptFieldSprouts(level, new long[]{pos.toLong()});
            helper.assertTrue(rejected.length == 1 && !pd.isOvergrownChunk(pos), "焼けた荒地で発芽を受け取った");
            com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.ageForTests(level, pos, com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.revertTicks());
            com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.checkNowForTests(level, pos);
            helper.assertTrue(!com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.isBurnt(level, inside), "期限が来ても焼けた荒地のまま");
            helper.assertTrue(!level.getBiome(inside).is(com.yayuy.overgrownvegetation.registry.ModBiomes.BURNT_WASTELAND), "期限が来てもバイオームが戻らない");
            helper.succeed();
        } finally {
            pd.forgetOvergrownChunk(pos);
            com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.ageForTests(level, pos, com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.revertTicks());
            com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.checkNowForTests(level, pos);
        }
    }

    /**
     * 枯らし薬: 半径の中の汚染の土は枯れた土に、下草は消える。見直しでは、台帳に載っているチャンクなら汚染の地面へ戻り、外れていればふつうの草に戻る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void witherDraughtWithersForADay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        BlockState grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState();
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) helper.setBlock(new BlockPos(x, 1, z), grass);
        int n = com.yayuy.overgrownvegetation.corruption.pushback.WitherDraughtEntity.splash(level, helper.absolutePos(new BlockPos(3, 2, 3)));
        BlockPos rel = new BlockPos(3, 1, 3);
        BlockState s = helper.getBlockState(rel);
        helper.assertTrue(n > 0 && s.is(ModPushBack.WITHERED_SOIL.get()), "枯らし薬で汚染の土が枯れない: " + s);
        helper.assertTrue(helper.getBlockState(new BlockPos(0, 1, 0)).is(ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                || DesignData.pushBack().spray.radius >= 4.3D, "半径の外まで枯れた");
        // 見直しは台帳だけを見るので、遠くのチャンクの座標で試す(テスト場のチャンクを台帳に載せて外すと、胞子の場のそのチャンクの降り積もりが 0 に戻り、
        // 同じ世界の暴露量のテストが変わる)
        BlockPos abs = new BlockPos(630_013 * 16 + 8, 100, 630_001 * 16 + 8);
        ChunkPos cp = new ChunkPos(abs);
        boolean before = false;
        try {
            pd.devBulkMark(new long[]{cp.toLong()}, pd.clock(level.getServer()));
            helper.assertTrue(com.yayuy.overgrownvegetation.corruption.pushback.WitheredSoilBlock.reviveTo(level, abs, s).is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()),
                    "台帳に載っているのに汚染の地面へ戻らない");
            pd.forgetOvergrownChunk(cp);
            helper.assertTrue(com.yayuy.overgrownvegetation.corruption.pushback.WitheredSoilBlock.reviveTo(level, abs, s).is(Blocks.GRASS_BLOCK),
                    "台帳から外れたのにふつうの草に戻らない");
            helper.succeed();
        } finally {
            pd.forgetOvergrownChunk(cp);
        }
    }

    // ---------------------------------------------------------------- 3a. 換気機の範囲で発芽しない

    /** 動いている換気機のチャンクでは発芽を受け取らない(台帳に載らない)。止めれば範囲でなくなる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void ventStopsSprouting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModVent.VENTILATOR.get().defaultBlockState());
        VentBlockEntity vent = (VentBlockEntity) helper.getBlockEntity(rel);
        BlockPos abs = helper.absolutePos(rel);
        ChunkPos cp = new ChunkPos(abs);
        boolean before = pd.isOvergrownChunk(cp);
        try {
            vent.energyPort().receiveEnergy(100000, false);
            vent.setFilter(new ItemStack(ModVent.AIR_FILTER.get()));
            helper.assertTrue(vent.tickOnce() && vent.running(), "換気機が動かない");
            VentGuard.invalidate();
            helper.assertTrue(VentGuard.covers(level, cp.x, cp.z), "動いている換気機のチャンクが範囲にならない");
            double r = DesignData.pushBack().vent.noSproutRadiusChunks;
            helper.assertTrue(!VentGuard.covers(level, cp.x + (int) Math.ceil(r) + 1, cp.z), "範囲の外まで守っている");
            if (!before) {
                long[] rejected = LedgerSpread.acceptFieldSprouts(level, new long[]{cp.toLong()});
                helper.assertTrue(rejected.length == 1 && !pd.isOvergrownChunk(cp), "換気機の範囲で発芽を受け取った");
            }
            vent.setFilter(ItemStack.EMPTY);
            VentGuard.invalidate();
            helper.assertTrue(!VentGuard.covers(level, cp.x, cp.z), "止めた換気機がまだ守っている");
            helper.succeed();
        } finally {
            helper.setBlock(rel, Blocks.AIR);
            VentGuard.invalidate();
            if (!before) pd.forgetOvergrownChunk(cp);
        }
    }

    // ---------------------------------------------------------------- 3b. 灰と石灰の帯 = 線・地脈の杭 = 範囲の場

    /** 帯の線(z = 3 の横一列)。 */
    private static void bandLine(GameTestHelper helper, BlockState state) {
        for (int x = 0; x <= 6; x++) helper.setBlock(new BlockPos(x, 2, 3), state);
    }

    /**
     * 帯の線は越えられない: 帯の列には入らない・出どころから線分が帯をまたぐ所へは置けない・同じ側は置ける。
     * 止めるたびに近い帯が 1 回すり減り、bandHits 回で消えると線が切れて越えられる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void bandLineCannotBeCrossedAndWearsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        bandLine(helper, ModPushBack.ASH_LIME_BAND.get().defaultBlockState());
        BlockPos src = helper.absolutePos(new BlockPos(3, 1, 0));     // 線の北
        BlockPos sameSide = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos across = helper.absolutePos(new BlockPos(3, 1, 6));  // 線の南
        BlockPos onLine = helper.absolutePos(new BlockPos(1, 1, 3));  // 帯の下の床
        try {
            RingWards.resetCooldownForTests(level);
            helper.assertTrue(RingWards.guarded(level, onLine), "帯の列に汚染が入る");
            helper.assertTrue(!RingWards.guarded(level, sameSide), "出どころの無い同じ側まで止めている");
            boolean[] got = new boolean[2];
            RingWards.withSource(src, () -> {
                got[0] = RingWards.guarded(level, across);
                got[1] = RingWards.guarded(level, sameSide);
            });
            helper.assertTrue(got[0], "出どころから帯の線をまたぐ所へ置ける");
            helper.assertTrue(!got[1], "同じ側なのに止めた");
            helper.assertTrue(RingWards.crossesBand(level, src, across), "跳ぶ道(線分)が帯をまたいだと分からない");
            // 関門(canCorrupt)にも効く
            helper.assertTrue(!CorruptionRegistry.canCorrupt(level, onLine, Blocks.STONE.defaultBlockState()), "汚染の関門が帯の列を断らない");
            // すり減り: 線分が (3,2,3) を横切り続ける
            BlockPos band = new BlockPos(3, 2, 3);
            int hits = DesignData.pushBack().ring.bandHits;
            int n = 0;
            while (n < hits + 2 && helper.getBlockState(band).getBlock() instanceof AshLimeBandBlock) {
                RingWards.resetCooldownForTests(level);
                RingWards.crossesBand(level, src, across);
                n++;
            }
            // canCorrupt で 1 回すり減った帯が (3,2,3) でないとは限らないので、回数は hits 以下で見る
            helper.assertTrue(!(helper.getBlockState(band).getBlock() instanceof AshLimeBandBlock), "帯が消えない(" + n + " 回)");
            helper.assertTrue(n <= hits && n >= hits - 1, "帯が消えた回数が設計値と違う: " + n + "(期待 " + hits + ")");
            helper.assertTrue(!RingWards.crossesBand(level, src, across), "帯が消えたのにまだ越えられない");
            helper.succeed();
        } finally {
            bandLine(helper, Blocks.AIR.defaultBlockState());
        }
    }

    /** 地脈の杭は FE がある間だけ範囲(stakeRadius)の中を守り、止めるたびに FE を払う。範囲のチャンクでは発芽も受け取らない。FE が尽きると消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void stakeFieldNeedsPower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        BlockPos rel = new BlockPos(3, 2, 3);
        BlockPos near = helper.absolutePos(new BlockPos(5, 1, 5));
        double r = DesignData.pushBack().ring.stakeRadius;
        BlockPos far = helper.absolutePos(new BlockPos(3 + (int) Math.ceil(r) + 2, 1, 3));
        ChunkPos cp = new ChunkPos(helper.absolutePos(rel));
        boolean before = pd.isOvergrownChunk(cp);
        try {
            helper.setBlock(rel, ModPushBack.LEY_STAKE.get().defaultBlockState());
            LeyStakeBlockEntity be = (LeyStakeBlockEntity) helper.getBlockEntity(rel);
            helper.assertTrue(!RingWards.inStakeField(level, near), "FE の無い杭が守っている");
            be.setEnergy(1000);
            helper.assertTrue(RingWards.inStakeField(level, near), "FE のある杭の範囲にならない");
            helper.assertTrue(!RingWards.inStakeField(level, far), "範囲の外まで守っている");
            RingWards.resetCooldownForTests(level);
            int e0 = be.energy();
            helper.assertTrue(RingWards.blocks(level, near), "杭の範囲で汚染を止めない");
            int pay = DesignData.pushBack().ring.stakeFePerBlock;
            helper.assertTrue(e0 - be.energy() == pay, "止めた分の FE が違う: " + (e0 - be.energy()) + "(期待 " + pay + ")");
            // 発芽: 杭のチャンクの中心が範囲なら受け取らない
            if (!before && RingWards.stakeCoversChunk(level, cp.x, cp.z)) {
                long[] rejected = LedgerSpread.acceptFieldSprouts(level, new long[]{cp.toLong()});
                helper.assertTrue(rejected.length == 1 && !pd.isOvergrownChunk(cp), "杭の範囲で発芽を受け取った");
            }
            be.setEnergy(0);
            helper.assertTrue(!RingWards.inStakeField(level, near), "FE が尽きたのに守っている");
            helper.succeed();
        } finally {
            helper.setBlock(rel, Blocks.AIR);
            if (!before) pd.forgetOvergrownChunk(cp);
        }
    }

    // ---------------------------------------------------------------- 段 B: 木がゆっくり燃える

    /** 幹の途中が燃え尽きると、地面から離れた上の部分(原木と葉)は丸ごと燃え落ち、根元の切り株は残る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void burntTrunkDropsTopKeepsStump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState log = ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
        BlockState leaf = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        for (int y = 2; y <= 6; y++) helper.setBlock(new BlockPos(3, y, 3), log);
        helper.setBlock(new BlockPos(2, 6, 3), leaf);
        helper.setBlock(new BlockPos(4, 6, 3), leaf);
        try {
            com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.consumeForTests(level, helper.absolutePos(new BlockPos(3, 3, 3)));
            helper.assertTrue(com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.pendingForTests() >= 4, "地面から離れた上の部分が燃え始めない");
            com.yayuy.overgrownvegetation.corruption.pushback.TreeBurn.finishForTests(level);
            helper.assertTrue(helper.getBlockState(new BlockPos(3, 2, 3)).is(ModBlocks.OVERGROWN_LOG.get()), "切り株が残らない");
            helper.assertTrue(helper.getBlockState(new BlockPos(3, 5, 3)).isAir(), "上の原木が宙に残った");
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 6, 3)).isAir(), "上の葉が宙に残った");
            helper.succeed();
        } finally {
            for (int y = 2; y <= 6; y++) helper.setBlock(new BlockPos(3, y, 3), Blocks.AIR);
        }
    }

    /** 帯の列・杭の範囲にかかる苗木は、木に育たない(苗木のまま)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void saplingNearBandDoesNotGrow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        bandLine(helper, ModPushBack.ASH_LIME_BAND.get().defaultBlockState());
        try {
            helper.assertTrue(RingWards.nearAny(level, helper.absolutePos(new BlockPos(3, 2, 1)), 7), "帯の近くと分からない");
            helper.assertTrue(!RingWards.nearAny(level, helper.absolutePos(new BlockPos(3, 2, -20)), 7), "帯から遠いのに近いと言う");
            helper.succeed();
        } finally {
            bandLine(helper, Blocks.AIR.defaultBlockState());
        }
    }

    // ---------------------------------------------------------------- 壁・天井の帯(11-18 夜 ユーザー「壁とかブロックの底面にも」)

    private static boolean dirty(ServerLevel level, BlockPos p) {
        return com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p));
    }

    /**
     * 洞窟の壁を 26 方向に這う汚染は、トンネルの壁と天井に撒いた帯の線で止まる(手前までは這う)。
     * トンネル(東西に 10・高さ 2・幅 1)の真ん中に、北の壁にくっつく帯と南の壁にくっつく帯を 1 つずつ置いて輪にする。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void caveWallCreepStopsAtWallBand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 630_017, cz = 630_001;
        level.getChunk(cx, cz);
        int x0 = cx * 16 + 2, z0 = cz * 16 + 4, y0 = 130; // 前の実行の箱(y 100 前後)から離す
        // 岩は厚く(外の空気に面した箱の外側の面を伝って帯を回り込まないように。本物の洞窟は岩に囲まれている)
        for (int x = x0 - 2; x <= x0 + 13; x++)
            for (int y = y0 - 2; y <= y0 + 10; y++)
                for (int z = z0 - 2; z <= z0 + 6; z++) {
                    boolean tunnel = x >= x0 + 1 && x <= x0 + 10 && z == z0 + 2 && (y == y0 + 3 || y == y0 + 4);
                    level.setBlock(new BlockPos(x, y, z), tunnel ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                }
        BlockState band = ModPushBack.ASH_LIME_BAND.get().defaultBlockState();
        BlockPos bandHigh = new BlockPos(x0 + 5, y0 + 4, z0 + 2), bandLow = new BlockPos(x0 + 5, y0 + 3, z0 + 2);
        level.setBlock(bandHigh, band.setValue(AshLimeBandBlock.ATTACH, net.minecraft.core.Direction.NORTH), 3);
        level.setBlock(bandLow, band.setValue(AshLimeBandBlock.ATTACH, net.minecraft.core.Direction.SOUTH), 3);
        try {
            helper.assertTrue(level.getBlockState(bandHigh).getBlock() instanceof AshLimeBandBlock
                    && level.getBlockState(bandLow).getBlock() instanceof AshLimeBandBlock, "壁に帯がくっつかない");
            BlockPos start = new BlockPos(x0 + 1, y0 + 2, z0 + 2);
            BlockState st = level.getBlockState(start);
            BlockState corrupted = CorruptionRegistry.corrupt(level, start, st, level.random);
            if (!CorruptionRegistry.noChange(st, corrupted)) CorruptionRegistry.placeCorrupted(level, start, st, corrupted, net.minecraft.world.level.block.Block.UPDATE_ALL);
            helper.assertTrue(dirty(level, start), "テストの前提: 元の床が汚染されない");
            com.yayuy.overgrownvegetation.spore.field.CaveWallCreep.seedForTests(level, start);
            BlockPos before = new BlockPos(x0 + 3, y0 + 2, z0 + 2);
            for (int i = 0; i < 400; i++) {
                com.yayuy.overgrownvegetation.spore.field.CaveWallCreep.runForTests(level, 5);
            }
            helper.assertTrue(dirty(level, before), "帯の手前まで這わない(テストの前提)");
            for (int x = x0 + 4; x <= x0 + 10; x++)
                for (int y = y0 + 2; y <= y0 + 5; y++)
                    for (int z = z0 + 1; z <= z0 + 3; z++)
                        helper.assertTrue(!dirty(level, new BlockPos(x, y, z)), "壁の帯を越えて這った: " + (x - x0) + "," + (y - y0) + "," + (z - z0));
            helper.succeed();
        } finally {
            level.setBlock(bandHigh, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(bandLow, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // ---------------------------------------------------------------- 水浸しのブロック(11-18 夜 ユーザーの不具合報告)

    /**
     * 水浸しの階段・ハーフブロックも汚染される(記録で覆う。置き換えないので水はそのまま残る)。海草など水の中の植物は今までどおり汚染しない。
     * 帯の列の上なら、水浸しでも汚染されない(押し返しの判定はそのまま効く)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void waterloggedStairsAndSlabsGetCovered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var wl = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
        BlockPos stairsRel = new BlockPos(1, 2, 1), slabRel = new BlockPos(3, 2, 1), guardedRel = new BlockPos(5, 2, 1);
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(wl, true);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(wl, true);
        helper.setBlock(stairsRel, stairs);
        helper.setBlock(slabRel, slab);
        helper.setBlock(guardedRel, slab);
        BlockPos bandRel = new BlockPos(5, 3, 1);
        try {
            for (BlockPos rel : new BlockPos[]{stairsRel, slabRel}) {
                BlockPos p = helper.absolutePos(rel);
                BlockState before = level.getBlockState(p);
                helper.assertTrue(CorruptionRegistry.canCorrupt(level, p, before), "水浸しの " + before.getBlock() + " を汚染できない");
                BlockState after = CorruptionRegistry.corrupt(level, p, before, level.random);
                if (!CorruptionRegistry.noChange(before, after)) CorruptionRegistry.placeCorrupted(level, p, before, after, 3);
                BlockState now = level.getBlockState(p);
                helper.assertTrue(dirty(level, p), "水浸しの " + before.getBlock() + " が汚染されない");
                helper.assertTrue(now.getBlock() == before.getBlock(), "置き換わった: " + now);
                helper.assertTrue(now.hasProperty(wl) && now.getValue(wl), "水が消えた: " + now);
            }
            // 海草は今までどおり(水の中の植物を地上の植物にしない)
            BlockPos grass = helper.absolutePos(new BlockPos(1, 2, 5));
            helper.setBlock(new BlockPos(1, 2, 5), Blocks.SEAGRASS);
            helper.assertTrue(!CorruptionRegistry.canCorrupt(level, grass, level.getBlockState(grass)), "海草を汚染できる");
            // 帯の上(帯の列)は水浸しでも汚染されない
            helper.setBlock(new BlockPos(5, 1, 1), Blocks.STONE);
            BlockPos gp = helper.absolutePos(guardedRel);
            helper.setBlock(bandRel, Blocks.AIR);
            helper.setBlock(new BlockPos(5, 2, 2), Blocks.STONE);
            level.setBlock(helper.absolutePos(new BlockPos(5, 2, 3)), ModPushBack.ASH_LIME_BAND.get().defaultBlockState()
                    .setValue(AshLimeBandBlock.ATTACH, net.minecraft.core.Direction.NORTH), 3); // (5,2,2) の石にくっつく = (5,2,1) も 3×3×3 の内
            helper.assertTrue(!CorruptionRegistry.canCorrupt(level, gp, level.getBlockState(gp)), "帯のそばの水浸しのハーフが汚染できる");
            helper.succeed();
        } finally {
            helper.setBlock(new BlockPos(5, 2, 3), Blocks.AIR);
        }
    }
}
