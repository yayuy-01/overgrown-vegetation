package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.front.FrontDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.director.RaidScales;
import com.yayuy.overgrownvegetation.director.RaidScales.RaidScale;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterRaidScaleGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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
            // 設定の倍率: 見送らない(11-23 片付け E: 数の倍率で値段を割る costFactor は、湧かせ方ごと消した)
            RaidScales.Knobs.skipChanceFactor = 0.0D;
            helper.assertTrue(!RaidScales.decide(7, 0.0D, 0.0D).skip(), "見送りの倍率 0 なのに見送った");
        } finally {
            RaidScales.Knobs.reset();
        }
        helper.succeed();
    }

    /**
     * 世界を通す: F5 で見送る → 財布が貯まる → もう 1 回見送る → 2 日で必ず中が起きる。F6 で 3 日以上なら大になり、大は静かな前触れの後に始まる。
     * 2026-11-23 片付け E: 中・大・小で実物を湧かせて財布から払う確かめは消した(襲撃の部隊は前線が出す。前線の襲撃は FrontRaidHuntGameTests)。
     * 前線を動かすのは、見張りの口と大の起こし方を通す間だけ(GameTest のサーバーでは前線が止まっている)。
     */
    @GameTest(template = "gametest_empty7", batch = "raid_scale_world", timeoutTicks = 60)
    public static void skipSaveAndSpend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        BaseMemory memory = BaseMemory.get(level.getServer());
        double budgetBefore = director.available(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        boolean frontBefore = FrontDirector.tickInGameTests;
        double day = BaseMemory.today(level.getServer());
        BlockPos spot = helper.absolutePos(new BlockPos(3, 2, 3));
        memory.clear();
        try {
            RaidScales.Knobs.reset();
            memory.addPresence(ChunkPos.asLong(spot.getX() >> 4, spot.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            memory.waveSent(b, day, level.getRandom()); // 最初の襲撃は済んだことに
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            RandomSource r = RandomSource.create(7L);

            // (0) 見張りの送り込みの口(BaseRaids.dispatch)を通す: 必ず見送る → 送った回数は増えず貯め始める
            int sentBefore = b.wavesSent();
            RaidScales.Knobs.skipChanceFactor = 100.0D;
            memory.waveNow(b, day);
            FrontDirector.tickInGameTests = true;
            BaseRaids.dispatch(level, memory, day);
            FrontDirector.tickInGameTests = frontBefore;
            helper.assertTrue(b.saving() && b.wavesSent() == sentBefore && b.nextWaveDay() > day, "送り込みの口で見送らない: " + RaidScales.lastNote);
            RaidScales.Knobs.reset();
            memory.endSaving(b);
            memory.setPurse(b, 0.0D);
            // (1) 見送る: 貯め始め・財布はまだ 0・次の機会は 2 日以内
            director.setBudget(level, 0.0D);
            helper.assertTrue(RaidScales.skipOrStart(level, memory, b, day, 0.0D, r), "F5 で 0 を引いて見送らない");
            helper.assertTrue(b.saving() && b.nextWaveDay() <= day + 2.0D + 1.0E-9D && b.nextWaveDay() > day, "見送った後の次の機会が違う: " + b.nextWaveDay());
            // 貯めの 1 日目はうろつかない・2 日目(1 日たった後)からうろつく(11-19 ユーザー)
            RaidScales.refreshSaving(level, memory, day);
            helper.assertTrue(!RaidScales.holdingOff(), "貯めの 1 日目(見送った直後)から G1 が遠巻きにうろつく");
            RaidScales.refreshSaving(level, memory, day + RaidScales.holdOffFromDays() - 0.05D);
            helper.assertTrue(!RaidScales.holdingOff(), "貯めの 1 日目の終わりに G1 が遠巻きにうろつく");
            RaidScales.refreshSaving(level, memory, day + RaidScales.holdOffFromDays());
            helper.assertTrue(RaidScales.holdingOff(), "貯めの 2 日目に G1 が遠巻きにうろつかない");
            // (2) 1.5 日後にもう 1 回見送る: 財布に 1.5 日分の 30%
            double expect = RaidScales.credit(ForestDirector.incomePerDay(level), com.yayuy.overgrownvegetation.ecosystem.Ecosystem.incomeFactor(level),
                    RaidScales.purseShare(), 1.5D, 1);
            helper.assertTrue(RaidScales.skipOrStart(level, memory, b, day + 1.5D, 0.0D, r), "1.5 日で見送らない(上限 2 日の前)");
            helper.assertTrue(expect > 1.0D && Math.abs(b.purse() - expect) < 1.0E-6D, "財布が貯まっていない: " + b.purse() + " / " + expect);
            helper.assertTrue(b.nextWaveDay() <= day + 2.0D + 1.0E-9D, "次の機会が上限の 2 日を越えた: " + (b.nextWaveDay() - day));
            // (3) 2 日: 0 を引いても必ず起き、F5 なので中
            helper.assertTrue(!RaidScales.skipOrStart(level, memory, b, day + 2.0D, 0.0D, r), "上限の日数で起きない");
            helper.assertTrue(RaidScales.lastScale == RaidScale.MEDIUM && !b.saving(), "F5 の 2 日で中が起きない: " + RaidScales.lastNote);

            // (4) F6 で 3 日以上 → 大。静かな前触れの後に本番
            DirectorGates.setPhaseForTests(6);
            memory.skipWave(b, day + 3.0D, day, day + 3.5D);
            FrontDirector.tickInGameTests = true;
            boolean skipped = RaidScales.skipOrStart(level, memory, b, day + 3.5D, 0.99D, r);
            FrontDirector.tickInGameTests = frontBefore;
            helper.assertTrue(!skipped, "F6 で 3.5 日なのに起きない");
            helper.assertTrue(RaidScales.lastScale == RaidScale.LARGE && director.mood() == ForestDirector.Mood.LARGE_OMEN && LargeRaids.scaled(director),
                    "F6 の 3.5 日で大の静かな前触れにならない: " + RaidScales.lastNote + "・" + director.mood());
            // 静かな前触れ: 森の湧きが止まる・G1 はうろつかない・前触れの長さは omenDays
            RaidScales.refreshSaving(level, memory, day + 3.5D);
            helper.assertTrue(DirectorGates.plantSpawnsSuppressed(level) && !RaidScales.holdingOff() && !b.saving(), "大の前触れで静かにならない");
            helper.assertTrue(Math.abs(director.moodUntil() - (day + 3.5D + RaidScales.omenDays(RaidScale.LARGE))) < 1.0E-9D, "前触れの長さが違う");
            LargeRaids.beginScaled(level, b, day + 5.5D);
            helper.assertTrue(director.mood() == ForestDirector.Mood.LARGE && LargeRaids.scaled(director), "前触れの後に大が始まらない");
            LargeRaids.end(level, day + 5.6D, "テスト");
            helper.assertTrue(director.mood() == ForestDirector.Mood.NORMAL && !LargeRaids.scaled(director), "大が終わらない");
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = frontBefore;
            if (director.mood() == ForestDirector.Mood.LARGE || director.mood() == ForestDirector.Mood.LARGE_OMEN) LargeRaids.end(level, day, "テスト");
            RaidScales.Knobs.reset();
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
