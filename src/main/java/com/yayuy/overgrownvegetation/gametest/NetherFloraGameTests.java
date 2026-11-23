package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LavaFernMatBlock;
import com.yayuy.overgrownvegetation.corruption.NetherLava;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NetherPioneerFlora;
import com.yayuy.overgrownvegetation.corruption.NetherStageWorker;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Tilt;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.function.BiPredicate;

/**
 * ネザーの先駆けの植物のテスト(2026-11-12)。
 * <ul>
 *     <li>根の網の太い根がポータルの出口から溶岩の海までつながり、岸に潜る</li>
 *     <li>枝の根がいちばん熱い隣の向きへ伸びる</li>
 *     <li>覆いの前線が届いた菌の木だけが搾り殺され、シュルームライトの光が消える(蔦と柄だけの柱は残る)</li>
 *     <li>浮き草が岸から沖へ 4〜6 ブロックに生え、乗ると沈んで戻り、殻はその下から先に固まり、湖になると水面に浮く</li>
 * </ul>
 * どれも海面の高さ S のまわりに平らな床(S 以下がネザーラック、上は空気 11 段と屋根、外周に壁)を作って試す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherFloraGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterNetherFloraGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(NetherFloraGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int BASE = 26_000;

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_SLOT = new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 置き場所(チャンク)。同じ回の中で重ならず、ほかの場所から本物の tick で広がる森も届かないように 64 チャンクおき(最初だけ乱数)。 */
    private static int offset(ServerLevel nether) {
        NEXT_SLOT.compareAndSet(-1, nether.getRandom().nextInt(300)); // 始まりの乱数は狭く(森どうしが遠すぎるとネザーの胞子の場の計算が重くなり、全体が 10 分を超えた)
        return Math.floorMod(NEXT_SLOT.getAndIncrement(), 3000) * 64;
    }

    /** チャンク [cx0, cx1] × [cz0, cz1] に床を作る。{@code lava} の列は S〜S-2 が溶岩の源。 */
    private static void build(ServerLevel nether, int cx0, int cz0, int cx1, int cz1, BiPredicate<Integer, Integer> lava) {
        int s = NetherLava.surfaceY(nether);
        for (int cx = cx0 - 1; cx <= cx1 + 1; cx++) for (int cz = cz0 - 1; cz <= cz1 + 1; cz++) nether.getChunk(cx, cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = cx0 << 4, z0 = cz0 << 4, x1 = (cx1 << 4) + 15, z1 = (cz1 << 4) + 15;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || z == z0 || x == x1 || z == z1;
                boolean l = lava.test(x, z);
                for (int y = s - 3; y <= 126; y++) { // 天井はネザーの天井まで埋める(上に自然の大きな洞があると、起点の「いちばん広い床」がテスト場の外になる。11-12 に枝のテストが 2〜3 割落ちた)
                    BlockState st;
                    if (y <= s) st = l && y >= s - 2 ? Blocks.LAVA.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState();
                    else if (y >= s + 12 || edge) st = Blocks.NETHERRACK.defaultBlockState();
                    else st = Blocks.AIR.defaultBlockState();
                    nether.setBlock(p.set(x, y, z), st, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_flora")
    public static void rootRunsFromThePortalToTheLavaSea(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int s = NetherLava.surfaceY(nether);
        int ox = BASE + offset(nether), oz = BASE;
        // 出口のチャンクが前の回・同じ回のほかの森で「もう森」になっていると seedFromPortal は何もしない(null)。使う所の台帳を先に消す(2026-11-18 揺れるテストの調べ)
        NetherTubeTreeGameTests.forgetLedger(nether, ox, oz, t.firstRootSearchChunks + 1);
        for (int dx = -t.firstRootSearchChunks - 1; dx <= t.firstRootSearchChunks + 1; dx++) {
            for (int dz = -t.firstRootSearchChunks - 1; dz <= t.firstRootSearchChunks + 1; dz++) {
                NetherLedger.setTerrainForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), dx == 3 && dz == 0);
            }
        }
        int seaX = ((ox + 3) << 4) + 6;
        build(nether, ox - 1, oz - 1, ox + 4, oz + 1, (x, z) -> x >= seaX);
        BlockPos exit = new BlockPos((ox << 4) + 4, s + 1, (oz << 4) + 8);
        ChunkPos sea = NetherLedger.seedFromPortal(nether, exit);
        if (sea == null || sea.x != ox + 3 || sea.z != oz) {
            helper.fail("最初の根の行き先が違う: " + sea);
            return;
        }
        NetherLedger.spreadForTests(nether, nether.getGameTime() + (long) t.firstRootTicksPerChunk * 4 + 1);
        for (int k = 0; k <= 3; k++) NetherPioneerFlora.growForTests(nether, ChunkPos.asLong(ox + k, oz), 1.0, 1.0);
        // 4 チャンクとも太い根がある
        for (int k = 0; k <= 3; k++) {
            int n = NetherPioneerFlora.pathForTests(nether, ChunkPos.asLong(ox + k, oz), false).length;
            if (n < 6) {
                helper.fail("チャンク " + k + " に太い根が無い(" + n + " ブロック)");
                return;
            }
        }
        // 出口の足もとの根から、6 方向につながった根をたどって、溶岩の岸に潜った根(S より下)に着く
        BlockPos start = null;
        for (int y = s - 1; y <= s + 2 && start == null; y++) {
            if (nether.getBlockState(new BlockPos(exit.getX(), y, exit.getZ())).is(ModBlocks.NETHER_ROOT.get())) start = new BlockPos(exit.getX(), y, exit.getZ());
        }
        if (start == null) {
            helper.fail("ポータルの出口の足もとに根が無い");
            return;
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        q.enqueue(start.asLong());
        seen.add(start.asLong());
        boolean dived = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (!q.isEmpty() && !dived) {
            BlockPos at = BlockPos.of(q.dequeueLong());
            if (at.getY() < s && at.getX() >= seaX - 1) {
                dived = true;
                break;
            }
            for (Direction d : Direction.values()) {
                p.setWithOffset(at, d);
                if (seen.add(p.asLong()) && nether.getBlockState(p).is(ModBlocks.NETHER_ROOT.get())) q.enqueue(p.asLong());
            }
        }
        if (!dived) {
            helper.fail("太い根が出口から溶岩の岸までつながっていない(たどれた根 " + seen.size() + ")");
            return;
        }
        helper.succeed();
    }

    /**
     * 溶岩の海が岩の下の洞にあるとき(11-12 ユーザー決定): 根は最後の地面で地中へ潜り、岩の中を斜めに抜け、洞の天井か壁から溶岩へ垂れる。
     * 床は海面 + 30、洞は海の列の海面 + 1〜8(天井は海面 + 9〜30 の岩)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_flora")
    public static void rootDivesThroughRockIntoACaveSea(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int s = NetherLava.surfaceY(nether);
        int ox = BASE + offset(nether), oz = BASE + 1200;
        // 出口のチャンクが前の回・同じ回のほかの森で「もう森」になっていると seedFromPortal は何もしない(null)。使う所の台帳を先に消す(2026-11-18 揺れるテストの調べ)
        NetherTubeTreeGameTests.forgetLedger(nether, ox, oz, t.firstRootSearchChunks + 1);
        for (int dx = -t.firstRootSearchChunks - 1; dx <= t.firstRootSearchChunks + 1; dx++) {
            for (int dz = -t.firstRootSearchChunks - 1; dz <= t.firstRootSearchChunks + 1; dz++) {
                NetherLedger.setTerrainForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), dx == 3 && dz == 0);
            }
        }
        int seaX = ((ox + 3) << 4) + 12;
        int floor = s + 30, caveTop = s + 8;
        for (int cx = ox - 2; cx <= ox + 5; cx++) for (int cz = oz - 2; cz <= oz + 2; cz++) nether.getChunk(cx, cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = (ox - 1) << 4, z0 = (oz - 1) << 4, x1 = ((ox + 4) << 4) + 15, z1 = ((oz + 1) << 4) + 15;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || z == z0 || x == x1 || z == z1;
                boolean cave = x >= seaX && !edge;
                for (int y = s - 3; y <= floor + 14; y++) {
                    BlockState st;
                    if (y <= s) st = cave && y >= s - 2 ? Blocks.LAVA.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState();
                    else if (cave && y <= caveTop) st = Blocks.AIR.defaultBlockState();
                    else if (y <= floor || y >= floor + 13 || edge) st = Blocks.NETHERRACK.defaultBlockState();
                    else st = Blocks.AIR.defaultBlockState();
                    nether.setBlock(p.set(x, y, z), st, Block.UPDATE_CLIENTS);
                }
            }
        }
        BlockPos exit = new BlockPos((ox << 4) + 4, floor + 1, (oz << 4) + 8);
        ChunkPos sea = NetherLedger.seedFromPortal(nether, exit);
        if (sea == null || sea.x != ox + 3 || sea.z != oz) {
            helper.fail("最初の根の行き先が違う: " + sea);
            return;
        }
        NetherLedger.spreadForTests(nether, nether.getGameTime() + (long) t.firstRootTicksPerChunk * 4 + 1);
        for (int k = 0; k <= 3; k++) NetherPioneerFlora.growForTests(nether, ChunkPos.asLong(ox + k, oz), 1.0, 1.0);
        BlockPos start = null;
        for (int y = floor - 1; y <= floor + 2 && start == null; y++) {
            if (nether.getBlockState(new BlockPos(exit.getX(), y, exit.getZ())).is(ModBlocks.NETHER_ROOT.get())) start = new BlockPos(exit.getX(), y, exit.getZ());
        }
        if (start == null) {
            helper.fail("ポータルの出口の足もとに根が無い");
            return;
        }
        // 出口からつながった根: 溶岩(海面より下)に着き、岩の中(海面 + 9〜床 - 1)では横へも進み(斜め)、洞の中に垂れた根がある
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        q.enqueue(start.asLong());
        seen.add(start.asLong());
        boolean inLava = false, hanging = false;
        IntOpenHashSet rockX = new IntOpenHashSet();
        while (!q.isEmpty()) {
            BlockPos at = BlockPos.of(q.dequeueLong());
            if (at.getY() < s && at.getX() >= seaX) inLava = true;
            if (at.getY() > s && at.getY() <= caveTop && at.getX() >= seaX) hanging = true;
            if (at.getY() > caveTop && at.getY() < floor) rockX.add(at.getX());
            for (Direction d : Direction.values()) {
                p.setWithOffset(at, d);
                if (seen.add(p.asLong()) && nether.getBlockState(p).is(ModBlocks.NETHER_ROOT.get())) q.enqueue(p.asLong());
            }
        }
        if (!inLava || !hanging) {
            helper.fail("潜った根が洞の溶岩に届かない(溶岩 " + inLava + "・洞に垂れた根 " + hanging + "・たどれた根 " + seen.size() + ")");
            return;
        }
        if (rockX.size() < 4) {
            helper.fail("岩の中の根が斜めになっていない(岩の中の根の列の x が " + rockX.size() + " 種類)");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_flora")
    public static void branchesReachForTheHottestNeighbour(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int s = NetherLava.surfaceY(nether);
        int ox = BASE + offset(nether), oz = BASE + 600;
        ChunkPos a = new ChunkPos(ox, oz);
        // 東の隣だけ溶岩(場が動いていても海と覚えさせて熱くする)
        int eastX = (ox + 1) << 4;
        build(nether, ox - 1, oz - 1, ox + 1, oz + 1, (x, z) -> x >= eastX && (z >> 4) == oz);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) NetherLedger.setTerrainForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), dx == 1 && dz == 0);
        BlockPos origin = new BlockPos(a.getMinBlockX() + 8, s, a.getMinBlockZ() + 8);
        NetherLedger.reachForTests(nether, a, 0, origin);
        int[] dir = NetherPioneerFlora.hottestNeighbour(nether, NetherLedgerData.get(nether), DesignData.netherSpread(), a.toLong());
        if (dir == null || dir[0] != 1 || dir[1] != 0) {
            helper.fail("いちばん熱い隣が東にならない: " + (dir == null ? "null" : dir[0] + "," + dir[1]));
            return;
        }
        NetherPioneerFlora.forgetForTests(a.toLong());
        NetherPioneerFlora.growForTests(nether, a.toLong(), 1.0, 1.0);
        long[] branch = NetherPioneerFlora.pathForTests(nether, a.toLong(), true);
        if (branch.length < 8) {
            helper.fail("枝の根が短い: " + branch.length);
            return;
        }
        double sx = 0, sz = 0;
        int placed = 0;
        for (long l : branch) {
            BlockPos b = BlockPos.of(l);
            sx += b.getX() - origin.getX();
            sz += b.getZ() - origin.getZ();
            if (nether.getBlockState(b).is(ModBlocks.NETHER_ROOT.get())) placed++;
        }
        sx /= branch.length;
        sz /= branch.length;
        if (!(sx > 2 && sx > Math.abs(sz) * 1.5)) {
            helper.fail(String.format("枝の根が熱い東へ伸びていない: 平均のずれ x %.1f・z %.1f", sx, sz));
            return;
        }
        if (placed < branch.length / 2) {
            helper.fail("枝の根のブロックが置かれていない: " + placed + "/" + branch.length);
            return;
        }
        helper.succeed();
    }

    // 400: 同じ batch の浮き草のテストが段階 4 まで進めて光る管の樹を立てるので、光の計算が混むことがある(2026-11-12)
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "nether_flora")
    public static void frontStranglesFungusTrees(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int s = NetherLava.surfaceY(nether);
        int ox = BASE + offset(nether), oz = BASE + 1200;
        ChunkPos a = new ChunkPos(ox, oz);
        build(nether, ox - 1, oz - 1, ox + 1, oz + 1, (x, z) -> false);
        int x0 = a.getMinBlockX(), z0 = a.getMinBlockZ();
        // 起点は北西の角。近い木(真紅)と遠い木(歪んだ)と、柄だけの柱
        BlockPos near = new BlockPos(x0 + 3, s + 1, z0 + 3);
        BlockPos far = new BlockPos(x0 + 14, s + 1, z0 + 14);
        BlockPos pillar = new BlockPos(x0 + 4, s + 1, z0 + 9);
        BlockPos light = tree(nether, near, Blocks.CRIMSON_STEM, Blocks.NETHER_WART_BLOCK, true);
        tree(nether, far, Blocks.WARPED_STEM, Blocks.WARPED_WART_BLOCK, false);
        for (int y = 0; y < 3; y++) nether.setBlock(pillar.above(y), Blocks.CRIMSON_STEM.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos vine = near.offset(1, 3, 0); // 傘の下に垂れた蔦
        nether.setBlock(vine, Blocks.WEEPING_VINES.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos twist = far.offset(0, 6, 0); // 歪んだ傘の上に伸びたねじれ蔦(2 段)
        nether.setBlock(twist, Blocks.TWISTING_VINES_PLANT.defaultBlockState(), Block.UPDATE_CLIENTS);
        nether.setBlock(twist.above(), Blocks.TWISTING_VINES.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos groundTwist = far.offset(3, 0, -3); // 地面から伸びるねじれ蔦(傘につながっていない。残る)
        nether.setBlock(groundTwist, Blocks.TWISTING_VINES.defaultBlockState(), Block.UPDATE_CLIENTS);
        NetherLedger.reachForTests(nether, a, 0, new BlockPos(x0 + 1, s, z0 + 1));
        NetherPioneerFlora.forgetForTests(a.toLong());
        // 前線が近い木だけに届いた所
        NetherPioneerFlora.growForTests(nether, a.toLong(), 1.0, 0.28);
        if (!nether.getBlockState(near).is(ModBlocks.STRANGLED_STEM.get())) {
            helper.fail("前線の中の真紅の柄が搾り殺されない: " + nether.getBlockState(near));
            return;
        }
        if (!nether.getBlockState(near.offset(1, 5, 0)).isAir()) {
            helper.fail("傘(ウォートブロック)が消えない: " + nether.getBlockState(near.offset(1, 5, 0)));
            return;
        }
        if (!nether.getBlockState(light).isAir()) {
            helper.fail("シュルームライトが消えない: " + nether.getBlockState(light));
            return;
        }
        if (!nether.getBlockState(vine).isAir()) {
            helper.fail("傘の下の垂れ蔦が消えない(傘が無いと宙に浮く): " + nether.getBlockState(vine));
            return;
        }
        if (!nether.getBlockState(far).is(Blocks.WARPED_STEM)) {
            helper.fail("前線がまだ届いていない遠い木まで搾り殺された");
            return;
        }
        // 段階 1 の終わり: 遠い木も。柄だけの柱は残す
        NetherPioneerFlora.growForTests(nether, a.toLong(), 1.0, 1.0);
        if (!nether.getBlockState(far).is(ModBlocks.STRANGLED_STEM.get())) {
            helper.fail("段階 1 の終わりに歪んだ柄が搾り殺されない: " + nether.getBlockState(far));
            return;
        }
        if (!nether.getBlockState(pillar).is(Blocks.CRIMSON_STEM)) {
            helper.fail("柄だけの柱(人の飾りかもしれない)まで変わった");
            return;
        }
        if (!nether.getBlockState(far.offset(0, 5, 0)).isAir() || !nether.getBlockState(twist).isAir() || !nether.getBlockState(twist.above()).isAir()) {
            helper.fail("歪んだ傘か、その上のねじれ蔦が消えない: " + nether.getBlockState(far.offset(0, 5, 0)) + " / " + nether.getBlockState(twist));
            return;
        }
        if (!nether.getBlockState(groundTwist).is(Blocks.TWISTING_VINES)) {
            helper.fail("傘につながっていない地面のねじれ蔦まで消えた");
            return;
        }
        // 光が消える(光の計算は tick の終わりに進むので少し待つ)
        BlockPos beside = light.below();
        helper.succeedWhen(() -> {
            int lv = nether.getBrightness(LightLayer.BLOCK, beside);
            if (lv != 0) {
                // 調べ用(2026-11-12): まわり 15 の光るブロック
                StringBuilder src = new StringBuilder();
                for (BlockPos q : BlockPos.betweenClosed(beside.offset(-15, -15, -15), beside.offset(15, 15, 15))) {
                    BlockState st = nether.getBlockState(q);
                    int e = st.getLightEmission(nether, q);
                    if (e > 0 && src.length() < 400) src.append(" ").append(st.getBlock().getDescriptionId()).append("@").append(q.subtract(beside).toShortString()).append("=").append(e);
                }
                helper.fail("消えたシュルームライトのまわりがまだ明るい: " + lv + "・まわりの光るブロック:" + src);
            }
        });
    }

    /** 柄の柱(高さ 5)と、上の 3×3×2 のウォート。{@code withLight} なら傘の 1 つをシュルームライトに。シュルームライトの位置を返す。 */
    private static BlockPos tree(ServerLevel level, BlockPos base, Block stem, Block wart, boolean withLight) {
        for (int y = 0; y < 5; y++) level.setBlock(base.above(y), stem.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos lightAt = base.offset(-1, 4, 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 4; dy <= 5; dy++) {
                    if (dx == 0 && dz == 0 && dy == 4) continue;
                    BlockPos p = base.offset(dx, dy, dz);
                    level.setBlock(p, withLight && p.equals(lightAt) ? Blocks.SHROOMLIGHT.defaultBlockState() : wart.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        return lightAt;
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "nether_flora")
    public static void matsGrowOnTheShoreSinkAndFloat(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        int s = NetherLava.surfaceY(nether);
        int ox = BASE + offset(nether), oz = BASE + 1800;
        ChunkPos a = new ChunkPos(ox, oz);
        int x0 = a.getMinBlockX(), z0 = a.getMinBlockZ();
        int shore = x0 + 4; // ここから東が溶岩の海(東の隣のチャンクも)
        build(nether, ox - 1, oz - 1, ox + 1, oz + 1, (x, z) -> x >= shore);
        // 周りの 8 チャンクにも届ける(境目のまだらで、測る範囲の列が隣のチャンクの持ち物になることがある。届いていないと浮き草が生えない。11-12 に 50 回に 1 回落ちていた)
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) NetherLedger.reachForTests(nether, new ChunkPos(ox + dx, oz + dz), 0, new BlockPos(x0 + 2, s, z0 + 8));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            NetherPioneerFlora.forgetForTests(ChunkPos.asLong(ox + dx, oz + dz));
            NetherPioneerFlora.growForTests(nether, ChunkPos.asLong(ox + dx, oz + dz), 1.0, 1.0);
        }
        int mats = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos oneMat = null;
        for (int x = x0 + 1; x <= x0 + 14; x++) {
            for (int z = z0 - 12; z <= z0 + 27; z++) { // 南北の隣のチャンクも育てたので、まだらの揺れが小さくなるよう広く数える
                if (!nether.getBlockState(p.set(x, s + 1, z)).is(ModBlocks.LAVA_FERN_MAT.get())) continue;
                mats++;
                // 浮き草の後に隣のチャンクの枝の根がその下の溶岩に入ったもの(根の上に乗った浮き草)は、岸からの距離を測らない
                if (nether.getBlockState(new BlockPos(x, s, z)).is(ModBlocks.NETHER_ROOT.get())) continue;
                // 岸からの距離 = いちばん近い溶岩でない列(枝の根が海に入るとそこも岸になる)
                int d = 99;
                for (int ddx = -7; ddx <= 7; ddx++) for (int ddz = -7; ddz <= 7; ddz++) {
                    int md = Math.abs(ddx) + Math.abs(ddz);
                    if (md < d && !nether.getBlockState(new BlockPos(x + ddx, s, z + ddz)).is(Blocks.LAVA)) d = md;
                }
                if (d < 1 || d > DesignData.netherSpread().floraMatMax) {
                    helper.fail("浮き草が岸から 1〜6 の外にある: 岸から " + d);
                    return;
                }
                if (oneMat == null && d >= 2 && d <= 4) oneMat = p.immutable();
            }
        }
        if (mats < 10 || oneMat == null) {
            int lava = 0, air = 0, roots = 0;
            for (int x = x0 + 4; x <= x0 + 10; x++) for (int z = z0 + 2; z <= z0 + 13; z++) {
                if (nether.getBlockState(p.set(x, s, z)).is(Blocks.LAVA)) lava++;
                if (nether.getBlockState(p.set(x, s + 1, z)).isAir()) air++;
                if (nether.getBlockState(p.set(x, s, z)).is(ModBlocks.NETHER_ROOT.get()) || nether.getBlockState(p.set(x, s + 1, z)).is(ModBlocks.NETHER_ROOT.get())) roots++;
            }
            helper.fail("岸に浮き草がほとんど生えない: " + mats + "(岸から 7 列: 海面の溶岩 " + lava + "・その上の空気 " + air + "・根 " + roots + "/84・段階 " + NetherLedgerData.get(nether).appliedStage(a.toLong()) + ")");
            return;
        }
        // 乗ると沈む: 上に立った豚で entityInside を呼び、予約された tick を手で進める(ネザーのテストのチャンクでは Mob と予約の tick が動かないことがある)
        final BlockPos mat = oneMat;
        Pig pig = EntityType.PIG.create(nether);
        if (pig == null) {
            helper.fail("豚が作れない");
            return;
        }
        pig.moveTo(mat.getX() + 0.5, mat.getY() + 0.0625, mat.getZ() + 0.5, 0, 0);
        pig.setOnGround(true);
        nether.getBlockState(mat).entityInside(nether, mat, pig);
        if (nether.getBlockState(mat).getValue(LavaFernMatBlock.TILT) != Tilt.UNSTABLE
                || !nether.getBlockTicks().hasScheduledTick(mat, ModBlocks.LAVA_FERN_MAT.get())) {
            helper.fail("乗っても傾き始めない: " + nether.getBlockState(mat));
            return;
        }
        nether.getBlockState(mat).tick(nether, mat, nether.getRandom());
        BlockState sunk = nether.getBlockState(mat);
        if (sunk.getValue(LavaFernMatBlock.TILT) != Tilt.FULL || !sunk.getCollisionShape(nether, mat).isEmpty()) {
            helper.fail("10 tick 後に沈まない(当たり判定が残る): " + sunk);
            return;
        }
        nether.getBlockState(mat).tick(nether, mat, nether.getRandom());
        if (nether.getBlockState(mat).getValue(LavaFernMatBlock.TILT) != Tilt.NONE || nether.getBlockState(mat).getCollisionShape(nether, mat).isEmpty()) {
            helper.fail("沈んだ浮き草が戻らない: " + nether.getBlockState(mat));
            return;
        }
        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    // 段階 2 の最初の 1 歩: 浮き草の下はみな殻、浮き草の無い沖はまだ溶岩
                    long arrival = NetherLedgerData.get(nether).arrivalOf(a.toLong());
                    // 本物の tick が始めた段階 1 の見回りが残っていることがあるので、段階 2 に入るまで 1 歩ずつ(見回りの間隔を空けて)進める
                    long tt = arrival + t.stageStartTicks[1] + 10;
                    for (int i = 0; i < 6 && NetherLedgerData.get(nether).appliedStage(a.toLong()) < 2; i++, tt += t.coverSweepTicks + 1) {
                        NetherStageWorker.runForTests(nether, a.toLong(), tt, 1);
                    }
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    int under = 0, lavaOffshore = 0, offshore = 0;
                    int bad = 0;
                    BlockPos firstBad = null;
                    for (int x = x0 + 1; x <= x0 + 15 + t.edgeJitterBlocks; x++) { // 沖は持ち主のまだらで東の隣へ食い込むことがあるので、隣まで見る
                        for (int z = z0 + 2; z <= z0 + 13; z++) {
                            if (com.yayuy.overgrownvegetation.corruption.NetherJitter.owner(nether.getSeed(), x, z, t.edgeJitterBlocks, t.edgeJitterScale) != a.toLong()) continue; // 境目のまだらで隣のチャンクの列
                            boolean hasMat = nether.getBlockState(m.set(x, s + 1, z)).is(ModBlocks.LAVA_FERN_MAT.get());
                            BlockState below = nether.getBlockState(m.set(x, s, z));
                            if (hasMat && below.is(ModBlocks.NETHER_ROOT.get())) continue; // 隣の枝の根が下に入った浮き草(根の上)は数えない
                            if (hasMat) {
                                under++;
                                if (!NetherLava.isCrustForTests(below)) {
                                    if (firstBad == null) firstBad = m.immutable();
                                    bad++;
                                }
                            } else if (x >= shore + 8) {
                                offshore++;
                                if (below.is(Blocks.LAVA)) lavaOffshore++;
                            }
                        }
                    }
                    if (bad > 0) throw new net.minecraft.gametest.framework.GameTestAssertException("浮き草の下が先に殻にならない: " + bad + "/" + under + " 最初 " + firstBad
                            + "・段階 " + NetherLedgerData.get(nether).appliedStage(a.toLong()) + "・チャンク " + new ChunkPos(firstBad) + " / " + a);
                    if (under == 0) throw new net.minecraft.gametest.framework.GameTestAssertException("殻の後に浮き草が残っていない");
                    if (offshore > 0 && lavaOffshore == 0) throw new net.minecraft.gametest.framework.GameTestAssertException("1 歩で沖まで全部殻になった(浮き草の順が見えない)");
                    // 段階 4 の湖まで: 浮き草は水面(S の水の上)に浮いている
                    NetherStageWorker.runForTests(nether, a.toLong(), arrival + t.stageStartTicks[1] + 10, 200);
                    NetherStageWorker.runForTests(nether, a.toLong(), arrival + t.stageStartTicks[3] + 10, 400);
                    int floating = 0, stranded = 0;
                    for (int x = shore + 1; x <= x0 + 14; x++) {
                        for (int z = z0 + 2; z <= z0 + 13; z++) {
                            if (com.yayuy.overgrownvegetation.corruption.NetherJitter.owner(nether.getSeed(), x, z, t.edgeJitterBlocks, t.edgeJitterScale) != a.toLong()) continue;
                            if (!nether.getBlockState(m.set(x, s + 1, z)).is(ModBlocks.LAVA_FERN_MAT.get())) continue;
                            if (nether.getFluidState(m.set(x, s, z)).is(Fluids.WATER)) floating++;
                            else stranded++;
                        }
                    }
                    if (floating == 0 || floating < stranded) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("湖になっても浮き草が水面に浮いていない: 水の上 " + floating + "・そうでない " + stranded);
                    }
                })
                .thenSucceed();
    }

    @javax.annotation.Nullable
    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("テスト用サーバーにネザーが無い");
        return nether;
    }

    private NetherFloraGameTests() {
    }
}
