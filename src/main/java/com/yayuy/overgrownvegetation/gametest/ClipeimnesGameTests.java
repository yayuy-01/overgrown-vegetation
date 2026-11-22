package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModRunners;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * クリペイムネスの段 1(2026-11-15): 「② 大型の地上を走る型」の移動を使い回して歩くこと(作りはヴェナムネスのテストと同じ)。
 * テスト場は 7 × 7 しかないので、体のうち地形とぶつかる芯(幅 1.4)が通る場面を小さく作る(盾の腕・脚・尾は外のバリアへはみ出してよい)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ClipeimnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ClipeimnesGameTests.class);
        }

        private Registration() {
        }
    }

    private ClipeimnesGameTests() {
    }

    private static ClipeimnesEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        ClipeimnesEntity v = helper.spawnWithNoFreeWill(ModRunners.CLIPEIMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setNoAi(false);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));   // うろつきで経路を上書きしない
        return v;
    }

    private static boolean walkTo(GameTestHelper helper, ClipeimnesEntity v, BlockPos goalRel, double speed) {
        BlockPos goal = helper.absolutePos(goalRel);
        Path path = v.getNavigation().createPath(goal, 0);
        if (path == null) {
            helper.fail("経路が引けない(" + goalRel + ")");
            return false;
        }
        v.getNavigation().moveTo(path, speed);
        return true;
    }

    /** G3 に入り、自然には湧かず、体の芯は設計値の幅 × 高さ、ほかの Mob から敵対される。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void clipRegisteredAsG3WithoutNaturalSpawn(GameTestHelper helper) {
        ClipeimnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_3) {
            helper.fail("G3 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (Math.abs(v.getBbWidth() - com.yayuy.overgrownvegetation.data.MobStats.width(ClipeimnesEntity.ID, 0.0F)) > 1e-3
                || Math.abs(v.getBbHeight() - com.yayuy.overgrownvegetation.data.MobStats.height(ClipeimnesEntity.ID, 0.0F)) > 1e-3) {
            helper.fail("芯の大きさが設計値でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(v)) {
            helper.fail("過繁茂の Mob と見なされない(ほかの Mob から敵対されない)");
            return;
        }
        helper.succeed();
    }

    /** 平地: 向こうの端まで歩き、体の向きに対して横へ滑らない(横の速さが 1 tick 0.05 ブロック以下)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void clipWalksForwardOnlyOnFlatGround(GameTestHelper helper) {
        ClipeimnesEntity v = placeAt(helper, 3.5D, 0.9D, 0.0F);
        double[] maxSide = {0.0D};
        double[] last = {v.getX(), v.getZ()};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D));
        helper.onEachTick(() -> {
            double dx = v.getX() - last[0], dz = v.getZ() - last[1];
            last[0] = v.getX();
            last[1] = v.getZ();
            float yaw = (v.yRotO + Mth.wrapDegrees(v.getYRot() - v.yRotO) * 0.5F) * Mth.DEG_TO_RAD;
            double side = Math.abs(dx * Mth.cos(yaw) + dz * Mth.sin(yaw));
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

    /** 幹の並ぶ森: 2 マスの隙間が互い違いの 2 列を縫って抜け、歩いている間に体が経路へ沿って曲がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 360)
    public static void clipWeavesThroughStaggeredTrunks(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 5; y++) {
                if (x != 2 && x != 3) helper.setBlock(new BlockPos(x, y, 1), Blocks.OAK_LOG.defaultBlockState());
                if (x != 4 && x != 5) helper.setBlock(new BlockPos(x, y, 4), Blocks.OAK_LOG.defaultBlockState());
            }
        }
        ClipeimnesEntity v = placeAt(helper, 3.0D, 0.75D, 0.0F);
        float[] maxBend = {0.0F};
        float[] a = new float[8];
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(4, 2, 5), 1.0D));
        helper.onEachTick(() -> {
            v.bend().angles(1.0F, a);
            for (float f : a) maxBend[0] = Math.max(maxBend[0], Math.abs(f));
            if (v.getNavigation().isDone() && helper.getTick() > 10 && v.getZ() < helper.absoluteVec(new Vec3(0, 0, 5.0D)).z) {
                walkTo(helper, v, new BlockPos(4, 2, 5), 1.0D);
            }
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
    @GameTest(template = "gametest_empty7", timeoutTicks = 360)
    public static void clipGoesAroundWall(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        ClipeimnesEntity v = placeAt(helper, 1.5D, 1.2D, 0.0F);
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(1, 2, 5), 1.0D));
        helper.onEachTick(() -> {
            if (v.getNavigation().isDone() && helper.getTick() > 10 && v.getZ() < helper.absoluteVec(new Vec3(0, 0, 4.0D)).z) {
                walkTo(helper, v, new BlockPos(1, 2, 5), 1.0D);
            }
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.4D)).z) {
                helper.succeed();
            }
        });
    }

    /**
     * 小回り: 真後ろの目標へは弧を描かずにその場で向き直る。向きのずれが {@link ClipeimnesEntity#MOVE_OFF} より大きい間は進まない
     * (芯の中心が 0.3 ブロック以上動かない)うちに、向きが目標の側へ回りきる。回る速さは上限 {@link ClipeimnesEntity#TURN_PER_TICK} を超えない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void clipTurnsAroundOnTheSpot(GameTestHelper helper) {
        ClipeimnesEntity v = placeAt(helper, 3.5D, 4.5D, 0.0F);   // +Z を向いて置き、後ろ(−Z)へ歩かせる
        Vec3 start = v.position();
        double[] maxMove = {0.0D};
        float[] maxRate = {0.0F};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 1), 1.0D));
        helper.onEachTick(() -> {
            maxRate[0] = Math.max(maxRate[0], Math.abs(v.getTurnRate()));
            float off = Math.abs(Mth.wrapDegrees(180.0F - v.getYRot()));   // −Z を向くと yaw 180
            if (off > ClipeimnesEntity.MOVE_OFF + 5.0F) {
                maxMove[0] = Math.max(maxMove[0], Math.hypot(v.getX() - start.x, v.getZ() - start.z));
            } else if (helper.getTick() > 3) {
                if (maxMove[0] > 0.3D) {
                    helper.fail("向き直る間に動いた(" + String.format("%.2f", maxMove[0]) + " ブロック)= 弧を描いている");
                    return;
                }
                if (maxRate[0] > ClipeimnesEntity.TURN_PER_TICK + 0.05F) {
                    helper.fail("回る速さが上限を超えた: " + maxRate[0]);
                    return;
                }
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 技(11-15 段 3)

    /** 技を試す個体: 行動の Goal を残して湧かせる(spawnWithNoFreeWill は襲う Goal も外す)。名前 nostroll で自分では標的を探さない。 */
    private static ClipeimnesEntity placeActive(GameTestHelper helper, double x, double z, float yaw) {
        ClipeimnesEntity v = helper.spawn(ModRunners.CLIPEIMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));
        return v;
    }

    /** 的(ハスク。日に焼けない)を体の前 {@code ahead} ブロックに置き、動かないようにする。 */
    private static net.minecraft.world.entity.monster.Husk targetAhead(GameTestHelper helper, ClipeimnesEntity v, double ahead) {
        net.minecraft.world.entity.monster.Husk h = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(3, 2, 3));
        Vec3 p = v.position().add(0.0D, 0.0D, ahead);   // v は +Z を向いている
        h.moveTo(p.x, p.y, p.z, 180.0F, 0.0F);
        h.setNoAi(true);
        v.setTarget(h);
        return h;
    }

    /** 盾打ち: 前の相手を打って傷を与え、前へ弾き飛ばす。続けて打つと左右の板が入れ替わる(右 → 左)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void clipBashAlternatesSidesAndKnocksBack(GameTestHelper helper) {
        ClipeimnesEntity v = placeActive(helper, 3.5D, 0.9D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = targetAhead(helper, v, 3.6D);   // 抱え込みの届く所(3.4)の外・盾打ちの届く所(3.8)の内
        float hp0 = h.getHealth();
        java.util.List<Integer> windups = new java.util.ArrayList<>();
        boolean[] pushed = {false};
        int[] last = {ClipeimnesEntity.ACT_NONE};
        helper.onEachTick(() -> {
            int a = v.act();
            if (a != last[0] && (a == ClipeimnesEntity.ACT_BASH_R_WINDUP || a == ClipeimnesEntity.ACT_BASH_L_WINDUP)) windups.add(a);
            if ((a == ClipeimnesEntity.ACT_BASH_R || a == ClipeimnesEntity.ACT_BASH_L) && h.getDeltaMovement().z > 0.3D) pushed[0] = true;
            last[0] = a;
            if (windups.size() >= 2 && a == ClipeimnesEntity.ACT_NONE) {
                if (windups.get(0) != ClipeimnesEntity.ACT_BASH_R_WINDUP || windups.get(1) != ClipeimnesEntity.ACT_BASH_L_WINDUP) {
                    helper.fail("左右が入れ替わらない: " + windups);
                    return;
                }
                if (!(h.getHealth() < hp0 || pushed[0])) {
                    helper.fail("盾打ちが当たらない(体力 " + h.getHealth() + "・弾き " + pushed[0] + ")");
                    return;
                }
                helper.succeed();
            }
            if (v.act() == ClipeimnesEntity.ACT_NONE) h.moveTo(v.getX(), v.getY(), v.getZ() + 3.6D, 180.0F, 0.0F);   // 弾かれた的を戻す
        });
    }

    /** 抱え込み: 前の相手を抱え(乗せ)、3 秒締めて(締めのたびに傷)放す。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void clipGrabHoldsThreeSecondsThenLets(GameTestHelper helper) {
        ClipeimnesEntity v = placeActive(helper, 3.5D, 0.9D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = targetAhead(helper, v, 2.2D);
        v.forceActForTests(ClipeimnesEntity.ACT_GRAB_WINDUP);
        float hp0 = h.getHealth();
        int[] heldFrom = {-1};
        helper.onEachTick(() -> {
            if (heldFrom[0] < 0 && h.getVehicle() == v) heldFrom[0] = (int) helper.getTick();
            if (heldFrom[0] >= 0 && h.getVehicle() != v) {
                long held = helper.getTick() - heldFrom[0];
                if (held < 55 || held > 65) {
                    helper.fail("抱えた長さが 3 秒でない: " + held + " tick");
                    return;
                }
                if (!(h.getHealth() < hp0)) {
                    helper.fail("締めの傷が無い");
                    return;
                }
                helper.succeed();
            }
            if (helper.getTick() > 60 && heldFrom[0] < 0) helper.fail("抱えない(技 " + v.act() + ")");
        });
    }

    /** 抱え込み: 外から殴られて傷が溜まると放し、よろける(hold_recover)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void clipHoldBreaksWhenHitFromOutside(GameTestHelper helper) {
        ClipeimnesEntity v = placeActive(helper, 3.5D, 0.9D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = targetAhead(helper, v, 2.2D);
        net.minecraft.world.entity.monster.Husk other = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(6, 2, 1));
        other.setNoAi(true);
        v.forceActForTests(ClipeimnesEntity.ACT_GRAB_WINDUP);
        boolean[] hit = {false};
        helper.onEachTick(() -> {
            if (!hit[0] && v.act() == ClipeimnesEntity.ACT_HOLD && h.getVehicle() == v) {
                hit[0] = true;
                v.invulnerableTime = 0;
                v.hurt(v.damageSources().mobAttack(other), 7.0F);
            }
            if (hit[0] && v.act() == ClipeimnesEntity.ACT_HOLD_RECOVER) {
                if (h.getVehicle() == v) {
                    helper.fail("よろけたのに放していない");
                    return;
                }
                helper.succeed();
            }
            if (helper.getTick() > 70 && !hit[0]) helper.fail("抱えない(技 " + v.act() + ")");
        });
    }

    /** 盾で詰める: 構えている間、前から殴られても傷が通らず溜めが 1 段増え、後ろからは通る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void clipGuardBlocksFrontOnly(GameTestHelper helper) {
        ClipeimnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        v.setNoAi(true);   // 向き・構えを動かさない(構えはテストが立てる)
        v.guardNowForTests();
        net.minecraft.world.entity.monster.Husk front = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(3, 2, 6));
        net.minecraft.world.entity.monster.Husk back = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(3, 2, 0));
        front.setNoAi(true);
        back.setNoAi(true);
        helper.runAfterDelay(2L, () -> {
            float hp0 = v.getHealth();
            v.invulnerableTime = 0;
            v.hurt(v.damageSources().mobAttack(front), 5.0F);
            if (v.getHealth() != hp0 || v.charge() != 1) {
                helper.fail("前からの傷を弾かない(体力 " + hp0 + " → " + v.getHealth() + "・溜め " + v.charge() + ")");
                return;
            }
            v.invulnerableTime = 0;
            v.hurt(v.damageSources().mobAttack(back), 5.0F);
            if (!(v.getHealth() < hp0)) {
                helper.fail("後ろからの傷まで弾いた");
                return;
            }
            helper.succeed();
        });
    }

    /** 溜めの解放: 溜めが満ちていて前に相手がいれば、両の盾を打ち合わせて前方へ衝撃波(傷と弾き)。溜めは 0 に戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void clipReleaseWhenChargeIsFull(GameTestHelper helper) {
        ClipeimnesEntity v = placeActive(helper, 3.5D, 0.9D, 0.0F);
        net.minecraft.world.entity.monster.Husk h = targetAhead(helper, v, 4.0D);
        v.setChargeForTests(ClipeimnesEntity.CHARGE_MAX);
        float hp0 = h.getHealth();
        boolean[] released = {false};
        helper.onEachTick(() -> {
            if (v.act() == ClipeimnesEntity.ACT_RELEASE) released[0] = true;
            if (released[0] && v.act() == ClipeimnesEntity.ACT_RELEASE_RECOVER) {
                if (!(h.getHealth() < hp0) || v.charge() != 0) {
                    helper.fail("衝撃波が当たらない・溜めが残る(体力 " + h.getHealth() + "・溜め " + v.charge() + ")");
                    return;
                }
                helper.succeed();
            }
            if (helper.getTick() > 40 && !released[0] && v.act() == ClipeimnesEntity.ACT_NONE) helper.fail("溜めが満ちても解放しない");
        });
    }
    /**
     * 弾除け(決定 11-15): 仲間(植物側の Mob。ここではヴェナムネス)が同じ相手を狙っていると、相手と仲間の間へ割り込み、相手へ盾を構える。
     * 24 × 24 の場: 相手(弓を持つハスク)は z 20、仲間は z 6、クリペイムネスは横の x 4 から。割り込む所は仲間から相手の方へ 3.5 = z 9.5 あたり。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 400)
    public static void clipCoversAllyFromTarget(GameTestHelper helper) {
        net.minecraft.world.entity.monster.Husk h = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(12, 2, 20));
        h.setNoAi(true);
        h.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
        com.yayuy.overgrownvegetation.entity.VenamnesEntity ally = helper.spawnWithNoFreeWill(ModRunners.VENAMNES.get(), new BlockPos(12, 2, 6));
        ally.setNoAi(true);
        ally.setTarget(h);
        ClipeimnesEntity v = placeActive(helper, 4.5D, 10.5D, -90.0F);
        v.setTarget(h);
        Vec3 cover = helper.absoluteVec(new Vec3(12.5D, 2.0D, 10.0D));
        helper.onEachTick(() -> {
            v.setTarget(h);
            ally.setTarget(h);
            double d = Math.hypot(v.getX() - cover.x, v.getZ() - cover.z);
            if (v.isCovering() && d <= 1.5D && v.guardState() == ClipeimnesEntity.GUARD_UP) helper.succeed();
            if (helper.getTick() > 390) helper.fail(String.format("割り込めない: 割り込みに行く %s・割り込む所まで %.2f・構え %d", v.isCovering(), d, v.guardState()));
        });
    }
}
