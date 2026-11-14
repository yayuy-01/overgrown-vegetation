package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    /** G5 に入り、自然には湧かず、体の芯は設計値の幅 × 高さ、ほかの Mob から敵対される。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void clipRegisteredAsG5WithoutNaturalSpawn(GameTestHelper helper) {
        ClipeimnesEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        var stat = DesignData.mobs().forId(ClipeimnesEntity.ID);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_5) {
            helper.fail("G5 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (stat.naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(v.getBbWidth() - com.yayuy.overgrownvegetation.data.MobStats.width(ClipeimnesEntity.ID, 0.0F)) > 1e-3
                || Math.abs(v.getBbHeight() - com.yayuy.overgrownvegetation.data.MobStats.height(ClipeimnesEntity.ID, 0.0F)) > 1e-3) {
            helper.fail("芯の大きさが設計値でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.isOvergrownMob(v)) {
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
}
