package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NetherSurface;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.NetherOvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ネザーの侵食(2026-10-02)のテスト。
 * <ul>
 *     <li>ネザーラック・ソウルサンド・ソウルソイルが専用の過繁茂ブロックになる</li>
 *     <li>流れている溶岩に汚染が来ると、源泉が玄武岩になる</li>
 *     <li>ネザーの床({@link NetherSurface})が天井の下の段を全部拾う</li>
 *     <li>床が汚染されたネザーのチャンクが、ネザー用の過繁茂バイオームになり、ネザーの台帳に入る</li>
 *     <li>読み込まれたネザーのチャンクで、汚染された床の隣の床へ広がる</li>
 *     <li>ネザーの過繁茂バイオームは密林の枠で湧き、媒介種(G1)は湧かない</li>
 * </ul>
 * ネザーの台帳など世界に 1 つの記録を触るので専用のバッチ。テスト場ではなく遠くの専用チャンクを使う。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherCorruptionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterNetherCorruptionGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(NetherCorruptionGameTests.class);
        }

        private Registration() {
        }
    }

    /** {@link PortalCorruptionGameTests}(30_600〜)と重ならない列。ネザー側の座標。 */
    private static final int NETHER_CHUNK = 9_000;
    /** オーバーワールド側の座標。 */
    private static final int OVERWORLD_CHUNK = 30_900;

    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_corruption")
    public static void netherBlocksHaveOvergrownForms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(OVERWORLD_CHUNK, OVERWORLD_CHUNK);
        level.getChunk(chunk.x, chunk.z);
        BlockPos base = new BlockPos(chunk.getMinBlockX() + 4, 100, chunk.getMinBlockZ() + 4);
        Block[][] cases = {
                {Blocks.NETHERRACK, ModBlocks.OVERGROWN_NETHERRACK.get()},
                {Blocks.CRIMSON_NYLIUM, ModBlocks.OVERGROWN_NYLIUM.get()},
                {Blocks.WARPED_NYLIUM, ModBlocks.OVERGROWN_NYLIUM.get()},
                {Blocks.SOUL_SAND, ModBlocks.NETHER_PEAT.get()},
                {Blocks.SOUL_SOIL, ModBlocks.OVERGROWN_SOUL_SOIL.get()},
        };
        for (int i = 0; i < cases.length; i++) {
            BlockPos pos = base.east(i * 2);
            level.setBlock(pos, cases[i][0].defaultBlockState(), Block.UPDATE_CLIENTS);
            NoisyCorruptionBurst.single(level, pos);
            BlockState got = level.getBlockState(pos);
            // randomBlockChance(既定 5%)で汎用の汚染ブロックになることがあるので、それも許す。
            if (!got.is(cases[i][1]) && !got.is(ModBlocks.OVERGROWN_BLOCK.get())) {
                helper.fail(cases[i][0] + " が " + cases[i][1] + " にならなかった: " + got);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 流れている溶岩に汚染が来ると、上流の源泉が固まる(流れている溶岩そのものは置き換えない)。2026-10-05 夜から固まった源泉は
     * 素の玄武岩ではなく覆われた玄武岩(座標によって過繁茂しているブロック)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_corruption")
    public static void flowingLavaSolidifiesItsSource(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(OVERWORLD_CHUNK + 4, OVERWORLD_CHUNK);
        level.getChunk(chunk.x, chunk.z);
        BlockPos source = new BlockPos(chunk.getMinBlockX() + 4, 100, chunk.getMinBlockZ() + 8);
        for (int dx = -1; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(source.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        level.setBlock(source, Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(source.east(1), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2), Block.UPDATE_CLIENTS);
        BlockPos flowing = source.east(2);
        level.setBlock(flowing, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 4), Block.UPDATE_CLIENTS);

        NoisyCorruptionBurst.single(level, flowing);
        BlockState solid = level.getBlockState(source);
        // 2026-10-30 から覆われた玄武岩 = 玄武岩 + 覆いの記録
        boolean coveredBasalt = solid.is(Blocks.BASALT)
                && com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, source);
        if (!coveredBasalt && !solid.is(ModBlocks.OVERGROWN_BLOCK.get())) {
            helper.fail("源泉が覆われた玄武岩(か過繁茂しているブロック)になっていない: " + solid);
            return;
        }
        helper.succeed();
    }

    /** 列の床を、天井の下から全部拾う。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_corruption")
    public static void netherSurfaceFindsEveryFloor(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) {
            return;
        }
        LevelChunk chunk = nether.getChunk(NETHER_CHUNK, NETHER_CHUNK);
        int x = chunk.getPos().getMinBlockX() + 3;
        int z = chunk.getPos().getMinBlockZ() + 3;
        for (int y = 95; y <= 112; y++) {
            BlockState state = (y == 100 || y == 106 || y == 95) ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState();
            nether.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
        }
        int[] floors = new int[NetherSurface.MAX_FLOORS];
        int n = NetherSurface.floors(nether, chunk, x, z, floors);
        boolean has106 = false;
        boolean has100 = false;
        for (int i = 0; i < n; i++) {
            has106 |= floors[i] == 106;
            has100 |= floors[i] == 100;
        }
        if (!has106 || !has100) {
            helper.fail("床を拾えていない: " + java.util.Arrays.toString(java.util.Arrays.copyOf(floors, n)));
            return;
        }
        for (int i = 0; i < n; i++) {
            if (floors[i] >= NetherSurface.topY(nether) + 1) {
                helper.fail("天井の岩盤を床として拾った: " + floors[i]);
                return;
            }
        }
        helper.succeed();
    }

    // 2026-11-11: 「床が汚染されたネザーのチャンクが過繁茂になり、隣の床へ這い出す」(10-02)のテストは消した。
    // ネザーの広がりとバイオームは根の台帳の段階だけが決める(NetherSkeletonGameTests)。


    /** 過繁茂したネザーでは水バケツの水が蒸発せずに置ける。ふつうのネザーでは今までどおり蒸発する。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "nether_corruption")
    public static void waterStaysInOvergrownNether(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) {
            return;
        }
        // テスト用のワールドは run/world に残るので、回ごとにまだ書き換えていないチャンクを使う。
        int offset = 200 + nether.getRandom().nextInt(4000);
        ChunkPos overgrown = new ChunkPos(NETHER_CHUNK + offset, NETHER_CHUNK - 50);
        ChunkPos plain = new ChunkPos(NETHER_CHUNK + offset, NETHER_CHUNK - 60);
        nether.getChunk(overgrown.x, overgrown.z);
        nether.getChunk(plain.x, plain.z);
        OvergrownBiomeConverter.forceOvergrown(nether, overgrown);
        net.minecraft.world.item.BucketItem bucket = (net.minecraft.world.item.BucketItem) net.minecraft.world.item.Items.WATER_BUCKET;
        BlockPos inOvergrown = overgrown.getMiddleBlockPosition(100);
        BlockPos inPlain = plain.getMiddleBlockPosition(100);
        for (BlockPos pos : new BlockPos[]{inOvergrown, inPlain}) {
            nether.setBlock(pos.below(), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
            nether.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            bucket.emptyContents(null, nether, pos, null, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
        }
        if (!nether.getBlockState(inOvergrown).is(Blocks.WATER)) {
            helper.fail("過繁茂したネザーで水が置けない: " + nether.getBlockState(inOvergrown));
            return;
        }
        if (nether.getBlockState(inPlain).is(Blocks.WATER)) {
            helper.fail("ふつうのネザーで水が蒸発しない");
            return;
        }
        helper.succeed();
    }

    /** 全列に y=100 の床({@code floor})と上 3 マスの空きを作る(その下・上の地形は元のまま)。 */
    private static void flatFloor(ServerLevel level, ChunkPos pos, BlockState floor) {
        for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                level.setBlock(new BlockPos(x, 100, z), floor, Block.UPDATE_CLIENTS);
                for (int y = 101; y <= 103; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
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

    private NetherCorruptionGameTests() {
    }
}
