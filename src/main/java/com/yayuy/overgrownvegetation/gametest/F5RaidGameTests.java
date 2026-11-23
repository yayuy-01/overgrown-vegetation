package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AssaultTable;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.spawn.BaseFouling;
import com.yayuy.overgrownvegetation.spawn.BreachGoal;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * F5 の襲撃(2026-11-16。正本は Vault「F5 の襲撃(最初の襲撃と送り込み)(11-16)」): 最初の襲撃が次の夜に来る・数が増える・上限 40・
 * 足りない分を湧かせて払う・貯めた襲撃の倍率・拠点が汚れる・壊せるブロックだけ壊す・石は壊さない。
 * フェーズの差し替えと湧かせる所の差し替えは世界に 1 つなので、世界を使う送り込みは 1 本にまとめて自分だけのバッチに入れる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class F5RaidGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(F5RaidGameTests.class);
        }

        private Registration() {
        }
    }

    private F5RaidGameTests() {
    }

    private static EntityType<?> type(String path) {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path));
    }

    /** 最初の襲撃は次の夜の始め(昼に認識 → その夜・夜に認識 → 明日の夜)。表は 10 から増える。 */
    @GameTest(template = "gametest_empty7", batch = "f5_raid_pure", timeoutTicks = 20)
    public static void firstNightAndWaveTable(GameTestHelper helper) {
        AssaultTable a = DesignData.director().assault;
        double start = a.nightStartTicks / 24000.0D, end = a.nightEndTicks / 24000.0D;
        double noon = BaseMemory.firstRaidDay(10.25D, 0.5D);
        helper.assertTrue(noon >= 10.0D + start && noon <= 10.0D + end, "昼に認識したのに、その日の夜の始めでない: " + noon);
        double night = BaseMemory.firstRaidDay(10.8D, 0.0D);
        helper.assertTrue(Math.abs(night - (11.0D + start)) < 1.0E-9D, "夜に認識したのに、明日の夜の始めでない: " + night);
        double morning = BaseMemory.firstRaidDay(11.01D, 1.0D);
        helper.assertTrue(Math.abs(morning - (11.0D + end)) < 1.0E-9D, "朝に認識したのに、その日の夜でない: " + morning);

        BaseMemoryTable t = DesignData.director().base;
        int prev = -1;
        for (double d : new double[] {0.0D, 1.0D, 2.0D, 4.0D, 6.0D, 30.0D}) {
            BaseMemoryTable.Wave w = t.waveFor(d);
            int sum = w.stageZero() + w.developed() + w.g2() + w.g3plus();
            helper.assertTrue(sum >= prev, "日がたつと数が減った: " + d + " 日 " + sum);
            prev = sum;
        }
        BaseMemoryTable.Wave w0 = t.waveFor(0.0D);
        helper.assertTrue(w0.stageZero() + w0.developed() + w0.g2() + w0.g3plus() == 10 && w0.g3plus() == 0, "最初の段が 10 体・G3 なしでない");
        BaseMemoryTable.Wave f = a.firstRaid;
        helper.assertTrue(f.stageZero() + f.developed() + f.g2() + f.g3plus() == 15 && f.g3plus() == 0, "最初の襲撃が 15 体・G1 と G2 だけでない");
        helper.succeed();
    }

    private static void clear(GameTestHelper helper) {
        for (Mob m : helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16.0D))) m.discard();
    }

    /** 拠点を汚す: 拠点の中にいる植物側の数だけ空気を上げる(1 体 1 分で +1%)。拠点の外の個体は数えない。 */
    @GameTest(template = "gametest_empty7", batch = "f5_raid_pure", timeoutTicks = 20)
    public static void attackersFoulTheBase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(3, 2, 3));
        BaseMemory mem = BaseMemory.createForTests();
        mem.addPresence(ChunkPos.asLong(center.getX() >> 4, center.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, 0.0D);
        mem.update(0.0D);
        BaseMemory.Base b = mem.bases().get(0);
        // テスト場が拠点の升の中に入るとは限らないので、拠点の真ん中とテスト場の距離が汚す半径の中かを先に見る
        double d = Math.hypot(center.getX() - b.centerX(), center.getZ() - b.centerZ());
        helper.assertTrue(d < DesignData.director().assault.foulRadius, "前提: テスト場が拠点の半径の中にない: " + d);
        for (int i = 0; i < 3; i++) {
            Mob m = (Mob) type("gregaria").create(level);
            m.moveTo(center.getX() + 0.5D + i, center.getY(), center.getZ() + 0.5D);
            m.setNoAi(true);
            level.addFreshEntity(m);
        }
        double before = BaseFouling.totalAirPercent;
        int n = BaseFouling.foulOnce(level, b);
        AssaultTable a = DesignData.director().assault;
        double want = a.foulAirPercentPerMinute * a.foulIntervalTicks / 1200.0D;
        helper.assertTrue(n >= 3, "拠点の中の植物側を数えない: " + n);
        helper.assertTrue(BaseFouling.totalAirPercent - before >= 3 * want - 1.0E-9D, "空気が上がらない: " + (BaseFouling.totalAirPercent - before));
        clear(helper);
        helper.succeed();
    }

    /** 壊す種(ファルキムネス)は、拠点への道をふさぐ板の壁を壊して進む。 */
    @GameTest(template = "gametest_empty7", batch = "f5_raid_breach", timeoutTicks = 900)
    public static void breakerBreaksPlankWall(GameTestHelper helper) {
        breachCase(helper, Blocks.OAK_PLANKS.defaultBlockState(), true);
    }

    /** 石の壁は壊さない(石で囲えば守れる)。 */
    @GameTest(template = "gametest_empty7", batch = "f5_raid_breach", timeoutTicks = 900)
    public static void breakerLeavesStoneWall(GameTestHelper helper) {
        breachCase(helper, Blocks.STONE.defaultBlockState(), false);
    }

    /** 小さな G2(グレガリア)は板の壁も壊さない。 */
    @GameTest(template = "gametest_empty7", batch = "f5_raid_breach", timeoutTicks = 900)
    public static void smallG2LeavesPlankWall(GameTestHelper helper) {
        breachCase(helper, Blocks.OAK_PLANKS.defaultBlockState(), false, "gregaria");
    }

    private static void breachCase(GameTestHelper helper, net.minecraft.world.level.block.state.BlockState wall, boolean expectBreak) {
        breachCase(helper, wall, expectBreak, "falcimnes");
    }

    private static void breachCase(GameTestHelper helper, net.minecraft.world.level.block.state.BlockState wall, boolean expectBreak, String species) {
        BreachGoal.breakAnywhereForTests = true; // テスト場には認識した拠点が無い(11-16 から壊すのは拠点のまわりだけ)
        // 東の端(x = 5)を床から天井まで壁でふさぐ。Mob は西(x = 1.5)、行き先はずっと東
        for (int z = 0; z < 7; z++) {
            for (int y = 2; y < 8; y++) helper.setBlock(new BlockPos(5, y, z), wall);
        }
        Mob m = (Mob) helper.spawn(type(species), new net.minecraft.world.phys.Vec3(1.5D, 2.0D, 3.5D));
        m.targetSelector.removeAllGoals(g -> true);
        BlockPos far = helper.absolutePos(new BlockPos(60, 2, 3));
        helper.assertTrue(PlantMarch.begin(m, -1, far.getX() + 0.5D, far.getZ() + 0.5D), species + " を歩かせられない");
        if (expectBreak) {
            helper.succeedWhen(() -> {
                boolean opened = false;
                for (int z = 0; z < 7 && !opened; z++) {
                    for (int y = 2; y < 5; y++) {
                        if (helper.getBlockState(new BlockPos(5, y, z)).isAir()) opened = true;
                    }
                }
                helper.assertTrue(opened, "板の壁を壊さない");
                m.discard();
            });
        } else {
            helper.runAtTickTime(600, () -> {
                for (int z = 0; z < 7; z++) {
                    for (int y = 2; y < 8; y++) {
                        helper.assertTrue(!helper.getBlockState(new BlockPos(5, y, z)).isAir(), "壊してはいけない壁が壊れた(" + species + "・" + wall + ")");
                    }
                }
                m.discard();
                helper.succeed();
            });
        }
    }
}
