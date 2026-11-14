package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WatcherTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestMoods;
import com.yayuy.overgrownvegetation.director.PlantSideCall;
import com.yayuy.overgrownvegetation.director.Watchers;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 司令塔の監視塔の口(植える・確定・刈られる・呼ぶ)。監視塔の本体はまだ無いので、植える口をテスト用に差し替えて確かめる。
 * 世界に 1 つの予算・拠点の記憶・植える口を使うので、1 本にまとめ、batch を分ける。終わったら戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WatcherGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(WatcherGameTests.class);
        }

        private Registration() {
        }
    }

    private WatcherGameTests() {
    }

    @GameTest(template = "gametest_empty7", batch = "director_watcher", timeoutTicks = 40)
    public static void plantConfirmCutAndCall(GameTestHelper helper) {
        var level = helper.getLevel();
        WatcherTable t = DesignData.director().watcher;
        ForestDirector director = ForestDirector.get(level);
        BaseMemory memory = BaseMemory.get(level.getServer());
        double before = director.available(level);
        double d0 = BaseMemory.today(level.getServer());
        List<BlockPos> planted = new ArrayList<>();
        int[] k = {0};
        memory.clear();
        try {
            BlockPos center = helper.absolutePos(new BlockPos(3, 2, 3));
            memory.addPresence(ChunkPos.asLong(center.getX() >> 4, center.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, d0);
            memory.update(d0);
            BaseMemory.Base b = memory.bases().get(0);

            // 監視塔が組み込まれていない間: 植えない・払わない
            director.setBudget(level, t.cost * 5.0D);
            Watchers.setPlanterForTests(null);
            if (!Watchers.active()) {
                helper.assertTrue(Watchers.tryPlant(level, memory, b, d0, RandomSource.create(1L)) == null, "組み込まれていないのに植えた");
                helper.assertTrue(Math.abs(director.available(level) - t.cost * 5.0D) < 1.0E-6D, "植えないのに払った");
            }

            // 植える: 予算を払い、場所を持つ。間を空けないと 2 本目は植えない。最大 2 本
            Watchers.setPlanterForTests((lv, pos, base) -> planted.add(pos));
            Watchers.setSpotForTests(() -> helper.absolutePos(new BlockPos(1 + k[0]++, 2, 1)));
            BlockPos first = Watchers.tryPlant(level, memory, b, d0, RandomSource.create(2L));
            helper.assertTrue(first != null && b.watchers().size() == 1 && planted.size() == 1, "植えない");
            helper.assertTrue(Math.abs(director.available(level) - t.cost * 4.0D) < 1.0E-6D, "植えた値段が違う: " + director.available(level));
            helper.assertTrue(Watchers.tryPlant(level, memory, b, d0, RandomSource.create(3L)) == null, "間を空けずに 2 本目を植えた");
            helper.assertTrue(Watchers.tryPlant(level, memory, b, d0 + t.plantIntervalDays, RandomSource.create(4L)) != null, "間を空けても 2 本目を植えない");
            helper.assertTrue(Watchers.tryPlant(level, memory, b, d0 + t.plantIntervalDays * 3, RandomSource.create(5L)) == null, "3 本目を植えた");

            // 確定(11-14 ユーザー W1 = 2): 偵察済みの拠点に監視塔の印が付いたら、日数を待たずにすぐ認識。「覚えた」だけでは認識しない
            Pig early = helper.spawn(EntityType.PIG, new Vec3(4.5, 2.0, 4.5));
            Watchers.markSeen(level, first, early);
            helper.assertTrue(memory.bases().get(0).stage() == BaseMemory.Stage.REMEMBERED, "偵察が済む前に、印だけで認識した");
            early.discard();
            memory.onScouted(b.id(), d0, RandomSource.create(6L));
            memory.update(d0, true);
            helper.assertTrue(memory.bases().get(0).stage() == BaseMemory.Stage.SCOUTED, "偵察の前の印で認識した(偵察の後にもう一度見られる必要がある)");
            double late = d0 + DesignData.director().base.recognizeMaxDays + 0.1D;
            memory.addPresence(ChunkPos.asLong(center.getX() >> 4, center.getZ() >> 4), 0.1D, late);
            memory.update(late, true);
            helper.assertTrue(memory.bases().get(0).stage() == BaseMemory.Stage.SCOUTED, "監視塔に見られていないのに認識した");
            Pig pig = helper.spawn(EntityType.PIG, new Vec3(5.5, 2.0, 5.5));
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(1.5, 2.0, 5.5));
            z.goalSelector.removeAllGoals(g -> true);
            int called = Watchers.markSeen(level, first, pig);
            helper.assertTrue(memory.bases().get(0).stage() == BaseMemory.Stage.RECOGNIZED, "監視塔に見られても、その場で認識しない");
            helper.assertTrue(called >= 1 && z.march().active() && z.march().baseId() == PlantSideCall.CALL_ID, "印の相手へ呼び寄せない: " + called);

            // 呼ぶ: 貯めている間は呼ばない
            z.march().cancel();
            ForestMoods.startHoard(level, d0, 1.0D);
            helper.assertTrue(PlantSideCall.towards(level, Vec3.atCenterOf(first), pig, t.callRadius, t.callTicks) == 0, "貯めている間に呼んだ");
            ForestMoods.stopHoard(level, d0);

            // 刈られる: 育った後なら、認識から 1 段戻る。立っている本数が減る
            Watchers.onGrown(level, first);
            helper.assertTrue(Watchers.onCut(level, first), "植えた監視塔だと分からない");
            helper.assertTrue(memory.bases().get(0).stage() == (t.cutGrownSetsBack ? BaseMemory.Stage.SCOUTED : BaseMemory.Stage.RECOGNIZED),
                    "育った監視塔を刈っても戻らない: " + memory.bases().get(0).stage());
            helper.assertTrue(memory.bases().get(0).watchers().size() == 1 && memory.bases().get(0).watchersCutGrown() == 1, "刈った数・残りが違う");
            pig.discard();
            z.discard();
        } finally {
            Watchers.setPlanterForTests(null);
            Watchers.setSpotForTests(null);
            memory.clear();
            director.setBudget(level, before);
        }
        helper.succeed();
    }
}
