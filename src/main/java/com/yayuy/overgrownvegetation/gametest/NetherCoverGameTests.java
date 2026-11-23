package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherGround;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NetherStageWorker;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
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
 * ネザーの覆い(重ねる苔の絵)のテスト。
 * <ul>
 *     <li>段階 1 の覆いは起点から広がる(2026-11-12): 途中では起点の近くだけ、段階 1 の終わりには約 coverStage1 の割合</li>
 *     <li>段階 3 から、搾られた岩は約 coverStage3(8〜9 割)、泥炭は約 coverPeat の割合で覆われる</li>
 *     <li>Mob などのふだんの汚染が当たった表に無いブロック(グロウストーン)は覆われる</li>
 * </ul>
 * 3×3 チャンクの平らな床(y 100 のネザーラック)を作り、9 チャンクとも同じ起点(真ん中のチャンクの真ん中)で時計を始める。
 * 数えるのは、外の揺らぎ(± 6)が届かない 36×36。斑はかたまりなので広く数える。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherCoverGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterNetherCoverGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(NetherCoverGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int BASE = 22_000;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_SLOT = new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 0〜3999 の置き場所(最初だけ乱数、あとは 1 つずつずらす)。 */
    private static int slot(ServerLevel nether) {
        NEXT_SLOT.compareAndSet(-1, nether.getRandom().nextInt(300)); // 始まりの乱数は狭く(森どうしが遠すぎるとネザーの胞子の場の計算が重くなり、全体が 10 分を超えた)
        return Math.floorMod(NEXT_SLOT.getAndIncrement(), 4000);
    }

    /** 3×3 チャンクの床を作り、9 チャンクとも {@code age} tick 前に、真ん中を起点に時計を始めて当てはめる。真ん中のチャンクを返す。 */
    private static ChunkPos floor(ServerLevel nether, int oz, long age, int steps) {
        return floor(nether, oz, age, steps, Blocks.NETHERRACK);
    }

    /** 床のブロックを選べる版(表に無い立方体の確かめ用)。 */
    private static ChunkPos floor(ServerLevel nether, int oz, long age, int steps, Block ground) {
        // 同じ回の中で置き場所が重ならず、ほかの床から本物の tick で広がる森(GameTest は全速なので数分で数十チャンク)も届かないように 64 チャンクおき
        int ox = BASE + slot(nether) * 64;
        ChunkPos a = new ChunkPos(ox, oz);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) nether.getChunk(a.x + dx, a.z + dz);
        int x0 = a.getMinBlockX(), z0 = a.getMinBlockZ();
        for (int x = x0 - 16; x < x0 + 32; x++) {
            for (int z = z0 - 16; z < z0 + 32; z++) {
                nether.setBlock(new BlockPos(x, 100, z), ground.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 101; y <= 104; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockPos origin = new BlockPos(x0 + 8, 100, z0 + 8);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) NetherLedger.reachForTests(nether, new ChunkPos(a.x + dx, a.z + dz), age, origin);
        long now = nether.getGameTime();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) NetherStageWorker.runForTests(nether, ChunkPos.asLong(a.x + dx, a.z + dz), now, steps);
        return a;
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_cover")
    public static void coverSpreadsFromTheOrigin(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        // 段階 1 の 2 割まで進んだ所: 起点の近くだけ覆われている
        ChunkPos a = floor(nether, BASE, t.stageStartTicks[1] / 5, 2);
        int x0 = a.getMinBlockX() + 8, z0 = a.getMinBlockZ() + 8;
        double near = share(nether, x0 - 5, z0 - 5, x0 + 5, z0 + 5); // 起点そのものは斑の 3 割(覆わない側)に入ることがあるので広めに
        double far = share(nether, x0 + 14, z0 - 3, x0 + 18, z0 + 3);
        if (!(near > 0.03 && far < 0.02)) {
            int roots = 0;
            for (int x = x0 - 5; x <= x0 + 5; x++) for (int z = z0 - 5; z <= z0 + 5; z++) for (int y = 99; y <= 102; y++) if (nether.getBlockState(new BlockPos(x, y, z)).is(ModBlocks.NETHER_ROOT.get())) roots++;
            helper.fail(String.format("途中で起点から広がっていない: 起点の近く %.2f・遠く %.2f(近くの根 %d・起点の床 %s)", near, far, roots, nether.getBlockState(new BlockPos(x0, 100, z0))));
            return;
        }
        // 同じ床で時刻を 20 tick ずつ進めて段階 1 の終わりまで(本物の tick と同じく、見回りは coverSweepTicks ごと): 全体で約 coverStage1
        long now = nether.getGameTime();
        long end = now + t.stageStartTicks[1] - t.stageStartTicks[1] / 5 + t.coverSweepTicks + 40;
        for (long tt = now + 20; tt <= end; tt += 20) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) NetherStageWorker.runForTests(nether, ChunkPos.asLong(a.x + dx, a.z + dz), tt, 1);
        }
        ChunkPos b = a;
        if (NetherLedgerData.get(nether).appliedStage(a.toLong()) < 1) {
            helper.fail("段階 1 の終わりまで時刻を進めても段階 1 の当てはめが終わらない(見回りが止まっている?)");
            return;
        }
        double all = share(nether, b.getMinBlockX() - 10, b.getMinBlockZ() - 10, b.getMinBlockX() + 25, b.getMinBlockZ() + 25);
        if (Math.abs(all - t.coverStage1) > 0.2) {
            helper.fail(String.format("段階 1 の終わりの覆いの割合 %.2f(%.2f ほどのはず)", all, t.coverStage1));
            return;
        }
        // 起点のまわり(coverOriginRadius)は、斑に関係なく必ず覆われている(2026-11-12 ユーザー決定)
        int r = (int) Math.floor(t.coverOriginRadius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > t.coverOriginRadius * t.coverOriginRadius) continue;
                BlockPos p = new BlockPos(x0 + dx, 100, z0 + dz);
                if (!coveredAt(nether, p)) {
                    helper.fail("起点のまわりが覆われていない: " + p);
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * 段階 3 から(2026-11-12 ユーザー決定): 床のネザーラックは全部覆われたネザーラックか泥炭になり(素のネザーラックの四角い穴が残らない)、
     * 泥炭は床の peatStage3 ほど、泥炭の上掛けは薄い(coverPeat)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_cover")
    public static void stage3CoversTheWholeFloor(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        ChunkPos a = floor(nether, BASE + 600, t.stageStartTicks[2] + 10, 400);
        int covered = 0, bare = 0, peat = 0, peatCovered = 0, other = 0;
        for (int x = a.getMinBlockX() - 8; x < a.getMinBlockX() + 24; x++) {
            for (int z = a.getMinBlockZ() - 8; z < a.getMinBlockZ() + 24; z++) {
                BlockPos p = new BlockPos(x, 100, z);
                BlockState s = nether.getBlockState(p);
                if (s.is(ModBlocks.OVERGROWN_NETHERRACK.get())) covered++;
                else if (s.is(Blocks.NETHERRACK) && nether.getBlockState(p.above()).isAir()) bare++; // 上に根が載ったマスは空気に触れないので覆わない(見えない)
                else if (s.is(ModBlocks.NETHER_PEAT.get())) {
                    peat++;
                    if (OvergrownLayerData.isCovered(nether, p)) peatCovered++;
                } else other++;
            }
        }
        if (bare > 0) {
            helper.fail("段階 3 で素のネザーラックが残った: " + bare + " マス(覆われた " + covered + "・泥炭 " + peat + "・ほか " + other + ")");
            return;
        }
        if (covered < 100) {
            helper.fail("段階 3 で床が覆われたネザーラックにならない: " + covered + " マス");
            return;
        }
        double fp = peat / (double) (peat + covered);
        if (Math.abs(fp - t.peatStage3) > 0.1) {
            helper.fail(String.format("段階 3 の床の泥炭の割合 %.2f(%.2f ほどのはず)", fp, t.peatStage3));
            return;
        }
        if (peat >= 30 && peatCovered / (double) peat > t.coverPeat + 0.25) {
            helper.fail(String.format("泥炭の上掛けが薄くない: %.2f(%.2f ほどのはず)", peatCovered / (double) peat, t.coverPeat));
            return;
        }
        helper.succeed();
    }

    /** 段階 3 から、表に無い立方体(玄武岩)の面は 3 割が汎用の過繁茂ブロック、残りが覆い(2026-11-12 ユーザー決定)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_cover")
    public static void stage3TurnsUnlistedCubesIntoOvergrownBlocks(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherSpreadTable t = DesignData.netherSpread();
        ChunkPos a = floor(nether, BASE + 1200, t.stageStartTicks[2] + 10, 400, Blocks.BLACKSTONE);
        int block = 0, overlay = 0, bare = 0;
        for (int x = a.getMinBlockX() - 8; x < a.getMinBlockX() + 24; x++) {
            for (int z = a.getMinBlockZ() - 8; z < a.getMinBlockZ() + 24; z++) {
                BlockPos p = new BlockPos(x, 100, z);
                BlockState s = nether.getBlockState(p);
                if (s.is(ModBlocks.NETHER_OVERGROWN_BLOCK.get())) block++;
                else if (s.is(Blocks.BLACKSTONE)) {
                    if (OvergrownLayerData.isCovered(nether, p)) overlay++;
                    else if (nether.getBlockState(p.above()).isAir()) bare++;
                }
            }
        }
        int all = block + overlay + bare;
        if (all < 500 || bare > 0) {
            helper.fail("段階 3 で表に無い立方体が全部覆われない: 過繁茂ブロック " + block + "・覆い " + overlay + "・そのまま " + bare);
            return;
        }
        double fb = block / (double) all;
        if (Math.abs(fb - t.netherOvergrownBlockShare) > 0.08) {
            helper.fail(String.format("汎用の過繁茂ブロックの割合 %.2f(%.2f ほどのはず)", fb, t.netherOvergrownBlockShare));
            return;
        }
        helper.succeed();
    }

    /** 床が覆われているか: 覆われたブロック(2026-11-12 から表のブロックは置き換える)か、覆い(上掛け)が付いている。 */
    private static boolean coveredAt(ServerLevel level, BlockPos p) {
        return NetherGround.isCoveredGround(level.getBlockState(p)) || OvergrownLayerData.isCovered(level, p);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_cover")
    public static void ordinaryCorruptionCoversUnlistedBlocks(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int ox = BASE + nether.getRandom().nextInt(4000) * 8, oz = BASE + 900;
        NetherLedger.reachForTests(nether, new ChunkPos(ox, oz), 0); // 上限の中にする起点
        ChunkPos target = new ChunkPos(ox + 3, oz);
        nether.getChunk(target.x, target.z);
        BlockPos pos = target.getMiddleBlockPosition(100);
        nether.setBlock(pos, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        nether.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        NoisyCorruptionBurst.single(nether, pos);
        if (!nether.getBlockState(pos).is(Blocks.GLOWSTONE)) {
            helper.fail("表に無いグロウストーンが置き換わった: " + nether.getBlockState(pos));
            return;
        }
        if (!OvergrownLayerData.isCovered(nether, pos)) {
            helper.fail("表に無いブロックが覆われない");
            return;
        }
        if (NetherLedgerData.get(nether).coverOriginOf(target.toLong()) != BlockPos.asLong(pos.getX(), 0, pos.getZ())) {
            helper.fail("ふだんの汚染の覆いの起点が当たった所になっていない");
            return;
        }
        helper.succeed();
    }

    /** 箱 (x1, z1)〜(x2, z2) の床 y 100 のうち覆われている割合。 */
    private static double share(ServerLevel level, int x1, int z1, int x2, int z2) {
        int covered = 0, all = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                all++;
                if (coveredAt(level, new BlockPos(x, 100, z))) covered++;
            }
        }
        return covered / (double) Math.max(1, all);
    }

    @javax.annotation.Nullable
    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("テスト用サーバーにネザーが無い");
        return nether;
    }

    private NetherCoverGameTests() {
    }
}
