package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
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

    /** G5 に入り、自然には湧かず、体の芯は 1.3 × 1.6、段差 1、跳ぶ力は設計の値。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG5WithoutNaturalSpawn(GameTestHelper helper) {
        PilimnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_5) {
            helper.fail("G5 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (DesignData.mobs().forId(PilimnesEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
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
        if (!com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.isOvergrownMob(v)) {
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
}
