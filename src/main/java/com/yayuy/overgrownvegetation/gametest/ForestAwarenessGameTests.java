package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.front.ForestAwareness;
import com.yayuy.overgrownvegetation.front.FrontHunts;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 森の中で気づかれる(2026-11-22 ユーザー決定 B 案。依頼書 storage-and-stealth-brief.md の 2)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ForestAwarenessGameTests {

    private static final String BATCH = "forest_awareness";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterForestAwarenessGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(ForestAwarenessGameTests.class);
        }

        private Registration() {
        }
    }

    private ForestAwarenessGameTests() {
    }

    /**
     * 器官のそば(奥)では行動の気づきが倍になり、森の外ではたまらない。超えたら見つかって狩りの「見られている」になり、その後はたまりにくい。走るのは器官のそばだけ数える。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void actsAddUpNearOrgansAndTriggerHunt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FrontTable t = DesignData.front();
        ForestOrgans data = ForestOrgans.get(level);
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_aware_test".getBytes()), "ov_aware_test"));
        int x = 33_000_000, z = 33_000_000;
        ForestOrgans.Organ o = data.plant(level, OrganKind.TOWER, false, 0, 11, 0, x, z);
        try {
            ForestAwareness.clearForTests();
            FrontHunts.seenForTests = null;
            // 森の外: たまらない
            p.setPos(x + 5000.5D, 64.0D, z + 0.5D);
            helper.assertTrue(ForestAwareness.add(p, ForestAwareness.Act.FIRE, 1.0D) == 0.0D, "森の外で気づきがたまった");
            // 器官のそば(育ち切った器官)
            o.state = ForestOrgans.State.ALIVE;
            p.setPos(x + 4.5D, 64.0D, z + 0.5D);
            helper.assertTrue(ForestAwareness.depth(level, p.getX(), p.getZ()) == 3, "器官のそばが奥と数えられない");
            double g = ForestAwareness.add(p, ForestAwareness.Act.FIRE, 1.0D);
            helper.assertTrue(Math.abs(g - t.awareFire * t.awareNearFactor) < 1.0E-6D, "器官のそばの火の気づきが違う: " + g);
            helper.assertTrue(!ForestAwareness.noticed(level, p) || g >= t.awareThreshold, "超えていないのに見つかった");
            // 超えるまで叩く → 見つかる・狩りの「見られている」
            for (int i = 0; i < 100 && !ForestAwareness.noticed(level, p); i++) ForestAwareness.add(p, ForestAwareness.Act.HIT_ORGAN, 1.0D);
            helper.assertTrue(ForestAwareness.noticed(level, p), "気づきが超えても見つからない");
            helper.assertTrue(FrontHunts.seen(level, p), "見つかったのに狩りの「見られている」にならない");
            // 見つかった後はたまりにくい
            ForestAwareness.ageForTests(p.getUUID(), (long) (t.awareNoticedSeconds * 20.0D) + 1L);
            helper.assertTrue(!ForestAwareness.noticed(level, p), "見つかっている間が終わらない");
            double calm = ForestAwareness.add(p, ForestAwareness.Act.FIRE, 1.0D);
            helper.assertTrue(Math.abs(calm - t.awareFire * t.awareNearFactor * t.awareCalmFactor) < 1.0E-6D, "見つかった後もたまりやすいまま: " + calm);
            // 走る: 器官のそばだけ
            ForestAwareness.clearForTests();
            p.setSprinting(true);
            ForestAwareness.tick(level, 1.0D);
            double run = ForestAwareness.value(p);
            p.setPos(x + 5000.5D, 64.0D, z + 0.5D);
            ForestAwareness.tick(level, 1.0D);
            helper.assertTrue(run >= 0.0D, "走りの気づきが負");
            helper.assertTrue(ForestAwareness.value(p) <= run, "器官から離れて走っても気づきがたまった");
            helper.succeed();
        } finally {
            p.setSprinting(false);
            ForestAwareness.clearForTests();
            data.remove(o.id);
        }
    }
}
