package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterVesicariaGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
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
        v.setCustomName(Component.literal("nostroll_notarget"));
        return v;
    }

    private static void walkTo(VesicariaEntity v, GameTestHelper helper, Vec3 rel, double speed) {
        // moveTo(x, y, z, 速さ) は「目標から 1 ブロック以内」で止まる経路(届く範囲 1)なので、1 マス手前で着いた扱いになる。
        // 段・隙間を抜けたかを見るテストでは、目標のマスちょうどまでの経路(届く範囲 0)にする(11-15)。
        Vec3 p = helper.absoluteVec(rel);
        v.getNavigation().moveTo(v.getNavigation().createPath(BlockPos.containing(p), 0), speed);
    }

    /** G2 に入り、自然には湧かず、体の芯は 0.8 × 0.6、過繁茂の Mob と見なされる。急ぎの口は種の値を返し、グレガリアは既定の 1.0 のまま。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void vesiRegisteredAsG2WithoutNaturalSpawn(GameTestHelper helper) {
        VesicariaEntity v = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (Math.abs(v.getBbWidth() - 0.8F) > 1e-3 || Math.abs(v.getBbHeight() - 0.6F) > 1e-3) {
            helper.fail("芯の大きさが 0.8 × 0.6 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(v)) {
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

    // ------------------------------------------------------------------ 段 2: 突撃・破裂・連鎖(11-15)

    private static net.minecraft.world.entity.animal.Cow cowAt(GameTestHelper helper, double x, double z) {
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        cow.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        return cow;
    }

    /** 相手(牛)が 12 ブロックより近いと突撃し(走りの速さ)、触れた瞬間に弾けて死に、牛に傷を付ける。植物側の死の破裂は重ならない。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 160)
    public static void vesiChargesAndBurstsOnContact(GameTestHelper helper) {
        // spawnWithNoFreeWill は行動(突撃も)を全部外す(標的の探しは残る)ので、ふつうに出す
        VesicariaEntity v = helper.spawn(ModCrawlers.VESICARIA.get(), new BlockPos(3, 2, 2));
        v.setCustomName(Component.literal("nostroll"));   // うろつかない・相手は探す
        Vec3 vp = helper.absoluteVec(new Vec3(3.5D, 2.0D, 2.5D));
        v.moveTo(vp.x, vp.y, vp.z, 0.0F, 0.0F);
        v.snapFacing(0.0F);
        net.minecraft.world.entity.animal.Cow cow = cowAt(helper, 3.5D, 13.5D);
        float cowHp = cow.getHealth();
        int plantBursts = com.yayuy.overgrownvegetation.corpse.PlantSideBurst.burstCount;
        boolean[] charged = {false};
        double[] maxSpeed = {0.0D};
        long[] burstAt = {-1L};
        helper.onEachTick(() -> {
            if (v.isCharging()) charged[0] = true;
            if (v.isAlive()) {
                double dx = v.getX() - v.xo, dz = v.getZ() - v.zo;
                maxSpeed[0] = Math.max(maxSpeed[0], Math.sqrt(dx * dx + dz * dz));
            } else if (burstAt[0] < 0) {
                burstAt[0] = helper.getTick();
            }
            if (burstAt[0] >= 0 && helper.getTick() >= burstAt[0] + VesicariaEntity.REMOVE_TICKS + 3) {
                if (!charged[0]) { helper.fail("突撃しないまま弾けた"); return; }
                if (maxSpeed[0] < 0.2D) { helper.fail(String.format("突撃が遅い(最大 %.3f)", maxSpeed[0])); return; }
                if (!v.hasBurst()) { helper.fail("死んだが破裂していない"); return; }
                if (cow.getHealth() >= cowHp) { helper.fail("牛に傷が無い: " + cow.getHealth()); return; }
                if (!v.isRemoved()) { helper.fail("しぼんだ姿のまま消えない(死んでから " + (helper.getTick() - burstAt[0]) + " tick)"); return; }
                if (com.yayuy.overgrownvegetation.corpse.PlantSideBurst.burstCount != plantBursts) { helper.fail("植物側の死の破裂も起きた(二重)"); return; }
                helper.succeed();
            }
        });
        helper.runAtTickTime(150L, () -> helper.fail(String.format("触れて弾けない(突撃 %s・最大の速さ %.3f・相手 %s・牛との距離 %.1f・%s)",
                charged[0], maxSpeed[0], v.getTarget(), v.distanceTo(cow), v.debugState())));
    }

    /**
     * 矢が当たる(傷が 1 でも)とその場で弾ける。11-18 夜 ユーザー決定で連鎖をなくしたので、隣の仲間はつられて弾けない
     * (前は半径 3 の仲間 2 体がつられて弾けた)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void vesiArrowBurstsAndChains(GameTestHelper helper) {
        VesicariaEntity a = placeAt(helper, 1.5D, 1.5D, 0.0F);
        VesicariaEntity b = placeAt(helper, 3.0D, 1.5D, 0.0F);
        VesicariaEntity c = placeAt(helper, 4.5D, 1.5D, 0.0F);
        VesicariaEntity far = placeAt(helper, 1.5D, 6.5D, 0.0F);
        for (VesicariaEntity v : new VesicariaEntity[]{a, b, c, far}) v.setNoAi(true);
        helper.runAfterDelay(2L, () -> {
            net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(helper.getLevel(),
                    a.getX(), a.getY() + 3.0D, a.getZ()); // 1.20.1: 矢の物・弓の引数は無い
            if (!a.hurt(a.damageSources().arrow(arrow, null), 1.0F)) helper.fail("矢の傷が通らない");
            if (a.isAlive()) helper.fail("矢が当たっても弾けない");
        });
        helper.runAtTickTime(4L + VesicariaEntity.CHAIN_DELAY_MAX * 2 + 2, () -> {
            if (b.hasBurst() || c.hasBurst()) { helper.fail("連鎖をなくしたのに連鎖した(隣 " + b.hasBurst() + "・その隣 " + c.hasBurst() + ")"); return; }
            if (far.hasBurst() || !far.isAlive()) { helper.fail("離れた仲間まで弾けた"); return; }
            helper.succeed();
        });
    }

    /** 植物側(グレガリア)は狙わない。死んでも植物側の死の破裂(PlantSideBurst)の対象ではない(自前の破裂だけ)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void vesiIgnoresPlantSideAndIsDeathBurstExempt(GameTestHelper helper) {
        VesicariaEntity v = placeAt(helper, 1.5D, 1.5D, 0.0F);
        GregariaEntity g = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new BlockPos(5, 2, 5));
        if (com.yayuy.overgrownvegetation.spore.PlantSideRules.bursts(v)) { helper.fail("植物側の死の破裂の対象に入っている(二重に弾ける)"); return; }
        if (com.yayuy.overgrownvegetation.entity.AcumnesEntity.isPrey(v, g)) { helper.fail("グレガリア(植物側)を獲物と見なす"); return; }
        helper.runAtTickTime(50L, () -> {
            if (v.getTarget() != null) { helper.fail("植物側を狙った: " + v.getTarget()); return; }
            if (v.isCharging()) { helper.fail("相手がいないのに突撃している"); return; }
            if (!g.isAlive()) { helper.fail("グレガリアが死んだ"); return; }
            helper.succeed();
        });
    }

    /**
     * 細い相手(村人。幅 0.6 = プレイヤーと同じ)にも触れて弾ける。経路は「1 ブロック以内で着いた」で止まるので、
     * 最後はまっすぐ寄る({@code CLOSE_IN})。11-15 テストクライアントで、プレイヤーの 0.9 ブロック手前に立ち止まったのを直した。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void vesiReachesNarrowTarget(GameTestHelper helper) {
        VesicariaEntity v = helper.spawn(ModCrawlers.VESICARIA.get(), new BlockPos(3, 2, 2));
        v.setCustomName(Component.literal("nostroll"));
        Vec3 vp = helper.absoluteVec(new Vec3(3.5D, 2.0D, 2.5D));
        v.moveTo(vp.x, vp.y, vp.z, 0.0F, 0.0F);
        v.snapFacing(0.0F);
        net.minecraft.world.entity.npc.Villager villager = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(3.5D, 2.0D, 9.5D));
        villager.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        float hp = villager.getHealth();
        helper.onEachTick(() -> {
            if (v.hasBurst()) {
                if (villager.getHealth() >= hp) { helper.fail("弾けたが村人に傷が無い"); return; }
                helper.succeed();
            }
        });
        helper.runAtTickTime(110L, () -> helper.fail(String.format("細い相手に触れない(距離 %.2f・突撃 %s・%s)", v.distanceTo(villager), v.isCharging(), v.debugState())));
    }
}
