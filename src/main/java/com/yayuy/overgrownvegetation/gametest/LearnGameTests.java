package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.data.KillMemoryTable;
import com.yayuy.overgrownvegetation.director.DangerWatch;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.director.KillMemory;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 司令塔 第 3 段 区切り 1「学ぶ」: 倒された記録・危ない所・進化しやすさ・根付きの点と遠距離の根付き・危ない所へ送る。
 * 記録はテスト用の {@link KillMemory#createForTests}(世界に 1 つの記録は使わない)。送る確かめはまわりの G1 を集めるので、batch を分ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LearnGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LearnGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "director_learn";

    private LearnGameTests() {
    }

    private static KillMemoryTable k() {
        return DesignData.director().kills;
    }

    /** しきい値は升ごとに 10〜13 のどれか(ユーザー指定の幅)。同じ升ならいつも同じ。升によって違う値が出る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void thresholdIsTenToThirteenPerChunk(GameTestHelper helper) {
        Set<Integer> seen = new HashSet<>();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                long c = ChunkPos.asLong(x, z);
                int th = KillMemory.threshold(c);
                helper.assertTrue(th >= k().dangerMinKills && th <= k().dangerMaxKills, "しきい値が幅の外: " + th);
                helper.assertTrue(th == KillMemory.threshold(c), "同じ升でしきい値が変わった");
                seen.add(th);
            }
        }
        helper.assertTrue(seen.size() == k().dangerMaxKills - k().dangerMinKills + 1, "幅のどれかが出ない: " + seen);
        helper.succeed();
    }

    /** しきい値の数だけ倒されたら危ない所。半減期で薄れて 1 日後には危なくない。まわり 3 × 3 升の和で数える。保存しても残る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dangerNeedsThresholdAndFades(GameTestHelper helper) {
        KillMemory m = KillMemory.createForTests();
        long c = ChunkPos.asLong(50, 50);
        int th = KillMemory.threshold(c);
        m.addForTests(c, th - 1, 0.0D);
        helper.assertTrue(m.dangers(0.0D).isEmpty(), "しきい値より少ないのに危ない所になった");
        // 隣の升の 1 体も数える(3 × 3 の和)
        m.addForTests(ChunkPos.asLong(51, 50), 1, 0.0D);
        helper.assertTrue(!m.dangers(0.0D).isEmpty(), "隣の升と合わせてしきい値なのに危ない所にならない");
        helper.assertTrue(m.dangers(0.0D).size() == 1, "同じ戦場が 2 つに数えられた: " + m.dangers(0.0D).size());
        double half = m.killsAround(c, k().halfLifeDays);
        helper.assertTrue(Math.abs(half - th / 2.0D) < 1.0E-6D, "半減期で半分になっていない: " + half);
        helper.assertTrue(m.dangers(k().halfLifeDays).isEmpty(), "1 日後も危ない所のまま");
        KillMemory r = KillMemory.createForTests();
        r.addForTests(c, th + 2, 0.0D);
        helper.assertTrue(r.roundTripForTests().dangers(0.0D).size() == 1, "保存したら危ない所が消えた");
        helper.succeed();
    }

}
