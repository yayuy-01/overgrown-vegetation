package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1BowShotGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1LeapGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Move;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SkillTiming;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SpewGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SpitGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TechniqueGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1ThroatPuffGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1VineLashGoal;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
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
 * G1 の 1 段目の技の溜め(2026-11-19、溜めを外す 2 便。ユーザー「案どおりでよい」)。期待値は {@link G1SkillTiming} の値から。
 * <ul>
 *   <li>縮めた技(跳びかかり・唾・喉の胞子・吐き戻し・蔦の鞭)は、前触れの段がその長さで終わる。</li>
 *   <li>当たる tick まで向き続ける技は、横へ歩く相手にも当たる。ただし 1 tick に回る角度は {@link G1SkillTiming#AIM_TURN_DEG} まで。</li>
 *   <li>矢は、据えていなければ引く間も歩く。</li>
 * </ul>
 * テスト場は 24 × 24(床は相対 y = 1、物は y = 2)。1 段目は +Z を向いて出るので、的は +Z の側に置く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1SkillWindupGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1SkillWindupGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty24";
    private static final String BATCH = "g1_skill_windup";

    private G1SkillWindupGameTests() {
    }

    /** 1 つの技だけを残した 1 段目(歩けない・標的はテストが決める)。 */
    private static DevelopedParasitized spawnWith(GameTestHelper helper, G1Developed d, Vec3 at, Class<? extends G1TechniqueGoal> only) {
        DevelopedParasitized m = (DevelopedParasitized) helper.spawn(ModG1.developed(d).get(), at);
        m.targetSelector.removeAllGoals(g -> true);
        m.goalSelector.removeAllGoals(g -> !only.isInstance(g));
        m.forceSkillsReady();
        return m;
    }

    private static Cow spawnCow(GameTestHelper helper, Vec3 at) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, at);
        cow.setNoAi(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        cow.setHealth(200.0F);
        return cow;
    }

    /**
     * 前触れの段が {@code expect} tick で終わる(次の段へ移る)。歩けなくして、的を置いて待つ。
     */
    private static void windupLasts(GameTestHelper helper, G1Developed d, Class<? extends G1TechniqueGoal> goal, double gapZ,
                                    G1Move windup, G1Move next, int expect) {
        DevelopedParasitized m = spawnWith(helper, d, new Vec3(12.5, 2.0, 6.5), goal);
        m.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
        Cow cow = spawnCow(helper, new Vec3(12.5, 2.0, 6.5 + gapZ));
        int[] windupTicks = {0};
        boolean[] reached = {false};
        helper.onEachTick(() -> {
            if (m.getTarget() != cow) m.setTarget(cow);
            if (reached[0]) return;
            if (m.action() == windup.id()) windupTicks[0]++;
            else if (windupTicks[0] > 0 && m.action() == next.id()) reached[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reached[0], "前触れから次の段へ移っていない(前触れ " + windupTicks[0] + " tick)");
            helper.assertTrue(Math.abs(windupTicks[0] - expect) <= 1, "前触れの長さが違う: " + windupTicks[0] + " tick(" + expect + " のはず)");
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void leapWindupIsShort(GameTestHelper helper) {
        windupLasts(helper, G1Developed.SPIDER, G1LeapGoal.class, 3.0D, G1Move.LEAP_WINDUP, G1Move.LEAP, G1SkillTiming.LEAP_WINDUP);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void spitWindupIsShort(GameTestHelper helper) {
        windupLasts(helper, G1Developed.SPIDER, G1SpitGoal.class, 8.0D, G1Move.SPIT_WINDUP, G1Move.SPIT, G1SkillTiming.SPIT_WINDUP);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void throatPuffWindupIsShort(GameTestHelper helper) {
        windupLasts(helper, G1Developed.PIG, G1ThroatPuffGoal.class, 2.5D, G1Move.PUFF_WINDUP, G1Move.PUFF, G1SkillTiming.THROAT_PUFF_WINDUP);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void spewWindupIsShort(GameTestHelper helper) {
        windupLasts(helper, G1Developed.CHICKEN, G1SpewGoal.class, 2.0D, G1Move.SPEW_WINDUP, G1Move.SPEW, G1SkillTiming.SPEW_WINDUP);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void vineLashWindupIsShort(GameTestHelper helper) {
        windupLasts(helper, G1Developed.ZOMBIE, G1VineLashGoal.class, 4.0D, G1Move.VINE_WINDUP, G1Move.VINE_LASH, G1SkillTiming.VINE_WINDUP);
    }

    /**
     * 蔦の鞭は振り下ろす tick まで標的を向き続ける: 横へ歩き続ける的(毎 tick 0.15 ブロック)にも巻き付く。
     * そのあいだ体の向きは 1 tick に {@link G1SkillTiming#AIM_TURN_DEG}° より多くは回らない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void vineLashTracksSidestepper(GameTestHelper helper) {
        DevelopedParasitized z = spawnWith(helper, G1Developed.ZOMBIE, new Vec3(8.5, 2.0, 6.5), G1VineLashGoal.class);
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
        Vec3 cowStart = helper.absoluteVec(new Vec3(8.5, 2.0, 10.5));
        Cow cow = spawnCow(helper, new Vec3(8.5, 2.0, 10.5));
        int[] n = {0};
        boolean[] started = {false};
        float[] maxTurn = {0.0F};
        float[] lastYaw = {Float.NaN};
        helper.onEachTick(() -> {
            if (z.getTarget() != cow) z.setTarget(cow);
            int a = z.action();
            boolean inLash = a == G1Move.VINE_WINDUP.id() || a == G1Move.VINE_LASH.id();
            if (inLash) started[0] = true;
            if (started[0] && z.tether() == null) {
                // 前触れが始まったら横へ歩き出す(+X)
                n[0]++;
                cow.teleportTo(cowStart.x + n[0] * 0.15D, cowStart.y, cowStart.z);
                cow.setDeltaMovement(Vec3.ZERO);
            }
            if (inLash) {
                if (!Float.isNaN(lastYaw[0])) maxTurn[0] = Math.max(maxTurn[0], Math.abs(Mth.wrapDegrees(z.getYRot() - lastYaw[0])));
                lastYaw[0] = z.getYRot();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(z.tether() != null, "横へ歩く的に巻き付いていない(歩いた " + n[0] + " tick)");
            helper.assertTrue(maxTurn[0] <= G1SkillTiming.AIM_TURN_DEG + 0.5F, "1 tick の回りが上限を超えた: " + maxTurn[0]);
        });
    }

    /** 振り向きの上限: 前触れの 1 tick 目に的が真横(90°)へ移っても、1 tick に {@link G1SkillTiming#AIM_TURN_DEG}° ずつしか回らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void aimTurnIsCapped(GameTestHelper helper) {
        DevelopedParasitized z = spawnWith(helper, G1Developed.ZOMBIE, new Vec3(8.5, 2.0, 6.5), G1VineLashGoal.class);
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
        Cow cow = spawnCow(helper, new Vec3(8.5, 2.0, 10.5));
        boolean[] moved = {false};
        float[] maxTurn = {0.0F};
        float[] total = {0.0F};
        float[] lastYaw = {Float.NaN};
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (z.getTarget() != cow) z.setTarget(cow);
            if (done[0]) return;
            int a = z.action();
            boolean inLash = a == G1Move.VINE_WINDUP.id() || a == G1Move.VINE_LASH.id();
            if (inLash && !moved[0]) {
                moved[0] = true;
                Vec3 side = helper.absoluteVec(new Vec3(12.5, 2.0, 6.5)); // 真横 4 ブロック
                cow.teleportTo(side.x, side.y, side.z);
            }
            if (inLash) {
                if (!Float.isNaN(lastYaw[0])) {
                    float d = Math.abs(Mth.wrapDegrees(z.getYRot() - lastYaw[0]));
                    maxTurn[0] = Math.max(maxTurn[0], d);
                    total[0] += d;
                }
                lastYaw[0] = z.getYRot();
            } else if (moved[0]) {
                done[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(done[0], "蔦の鞭がまだ終わっていない");
            helper.assertTrue(maxTurn[0] <= G1SkillTiming.AIM_TURN_DEG + 0.5F, "1 tick の回りが上限を超えた: " + maxTurn[0]);
            helper.assertTrue(total[0] >= 60.0F, "真横の的の方へ回っていない: " + total[0] + "°");
        });
    }

    /** 矢: 据えていなければ、弦を引く間も横へ歩く(足を止めない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void skeletonWalksWhileDrawing(GameTestHelper helper) {
        DevelopedParasitized s = spawnWith(helper, G1Developed.SKELETON, new Vec3(12.5, 2.0, 6.5), G1BowShotGoal.class);
        Cow cow = spawnCow(helper, new Vec3(12.5, 2.0, 14.5));
        Vec3[] drawStart = {null};
        double[] moved = {0.0D};
        int[] drawTicks = {0};
        helper.onEachTick(() -> {
            if (s.getTarget() != cow) s.setTarget(cow);
            if (s.action() == G1Move.SHOOT_WINDUP.id()) {
                if (drawStart[0] == null) drawStart[0] = s.position();
                drawTicks[0]++;
                Vec3 d = s.position().subtract(drawStart[0]);
                moved[0] = Math.max(moved[0], Math.sqrt(d.x * d.x + d.z * d.z));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(drawTicks[0] >= 15, "まだ弦を引き終えていない: " + drawTicks[0]);
            helper.assertTrue(!s.anchored(), "据えてしまった");
            helper.assertTrue(moved[0] >= 0.4D, "弦を引く間に歩いていない: " + moved[0] + " ブロック");
        });
    }
}
