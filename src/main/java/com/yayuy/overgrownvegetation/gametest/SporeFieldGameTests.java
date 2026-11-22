package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 胞子の場と風(2026-10-23 第 2 段 A〜C)。場と風の式は素の Java なのでほとんどはワールドを使わずに確かめ、
 * ゲームへの配線(追いつき・台帳から場へ)は場を 1 回分だけ回して見る。発芽はテストの世界の台帳へ渡さない
 * ({@code SporeFieldServer#catchUpForTests})。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeFieldGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SporeFieldGameTests.class);
        }

        private Registration() {
        }
    }

    private SporeFieldGameTests() {
    }

    /** 風は入力だけで決まる(呼ぶ順番・回数に依らない)。サーバーとクライアントが同じ向きを得る前提。 */
    @GameTest(template = "gametest_empty7")
    public static void windIsDeterministic(GameTestHelper helper) {
        long seed = 987654321L;
        PrevailingWind.Weather w = new PrevailingWind.Weather(true, false, 50_000L, PrevailingWind.NONE, 10_000L, 30_000L);
        PrevailingWind.Sample a = new PrevailingWind.Sample(), b = new PrevailingWind.Sample();
        long[] times = {0L, 61_234L, 200_000L, 55_000L, 61_234L};
        double[] first = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            PrevailingWind.sample(seed, times[i], w, a);
            first[i] = a.angleDeg() * 1000 + a.strength;
        }
        for (int i = times.length - 1; i >= 0; i--) {
            PrevailingWind.sample(seed, times[i], w, b);
            if (b.angleDeg() * 1000 + b.strength != first[i]) {
                helper.fail("同じ入力で答えが違う: t=" + times[i]);
                return;
            }
        }
        // 雷雨は嵐の強さ
        PrevailingWind.sample(seed, 60_000L, new PrevailingWind.Weather(true, true, 50_000L, PrevailingWind.NONE, 10_000L, 30_000L), a);
        if (!a.storm || a.strength != PrevailingWind.STORM) {
            helper.fail("雷雨が嵐の強さになっていない: " + a.strength);
            return;
        }
        // シードが違えば土台の向きは違う(ほとんどの組で)
        int differ = 0;
        for (long s = 1; s <= 20; s++) {
            PrevailingWind.sample(s, 3000L, PrevailingWind.Weather.CLEAR, a);
            PrevailingWind.sample(s + 100, 3000L, PrevailingWind.Weather.CLEAR, b);
            if (Math.abs(Math.IEEEremainder(a.angleDeg() - b.angleDeg(), 360)) > 5) differ++;
        }
        if (differ < 15) {
            helper.fail("シードで向きが変わらない: " + differ + "/20");
            return;
        }
        helper.succeed();
    }

    /** 一定の東風の下で、汚染の塊の風下(東)が風上(西)より多く発芽する。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void fieldSproutsDownwind(GameTestHelper helper) {
        SporeField.Params p = SporeFieldTable.gameDefaults();
        SporeField f = new SporeField(42L, p);
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) f.setCorrupted(x, z, -10L * PrevailingWind.DAY);
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.dirZ = 0;
        wind.strength = 1;
        for (int k = 1; k <= 2 * PrevailingWind.DAY / p.stepTicks; k++) {
            long t = (long) k * p.stepTicks;
            f.step(new SporeField.Conditions(t, false, false, false), wind);
        }
        int east = 0, west = 0;
        for (int z = -10; z < 15; z++) {
            for (int x = 5; x < 40; x++) if (f.isCorrupted(x, z)) east++;
            for (int x = -35; x < 0; x++) if (f.isCorrupted(x, z)) west++;
        }
        if (east == 0 || east <= west) {
            helper.fail("風下の方が発芽していない: 東 " + east + " / 西 " + west);
            return;
        }
        helper.succeed();
    }

    /** 除去装置は 1 回に上限まで抜く(範囲を 0 にはしない)。少なければ全部抜く。 */
    @GameTest(template = "gametest_empty7")
    public static void purifierExtractIsCapped(GameTestHelper helper) {
        SporeField f = new SporeField(1L, SporeFieldTable.gameDefaults());
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) f.emitPoint(x, z, 1.0);
        double before = 0;
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) before += f.air(x, z);
        double taken = f.extractAround(0, 0, 3.0, 0.5);
        double after = 0;
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) after += f.air(x, z);
        if (Math.abs(taken - 0.5) > 1e-6 || Math.abs(before - after - 0.5) > 1e-3) {
            helper.fail("上限で抜いていない: 抜いた " + taken + "・減った " + (before - after));
            return;
        }
        SporeField g = new SporeField(1L, SporeFieldTable.gameDefaults());
        g.emitPoint(1, 1, 0.1);
        double all = g.extractAround(0, 0, 3.0, 0.5);
        if (Math.abs(all - 0.1) > 1e-6 || g.air(1, 1) > 1e-9) {
            helper.fail("少ないのに全部抜いていない: " + all);
            return;
        }
        helper.succeed();
    }

    /** 暦が飛んだら(睡眠)、1 回の計算で maxStepsPerRun 回まで進め、残りは次の回へ。 */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world")
    public static void fieldCatchesUpAfterSleep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SporeFieldTable table = DesignData.sporeField();
        long behind = (long) table.updateTicks() * (table.maxStepsPerRun() * 2L + 3L);
        int steps = SporeFieldServer.catchUpForTests(level, behind);
        if (steps != table.maxStepsPerRun()) {
            helper.fail("1 回目の更新の回数: " + steps + "(期待 " + table.maxStepsPerRun() + ")");
            return;
        }
        long clock = PhaseData.get(level).clock(level.getServer());
        long left = clock - SporeFieldServer.fieldClockForTests();
        long expect = behind - (long) steps * table.updateTicks();
        if (Math.abs(left - expect) > table.updateTicks()) {
            helper.fail("残りの遅れ: " + left + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }

    /** 胞子濃度 = 場の浮遊 + 林床 ÷ fullAt × 100(第 2 段 D)。写しのある所を読む。 */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world")
    public static void concentrationReadsTheField(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = -1_150_000, cz = 1_150_000; // テスト場から遠いチャンク
        SporeFieldServer.watchForTests(cx, cz);
        SporeFieldServer.withFieldForTests(f -> {
            f.emitPoint(cx, cz, 0.4);
            return null;
        });
        SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
        SporeFieldServer.Cell cell = SporeFieldServer.cell(cx, cz);
        if (cell == null) {
            helper.fail("写しが無い");
            return;
        }
        double expect = cell.conc() / DesignData.sporeField().fullAt() * 100.0D;
        double got = com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, new ChunkPos(cx, cz));
        SporeFieldServer.withFieldForTests(f -> f.extractAround(cx, cz, 2.0, Double.POSITIVE_INFINITY)); // 片付け
        if (expect <= 0.0D || Math.abs(got - expect) > 0.5D) {
            helper.fail("濃度が場の読みと合わない: " + got + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }


    /** 動いている除去装置は範囲の浮遊・林床・降り積もりを空にする(上限なし。10-23 追記 11)。 */
    @GameTest(template = "gametest_empty7")
    public static void purifierHoldsItsRange(GameTestHelper helper) {
        SporeField f = new SporeField(3L, SporeFieldTable.gameDefaults());
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) f.emitPoint(x, z, 5.0);
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.strength = 2;
        for (int k = 1; k <= 20; k++) f.step(new SporeField.Conditions(k * 100L, true, false, false), wind); // 雨で降り積もらせる
        f.extractAround(0, 0, 3.0, Double.POSITIVE_INFINITY);
        f.clearDepositAround(0, 0, 3.0);
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                if (f.air(x, z) > 1e-9 || f.low(x, z) > 1e-9 || f.deposited(x, z) > 1e-9) {
                    helper.fail("範囲に胞子が残っている: (" + x + ", " + z + ")");
                    return;
                }
            }
        helper.succeed();
    }

    /** 台帳に載ったチャンク(どの経路でも)は、次の計算で場でも汚染済みになり、外れたら取り消される。 */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world")
    public static void ledgerChunkEntersField(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long key = ChunkPos.asLong(1_200_000, -1_200_000); // テスト場から遠いチャンク
        SporeFieldServer.onLedgerAdded(key, 0L);
        SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
        Boolean in = SporeFieldServer.withFieldForTests(f -> f.isCorrupted(1_200_000, -1_200_000));
        SporeFieldServer.onLedgerRemoved(key);
        SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
        Boolean out = SporeFieldServer.withFieldForTests(f -> f.isCorrupted(1_200_000, -1_200_000));
        if (in == null) {
            helper.fail("場がまだ無い");
            return;
        }
        if (!in || out) {
            helper.fail("台帳から場へ: 入った " + in + "・外した後 " + out);
            return;
        }
        helper.succeed();
    }

    /**
     * シャベルで粉を払う(第 2 段の追加、10-23【決定】): 払うと升の印が付き、降り積もりが升の分だけ減り、「胞子」が出る。
     * 同じ升は 2 度払えない。降り積もりが払う直前の値まで戻れば印は消える。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world")
    public static void sweepReducesDustAndDropsSpores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3));
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        SporeFieldTable t = DesignData.sporeField();
        double th = t.field().germThreshold;
        int ticks = t.updateTicks();
        SporeFieldServer.watchForTests(cx, cz);
        SporeFieldServer.withFieldForTests(f -> {
            f.depositPoint(cx, cz, th * 2.0D);
            return null;
        });
        SporeFieldServer.catchUpForTests(level, ticks);
        SporeFieldServer.Cell c0 = SporeFieldServer.cell(cx, cz);
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkAt(pos);
        Runnable cleanup = () -> {
            SporeFieldServer.withFieldForTests(f -> {
                f.clearDepositAround(cx, cz, 0.5D);
                return null;
            });
            chunk.removeData(com.yayuy.overgrownvegetation.registry.ModAttachments.SWEPT_DUST);
            chunk.removeData(com.yayuy.overgrownvegetation.registry.ModAttachments.SWEPT_DUST_REFILL);
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(3))
                    .forEach(net.minecraft.world.entity.Entity::discard);
        };
        if (c0 == null || c0.stage() > 0 || c0.dep() < th) {
            cleanup.run();
            helper.fail("粉の濃い升を作れない: " + c0);
            return;
        }
        boolean swept = com.yayuy.overgrownvegetation.spore.field.SporeSweeping.sweep(level, pos, null,
                net.minecraft.world.item.ItemStack.EMPTY, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        int bit = 1 << com.yayuy.overgrownvegetation.spore.field.SporeSweeping.square(pos);
        int mask = chunk.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.SWEPT_DUST);
        int spores = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(3),
                        e -> e.getItem().is(com.yayuy.overgrownvegetation.registry.ModItems.SPORE.get()))
                .stream().mapToInt(e -> e.getItem().getCount()).sum();
        boolean again = com.yayuy.overgrownvegetation.spore.field.SporeSweeping.sweep(level, pos, null,
                net.minecraft.world.item.ItemStack.EMPTY, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        SporeFieldServer.catchUpForTests(level, ticks);
        SporeFieldServer.Cell c1 = SporeFieldServer.cell(cx, cz);
        double before = c0.dep();
        // 戻す: 払う直前の値まで積もらせると印が消える
        SporeFieldServer.withFieldForTests(f -> {
            f.depositPoint(cx, cz, before);
            return null;
        });
        SporeFieldServer.catchUpForTests(level, ticks);
        SporeFieldServer.catchUpForTests(level, ticks); // 印を消すのは写しが新しくなった後の回
        int maskAfter = chunk.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.SWEPT_DUST);
        cleanup.run();
        if (!swept || (mask & bit) == 0) {
            helper.fail("払えない・印が付かない: swept=" + swept + " mask=" + Integer.toBinaryString(mask));
            return;
        }
        if (again) {
            helper.fail("同じ升を 2 度払えた");
            return;
        }
        if (spores < t.sweepDropMin() || spores > t.sweepDropMax()) {
            helper.fail("濃い升の胞子の数が違う: " + spores + "(" + t.sweepDropMin() + "〜" + t.sweepDropMax() + ")");
            return;
        }
        if (c1 == null || c1.dep() > before * (1.0D - t.sweepShare() * 0.5D)) {
            helper.fail("降り積もりが升の分だけ減らない: " + before + " → " + (c1 == null ? "null" : c1.dep()));
            return;
        }
        if (maskAfter != 0) {
            helper.fail("降り積もりが戻っても印が消えない: " + Integer.toBinaryString(maskAfter));
            return;
        }
        helper.succeed();
    }

    /**
     * 設定「読み込まれていない範囲への侵食」がオフ: 読み込まれていないチャンクの発芽は台帳に断られても取り消さず、降り積もりを残して
     * 読み込まれるまで待つ(その間は発芽を試み直さない)。読み込まれたら発芽が通って台帳に載る(2026-10-24)。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_wait", timeoutTicks = 400) // 場を何日も進めるので、ほかの場のテストと同時に走らせない
    public static void unloadedSproutWaitsForLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData data = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.get(server);
        java.util.Map<com.yayuy.overgrownvegetation.difficulty.DifficultySetting, Double> saved = data.overrides();
        int cx = 1_250_000, cz = -1_250_000;
        long key = ChunkPos.asLong(cx, cz);
        double th = DesignData.sporeField().field().germThreshold;
        int u = DesignData.sporeField().updateTicks();
        com.yayuy.overgrownvegetation.phase.PhaseData phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        Runnable cleanup = () -> {
            data.replaceAll(saved);
            SporeFieldServer.withFieldForTests(f -> {
                f.unsprout(cx, cz);
                f.clearDepositAround(cx, cz, 1.5D);
                f.extractAround(cx, cz, 1.5D, Double.POSITIVE_INFINITY);
                return null;
            });
            phase.forgetOvergrownChunk(new ChunkPos(cx, cz));
        };
        try {
            data.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.UNLOADED_SPREAD, 0.0D);
            if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                helper.fail("遠くのチャンクが読み込まれている");
                return;
            }
            SporeFieldServer.watchForTests(cx, cz);
            boolean waiting = false;
            for (int i = 0; i < 30 && !waiting; i++) {
                SporeFieldServer.withFieldForTests(f -> {
                    f.depositPoint(cx, cz, th * 3);
                    return null;
                });
                // 発芽の抽選は時刻で決まるので回ごとに違う時刻。断られた発芽は次の更新の頭で「待ち」になる
                SporeFieldServer.catchUpForTests(level, (long) u * 60L * (i + 1), key);
                SporeFieldServer.catchUpForTests(level, u, key);
                waiting = Boolean.TRUE.equals(SporeFieldServer.withFieldForTests(f -> f.isWaiting(cx, cz)));
            }
            if (!waiting) {
                helper.fail("読み込まれていない発芽が待ちにならない");
                return;
            }
            double dep = SporeFieldServer.withFieldForTests(f -> f.deposited(cx, cz));
            boolean corrupted = SporeFieldServer.withFieldForTests(f -> f.isCorrupted(cx, cz));
            if (corrupted || dep < th || phase.isOvergrownKey(key)) {
                helper.fail("待ちの升: 汚染 " + corrupted + "・降り積もり " + dep + "・台帳 " + phase.isOvergrownKey(key));
                return;
            }
            // 待ちの間は試み直さない(何回更新しても汚染にならず、待ちのまま)
            for (int i = 0; i < 5; i++) SporeFieldServer.catchUpForTests(level, (long) u * 60L * (i + 1), key);
            if (Boolean.TRUE.equals(SporeFieldServer.withFieldForTests(f -> f.isCorrupted(cx, cz)))
                    || !Boolean.TRUE.equals(SporeFieldServer.withFieldForTests(f -> f.isWaiting(cx, cz)))) {
                helper.fail("待ちの升が発芽を試み直した");
                return;
            }
            // 読み込む → 待ちが解けて発芽が通り、台帳に載る
            level.getChunk(cx, cz);
            SporeFieldServer.catchUpForTests(level, u, key);
            boolean sprouted = Boolean.TRUE.equals(SporeFieldServer.withFieldForTests(f -> f.isCorrupted(cx, cz)));
            if (!sprouted || !phase.isOvergrownKey(key)) {
                helper.fail("読み込んでも発芽が通らない: 場 " + sprouted + "・台帳 " + phase.isOvergrownKey(key)
                        + "・見張り " + SporeFieldServer.waitingForTests(key));
                return;
            }
            helper.succeed();
        } finally {
            cleanup.run();
        }
    }

    /**
     * 地割れの噴出(2026-10-24 追記 19): 圧の前線は風を無視して全方位へ広がり(1 回の更新で速さ × 5 秒ぶん)、通った升の浮遊が上がる。
     * 近い升ほど濃い(距離に反比例)。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_wait", timeoutTicks = 200)
    public static void riftFrontSpreadsAllWays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 1_260_000, cz = -1_260_000;
        int u = DesignData.sporeField().updateTicks();
        double perUpdate = DesignData.sporeField().riftFrontSpeed() * u / 20.0D;
        Runnable cleanup = () -> SporeFieldServer.withFieldForTests(f -> {
            f.fronts.clear();
            f.extractAround(cx, cz, perUpdate * 3, Double.POSITIVE_INFINITY);
            f.clearDepositAround(cx, cz, perUpdate * 3);
            return null;
        });
        try {
            SporeFieldServer.watchForTests(cx, cz);
            SporeFieldServer.onRiftErupt(cx, cz);
            SporeFieldServer.catchUpForTests(level, u);
            double r1 = SporeFieldServer.withFieldForTests(f -> f.fronts.isEmpty() ? -1.0D : f.fronts.get(0).radius);
            int near = (int) (Math.min(perUpdate, DesignData.sporeField().riftFrontRadius()) * 0.3D), far = (int) (Math.min(perUpdate, DesignData.sporeField().riftFrontRadius()) * 0.6D);
            double[] e = SporeFieldServer.withFieldForTests(f -> new double[]{f.air(cx + near, cz), f.air(cx - far, cz), f.air(cx, cz + far), f.air(cx, cz - far)});
            SporeFieldServer.catchUpForTests(level, u);
            double r2 = SporeFieldServer.withFieldForTests(f -> f.fronts.isEmpty() ? -1.0D : f.fronts.get(0).radius);
            // 半径 frontRadiusChunks で止まる(2026-10-24 ユーザー指定): その外へは配らない
            double maxR = DesignData.sporeField().riftFrontRadius();
            int out = (int) Math.ceil(maxR) + 2;
            double beyond = SporeFieldServer.withFieldForTests(f -> f.air(cx + out, cz));
            double expect1 = Math.min(perUpdate, maxR);
            if (Math.abs(r1 - expect1) > 0.5D && !(r1 < 0 && expect1 >= maxR) || 2 * perUpdate >= maxR && r2 >= 0) {
                helper.fail("前線の半径が速さどおりに広がらない・止まらない: " + r1 + " → " + r2 + "(1 回 " + perUpdate + "・止める " + maxR + ")");
                return;
            }
            if (!(e[1] > 0 && e[2] > 0 && e[3] > 0 && e[0] > e[1] && beyond == 0)) {
                helper.fail("前線が全方位へ配らない・距離で薄まらない: 近 " + e[0] + "・西 " + e[1] + "・南 " + e[2] + "・北 " + e[3] + "・2 回目の外 " + beyond);
                return;
            }
            helper.succeed();
        } finally {
            cleanup.run();
        }
    }

    /** 地割れの周りの下支え: 中心の近くは濃度の読みに +100%(既定)、半径の外は 0。 */
    @GameTest(template = "gametest_empty7", batch = "spore_field_wait")
    public static void riftFloorAddsConcentration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.core.BlockPos c = helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
        com.yayuy.overgrownvegetation.quake.EarthquakeData data = com.yayuy.overgrownvegetation.quake.EarthquakeData.get(level);
        SporeFieldTable t = DesignData.sporeField();
        double floor = t.riftFloorPercent();
        int edge = (int) Math.ceil(t.riftFloorRadius() * 16.0D) + 2;
        double before = com.yayuy.overgrownvegetation.spore.SporeConcentration.riftFloorPercent(level, c);
        data.addRiftForTests(c);
        try {
            double at = com.yayuy.overgrownvegetation.spore.SporeConcentration.riftFloorPercent(level, c);
            double outside = com.yayuy.overgrownvegetation.spore.SporeConcentration.riftFloorPercent(level, c.offset(edge, 0, 0));
            double mid = com.yayuy.overgrownvegetation.spore.SporeConcentration.riftFloorPercent(level, c.offset((int) (t.riftFloorRadius() * 16 * 0.75D), 0, 0));
            double reading = com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, c);
            if (Math.abs(at - floor) > 1e-6 || outside != before || !(mid > 0 && mid < floor) || reading < floor * 0.5D) {
                helper.fail("下支え: 中心 " + at + "・縁の手前 " + mid + "・外 " + outside + "・読み " + reading + "(期待 " + floor + ")");
                return;
            }
            helper.succeed();
        } finally {
            data.removeRiftForTests(c);
        }
    }

    /** 萌芽体の湧く点が林冠の葉の上にならない(2026-10-31 不具合): 床の上に空気 2 段と mod の葉 3 段の柱。葉の上の点は床の 1 つ上へ降りる。 */
    @GameTest(template = "gametest_empty7")
    public static void sproutPointDropsBelowCanopy(GameTestHelper helper) {
        net.minecraft.world.level.block.state.BlockState leaf = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        for (int y = 4; y <= 6; y++) helper.setBlock(new net.minecraft.core.BlockPos(3, y, 3), leaf);
        net.minecraft.core.BlockPos top = helper.absolutePos(new net.minecraft.core.BlockPos(3, 7, 3));
        net.minecraft.core.BlockPos ground = helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
        net.minecraft.core.BlockPos got = com.yayuy.overgrownvegetation.spore.field.SporeSprouts.belowCanopy(helper.getLevel(), top);
        if (!ground.equals(got)) {
            helper.fail("葉の上の点が床の 1 つ上へ降りない: " + got + "(期待 " + ground + ")");
            return;
        }
        net.minecraft.core.BlockPos open = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        if (!open.equals(com.yayuy.overgrownvegetation.spore.field.SporeSprouts.belowCanopy(helper.getLevel(), open))) {
            helper.fail("葉の無い列の点が動いた");
            return;
        }
        helper.succeed();
    }
}
