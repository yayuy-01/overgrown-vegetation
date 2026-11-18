package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.HoardRaids;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.director.RaidScales;
import com.yayuy.overgrownvegetation.director.RaidScales.RaidScale;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 襲撃の規模(2026-11-19 ユーザー決定「見送りと貯め」): 見送りで財布が貯まる・上限の日数で必ず起きる・フェーズで規模が絞られる・
 * 財布に応じて出る数が変わる・同時の上限で頭打ちになり、使い残しは後の送り込みが使う・大は財布から払って尽きたら終わる。
 * フェーズの差し替え・湧かせる所の差し替え・拠点の記憶は世界に 1 つなので、世界を使う方は 1 本にまとめて自分だけのバッチに入れる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RaidScaleGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(RaidScaleGameTests.class);
        }

        private Registration() {
        }
    }

    private RaidScaleGameTests() {
    }

    /** 決め方(純粋な計算): 見送る確率・上限の日数で必ず起きる・段の呼び分けとフェーズの絞り・次の機会・財布へ足す量・設定の倍率。 */
    @GameTest(template = "gametest_empty7", batch = "raid_scale_pure", timeoutTicks = 20)
    public static void decideTiersAndCaps(GameTestHelper helper) {
        try {
            RaidScales.Knobs.reset();
            // 見送る確率(F5 40%): 引いた数が下なら見送る・上なら小
            helper.assertTrue(RaidScales.decide(5, 0.0D, 0.39D).skip(), "F5 で 0.39 を引いて見送らない");
            RaidScales.Decision small = RaidScales.decide(5, 0.0D, 0.41D);
            helper.assertTrue(!small.skip() && small.scale() == RaidScale.SMALL, "貯め 0 日で小でない: " + small);
            // 上限の日数(F5 2 日)に届いたら、引いた数によらず起きる。F5 は中まで
            RaidScales.Decision forced = RaidScales.decide(5, 2.0D, 0.0D);
            helper.assertTrue(!forced.skip() && forced.scale() == RaidScale.MEDIUM, "F5 で 2 日たまって必ず中にならない: " + forced);
            helper.assertTrue(RaidScales.decide(5, 1.5D, 0.0D).skip(), "F5 で 1.5 日なのに上限扱い");
            // 段: 1〜2 日 = 中・3 日以上 = 大(大は F6 から)
            helper.assertTrue(RaidScales.tierFor(6, 1.0D) == RaidScale.MEDIUM && RaidScales.tierFor(6, 2.9D) == RaidScale.MEDIUM, "F6 の 1〜2 日が中でない");
            helper.assertTrue(RaidScales.tierFor(6, 3.0D) == RaidScale.LARGE && RaidScales.tierFor(7, 10.0D) == RaidScale.LARGE, "3 日以上が大でない");
            helper.assertTrue(RaidScales.tierFor(5, 4.0D) == RaidScale.MEDIUM, "F5 で大が出た(大は F6 から)");
            RaidScales.Decision f6 = RaidScales.decide(6, 5.0D, 0.0D);
            helper.assertTrue(!f6.skip() && f6.scale() == RaidScale.LARGE, "F6 で 5 日たまって必ず大にならない: " + f6);
            helper.assertTrue(RaidScales.decide(7, 5.0D, 0.59D).skip() && !RaidScales.decide(7, 5.0D, 0.61D).skip(), "F7 の見送る確率が 60% でない");
            helper.assertTrue(!RaidScales.decide(4, 0.0D, 0.0D).skip(), "F4 で見送った(上限 0 日)");
            // 次の機会は 貯め始め + 上限 を越えない
            helper.assertTrue(Math.abs(RaidScales.nextOpportunity(10.0D, 1.5D, 9.0D, 2.0D) - 11.0D) < 1.0E-9D, "次の機会が上限の日を越えた");
            helper.assertTrue(Math.abs(RaidScales.nextOpportunity(10.0D, 1.5D, 10.0D, 5.0D) - 11.5D) < 1.0E-9D, "次の機会が 今 + 間 でない");
            // 財布へ: 1 日の生産量 × 健康 × 30% × 日数 ÷ 拠点の数
            helper.assertTrue(Math.abs(RaidScales.credit(450.0D, 1.0D, 0.3D, 1.5D, 1) - 202.5D) < 1.0E-9D, "財布へ足す量が違う");
            helper.assertTrue(Math.abs(RaidScales.credit(450.0D, 0.5D, 0.3D, 2.0D, 2) - 67.5D) < 1.0E-9D, "健康・拠点の数で割っていない");
            // 設定の倍率: 見送らない・数の倍率は値段を割る
            RaidScales.Knobs.skipChanceFactor = 0.0D;
            helper.assertTrue(!RaidScales.decide(7, 0.0D, 0.0D).skip(), "見送りの倍率 0 なのに見送った");
            RaidScales.Knobs.countFactor[RaidScale.LARGE.ordinal()] = 2.0D;
            helper.assertTrue(Math.abs(RaidScales.costFactor(RaidScale.LARGE) - 0.5D) < 1.0E-9D, "数の倍率 2 で値段が半分にならない");
        } finally {
            RaidScales.Knobs.reset();
        }
        helper.succeed();
    }

    /**
     * 世界を通す: F5 で見送る → 財布が貯まる → もう 1 回見送る → 2 日で必ず中が起きて財布から払う → 中は同時 40 で頭打ち・使い残しは後の小が使う。
     * 財布が少ないと中の数が減る。F6 で 3 日以上なら大になり、大は財布から払って尽きたら終わる(森の予算には手を付けない)。
     */
    @GameTest(template = "gametest_empty7", batch = "raid_scale_world", timeoutTicks = 60)
    public static void skipSaveAndSpend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        BaseMemory memory = BaseMemory.get(level.getServer());
        double budgetBefore = director.available(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        double day = BaseMemory.today(level.getServer());
        BlockPos spot = helper.absolutePos(new BlockPos(3, 2, 3));
        memory.clear();
        try {
            RaidScales.Knobs.reset();
            HoardRaids.clearForTests();
            memory.addPresence(ChunkPos.asLong(spot.getX() >> 4, spot.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            memory.waveSent(b, day, level.getRandom()); // 最初の襲撃は済んだことに
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            DirectorHooks.setSpotForTests(() -> spot);
            RandomSource r = RandomSource.create(7L);

            // (1) 見送る: 貯め始め・財布はまだ 0・次の機会は 2 日以内
            director.setBudget(level, 0.0D);
            helper.assertTrue(RaidScales.skipOrStart(level, memory, b, day, 0.0D, r), "F5 で 0 を引いて見送らない");
            helper.assertTrue(b.saving() && b.nextWaveDay() <= day + 2.0D + 1.0E-9D && b.nextWaveDay() > day, "見送った後の次の機会が違う: " + b.nextWaveDay());
            RaidScales.refreshSaving(level, memory);
            helper.assertTrue(RaidScales.holdingOff(), "見送って貯めている間に G1 が遠巻きにうろつかない");
            // (2) 1.5 日後にもう 1 回見送る: 財布に 1.5 日分の 30%
            double expect = RaidScales.credit(ForestDirector.incomePerDay(level), com.yayuy.overgrownvegetation.ecosystem.Ecosystem.incomeFactor(level),
                    RaidScales.purseShare(), 1.5D, 1);
            helper.assertTrue(RaidScales.skipOrStart(level, memory, b, day + 1.5D, 0.0D, r), "1.5 日で見送らない(上限 2 日の前)");
            helper.assertTrue(expect > 1.0D && Math.abs(b.purse() - expect) < 1.0E-6D, "財布が貯まっていない: " + b.purse() + " / " + expect);
            helper.assertTrue(b.nextWaveDay() <= day + 2.0D + 1.0E-9D, "次の機会が上限の 2 日を越えた: " + (b.nextWaveDay() - day));
            // (3) 2 日: 0 を引いても必ず起き、F5 なので中。財布を足して大きくしてから(払う順を見る)
            memory.setPurse(b, 5000.0D);
            director.setBudget(level, 0.0D);
            helper.assertTrue(!RaidScales.skipOrStart(level, memory, b, day + 2.0D, 0.0D, r), "上限の日数で起きない");
            helper.assertTrue(RaidScales.lastScale == RaidScale.MEDIUM && !b.saving() && HoardRaids.raid(b.id()) != null, "F5 の 2 日で中が起きない: " + RaidScales.lastNote);
            int alive = HoardRaids.alive(level, b);
            helper.assertTrue(alive > 0 && alive <= HoardRaids.maxAlive(), "中が同時の上限 40 を守らない: " + alive);
            double left = b.purse();
            helper.assertTrue(left < 5000.0D && left > 0.0D, "中が財布から払っていない・使い残しが無い: " + left);
            HoardRaids.clearForTests();
            clear(helper);
            // (4) 使い残しは後の送り込み(小)が使う(森の予算は 0 のまま)
            director.setBudget(level, 0.0D);
            BaseRaids.Result next = BaseRaids.sendWave(level, b, day + 3.0D, r);
            helper.assertTrue(next.total() > 0 && b.purse() < left, "使い残しの財布で次の送り込みが出ない: " + next.summary() + "・財布 " + b.purse());
            clear(helper);
            // (5) 財布が少ないと中の数が減る
            memory.setPurse(b, 20.0D);
            director.setBudget(level, 0.0D);
            int few = HoardRaids.begin(level, b, day + 3.0D, r);
            HoardRaids.clearForTests();
            clear(helper);
            helper.assertTrue(few > 0 && few < alive, "財布 20 の中が、財布 5000 の中より少なくない: " + few + " / " + alive);

            // (6) F6 で 3 日以上 → 大(前触れなし)。財布から払い、尽きたら終わる。森の予算には手を付けない
            DirectorGates.setPhaseForTests(6);
            memory.skipWave(b, day + 3.0D, day, day + 3.5D);
            memory.setPurse(b, DesignData.director().largeRaid.costStageZero * 6.0D + 0.5D);
            director.setBudget(level, 0.0D);
            helper.assertTrue(!RaidScales.skipOrStart(level, memory, b, day + 3.5D, 0.99D, r), "F6 で 3.5 日なのに起きない");
            helper.assertTrue(RaidScales.lastScale == RaidScale.LARGE && director.mood() == ForestDirector.Mood.LARGE_OMEN && LargeRaids.scaled(director),
                    "F6 の 3.5 日で大の静かな前触れにならない: " + RaidScales.lastNote + "・" + director.mood());
            // 静かな前触れ: 森の湧きが止まる・G1 はうろつかない・財布は増減しない・前触れの長さは 2 日
            RaidScales.refreshSaving(level, memory);
            helper.assertTrue(DirectorGates.plantSpawnsSuppressed(level) && !RaidScales.holdingOff() && !b.saving(), "大の前触れで静かにならない");
            helper.assertTrue(Math.abs(director.moodUntil() - (day + 3.5D + RaidScales.omenDays(RaidScale.LARGE))) < 1.0E-9D, "前触れの長さが違う");
            LargeRaids.beginScaled(level, b, day + 5.5D);
            helper.assertTrue(director.mood() == ForestDirector.Mood.LARGE && LargeRaids.scaled(director), "前触れの後に大が始まらない");
            int[] k = {0};
            LargeRaids.setSpotForTests(() -> helper.absolutePos(new BlockPos(1 + (k[0] % 5), 2, 1 + (k[0]++ / 5) % 5)));
            int total = 0;
            for (int i = 0; i < 12 && director.mood() == ForestDirector.Mood.LARGE; i++) {
                director.setBudget(level, 0.0D);
                total += LargeRaids.step(level, RandomSource.create(20L + i));
            }
            helper.assertTrue(total >= 1 && b.purse() < DesignData.director().largeRaid.costStageZero, "大が財布から払っていない: " + total + "・財布 " + b.purse());
            helper.assertTrue(director.mood() == ForestDirector.Mood.NORMAL && !LargeRaids.scaled(director), "財布が尽きても大が終わらない");
            clear(helper);
            // (7) 設定のページの合計の上限(大 3 体): 財布が残っていても 3 体で終わり、使い残しは財布に残る
            RaidScales.Knobs.maxTotal[RaidScale.LARGE.ordinal()] = 3;
            memory.setPurse(b, 1000.0D);
            LargeRaids.beginScaled(level, b, day + 6.0D);
            int capped = 0;
            for (int i = 0; i < 12 && director.mood() == ForestDirector.Mood.LARGE; i++) {
                director.setBudget(level, 0.0D);
                capped += LargeRaids.step(level, RandomSource.create(40L + i));
            }
            helper.assertTrue(capped >= 1 && capped <= 3 && director.mood() == ForestDirector.Mood.NORMAL && b.purse() > 900.0D,
                    "大の合計の上限 3 で終わらない: " + capped + "・" + director.mood() + "・財布 " + b.purse());
            helper.succeed();
        } finally {
            LargeRaids.setSpotForTests(null);
            if (director.mood() == ForestDirector.Mood.LARGE) LargeRaids.end(level, day, "テスト");
            HoardRaids.clearForTests();
            RaidScales.Knobs.reset();
            DirectorHooks.setSpotForTests(null);
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            memory.clear();
            director.setBudget(level, budgetBefore);
            clear(helper);
        }
    }

    private static void clear(GameTestHelper helper) {
        for (Mob m : helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16.0D))) m.discard();
    }
}
