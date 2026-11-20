package com.yayuy.overgrownvegetation.corruption.fissure;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.organ.OrganTissueBlock;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
 * 地下の小さな亀裂(2026-11-20)の確かめ。遠くの専用チャンクに石の塊を組み、中に洞窟の部屋(と地上への縦穴)を掘って使う。
 * <ul>
 *   <li>F3 以下では開かない・F4 から開く・フェーズで数が増えない。</li>
 *   <li>場所は地脈の線の近く。プレイヤーを動かしても場所の選びが変わらない。</li>
 *   <li>地脈の線の近くで、洞窟のある所にだけ開く。汲み上げ器の近くには開かない。</li>
 *   <li>巣の根から地下が繁茂し、口まで届くと地上へあふれ、芯を壊すと止まって枯れる。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FissureGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FissureGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    /** 石の塊の上面(この上は空気)。 */
    private static final int TOP = 79;
    /** 部屋(局所 x, z 4..11・y 50..54)と縦穴(局所 5, 5・y 55..TOP)。 */
    private static final int ROOM_Y0 = 50, ROOM_Y1 = 54;

    // ---------------------------------------------------------------- 場所選び

    @GameTest(template = TEMPLATE, batch = "fissure_a", timeoutTicks = 20)
    public static void phaseGateAndConstantCap(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FissureTable t = DesignData.fissure();
        for (int p = 0; p <= 10; p++) {
            boolean open = FissurePlanner.phaseOpen(p);
            h.assertTrue(open == (p >= 4), "フェーズ " + p + " の開き方が違う: " + open);
            if (p >= 4) h.assertTrue(FissurePlanner.cap(p) == t.plan.maxActive, "フェーズ " + p + " で数が変わった: " + FissurePlanner.cap(p));
        }
        FissureData data = FissureData.get(level);
        int before = data.fissures().size();
        for (int p = 1; p <= 3; p++) {
            String r = FissurePlanner.tryPlan(level, RandomSource.create(p), p, false);
            h.assertTrue(r == null, "F" + p + " で考えた: " + r);
        }
        h.assertTrue(data.fissures().size() == before, "F3 以下で亀裂が増えた");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "fissure_b", timeoutTicks = 20)
    public static void siteNearLeyAndIgnoresPlayers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FissureData data = FissureData.get(level);
        FissurePlanner.testAnchors = List.of(new long[]{11_300_000L, 40_000L});
        // makeMockServerPlayerInLevel は通信の口が無く、ほかの仕組みの送信でサーバーが落ちる(11-20)。FakePlayer で動かす
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(java.util.UUID.fromString("0fe55a2e-0000-4000-8000-00000000f155"), "fissure_test"));
        try {
            int lineBlocks = DesignData.fissure().plan.lineBlocks;
            for (int s = 0; s < 20; s++) {
                int[] site = FissurePlanner.chooseSite(level, data, RandomSource.create(1000 + s));
                h.assertTrue(site != null, "場所が見つからない(種 " + s + ")");
                double d = LeyLines.distanceToLine(level.getSeed(), site[0], site[1]);
                h.assertTrue(d <= lineBlocks, "地脈の線から遠い: " + d);
                double far = Math.hypot(site[0] - 11_300_000.0D, site[1] - 40_000.0D);
                h.assertTrue(far <= DesignData.fissure().plan.reachBlocks + 1, "森が知っている範囲の外: " + far);
            }
            player.teleportTo(level, 11_300_000.5D, 120.0D, 40_000.5D, 0.0F, 0.0F);
            int[] a = FissurePlanner.chooseSite(level, data, RandomSource.create(77));
            player.teleportTo(level, -5_000.5D, 120.0D, 9_000.5D, 0.0F, 0.0F);
            int[] b = FissurePlanner.chooseSite(level, data, RandomSource.create(77));
            h.assertTrue(a != null && b != null && a[0] == b[0] && a[1] == b[1], "プレイヤーを動かすと場所が変わった");
        } finally {
            FissurePlanner.testAnchors = null;
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- 開く所

    @GameTest(template = TEMPLATE, batch = "fissure_c", timeoutTicks = 40)
    public static void opensOnlyInCaveNearLine(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int[] near = chunkByLine(level, 11_400_000, true);
        build(level, near[0], near[1], true, false);
        BlockPos floor = FissureWorld.findCaveFloor(level, near[0], near[1], 5);
        h.assertTrue(floor != null, "線の近くの洞窟に開かない");
        h.assertTrue(floor.getY() == ROOM_Y0, "部屋の床でない: " + floor);
        int[] solid = chunkByLine(level, 11_420_000, true);
        build(level, solid[0], solid[1], false, false);
        h.assertTrue(FissureWorld.findCaveFloor(level, solid[0], solid[1], 5) == null, "洞窟の無い所に開いた");
        int[] far = chunkByLine(level, 11_440_000, false);
        build(level, far[0], far[1], true, false);
        h.assertTrue(FissureWorld.findCaveFloor(level, far[0], far[1], 5) == null, "地脈の線から遠い洞窟に開いた");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "fissure_d", timeoutTicks = 40)
    public static void pumpBlocksOpening(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int[] c = chunkByLine(level, 11_460_000, true);
        build(level, c[0], c[1], true, false);
        BlockPos pump = new BlockPos((c[0] << 4) + 8, TOP + 1, (c[1] << 4) + 8);
        level.setBlock(pump, ModLey.PUMP.get().defaultBlockState(), Block.UPDATE_ALL);
        FissureData data = FissureData.get(level);
        FissureData.Fissure f = data.add((c[0] << 4) + 8, (c[1] << 4) + 8, 9, PhaseData.clockNow(level), 0.0D);
        FissurePlanner.testAnchors = List.of(); // 選び直す場所を出さない(取り消しになる)
        try {
            String r = FissureWorld.resolve(level, data, f);
            h.assertTrue(f.state != FissureData.State.OPEN && f.floor == null, "汲み上げ器の近くに開いた: " + r);
            h.assertTrue(r.contains("汲み上げ器"), "理由が違う: " + r);
            h.assertTrue(data.pumps().contains(pump.asLong()), "汲み上げ器を覚えていない");
        } finally {
            FissurePlanner.testAnchors = null;
            data.remove(f.id);
            level.setBlock(pump, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            data.removePump(pump);
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- 巣・根・口・枯れ

    @GameTest(template = TEMPLATE, batch = "fissure_e", timeoutTicks = 60)
    public static void nestRootsMouthAndWither(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int[] c = chunkByLine(level, 11_480_000, true);
        build(level, c[0], c[1], true, true);
        FissureData data = FissureData.get(level);
        FissureData.Fissure f = data.add((c[0] << 4) + 8, (c[1] << 4) + 8, 4242, PhaseData.clockNow(level), 0.0D);
        FissurePlanner.testAnchors = List.of();
        try {
            String r = FissureWorld.resolve(level, data, f);
            for (int i = 0; i < 400 && r.startsWith("wait:"); i++) r = FissureWorld.resolve(level, data, f); // 口に届くかは 1 回に 1 つずつ試す
            h.assertTrue("opened".equals(r), "開かない: " + r);
            h.assertTrue(f.route != null && f.routeHitsMouth, "開くときに試した道を使っていない");
            h.assertTrue(f.core != null && level.getBlockState(f.core).is(OrganBlocks.ORGAN_CORE.get()), "芯が無い: " + f.core);
            h.assertTrue(level.getBlockState(f.core.above()).is(OrganBlocks.ORGAN_FLESH.get()), "芯の上の栓が無い");
            h.assertTrue(f.core.getY() < ROOM_Y0, "芯が床の下に無い: " + f.core);
            // 根が伸び切るまで時計を進めたことにする
            FissureTable t = DesignData.fissure();
            long now = PhaseData.clockNow(level);
            f.openedAt = now - (long) (t.nest.rootDelayDays * 24000.0D) - (long) t.roots.ticksPerCell * (t.roots.maxLength + 5) - 24000L;
            for (int i = 0; i < 200; i++) {
                int n = FissureWorld.grow(level, data, f, now, 64);
                if (f.reachedMouth()) n += FissureWorld.overflow(level, data, f, now, 64);
                if (n == 0) break;
            }
            h.assertTrue(f.route != null && f.routeHitsMouth, "根の道が口に届かない: " + f.describe());
            h.assertTrue(f.grown == f.route.length, "根が伸び切らない: " + f.describe());
            BlockPos mouth = f.mouth();
            h.assertTrue(mouth != null && mouth.getY() >= TOP, "口が地上に無い: " + mouth);
            int roots = 0, corrupted = 0;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int bx = c[0] << 4, bz = c[1] << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = ROOM_Y0 - 8; y <= TOP + 1; y++) {
                        m.set(bx + x, y, bz + z);
                        BlockState s = level.getBlockState(m);
                        if (s.is(OrganBlocks.TANGLED_ROOT.get())) roots++;
                        else if (!s.isAir() && OvergrownLayerData.isCorruptedCell(level, m, s)) corrupted++;
                    }
                }
            }
            h.assertTrue(roots >= 10, "根が少ない: " + roots);
            h.assertTrue(corrupted >= 30, "地下が繁茂しない(汚染 " + corrupted + ")");
            // 口から地上へ
            h.assertTrue(f.reachedMouth(), "口に着いた時刻が無い");
            int spill = 0;
            for (int i : f.armDone) spill += i;
            h.assertTrue(spill > 0, "口から根があふれない");
            h.assertTrue(f.pointsDone > 0, "地上の汚染の点が無い");
            // 芯を壊す → 止まって枯れる
            int grownBefore = f.grown;
            level.destroyBlock(f.core, false);
            h.assertTrue(f.state == FissureData.State.DEAD, "芯を壊しても枯れない");
            h.assertTrue(FissureWorld.grow(level, data, f, now + 240000L, 64) == 0 || f.state == FissureData.State.DEAD, "枯れた後も伸びる");
            for (int i = 0; i < 100 && f.withered < f.placed.size(); i++) FissureWorld.witherSome(level, data, f, 64);
            int alive = 0, dead = 0;
            for (long p : f.placed) {
                BlockState s = level.getBlockState(BlockPos.of(p));
                if (s.hasProperty(OrganTissueBlock.WITHERED)) {
                    if (s.getValue(OrganTissueBlock.WITHERED)) dead++;
                    else alive++;
                }
            }
            h.assertTrue(alive == 0 && dead > 0, "枯れの姿にならない(生 " + alive + "・枯れ " + dead + ")");
            h.assertTrue(f.grown == grownBefore, "芯を壊した後に根が伸びた");
        } finally {
            FissurePlanner.testAnchors = null;
            data.remove(f.id);
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- 場所

    /** x0 から東へ、真ん中が地脈の線に近い(near)・遠いチャンクを探す。 */
    static int[] chunkByLine(ServerLevel level, int x0, boolean near) {
        long seed = level.getSeed();
        int cz = 2_500;
        for (int i = 0; i < 4000; i++) {
            int cx = (x0 >> 4) + i;
            double d = LeyLines.distanceToLine(seed, (cx << 4) + 8, (cz << 4) + 8);
            if (near ? d <= 4.0D : d >= 70.0D) return new int[]{cx, cz};
        }
        throw new IllegalStateException("地脈の線のチャンクが見つからない");
    }

    /**
     * チャンクを石の塊にする(世界の底の 1 つ上から {@link #TOP} まで。上は空気)。room なら部屋、shaft なら部屋から地上への縦穴。
     * 周り 8 チャンクも読み込む。
     */
    static void build(ServerLevel level, int cx, int cz, boolean room, boolean shaft) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(cx + dx, cz + dz);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int bx = cx << 4, bz = cz << 4;
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinBuildHeight() + 1; y <= TOP + 24; y++) {
                    boolean open = y > TOP
                            || room && x >= 4 && x <= 11 && z >= 4 && z <= 11 && y >= ROOM_Y0 && y <= ROOM_Y1
                            || shaft && x == 5 && z == 5 && y > ROOM_Y1 && y <= TOP;
                    m.set(bx + x, y, bz + z);
                    BlockState want = open ? air : stone;
                    if (level.getBlockState(m) != want) level.setBlock(m, want, flags);
                }
            }
        }
    }
}
