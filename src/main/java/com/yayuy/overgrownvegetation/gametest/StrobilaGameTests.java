package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.StrobilaEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ストロビラの段 1(2026-11-15): 「小さな地這いの虫」の型の 2 体目。1 ブロックの段は這い上がる・2 段の壁は越えない・
 * 1 マス幅の通路は通る・高さ 1 の隙間はくぐらない(尾が天井を抜けないように)・急ぎは walk 2.5 倍の速さで止まる・
 * 描画の外接箱が脚と尾を覆う。うろつき(小走り)は近くにプレイヤーがいるときだけなのでここでは見ない(テストクライアントで見る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StrobilaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(StrobilaGameTests.class);
        }

        private Registration() {
        }
    }

    private StrobilaGameTests() {
    }

    private static StrobilaEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        StrobilaEntity s = helper.spawnWithNoFreeWill(ModCrawlers.STROBILA.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        s.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        s.snapFacing(yaw);
        s.setNoAi(false);
        s.setCustomName(Component.literal("nostroll"));
        return s;
    }

    private static void walkTo(StrobilaEntity s, GameTestHelper helper, Vec3 rel) {
        Vec3 p = helper.absoluteVec(rel);
        s.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
    }

    /** G4 に入り、自然には湧かず、芯は 0.95 × 1.2、過繁茂の Mob と見なされる。描画の外接箱は脚と尾(全体 3.6 × 2.8 × 5.5)を覆う。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void strobilaRegisteredAsG4WithWideCullingBox(GameTestHelper helper) {
        StrobilaEntity s = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(s.getType()) != MobGroup.GROUP_4) {
            helper.fail("G4 に入っていない: " + MobGroup.of(s.getType()));
            return;
        }
        if (DesignData.mobs().forId(StrobilaEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(s.getBbWidth() - 0.95F) > 1e-3 || Math.abs(s.getBbHeight() - 1.2F) > 1e-3) {
            helper.fail("芯の大きさが 0.95 × 1.2 でない: " + s.getBbWidth() + " × " + s.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.isOvergrownMob(s)) {
            helper.fail("過繁茂の Mob と見なされない");
            return;
        }
        AABB c = s.getBoundingBoxForCulling();
        if (c.getXsize() < 5.5D || c.getZsize() < 5.5D || c.maxY - s.getY() < 2.8D) {
            helper.fail(String.format("描画の外接箱が狭い(%.2f × %.2f × 上 %.2f)", c.getXsize(), c.getZsize(), c.maxY - s.getY()));
            return;
        }
        helper.succeed();
    }

    /** 1 ブロックの段(z 3〜6 の台)へ這い上がる。跳ばない(上る間に足の高さが段の上 0.3 を超えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void strobilaClimbsOneBlockStep(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double top = helper.absoluteVec(new Vec3(0, 3.0D, 0)).y;
        double[] maxY = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2 && s.getNavigation().isDone()) walkTo(s, helper, new Vec3(3.5D, 3.0D, 5.5D));
            maxY[0] = Math.max(maxY[0], s.getY());
            if (s.getY() >= top - 0.01D && s.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.0D)).z) {
                if (maxY[0] > top + 0.3D) {
                    helper.fail(String.format("段を跳んで上った(最高 %.2f、段の上 %.2f)", maxY[0], top));
                    return;
                }
                helper.succeed();
            }
        });
        helper.runAtTickTime(190L, () -> helper.fail("上れない: " + where(helper, s)));
    }

    /** 失敗の文: 場の中の位置と、経路(届くか・点の数・終わり)。 */
    private static String where(GameTestHelper helper, StrobilaEntity s) {
        Vec3 o = helper.absoluteVec(Vec3.ZERO);
        net.minecraft.world.level.pathfinder.Path p = s.getNavigation().getPath();
        return String.format("位置 %.2f %.2f %.2f 向き %.0f、経路 %s", s.getX() - o.x, s.getY() - o.y, s.getZ() - o.z, s.getYRot(),
                p == null ? "なし" : (p.canReach() ? "届く " : "届かない ") + p.getNextNodeIndex() + "/" + p.getNodeCount()
                        + " 終わり " + p.getTarget().subtract(helper.absolutePos(BlockPos.ZERO)));
    }

    /** 2 段の壁(z 3 の全幅)は越えない: 向こう側へ行かせても 100 tick 手前に残る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void strobilaStopsAtTwoBlockWall(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double wall = helper.absoluteVec(new Vec3(0, 0, 3.0D)).z;
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2) walkTo(s, helper, new Vec3(3.5D, 2.0D, 6.5D));
            if (s.getZ() > wall) helper.fail(String.format("越えた(z %.2f、y %.2f)", s.getZ() - wall, s.getY()));
        });
        helper.runAtTickTime(100L, helper::succeed);
    }

    /** 1 マス幅・高さ 3 の通路(z 3 の壁の x 3 だけ空いている)を抜けて向こうへ出る。目標は z 6.5(moveTo は目標から 1 ブロック以内で着いた扱いなので、z 5.5 だと通路の出口で止まる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void strobilaPassesOneWideDoorway(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            if (x == 3) continue;
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 1.5D, 1.0D, 0.0F);
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2 && s.getNavigation().isDone()) walkTo(s, helper, new Vec3(3.5D, 2.0D, 6.5D));
            if (s.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.6D)).z) helper.succeed();   // 壁(z 3〜4)を体の半分(0.475)より抜けた
        });
        helper.runAtTickTime(230L, () -> helper.fail("通れない: " + where(helper, s)));
    }

    /** 高さ 1 の隙間(z 3 の壁の x 3 だけ、下の段 y 2 が空いている)はくぐらない(グレガリアはくぐる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void strobilaDoesNotCrawlUnderLowGap(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 4; y++) {
                if (x == 3 && y == 2) continue;
                helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
            }
        }
        StrobilaEntity s = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double wall = helper.absoluteVec(new Vec3(0, 0, 3.0D)).z;
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2) walkTo(s, helper, new Vec3(3.5D, 2.0D, 6.5D));
            if (s.getZ() + s.getBbWidth() / 2.0D > wall + 0.5D) helper.fail(String.format("くぐった(z %.2f)", s.getZ() - wall));
        });
        helper.runAtTickTime(150L, helper::succeed);
    }

    /**
     * 急ぎ(経路の速さ 1.0)の地面の速さは walk 2.5 倍(= {@link StrobilaEntity#WALK_REF} × 2.5 ≈ 0.075)の近く:
     * 平らな 24 マスの場をまっすぐ歩かせ、20〜80 tick の平均が 0.055〜0.085 ブロック/tick(上は足が滑らない上限 + 1 割強)。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void strobilaHurrySpeedMatchesWalkCap(GameTestHelper helper) {
        StrobilaEntity s = placeAt(helper, 3.5D, 2.5D, 0.0F);
        double[] z0 = {Double.NaN};
        helper.runAfterDelay(2L, () -> walkTo(s, helper, new Vec3(3.5D, 2.0D, 21.5D)));
        helper.runAtTickTime(20L, () -> z0[0] = s.getZ());
        helper.runAtTickTime(80L, () -> {
            double v = (s.getZ() - z0[0]) / 60.0D;
            double cap = StrobilaEntity.WALK_REF * 2.5D;
            if (v < cap * 0.73D || v > cap * 1.13D) {
                helper.fail(String.format("急ぎの速さ %.4f ブロック/tick(的 %.4f)", v, cap));
                return;
            }
            helper.succeed();
        });
    }
}
