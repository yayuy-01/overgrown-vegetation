package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionProximity;
import com.yayuy.overgrownvegetation.corruption.LedgerSpread;
import com.yayuy.overgrownvegetation.perf.BackgroundTask;
import com.yayuy.overgrownvegetation.perf.FluidSpreadShortcut;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 性能の仕組み(perf/、2026-10-06)のテスト。
 * <ul>
 *     <li><b>別スレッドの計算</b>({@link BackgroundTask}): 計算はサーバーのスレッドの外で走り、答えはサーバースレッドで当てはめる</li>
 *     <li><b>台帳の写し</b>({@link PhaseData#snapshotLedger} の {@code clone}): 反復の順番が元と同じ —— 別スレッドで数えた
 *     台帳の縁が、サーバースレッドで数えたときと同じ答えになる前提</li>
 *     <li>汚染から逃げる Goal の「近くに繁茂系でないチャンクがあるか」({@link CorruptionProximity#hasOpenChunkWithin})</li>
 *     <li>水の広がりの近道({@link FluidSpreadShortcut}、10-06 夜)が省いたマスには本当に流れ込めない(検算)</li>
 * </ul>
 * 世界には触らない(台帳は自前の表で作る。水の近道のテストだけテスト場に池を作る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PerfGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterPerfGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(PerfGameTests.class);
        }

        private Registration() {
        }
    }

    /** GameTest のサーバーでも別スレッドで回す仕事(このテストだけが使う)。 */
    private static final BackgroundTask<String> TEST_TASK = new BackgroundTask<String>("テスト用").asyncEvenInTests();

    /** 計算は別スレッド、当てはめはサーバースレッド(次の tick の頭)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void backgroundTaskComputesOffThreadAndAppliesOnServerThread(GameTestHelper helper) {
        AtomicReference<String> computedOn = new AtomicReference<>();
        AtomicReference<Boolean> appliedOnServer = new AtomicReference<>();
        TEST_TASK.start(helper.getLevel().getServer(), () -> {
            computedOn.set(Thread.currentThread().getName());
            return "答え";
        }, answer -> appliedOnServer.set("答え".equals(answer) && helper.getLevel().getServer().isSameThread()));
        expectTrue(helper, TEST_TASK.running(), "始めた直後に走っている印が無い");
        helper.succeedWhen(() -> {
            if (appliedOnServer.get() == null) {
                helper.fail("まだ当てはめていない");
            }
            expectTrue(helper, appliedOnServer.get(), "答えがサーバースレッドで当てはめられていない");
            expectTrue(helper, computedOn.get() != null && !computedOn.get().equals(Thread.currentThread().getName()),
                    "計算がサーバースレッドで走った: " + computedOn.get());
            expectTrue(helper, !TEST_TASK.running(), "当てはめた後も走っている印が残っている");
        });
    }

    /** 台帳の写し(clone)は反復の順番まで元と同じ。別スレッドで数えた縁が同じ答えになる前提。 */
    @GameTest(template = "gametest_empty7")
    public static void ledgerSnapshotKeepsIterationOrder(GameTestHelper helper) {
        Long2LongOpenHashMap ledger = new Long2LongOpenHashMap();
        Random random = new Random(1234L);
        for (int i = 0; i < 20_000; i++) {
            ledger.put(ChunkPos.asLong(random.nextInt(2000) - 1000, random.nextInt(2000) - 1000), random.nextInt(100_000));
        }
        for (int i = 0; i < 3_000; i++) { // 消した跡があっても同じ
            ledger.remove(ChunkPos.asLong(random.nextInt(2000) - 1000, random.nextInt(2000) - 1000));
        }
        Long2LongOpenHashMap copy = ledger.clone();
        expectTrue(helper, order(ledger).equals(order(copy)), "写しの反復の順番が元と違う");
        Long2IntOpenHashMap live = LedgerSpread.frontier(ledger);
        Long2IntOpenHashMap fromCopy = LedgerSpread.frontier(copy);
        expectTrue(helper, live.equals(fromCopy), "写しで数えた縁が元と違う");
        expectTrue(helper, order(live.keySet().iterator()).equals(order(fromCopy.keySet().iterator())),
                "写しで数えた縁の並びが元と違う(印の付き方が変わる)");
        helper.succeed();
    }

    /** 台帳の縁: L 字の切れ込みは 2 面、端の外は 1 面、斜めだけは入らない(写しの版も同じ数え方)。 */
    @GameTest(template = "gametest_empty7")
    public static void frontierOfSnapshotCountsEdgeContacts(GameTestHelper helper) {
        Long2LongOpenHashMap ledger = new Long2LongOpenHashMap();
        ledger.put(ChunkPos.asLong(0, 0), 1L);
        ledger.put(ChunkPos.asLong(1, 0), 1L);
        ledger.put(ChunkPos.asLong(0, 1), 1L);
        Long2IntOpenHashMap contacts = LedgerSpread.frontier(ledger);
        expectTrue(helper, contacts.get(ChunkPos.asLong(1, 1)) == 2, "L 字の切れ込み");
        expectTrue(helper, contacts.get(ChunkPos.asLong(2, 0)) == 1, "東の端の外");
        expectTrue(helper, !contacts.containsKey(ChunkPos.asLong(0, 0)), "台帳にあるチャンクが縁に入った");
        expectTrue(helper, !contacts.containsKey(ChunkPos.asLong(2, 2)), "斜めにしか接していないチャンクが縁に入った");
        helper.succeed();
    }

    /** テスト場(平らな世界の平原)は繁茂系ではないので、逃げ先になりうるチャンクがある。 */
    @GameTest(template = "gametest_empty7")
    public static void plainsCountAsOpenChunks(GameTestHelper helper) {
        expectTrue(helper, CorruptionProximity.hasOpenChunkWithin(helper.getLevel(), helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3)), 24),
                "平原のテスト場で、繁茂系でないチャンクが見つからない");
        helper.succeed();
    }

    private static LongArrayList order(Long2LongOpenHashMap map) {
        return order(map.keySet().iterator());
    }

    private static LongArrayList order(LongIterator keys) {
        LongArrayList list = new LongArrayList();
        while (keys.hasNext()) {
            list.add(keys.nextLong());
        }
        return list;
    }

    /**
     * 水の広がりの近道({@link FluidSpreadShortcut})が省いた横のマスには、本当に流れ込めない。GameTest のサーバーでは近道が検算で動き
     * (省く判定のマスでも本来の計算をして確かめ、本来の答えを返す)、食い違いを数える。ここでは石の縁の池(中の水源の横は水源か石)・
     * 水没できるブロック(ハーフ・柵 = {@code LiquidBlockContainer}、液体の種類で答えが変わるので省かない)・松明(流れて壊れる)・
     * 高い所から落ちる水(流れる水の広がり)を作って流す。ほかのテストの水も同じ検算を通る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void fluidShortcutOnlySkipsNeighboursWaterCannotEnter(GameTestHelper helper) {
        expectTrue(helper, FluidSpreadShortcut.enabled() && FluidSpreadShortcut.checking(), "GameTest のサーバーでは近道が検算で動くはず");
        long checkedBefore = FluidSpreadShortcut.checked();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean rim = x == 0 || x == 4 || z == 0 || z == 4;
                helper.setBlock(new BlockPos(x, 2, z), rim ? stone : Blocks.WATER.defaultBlockState());
            }
        }
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.OAK_SLAB.defaultBlockState());
        helper.setBlock(new BlockPos(2, 2, 4), Blocks.OAK_FENCE.defaultBlockState());
        helper.setBlock(new BlockPos(0, 2, 2), Blocks.TORCH.defaultBlockState());
        helper.setBlock(new BlockPos(6, 2, 6), stone);
        helper.setBlock(new BlockPos(6, 3, 6), stone);
        helper.setBlock(new BlockPos(6, 4, 6), Blocks.WATER.defaultBlockState());
        helper.runAtTickTime(120, () -> {
            expectTrue(helper, FluidSpreadShortcut.checked() > checkedBefore, "検算が 1 件も走らなかった(近道が当たっていない)");
            expectTrue(helper, FluidSpreadShortcut.mismatches() == 0,
                    "近道が省いたマスに本当は流れ込めた: " + FluidSpreadShortcut.mismatches() + " 件(ログの [FluidSpreadShortcut])");
            helper.succeed();
        });
    }

    private static void expectTrue(GameTestHelper helper, boolean ok, String what) {
        if (!ok) {
            helper.fail(what);
        }
    }

    private PerfGameTests() {
    }
}
