package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 環境生物の作り直し 段 1(2026-11-16): プルモノスコルピウス(狩る側の環境生物)・プロトファスマ(獲物の環境生物)が「小さな地這いの虫」の型で歩く。
 * グループのタグ(= 植物側・すべての Mob から敵対される)に入り、平地を歩いて着き、1 ブロックの段を這い上がる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CrawlerCritterGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CrawlerCritterGameTests.class);
        }

        private Registration() {
        }
    }

    private CrawlerCritterGameTests() {
    }

    private static <T extends CrawlerCritter> T placeAt(GameTestHelper helper, EntityType<T> type, double x, double z, float yaw) {
        T c = helper.spawn(type, new BlockPos(0, 2, 0));   // spawnWithNoFreeWill は Goal を全部外すので使わない
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        c.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        c.snapFacing(yaw);
        c.setCustomName(Component.literal("nostroll_notarget_nopack"));
        return c;
    }

    private static void check(GameTestHelper helper, CrawlerCritter c, MobGroup group) {
        if (MobGroup.of(c.getType()) != group) {
            helper.fail(c.getType().toShortString() + " が " + group + " に入っていない: " + MobGroup.of(c.getType()));
            return;
        }
        if (!SporeExposureTracker.isPlantSide(c)) {
            helper.fail(c.getType().toShortString() + " が植物側と見なされない");
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(c)) {
            helper.fail(c.getType().toShortString() + " が過繁茂の Mob と見なされない(ほかの Mob から敵対されない)");
        }
    }

    /** プルモノスコルピウスは狩る側の環境生物・プロトファスマは獲物の環境生物に入り、植物側(すべての Mob から敵対される)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void groupsAndPlantSide(GameTestHelper helper) {
        check(helper, placeAt(helper, ModCrawlers.PULMONOSCORPIUS.get(), 2.5D, 3.5D, 0.0F), MobGroup.CRITTER_HUNTER);
        check(helper, placeAt(helper, ModCrawlers.PROTOPHASMA.get(), 4.5D, 3.5D, 0.0F), MobGroup.CRITTER_PREY);
        check(helper, placeAt(helper, ModCrawlers.GIANT_SNAIL.get(), 1.5D, 5.5D, 0.0F), MobGroup.CRITTER_PREY);
        check(helper, placeAt(helper, ModCrawlers.ARTHROPLEURA.get(), 4.5D, 5.5D, 0.0F), MobGroup.CRITTER_PREY);
        helper.succeed();
    }

    /** 平地を歩いて向こうへ着く(2 種とも)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void bothWalkAcrossFlatGround(GameTestHelper helper) {
        CrawlerCritter a = placeAt(helper, ModCrawlers.PULMONOSCORPIUS.get(), 1.5D, 1.2D, 0.0F);
        CrawlerCritter b = placeAt(helper, ModCrawlers.PROTOPHASMA.get(), 5.0D, 1.2D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            for (CrawlerCritter c : new CrawlerCritter[]{a, b}) {
                Vec3 p = helper.absoluteVec(new Vec3(c.getX() - helper.absoluteVec(Vec3.ZERO).x, 2.0D, 5.6D));
                c.getNavigation().moveTo(c.getNavigation().createPath(BlockPos.containing(p), 0), 1.0D);
            }
        });
        double goal = helper.absoluteVec(new Vec3(0, 0, 4.8D)).z;
        helper.onEachTick(() -> {
            if (a.getZ() > goal && b.getZ() > goal) helper.succeed();
        });
    }

    /** 1 ブロックの段(z 3〜6 の台)へ這い上がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void bothClimbOneBlockStep(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
        }
        CrawlerCritter a = placeAt(helper, ModCrawlers.PULMONOSCORPIUS.get(), 1.5D, 1.0D, 0.0F);
        CrawlerCritter b = placeAt(helper, ModCrawlers.PROTOPHASMA.get(), 5.0D, 1.0D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            for (CrawlerCritter c : new CrawlerCritter[]{a, b}) {
                Vec3 p = helper.absoluteVec(new Vec3(c.getX() - helper.absoluteVec(Vec3.ZERO).x, 3.0D, 5.5D));
                c.getNavigation().moveTo(c.getNavigation().createPath(BlockPos.containing(p), 0), 1.0D);
            }
        });
        double top = helper.absoluteVec(new Vec3(0, 3.0D, 0)).y;
        helper.onEachTick(() -> {
            if (a.getY() >= top - 0.01D && b.getY() >= top - 0.01D) helper.succeed();
        });
    }

    /**
     * 植物側は汚染された森の茂みで遅くならない(11-16 ユーザー): 茂みを敷き詰めた中を急がせて、20 tick の平均の速さが 0.13 を超える
     * (急ぎ 0.169。茂みで遅くなると 0.06 ほど)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void plantSideIsNotSlowedByBushes(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_BUSH.get().defaultBlockState());
        }
        CrawlerCritter a = placeAt(helper, ModCrawlers.PULMONOSCORPIUS.get(), 3.5D, 0.8D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            Vec3 p = helper.absoluteVec(new Vec3(3.5D, 2.0D, 6.0D));
            a.getNavigation().moveTo(a.getNavigation().createPath(BlockPos.containing(p), 0), 1.0D);
        });
        double[] at = new double[1];
        helper.runAtTickTime(12L, () -> at[0] = a.getZ());
        helper.runAtTickTime(32L, () -> {
            double v = (a.getZ() - at[0]) / 20.0D;
            if (v > 0.13D) helper.succeed();
            else helper.fail(String.format(java.util.Locale.ROOT, "茂みで遅くなった(20 tick の平均 %.3f ブロック/tick)", v));
        });
    }

    /** 陸生カタツムリ(這う 約 0.022)とアースロプレウラ(急ぎ 約 0.12)も歩いて着く。カタツムリは 2 ブロック、アースロプレウラは 4 ブロック。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260)
    public static void snailAndArthropleuraWalk(GameTestHelper helper) {
        CrawlerCritter s = placeAt(helper, ModCrawlers.GIANT_SNAIL.get(), 1.5D, 3.0D, 0.0F);
        CrawlerCritter a = placeAt(helper, ModCrawlers.ARTHROPLEURA.get(), 4.8D, 1.2D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            Vec3 ps = helper.absoluteVec(new Vec3(1.5D, 2.0D, 5.5D));
            s.getNavigation().moveTo(s.getNavigation().createPath(BlockPos.containing(ps), 0), s.rushSpeed());
            Vec3 pa = helper.absoluteVec(new Vec3(4.8D, 2.0D, 5.6D));
            a.getNavigation().moveTo(a.getNavigation().createPath(BlockPos.containing(pa), 0), a.rushSpeed());
        });
        double goalS = helper.absoluteVec(new Vec3(0, 0, 4.8D)).z, goalA = helper.absoluteVec(new Vec3(0, 0, 4.8D)).z;
        helper.onEachTick(() -> {
            if (s.getZ() > goalS && a.getZ() > goalA) helper.succeed();
        });
        helper.runAtTickTime(255L, () -> {
            Vec3 o = helper.absoluteVec(Vec3.ZERO);
            helper.fail(String.format(java.util.Locale.ROOT, "着かない: カタツムリ z %.2f(引っ込み %s)・アースロプレウラ z %.2f 経路 %s", s.getZ() - o.z,
                    ((com.yayuy.overgrownvegetation.entity.GiantSnailEntity) s).retracted(), a.getZ() - o.z, a.getNavigation().isDone() ? "なし" : "あり"));
        });
    }

    /** 陸生カタツムリ: 叩かれると殻に引っ込み、引っ込んでいる間の傷は半分、約 5 秒で頭を出す(11-16 夜 ユーザー)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void snailRetractsWhenHit(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.GiantSnailEntity s = placeAt(helper, ModCrawlers.GIANT_SNAIL.get(), 3.5D, 3.5D, 0.0F);
        net.minecraft.world.entity.monster.Zombie z = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 2, 6));
        float[] h = new float[2];
        helper.runAtTickTime(2L, () -> {
            h[0] = s.getHealth();
            s.hurt(s.damageSources().mobAttack(z), 2.0F);
            if (!s.retracted()) helper.fail("叩かれても殻に引っ込まない");
            h[1] = s.getHealth();
        });
        helper.runAtTickTime(25L, () -> {   // 傷の後の無敵の間(10 tick)が過ぎてから
            float before = s.getHealth();
            s.hurt(s.damageSources().mobAttack(z), 4.0F);
            float lost = before - s.getHealth();
            float first = h[0] - h[1];   // 1 回目(引っ込む前・2)の傷。防具 6 を引いた後なので、4 の半分 = 2 も同じ量になるはず
            if (Math.abs(lost - first) > 0.05F) {
                helper.fail("引っ込んでいる間の傷が半分でない: 4 で " + lost + "(引っ込む前の 2 で " + first + ")");
            }
        });
        // 2 回目に叩いた tick 25 から約 5 秒(100 tick)で頭を出す
        helper.runAtTickTime(25L + com.yayuy.overgrownvegetation.entity.GiantSnailEntity.RETRACT_TICKS + 5L, () -> {
            if (s.retracted()) helper.fail("約 5 秒たっても頭を出さない");
            else helper.succeed();
        });
    }

    /** 陸生カタツムリ: 敵(植物側以外の生き物)が近づくと殻に引っ込む。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void snailRetractsWhenEnemyNear(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.GiantSnailEntity s = placeAt(helper, ModCrawlers.GIANT_SNAIL.get(), 3.5D, 2.0D, 0.0F);
        helper.runAtTickTime(15L, () -> {
            if (s.retracted()) helper.fail("敵がいないのに引っ込んだ");
            helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 4));
        });
        helper.onEachTick(() -> {
            if (helper.getTick() > 16 && s.retracted()) helper.succeed();
        });
    }
}
