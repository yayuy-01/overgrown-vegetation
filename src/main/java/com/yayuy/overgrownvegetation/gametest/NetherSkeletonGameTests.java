package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherFloraHooks;
import com.yayuy.overgrownvegetation.corruption.NetherLava;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NetherStageWorker;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ネザーの汚染の骨組み(2026-11-11。根の台帳と段階・溶岩の海 → 湖)のテスト。
 * <ul>
 *     <li>近くに海が無いと根は隣へ這って海を探し、海に着いたら止まる(その先は胞子)</li>
 *     <li>最初の根はいちばん近い溶岩の海へまっすぐ伸びる</li>
 *     <li>段階は前線が届いてからの時間どおり(設計値から引く)</li>
 *     <li>留守中も前線が進み、読み込まれたら地面が追いつく</li>
 *     <li>湖の縁に黒曜石・丸石・石ができない(土手)。隣も湖になれば土手は水に戻る。湖が広くなったら天を支える樹の口が 1 回呼ばれる</li>
 *     <li>洞窟の下(真上が岩)の溶岩は変わらない</li>
 *     <li>水の下にソウルサンドが残らない(池)</li>
 * </ul>
 * ネザーの遠くのチャンクを使う(回ごとにずらす)。ネザーの台帳など世界に 1 つの記録を触るので専用のバッチ。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherSkeletonGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterNetherSkeletonGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(NetherSkeletonGameTests.class);
        }

        private Registration() {
        }
    }

    /** ほかのネザーのテスト(9_000〜・ポータルの 3_800 付近)と重ならない所。 */
    private static final int BASE = 14_000;

    private static int offset(ServerLevel nether) {
        return nether.getRandom().nextInt(2000) * 8;
    }

    // ------------------------------------------------------------------------------------------
    // 台帳(世界を読まない)
    // ------------------------------------------------------------------------------------------

    /**
     * 半径の中に溶岩の海が無いと、根は隣へ這って海を探す。海に着いたら根は止まる(そこから先は胞子。2026-11-11 ユーザー)。
     * 陸は {@code landTicksPerChunk}、海のチャンクへは {@code seaTicksPerChunk} で進む。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_skeleton")
    public static void rootCreepsUntilTheFirstSea(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 4000;
        int far = t.firstRootSearchChunks + 2; // 探す半径の外の東に海
        int r = far + 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                NetherLedger.setTerrainForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), dx >= far);
            }
        }
        ChunkPos sea = NetherLedger.seedFromPortal(nether, new ChunkPos(ox, oz).getMiddleBlockPosition(64));
        if (sea != null) {
            helper.fail("半径の外の海を最初の根の行き先にした: " + sea);
            return;
        }
        long now = nether.getGameTime();
        long origin = ChunkPos.asLong(ox, oz);
        NetherLedgerData d = NetherLedgerData.get(nether);
        // 陸を far - 1 チャンク這って、海のチャンクへ 1 つ入るまで
        long need = (long) t.landTicksPerChunk * (far - 1) + t.seaTicksPerChunk + 1;
        NetherLedger.spreadForTests(nether, now + need);
        if (!d.isReached(ChunkPos.asLong(ox + far, oz))) {
            helper.fail("根が " + need + " tick で東の海(" + far + " チャンク先)に着かない");
            return;
        }
        if (!d.rootReachedSea(origin)) {
            helper.fail("海に着いたのに根が止まっていない");
            return;
        }
        int reached = d.reachedCount();
        NetherLedger.spreadForTests(nether, now + need * 4);
        if (d.reachedCount() != reached) {
            helper.fail("海に着いた後も根が伸びた: " + reached + " → " + d.reachedCount());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_skeleton")
    public static void firstRootGoesStraightToTheNearestSea(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 6000;
        // まわり 8 チャンクは陸、北東 (6, -6) だけ海
        for (int dx = -t.firstRootSearchChunks - 1; dx <= t.firstRootSearchChunks + 1; dx++) {
            for (int dz = -t.firstRootSearchChunks - 1; dz <= t.firstRootSearchChunks + 1; dz++) {
                NetherLedger.setTerrainForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), dx == 6 && dz == -6);
            }
        }
        BlockPos exit = new ChunkPos(ox, oz).getMiddleBlockPosition(64);
        ChunkPos sea = NetherLedger.seedFromPortal(nether, exit);
        if (sea == null || sea.x != ox + 6 || sea.z != oz - 6) {
            helper.fail("最初の根の行き先が違う: " + sea);
            return;
        }
        long now = nether.getGameTime();
        // 12 チャンクの道を、根の速さで進む
        long need = (long) t.firstRootTicksPerChunk * 12 + 1;
        NetherLedger.spreadForTests(nether, now + need);
        if (!NetherLedgerData.get(nether).isReached(sea.toLong())) {
            helper.fail("最初の根が " + need + " tick で海に着かない");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_skeleton")
    public static void stagesFollowTheClock(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 8000;
        for (int s = 1; s <= 5; s++) {
            ChunkPos p = new ChunkPos(ox + s * 3, oz);
            long age = t.stageStartTicks[s - 1] + 5;
            // 前の回・同じ回のほかの森で届いていたチャンクは古い時刻のままになる(corrupt は届いた所を上書きしない)ので消してから(2026-11-18 揺れるテストの調べ)
            NetherLedgerData.get(nether).forgetForTests(p.toLong());
            NetherLedger.reachForTests(nether, p, age);
            int got = NetherLedger.stageOf(nether, p.toLong());
            if (got != s) {
                helper.fail("届いて " + age + " tick のチャンクが段階 " + got + "(" + s + " のはず)");
                return;
            }
            if (s >= 2) {
                ChunkPos q = new ChunkPos(ox + s * 3, oz + 3);
                NetherLedgerData.get(nether).forgetForTests(q.toLong());
                NetherLedger.reachForTests(nether, q, t.stageStartTicks[s - 1] - 5);
                if (NetherLedger.stageOf(nether, q.toLong()) != s - 1) {
                    helper.fail("段階 " + s + " の始まりより前なのに段階が進んでいる");
                    return;
                }
            }
        }
        // ユーザー決定: 侵入から 1 日、遅くとも 2 日で湖(段階 4)
        if (t.stageStartTicks[3] > 48000) {
            helper.fail("段階 4(湖)の始まりが 2 日より後: " + t.stageStartTicks[3]);
            return;
        }
        helper.succeed();
    }

    /** 誰もいない間も根は伸び(台帳の時刻で進む)、読み込まれたら地面が今の段階まで追いつく。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1200, batch = "nether_skeleton")
    public static void catchesUpWhileAway(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 10000;
        for (int dx = -t.firstRootSearchChunks - 1; dx <= t.firstRootSearchChunks + 1; dx++) {
            for (int dz = -t.firstRootSearchChunks - 1; dz <= t.firstRootSearchChunks + 1; dz++) {
                NetherLedger.setTerrainForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), dx == 8 && dz == 0);
            }
        }
        // 読み込まれていない所で森が始まった。最初の根は東 8 チャンクの海へ
        NetherLedger.seedFromPortal(nether, new ChunkPos(ox, oz).getMiddleBlockPosition(64));
        NetherLedgerData d = NetherLedgerData.get(nether);
        NetherLedger.spreadForTests(nether, nether.getGameTime() + (long) t.firstRootTicksPerChunk * 8 + 1);
        for (int i = 1; i <= 8; i++) {
            if (!d.isReached(ChunkPos.asLong(ox + i, oz))) {
                helper.fail("留守中に最初の根が伸びていない(東 " + i + " チャンク目)");
                return;
            }
        }
        // 0.5 日前に始まったことにして、読み込む(周り 8 チャンクごと、テストの間は読み込んだまま)
        long key = ChunkPos.asLong(ox, oz);
        d.forgetForTests(key);
        NetherLedger.reachForTests(nether, new ChunkPos(ox, oz), 12000);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                nether.setChunkForced(ox + dx, oz + dz, true);
            }
        }
        int want = NetherLedger.stageOf(nether, key);
        NetherStageWorker.activateForTests(key);
        helper.succeedWhen(() -> {
            NetherStageWorker.activateForTests(key);
            int applied = d.appliedStage(key);
            if (applied < want) helper.fail("地面が段階 " + applied + " まで(" + want + " まで追いつくはず)");
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    nether.setChunkForced(ox + dx, oz + dz, false);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // 溶岩の海 → 湖(世界を作って確かめる)
    // ------------------------------------------------------------------------------------------

    /**
     * 東西に並んだ 2 チャンク(A・B)を溶岩の海にする(y 26〜31 が溶岩、32〜36 が空気。外周の列は岸のネザーラック)。
     * A だけ段階 5 まで進め、黒曜石・丸石・石が無いこと・A が湖・境目の B 側が土手(殻)・B の奥は溶岩のままを確かめる。
     * 続けて B も段階 5 まで進め、土手が水に戻ること・天を支える樹の口が 1 回だけ呼ばれることを確かめる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "nether_skeleton")
    public static void lakeEdgeHasNoObsidian(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 12000;
        ChunkPos a = new ChunkPos(ox, oz), b = new ChunkPos(ox + 1, oz);
        loadAround(nether, a, 2);
        force(nether, a, 2, true); // 待つ間に読み込みから外れると当てはめが進まない
        int s = NetherLava.surfaceY(nether);
        int minX = a.getMinBlockX(), maxX = b.getMaxBlockX(), minZ = a.getMinBlockZ(), maxZ = a.getMaxBlockZ();
        buildSea(nether, minX, maxX, minZ, maxZ, s, false);
        NetherFloraHooks.Counter counter = new NetherFloraHooks.Counter();
        NetherTubeTreeGameTests.forgetLedger(nether, ox, oz, 2); // 見直し ④(11-16): 前の回の台帳が残っていると、湖の段が済んだことになる
        NetherFloraHooks.register(counter);
        long now = nether.getGameTime();
        long age5 = t.stageStartTicks[4] + 10;
        NetherLedger.reachForTests(nether, a, age5);
        NetherStageWorker.runForTests(nether, a.toLong(), now, 400);
        helper.runAfterDelay(40, () -> {
            String bad = findBad(nether, minX, maxX, minZ, maxZ, s);
            if (bad != null) {
                NetherFloraHooks.unregister(counter);
                helper.fail("A だけ湖にした後: " + bad);
                return;
            }
            BlockPos mid = new BlockPos(a.getMinBlockX() + 8, s, a.getMinBlockZ() + 8);
            // 管の樹(2026-11-12)が立った列は水に浸かった管(液体は水)
            if (!nether.getFluidState(mid).is(net.minecraft.tags.FluidTags.WATER) || !nether.getFluidState(mid.below()).is(net.minecraft.tags.FluidTags.WATER)
                    || !NetherLava.isCrustForTests(nether.getBlockState(mid.below(2)))) {
                NetherFloraHooks.unregister(counter);
                helper.fail("A の真ん中が水 2 段 + 殻になっていない: " + nether.getBlockState(mid) + " / "
                        + nether.getBlockState(mid.below()) + " / " + nether.getBlockState(mid.below(2)));
                return;
            }
            // 境目は揺らぐ(まだら)ので、決まった列ではなく「水が溶岩に触れていない」= 土手があることを確かめる
            String touch = waterTouchesLava(nether, minX, maxX, minZ, maxZ, s);
            if (touch != null) {
                NetherFloraHooks.unregister(counter);
                helper.fail("土手が無い: " + touch);
                return;
            }
            BlockPos deepB = new BlockPos(b.getMinBlockX() + 8, s, b.getMinBlockZ() + 8);
            if (!nether.getBlockState(deepB).is(Blocks.LAVA)) {
                NetherFloraHooks.unregister(counter);
                helper.fail("まだ森でない B の奥が溶岩のままでない: " + nether.getBlockState(deepB));
                return;
            }
            // B も湖へ
            NetherLedger.reachForTests(nether, b, age5);
            NetherStageWorker.runForTests(nether, b.toLong(), nether.getGameTime(), 400);
            helper.runAfterDelay(40, () -> {
                NetherFloraHooks.unregister(counter);
                force(nether, a, 2, false);
                String bad2 = findBad(nether, minX, maxX, minZ, maxZ, s);
                if (bad2 != null) {
                    helper.fail("B も湖にした後: " + bad2);
                    return;
                }
                String touch2 = waterTouchesLava(nether, minX, maxX, minZ, maxZ, s);
                if (touch2 != null) {
                    helper.fail("土手が無い: " + touch2);
                    return;
                }
                // A と B の境目の帯(揺らぎの届かない真ん中の行)は、土手が残らず水に戻っている
                for (int x = minX + 7; x <= maxX - 7; x++) {
                    for (int z = minZ + 7; z <= minZ + 8; z++) {
                        BlockPos q = new BlockPos(x, s, z);
                        // 管の樹(2026-11-12)が立った列は、水に浸かった管(水の液体)になる
                        if (!nether.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) || !nether.getFluidState(q.below()).is(net.minecraft.tags.FluidTags.WATER)) {
                            long own = com.yayuy.overgrownvegetation.corruption.NetherJitter.owner(nether.getSeed(), q.getX(), q.getZ(), t.edgeJitterBlocks, t.edgeJitterScale);
                            helper.fail("隣も湖になったのに水になっていない(土手が残った?): " + q + " = " + nether.getBlockState(q)
                                    + "・下 " + nether.getBlockState(q.below()) + " / " + nether.getBlockState(q.below(2)) + " / " + nether.getBlockState(q.below(3))
                                    + "・上 " + nether.getBlockState(q.above()) + "・持ち主 " + (own == a.toLong() ? "A" : own == b.toLong() ? "B" : new ChunkPos(own) + "")
                                    + "・A の段階 " + NetherLedgerData.get(nether).appliedStage(a.toLong()) + "・B の段階 " + NetherLedgerData.get(nether).appliedStage(b.toLong()));
                            return;
                        }
                    }
                }
                int cells = NetherLedgerData.get(nether).lakeCellsOf(a.toLong()) + NetherLedgerData.get(nether).lakeCellsOf(b.toLong());
                int want = cells >= t.skyPillarLakeCells ? 1 : 0;
                if (counter.lakes != want) {
                    helper.fail("天を支える樹の口が " + counter.lakes + " 回(湖 " + cells + " マスなら " + want + " 回のはず)");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** 半分が開けた海、半分が岩の天井の下(洞窟の下)の溶岩。段階 5 にしても天井の下の奥は溶岩のまま。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "nether_skeleton")
    public static void lavaUnderCavesStays(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 14000;
        ChunkPos a = new ChunkPos(ox, oz);
        loadAround(nether, a, 1);
        int s = NetherLava.surfaceY(nether);
        int minX = a.getMinBlockX(), maxX = a.getMaxBlockX(), minZ = a.getMinBlockZ(), maxZ = a.getMaxBlockZ();
        buildSea(nether, minX, maxX, minZ, maxZ, s, true);
        long now = nether.getGameTime();
        NetherLedger.reachForTests(nether, a, t.stageStartTicks[4] + 10);
        NetherStageWorker.runForTests(nether, a.toLong(), now, 400);
        helper.runAfterDelay(40, () -> {
            String bad = findBad(nether, minX, maxX, minZ, maxZ, s);
            if (bad != null) {
                helper.fail(bad);
                return;
            }
            // 天井は z が中央より北(minZ+8 以上)。境目から 2 列以上奥は溶岩のまま
            for (int x = minX + 2; x <= maxX - 2; x++) {
                for (int z = minZ + 10; z <= maxZ - 2; z++) {
                    for (int y = s - 2; y <= s; y++) {
                        BlockState st = nether.getBlockState(new BlockPos(x, y, z));
                        if (!st.is(Blocks.LAVA)) {
                            helper.fail("天井の下の溶岩が変わった: " + new BlockPos(x, y, z) + " = " + st);
                            return;
                        }
                    }
                }
            }
            BlockPos open = new BlockPos(minX + 8, s, minZ + 7); // 揺らぎ(± 6)が届いても持ち主がこのチャンクの列
            if (!nether.getFluidState(open).is(net.minecraft.tags.FluidTags.WATER)) { // 管の樹(2026-11-12)が立った列は水に浸かった管
                helper.fail("開けた側が湖になっていない: " + nether.getBlockState(open));
                return;
            }
            helper.succeed();
        });
    }

    /** ソウルサンドの床のくぼみに池ができても、水の下にソウルサンド・ソウルソイルが残らない(泡の柱を出さない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_skeleton")
    public static void noSoulSandUnderWater(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable saved = DesignData.netherSpread();
        int ox = BASE + offset(nether), oz = BASE + 16000;
        ChunkPos a = new ChunkPos(ox, oz);
        loadAround(nether, a, 1);
        int x0 = a.getMinBlockX(), z0 = a.getMinBlockZ();
        // y 100: 全面ソウルサンドの床。y 101: 中に 3×3 のくぼみ(まわりはソウルソイルの縁)。y 102〜104: 空気
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                nether.setBlock(new BlockPos(x, 100, z), Blocks.SOUL_SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
                boolean basin = x >= x0 + 6 && x <= x0 + 8 && z >= z0 + 6 && z <= z0 + 8;
                nether.setBlock(new BlockPos(x, 101, z), basin ? Blocks.AIR.defaultBlockState() : Blocks.SOUL_SOIL.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
                for (int y = 102; y <= 104; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        // 池を必ず張る設計値でまわす
        DesignData.setNetherSpreadForTests(NetherSpreadTable.fromJson(withPond(saved)));
        try {
            NetherLedger.reachForTests(nether, a, saved.stageStartTicks[2] + 10);
            NetherStageWorker.runForTests(nether, a.toLong(), nether.getGameTime(), 200);
        } finally {
            DesignData.setNetherSpreadForTests(saved);
        }
        int water = 0;
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                for (int y = 96; y <= 104; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!nether.getBlockState(p).is(Blocks.WATER)) continue;
                    water++;
                    BlockState below = nether.getBlockState(p.below());
                    if (below.is(Blocks.SOUL_SAND) || below.is(Blocks.SOUL_SOIL)) {
                        helper.fail("水の下にソウルサンドが残った: " + p.below() + " = " + below);
                        return;
                    }
                }
            }
        }
        if (water == 0) {
            helper.fail("くぼみに池ができていない");
            return;
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------

    private static NetherSpreadTable.Json withPond(NetherSpreadTable t) {
        NetherSpreadTable.Json j = new NetherSpreadTable.Json();
        j.pondChance = 1.0;
        j.seaTicksPerChunk = t.seaTicksPerChunk;
        j.landTicksPerChunk = t.landTicksPerChunk;
        double[] days = new double[5];
        for (int i = 0; i < 5; i++) days[i] = t.stageStartTicks[i] / 24000.0;
        j.stageStartDays = days;
        j.maxRadiusChunks = 0;
        return j;
    }

    /**
     * 溶岩の海を作る: y {@code s-5}〜{@code s} が溶岩・{@code s+1}〜{@code s+5} が空気、外周の列は岸(ネザーラック)、その下 {@code s-6} はネザーラック。
     * {@code roofNorth} なら z が中央より北(minZ+8 以降)の列は {@code s+1} をネザーラックの天井にする(洞窟の下の溶岩)。
     */
    private static void buildSea(ServerLevel level, int minX, int maxX, int minZ, int maxZ, int s, boolean roofNorth) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean shore = x == minX || x == maxX || z == minZ || z == maxZ;
                level.setBlock(new BlockPos(x, s - 6, z), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = s - 5; y <= s; y++) {
                    level.setBlock(new BlockPos(x, y, z), shore ? Blocks.NETHERRACK.defaultBlockState() : Blocks.LAVA.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
                boolean roof = roofNorth && z >= minZ + 8;
                for (int y = s + 1; y <= s + 5; y++) {
                    // 岸の列は壁にする(テストの外の自然の溶岩が空気の層へ流れ込まないように)
                    BlockState st = shore || (roof && y == s + 1) ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(new BlockPos(x, y, z), st, Block.UPDATE_CLIENTS);
                }
                // 屋根(s+6): 上の自然の溶岩だまりが湖へ流れ落ちて石・丸石を作らないように(テストの外の物。11-11 にたまに落ちた)
                level.setBlock(new BlockPos(x, s + 6, z), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** 範囲の高さ s と s−1 の水の横・下に溶岩があれば、その場所(土手が無い)。 */
    private static String waterTouchesLava(ServerLevel level, int minX, int maxX, int minZ, int maxZ, int s) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = s - 1; y <= s; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getBlockState(p).is(Blocks.WATER)) continue;
                    for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.NORTH,
                            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.DOWN}) {
                        if (level.getBlockState(p.relative(d)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                            return p + " の " + d + " が溶岩";
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 範囲の y s-6〜s+1 に黒曜石・丸石・石があれば、その場所。 */
    private static String findBad(ServerLevel level, int minX, int maxX, int minZ, int maxZ, int s) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = s - 6; y <= s + 1; y++) {
                    BlockState st = level.getBlockState(new BlockPos(x, y, z));
                    if (st.is(Blocks.OBSIDIAN) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE)) {
                        return new BlockPos(x, y, z) + " に " + st.getBlock().getName().getString();
                    }
                }
            }
        }
        return null;
    }

    private static void force(ServerLevel level, ChunkPos c, int r, boolean on) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                level.setChunkForced(c.x + dx, c.z + dz, on);
            }
        }
    }

    private static void loadAround(ServerLevel level, ChunkPos c, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                level.getChunk(c.x + dx, c.z + dz);
            }
        }
    }

    @javax.annotation.Nullable
    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("テスト用サーバーにネザーが無い");
        }
        return nether;
    }

    private NetherSkeletonGameTests() {
    }
}
