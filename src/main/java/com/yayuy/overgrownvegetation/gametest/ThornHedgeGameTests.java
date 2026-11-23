package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.HedgeTable;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ThornHedges;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 棘の生垣(2026-11-20 司令塔の作り直し 区切り 3): 植物側でない者だけ傷つく / 輪に払って置き、壊すと払って置き直す / 予算なしで伸びるが
 * 密集しない・器官の輪の外へは伸びない / 押し返す道具の効く所には置かない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ThornHedgeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterThornHedgeGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(ThornHedgeGameTests.class);
        }

        private Registration() {
        }
    }

    private ThornHedgeGameTests() {
    }

    /** 植物側でない生き物(豚)は傷つき、植物側の Mob(G1)は傷つかない。 */
    @GameTest(template = "gametest_empty7", batch = "director_hedge_hurt", timeoutTicks = 80)
    public static void hedgeHurtsOnlyNonPlantSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 2, 1), ThornHedges.THORN_HEDGE.get());
        helper.setBlock(new BlockPos(5, 2, 5), ThornHedges.THORN_HEDGE.get());
        Mob pig = helper.spawn(EntityType.PIG, new BlockPos(1, 2, 1));
        pig.goalSelector.removeAllGoals(g -> true);
        Mob g1 = (Mob) helper.spawn(com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickG1(level.getRandom()).entityType(), new BlockPos(5, 2, 5));
        g1.goalSelector.removeAllGoals(g -> true);
        g1.targetSelector.removeAllGoals(g -> true);
        g1.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 200)); // 日に焼けて減らないように
        float pig0 = pig.getHealth(), g10 = g1.getHealth();
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(pig.getHealth() < pig0, "豚が棘で傷つかない: " + pig0 + " → " + pig.getHealth());
            helper.assertTrue(g1.getHealth() >= g10, "植物側の Mob が棘で傷ついた: " + g10 + " → " + g1.getHealth());
            helper.succeed();
        });
    }

    /** 輪の欠けた所に払って置く。壊されたら払って置き直す。予算が無ければ置かない。 */
    @GameTest(template = "gametest_empty7", batch = "director_hedge_ring", timeoutTicks = 20)
    public static void ringPlacedAndReplacedWithBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector dir = ForestDirector.get(level);
        double before = dir.available(level);
        HedgeTable t = ThornHedges.table();
        BlockPos c = helper.absolutePos(new BlockPos(3, 2, 3));
        try {
            dir.setBudget(level, 0.0D);
            helper.assertTrue(ThornHedges.placeRing(level, c.getX() + 0.5D, c.getZ() + 0.5D, 2.0D, 4, 7L, Double.NaN, 100) == 0, "予算 0 で置いた");
            dir.setBudget(level, 10000.0D);
            int placed = ThornHedges.placeRing(level, c.getX() + 0.5D, c.getZ() + 0.5D, 2.0D, 4, 7L, Double.NaN, 100);
            helper.assertTrue(placed > 0, "輪に置かない");
            helper.assertTrue(Math.abs(10000.0D - dir.available(level) - placed * t.cost) < 1.0E-6D, "置いた数の値段を払っていない");
            // 1 マス壊す → 置き直す(払う)
            helper.assertTrue(ThornHedges.LAST_RING.size() == placed, "置いた所の記録が合わない");
            for (BlockPos p : ThornHedges.LAST_RING) helper.assertTrue(ThornHedges.isHedge(level.getBlockState(p)), "置いたはずの所に生垣が無い: " + p);
            BlockPos one = ThornHedges.LAST_RING.get(0);
            level.setBlock(one, Blocks.AIR.defaultBlockState(), 3);
            double b1 = dir.available(level);
            int again = ThornHedges.placeRing(level, c.getX() + 0.5D, c.getZ() + 0.5D, 2.0D, 4, 7L, Double.NaN, 100);
            helper.assertTrue(again == 1 && ThornHedges.isHedge(level.getBlockState(one)), "壊した所を置き直さない: " + again);
            helper.assertTrue(Math.abs(b1 - dir.available(level) - t.cost) < 1.0E-6D, "置き直しに払っていない");
            // 列と列の間は空く(密集させない): 輪の柱の数は 列 × 長さ まで
            helper.assertTrue(placed <= 4 * t.segmentLength, "列より多く置いた: " + placed);
            helper.succeed();
        } finally {
            dir.setBudget(level, before);
        }
    }

    /** 予算なしで伸びる。まわりに多ければ伸びない。器官の輪の帯の外・器官が無い所へは伸びない。 */
    @GameTest(template = "gametest_empty7", batch = "director_hedge_grow", timeoutTicks = 20)
    public static void hedgeGrowsSparselyNearOrgans(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HedgeTable t = ThornHedges.table();
        ForestOrgans data = ForestOrgans.get(level);
        BlockPos start = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.setBlock(new BlockPos(3, 2, 3), ThornHedges.THORN_HEDGE.get());
        RandomSource r = RandomSource.create(5);
        boolean grewWithout = false;
        for (int i = 0; i < 40; i++) grewWithout |= ThornHedges.grow(level, start, r);
        helper.assertTrue(!grewWithout, "器官が無いのに伸びた");
        ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 2, 0, start.getX() - (int) Math.round(t.radiusSmall), start.getZ());
        o.state = ForestOrgans.State.ALIVE;
        try {
            int grown = 0;
            for (int i = 0; i < 400; i++) {
                for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(1, 2, 1)), helper.absolutePos(new BlockPos(5, 2, 5)))) {
                    if (ThornHedges.isHedge(level.getBlockState(p)) && ThornHedges.grow(level, p.immutable(), r)) grown++;
                }
            }
            helper.assertTrue(grown > 0, "器官の輪の上なのに伸びない");
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(start.offset(-t.growNearRadius, -1, -t.growNearRadius), start.offset(t.growNearRadius, 1, t.growNearRadius))) {
                if (ThornHedges.isHedge(level.getBlockState(p))) n++;
            }
            // 伸びる先のまわりに growMaxNear 本あれば伸びないので、5×5 は埋まらない(伸びた先ごとの決まりなので、ほかの先からの分を入れて 2 倍まで)
            helper.assertTrue(n <= 2 * t.growMaxNear && n < 25, "密集した: " + n);
            helper.succeed();
        } finally {
            data.remove(o.id);
        }
    }

    /** 押し返す道具の効く所(焼けた荒地)には置かない。 */
    @GameTest(template = "gametest_empty7", batch = "director_hedge_burnt", timeoutTicks = 20)
    public static void noHedgeOnPushedBackGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos p = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.assertTrue(ThornHedges.canPlace(level, p), "ふつうの地面に置けない");
        var chunk = level.getChunkAt(p);
        com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.flip(level, chunk);
        try {
            helper.assertTrue(!ThornHedges.canPlace(level, p), "焼けた荒地に置ける");
            helper.succeed();
        } finally {
            com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.revert(level, chunk);
        }
    }
}
