package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * F1 の前倒し(2026-11-23 ユーザー決定): フェーズ 0 で過繁茂の台帳に 1 チャンクでも載ったら、日数・実績を待たずに F1。
 * 凍結中(/ov phase false)は上げない。台帳 0 なら今まで通り日数と実績のゲート。世界の本物の {@link PhaseData} には触らない
 * (切り離した PhaseData。台帳は胞子の場の写しにも知らせるので、世界の外れの座標を使い、最後に外す)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhaseEarlyStartGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        // 1.20.1: 受け手のメソッドの名前はクラスごとに変える(Forge の EventBus は「クラスの単純名_メソッド名_イベントの単純名」で包みを作って使い回すので、同じパッケージの同じ名前の入れ子のクラスがぶつかり、前の受け手が 2 回呼ばれる)
        @SubscribeEvent
        public static void onPhaseEarlyStartGameTests(RegisterGameTestsEvent event) {
            event.register(PhaseEarlyStartGameTests.class);
        }

        private Registration() {
        }
    }

    private static ChunkPos farChunk(ServerLevel level) {
        int base = 9_200_000 + level.random.nextInt(10_000) * 64; // 世界の外れ(本物の台帳とかぶらない所)
        return new ChunkPos(base, base);
    }

    /** 今の世界の値で、台帳 0 のとき日数・実績(0 個)のゲートを通るか。 */
    private static boolean gatesOpenWithoutAdvancements(ServerLevel level) {
        return level.getGameTime() >= WorldDifficultyData.phase1StartTicks(level.getServer())
                && WorldDifficultyData.phase1Advancements(level.getServer()) <= 0;
    }

    /** 台帳に 1 チャンク載せる → 次の判定で F1。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void ledgerStartsPhase1Early(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        ChunkPos c = farChunk(level);
        try {
            helper.assertTrue(data.getPhase() == 0, "切り離した PhaseData がフェーズ 0 で始まらない");
            data.adoptOvergrownChunk(level, c);
            data.tick(level);
            helper.assertTrue(data.getPhase() == 1, "台帳 1 チャンクで F1 にならない(" + data.getPhase() + ")");
        } finally {
            data.forgetOvergrownChunk(c);
        }
        helper.succeed();
    }

    /** 凍結中は台帳があっても上がらない(tick・実績の両方の入口)。解けば上がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void frozenBlocksEarlyStart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        ChunkPos c = farChunk(level);
        try {
            data.setFrozen(level, true);
            data.adoptOvergrownChunk(level, c);
            data.tick(level);
            helper.assertTrue(data.getPhase() == 0, "凍結中に tick で F1 になった");
            data.recordStartAdvancement(level, "minecraft:story/mine_stone");
            helper.assertTrue(data.getPhase() == 0, "凍結中に実績の入口で F1 になった");
            data.setFrozen(level, false);
            data.tick(level);
            helper.assertTrue(data.getPhase() == 1, "凍結を解いても F1 にならない");
        } finally {
            data.forgetOvergrownChunk(c);
        }
        helper.succeed();
    }

    /** 台帳 0 なら今まで通り日数と実績を待つ(期待値はその時の世界の値から)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void emptyLedgerWaitsForGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        helper.assertTrue(data.overgrownLedgerSize() == 0, "切り離した PhaseData の台帳が空でない");
        boolean expect = gatesOpenWithoutAdvancements(level);
        data.tick(level);
        helper.assertTrue((data.getPhase() == 1) == expect,
                "台帳 0 の判定がゲートと合わない(フェーズ " + data.getPhase() + "・ゲート " + expect + ")");
        helper.succeed();
    }
}
