package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * G1 の通常攻撃は溜め無し(2026-11-19 ユーザー決定「通常攻撃は溜め無し、強い大技だけ溜めを残す」)。
 * 間合いに入ったその tick に当たり、振っている間も追い続け、次の 1 撃はバニラの近接(20 tick)くらいで来る。
 * テスト場は 24 × 24(床は相対 y = 1、物は y = 2)。的の牛は体力 200 で死なない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1NoWindupGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterG1NoWindupGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(G1NoWindupGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String SMALL = "gametest_empty7";
    private static final String WIDE = "gametest_empty24";
    private static final String BATCH = "g1_no_windup";

    private G1NoWindupGameTests() {
    }

    private static Cow spawnCow(GameTestHelper helper, Vec3 at) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, at);
        cow.setNoAi(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        cow.setHealth(200.0F);
        return cow;
    }

    private static AbstractParasitizedMonster spawnZombie(GameTestHelper helper, Vec3 at) {
        AbstractParasitizedMonster g1 = (AbstractParasitizedMonster) helper.spawn(G1Species.ZOMBIE.entityType(), at);
        g1.targetSelector.removeAllGoals(g -> true);
        return g1;
    }

    /** 間合いの中の的には、振り出したその tick に当たる。前触れ・隙の段は流れない。2 撃目は約 20 tick 後。Mob に当たれば感染する。 */
    @GameTest(template = SMALL, batch = BATCH, timeoutTicks = 120)
    public static void strikeLandsOnTheSameTick(GameTestHelper helper) {
        AbstractParasitizedMonster g1 = spawnZombie(helper, new Vec3(2.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.8, 2.0, 3.5));
        long t0 = helper.getTick();
        int[] strikeAt = {-1, -1};
        int[] hitAt = {-1, -1};
        float[] hp = {cow.getHealth()};
        String[] bad = {null};
        helper.onEachTick(() -> {
            if (g1.getTarget() != cow) g1.setTarget(cow);
            int now = (int) (helper.getTick() - t0);
            int a = g1.action();
            if (a == AbstractParasitizedMonster.ACTION_WINDUP || a == AbstractParasitizedMonster.ACTION_RECOVER) {
                bad[0] = "前触れか隙の段が流れた: " + a;
            }
            if (a == AbstractParasitizedMonster.ACTION_STRIKE) {
                if (strikeAt[0] < 0) strikeAt[0] = now;
                else if (strikeAt[1] < 0 && hitAt[0] >= 0 && now - strikeAt[0] > G1StrikeGoal.SWING_TICKS) strikeAt[1] = now;
            }
            if (cow.getHealth() < hp[0]) {
                hp[0] = cow.getHealth();
                if (hitAt[0] < 0) hitAt[0] = now;
                else if (hitAt[1] < 0) hitAt[1] = now;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(bad[0] == null, String.valueOf(bad[0]));
            helper.assertTrue(hitAt[1] >= 0, "2 撃目が当たっていない(1 撃目 " + hitAt[0] + ")");
            helper.assertTrue(strikeAt[0] >= 0 && hitAt[0] - strikeAt[0] <= 1,
                    "振り出してから当たるまでが長い: 振り " + strikeAt[0] + "・当たり " + hitAt[0]);
            int interval = hitAt[1] - hitAt[0];
            helper.assertTrue(interval >= G1StrikeGoal.INTERVAL_MIN - 1 && interval <= G1StrikeGoal.INTERVAL_MAX + 2,
                    "1 撃の間隔が 18〜20 tick でない: " + interval);
            helper.assertTrue(com.yayuy.overgrownvegetation.spore.MobInfection.isInfected(cow),
                    "当たった Mob が感染していない(11-15 から 1 撃で必ず感染)");
            g1.discard();
            cow.discard();
        });
    }

    /**
     * 逃げる的を追いながら殴る: 的は毎 tick 0.08 ブロックずつ +X へ動く(殴られて押されても道筋に戻す)。
     * 何度も当たり、振っている tick にも G1 の位置が進み続け、全体でも的についていく。
     */
    @GameTest(template = WIDE, batch = BATCH, timeoutTicks = 200)
    public static void chasesAndHitsMovingTarget(GameTestHelper helper) {
        AbstractParasitizedMonster g1 = spawnZombie(helper, new Vec3(2.5, 2.0, 12.5));
        Cow cow = spawnCow(helper, new Vec3(4.0, 2.0, 12.5));
        Vec3 cowStart = helper.absoluteVec(new Vec3(4.0, 2.0, 12.5));
        Vec3 g1Start = g1.position();
        long t0 = helper.getTick();
        int[] hits = {0};
        float[] hp = {cow.getHealth()};
        double[] movedWhileSwinging = {0.0D};
        int[] swingTicks = {0};
        Vec3[] last = {g1.position()};
        String[] bad = {null};
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            int now = (int) (helper.getTick() - t0);
            if (g1.getTarget() != cow) g1.setTarget(cow);
            double x = cowStart.x + Math.min(now, 150) * 0.08D;
            cow.teleportTo(x, cowStart.y, cowStart.z);
            cow.setDeltaMovement(Vec3.ZERO);
            Vec3 p = g1.position();
            int a = g1.action();
            if (a == AbstractParasitizedMonster.ACTION_WINDUP || a == AbstractParasitizedMonster.ACTION_RECOVER) {
                bad[0] = "前触れか隙の段が流れた: " + a;
            }
            if (a == AbstractParasitizedMonster.ACTION_STRIKE) {
                swingTicks[0]++;
                double dx = p.x - last[0].x;
                double dz = p.z - last[0].z;
                movedWhileSwinging[0] += Math.sqrt(dx * dx + dz * dz);
            }
            last[0] = p;
            if (cow.getHealth() < hp[0]) {
                hp[0] = cow.getHealth();
                hits[0]++;
            }
            if (now >= 150) done[0] = true;
        });
        helper.runAtTickTime(t0 + 160, () -> {
            helper.assertTrue(bad[0] == null, String.valueOf(bad[0]));
            helper.assertTrue(hits[0] >= 4, "逃げる的に当たる数が少ない: " + hits[0] + " 回");
            helper.assertTrue(swingTicks[0] > 0, "振っていない");
            double perTick = movedWhileSwinging[0] / swingTicks[0];
            helper.assertTrue(perTick > 0.04D, "振っている間に足が止まっている: 1 tick あたり " + perTick + " ブロック(振り " + swingTicks[0] + " tick)");
            double advanced = g1.getX() - g1Start.x;
            helper.assertTrue(advanced > 8.0D, "的についていけていない: " + advanced + " ブロック");
            g1.discard();
            cow.discard();
            helper.succeed();
        });
    }
}
