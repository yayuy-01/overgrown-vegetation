package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseDefense;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.G1March;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 司令塔 第 3 段 区切り 2「弱い所を突く」: 方位の数え方・いちばん弱い方位・回り込み(送り込み)・偵察の眺める向き。
 * 明るさを読む所(輪 24〜64 ブロック)はテスト場の外なので、世界で明るさを読む確かめはゲーム内で行う(ここでは計算と使い方)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WeakSideGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterWeakSideGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(WeakSideGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "director_weak";

    private WeakSideGameTests() {
    }

    private static BaseMemoryTable t() {
        return DesignData.director().base;
    }

    /** 方位: 東 = 0・南(+z)= 2・西 = 4・北 = 6。いちばん弱いのは守りの最小(読めなかった方位は選ばない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sectorsAndWeakest(GameTestHelper helper) {
        helper.assertTrue(BaseDefense.sectorOf(Math.atan2(0, 1), 8) == 0, "東が 0 でない");
        helper.assertTrue(BaseDefense.sectorOf(Math.atan2(1, 0), 8) == 2, "南が 2 でない");
        helper.assertTrue(BaseDefense.sectorOf(Math.atan2(0, -1), 8) == 4, "西が 4 でない");
        helper.assertTrue(BaseDefense.sectorOf(Math.atan2(-1, 0), 8) == 6, "北が 6 でない");
        for (int i = 0; i < 8; i++) {
            helper.assertTrue(BaseDefense.sectorOf(BaseDefense.angleOf(i, 8), 8) == i, "方位 " + i + " の向きが戻らない");
        }
        double[] d = {0.8, 0.5, Double.NaN, 0.2, 0.9, 0.2, 0.7, 0.6};
        helper.assertTrue(BaseDefense.weakest(d) == 3, "いちばん弱い方位が違う: " + BaseDefense.weakest(d));
        helper.assertTrue(BaseDefense.weakest(new double[]{Double.NaN, Double.NaN}) == -1, "読めないのに方位を選んだ");
        helper.succeed();
    }

    /** 送り込みの回り込み: 弱い方角が分かっていれば、先に弱い方角の輪の上の点へ。着いたら拠点の真ん中へ。分からなければまっすぐ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void marchFlanksThroughWeakSide(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(ChunkPos.asLong(0, 0), t().rememberScore * 2.0D, 0.0D);
        m.update(0.0D);
        BaseMemory.Base b = m.bases().get(0);
        G1March straight = new G1March();
        straight.beginFlank(b, 1.0D, RandomSource.create(1L));
        helper.assertTrue(!straight.flanking(), "弱い方角が分からないのに回り込んだ");
        b.setDefense(new double[]{1, 1, 0.1, 1, 1, 1, 1, 1}, 2, 0.0D); // 南が弱い
        G1March flank = new G1March();
        flank.beginFlank(b, 1.0D, RandomSource.create(1L));
        Vec3 way = flank.target(0.0D);
        helper.assertTrue(flank.flanking() && way.z > b.centerZ() + t().flankRadius * 0.9D, "南の弱い点へ向かっていない: " + way);
        flank.step(way);
        helper.assertTrue(!flank.flanking() && flank.active() && Math.abs(flank.target(0.0D).x - b.centerX()) < 1.0E-6D, "弱い点に着いても真ん中へ向かわない");
        G1March never = new G1March();
        never.beginFlank(b, 0.0D, RandomSource.create(1L));
        helper.assertTrue(!never.flanking(), "割合 0 なのに回り込んだ");
        helper.succeed();
    }

    /** 偵察は弱い方角から眺める(GameTest では段の強さが最盛 = 回り込む割合そのまま)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void scoutWatchesFromWeakSide(GameTestHelper helper) {
        AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
        z.goalSelector.removeAllGoals(g -> true);
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(ChunkPos.asLong(z.getBlockX() >> 4, z.getBlockZ() >> 4), t().rememberScore * 2.0D, 0.0D);
        m.update(0.0D);
        BaseMemory.Base b = m.bases().get(0);
        b.setDefense(new double[]{1, 1, 1, 1, 1, 1, 0.0, 1}, 6, 0.0D); // 北が弱い
        z.fate().scout().begin(b);
        Vec3 w = z.fate().scout().watchPoint();
        z.discard();
        if (t().flankShare >= 1.0D) {
            helper.assertTrue(w.z < b.centerZ() - t().scoutWatchMin * 0.9D, "北の弱い方角から眺めていない: " + w);
        }
        helper.succeed();
    }
}
