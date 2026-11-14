package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.DirectorGates.Gate;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * 司令塔のフェーズで開く口({@link DirectorGates}。2026-11-14 の割り当て)。フェーズの差し替えは世界に 1 つなので、
 * 世界を使う確かめは 1 本にまとめ、このバッチにはほかのテストを入れない。終わったら差し替えを戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class DirectorGateGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(DirectorGateGameTests.class);
        }

        private Registration() {
        }
    }

    private DirectorGateGameTests() {
    }

    /** 割り当てどおりのフェーズで開く(苗床 1・根付き 2・進化 3・偵察 4・認識 5・送り込み 5・スポナー 4・G4 4・G5〜 5・混ざった群れ 6)。 */
    @GameTest(template = "gametest_empty7", batch = "director_gates_pure", timeoutTicks = 20)
    public static void gatesOpenAtAgreedPhases(GameTestHelper helper) {
        DirectorTable.Gates g = DesignData.director().gates;
        Object[][] expected = {
                {Gate.NURSERY, 1}, {Gate.ROOTED, 2}, {Gate.EVOLVE, 3}, {Gate.SCOUT, 4}, {Gate.RECOGNIZE, 5}, {Gate.RAID, 5},
                {Gate.SPAWNER, 4}, {Gate.WAVE_G4, 4}, {Gate.WAVE_G5, 5}, {Gate.WAVE_MIXED, 6}};
        for (Object[] e : expected) {
            Gate gate = (Gate) e[0];
            int at = (Integer) e[1];
            helper.assertTrue(!DirectorGates.open(g, gate, at - 1) && DirectorGates.open(g, gate, at) && DirectorGates.open(g, gate, 9),
                    gate.label + " が F" + at + " で開いていない(設定 F" + DirectorGates.openAt(g, gate) + ")");
        }
        helper.succeed();
    }

    /** 認識の口: 閉じている間は偵察済みのまま。開いたら日数が足りていればすぐ認識。確定の条件・遅らせる・戻す・忘れさせるが外から効く。 */
    @GameTest(template = "gametest_empty7", batch = "director_gates_pure", timeoutTicks = 20)
    public static void recognitionWaitsForGateAndHooks(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        long chunk = ChunkPos.asLong(300, 300);
        m.addPresence(chunk, DesignData.director().base.rememberScore * 2.0D, 0.0D);
        m.update(0.0D);
        BaseMemory.Base b = m.bases().get(0);
        m.onScouted(b.id(), 0.0D, RandomSource.create(1L));
        double late = b.recognizeAfter() + 0.5D;
        m.addPresence(chunk, 0.1D, late);
        m.update(late, false);
        helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED, "F5 より前に認識した");
        // 確定の条件(監視塔の口): 条件が満たされない間は認識しない
        boolean[] tower = {false};
        BaseMemory.registerConfirmer((base, day) -> tower[0]);
        try {
            m.update(late, true);
            helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED, "確定の条件が無いのに認識した");
            // 遅らせる(監視塔を刈った)
            m.delayRecognition(b.id(), 10.0D);
            tower[0] = true;
            m.update(late, true);
            helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED, "遅らせたのに認識した");
            m.delayRecognition(b.id(), -10.0D); // 負は無視される
            m.addPresence(chunk, 0.1D, late + 10.0D);
            m.update(late + 10.0D, true);
            helper.assertTrue(m.bases().get(0).stage() == BaseMemory.Stage.RECOGNIZED, "条件がそろっても認識しない");
        } finally {
            BaseMemory.clearConfirmersForTests();
        }
        m.setBack(b.id(), late + 10.0D, false);
        helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED, "1 段戻らない");
        m.setBack(b.id(), late + 10.0D, true);
        helper.assertTrue(m.bases().isEmpty() && m.cellCount() == 0, "忘れさせても残っている");
        helper.succeed();
    }

    /**
     * 世界を通す口: 苗床のスポナーは F4 から・TD の前触れの間は止まる。差し替えたフェーズで確かめ、最後に戻す。
     * 世界に 1 つの予算を使うので、前の値に戻す。
     */
    @GameTest(template = "gametest_empty7", batch = "director_gates_world", timeoutTicks = 20)
    public static void spawnerOpensAtF4AndStopsDuringTdOmen(GameTestHelper helper) {
        var level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        double before = director.available(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        Supplier<EntityType<? extends Mob>> zombie = () -> EntityType.ZOMBIE;
        List<Supplier<? extends EntityType<? extends Mob>>> pool = List.of(zombie);
        try {
            director.setBudget(level, DesignData.director().costSpawn * 3.0D);
            DirectorGates.setPhaseForTests(3);
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), pool) == null, "F3 で苗床のスポナーが動いた");
            helper.assertTrue(DirectorGates.open(level, Gate.EVOLVE) && !DirectorGates.open(level, Gate.SCOUT), "F3 の開き方が違う");
            DirectorGates.setPhaseForTests(4);
            TD[0] = true;
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), pool) == null, "TD の前触れの間にスポナーが動いた");
            TD[0] = false;
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), pool) == EntityType.ZOMBIE, "F4 で苗床のスポナーが動かない");
        } finally {
            TD[0] = false;
            DirectorGates.setPhaseForTests(phaseBefore);
            director.setBudget(level, before);
        }
        helper.succeed();
    }

    /** テストの TD の前触れ(1 回だけ登録する)。 */
    private static final boolean[] TD = {false};

    static {
        DirectorGates.registerTdOmen(level -> TD[0]);
    }
}
