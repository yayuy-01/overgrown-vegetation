package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import com.yayuy.overgrownvegetation.entity.flyer.DartHover;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModG23Flyers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * メガネウラの段 1(2026-11-16): 飛ぶ型の土台にトンボの飛び方を載せた所。旋回・止まっては飛ぶはテスト場(上に物がある)で測れないので計算で回す
 * (ゲームの中と同じ部品 {@link FlightBody}・{@link DartHover} と同じ値)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MeganeuraGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MeganeuraGameTests.class);
        }

        private Registration() {
        }
    }

    private MeganeuraGameTests() {
    }

    /** G3 に入り、当たりは 1.0 × 0.5、植物側(ほかの Mob から敵対される)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG3(GameTestHelper helper) {
        MeganeuraEntity m = helper.spawn(ModG23Flyers.MEGANEURA.get(), new BlockPos(3, 3, 3));
        helper.assertTrue(MobGroup.of(m.getType()) == MobGroup.GROUP_3, "G3 に入っていない: " + MobGroup.of(m.getType()));
        helper.assertTrue(Math.abs(m.getBbWidth() - 1.0F) < 1e-3 && Math.abs(m.getBbHeight() - 0.5F) < 1e-3,
                "当たりが 1.0 × 0.5 でない: " + m.getBbWidth() + " × " + m.getBbHeight());
        helper.assertTrue(com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(m), "植物側と見なされない");
        helper.succeed();
    }

    /** 巡航(0.5)で目一杯曲がると半径 3 ブロック以内の小さな弧(トンボの急な向き変え)。急ぐ(1.0)でも 6 以内。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void turnsSharply(GameTestHelper helper) {
        FlightBody.Params p = MeganeuraEntity.flightParams();
        double[] r = new double[2];
        double[] speeds = {p.cruise, MeganeuraEntity.DASH_SPEED};
        for (int i = 0; i < 2; i++) {
            FlightBody b = new FlightBody();
            b.speed = speeds[i];
            for (int t = 0; t < 120; t++) b.step(p, b.heading + 90.0D, speeds[i], 0.0D, 0.0D, 0.0D);
            r[i] = b.speed / Math.toRadians(Math.abs(b.turnRate));
        }
        helper.assertTrue(r[0] <= 3.0D, String.format("巡航の旋回の半径 %.2f > 3", r[0]));
        helper.assertTrue(r[1] <= 6.0D, String.format("急ぐときの旋回の半径 %.2f > 6", r[1]));
        com.mojang.logging.LogUtils.getLogger().info(String.format("MEGANEURA 旋回の半径 巡航 %.2f・急ぐ %.2f", r[0], r[1]));
        helper.succeed();
    }

    /** 止まっては飛ぶ: 8 ブロック先へ鋭く飛んで、行き過ぎ 0.3 以内でピタッと止まる(40 tick 以内に速さ 0)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void dartsAndStopsDead(GameTestHelper helper) {
        DartHover d = new DartHover();
        DartHover.Params p = MeganeuraEntity.dartParams();
        double x = 0, y = 0, z = 0;
        d.holdAt(0, 0, 0);
        d.dartTo(8, 1, 0);
        double maxX = 0;
        int settledAt = -1;
        for (int t = 0; t < 80; t++) {
            d.step(p, x, y, z, 0.0D, 0.0D, false);
            x += d.vx;
            y += d.vy;
            z += d.vz;
            maxX = Math.max(maxX, x);
            if (settledAt < 0 && d.settled()) settledAt = t;
        }
        helper.assertTrue(maxX - 8.0D <= 0.3D, String.format("行き過ぎ %.2f", maxX - 8.0D));
        helper.assertTrue(settledAt >= 0 && settledAt <= 40, "40 tick で止まらない: " + settledAt);
        helper.assertTrue(Math.abs(x - 8.0D) < 0.2D && Math.abs(y - 1.0D) < 0.2D, String.format("止まった所がずれた (%.2f, %.2f)", x, y));
        helper.succeed();
    }

    /** 飛んでいる間に叩かれると急ぐ(chase の羽ばたき)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void dashesWhenHit(GameTestHelper helper) {
        MeganeuraEntity m = helper.spawn(ModG23Flyers.MEGANEURA.get(), new BlockPos(3, 3, 3));
        Vec3 p = helper.absoluteVec(new Vec3(3.5D, 4.0D, 3.5D));
        m.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        m.roostOverride = Boolean.FALSE;
        m.debugSolo = true;
        helper.runAfterDelay(5L, () -> {
            m.hurt(helper.getLevel().damageSources().generic(), 1.0F);
            helper.assertTrue(m.mood() == MeganeuraEntity.MOOD_DASH, "叩かれても急がない: " + m.mood());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 飛び立ちの溜め(11-17 ユーザー決定)

    private static MeganeuraEntity perched(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        MeganeuraEntity m = helper.spawn(ModG23Flyers.MEGANEURA.get(), new BlockPos(3, 2, 3));
        m.debugSolo = true;
        m.roostOverride = Boolean.TRUE;
        m.debugPerchAt(helper.absolutePos(new BlockPos(3, 1, 3)));
        m.debugTickFlock();
        return m;
    }

    /** 朝(自分から飛び立つ)は溜めを流してから昇る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void calmTakeoffWindsUp(GameTestHelper helper) {
        MeganeuraEntity m = perched(helper);
        helper.runAfterDelay(5L, () -> m.roostOverride = Boolean.FALSE);
        helper.onEachTick(() -> {
            if (m.mode() == com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer.MODE_TAKEOFF && m.takeoffPhase() == 1) helper.succeed();
        });
    }

    /** 驚いて飛び立つ(叩かれた)ときは溜めない(すぐ昇る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void startledTakeoffSkipsWindup(GameTestHelper helper) {
        MeganeuraEntity m = perched(helper);
        boolean[] sawWindup = {false};
        double[] y0 = {Double.NaN};
        helper.runAfterDelay(5L, () -> {
            y0[0] = m.getY();
            m.hurt(helper.getLevel().damageSources().generic(), 1.0F);
        });
        helper.onEachTick(() -> {
            if (m.mode() == com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer.MODE_TAKEOFF && m.takeoffPhase() == 1) sawWindup[0] = true;
        });
        helper.runAfterDelay(20L, () -> {
            helper.assertFalse(sawWindup[0], "驚いたのに溜めた");
            helper.assertTrue(m.getY() - y0[0] > 1.5D, String.format("すぐ昇っていない(%.2f)", m.getY() - y0[0]));
            helper.succeed();
        });
    }
}
