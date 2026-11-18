package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.TubeTreePlumeBlock;
import com.yayuy.overgrownvegetation.block.TubeTreePlumeBlock.Pose;
import com.yayuy.overgrownvegetation.block.TubeTreeStemBlock;
import com.yayuy.overgrownvegetation.corruption.NetherJitter;
import com.yayuy.overgrownvegetation.corruption.NetherLava;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NetherStageWorker;
import com.yayuy.overgrownvegetation.corruption.NetherTubeTrees;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * 管の樹のテスト(2026-11-12 ネザー段階 2 の植物 1)。
 * <ul>
 *     <li>段階 2 の殻の上にだけ立ち、高さが決まりどおり(真ん中 4〜9・まわり 3〜8)で、管の先ごとに羽根が 1 つ</li>
 *     <li>近づくと羽根が引っ込み、離れると今の姿へ戻る。開いた羽根は光り、通り抜けられる。管を壊すと管を落とす</li>
 *     <li>段階 4 の湖でも残り、管は湖の底まで伸びて水に浸かる(段階 3 の覆いにも食われない)</li>
 * </ul>
 * 海面の高さ S のまわりに平らな床(S 以下がネザーラック、x0 + 4 から東は S〜S-2 が溶岩の海、上は空気 11 段と屋根)を作って試す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherTubeTreeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(NetherTubeTreeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int BASE = 26_000;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_SLOT = new java.util.concurrent.atomic.AtomicInteger(-1);

    private static int offset(ServerLevel nether) {
        NEXT_SLOT.compareAndSet(-1, nether.getRandom().nextInt(300));
        return Math.floorMod(NEXT_SLOT.getAndIncrement(), 3000) * 64;
    }

    /** 作る場所のまわり r チャンクの台帳の記録(段・樹の数・湖・覆いの起点など)を消す(テストのワールドは使い回すため)。 */
    static void forgetLedger(ServerLevel nether, int ox, int oz, int r) {
        NetherLedgerData d = NetherLedgerData.get(nether);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) d.forgetForTests(ChunkPos.asLong(ox + dx, oz + dz));
        NetherLedger.resetHeapForTests();
    }

    /** チャンク [cx0, cx1] × [cz0, cz1] に床を作る({@code NetherFloraGameTests} と同じ)。{@code lava} の列は S〜S-2 が溶岩の源。 */
    private static void build(ServerLevel nether, int cx0, int cz0, int cx1, int cz1, BiPredicate<Integer, Integer> lava) {
        int s = NetherLava.surfaceY(nether);
        for (int cx = cx0 - 1; cx <= cx1 + 1; cx++) for (int cz = cz0 - 1; cz <= cz1 + 1; cz++) nether.getChunk(cx, cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = cx0 << 4, z0 = cz0 << 4, x1 = (cx1 << 4) + 15, z1 = (cz1 << 4) + 15;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || z == z0 || x == x1 || z == z1;
                boolean l = lava.test(x, z);
                for (int y = s - 3; y <= 126; y++) {
                    BlockState st;
                    if (y <= s) st = l && y >= s - 2 ? Blocks.LAVA.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState();
                    else if (y >= s + 12 || edge) st = Blocks.NETHERRACK.defaultBlockState();
                    else st = Blocks.AIR.defaultBlockState();
                    nether.setBlock(p.set(x, y, z), st, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * {@link #stage2Once} を、真ん中のチャンクに管の樹が 1 本でも立つ場所が出るまで(8 か所まで)試す。群れのノイズ({@link #hasGrove})が濃くても、
     * 升ごとの抽選(SALT_CELL)が全部外れる所や、先に生えた浮き草が升を塞ぐ所(浮き草の上には立てない = 設計どおり)では 1 本も立たない。
     * 場所は乱数で選ぶので、前は 1 割ほど「段階 2 で管の樹が立たない」で落ちていた(2026-11-18 揺れるテストの調べ: 200 本並べて 13 本。直した後 0 本)。
     */
    private static ChunkPos stage2(ServerLevel nether, int oz) {
        ChunkPos a = stage2Once(nether, oz);
        for (int tries = 1; tries < 8 && bases(nether, a).isEmpty(); tries++) a = stage2Once(nether, oz);
        return a;
    }

    /** 3×3 チャンクの床(西 4 列が陸・東が溶岩の海)を作り、全部に前線を届けて段階 2 を終わらせる。真ん中のチャンクを返す。 */
    private static ChunkPos stage2Once(ServerLevel nether, int oz) {
        NetherSpreadTable t = DesignData.netherSpread();
        int s = NetherLava.surfaceY(nether);
        // 群れのまだらは座標で決まり、チャンクごと群れの無い所もある。海の側に濃い群れのある所を選ぶ(2026-11-12)
        int ox = BASE + offset(nether);
        for (int tries = 0; tries < 40 && !hasGrove(nether, ox, oz); tries++) ox = BASE + offset(nether);
        ChunkPos a = new ChunkPos(ox, oz);
        int x0 = a.getMinBlockX(), z0 = a.getMinBlockZ();
        int shore = x0 + 4;
        build(nether, ox - 1, oz - 1, ox + 1, oz + 1, (x, z) -> x >= shore);
        NetherTubeTrees.clearForTests();
        forgetLedger(nether, ox, oz, 2); // 見直し ④(11-16): 前の回の台帳(段・樹の数・記録)が残っていると、段階 2 が済んだことになって樹が立たない
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            NetherLedger.reachForTests(nether, new ChunkPos(ox + dx, oz + dz), 0, new BlockPos(x0 + 2, s, z0 + 8));
        }
        long arrival = NetherLedgerData.get(nether).arrivalOf(a.toLong());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long k = ChunkPos.asLong(ox + dx, oz + dz);
            long tt = arrival + t.stageStartTicks[1] + 10;
            // 段階 1 の見回りを終えてから段階 2(殻の輪と管の樹)を終わらせる
            for (int i = 0; i < 8 && NetherLedgerData.get(nether).appliedStage(k) < 2; i++, tt += t.coverSweepTicks + 1) {
                NetherStageWorker.runForTests(nether, k, tt, 1);
            }
            NetherStageWorker.runForTests(nether, k, tt, 400);
        }
        return a;
    }

    /** チャンク (cx, cz) の海の側(x0 + 6 から東)に、群れのまだらが濃い(0.6 以上の)候補の升があるか。 */
    private static boolean hasGrove(ServerLevel nether, int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        int good = 0;
        for (int x = x0 + 7; x <= x0 + 15; x += 3) for (int z = z0 + 1; z <= z0 + 15; z += 3) {
            if (NetherTubeTrees.groveForTests(nether, x, z) >= 0.6) good++;
        }
        return good >= 4;
    }

    /** そのチャンクが持ち主の列の、根元の管(base)の位置。 */
    private static List<BlockPos> bases(ServerLevel nether, ChunkPos a) {
        NetherSpreadTable t = DesignData.netherSpread();
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int s = NetherLava.surfaceY(nether);
        for (int x = a.getMinBlockX() - t.edgeJitterBlocks; x <= a.getMaxBlockX() + t.edgeJitterBlocks; x++) {
            for (int z = a.getMinBlockZ() - t.edgeJitterBlocks; z <= a.getMaxBlockZ() + t.edgeJitterBlocks; z++) {
                if (NetherJitter.owner(nether.getSeed(), x, z, t.edgeJitterBlocks, t.edgeJitterScale) != a.toLong()) continue;
                for (int y = s - 3; y <= s + 2; y++) {
                    BlockState st = nether.getBlockState(p.set(x, y, z));
                    if (st.is(ModBlocks.TUBE_TREE_STEM.get()) && st.getValue(TubeTreeStemBlock.BASE)) out.add(p.immutable());
                }
            }
        }
        return out;
    }

    /** 根元から上へ、管の高さ(羽根が無ければ -1)。 */
    private static int height(ServerLevel nether, BlockPos base) {
        int h = 0;
        BlockPos.MutableBlockPos p = base.mutable();
        while (nether.getBlockState(p).is(ModBlocks.TUBE_TREE_STEM.get())) {
            h++;
            p.move(0, 1, 0);
        }
        return nether.getBlockState(p).is(ModBlocks.TUBE_TREE_PLUME.get()) ? h : -1;
    }

    // ------------------------------------------------------------------------------------------

    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_tube")
    public static void tubeTreesStandOnTheCrust(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        ChunkPos a = stage2(nether, BASE + 2400);
        int s = NetherLava.surfaceY(nether);
        List<BlockPos> bases = bases(nether, a);
        int trees = NetherLedgerData.get(nether).tubesOf(a.toLong());
        if (trees <= 0 || bases.size() < trees) {
            helper.fail("段階 2 の殻の上に管の樹が立たない: 樹 " + trees + "・根元 " + bases.size() + "・段階 " + NetherLedgerData.get(nether).appliedStage(a.toLong()));
            return;
        }
        int tall = 0;
        Pose want = NetherTubeTrees.desiredPose(nether);
        for (BlockPos b : bases) {
            BlockState under = nether.getBlockState(b.below());
            // 隣のチャンクの枝の根が、後から管の下の殻へ入ることがある(管は根の上に立ったまま)
            if (!NetherLava.isCrustForTests(under) && !under.is(ModBlocks.NETHER_ROOT.get())) {
                helper.fail("殻でない所に管が立った: " + b + " の下 " + under);
                return;
            }
            if (b.getX() < a.getMinBlockX() + 4) {
                helper.fail("陸(溶岩の無かった所)に管が立った: " + b);
                return;
            }
            int h = height(nether, b);
            if (h < t.tubeSideMin || h > t.tubeMiddleMax) {
                helper.fail("管の高さが 3〜9 の外(または羽根が無い): " + b + " 高さ " + h + " [列] " + java.util.stream.IntStream.rangeClosed(-2, 11).mapToObj(k -> (k == 0 ? "*" : "") + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(nether.getBlockState(b.above(k)).getBlock()).getPath() + (nether.getBlockState(b.above(k)).hasProperty(com.yayuy.overgrownvegetation.block.TubeTreeStemBlock.BASE) && nether.getBlockState(b.above(k)).getValue(com.yayuy.overgrownvegetation.block.TubeTreeStemBlock.BASE) ? "(B)" : "")).collect(java.util.stream.Collectors.joining(",")) + " 列の持ち主 " + new ChunkPos(NetherJitter.owner(nether.getSeed(), b.getX(), b.getZ(), DesignData.netherSpread().edgeJitterBlocks, DesignData.netherSpread().edgeJitterScale)) + " 真ん中 " + a);
                return;
            }
            if (h >= t.tubeMiddleMin) tall++;
            BlockState plume = nether.getBlockState(b.above(h));
            if (plume.getValue(TubeTreePlumeBlock.POSE) != want) {
                helper.fail("羽根が今の姿でない: " + plume + "(今は " + want + ")");
                return;
            }
        }
        if (tall < trees) {
            helper.fail("真ん中の管(4 以上)が樹の数より少ない: " + tall + " < " + trees);
            return;
        }
        if (b(nether, a, s) > 0) {
            helper.fail("殻になっていない海の表面が残っている(管の樹の前に殻が終わっていない)");
            return;
        }
        helper.succeed();
    }

    /** 持ち主の列で、まだ溶岩の海の表面の数。 */
    private static int b(ServerLevel nether, ChunkPos a, int s) {
        NetherSpreadTable t = DesignData.netherSpread();
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = a.getMinBlockX(); x <= a.getMaxBlockX(); x++) {
            for (int z = a.getMinBlockZ(); z <= a.getMaxBlockZ(); z++) {
                if (NetherJitter.owner(nether.getSeed(), x, z, t.edgeJitterBlocks, t.edgeJitterScale) != a.toLong()) continue;
                // 真上が空いている溶岩だけ(根などの下の溶岩は殻にならない決まり)
                if (nether.getBlockState(p.set(x, s, z)).is(Blocks.LAVA) && nether.getBlockState(p.set(x, s + 1, z)).isAir()) n++;
            }
        }
        return n;
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_tube")
    public static void plumesRetractNearAPlayerAndReturn(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int s = NetherLava.surfaceY(nether);
        int ox = BASE + offset(nether), oz = BASE + 3000;
        build(nether, ox, oz, ox, oz, (x, z) -> false);
        NetherTubeTrees.clearForTests();
        forgetLedger(nether, ox, oz, 1);
        BlockPos floor = new BlockPos((ox << 4) + 8, s, (oz << 4) + 8);
        nether.setBlock(floor, ModBlocks.COOLED_CRUST.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockState stem = ModBlocks.TUBE_TREE_STEM.get().defaultBlockState();
        for (int k = 1; k <= 5; k++) nether.setBlock(floor.above(k), stem.setValue(TubeTreeStemBlock.BASE, k == 1), Block.UPDATE_CLIENTS);
        BlockPos plume = floor.above(6);
        nether.setBlock(plume, ModBlocks.TUBE_TREE_PLUME.get().defaultBlockState().setValue(TubeTreePlumeBlock.POSE, Pose.OPEN), Block.UPDATE_CLIENTS);
        // 開いた羽根は光り、通り抜けられる
        BlockState open = nether.getBlockState(plume);
        if (open.getLightEmission(nether, plume) <= 0 || !open.getCollisionShape(nether, plume).isEmpty()) {
            helper.fail("開いた羽根が光らない・通り抜けられない: 光 " + open.getLightEmission(nether, plume));
            return;
        }
        // 水平 6 ブロック先(半径 4 の外)では引っ込まない
        if (NetherTubeTrees.retractAround(nether, plume.getX() + 6.5, s + 1, plume.getZ() + 0.5, 4) != 0) {
            helper.fail("半径の外で羽根が引っ込んだ");
            return;
        }
        // 水平 3 ブロック・地面に立っていれば引っ込む(羽根は 6 段上でも)
        int n = NetherTubeTrees.retractAround(nether, plume.getX() + 3.5, s + 1, plume.getZ() + 0.5, 4);
        BlockState r = nether.getBlockState(plume);
        if (n != 1 || r.getValue(TubeTreePlumeBlock.POSE) != Pose.RETRACTED || r.getLightEmission(nether, plume) != 0) {
            helper.fail("近づいても羽根が引っ込まない: " + r + "(引っ込めた数 " + n + ")");
            return;
        }
        // テストのネザーにプレイヤーはいないので、離れたことになって今の姿へ戻る
        NetherTubeTrees.returnAway(nether, 4);
        Pose want = NetherTubeTrees.desiredPose(nether);
        if (nether.getBlockState(plume).getValue(TubeTreePlumeBlock.POSE) != want) {
            helper.fail("離れても羽根が戻らない: " + nether.getBlockState(plume) + "(今は " + want + ")");
            return;
        }
        // 管を壊すと管を落とす(飾り)。羽根は何も落とさない
        List<ItemStack> drops = Block.getDrops(nether.getBlockState(floor.above(2)), nether, floor.above(2), null);
        if (drops.isEmpty() || !drops.get(0).is(ModBlocks.TUBE_TREE_STEM.get().asItem())) {
            helper.fail("管を壊しても管を落とさない: " + drops);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "nether_tube")
    public static void tubeTreesStayUnderTheLake(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        ChunkPos a = stage2(nether, BASE + 3600);
        int s = NetherLava.surfaceY(nether);
        List<BlockPos> before = bases(nether, a);
        if (before.isEmpty()) {
            helper.fail("段階 2 で管の樹が立たない" + " [診断] チャンク " + a + " 群れ " + hasGrove(nether, a.x, a.z) + " 段 " + NetherLedgerData.get(nether).appliedStage(a.toLong()) + " 届いた " + NetherLedgerData.get(nether).arrivalOf(a.toLong()) + " 今 " + nether.getGameTime() + " 床 " + nether.getBlockState(new BlockPos(a.getMinBlockX() + 8, s, a.getMinBlockZ() + 8)) + " 上 " + nether.getBlockState(new BlockPos(a.getMinBlockX() + 8, s + 1, a.getMinBlockZ() + 8)) + " 海側 " + nether.getBlockState(new BlockPos(a.getMinBlockX() + 10, s, a.getMinBlockZ() + 8)));
            return;
        }
        int plumesBefore = 0;
        for (BlockPos b : before) if (height(nether, b) > 0) plumesBefore++;
        // 段階 3(覆い)→ 段階 4(湖)→ 段階 5(残りも湖に)まで
        long arrival = NetherLedgerData.get(nether).arrivalOf(a.toLong());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            NetherStageWorker.runForTests(nether, ChunkPos.asLong(a.x + dx, a.z + dz), arrival + t.stageStartTicks[4] + 10, 600);
        }
        List<BlockPos> after = bases(nether, a);
        int plumes = 0, soaked = 0;
        for (BlockPos b : after) {
            int h = height(nether, b);
            if (h <= 0) {
                helper.fail("湖の後に管が途切れた(羽根まで続かない): " + b + " [列] " + java.util.stream.IntStream.rangeClosed(-2, 11).mapToObj(k -> (k == 0 ? "*" : "") + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(nether.getBlockState(b.above(k)).getBlock()).getPath() + (nether.getBlockState(b.above(k)).hasProperty(com.yayuy.overgrownvegetation.block.TubeTreeStemBlock.BASE) && nether.getBlockState(b.above(k)).getValue(com.yayuy.overgrownvegetation.block.TubeTreeStemBlock.BASE) ? "(B)" : "")).collect(java.util.stream.Collectors.joining(",")) + " 列の持ち主 " + new ChunkPos(NetherJitter.owner(nether.getSeed(), b.getX(), b.getZ(), DesignData.netherSpread().edgeJitterBlocks, DesignData.netherSpread().edgeJitterScale)) + " 真ん中 " + a);
                return;
            }
            plumes++;
            if (b.getY() <= s) {
                // 湖の底まで伸びた管: 根元から S までは水に浸かり、根元の下は殻
                for (int y = b.getY(); y <= s; y++) {
                    BlockState st = nether.getBlockState(new BlockPos(b.getX(), y, b.getZ()));
                    if (!st.getValue(TubeTreeStemBlock.WATERLOGGED)) {
                        helper.fail("水の中の管が水に浸かっていない: " + b.atY(y));
                        return;
                    }
                }
                if (!NetherLava.isCrustForTests(nether.getBlockState(b.below()))) {
                    helper.fail("湖の底の管の下が殻でない: " + nether.getBlockState(b.below()));
                    return;
                }
                soaked++;
            }
        }
        if (soaked == 0) {
            helper.fail("湖になっても水に浸かった管の樹が無い(管の樹 " + plumes + ")");
            return;
        }
        if (plumes < plumesBefore) {
            helper.fail("段階 3〜5 で管の樹が減った: " + plumesBefore + " → " + plumes);
            return;
        }
        helper.succeed();
    }

    @javax.annotation.Nullable
    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("テスト用サーバーにネザーが無い");
        return nether;
    }

    private NetherTubeTreeGameTests() {
    }
}
