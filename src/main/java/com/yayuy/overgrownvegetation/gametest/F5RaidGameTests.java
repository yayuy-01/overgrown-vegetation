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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

    /** 最初の襲撃は次の夜の始め(昼に認識 → その夜・夜に認識 → 明日の夜)。表は 10 → 40 で増え、上限 40 に収まる。 */
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
        helper.assertTrue(prev == a.maxWave, "最後の段が上限の " + a.maxWave + " でない: " + prev);
        BaseMemoryTable.Wave f = a.firstRaid;
        helper.assertTrue(f.stageZero() + f.developed() + f.g2() + f.g3plus() == 15 && f.g3plus() == 0, "最初の襲撃が 15 体・G1 と G2 だけでない");
        // 上限に収める: 安いもの(段階 0 → G2)から削る
        BaseMemoryTable.Wave big = BaseRaids.capped(new BaseMemoryTable.Wave(0.0D, 20, 10, 20, 10), 40);
        helper.assertTrue(big.stageZero() + big.developed() + big.g2() + big.g3plus() == 40 && big.stageZero() == 0 && big.developed() == 10 && big.g3plus() == 10,
                "上限の削り方が違う: " + big);
        helper.succeed();
    }

    /**
     * 世界を通す送り込み: 最初の襲撃(15 体・湧かせて払う)→ 日がたった回(40 体まで)→ 予算が少なければ安い段階 0 から送る → 貯めた襲撃は進化体と G3 が 1.5 倍。
     * 湧かせる所はテスト場に決め打ち(テスト場の高さの地図は天井を答えるため)。
     */
    @GameTest(template = "gametest_empty7", batch = "f5_raid_world", timeoutTicks = 60)
    public static void wavesSpawnAndPayInOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        double budgetBefore = director.available(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        AssaultTable a = DesignData.director().assault;
        BlockPos spot = helper.absolutePos(new BlockPos(3, 2, 3));
        BaseMemory mem = BaseMemory.createForTests();
        mem.addPresence(ChunkPos.asLong((spot.getX() >> 4) + 4, spot.getZ() >> 4), DesignData.director().base.rememberScore * 2.0D, 0.0D);
        mem.update(0.0D);
        BaseMemory.Base b = mem.bases().get(0);
        mem.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, 0.0D);
        try {
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            DirectorHooks.setSpotForTests(() -> spot);
            com.yayuy.overgrownvegetation.director.ForestEconomy.setIncomeForTests(300000.0D); // 11-20 夜: 1 回の予算は 1 日の稼ぎの日数分まで。ここでは予算の側で決まるように稼ぎを大きく
            // 最初の襲撃
            director.setBudget(level, 500000.0D);
            helper.assertTrue(BaseMemory.firstRaidPending(b), "まだ送っていないのに最初の襲撃でない");
            BaseRaids.Result first = BaseRaids.sendWave(level, b, 0.0D, level.getRandom());
            helper.assertTrue(first.first() && first.total() == 15 && first.g3plus() == 0 && first.g2() == a.firstRaid.g2(),
                    "最初の襲撃が 15 体(G1 と G2)でない: " + first.summary());
            helper.assertTrue(first.spawnedZero() > 0, "段階 0 の足りない分を湧かせていない: " + first.summary());
            mem.waveSent(b, 0.0D, level.getRandom());
            helper.assertTrue(!BaseMemory.firstRaidPending(b), "送った後も最初の襲撃のまま");
            double gap = b.nextWaveDay();
            BaseMemoryTable bt = DesignData.director().base;
            helper.assertTrue(gap >= bt.waveIntervalMinDays - 1.0E-9D && gap <= bt.waveIntervalMaxDays + 1.0E-9D, "次の送り込みまでが 1〜2 日でない: " + gap);
            clear(helper);
            // 6 日たった回: 11-20 夜から体数は予算だけで決まる(予算 × waveBudgetShare ÷ 顔ぶれの平均の値段)。予算が倍なら体数もほぼ倍・40 で切られない
            BaseMemoryTable.Wave w6 = bt.waveFor(6.0D);
            int n300 = BaseRaids.sizeByBudget(level, w6, 30000.0D).total(), n600 = BaseRaids.sizeByBudget(level, w6, 60000.0D).total();
            helper.assertTrue(n300 > 0 && Math.abs(n600 - 2 * n300) <= 2, "体数が予算に比例しない: 300 → " + n300 + "・600 → " + n600);
            helper.assertTrue(BaseRaids.sizeByBudget(level, w6, 300000.0D).total() > a.maxWave, "体数が前の上限 40 で切られた");
            int alive = com.yayuy.overgrownvegetation.director.LargeRaids.maxAlive();
            helper.assertTrue(BaseRaids.capped(BaseRaids.sizeByBudget(level, w6, 1.0E8D), alive).total() == alive, "同時の上限で切られない");
            director.setBudget(level, 40000.0D);
            BaseRaids.Result late = BaseRaids.sendWave(level, b, 6.0D, level.getRandom());
            int want = BaseRaids.sizeByBudget(level, w6, 40000.0D * com.yayuy.overgrownvegetation.director.ForestEconomy.table().waveBudgetShare).total();
            helper.assertTrue(late.total() >= want - 4 && late.total() <= want + 4 && late.g3plus() > 0, "6 日目が予算どおり(約 " + want + " 体・G3 入り)でない: " + late.summary());
            clear(helper);
            // 予算が少ない: 安い段階 0 から(進化・G3 は払えない)
            director.setBudget(level, a.stageZeroCost * 5.0D + 0.5D);
            BaseRaids.Result poor = BaseRaids.sendWave(level, b, 6.0D, level.getRandom());
            // 11-20 夜: 体数は予算で決まる(5.5 × 0.75 ÷ 平均の値段 = 0 体)。少ない予算で、高い進化体・G3 を出さない
            helper.assertTrue(poor.total() <= BaseRaids.sizeByBudget(level, bt.waveFor(6.0D), 5.5D).total() && poor.evolved() == 0 && poor.g3plus() == 0,
                    "予算が少ないのに多く・高いものを送った: " + poor.summary());
            clear(helper);
            // 貯めた襲撃: 進化体と G3 の枠が 1.5 倍、計は上限のまま
            director.setBudget(level, 500000.0D);
            BaseRaids.Result hoard = BaseRaids.sendWave(level, b, 2.0D, level.getRandom(), true);
            BaseMemoryTable.Wave base2 = bt.waveFor(2.0D);
            // 11-20 夜: 体数は予算で決まり、同時の上限(中の maxAlive)で切られる。進化体・G3 の割合は 1.5 倍のまま
            helper.assertTrue(hoard.hoard() && hoard.total() > 0 && hoard.total() <= com.yayuy.overgrownvegetation.director.HoardRaids.maxAlive()
                            && hoard.wantG3plus() * base2.g2() >= base2.g3plus() * hoard.wantG2() - hoard.wantG2(),
                    "貯めた襲撃の枠が違う: " + hoard.summary() + "(表 " + base2 + ")");
            clear(helper);
            // 貯めた襲撃の継ぎ足し: 同時 40 まで、倒された分を継ぎ足して合計 80 まで(11-16 ユーザー)
            com.yayuy.overgrownvegetation.director.HoardRaids.clearForTests();
            director.setBudget(level, 10000000.0D);
            int firstHoard = com.yayuy.overgrownvegetation.director.HoardRaids.begin(level, b, 6.0D, level.getRandom());
            helper.assertTrue(firstHoard > 0 && firstHoard <= a.hoardMaxAlive, "貯めた襲撃の 1 回目が 1〜40 体でない: " + firstHoard);
            var raid = com.yayuy.overgrownvegetation.director.HoardRaids.raid(b.id());
            helper.assertTrue(raid != null, "貯めた襲撃が続いていない");
            // 倒されないうちは継ぎ足さない(同時 40 のまま)
            com.yayuy.overgrownvegetation.director.HoardRaids.step(level, 6.01D, level.getRandom(), List.of(b));
            helper.assertTrue(com.yayuy.overgrownvegetation.director.HoardRaids.alive(level, b) <= a.hoardMaxAlive, "同時に 40 体を超えた");
            // 全部倒されたことにして継ぎ足しを繰り返す → 11-20 夜から合計の上限は無く、予算が尽きたら終わる(前は合計 80)
            director.setBudget(level, 150000.0D);
            for (int i = 0; i < 30 && com.yayuy.overgrownvegetation.director.HoardRaids.raid(b.id()) != null; i++) {
                for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(spot).inflate(16.0D), m -> m.getTags().contains(com.yayuy.overgrownvegetation.director.HoardRaids.TAG))) m.discard();
                com.yayuy.overgrownvegetation.director.HoardRaids.step(level, 6.02D + i * 0.01D, level.getRandom(), List.of(b));
            }
            helper.assertTrue(com.yayuy.overgrownvegetation.director.HoardRaids.raid(b.id()) == null
                            && com.yayuy.overgrownvegetation.director.HoardRaids.lastTotal > 80
                            && (com.yayuy.overgrownvegetation.director.HoardRaids.lastEnded.contains("払えなかった")
                            || com.yayuy.overgrownvegetation.director.HoardRaids.lastEnded.contains("予算から決めた数")),
                    "予算が尽きるまで継ぎ足さない: 合計 " + com.yayuy.overgrownvegetation.director.HoardRaids.lastTotal + "・理由 " + com.yayuy.overgrownvegetation.director.HoardRaids.lastEnded);
            // 割合を保って縮める
            BaseMemoryTable.Wave s = com.yayuy.overgrownvegetation.director.HoardRaids.shrink(new BaseMemoryTable.Wave(0.0D, 2, 8, 13, 17), 10);
            helper.assertTrue(s.stageZero() + s.developed() + s.g2() + s.g3plus() == 10 && s.g3plus() >= 4 && s.g2() >= 3, "縮め方が違う: " + s);
            clear(helper);
            helper.succeed();
        } finally {
            com.yayuy.overgrownvegetation.director.HoardRaids.clearForTests();
            DirectorHooks.setSpotForTests(null);
            com.yayuy.overgrownvegetation.director.ForestEconomy.setIncomeForTests(16500.0D);
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            director.setBudget(level, budgetBefore);
            clear(helper);
        }
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
