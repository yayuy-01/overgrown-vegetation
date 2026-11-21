package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PilimnesEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ピリムネスの段 1(2026-11-15): 「トカゲ型」の移動で歩くこと。体長 9.5 ブロックのうち地形とぶつかるのは芯(幅 1.3)だけなので、
 * 7 × 7 のテスト場で芯が通る場面を作る(首・尾は外のバリアへはみ出してよい)。走っては止まる・物陰の脇で止まるは長い道が要るので 24 × 24 で。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PilimnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PilimnesGameTests.class);
        }

        private Registration() {
        }
    }

    private PilimnesGameTests() {
    }

    private static PilimnesEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        PilimnesEntity v = helper.spawnWithNoFreeWill(ModLizards.PILIMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setNoAi(false);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));   // うろつきで経路を上書きしない
        return v;
    }

    private static boolean walkTo(GameTestHelper helper, PilimnesEntity v, BlockPos goalRel, double speed) {
        BlockPos goal = helper.absolutePos(goalRel);
        Path path = v.getNavigation().createPath(goal, 0);
        if (path == null) {
            helper.fail("経路が引けない(" + goalRel + ")");
            return false;
        }
        v.getNavigation().moveTo(path, speed);
        return true;
    }

    /** G3 に入り、自然には湧かず、体の芯は 1.3 × 1.6、段差 1、跳ぶ力は設計の値。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG3WithoutNaturalSpawn(GameTestHelper helper) {
        PilimnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_3) {
            helper.fail("G3 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (Math.abs(v.getBbWidth() - 1.3F) > 1e-3 || Math.abs(v.getBbHeight() - 1.6F) > 1e-3) {
            helper.fail("芯の大きさが 1.3 × 1.6 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (Math.abs(v.maxUpStep() - 1.0F) > 1e-3 || Math.abs(v.getAttributeValue(Attributes.JUMP_STRENGTH) - PilimnesEntity.JUMP_STRENGTH) > 1e-6) {
            helper.fail("段差 " + v.maxUpStep() + "・跳ぶ力 " + v.getAttributeValue(Attributes.JUMP_STRENGTH) + " が決まり通りでない");
            return;
        }
        // 11-16: 弾きの射程 44(ユーザー「遠距離はもっと射程があっていい」で 2 倍)の相手を見つけられること
        if (v.getAttributeValue(Attributes.FOLLOW_RANGE) < 44.0D) {
            helper.fail("相手を見つける距離 " + v.getAttributeValue(Attributes.FOLLOW_RANGE) + " が弾きの射程 44 より短い");
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(v)) {
            helper.fail("過繁茂の Mob と見なされない(ほかの Mob から敵対されない)");
            return;
        }
        helper.succeed();
    }

    /** 平地: 向こうの端まで歩き、体の向きに対して横へ滑らない(1 tick 0.05 ブロック以下)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void walksForwardOnlyOnFlatGround(GameTestHelper helper) {
        PilimnesEntity v = placeAt(helper, 3.5D, 0.9D, 0.0F);
        double[] maxSide = {0.0D};
        double[] last = {v.getX(), v.getZ()};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 6), 1.0D));
        helper.runAtTickTime(190L, () -> stuckReport(helper, v, "190"));
        helper.onEachTick(() -> {
            double dx = v.getX() - last[0], dz = v.getZ() - last[1];
            last[0] = v.getX();
            last[1] = v.getZ();
            float yaw = (v.yRotO + Mth.wrapDegrees(v.getYRot() - v.yRotO) * 0.5F) * Mth.DEG_TO_RAD;
            double side = Math.abs(dx * Mth.cos(yaw) + dz * Mth.sin(yaw));
            maxSide[0] = Math.max(maxSide[0], side);
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 5.2D)).z) {   // 11-16: 幅 1.3 の体は最後の点(z 6.5)の 1 ブロック余り手前で「着いた」になる(5.37 で止まった回があった)
                if (maxSide[0] > 0.05D) {
                    helper.fail("ピリムネスが体の向きに対して横へ滑った(1 tick 最大 " + String.format("%.3f", maxSide[0]) + ")");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 2 ブロックの段(z 3 から先が 2 段高い台): 下から跳び上がって台の上の目標へ着く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void hopsUpTwoBlockLedge(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE.defaultBlockState());
            }
        }
        PilimnesEntity v = placeAt(helper, 3.5D, 0.9D, 0.0F);
        double topY = helper.absoluteVec(new Vec3(0, 4.0D, 0)).y;
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 4, 5), 1.0D));
        helper.onEachTick(() -> {
            if (v.getNavigation().isDone() && helper.getTick() > 10 && v.getY() < topY - 0.5D) {
                walkTo(helper, v, new BlockPos(3, 4, 5), 1.0D);
            }
            if (v.onGround() && v.getY() > topY - 0.01D && v.getZ() > helper.absoluteVec(new Vec3(0, 0, 3.6D)).z) {
                helper.succeed();
            }
        });
    }

    /** 物陰の判定: 体の横 2〜3 ブロックに高さ 2 の幹があれば物陰、高さ 1 の石や真正面の幹は物陰でない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10)
    public static void coverIsTwoHighBeside(GameTestHelper helper) {
        PilimnesEntity v = placeAt(helper, 3.5D, 0.5D, 0.0F);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        boolean empty = v.coverBeside(at, 0.0D, 1.0D);
        helper.setBlock(new BlockPos(3, 2, 5), Blocks.OAK_LOG.defaultBlockState());   // 真正面(前へ 2)
        helper.setBlock(new BlockPos(3, 3, 5), Blocks.OAK_LOG.defaultBlockState());
        boolean ahead = v.coverBeside(at, 0.0D, 1.0D);
        helper.setBlock(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState());     // 横に高さ 1
        boolean low = v.coverBeside(at, 0.0D, 1.0D);
        helper.setBlock(new BlockPos(1, 3, 3), Blocks.STONE.defaultBlockState());     // 横に高さ 2
        boolean beside = v.coverBeside(at, 0.0D, 1.0D);
        if (empty || ahead || low || !beside) {
            helper.fail("物陰の判定が決まり通りでない: 何も無い " + empty + " 真正面 " + ahead + " 高さ 1 " + low + " 横に高さ 2 " + beside);
            return;
        }
        helper.succeed();
    }

    /**
     * 走っては止まる(開けた所): 20 ブロックの直線を走る間に 1 回以上止まり、止まっている間は動かず向きも変えない。物陰が無いので物陰で止まった回数は 0。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 700)
    public static void dashesAndPausesInTheOpen(GameTestHelper helper) {
        PilimnesEntity v = placeAt(helper, 12.5D, 1.5D, 0.0F);
        double[] last = {v.getX(), v.getZ(), v.getYRot()};
        double[] worst = {0.0D, 0.0D};
        int[] pausedTicks = {0};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(12, 2, 22), 1.0D));
        helper.runAtTickTime(690L, () -> {   // 11-15: 500 tick で 5 回中 2 回時間切れ(原因は未確認)。落ちたときに様子が分かるように
            Vec3 rel = v.position().subtract(helper.absoluteVec(Vec3.ZERO));
            helper.fail(String.format(java.util.Locale.ROOT, "690 tick で着かない: 位置 %.2f %.2f %.2f 止まった %d 止まり中 %s 残り %.2f 経路 %s 速さ %.3f", rel.x, rel.y, rel.z,
                    v.pauses, v.isPaused(), v.dashLeft(), v.getNavigation().isDone() ? "なし" : v.getNavigation().getPath().getNextNodeIndex() + "/" + v.getNavigation().getPath().getNodeCount(),
                    v.getDeltaMovement().horizontalDistance()));
        });
        helper.onEachTick(() -> {
            double moved = Math.sqrt(Mth.square(v.getX() - last[0]) + Mth.square(v.getZ() - last[1]));
            double turned = Math.abs(Mth.wrapDegrees(v.getYRot() - (float) last[2]));
            if (v.isPaused()) {
                pausedTicks[0]++;
                if (pausedTicks[0] > 3) {   // 止まり始めの数 tick は惰性で少し滑る
                    worst[0] = Math.max(worst[0], moved);
                    worst[1] = Math.max(worst[1], turned);
                }
            } else {
                pausedTicks[0] = 0;
            }
            last[0] = v.getX();
            last[1] = v.getZ();
            last[2] = v.getYRot();
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 20.5D)).z) {
                if (v.pauses < 1) {
                    helper.fail("20 ブロックの間に一度も止まらなかった");
                } else if (worst[0] > 0.02D || worst[1] > 0.5D) {
                    helper.fail(String.format("止まっている間に動いた(1 tick 最大 %.3f ブロック・%.2f 度)", worst[0], worst[1]));
                } else if (v.coverPauses != 0) {
                    helper.fail("物陰の無い所で物陰の脇に止まったと数えた: " + v.coverPauses);
                } else {
                    helper.succeed();
                }
            }
        });
    }

    /** 物陰の脇で止まる: 道の両脇(横 3 ブロック)に 2 ブロックおきに幹が並ぶ所を走ると、止まった所はどれも幹の脇。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 500)
    public static void pausesBesideTrunks(GameTestHelper helper) {
        for (int z = 4; z <= 20; z += 2) {
            for (int y = 2; y <= 4; y++) {
                helper.setBlock(new BlockPos(9, y, z), Blocks.OAK_LOG.defaultBlockState());
                helper.setBlock(new BlockPos(15, y, z), Blocks.OAK_LOG.defaultBlockState());
            }
        }
        PilimnesEntity v = placeAt(helper, 12.5D, 1.5D, 0.0F);
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(12, 2, 22), 1.0D));
        helper.runAtTickTime(490L, () -> stuckReport(helper, v, "490"));
        helper.onEachTick(() -> {
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 20.5D)).z) {
                if (v.pauses < 1) {
                    helper.fail("一度も止まらなかった");
                } else if (v.coverPauses != v.pauses) {
                    helper.fail("幹の脇でない所で止まった: 止まった " + v.pauses + " 回のうち幹の脇 " + v.coverPauses + " 回");
                } else {
                    helper.succeed();
                }
            }
        });
    }

    /** 時間切れの前に様子を書いて落とす(11-16: 経路が途中で捨てられたかを見分ける)。 */
    private static void stuckReport(GameTestHelper helper, PilimnesEntity v, String when) {
        Vec3 rel = v.position().subtract(helper.absoluteVec(Vec3.ZERO));
        helper.fail(String.format(java.util.Locale.ROOT, "%s tick で着かない: 位置 %.2f %.2f %.2f 止まった %d 止まり中 %s 残り %.2f 経路 %s 標的 %s 技 %d", when,
                rel.x, rel.y, rel.z, v.pauses, v.isPaused(), v.dashLeft(),
                v.getNavigation().isDone() ? "なし" : v.getNavigation().getPath().getNextNodeIndex() + "/" + v.getNavigation().getPath().getNodeCount(),
                v.getTarget() == null ? "-" : v.getTarget().getType().toShortString(), v.act()));
    }

    // ------------------------------------------------------------------ 技(能力の段 11-16)

    /** 的: 動かない羊(体力を多くして、何発か受けても死なない)。 */
    private static net.minecraft.world.entity.animal.Sheep target(GameTestHelper helper, double x, double z) {
        net.minecraft.world.entity.animal.Sheep s = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.SHEEP, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        s.moveTo(p.x, p.y, p.z, 180.0F, 0.0F);
        s.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200.0D);
        s.setHealth(200.0F);
        return s;
    }

    /** 技を試す個体: 襲う Goal を残して湧かせる(spawnWithNoFreeWill は Goal を全部外す。クリペイムネスのテストと同じ)。名前 nostroll で自分では標的を探さない。 */
    private static PilimnesEntity placeActive(GameTestHelper helper, double x, double z, float yaw) {
        PilimnesEntity v = helper.spawn(ModLizards.PILIMNES.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));
        return v;
    }

    /** 技を出させる: 標的を置き、次の判断で出す技を決める(名前は nostroll = 自分では探さない・うろつかない)。 */
    private static PilimnesEntity shooter(GameTestHelper helper, LivingEntityHolder t, int act) {
        PilimnesEntity v = placeActive(helper, 12.5D, 2.5D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            v.setTarget(t.get());
            v.forceActForTests(act);
        });
        return v;
    }

    @FunctionalInterface
    private interface LivingEntityHolder {
        net.minecraft.world.entity.LivingEntity get();
    }

    /** 棘の弾き: 11 ブロック先の的へ 1 本。当たって傷を負わせ、足が鈍る(鈍足)。前触れの 16 tick(補間 3 込み 19)の後に撃つ。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void flickHitsAndSlows(GameTestHelper helper) {
        var s = target(helper, 12.5D, 13.5D);
        PilimnesEntity v = shooter(helper, () -> s, PilimnesEntity.ACT_FLICK_WINDUP);
        helper.onEachTick(() -> {
            if (s.getHealth() < 200.0F && s.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)) {
                if (helper.getTick() < 2 + PilimnesHuntGoal_FLICK_SHOT_TICK) {
                    helper.fail("前触れより早く当たった(" + helper.getTick() + " tick)");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 弾きの棘が出るまでの tick(前触れ 補間 3 + 16、実行の 2 tick 目)。 */
    private static final int PilimnesHuntGoal_FLICK_SHOT_TICK = 3 + 16 + 2;

    /** 扇の散らし撃ち: 7 ブロック先の的へ 3 本が同時に飛び、少なくとも 1 本が当たる。足は鈍らない。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 100)
    public static void fanShootsThree(GameTestHelper helper) {
        var s = target(helper, 12.5D, 9.5D);
        PilimnesEntity v = shooter(helper, () -> s, PilimnesEntity.ACT_FAN_WINDUP);
        int[] most = {0};
        helper.onEachTick(() -> {
            int n = helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.PilimnesSpine.class, v.getBoundingBox().inflate(24.0D),
                    e -> e.getOwner() == v && e.kind() == com.yayuy.overgrownvegetation.entity.PilimnesSpine.FAN).size();
            most[0] = Math.max(most[0], n);
            if (s.getHealth() < 200.0F) {
                if (most[0] < 3) {
                    helper.fail("扇の棘が 3 本でない: " + most[0]);
                } else if (s.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)) {
                    helper.fail("扇で足が鈍った(鈍るのは弾きだけ)");
                } else {
                    helper.succeed();
                }
            }
        });
    }

    /** 噛みつき: 体の前 4 ブロックの的を、前触れ(補間 3 + 8)の後に噛む。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 60)
    public static void biteHitsInFront(GameTestHelper helper) {
        var s = target(helper, 12.5D, 6.5D);
        PilimnesEntity v = shooter(helper, () -> s, PilimnesEntity.ACT_BITE_WINDUP);
        helper.onEachTick(() -> {
            if (s.getHealth() < 200.0F) {
                if (helper.getTick() < 2 + 3 + 8 + 3) {
                    helper.fail("前触れより早く噛んだ(" + helper.getTick() + " tick)");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /**
     * 棘の雨: 8 本を空へ放ち、約 1.5 秒後に的のまわり(半径 2.5 + 余裕)へ落ちる弧で飛ぶ。落ちる所の印は出さない。
     * テスト場には天井のバリアがあり(弧の頂は放った所の約 6.5 ブロック上)、本当に落ちるのを待つと天井に当たって消えるので、
     * 放った瞬間の位置と速さから弧をバニラと同じ式(位置 += 速さ → 速さ × 0.99 − 重力)で地面までなぞって、落ちる所を見る(11-16)。
     * 体は前 5.5 に置く(尾と後ろの棘がテスト場の壁のバリアに入らないように。2.5 だと後ろの棘が壁の中で生まれて止まった)。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void rainFallsAroundTarget(GameTestHelper helper) {
        var s = target(helper, 12.5D, 17.5D);
        PilimnesEntity v = placeActive(helper, 12.5D, 5.5D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            v.setTarget(s);
            v.forceActForTests(PilimnesEntity.ACT_RAIN_WINDUP);
        });
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.List<String> far = new java.util.ArrayList<>();
        int[] near = {0};
        helper.onEachTick(() -> {
            for (var e : helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.PilimnesSpine.class, v.getBoundingBox().inflate(32.0D),
                    e -> e.getOwner() == v && e.kind() == com.yayuy.overgrownvegetation.entity.PilimnesSpine.RAIN)) {
                if (!seen.add(e.getId())) continue;
                Vec3 p = e.position(), vel = e.getDeltaMovement();
                for (int k = 0; k < 80 && !(vel.y < 0.0D && p.y <= s.getY() + 0.1D); k++) {
                    p = p.add(vel);
                    vel = vel.scale(0.99D).subtract(0.0D, com.yayuy.overgrownvegetation.entity.PilimnesSpine.RAIN_GRAVITY, 0.0D);
                }
                double d = Math.hypot(p.x - s.getX(), p.z - s.getZ());
                if (d <= 2.5D + 1.0D) near[0]++;
                else far.add(String.format(java.util.Locale.ROOT, "%.1f", d));
            }
            if (seen.size() >= 8) {
                if (near[0] < 8) {
                    helper.fail("雨の棘の弧が的のまわりに落ちない: 8 本のうち " + near[0] + " 本(外れの距離 " + far + ")");
                } else {
                    helper.succeed();
                }
            }
        });
    }

    /** 前触れは殴っても潰せない(決定 11-15): 弾きの前触れの間に殴っても、棘は撃たれる。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void windupIsNotInterruptedByHits(GameTestHelper helper) {
        var s = target(helper, 12.5D, 13.5D);
        PilimnesEntity v = shooter(helper, () -> s, PilimnesEntity.ACT_FLICK_WINDUP);
        helper.runAtTickTime(10L, () -> v.hurt(helper.getLevel().damageSources().generic(), 3.0F));
        helper.runAtTickTime(14L, () -> v.hurt(helper.getLevel().damageSources().generic(), 3.0F));
        helper.onEachTick(() -> {
            if (v.actCountsForTests()[0] >= 1) helper.succeed();
        });
    }

    /** 敵として襲う: 技を決めずに的を置くと、自分で技を選んで撃つ(12 ブロック先 = 弾きの間合い)。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 200)
    public static void attacksTargetOnItsOwn(GameTestHelper helper) {
        var s = target(helper, 12.5D, 14.5D);
        PilimnesEntity v = shooter(helper, () -> s, PilimnesEntity.ACT_NONE);
        helper.onEachTick(() -> {
            if (s.getHealth() < 200.0F) helper.succeed();
        });
    }
}
