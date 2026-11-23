package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * 司令塔 第 4 段「大きな襲撃」(司令塔の襲撃の大・中・小の大)。世界に 1 つの気分・予算・拠点の記憶を使うので、世界を使う確かめは 1 本にまとめ、batch を分ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LargeRaidGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterLargeRaidGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(LargeRaidGameTests.class);
        }

        private Registration() {
        }
    }

    private LargeRaidGameTests() {
    }

    /** 起こる条件: F7 が開いている・定着か最盛(導入では起こさない)・認識した拠点がある・間が空いた・ふだんか貯めている最中。 */
    @GameTest(template = "gametest_empty7", batch = "director_large_pure", timeoutTicks = 20)
    public static void largeRaidStartsOnlyWhenAllowed(GameTestHelper helper) {
        var N = ForestDirector.Mood.NORMAL;
        helper.assertTrue(LargeRaids.canStart(true, PhaseStage.ESTABLISHED, true, 5.0D, 1.0D, N), "定着で起こらない");
        helper.assertTrue(LargeRaids.canStart(true, PhaseStage.PEAK, true, 5.0D, 1.0D, ForestDirector.Mood.HOARD), "貯めている最中から起こらない");
        helper.assertTrue(!LargeRaids.canStart(true, PhaseStage.INTRO, true, 5.0D, 1.0D, N), "導入で起こった");
        helper.assertTrue(!LargeRaids.canStart(false, PhaseStage.PEAK, true, 5.0D, 1.0D, N), "F7 より前に起こった");
        helper.assertTrue(!LargeRaids.canStart(true, PhaseStage.PEAK, false, 5.0D, 1.0D, N), "認識した拠点が無いのに起こった");
        helper.assertTrue(!LargeRaids.canStart(true, PhaseStage.PEAK, true, 5.0D, 6.0D, N), "前の襲撃からすぐ起こった");
        helper.assertTrue(!LargeRaids.canStart(true, PhaseStage.PEAK, true, 5.0D, 1.0D, ForestDirector.Mood.LARGE_OMEN), "前触れの最中にまた起こった");
        helper.assertTrue(DesignData.director().gates.largeRaid == 7, "大きな襲撃が F7 でない");
        helper.succeed();
    }

    /**
     * 前触れ: 植物側の湧きが止まり(苗床は孵さない)、上限が 4 倍。本番: 予算の分だけ攻め手を湧かせ続け、攻め手は印を持って拠点へ向かう。
     * 予算を使い切ったら終わる(攻め手は残る)。
     */
    @GameTest(template = "gametest_empty7", batch = "director_large_world", timeoutTicks = 40)
    public static void omenSuppressesThenSpawnsUntilBudgetRunsOut(GameTestHelper helper) {
        var level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        BaseMemory memory = BaseMemory.get(level.getServer());
        double before = director.available(level);
        double day = BaseMemory.today(level.getServer());
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        memory.clear();
        try {
            memory.addPresence(ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            memory.setStageForTests(memory.bases().get(0), BaseMemory.Stage.RECOGNIZED, day);
            memory.update(day);
            double capBefore = ForestDirector.cap(level);
            LargeRaids.startOmen(level, day, 2.0D);
            helper.assertTrue(DirectorGates.largeRaidOmen(level) && DirectorGates.plantSpawnsSuppressed(level), "前触れで植物側の湧きが止まらない");
            helper.assertTrue(Math.abs(ForestDirector.cap(level) - capBefore * DesignData.director().largeRaid.capFactor) < 1.0E-6D, "前触れの上限が 4 倍でない");
            Supplier<EntityType<? extends Mob>> pig = () -> EntityType.PIG;
            director.setBudget(level, 10000.0D);
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), List.of(pig)) == null, "前触れの間に苗床が孵した");
            LargeRaids.begin(level, day);
            helper.assertTrue(director.mood() == ForestDirector.Mood.LARGE && LargeRaids.target(level) != null, "本番が始まらない");
            int[] k = {0};
            LargeRaids.setSpotForTests(() -> helper.absolutePos(new BlockPos(1 + (k[0] % 5), 2, 1 + (k[0]++ / 5) % 5)));
            double budget = DesignData.director().largeRaid.costStageZero * 4.0D + 0.5D; // 段階 0 なら 4 体ぶん(1 段目は値段が高い)
            director.setBudget(level, budget);
            int total = 0;
            for (int i = 0; i < 10 && director.mood() == ForestDirector.Mood.LARGE; i++) {
                total += LargeRaids.step(level, RandomSource.create(10L + i));
            }
            // G2・G3 の攻め手も印を持つ(11-15 enemy-spawns。顔ぶれの plantShare に当たった回)
            List<Mob> attackers = level.getEntitiesOfClass(Mob.class, GtCompat.bounds(helper).inflate(8.0D),
                    m -> m.getTags().contains(LargeRaids.ATTACKER_TAG));
            helper.assertTrue(total >= 1 && attackers.size() == total, "攻め手が湧かない・印が無い: " + total + " / " + attackers.size());
            helper.assertTrue(attackers.stream().allMatch(m -> m instanceof AbstractParasitizedMonster g ? g.march().active() : com.yayuy.overgrownvegetation.spawn.PlantMarch.active(m)), "攻め手が拠点へ向かわない");
            helper.assertTrue(director.mood() == ForestDirector.Mood.NORMAL, "予算を使い切っても終わらない(残り " + director.available(level) + ")");
            helper.assertTrue(attackers.stream().allMatch(Mob::isAlive), "終わったら攻め手が消えた");
            attackers.forEach(net.minecraft.world.entity.Entity::discard);
        } finally {
            LargeRaids.setSpotForTests(null);
            if (director.mood() != ForestDirector.Mood.NORMAL) LargeRaids.end(level, day, "テスト");
            memory.clear();
            director.setBudget(level, before);
        }
        helper.succeed();
    }
}
