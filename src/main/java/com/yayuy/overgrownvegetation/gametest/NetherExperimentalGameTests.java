package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
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
 * 新しいネザーの仕組みは「実験的要素」がオンのときだけ(2026-11-12 ユーザー決定。ネザーの作り直しは 0.2.0 の後)。
 * <ul>
 *     <li>オフ: 台帳が始まらない(ポータルの起点もふだんの汚染も)・胞子の場が止まる・ふだんの汚染は表どおり覆われたブロック(ソウルサンドは泥炭)</li>
 *     <li>オン → オフ → オン: オフの間は森の時計が止まり(届いた時刻が後ろへずれる)、オンで続きから進む</li>
 * </ul>
 * GameTest のサーバーは値が無ければオン。値を書いたテストは最後に必ず消す(ほかのテストがオンのまま動くように)。値を書くので、テストごとに batch を分ける(同じ batch は同時に走る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherExperimentalGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterNetherExperimentalGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(NetherExperimentalGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int BASE = 30_000;

    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_experimental")
    public static void offKeepsTheNewNetherStill(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("テスト用サーバーにネザーが無い");
            return;
        }
        int cx = BASE + nether.getRandom().nextInt(2000) * 4, cz = BASE;
        nether.getChunk(cx, cz);
        BlockPos base = new BlockPos((cx << 4) + 4, 100, (cz << 4) + 4);
        try {
            if (!NetherLedger.active(nether)) throw new GameTestAssertException("GameTest のサーバーで新しいネザーが最初から止まっている");
            ExperimentalFeatures.set(server, false);
            if (NetherLedger.active(nether)) throw new GameTestAssertException("実験的要素をオフにしても台帳が動いている");
            if (NetherSporeFieldServer.running()) throw new GameTestAssertException("実験的要素がオフなのにネザーの胞子の場が回っている");
            // ポータルの出口からも始まらない
            if (NetherLedger.seedFromPortal(nether, base.below(60)) != null || NetherLedgerData.get(nether).isReached(new ChunkPos(base).toLong())) {
                throw new GameTestAssertException("オフなのにポータルの出口から森が始まった");
            }
            // ふだんの汚染: 表どおり覆われたブロック(ソウルサンドは泥炭)。台帳の時計は始まらない
            Block[][] cases = {
                    {Blocks.NETHERRACK, ModBlocks.OVERGROWN_NETHERRACK.get()},
                    {Blocks.CRIMSON_NYLIUM, ModBlocks.OVERGROWN_NYLIUM.get()},
                    {Blocks.SOUL_SOIL, ModBlocks.OVERGROWN_SOUL_SOIL.get()},
                    {Blocks.SOUL_SAND, ModBlocks.NETHER_PEAT.get()},
            };
            for (int i = 0; i < cases.length; i++) {
                BlockPos pos = base.east(i * 2);
                nether.setBlock(pos, cases[i][0].defaultBlockState(), Block.UPDATE_CLIENTS);
                nether.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                NoisyCorruptionBurst.single(nether, pos);
                BlockState got = nether.getBlockState(pos);
                // randomBlockChance(既定 5%)で汎用の汚染ブロックになることがあるので、それも許す
                if (!got.is(cases[i][1]) && !got.is(ModBlocks.OVERGROWN_BLOCK.get())) {
                    throw new GameTestAssertException("オフのふだんの汚染で " + cases[i][0] + " が " + cases[i][1] + " にならない: " + got);
                }
            }
            if (NetherLedgerData.get(nether).isReached(new ChunkPos(base).toLong())) {
                throw new GameTestAssertException("オフなのに、ふだんの汚染でネザーの台帳の時計が始まった");
            }
        } finally {
            ExperimentalFeatures.reset(server);
        }
        if (!NetherLedger.active(nether)) {
            helper.fail("値を消してもオンに戻らない");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "nether_experimental_pause")
    public static void switchingOffPausesTheForestAndOnResumes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("テスト用サーバーにネザーが無い");
            return;
        }
        int cx = BASE + nether.getRandom().nextInt(2000) * 4, cz = BASE + 800;
        nether.getChunk(cx, cz);
        long key = ChunkPos.asLong(cx, cz);
        NetherLedger.reachForTests(nether, new ChunkPos(cx, cz), 0);
        long[] before = new long[2];
        helper.startSequence()
                .thenExecute(() -> {
                    before[0] = NetherLedgerData.get(nether).arrivalOf(key);
                    before[1] = nether.getGameTime();
                    ExperimentalFeatures.set(server, false);
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    long shifted = NetherLedgerData.get(nether).arrivalOf(key) - before[0];
                    long elapsed = nether.getGameTime() - before[1];
                    ExperimentalFeatures.reset(server); // 先に戻す(下で落ちても、ほかのテストをオフで動かさない)
                    if (Math.abs(shifted - elapsed) > 40) {
                        throw new GameTestAssertException("オフの間に森の時計が止まらない: 届いた時刻のずれ " + shifted + "・経った " + elapsed);
                    }
                    before[0] = NetherLedgerData.get(nether).arrivalOf(key);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    long moved = NetherLedgerData.get(nether).arrivalOf(key) - before[0];
                    if (moved != 0) throw new GameTestAssertException("オンに戻しても時計がずれ続ける: " + moved);
                    if (!NetherLedger.active(nether)) throw new GameTestAssertException("オンに戻らない");
                })
                .thenSucceed();
    }

    /**
     * {@code /ov experimental on|off}(2026-11-12)で切り替わり、オフのネザーの過繁茂はフェーズの面積に数える(作り直しの前と同じ)。
     * 同じチャンクを二度数えず、オンにした後に台帳が段階 3 で数え直さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "nether_experimental_count")
    public static void commandSwitchesAndOffCountsOvergrownOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("テスト用サーバーにネザーが無い");
            return;
        }
        int cx = BASE + nether.getRandom().nextInt(2000) * 4, cz = BASE + 1600;
        ChunkPos a = new ChunkPos(cx, cz), b = new ChunkPos(cx + 1, cz), c = new ChunkPos(cx + 2, cz);
        net.minecraft.commands.CommandSourceStack op = server.createCommandSourceStack().withSuppressedOutput();
        // 見直し ④(11-16): 前の回の台帳(数えた印など)が残っていると数え方が狂う。使う 3 チャンクとまわりを消してから
        NetherTubeTreeGameTests.forgetLedger(nether, cx + 1, cz, 2);
        var phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(nether);
        try {
            server.getCommands().performPrefixedCommand(op, "ov experimental off");
            if (ExperimentalFeatures.enabled(server) || NetherLedger.active(nether)) {
                throw new GameTestAssertException("/ov experimental off で止まらない");
            }
            long c0 = phase.getOvergrownChunks();
            NetherLedger.recordForTests(nether, java.util.List.of(a, b));
            if (phase.getOvergrownChunks() - c0 != 2) {
                throw new GameTestAssertException("オフのネザーの過繁茂をフェーズに数えない: " + (phase.getOvergrownChunks() - c0) + "(2 のはず)");
            }
            NetherLedger.recordForTests(nether, java.util.List.of(a));
            if (phase.getOvergrownChunks() - c0 != 2) throw new GameTestAssertException("同じチャンクを二度数えた");
            server.getCommands().performPrefixedCommand(op, "ov experimental on");
            if (!ExperimentalFeatures.enabled(server) || !NetherLedger.active(nether)) {
                throw new GameTestAssertException("/ov experimental on で動かない");
            }
            // オン: 報告はもう数えない(台帳が数える)。オフで数えた a は、台帳が段階 3 に入れても数え直さない。新しい c は数える
            long c1 = phase.getOvergrownChunks();
            NetherLedger.recordForTests(nether, java.util.List.of(c));
            if (phase.getOvergrownChunks() != c1) throw new GameTestAssertException("オンなのに報告で数えた(台帳と二重)");
            long age3 = com.yayuy.overgrownvegetation.data.DesignData.netherSpread().stageStartTicks[2] + 10;
            NetherLedger.reachForTests(nether, a, age3);
            NetherLedger.reachForTests(nether, c, age3);
            NetherLedger.advance(nether, 0);
            if (phase.getOvergrownChunks() - c1 != 1) {
                throw new GameTestAssertException("オンにした後の台帳の数え方が違う: " + (phase.getOvergrownChunks() - c1) + "(c だけの 1 のはず)");
            }
        } finally {
            ExperimentalFeatures.reset(server);
        }
        helper.succeed();
    }

    private NetherExperimentalGameTests() {
    }
}
