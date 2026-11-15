package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import com.yayuy.overgrownvegetation.entity.VesicariaEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ヴェシカリアの段 1(2026-11-15): 「小さな地這いの虫」の型の上で、うろつく・急ぐところまで。
 * 1 ブロックの段は這い上がる・2 段の壁は越えない・1 マスの低い隙間をくぐる・急ぎは walk 2.5 倍の速さの近く・うろつきは急ぎより遅い。
 * 小走りのうろつきは近くにプレイヤーがいるときだけなのでここでは見ない(テストクライアントで見る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VesicariaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(VesicariaGameTests.class);
        }

        private Registration() {
        }
    }

    private VesicariaGameTests() {
    }

    private static VesicariaEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        VesicariaEntity v = helper.spawnWithNoFreeWill(ModCrawlers.VESICARIA.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setNoAi(false);
        v.setCustomName(Component.literal("nostroll"));
        return v;
    }

    private static void walkTo(VesicariaEntity v, GameTestHelper helper, Vec3 rel, double speed) {
        // moveTo(x, y, z, 速さ) は「目標から 1 ブロック以内」で止まる経路(届く範囲 1)なので、1 マス手前で着いた扱いになる。
        // 段・隙間を抜けたかを見るテストでは、目標のマスちょうどまでの経路(届く範囲 0)にする(11-15)。
        Vec3 p = helper.absoluteVec(rel);
        v.getNavigation().moveTo(v.getNavigation().createPath(BlockPos.containing(p), 0), speed);
    }

    /** G4 に入り、自然には湧かず、体の芯は 0.8 × 0.6、過繁茂の Mob と見なされる。急ぎの口は種の値を返し、グレガリアは既定の 1.0 のまま。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void vesiRegisteredAsG4WithoutNaturalSpawn(GameTestHelper helper) {
        VesicariaEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_4) {
            helper.fail("G4 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (DesignData.mobs().forId(VesicariaEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(v.getBbWidth() - 0.8F) > 1e-3 || Math.abs(v.getBbHeight() - 0.6F) > 1e-3) {
            helper.fail("芯の大きさが 0.8 × 0.6 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.isOvergrownMob(v)) {
            helper.fail("過繁茂の Mob と見なされない");
            return;
        }
        if (v.rushSpeed() != VesicariaEntity.RUSH_SPEED) {
            helper.fail("急ぎの口が種の値を返さない: " + v.rushSpeed());
            return;
        }
        GregariaEntity g = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new BlockPos(1, 2, 1));
        if (g.rushSpeed() != 1.0D) {
            helper.fail("グレガリアの急ぎが既定 1.0 でない: " + g.rushSpeed());
            return;
        }
        helper.succeed();
    }

    /** 1 ブロックの段(z 3〜6 の台)へ這い上がる。跳ばない(上る間に足の高さが段の上 0.3 を超えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void vesiClimbsOneBlockStep(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
        }
        VesicariaEntity v = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double top = helper.absoluteVec(new Vec3(0, 3.0D, 0)).y;
        double[] maxY = {Double.NEGATIVE_INFINITY};
        helper.runAfterDelay(2L, () -> walkTo(v, helper, new Vec3(3.5D, 3.0D, 5.5D), v.rushSpeed()));
        helper.onEachTick(() -> {
            maxY[0] = Math.max(maxY[0], v.getY());
            if (v.getY() >= top - 0.01D && v.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.5D)).z) {
                if (maxY[0] > top + 0.3D) {
                    helper.fail(String.format("段を跳んで上った(最高 %.2f、段の上 %.2f)", maxY[0], top));
                    return;
                }
                helper.succeed();
            }
        });
        helper.runAtTickTime(190L, () -> {
            Vec3 o = helper.absoluteVec(Vec3.ZERO);
            net.minecraft.world.level.pathfinder.Path p = v.getNavigation().getPath();
            helper.fail(String.format("上れない(位置 %.2f %.2f %.2f、地面 %s、経路 %s)", v.getX() - o.x, v.getY() - o.y, v.getZ() - o.z, v.onGround(),
                    p == null ? "なし" : (p.canReach() ? "届く " : "届かない ") + p.getNextNodeIndex() + "/" + p.getNodeCount() + " 終わり " + p.getTarget()));
        });
    }

    /** 2 段の壁(z 3 の全幅)は越えない: 向こう側へ行かせても 100 tick 手前に残る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void vesiDoesNotClimbTwoBlockWall(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        VesicariaEntity v = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double wall = helper.absoluteVec(new Vec3(0, 0, 3.0D)).z;
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2) walkTo(v, helper, new Vec3(3.5D, 2.0D, 5.5D), v.rushSpeed());
            if (v.getZ() > wall) helper.fail(String.format("越えた(z %.2f、y %.2f)", v.getZ() - wall, v.getY()));
        });
        helper.runAtTickTime(100L, helper::succeed);
    }

    /** 1 マスの低い隙間(z 3 の壁の x 3 だけ、下の段 y 2 が空いている)をくぐって向こうへ出る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void vesiCrawlsUnderLowGap(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 4; y++) {
                if (x == 3 && y == 2) continue;
                helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
            }
        }
        VesicariaEntity v = placeAt(helper, 1.5D, 1.0D, 0.0F);
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2 && v.getNavigation().isDone()) walkTo(v, helper, new Vec3(3.5D, 2.0D, 5.5D), v.rushSpeed());
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.4D)).z) helper.succeed();   // 壁(z 3〜4)を体の半分(0.4)より抜けた
        });
        helper.runAtTickTime(290L, () -> {
            Vec3 o = helper.absoluteVec(Vec3.ZERO);
            net.minecraft.world.level.pathfinder.Path p = v.getNavigation().getPath();
            helper.fail(String.format("くぐれない(位置 %.2f %.2f %.2f、経路 %s)", v.getX() - o.x, v.getY() - o.y, v.getZ() - o.z,
                    p == null ? "なし" : (p.canReach() ? "届く " : "届かない ") + p.getNextNodeIndex() + "/" + p.getNodeCount() + " 終わり " + p.getTarget()));
        });
    }

    /**
     * 急ぎの地面の速さは段 1 の {@link VesicariaEntity#RUSH_GROUND}(≈ 0.075。新しい walk の 1.67 倍)の近く:
     * 平らな 24 マスの場をまっすぐ歩かせ、20〜80 tick の平均が 的の 0.73〜1.13 倍。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void vesiRushSpeedMatchesWalkCap(GameTestHelper helper) {
        measure(helper, VesicariaEntity.RUSH_SPEED, VesicariaEntity.RUSH_GROUND, "急ぎ");
    }

    /** うろつき(小走り)の地面の速さは 約 0.045(新しい walk の 1 倍)の近く = 急ぎより遅い。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void vesiStrollSpeedIsSlower(GameTestHelper helper) {
        measure(helper, VesicariaEntity.STROLL_SPEED, VesicariaEntity.STROLL_GROUND, "うろつき");
    }

    /**
     * 突撃の速さ(段 2 の技の前に値だけ): 約 {@link VesicariaEntity#CHARGE_GROUND}(走るプレイヤーくらい。11-15 ユーザー)の近く。
     * 速いので 10〜50 tick の平均で見る(場の奥 21.5 まで届く前に終える)。run は 0.6〜1.25 倍で約 0.17〜0.35 を覆う。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 80)
    public static void vesiChargeSpeedMatchesRun(GameTestHelper helper) {
        measure(helper, VesicariaEntity.CHARGE_SPEED, VesicariaEntity.CHARGE_GROUND, "突撃", 10L, 50L);
    }

    private static void measure(GameTestHelper helper, double modifier, double target, String what) {
        measure(helper, modifier, target, what, 20L, 80L);
    }

    private static void measure(GameTestHelper helper, double modifier, double target, String what, long from, long to) {
        VesicariaEntity v = placeAt(helper, 3.5D, 2.5D, 0.0F);
        double[] z0 = {Double.NaN};
        helper.runAfterDelay(2L, () -> walkTo(v, helper, new Vec3(3.5D, 2.0D, 21.5D), modifier));
        helper.runAtTickTime(from, () -> z0[0] = v.getZ());
        helper.runAtTickTime(to, () -> {
            double s = (v.getZ() - z0[0]) / (to - from);
            OvergrownVegetation.LOGGER.info(String.format("VESI_SPEED %s %.4f ブロック/tick(的 %.4f)", what, s, target));
            if (s < target * 0.73D || s > target * 1.13D) {
                helper.fail(String.format("%sの速さ %.4f ブロック/tick(的 %.4f)", what, s, target));
                return;
            }
            helper.succeed();
        });
    }
}
