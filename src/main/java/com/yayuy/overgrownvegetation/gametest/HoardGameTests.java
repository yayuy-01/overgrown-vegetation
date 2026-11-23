package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HoardTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestMoods;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 司令塔 第 3 段 区切り 3「貯めて一気に使う」。世界に 1 つの司令塔の気分と予算を使うので、世界を使う確かめは 1 本にまとめ、batch を分ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class HoardGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterHoardGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(HoardGameTests.class);
        }

        private Registration() {
        }
    }

    private HoardGameTests() {
    }

    /** 貯め始める条件: 進化が開いている・定着か最盛(導入では起こさない = ユーザー)・大きな襲撃の前触れでない・間が空いた・予算が上限の半分以上。 */
    @GameTest(template = "gametest_empty7", batch = "director_hoard_pure", timeoutTicks = 20)
    public static void hoardStartsOnlyWhenAllowed(GameTestHelper helper) {
        HoardTable t = DesignData.director().hoard;
        double cap = 100.0D, enough = cap * t.minBudgetFraction;
        helper.assertTrue(ForestMoods.canStartHoard(true, PhaseStage.ESTABLISHED, false, 5.0D, 1.0D, enough, cap, t), "定着で貯め始めない");
        helper.assertTrue(ForestMoods.canStartHoard(true, PhaseStage.PEAK, false, 5.0D, 1.0D, enough, cap, t), "最盛で貯め始めない");
        helper.assertTrue(!ForestMoods.canStartHoard(true, PhaseStage.INTRO, false, 5.0D, 1.0D, enough, cap, t), "導入で貯め始めた");
        helper.assertTrue(!ForestMoods.canStartHoard(false, PhaseStage.PEAK, false, 5.0D, 1.0D, enough, cap, t), "進化が閉じているのに貯め始めた");
        helper.assertTrue(!ForestMoods.canStartHoard(true, PhaseStage.PEAK, true, 5.0D, 1.0D, enough, cap, t), "大きな襲撃の前触れなのに貯め始めた");
        helper.assertTrue(!ForestMoods.canStartHoard(true, PhaseStage.PEAK, false, 5.0D, 6.0D, enough, cap, t), "使い終えてすぐ貯め始めた");
        helper.assertTrue(!ForestMoods.canStartHoard(true, PhaseStage.PEAK, false, 5.0D, 1.0D, enough - 1.0D, cap, t), "予算が少ないのに貯め始めた");
        helper.succeed();
    }

    /**
     * 貯める間: 上限が上がり、苗床のスポナーは孵さず、見に来ても道を選ばない。使う: まわりの段階 0 を一斉に進化させ、ふだんに戻る。
     */
    @GameTest(template = "gametest_empty7", batch = "director_hoard_world", timeoutTicks = 40)
    public static void hoardPausesThenSpendsAtOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        double before = director.available(level);
        double day = BaseMemory.today(level.getServer());
        double capBefore = ForestDirector.cap(level);
        List<AbstractParasitizedMonster> mine = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(1.5 + i, 2.0, 3.5));
            z.goalSelector.removeAllGoals(g -> true);
            z.targetSelector.removeAllGoals(g -> true);
            mine.add(z);
        }
        ForestMoods.startHoard(level, day, 1.0D);
        try {
            helper.assertTrue(ForestDirector.hoarding() && director.mood() == ForestDirector.Mood.HOARD, "貯め始めていない");
            helper.assertTrue(ForestDirector.cap(level) > capBefore, "貯める間に上限が上がらない");
            director.setBudget(level, DesignData.director().costEvolve * 3.0D);
            Supplier<EntityType<? extends Mob>> zombie = () -> EntityType.ZOMBIE;
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), List.of(zombie)) == null, "貯める間に苗床が孵した");
        } catch (RuntimeException e) {
            ForestMoods.stopHoard(level, day);
            director.setBudget(level, before);
            throw e;
        }
        helper.runAfterDelay(3, () -> {
            try {
                for (AbstractParasitizedMonster z : mine) {
                    helper.assertTrue(z.fate().route() == ForestDirector.Route.NONE,
                            "貯める間に道を選んだ");
                }
                List<Vec3> around = List.of(mine.get(0).position());
                int n = ForestMoods.spend(level, day, RandomSource.create(2L), around);
                int evolving = 0;
                for (AbstractParasitizedMonster z : mine) {
                    if (z.fate().route() == ForestDirector.Route.EVOLVE) evolving++;
                }
                helper.assertTrue(n == 3 && evolving == 3, "一斉に進化させた数が違う(予算 3 体ぶん): " + n + " / " + evolving);
                helper.assertTrue(!ForestDirector.hoarding() && director.mood() == ForestDirector.Mood.NORMAL, "使い終えてもふだんに戻らない");
            } finally {
                ForestMoods.stopHoard(level, day);
                director.setBudget(level, before);
                for (AbstractParasitizedMonster z : mine) z.discard();
            }
            helper.succeed();
        });
    }
}
