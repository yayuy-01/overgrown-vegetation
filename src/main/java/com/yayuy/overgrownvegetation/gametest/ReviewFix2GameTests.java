package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.AssaultTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestMoods;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import com.yayuy.overgrownvegetation.spawn.RaidKeepAlive;
import com.yayuy.overgrownvegetation.spawn.RaidNightSleep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * コードの見直し ②1〜②5・②7 の直し(11-16)。正本は Vault「コードの見直し(11-16)」。
 * 世界に 1 つの記憶・フェーズの差し替えを使う確かめは 1 本にまとめ、自分だけのバッチに入れる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ReviewFix2GameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ReviewFix2GameTests.class);
        }

        private Registration() {
        }
    }

    private ReviewFix2GameTests() {
    }

    private static EntityType<?> type(String path) {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path));
    }

    /** ②1 の試し直しの時刻(純粋な計算)と、②4 の進化で引き継がない印・②7 の 1 つの時計。 */
    @GameTest(template = "gametest_empty7", batch = "review_fix2_pure", timeoutTicks = 20)
    public static void retryTimesTagsAndClock(GameTestHelper helper) {
        AssaultTable a = DesignData.director().assault;
        double start = a.nightStartTicks / 24000.0D;
        BaseMemory mem = BaseMemory.createForTests();
        mem.addPresence(ChunkPos.asLong(500_000, 500_000), DesignData.director().base.rememberScore * 2.0D, 0.0D);
        mem.update(0.0D);
        BaseMemory.Base b = mem.bases().get(0);
        // 夜の中: そのまま wait 日後
        mem.firstRaidRetry(b, 10.0D, 0.05D, 0.6D, 0.0D);
        helper.assertTrue(Math.abs(b.nextWaveDay() - 10.05D) < 1.0E-9D, "夜の中の試し直しがそのまま wait 後でない: " + b.nextWaveDay());
        // 試し直すと朝になる: 次の夜の始め
        mem.firstRaidRetry(b, 10.0D, 0.05D, 0.95D, 0.0D);
        helper.assertTrue(Math.abs(b.nextWaveDay() - (10.05D + start)) < 1.0E-9D, "朝にかかる試し直しが次の夜にならない: " + b.nextWaveDay());
        helper.assertTrue(BaseMemory.firstRaidPending(b), "試し直しで送った回数が増えた");
        // 日付と 1 日の時刻がずれていても、次の夜の始めまでを足す
        helper.assertTrue(Math.abs(BaseMemory.firstRaidDay(3.3D, 0.9D, 0.0D) - (3.3D + 0.1D + start)) < 1.0E-9D, "時刻を渡した最初の夜が違う");
        // 進化で引き継がない印
        helper.assertTrue(EnemySpawns.isSpawnTag(EnemySpawns.EDGE_TAG) && EnemySpawns.isSpawnTag(EnemySpawns.UNDER_TAG) && EnemySpawns.isSpawnTag(EnemySpawns.GROUP_TAG + "1")
                && !EnemySpawns.isSpawnTag(com.yayuy.overgrownvegetation.director.HoardRaids.TAG), "縁・地下の印の見分けが違う");
        // 時計: 森の日付はフェーズの暦(ゲームの時間 + 寝て飛ばした分)
        ServerLevel level = helper.getLevel();
        double want = PhaseData.get(level).clock(level.getServer()) / 24000.0D;
        helper.assertTrue(Math.abs(BaseMemory.today(level.getServer()) - want) < 1.0E-6D, "森の日付がフェーズの暦でない");
        helper.succeed();
    }

    /**
     * 世界を通す確かめ:
     * ②1 最初の襲撃が 0 体なら数えない / ②4 送り込みで縁の印が外れる / ②3 歩いている間は消えない / ②5 上限 40 / ②2 最初の襲撃の前は貯めない /
     * ②7 襲撃の夜は寝られない。
     */
    @GameTest(template = "gametest_empty7", batch = "review_fix2_world", timeoutTicks = 60)
    public static void firstRaidRetryTagsKeepCapHoardAndSleep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        double budgetBefore = director.available(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        BaseMemory global = BaseMemory.get(level.getServer());
        BlockPos spot = helper.absolutePos(new BlockPos(3, 2, 3));
        try {
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            // ②1: 予算も個体も無い → 0 体 → 送った回数は増えず、後で試し直す
            BaseMemory mem = BaseMemory.createForTests();
            mem.addPresence(ChunkPos.asLong((spot.getX() >> 4) + 4, spot.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, 0.0D);
            mem.update(0.0D);
            BaseMemory.Base b = mem.bases().get(0);
            mem.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, 0.0D);
            mem.waveNow(b, 1.0D);
            director.setBudget(level, 0.0D);
            DirectorHooks.setSpotForTests(() -> null);
            BaseRaids.dispatch(level, mem, 1.0D);
            helper.assertTrue(BaseMemory.firstRaidPending(b) && b.nextWaveDay() > 1.0D, "0 体の最初の襲撃を送ったと数えた・試し直しの時刻が無い: " + b.nextWaveDay());
            // ②4: 縁で湧いた G1 を送り込むと、縁の印が外れる
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(1.5D, 2.0D, 1.5D));
            z.addTag(EnemySpawns.EDGE_TAG);
            z.addTag(EnemySpawns.GROUP_TAG + "1");
            BaseRaids.sendWave(level, b, 1.0D, level.getRandom());
            helper.assertTrue(z.march().active() && !EnemySpawns.spawnedHere(z), "送り込んだ G1 に縁の印が残っている");
            // ②3: 拠点へ歩いている G4 は消えない。歩いていなければ消えてよい
            Mob g4 = (Mob) type("gregaria").create(level);
            g4.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
            level.addFreshEntity(g4);
            helper.assertTrue(!RaidKeepAlive.keep(g4), "ただの G4 まで消さないことになっている");
            PlantMarch.begin(g4, b);
            helper.assertTrue(RaidKeepAlive.keep(g4), "拠点へ歩いている G4 が消えうる");
            g4.discard();
            z.discard();
            // ②5: いる個体(段階 0・G4・G5)が多くても、計は 40 を超えない
            for (int i = 0; i < 20; i++) helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(1.5D + i % 5, 2.0D, 1.5D + i / 5));
            for (int i = 0; i < 13; i++) helper.spawn((EntityType<? extends Mob>) type("gregaria"), new Vec3(1.5D + i % 5, 3.0D, 4.5D + i / 5));
            for (int i = 0; i < 12; i++) helper.spawn((EntityType<? extends Mob>) type("falcimnes"), new Vec3(1.5D + i % 5, 4.0D, 1.5D + i / 5));
            BaseMemory.Base later = b;
            mem.waveSent(later, 1.0D, level.getRandom());
            BaseRaids.Result big = BaseRaids.sendWave(level, later, 7.0D, level.getRandom());
            helper.assertTrue(big.total() <= DesignData.director().assault.maxWave, "送り込みが 40 体を超えた: " + big.summary());
            clear(helper);
            // ②2: 最初の襲撃がまだの拠点があるうちは貯め始めない(世界の記憶を使う)
            global.clear();
            global.addPresence(ChunkPos.asLong(spot.getX() >> 4, spot.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, 0.0D);
            global.update(0.0D);
            BaseMemory.Base gb = global.bases().get(0);
            global.setStageForTests(gb, BaseMemory.Stage.RECOGNIZED, 0.0D);
            helper.assertTrue(ForestMoods.firstRaidOutstanding(level), "最初の襲撃がまだの拠点を見ていない");
            global.waveSent(gb, 0.0D, level.getRandom());
            helper.assertTrue(!ForestMoods.firstRaidOutstanding(level), "最初の襲撃が済んでも、まだと答える");
            // ②7: 最初の襲撃が今夜来る拠点のそばでは寝られない。遠くでは寝られる
            global.clear();
            global.addPresence(ChunkPos.asLong(spot.getX() >> 4, spot.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, 0.0D);
            global.update(0.0D);
            gb = global.bases().get(0);
            global.setStageForTests(gb, BaseMemory.Stage.RECOGNIZED, 0.0D);
            global.waveNow(gb, BaseMemory.today(level.getServer()));
            helper.assertTrue(RaidNightSleep.raidTonight(level.getServer(), spot.getX(), spot.getZ()), "最初の襲撃が来る夜に寝られる");
            helper.assertTrue(!RaidNightSleep.raidTonight(level.getServer(), spot.getX() + 1000.0D, spot.getZ()), "拠点から遠くでも寝られない");
            global.waveNow(gb, BaseMemory.today(level.getServer()) + 3.0D);
            helper.assertTrue(!RaidNightSleep.raidTonight(level.getServer(), spot.getX(), spot.getZ()), "襲撃の来ない夜に寝られない");
            helper.succeed();
        } finally {
            DirectorHooks.setSpotForTests(null);
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            director.setBudget(level, budgetBefore);
            global.clear();
            clear(helper);
        }
    }

    private static void clear(GameTestHelper helper) {
        for (Mob m : helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16.0D))) m.discard();
    }
}
