package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.EndOvergrowth;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.PortalCorruption;
import com.yayuy.overgrownvegetation.corruption.PortalCorruptionData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * ポータルを越えた侵食({@link PortalCorruption}、2026-10-02)のテスト。
 *
 * <p>守りたいのは 4 つ。
 * <ul>
 *     <li><b>火の付いたネザーポータルの枠は壊さない</b> —— 汚染が届いても黒曜石のまま覆うだけ(置き換えるとポータルが消える)。</li>
 *     <li><b>枠に届いたらネザーの出口のまわりが汚染され、両端を覚える</b> —— もう一度届いても積み直さない。</li>
 *     <li><b>エンドポータルの枠の隣が汚染されたら、森がエンドに着く</b> —— 目の無い枠でも(既定値)。</li>
 *     <li><b>ネザーで過繁茂になったチャンクは台帳に入らない</b> —— 入ると同じ座標のオーバーワールドが極相化する。</li>
 * </ul>
 *
 * <p>どれも世界に 1 つの記録を触るので、それぞれ専用のバッチ。テスト場ではなく遠くの専用チャンクを使う。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PortalCorruptionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterPortalCorruptionGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(PortalCorruptionGameTests.class);
        }

        private Registration() {
        }
    }

    /** {@link OvergrownHuskGameTests}(30_100〜)と重ならない列。 */
    private static final int TEST_CHUNK = 30_600;

    /** 汚染が火の付いたネザーポータルの枠に届くと、枠は黒曜石のまま覆われ、ネザーの出口のまわりが汚染される。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "portal_nether")
    public static void litNetherFrameSeedsTheNether(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("テスト用サーバーにネザーが無い");
            return;
        }
        ChunkPos chunk = new ChunkPos(TEST_CHUNK, TEST_CHUNK);
        level.getChunk(chunk.x, chunk.z);
        int x0 = chunk.getMinBlockX() + 6;
        int y0 = 100;
        int z0 = chunk.getMinBlockZ() + 8;
        // 内側 2 × 3 の最小の枠(X 軸向き)。
        for (int dx = 0; dx <= 3; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                boolean border = dx == 0 || dx == 3 || dy == 0 || dy == 4;
                BlockState state = border ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
                level.setBlock(new BlockPos(x0 + dx, y0 + dy, z0), state, Block.UPDATE_CLIENTS);
            }
        }
        BlockPos inner = new BlockPos(x0 + 1, y0 + 1, z0);
        PortalShape.findEmptyPortalShape(level, inner, Direction.Axis.X).ifPresent(PortalShape::createPortalBlocks);
        if (!level.getBlockState(inner).is(Blocks.NETHER_PORTAL)) {
            helper.fail("テスト用のネザーポータルに火が付かなかった");
            return;
        }
        // テスト用のワールドは run/world に残るので、前の回の記録(侵食済みのポータル)を消してから始める。
        PortalCorruption.reset(server);

        // 1. 枠の下の黒曜石を、ふだんの汚染と同じ経路で汚染する。
        BlockPos frame = new BlockPos(x0 + 1, y0, z0);
        boolean changed = NoisyCorruptionBurst.single(level, frame);
        if (changed || !level.getBlockState(frame).is(Blocks.OBSIDIAN)) {
            helper.fail("ポータルの枠が置き換えられた: " + level.getBlockState(frame));
            return;
        }
        if (!OvergrownLayerData.isCovered(level, frame)) {
            helper.fail("ポータルの枠が覆われていない");
            return;
        }
        if (!level.getBlockState(inner).is(Blocks.NETHER_PORTAL)) {
            helper.fail("ポータルが消えた");
            return;
        }
        if (PortalCorruption.queuedCount() != 1) {
            helper.fail("ポータルが待ち行列に 1 件積まれていない: " + PortalCorruption.queuedCount());
            return;
        }

        // 2. 向こう側へ伝える。
        List<PortalCorruption.Result> results = PortalCorruption.processAllNow(server);
        GlobalPos from = GlobalPos.of(Level.OVERWORLD, inner);
        PortalCorruption.Result result = results.stream().filter(r -> r.from().equals(from)).findFirst().orElse(null);
        if (result == null) {
            helper.fail("向こう側へ伝わらなかった: " + results);
            return;
        }
        if (result.to().dimension() != Level.NETHER) {
            helper.fail("出口がネザーではない: " + result.to());
            return;
        }
        BlockPos exit = result.to().pos();
        int expectX = x0 / 8;
        int expectZ = z0 / 8;
        if (Math.abs(exit.getX() - expectX) > 24 || Math.abs(exit.getZ() - expectZ) > 24) {
            helper.fail("ネザーの出口が座標の 1/8 から遠い: " + exit + "(期待 " + expectX + ", " + expectZ + ")");
            return;
        }
        if (!nether.getBlockState(exit).is(Blocks.NETHER_PORTAL)) {
            helper.fail("ネザーの出口にポータルが無い: " + nether.getBlockState(exit));
            return;
        }
        // 2026-10-23 胞子の場 F: 出口に汚染の球は置かない。両端の対を覚え、以後は胞子が染み出す(PortalSeepage)
        PortalCorruptionData data = PortalCorruptionData.get(server);
        if (!data.isInfected(result.from()) || !data.isInfected(result.to())) {
            helper.fail("両端が侵食済みとして覚えられていない");
            return;
        }
        long[] pairs = data.pairs();
        boolean paired = false;
        for (int i = 0; i + 1 < pairs.length; i += 2) paired |= pairs[i + 1] == exit.asLong();
        if (!paired) {
            helper.fail("ポータルの対が覚えられていない");
            return;
        }

        // 3. もう一度届いても積み直さない。
        NoisyCorruptionBurst.single(level, new BlockPos(x0 + 2, y0, z0));
        if (PortalCorruption.queuedCount() != 0) {
            helper.fail("侵食済みのポータルがまた積まれた");
            return;
        }
        helper.succeed();
    }

    /**
     * 目の無いエンドポータルの輪(12 個)の下の石レンガが汚染されると、輪が光る腫瘍の枠に置き換わって開き、
     * 森がエンドに着き、足場のまわりが汚染される。2 つ目の輪は開くが、エンドへ着くのは一度だけ。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "portal_end")
    public static void endFrameNeighbourReachesTheEnd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            helper.fail("テスト用サーバーにエンドが無い");
            return;
        }
        // テスト用のワールドは run/world に残るので、前の回の「エンドに着いた」を消してから始める。
        PortalCorruption.reset(server);
        PortalCorruptionData data = PortalCorruptionData.get(server);
        if (data.endReached()) {
            helper.fail("テストの前から森がエンドに着いたことになっている");
            return;
        }
        ChunkPos chunk = new ChunkPos(TEST_CHUNK + 4, TEST_CHUNK);
        level.getChunk(chunk.x, chunk.z);
        BlockPos ringCorner = new BlockPos(chunk.getMinBlockX() + 2, 101, chunk.getMinBlockZ() + 2);
        buildRing(level, ringCorner);
        BlockPos floor = ringCorner.offset(2, -1, 0); // 北の辺の真ん中の枠の下
        PortalCorruption.processAllNow(server);

        if (!NoisyCorruptionBurst.single(level, floor)) {
            helper.fail("枠の下の石レンガが汚染されなかった");
            return;
        }
        if (PortalCorruption.queuedCount() != 2) {
            helper.fail("輪とエンド行きが待ち行列に積まれていない: " + PortalCorruption.queuedCount());
            return;
        }
        List<PortalCorruption.Result> results = PortalCorruption.processAllNow(server);
        if (results.size() != 2 || results.get(1).to().dimension() != Level.END) {
            helper.fail("輪を開いてエンドへ伝わらなかった: " + results);
            return;
        }
        String ring = checkRingOpened(level, ringCorner);
        if (ring != null) {
            helper.fail(ring);
            return;
        }
        if (!data.endReached()) {
            helper.fail("森がエンドに着いたことが記録されていない");
            return;
        }
        if (data.dragonKilledBeforeEnd()) {
            helper.fail("新しいワールドなのにエンドラが倒されていたことになっている");
            return;
        }
        // 本島のチャンクが塗る順番待ちに積まれる(出口の足場に球を置くのは 10-02 にやめた)。全部塗ると
        // 200 チャンクほど作ることになって重いので、祭壇のチャンクと柱の輪のチャンクだけ塗って確かめる。
        if (EndOvergrowth.queuedCount() < EndOvergrowth.islandChunks().size()) {
            PortalCorruption.reset(server);
            helper.fail("本島のチャンクが積まれていない: " + EndOvergrowth.queuedCount() + " / " + EndOvergrowth.islandChunks().size());
            return;
        }
        for (ChunkPos islandChunk : List.of(new ChunkPos(0, 0), new ChunkPos(2, 0))) {
            String island = checkIslandChunk(end, islandChunk);
            if (island != null) {
                PortalCorruption.reset(server);
                helper.fail(island);
                return;
            }
        }
        // 2 つ目の輪: 開くが、エンドへ着くのは一度だけ(エンド行きは積まれない)。
        BlockPos ring2 = ringCorner.east(8);
        buildRing(level, ring2);
        NoisyCorruptionBurst.single(level, ring2.offset(2, -1, 0));
        if (PortalCorruption.queuedCount() != 1) {
            helper.fail("2 つ目の輪だけが積まれていない: " + PortalCorruption.queuedCount());
            return;
        }
        PortalCorruption.processAllNow(server);
        String ring2Result = checkRingOpened(level, ring2);
        if (ring2Result != null) {
            helper.fail("2 つ目: " + ring2Result);
            return;
        }
        // 残りの本島をテスト用サーバーの裏で塗り始めないよう、記録と順番待ちを消しておく。
        PortalCorruption.reset(server);
        helper.succeed();
    }

    /**
     * 本島の 1 チャンクを塗って確かめる: バイオームが過繫茂したエンド・列の一番上のエンドストーンが残っていない・
     * 黒曜石(柱)はそのまま・祭壇のまわりに植物が無い。
     */
    private static String checkIslandChunk(ServerLevel end, ChunkPos pos) {
        end.getChunk(pos.x, pos.z);
        int obsidianBefore = countIn(end, pos, Blocks.OBSIDIAN);
        EndOvergrowth.convertChunk(end, pos);
        if (!end.getBiome(pos.getMiddleBlockPosition(60)).is(ModBiomes.OVERGROWN_END)) {
            return pos + " のバイオームが過繫茂したエンドになっていない: " + end.getBiome(pos.getMiddleBlockPosition(60)).unwrapKey();
        }
        if (countIn(end, pos, ModBlocks.OVERGROWN_END_STONE.get()) == 0) {
            return pos + " に過繁茂したエンドストーンが無い(本島の外?)";
        }
        if (countIn(end, pos, Blocks.OBSIDIAN) != obsidianBefore) {
            return pos + " の黒曜石が書き換わった";
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                int top = end.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                // 一番上が植物なら、その下の地面を見る。
                at.set(x, top, z);
                while (at.getY() > end.getMinBuildHeight() && !end.getBlockState(at).isCollisionShapeFullBlock(end, at)) {
                    at.move(0, -1, 0);
                }
                if (end.getBlockState(at).is(Blocks.END_STONE)) {
                    return "列の一番上にエンドストーンが残っている: " + at.immutable();
                }
                if (x * x + z * z <= 100 && top > at.getY()) {
                    return "祭壇のまわりに植物が生えた: " + new BlockPos(x, top, z) + " " + end.getBlockState(new BlockPos(x, top, z));
                }
            }
        }
        return null;
    }

    private static int countIn(ServerLevel level, ChunkPos pos, Block block) {
        int n = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    if (level.getBlockState(at.set(x, y, z)).is(block)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** ネザーで過繁茂になったチャンクは台帳に入らない(面積には数える)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "portal_ledger")
    public static void netherChunkStaysOutOfTheLedger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("テスト用サーバーにネザーが無い");
            return;
        }
        // オーバーワールドのテストで使わない座標(ネザー側だけ読み込む)。
        // テスト用のワールドは run/world に残るので、回ごとにまだ書き換えていないチャンクを使う。
        ChunkPos chunk = new ChunkPos(TEST_CHUNK + 40 + level.getRandom().nextInt(4000), TEST_CHUNK + 40 + level.getRandom().nextInt(4000));
        nether.getChunk(chunk.x, chunk.z);
        PhaseData phase = PhaseData.get(level);
        long before = phase.getOvergrownChunks();
        // 2026-11-11: ネザーの過繁茂は根の台帳の段階 3。段階 3 に入ったところとして台帳に入れ、数えさせる
        com.yayuy.overgrownvegetation.corruption.NetherLedger.reachForTests(nether, chunk,
                com.yayuy.overgrownvegetation.data.DesignData.netherSpread().stageStartTicks[2] + 1);
        com.yayuy.overgrownvegetation.corruption.NetherLedger.advance(nether, 0);
        if (phase.isOvergrownChunk(chunk)) {
            helper.fail("ネザーのチャンクが台帳に入った(同じ座標のオーバーワールドとして数えられる)");
            return;
        }
        if (phase.getOvergrownChunks() != before + 1) {
            helper.fail("過繁茂の面積に数えられていない: " + before + " → " + phase.getOvergrownChunks());
            return;
        }
        helper.succeed();
    }

    /**
     * 5 × 5 の角を除いた 12 個の目の無い枠(北西の角 = {@code corner}、向きは内側)と、その下の石レンガの床を作る。
     */
    private static void buildRing(ServerLevel level, BlockPos corner) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                BlockPos at = corner.offset(dx, 0, dz);
                level.setBlock(at.below(), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                boolean edgeX = dx == 0 || dx == 4;
                boolean edgeZ = dz == 0 || dz == 4;
                if (edgeX == edgeZ) {
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS); // 角と内側
                    continue;
                }
                Direction facing = dz == 0 ? Direction.SOUTH : dz == 4 ? Direction.NORTH : dx == 0 ? Direction.EAST : Direction.WEST;
                level.setBlock(at, Blocks.END_PORTAL_FRAME.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.EndPortalFrameBlock.FACING, facing), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** 輪の 12 個が腫瘍の枠になり、内側 3 × 3 がエンドポータルになったか。なっていなければ理由。 */
    @javax.annotation.Nullable
    private static String checkRingOpened(ServerLevel level, BlockPos corner) {
        int frames = 0;
        int portals = 0;
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                BlockState state = level.getBlockState(corner.offset(dx, 0, dz));
                if (state.is(ModBlocks.OVERGROWN_END_PORTAL_FRAME.get())) {
                    frames++;
                } else if (state.is(Blocks.END_PORTAL)) {
                    portals++;
                }
            }
        }
        return frames == 12 && portals == 9 ? null : "輪が開いていない: 腫瘍の枠 " + frames + " / エンドポータル " + portals;
    }

    /** {@code center} から半径 {@code radius} の立方体の中で、汚染された・覆われたマスの数。 */
    private static int countCorrupted(ServerLevel level, BlockPos center, int radius) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(ModTags.CORRUPTED) || (!state.isAir() && OvergrownLayerData.isCovered(level, pos))) {
                count++;
            }
        }
        return count;
    }

    private PortalCorruptionGameTests() {
    }
}
