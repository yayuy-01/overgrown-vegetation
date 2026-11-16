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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G2・G3 の作り直し 段 1(2026-11-16): プルモノスコルピウス(G3)・プロトファスマ(G2)が「小さな地這いの虫」の型で歩く。
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

    /** プルモノスコルピウスは G3・プロトファスマは G2 に入り、植物側(すべての Mob から敵対される)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void groupsAndPlantSide(GameTestHelper helper) {
        check(helper, placeAt(helper, ModCrawlers.PULMONOSCORPIUS.get(), 2.5D, 3.5D, 0.0F), MobGroup.GROUP_3);
        check(helper, placeAt(helper, ModCrawlers.PROTOPHASMA.get(), 4.5D, 3.5D, 0.0F), MobGroup.GROUP_2);
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
}
