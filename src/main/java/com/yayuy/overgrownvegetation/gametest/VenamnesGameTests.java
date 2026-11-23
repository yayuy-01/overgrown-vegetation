package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

    /** G3 に入り、自然には湧かず、体の芯は 1.2 × 2.4。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG3WithoutNaturalSpawn(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_3) {
            helper.fail("G3 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (Math.abs(v.getBbWidth() - 1.2F) > 1e-3 || Math.abs(v.getBbHeight() - 2.4F) > 1e-3) {
            helper.fail("芯の大きさが 1.2 × 2.4 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(v)) {
            helper.fail("過繁茂の Mob と見なされない(ほかの Mob から敵対されない)");
            return;
        }
        helper.succeed();
    }

    /**
     * 横滑りの上限: 速さ 0.30 のときの 0.05 ブロック/tick を、地面の速さ(≈ 2.16 × 速さ²)の比で伸ばす。
     * 11-18 夜 ユーザー決定で速さを 0.34 に上げたら、曲がるときの横の速さも同じ割合(地面の速さの約 25%)のまま 0.062 になった。
     */
    private static double slipLimit() {
        Double s = com.yayuy.overgrownvegetation.data.DesignData.mobs().forId(VenamnesEntity.ID).movementSpeed;
        double speed = s != null ? s : 0.30D;
        return 0.05D * (speed * speed) / (0.30D * 0.30D) + 0.003D;
    }

    /**
     * 平地: 向こうの端まで歩き、体の向きに対して横へ滑らない(進むのは体の前へだけ。横の速さが 1 tick 0.05 ブロック以下 × 地面の速さの比)。
     * 経路の点の左右の位置は体の幅とノードの角で決まるので、まっすぐの線からのずれは見ない(回って向きを変えるのは正しい)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void walksForwardOnlyOnFlatGround(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 0.9D, 0.0F);
        double[] maxSide = {0.0D};
        double[] last = {v.getX(), v.getZ()};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D));
        int[] rewalks = {0};
        helper.onEachTick(() -> {
            // 11-16: ほかのセッションの全体の流しで「着かない(時間切れ)」が 2 回出た(単独 200 個・自分の全体 8 回では 0 件)。
            // 経路が途中で終わった・止められたら引き直し(ほかの歩くテストと同じ)、時間切れの前に様子を書いて落とす
            if (helper.getTick() > 10 && v.getNavigation().isDone() && v.getZ() < helper.absoluteVec(new Vec3(0, 0, 5.4D)).z) {
                rewalks[0]++;
                walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D);
            }
            if (helper.getTick() >= 150) {
                Vec3 r = v.position().subtract(helper.absoluteVec(Vec3.ZERO));
                helper.fail(String.format(java.util.Locale.ROOT, "着かない: 位置 (%.2f, %.2f, %.2f) 向き %.0f 引き直し %d 動作 %d 標的 %s 最後に殴った %s 接地 %s",
                        r.x, r.y, r.z, v.getYRot(), rewalks[0], v.getAction(), v.getTarget() == null ? "-" : v.getTarget().getType().toShortString(),
                        v.getLastHurtByMob() == null ? "-" : v.getLastHurtByMob().getType().toShortString(), v.onGround()));
                return;
            }
            double dx = v.getX() - last[0], dz = v.getZ() - last[1];
            last[0] = v.getX();
            last[1] = v.getZ();
            float yaw = (v.yRotO + net.minecraft.util.Mth.wrapDegrees(v.getYRot() - v.yRotO) * 0.5F) * net.minecraft.util.Mth.DEG_TO_RAD;   // この tick の向きの真ん中
            double side = Math.abs(dx * net.minecraft.util.Mth.cos(yaw) + dz * net.minecraft.util.Mth.sin(yaw));
            maxSide[0] = Math.max(maxSide[0], side);
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 5.4D)).z) {
                if (maxSide[0] > slipLimit()) {   // 11-18 夜: 速さ 0.30 → 0.34 に合わせて、地面の速さの比で
                    helper.fail("ヴェナムネスが体の向きに対して横へ滑った(1 tick 最大 " + String.format("%.3f", maxSide[0]) + ")");
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

    // ------------------------------------------------------------------ 段 B(11-15): 足噛み・狩りの疾走

    /** 首の横(前へ 2.5・左へ 1.8)に的を置く。{@code facing} なら的はヴェナムネスの方を向く、そうでなければ同じ向き(背を向けて走っている形)。 */
    private static net.minecraft.world.entity.monster.Husk sideDummy(GameTestHelper helper, VenamnesEntity v, boolean facing) {
        net.minecraft.world.entity.monster.Husk h = dummy(helper, 3.5D + 1.8D, 0.6D + 2.5D);   // 南向き(yaw 0)の左 = 東(+x)
        float yaw = facing ? yawTo(h.position(), v.position()) : 0.0F;
        h.setYRot(yaw);
        h.setYHeadRot(yaw);
        h.yHeadRotO = yaw;
        return h;
    }

    /** 足噛み: 首の横の背を向けた相手には、前触れ(0.3 秒)の後に当たり、足を鈍らせる(移動の遅さ)。左の相手には左の首振り。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void nipHitsSideTargetAndSlows(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 0.6D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = sideDummy(helper, v, false);
        v.setTarget(h);
        helper.runAfterDelay(1L, () -> {
            v.forceNip();
            if (v.getNip() != VenamnesEntity.NIP_L_WINDUP) helper.fail("左の相手に左の足噛みにならない: " + v.getNip());
        });
        helper.runAfterDelay(2L + VenamnesEntity.NIP_WINDUP_T + VenamnesEntity.NIP_EXEC_T + 2L, () -> {
            if (v.nipResult() != 1 || h.getHealth() >= 500.0F || !h.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)) {
                helper.fail("足噛みが当たって鈍らせない: 結果 " + v.nipResult() + " 体力 " + h.getHealth());
                return;
            }
            if (v.isActing()) {
                helper.fail("足噛みが技の最中として走りを止める");
                return;
            }
            helper.succeed();
        });
    }

    /** 足噛み: 相手がこちらを向いている(振り向いて向き合う)と行き過ぎて当たらない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void nipMissesWhenTargetFacesIt(GameTestHelper helper) {
        VenamnesEntity v = placeAt(helper, 3.5D, 0.6D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = sideDummy(helper, v, true);
        v.setTarget(h);
        helper.runAfterDelay(1L, v::forceNip);
        helper.runAfterDelay(2L + VenamnesEntity.NIP_WINDUP_T + VenamnesEntity.NIP_EXEC_T + 2L, () -> {
            if (v.nipResult() != 2 || h.getHealth() < 500.0F || h.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)) {
                helper.fail("向き合った相手に当たった: 結果 " + v.nipResult() + " 体力 " + h.getHealth());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 狩りの疾走: 叫び(1.4 秒 + 補間)の間は動かず、疾走から最後の飛びかかりで当て、着地の後の息切れ(3 秒)は動かない。
     * テスト場は狭いので、叫びの終わりにもう飛びかかれる距離(対角線 7.6)に的を置く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void huntEndsWithPounceAndLongRecovery(GameTestHelper helper) {
        Vec3 from = new Vec3(0.8D, 2.0D, 0.8D), to = new Vec3(6.2D, 2.0D, 6.2D);
        VenamnesEntity v = placeAt(helper, from.x, from.z, yawTo(from, to));
        net.minecraft.world.entity.monster.Husk h = dummy(helper, to.x, to.z);
        v.setTarget(h);
        v.forceAction(VenamnesEntity.ACT_HUNT_WINDUP);
        java.util.List<Integer> seq = new java.util.ArrayList<>();
        double[] start = {v.getX(), v.getZ()};
        double[] rest = new double[2];
        helper.onEachTick(() -> {
            int act = v.getAction();
            if (seq.isEmpty() || seq.get(seq.size() - 1) != act) seq.add(act);
            if (act == VenamnesEntity.ACT_HUNT_WINDUP && Math.hypot(v.getX() - start[0], v.getZ() - start[1]) > 0.05D) {
                helper.fail("叫びの間に動いた");
                return;
            }
            if (act == VenamnesEntity.ACT_HUNT_RECOVER && v.actionTicks() == 1) {
                rest[0] = v.getX();
                rest[1] = v.getZ();
            }
            if (act == VenamnesEntity.ACT_HUNT_RECOVER && v.actionTicks() >= VenamnesEntity.HUNT_RECOVER_T - 1) {
                if (Math.hypot(v.getX() - rest[0], v.getZ() - rest[1]) > 0.05D) {
                    helper.fail("息切れの間に動いた");
                    return;
                }
                if (!seq.contains(VenamnesEntity.ACT_HUNT_SPRINT) || !seq.contains(VenamnesEntity.ACT_HUNT_POUNCE) || !seq.contains(VenamnesEntity.ACT_HUNT_LAND)) {
                    helper.fail("疾走 → 飛びかかり → 着地 を通っていない: " + seq);
                    return;
                }
                if (h.getHealth() >= 500.0F) {
                    helper.fail("最後の飛びかかりが当たらない");
                    return;
                }
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 段 C(11-15): 段差・水・性格

    /** 段差: 3 段の崖(z 4 から奥。x は全部)の上へ、経路が「跳べば届く」を選び、跳び上がって着く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void leapsUpThreeBlockLedge(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) for (int z = 4; z <= 6; z++) for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        VenamnesEntity v = placeAt(helper, 3.5D, 0.8D, 0.0F);
        boolean[] leapt = {false};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 5, 5), 1.0D));
        helper.onEachTick(() -> {
            if (v.getAction() == VenamnesEntity.ACT_LEAP) leapt[0] = true;
            if (helper.getTick() > 10 && v.getNavigation().isDone() && !v.isActing() && v.getY() < helper.absoluteVec(new Vec3(0, 4.9D, 0)).y) {
                walkTo(helper, v, new BlockPos(3, 5, 5), 1.0D);
            }
            if (v.getY() >= helper.absoluteVec(new Vec3(0, 4.95D, 0)).y && v.onGround() && !v.isActing()) {
                if (!leapt[0]) {
                    helper.fail("跳び上がりを通らずに上った");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 段差: 4 段の壁は跳べない(経路が届かない・上らない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void doesNotLeapFourBlockWall(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) for (int z = 4; z <= 6; z++) for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        VenamnesEntity v = placeAt(helper, 3.5D, 0.8D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            Path p = v.getNavigation().createPath(helper.absolutePos(new BlockPos(3, 6, 5)), 0);
            BlockPos goal = helper.absolutePos(new BlockPos(3, 6, 5));
            if (p != null && p.getEndNode() != null && p.getEndNode().asBlockPos().distManhattan(goal) <= 2) {   // canReach は回り道の途中の目標へ届くことでも true になるので、終わりの点で見る
                helper.fail("4 段の上へ届く経路が引けた");
                return;
            }
            if (p != null) v.getNavigation().moveTo(p, 1.0D);
        });
        helper.runAfterDelay(110L, () -> {
            if (v.getY() > helper.absoluteVec(new Vec3(0, 3.0D, 0)).y || v.getAction() == VenamnesEntity.ACT_LEAP) {
                helper.fail("4 段の壁へ跳んだ: y " + (v.getY() - helper.absoluteVec(Vec3.ZERO).y));
                return;
            }
            helper.succeed();
        });
    }

    /** 水: 深さ 2・幅 3 の水路(z 2〜4。テスト場の幅いっぱいで回り道は無い)を泳いで向こう岸へ渡る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void swimsAcrossChannel(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 0, z), Blocks.WATER.defaultBlockState());
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER.defaultBlockState());
            }
        }
        VenamnesEntity v = hunter(helper, 0.0F);   // 浮く Goal が要る(spawnWithNoFreeWill は外す)。名前 nostroll = うろつかない・標的を探さない
        boolean[] swam = {false};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D));
        helper.onEachTick(() -> {
            if (v.isInWater()) swam[0] = true;
            if (helper.getTick() > 10 && v.getNavigation().isDone() && v.getZ() < helper.absoluteVec(new Vec3(0, 0, 5.2D)).z) {
                walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D);
            }
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 5.2D)).z && v.onGround()) {
                if (!swam[0]) {
                    helper.fail("水に入らずに渡った");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 性格の確かめ用: 追う Goal の動くヴェナムネスと、こちらを向いた偽プレイヤー(2.2 ブロック先。テスト場が狭いので、つけ回しの距離より近い = 止まって向き直る所)。 */
    private static VenamnesEntity hunter(GameTestHelper helper, float caution) {
        VenamnesEntity v = helper.spawn(ModRunners.VENAMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(3.5D, 2.0D, 0.8D));
        v.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        v.snapFacing(0.0F);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));
        v.setCaution(caution);
        return v;
    }

    private static net.minecraft.world.entity.player.Player watcher(GameTestHelper helper, VenamnesEntity v) {
        net.minecraft.world.entity.player.Player pl = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 at = helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.0D));
        pl.moveTo(at.x, at.y, at.z, 180.0F, 0.0F);   // 北(ヴェナムネスの方)を向く
        pl.setYHeadRot(180.0F);
        v.setTarget(pl);
        return pl;
    }

    /**
     * 性格: 慎重な個体は、見ている(こちらを向いた)プレイヤーの前では止まって固まり、噛みに来ない。プレイヤーが背を向けて離れていくと、
     * その瞬間に狩りの疾走(叫び)を始める。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void cautiousFreezesWhenWatchedAndSprintsWhenBackTurned(GameTestHelper helper) {
        VenamnesEntity v = hunter(helper, 1.0F);
        net.minecraft.world.entity.player.Player pl = watcher(helper, v);
        double[] start = new double[2];
        helper.runAfterDelay(3L, () -> {
            start[0] = v.getX();
            start[1] = v.getZ();
        });
        helper.runAfterDelay(60L, () -> {
            if (v.prowlMode() != VenamnesEntity.PROWL_FREEZE || v.isActing() || Math.hypot(v.getX() - start[0], v.getZ() - start[1]) > 0.3D) {
                helper.fail("見られているのに固まらない: 構え " + v.prowlMode() + " 動作 " + v.getAction());
                return;
            }
            pl.setYRot(0.0F);
            pl.setYHeadRot(0.0F);   // 背を向ける(南)
        });
        helper.onEachTick(() -> {
            if (helper.getTick() > 61) {
                pl.setPos(pl.getX(), pl.getY(), Math.min(pl.getZ() + 0.25D, helper.absoluteVec(new Vec3(0, 0, 6.6D)).z));   // 離れていく
            }
            if (helper.getTick() > 61 && v.getAction() == VenamnesEntity.ACT_HUNT_WINDUP) helper.succeed();
        });
    }

    /** 性格: 大胆な個体は見られていても固まらず、真っすぐ寄って噛む。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void boldIgnoresGazeAndBites(GameTestHelper helper) {
        VenamnesEntity v = hunter(helper, 0.0F);
        watcher(helper, v);
        helper.onEachTick(() -> {
            if (v.prowlMode() != VenamnesEntity.PROWL_NONE) {
                helper.fail("大胆な個体がつけ回した");
                return;
            }
            if (v.getAction() == VenamnesEntity.ACT_BITE_WINDUP || v.getAction() == VenamnesEntity.ACT_POUNCE_WINDUP) helper.succeed();
        });
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
