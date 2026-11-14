package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyNavigation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModRunners;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ヴェナムネスの段 1(2026-11-14): 「② 大型の地上を走る型」の移動を使い回して歩くこと。テスト場は 7 × 7 しかないので、
 * 体長 8 ブロックの体のうち地形とぶつかる芯(幅 1.2)が通る場面を小さく作る(首・尾は外のバリアへはみ出してよい。当たりは芯だけ)。
 * 開けた所の速さはテスト場では作れない(外周のバリアが幹と数えられ、幹までの距離は 3.5 を超えない)ので、倍率の式だけを見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VenamnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(VenamnesGameTests.class);
        }

        private Registration() {
        }
    }

    private VenamnesGameTests() {
    }

    private static VenamnesEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        VenamnesEntity v = helper.spawnWithNoFreeWill(ModRunners.VENAMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setNoAi(false);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));   // うろつきで経路を上書きしない
        return v;
    }

    /** 歩かせる(経路が引けなければ失敗)。 */
    private static boolean walkTo(GameTestHelper helper, VenamnesEntity v, BlockPos goalRel, double speed) {
        BlockPos goal = helper.absolutePos(goalRel);
        Path path = v.getNavigation().createPath(goal, 0);
        if (path == null) {
            helper.fail("経路が引けない(" + goalRel + ")");
            return false;
        }
        v.getNavigation().moveTo(path, speed);
        return true;
    }

    /** G5 に入り、自然には湧かず、体の芯は 1.2 × 2.4。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG5WithoutNaturalSpawn(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_5) {
            helper.fail("G5 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (DesignData.mobs().forId(VenamnesEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(v.getBbWidth() - 1.2F) > 1e-3 || Math.abs(v.getBbHeight() - 2.4F) > 1e-3) {
            helper.fail("芯の大きさが 1.2 × 2.4 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.isOvergrownMob(v)) {
            helper.fail("過繁茂の Mob と見なされない(ほかの Mob から敵対されない)");
            return;
        }
        helper.succeed();
    }

    /**
     * 平地: 向こうの端まで歩き、体の向きに対して横へ滑らない(進むのは体の前へだけ。横の速さが 1 tick 0.05 ブロック以下)。
     * 経路の点の左右の位置は体の幅とノードの角で決まるので、まっすぐの線からのずれは見ない(回って向きを変えるのは正しい)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void walksForwardOnlyOnFlatGround(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 0.9D, 0.0F);
        double[] maxSide = {0.0D};
        double[] last = {v.getX(), v.getZ()};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D));
        helper.onEachTick(() -> {
            double dx = v.getX() - last[0], dz = v.getZ() - last[1];
            last[0] = v.getX();
            last[1] = v.getZ();
            float yaw = (v.yRotO + net.minecraft.util.Mth.wrapDegrees(v.getYRot() - v.yRotO) * 0.5F) * net.minecraft.util.Mth.DEG_TO_RAD;   // この tick の向きの真ん中
            double side = Math.abs(dx * net.minecraft.util.Mth.cos(yaw) + dz * net.minecraft.util.Mth.sin(yaw));
            maxSide[0] = Math.max(maxSide[0], side);
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 5.4D)).z) {
                if (maxSide[0] > 0.05D) {
                    helper.fail("体の向きに対して横へ滑った(1 tick 最大 " + String.format("%.3f", maxSide[0]) + ")");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /**
     * 幹の並ぶ森: 2 マスの隙間が互い違いの 2 列(z 1 は x 2〜3 が開き、z 4 は x 4〜5 が開く。列の間は 2 マス = 芯 1.2 が横向きに入る)を縫って抜ける。
     * 体は歩いている間に経路へ沿って曲がる(曲げが一度でも 5 度を超える)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void weavesThroughStaggeredTrunks(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 5; y++) {
                if (x != 2 && x != 3) helper.setBlock(new BlockPos(x, y, 1), Blocks.OAK_LOG.defaultBlockState());
                if (x != 4 && x != 5) helper.setBlock(new BlockPos(x, y, 4), Blocks.OAK_LOG.defaultBlockState());
            }
        }
        VenamnesEntity v = placeAt(helper, 3.0D, 0.6D, 0.0F);
        float[] maxBend = {0.0F};
        float[] a = new float[8];
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(4, 2, 5), 1.0D));   // 2 列目の隙間の先(体の位置 5.0, 6.0)
        helper.onEachTick(() -> {
            v.bend().angles(1.0F, a);
            for (float f : a) maxBend[0] = Math.max(maxBend[0], Math.abs(f));
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 5.0D)).z) {
                if (maxBend[0] < 5.0F) {
                    helper.fail("縫って歩く間に体が曲がらない(最大 " + maxBend[0] + " 度)");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 壁: x 0〜4 の壁(z 3)の向こうの目標へ、開いている x 5〜6 を回り込んで着く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void goesAroundWall(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        VenamnesEntity v = placeAt(helper, 1.5D, 1.2D, 0.0F);
        LongBodyNavigation.trace = true;
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(1, 2, 5), 1.0D));
        helper.onEachTick(() -> {
            if (v.getNavigation().isDone() && helper.getTick() > 10 && v.getZ() < helper.absoluteVec(new Vec3(0, 0, 4.0D)).z) {
                walkTo(helper, v, new BlockPos(1, 2, 5), 1.0D);   // 回り込んで経路が尽きたら引き直す(Goal の代わり)
            }
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.4D)).z) {
                LongBodyNavigation.trace = false;
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 段 A(11-15): 追う・噛みつく・飛びかかる

    /** 的: 動かない・日に焼けない・体力の多いハスク(噛まれた数を体力で数える)。 */
    private static net.minecraft.world.entity.monster.Husk dummy(GameTestHelper helper, double x, double z) {
        net.minecraft.world.entity.monster.Husk h = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        h.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        h.setNoAi(true);
        java.util.Objects.requireNonNull(h.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)).setBaseValue(500.0D);
        h.setHealth(500.0F);
        return h;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Math.atan2(to.z - from.z, to.x - from.x) * (180.0D / Math.PI)) - 90.0F;
    }

    /**
     * 噛みつき: 前触れ(0.5 秒 + 補間)の間は当たらず、実行の 2〜3 tick 目に 1 回だけ当たる。隙(0.8 秒)の間は動かず・回らない。
     * 的は真正面 3.5 ブロック(頭の真ん前)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void biteHitsAfterWindupThenHoldsStill(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 0.6D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = dummy(helper, 3.5D, 4.1D);
        v.setTarget(h);
        v.forceAction(VenamnesEntity.ACT_BITE_WINDUP);
        int hitAt = VenamnesEntity.BITE_T.executeStart();
        int[] tick = {0};
        double[] still = new double[3];
        helper.onEachTick(() -> {
            tick[0]++;
            int k = tick[0];
            float lost = 500.0F - h.getHealth();
            if (k < hitAt + 1 && lost > 0.0F) {
                helper.fail("前触れの間に当たった(" + k + " tick 目)");
                return;
            }
            if (k == hitAt + VenamnesEntity.BITE_T.execute() && lost <= 0.0F) {
                helper.fail("実行の間に当たらなかった");
                return;
            }
            if (v.getAction() == VenamnesEntity.ACT_BITE_RECOVER && v.actionTicks() == 1) {
                still[0] = v.getX();
                still[1] = v.getZ();
                still[2] = v.getYRot();
            }
            if (v.getAction() == VenamnesEntity.ACT_BITE_RECOVER && v.actionTicks() >= VenamnesEntity.BITE_T.recover() - 1) {
                double moved = Math.hypot(v.getX() - still[0], v.getZ() - still[1]);
                if (moved > 0.05D || Math.abs(net.minecraft.util.Mth.wrapDegrees(v.getYRot() - (float) still[2])) > 0.5F) {
                    helper.fail(String.format("隙の間に動いた(%.3f ブロック・%.1f 度)", moved, net.minecraft.util.Mth.wrapDegrees(v.getYRot() - (float) still[2])));
                    return;
                }
                float dealt = 500.0F - h.getHealth();
                double atk = v.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                if (dealt > atk * 1.5D) {
                    helper.fail("2 回以上当たった(" + dealt + ")");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /**
     * 飛びかかり: 前触れ(0.8 秒 + 補間)の後に跳び、決めた距離(頭が的に届く所)へ ±0.7 で着き、着地で 1 回当たる。着地の隙(1 秒)は
     * 滑りが収まった後(6 tick 目から)動かない。テスト場は 7 × 7 なので対角線(7.6 ブロック)で。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void pounceLandsThenFreezes(GameTestHelper helper) {
        Vec3 from = new Vec3(0.8D, 2.0D, 0.8D), to = new Vec3(6.2D, 2.0D, 6.2D);
        VenamnesEntity v = placeAt(helper, from.x, from.z, yawTo(from, to));
        net.minecraft.world.entity.monster.Husk h = dummy(helper, to.x, to.z);
        v.setTarget(h);
        v.forceAction(VenamnesEntity.ACT_POUNCE_WINDUP);
        double[] launch = new double[2];
        double[] land = new double[2];
        double[] settled = new double[2];
        boolean[] airborne = {false};
        helper.onEachTick(() -> {
            int act = v.getAction();
            if (act == VenamnesEntity.ACT_POUNCE_WINDUP && v.actionTicks() > 4 && !v.onGround()) {   // 湧いた直後の数 tick はまだ着地を数えていない
                helper.fail("前触れの間に地面を離れた");
                return;
            }
            if (act == VenamnesEntity.ACT_POUNCE && v.actionTicks() == VenamnesEntity.POUNCE_LIFTOFF) {
                launch[0] = v.getX();
                launch[1] = v.getZ();
            }
            if (act == VenamnesEntity.ACT_POUNCE && !v.onGround()) airborne[0] = true;
            if (act == VenamnesEntity.ACT_POUNCE_RECOVER && v.actionTicks() == 1) {
                land[0] = v.getX();
                land[1] = v.getZ();
            }
            if (act == VenamnesEntity.ACT_POUNCE_RECOVER && v.actionTicks() == 6) {
                settled[0] = v.getX();
                settled[1] = v.getZ();
            }
            if (act == VenamnesEntity.ACT_POUNCE_RECOVER && v.actionTicks() >= VenamnesEntity.POUNCE_RECOVER_T - 1) {
                if (!airborne[0]) {
                    helper.fail("跳ばなかった");
                    return;
                }
                double flew = Math.hypot(land[0] - launch[0], land[1] - launch[1]);
                if (Math.abs(flew - v.leapDistance()) > 0.7D) {
                    helper.fail(String.format("跳んだ距離 %.2f が決めた距離 %.2f と合わない", flew, v.leapDistance()));
                    return;
                }
                double moved = Math.hypot(v.getX() - settled[0], v.getZ() - settled[1]);
                if (moved > 0.05D) {
                    helper.fail(String.format("着地の隙に動いた(%.3f)", moved));
                    return;
                }
                if (h.getHealth() >= 500.0F) {
                    helper.fail(String.format("当たらなかった(跳んだ %.2f、的まで %.2f)", flew,
                            Math.hypot(h.getX() - v.getX(), h.getZ() - v.getZ())));
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 飛びかかりは跳ぶ前に向きを固める: 前触れの終わりに的が横へ出れば当たらない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void pounceMissesWhenTargetSidesteps(GameTestHelper helper) {
        Vec3 from = new Vec3(0.8D, 2.0D, 0.8D), to = new Vec3(6.2D, 2.0D, 6.2D);
        VenamnesEntity v = placeAt(helper, from.x, from.z, yawTo(from, to));
        net.minecraft.world.entity.monster.Husk h = dummy(helper, to.x, to.z);
        v.setTarget(h);
        v.forceAction(VenamnesEntity.ACT_POUNCE_WINDUP);
        helper.onEachTick(() -> {
            if (v.getAction() == VenamnesEntity.ACT_POUNCE_WINDUP && v.actionTicks() == VenamnesEntity.POUNCE_WINDUP_T - 2) {
                Vec3 side = helper.absoluteVec(new Vec3(6.2D, 2.0D, 2.8D));   // 跳ぶ線から横へ約 2.4 ブロック
                h.moveTo(side.x, side.y, side.z, 0.0F, 0.0F);
            }
            if (v.getAction() == VenamnesEntity.ACT_POUNCE_RECOVER && v.actionTicks() >= VenamnesEntity.POUNCE_RECOVER_T - 1) {
                if (h.getHealth() < 500.0F) {
                    helper.fail("横へ出たのに当たった");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 追う Goal: 標的を置くと寄って噛む(技を選ぶのは Goal)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void huntGoalClosesInAndBites(GameTestHelper helper) {
        VenamnesEntity v = helper.spawn(ModRunners.VENAMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(1.0D, 2.0D, 0.8D));
        v.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        v.snapFacing(0.0F);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));   // うろつかない・自分で標的を探さない
        net.minecraft.world.entity.monster.Husk h = dummy(helper, 5.5D, 6.0D);
        v.setTarget(h);
        boolean[] bit = {false};
        helper.onEachTick(() -> {
            if (v.getAction() == VenamnesEntity.ACT_BITE_WINDUP || v.getAction() == VenamnesEntity.ACT_POUNCE_WINDUP) bit[0] = true;
            if (h.getHealth() < 500.0F) {
                if (!bit[0]) {
                    helper.fail("技の前触れを通らずに当たった");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** まっすぐの下見: 平らなら通る、間に幹 1 本・2 段の段差・水があれば通らない、1 段の段差は通る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 5)
    public static void steerCorridorCheck(GameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(3.5D, 2.0D, 0.5D)), b = helper.absoluteVec(new Vec3(3.5D, 2.0D, 6.5D));
        var lvl = helper.getLevel();
        boolean open = com.yayuy.overgrownvegetation.entity.runner.ChaseSteer.clear(lvl, a, b, 0.6D, 24.0D);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE.defaultBlockState());   // 1 段の段差(1 マスだけ。体の幅の左右は平ら → 段の角で 1 段差 = 通る)
        boolean step1 = com.yayuy.overgrownvegetation.entity.runner.ChaseSteer.clear(lvl, a, b, 0.6D, 24.0D);
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.OAK_LOG.defaultBlockState());   // 幹(2 段に積む)
        boolean trunk = com.yayuy.overgrownvegetation.entity.runner.ChaseSteer.clear(lvl, a, b, 0.6D, 24.0D);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.WATER.defaultBlockState());   // 床の水(足場にならない)
        boolean water = com.yayuy.overgrownvegetation.entity.runner.ChaseSteer.clear(lvl, a, b, 0.6D, 24.0D);
        if (!open || !step1 || trunk || water) {
            helper.fail("下見が決まり通りでない: 平ら " + open + " 1 段 " + step1 + " 幹 " + trunk + " 水 " + water);
            return;
        }
        helper.succeed();
    }

    /** 開けた所の倍率: 幹まで 2 以下で 1、5 以上で上限、回っている間は 1 へ戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 5)
    public static void sprintOnlyInTheOpen(GameTestHelper helper) {
        double forest = VenamnesEntity.sprintTarget(1.5D, 0.0F), mid = VenamnesEntity.sprintTarget(3.5D, 0.0F);
        double open = VenamnesEntity.sprintTarget(6.0D, 0.0F), turning = VenamnesEntity.sprintTarget(6.0D, 9.0F);
        if (Math.abs(forest - 1.0D) > 1e-6 || Math.abs(open - VenamnesEntity.SPRINT_OPEN) > 1e-6 || !(mid > 1.0D && mid < open)
                || Math.abs(turning - 1.0D) > 1e-6) {
            helper.fail(String.format("倍率が決まり通りでない: 森 %.3f 中 %.3f 開け %.3f 回る %.3f", forest, mid, open, turning));
            return;
        }
        helper.succeed();
    }
}
