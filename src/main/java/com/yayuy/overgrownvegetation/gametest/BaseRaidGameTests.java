package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 司令塔 第 2 段 区切り 3: 送り込み({@link BaseRaids#sendWave})。まわり 128 ブロックの G1 を集めるので、ほかのテストと同じバッチに入れない
 * (このバッチはこの 1 本だけ)。世界に 1 つの予算を書き換えるので、終わったら戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseRaidGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterBaseRaidGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(BaseRaidGameTests.class);
        }

        private Registration() {
        }
    }

    private BaseRaidGameTests() {
    }

    /**
     * 認識して 1 日の段: 段階 0 を歩かせ、進化体が足りない分は予算で段階 0 を進化させて送る。予算が無ければ進化させない。
     * G2・G3〜 は口だけ(まだ出せない)。
     */
    @GameTest(template = "gametest_empty7", batch = "base_raid", timeoutTicks = 20)
    public static void waveSendsStageZeroAndEvolvesWithBudget(GameTestHelper helper) {
        var level = helper.getLevel();
        List<AbstractParasitizedMonster> mine = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(1.5 + i, 2.0, 3.5));
            z.goalSelector.removeAllGoals(g -> true);
            z.targetSelector.removeAllGoals(g -> true);
            mine.add(z);
        }
        // 拠点はテスト場から 3 チャンク東(段階 0 が拠点に近すぎない所)
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(ChunkPos.asLong((mine.get(0).getBlockX() >> 4) + 3, mine.get(0).getBlockZ() >> 4),
                DesignData.director().base.rememberScore * 2.0D, 0.0D);
        m.update(0.0D);
        BaseMemory.Base b = m.bases().get(0);
        m.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, 0.0D);
        m.waveSent(b, 0.0D, level.getRandom()); // 見直し ④(11-16): 最初の襲撃を済ませてから、ふつうの段(認識して 1 日)を試す
        ForestDirector director = ForestDirector.get(level);
        double before = director.available(level);
        // 11-16: 足りない分を湧かせる所を「無し」に(このテストは、いる個体を歩かせる・進化させる所だけを見る)
        com.yayuy.overgrownvegetation.spawn.DirectorHooks.setSpotForTests(() -> null);
        try {
            BaseMemoryTable.Wave wave = DesignData.director().base.waveFor(1.0D);
            director.setBudget(level, 0.0D);
            BaseRaids.Result poor = BaseRaids.sendWave(level, b, 1.0D, level.getRandom());
            helper.assertTrue(poor.evolved() == 0, "予算が無いのに進化させた");
            helper.assertTrue(poor.g2() == 0 && poor.g3plus() == 0, "G2 以降はまだ出せないはず");
            helper.assertTrue(poor.stageZero() == Math.min(wave.stageZero(), 4), "段階 0 を段の数だけ送っていない: " + poor.summary());
            for (AbstractParasitizedMonster z : mine) z.march().cancel();
            director.setBudget(level, 100000.0D); // 11-20 夜: 体数は予算で決まるので、進化に回る分が出るだけの予算
            BaseRaids.Result rich = BaseRaids.sendWave(level, b, 1.0D, level.getRandom());
            int evolving = 0;
            for (AbstractParasitizedMonster z : mine) {
                if (z.fate().route() == ForestDirector.Route.EVOLVE && z.march().active()) evolving++;
            }
            helper.assertTrue(rich.evolved() >= 1 && rich.evolved() <= 4 && evolving == rich.evolved(),
                    "予算があるのに進化させて送らない: " + rich.summary());
        } finally {
            com.yayuy.overgrownvegetation.spawn.DirectorHooks.setSpotForTests(null);
            director.setBudget(level, before);
        }
        for (AbstractParasitizedMonster z : mine) z.discard();
        helper.succeed();
    }

    /**
     * 区切り 4: 認識した拠点のそばの苗床から孵った G1 は拠点へ向かう。拠点から遠い苗床は、ときどき孵すのを断る(そばの苗床は断らない)。
     * 世界に 1 つの記憶を使う(GameTest の世界にプレイヤーはいないので空のはず)。終わったら消す。
     */
    @GameTest(template = "gametest_empty7", batch = "base_raid", timeoutTicks = 20)
    public static void nurseryNearBaseSendsHatchlingsAndFarOnesYield(GameTestHelper helper) {
        var level = helper.getLevel();
        BaseMemory global = BaseMemory.get(level.getServer());
        global.clear();
        try {
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
            z.goalSelector.removeAllGoals(g -> true);
            double day = BaseMemory.today(level.getServer());
            global.addPresence(ChunkPos.asLong(z.getBlockX() >> 4, z.getBlockZ() >> 4), DesignData.director().base.rememberScore * 2.0D, day);
            global.update(day);
            global.setStageForTests(global.bases().get(0), BaseMemory.Stage.RECOGNIZED, day);
            global.update(day);
            helper.assertTrue(BaseRaids.onNurseryHatched(level, z) && z.march().active(), "拠点のそばで孵った G1 が拠点へ向かわない");
            var random = net.minecraft.util.RandomSource.create(3L);
            int nearSkips = 0, farSkips = 0;
            Vec3 far = z.position().add(DesignData.director().base.evolveBaseBlocks * 3.0D, 0.0D, 0.0D);
            for (int i = 0; i < 100; i++) {
                if (ForestDirector.skipFarNursery(level, z.position(), random)) nearSkips++;
                if (ForestDirector.skipFarNursery(level, far, random)) farSkips++;
            }
            helper.assertTrue(nearSkips == 0, "拠点のそばの苗床が断った");
            helper.assertTrue(farSkips > 0 && farSkips < 100 || DesignData.director().base.farNurserySkip >= 1.0D, "遠い苗床が断らない: " + farSkips);
            z.discard();
        } finally {
            global.clear();
        }
        helper.succeed();
    }
}
