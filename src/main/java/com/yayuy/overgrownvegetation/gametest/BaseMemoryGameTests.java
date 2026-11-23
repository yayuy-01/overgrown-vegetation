package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseWatch;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 司令塔 第 2 段 区切り 1: 拠点の記憶(覚える・まとめる・減る・忘れる・保存)。
 * 世界に 1 つの記憶は使わない({@link BaseMemory#createForTests} の記憶に、日付を自分で渡す = 隣のテスト場とぶつからない)。
 * 期待値は設計値({@code director.json} の {@code base})から出す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseMemoryGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterBaseMemoryGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(BaseMemoryGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "base_memory";
    private static final long A = ChunkPos.asLong(100, 100);
    private static final long A_NEXT = ChunkPos.asLong(101, 100);
    private static final long FAR = ChunkPos.asLong(140, 100);

    private BaseMemoryGameTests() {
    }

    private static BaseMemoryTable t() {
        return DesignData.director().base;
    }

    /** 点が覚える点を超えたら拠点を 1 つ覚える。届かなければ覚えない。離れた所は別の拠点。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void remembersOnlyAboveThreshold(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(A, t().rememberScore * 0.5D, 0.0D);
        m.update(0.0D);
        helper.assertTrue(m.bases().isEmpty(), "点が足りないのに覚えた");
        m.addPresence(A, t().rememberScore * 0.6D, 0.1D);
        m.update(0.1D);
        helper.assertTrue(m.bases().size() == 1, "点が足りたのに覚えていない: " + m.bases().size());
        helper.assertTrue(m.bases().get(0).stage() == BaseMemory.Stage.REMEMBERED, "最初の段が「覚えた」でない");
        m.addPresence(FAR, t().rememberScore * 2.0D, 0.1D);
        m.update(0.1D);
        helper.assertTrue(m.bases().size() == 2, "遠い升が別の拠点になっていない: " + m.bases().size());
        helper.succeed();
    }

    /** 隣の升の置き物は同じ拠点に数える(置き物 1 つ = structureHours 時間)。真ん中は点の重い方へ寄る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void neighbourChunkStructuresJoinTheBase(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(A, t().rememberScore * 0.7D, 0.0D);
        m.setStructures(A_NEXT, 4, 0.0D);
        m.update(0.0D);
        double expected = t().rememberScore * 0.7D + t().structureHours * Math.min(4, t().structureCap);
        helper.assertTrue(m.bases().size() == 1, "隣の升が同じ拠点にまとまっていない: " + m.bases().size());
        BaseMemory.Base b = m.bases().get(0);
        helper.assertTrue(Math.abs(b.score() - expected) < 1.0E-6D, "点が違う: " + b.score() + "(期待 " + expected + ")");
        helper.assertTrue(b.cells() == 2 && b.structures() == 4, "升・置き物の数が違う");
        helper.assertTrue(b.centerX() > 100 * 16 + 8 && b.centerX() < 101 * 16 + 8, "真ん中が 2 つの升の間に無い: " + b.centerX());
        helper.succeed();
    }

    /** 覚えただけの拠点: 1 日までは減らず、そこから減って、2 日で忘れる(11-14 ユーザー決定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rememberedBaseFadesAndIsForgotten(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        double full = t().rememberScore * 2.0D;
        m.addPresence(A, full, 0.0D);
        m.update(0.0D);
        m.update(t().decayStartDays);
        helper.assertTrue(m.bases().size() == 1 && Math.abs(m.bases().get(0).score() - full) < 1.0E-6D, "減り始める前に減った");
        double mid = (t().decayStartDays + t().forgetRememberedDays) / 2.0D;
        m.update(mid);
        helper.assertTrue(m.bases().size() == 1 && Math.abs(m.bases().get(0).score() - full * 0.5D) < 1.0E-6D,
                "途中で半分になっていない: " + (m.bases().isEmpty() ? "忘れた" : m.bases().get(0).score()));
        m.update(t().forgetRememberedDays + 0.01D);
        helper.assertTrue(m.bases().isEmpty() && m.cellCount() == 0 && m.forgottenCount() == 1, "2 日で忘れていない");
        helper.succeed();
    }

    /** 認識した拠点は 2 日では忘れず、5 日で忘れる(11-14 ユーザー決定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void recognizedBaseLastsFiveDays(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(A, t().rememberScore * 2.0D, 0.0D);
        m.update(0.0D);
        m.setStageForTests(m.bases().get(0), BaseMemory.Stage.RECOGNIZED, 0.0D);
        m.update(0.0D);
        m.update(t().forgetRememberedDays + 0.5D);
        helper.assertTrue(m.bases().size() == 1, "認識した拠点が覚えただけの日数で消えた");
        helper.assertTrue(m.bases().get(0).score() < t().rememberScore * 2.0D, "1 日を過ぎたのに減っていない");
        m.update(t().forgetRecognizedDays - 0.05D);
        helper.assertTrue(m.bases().size() == 1, "5 日より前に忘れた");
        m.update(t().forgetRecognizedDays + 0.01D);
        helper.assertTrue(m.bases().isEmpty(), "5 日で忘れていない");
        helper.succeed();
    }

    /** 寝室の升にいれば、隣の升(チェストだけ)も「いた」ことになり、減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void absenceIsCountedPerBaseNotPerChunk(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(A, t().rememberScore, 0.0D);
        m.setStructures(A_NEXT, 5, 0.0D);
        m.update(0.0D);
        for (double day = 0.25D; day <= 4.0D; day += 0.25D) {
            m.addPresence(A, 0.1D, day);
            m.update(day);
        }
        helper.assertTrue(m.bases().size() == 1 && m.bases().get(0).cells() == 2, "隣の升が忘れられた");
        helper.assertTrue(m.bases().get(0).structures() == 5, "置き物が消えた");
        helper.succeed();
    }

    /** 減った分は戻らない: 1.5 日いなかった後に戻ると、減った所から足し直す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void decayIsNotUndoneByReturning(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        double full = t().rememberScore * 2.0D;
        m.addPresence(A, full, 0.0D);
        m.update(0.0D);
        double back = (t().decayStartDays + t().forgetRememberedDays) / 2.0D;
        m.addPresence(A, 0.0D, back);
        m.update(back);
        helper.assertTrue(m.bases().size() == 1 && Math.abs(m.bases().get(0).score() - full * 0.5D) < 1.0E-6D,
                "戻ったら減りが消えた: " + (m.bases().isEmpty() ? "忘れた" : m.bases().get(0).score()));
        helper.succeed();
    }

    /** 保存して読み直しても、拠点と段と升が残る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void memorySurvivesSaveAndLoad(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        m.addPresence(A, t().rememberScore * 2.0D, 0.0D);
        m.setStructures(A_NEXT, 3, 0.0D);
        m.update(0.0D);
        m.setStage(m.bases().get(0), BaseMemory.Stage.SCOUTED, 0.5D, helper.getLevel().getRandom());
        BaseMemory r = m.roundTripForTests();
        r.update(0.6D);
        helper.assertTrue(r.bases().size() == 1, "読み直したら拠点が消えた");
        BaseMemory.Base b = r.bases().get(0);
        helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED && Math.abs(b.stageSince() - 0.5D) < 1.0E-9D, "段が残っていない");
        helper.assertTrue(b.cells() == 2 && b.structures() == 3 && b.id() == m.bases().get(0).id(), "升・置き物・番号が残っていない");
        helper.succeed();
    }

    /** 置き物の数え方: ベッド(2 マスで 1 つ)・チェスト・作業台・かまどを数え、タグに無い物(石)は数えない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void countsTaggedStructuresInChunk(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(3, 2, 3));
        int before = BaseWatch.countStructures(helper.getLevel(), feet, t().scanHalfHeight);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(new BlockPos(1, 2, 0), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(4, 2, 1), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(5, 2, 1), Blocks.FURNACE);
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.STONE);
        // テスト場が升の境をまたぐと、足元の升に入らない物がある。足元の升に入った物だけを期待する
        int expected = 0;
        for (BlockPos p : new BlockPos[]{new BlockPos(1, 2, 1), new BlockPos(3, 2, 1), new BlockPos(4, 2, 1), new BlockPos(5, 2, 1)}) {
            BlockPos a = helper.absolutePos(p);
            if (a.getX() >> 4 == feet.getX() >> 4 && a.getZ() >> 4 == feet.getZ() >> 4) expected++;
        }
        int after = BaseWatch.countStructures(helper.getLevel(), feet, t().scanHalfHeight);
        helper.assertTrue(after - before == expected, "数えた置き物が違う: " + (after - before) + "(期待 " + expected + ")");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 区切り 2: 偵察

    /** 覚えた拠点を 1 つ作る(升 A)。 */
    private static BaseMemory.Base remembered(BaseMemory m, long chunk, double day) {
        m.addPresence(chunk, t().rememberScore * 2.0D, day);
        m.update(day);
        return m.bases().get(0);
    }

    /** 偵察が眺め終えたら偵察済み → 1〜2 日(拠点ごとの値)で認識。それより前は偵察済みのまま。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void scoutedBaseIsRecognizedAfterOneToTwoDays(GameTestHelper helper) {
        BaseMemory m = BaseMemory.createForTests();
        BaseMemory.Base b = remembered(m, A, 0.0D);
        helper.assertTrue(!BaseMemory.wantsScout(b, 0.0D) && BaseMemory.wantsScout(b, t().scoutFirstDays), "覚えてからの偵察の間が違う");
        // 偵察の間(11-14 ユーザー): ふだん 0.4〜0.6 日・F4 の間は 1〜2 日。出した時から数え、眺め終えても次の時は変えない
        net.minecraft.util.RandomSource ir = net.minecraft.util.RandomSource.create(5L);
        for (int i = 0; i < 50; i++) {
            double n = BaseMemory.scoutInterval(ir, false), q = BaseMemory.scoutInterval(ir, true);
            helper.assertTrue(n >= t().scoutIntervalMinDays && n <= t().scoutIntervalMaxDays, "ふだんの偵察の間が幅の外: " + n);
            helper.assertTrue(q >= t().scoutQuietIntervalMinDays && q <= t().scoutQuietIntervalMaxDays && q > n, "F4 の偵察の間が幅の外: " + q);
        }
        m.scoutSent(b, 0.2D, ir, true);
        double next = b.nextScoutDay();
        helper.assertTrue(next >= 0.2D + t().scoutQuietIntervalMinDays, "F4 なのに次の偵察が早い: " + next);
        m.onScouted(b.id(), 0.2D, helper.getLevel().getRandom());
        helper.assertTrue(b.nextScoutDay() == next, "眺め終えたら次の偵察の時が変わった");
        helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED, "眺め終えても偵察済みにならない");
        double after = b.recognizeAfter();
        helper.assertTrue(after >= t().recognizeMinDays && after <= t().recognizeMaxDays, "認識までの日数が 1〜2 日の外: " + after);
        // いる間は減らないよう、毎回少し足しながら日を進める
        double day = 0.2D;
        for (; day < 0.2D + after - 0.05D; day += 0.1D) {
            m.addPresence(A, 0.1D, day);
            m.update(day);
        }
        helper.assertTrue(b.stage() == BaseMemory.Stage.SCOUTED, "決めた日数より前に認識した");
        m.addPresence(A, 0.1D, 0.2D + after + 0.01D);
        m.update(0.2D + after + 0.01D);
        helper.assertTrue(m.bases().get(0).stage() == BaseMemory.Stage.RECOGNIZED, "決めた日数で認識しない");
        helper.succeed();
    }

    /** 偵察を倒すと、逆に認識が進む: 眺め終えたのと同じく偵察済みになり、さらに killAdvanceDays 日早まる。2 体目でもっと早まる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void killingScoutsAdvancesRecognition(GameTestHelper helper) {
        BaseMemory left = BaseMemory.createForTests();
        BaseMemory killed = BaseMemory.createForTests();
        BaseMemory.Base l = remembered(left, A, 0.0D);
        BaseMemory.Base k = remembered(killed, A, 0.0D);
        net.minecraft.util.RandomSource r1 = net.minecraft.util.RandomSource.create(7L), r2 = net.minecraft.util.RandomSource.create(7L);
        left.onScouted(l.id(), 0.2D, r1);
        killed.onScoutKilled(k.id(), 0.2D, r2);
        helper.assertTrue(k.stage() == BaseMemory.Stage.SCOUTED && k.scoutKills() == 1, "倒しても偵察済みにならない");
        double diff = l.daysToRecognize(0.2D) - k.daysToRecognize(0.2D);
        helper.assertTrue(Math.abs(diff - t().killAdvanceDays) < 1.0E-9D, "倒したときの早まりが違う: " + diff);
        killed.onScoutKilled(k.id(), 0.3D, r2);
        double diff2 = l.daysToRecognize(0.3D) - k.daysToRecognize(0.3D);
        helper.assertTrue(Math.abs(diff2 - 2.0D * t().killAdvanceDays) < 1.0E-9D, "2 体目で早まっていない: " + diff2);
        helper.succeed();
    }

    /** 偵察の使い: 行く → 着いたら眺める(見に来ない)→ 眺め終えたら去る → 離れたら終わり。保存しても段が残る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void scoutErrandGoesWatchesAndLeaves(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster z =
                helper.spawn(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new net.minecraft.world.phys.Vec3(3.5, 2.0, 3.5));
        z.goalSelector.removeAllGoals(g -> true);
        z.targetSelector.removeAllGoals(g -> true);
        BaseMemory m = BaseMemory.createForTests();
        BaseMemory.Base b = remembered(m, ChunkPos.asLong(z.getBlockX() >> 4, z.getBlockZ() >> 4), 0.0D);
        helper.assertTrue(com.yayuy.overgrownvegetation.director.BaseWatch.canScout(z), "出せるはずの段階 0 が出せない");
        z.fate().scout().begin(b);
        helper.assertTrue(!com.yayuy.overgrownvegetation.director.BaseWatch.canScout(z), "使いの途中なのにまた出せる");
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        z.saveWithoutId(tag);
        var copy = com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType().create(helper.getLevel());
        copy.load(tag);
        helper.assertTrue(copy.fate().scout().phase() == com.yayuy.overgrownvegetation.director.G1Scout.Phase.GO
                && copy.fate().scout().baseId() == b.id(), "使いが保存されていない");
        helper.runAfterDelay(5, () -> {
            var s = z.fate().scout();
            helper.assertTrue(s.phase() == com.yayuy.overgrownvegetation.director.G1Scout.Phase.WATCH, "拠点のそばで眺めていない: " + s.phase());
            helper.assertTrue(z.fate().nextVisit() > helper.getLevel().getGameTime(), "偵察の間に見に来る");
            s.setTicksForTests(t().scoutWatchMaxTicks);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(s.phase() == com.yayuy.overgrownvegetation.director.G1Scout.Phase.LEAVE, "眺め終えても去らない: " + s.phase());
                s.setTicksForTests(t().scoutLeaveTicks);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(!s.active(), "去った後も使いが終わらない");
                    z.discard();
                    helper.succeed();
                });
            });
        });
    }

    // ------------------------------------------------------------------ 区切り 3: 認識の後の送り込み

    /** 段: 最初は進化体・G3〜を送らない(段階 0 と G2 だけ)。日がたつほど、強い顔ぶれの数は減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void wavesGrowStrongerOverDays(GameTestHelper helper) {
        BaseMemoryTable.Wave first = t().waveFor(0.0D);
        // 11-16 作り直し(F5 の襲撃): 最初の段は G1 と G2 が中心(進化体は少し、G3 は無し)
        helper.assertTrue(first.g3plus() == 0 && first.developed() <= first.stageZero(), "最初の段に G3 が入っている・進化体が段階 0 より多い");
        BaseMemoryTable.Wave prev = first;
        for (double d = 0.5D; d <= 10.0D; d += 0.5D) {
            BaseMemoryTable.Wave w = t().waveFor(d);
            helper.assertTrue(w.developed() >= prev.developed() && w.g2() >= prev.g2() && w.g3plus() >= prev.g3plus(),
                    d + " 日目で顔ぶれが弱くなった");
            prev = w;
        }
        helper.assertTrue(prev.developed() > first.developed(), "日がたっても進化体が混ざらない");
        helper.succeed();
    }

    /** 認識した拠点に近い個体ほど進化を選びやすい(D-8)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void nearRecognizedBaseEvolvesMore(GameTestHelper helper) {
        var dt = DesignData.director();
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(11L);
        int far = 0, near = 0;
        for (int i = 0; i < 400; i++) {
            if (ForestDirector.score(dt, new ForestDirector.Inputs(true, false, false, dt.costEvolve * 10.0D, 1.0E9D, 0.0D, 0.0D, 0.0D), random)
                    .route() == ForestDirector.Route.EVOLVE) far++;
            if (ForestDirector.score(dt, new ForestDirector.Inputs(true, false, false, dt.costEvolve * 10.0D, 1.0E9D, 0.0D, 0.0D, 1.0D), random)
                    .route() == ForestDirector.Route.EVOLVE) near++;
        }
        helper.assertTrue(near > far, "拠点のそばで進化が増えていない: そば " + near + " / 遠く " + far);
        helper.succeed();
    }

    /** 根付きの向き(D-16): 拠点が遠ければ森から離れつつ拠点へ寄る。近すぎれば拠点から離れる。拠点が無ければ森から離れるだけ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rootedLeansTowardBaseButNotInside(GameTestHelper helper) {
        Vec3 away = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 lean = G1Fate.blendTowardBase(away, Vec3.ZERO, new Vec3(0.0D, 0.0D, 100.0D), t());
        helper.assertTrue(lean.x > 0.0D && lean.z > 0.0D, "遠い拠点へ寄っていない: " + lean);
        Vec3 tooClose = G1Fate.blendTowardBase(away, Vec3.ZERO, new Vec3(0.0D, 0.0D, t().rootBaseMinBlocks * 0.5D), t());
        helper.assertTrue(tooClose.z < 0.0D, "近すぎる拠点から離れていない: " + tooClose);
        helper.assertTrue(G1Fate.blendTowardBase(away, Vec3.ZERO, null, t()) == away, "拠点が無いのに向きが変わった");
        helper.succeed();
    }

    /** 拠点へ向かう途中で進化した個体は、1 段目になっても向かう。保存しても使いが残る。着いたら終わる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void marchSurvivesEvolutionAndEndsOnArrival(GameTestHelper helper) {
        AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
        z.goalSelector.removeAllGoals(g -> true);
        z.targetSelector.removeAllGoals(g -> true);
        BaseMemory m = BaseMemory.createForTests();
        BaseMemory.Base farBase = remembered(m, ChunkPos.asLong((z.getBlockX() >> 4) + 20, z.getBlockZ() >> 4), 0.0D);
        z.march().begin(farBase);
        CompoundTag tag = new CompoundTag();
        z.saveWithoutId(tag);
        AbstractParasitizedMonster copy = G1Species.ZOMBIE.entityType().create(helper.getLevel());
        copy.load(tag);
        helper.assertTrue(copy.march().active() && copy.march().baseId() == farBase.id(), "使いが保存されていない");
        z.fate().beginEvolve();
        helper.runAfterDelay(DesignData.director().evolveOmenTicks + 10, () -> {
            List<DevelopedParasitized> dev = helper.getLevel().getEntitiesOfClass(DevelopedParasitized.class, GtCompat.bounds(helper));
            helper.assertTrue(dev.size() == 1, "進化していない: " + dev.size());
            DevelopedParasitized d = dev.get(0);
            helper.assertTrue(d.march().active() && d.march().baseId() == farBase.id(), "進化したら拠点へ向かわなくなった");
            // 歩いても着かない: marchTimeoutTicks で終わる
            d.march().setTicksForTests(t().marchTimeoutTicks);
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(!d.march().active(), "時間切れでも使いが終わらない");
                d.discard();
                helper.succeed();
            });
        });
    }
}
