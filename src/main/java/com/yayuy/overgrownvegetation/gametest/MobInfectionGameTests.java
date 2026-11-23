package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobInfectionSpec;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.MobInfection;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * Mob の胞子感染と飛び火(2026-11-14 ユーザー決定、{@link MobInfection})。濃度を渡して判定する口({@link SporeExposureTracker#tickWith})を直に叩く。
 * 期待値は設計値({@code spore_field.json} の exposure・mobInfection)から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MobInfectionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterMobInfectionGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(MobInfectionGameTests.class);
        }

        private Registration() {
        }
    }

    private MobInfectionGameTests() {
    }

    private static final String BATCH = "mob_infection";

    private static MobInfectionSpec spec() {
        return DesignData.sporeField().mobInfection();
    }

    /** 感染するまでの判定の回数(1 回 = countStepTicks)。 */
    private static int stepsToInfect() {
        return (int) Math.ceil(spec().startSeconds() * 20.0D / spec().countStepTicks());
    }

    /** 境目より十分濃い濃度(1 回で 1500 に届かない程度)。 */
    private static double exposedPercent() {
        return Math.max(DesignData.sporeField().exposureMinPercent() * 2.0D, 10.0D);
    }

    private static void step(GameTestHelper helper, LivingEntity e, double percent, int n) {
        for (int i = 0; i < n; i++) SporeExposureTracker.tickWith(helper.getLevel(), e, spec().countStepTicks(), percent);
    }

    /** 5 秒浴びると感染する。4 秒(1 回少ない)では感染しない。境目より下では数えない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void infectsAfterFiveSecondsNotFour(GameTestHelper helper) {
        Cow four = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1, 2, 1));
        Cow five = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 5));
        Cow thin = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1, 2, 5));
        for (Cow c : new Cow[]{four, five, thin}) c.setInvulnerable(true);
        int n = stepsToInfect();
        step(helper, four, exposedPercent(), n - 1);
        step(helper, five, exposedPercent(), n);
        step(helper, thin, DesignData.sporeField().exposureMinPercent() * 0.5D, n * 3);
        if (MobInfection.isInfected(four)) {
            helper.fail((n - 1) + " 回(" + (n - 1) * spec().countStepTicks() / 20.0D + " 秒)で感染した");
            return;
        }
        if (!MobInfection.isInfected(five)) {
            helper.fail(n + " 回(" + spec().startSeconds() + " 秒)で感染しない");
            return;
        }
        if (MobInfection.isInfected(thin)) {
            helper.fail("境目より薄い所で感染した");
            return;
        }
        helper.succeed();
    }

    /** 途中で出たら数え直す(4 秒 → 出る → 4 秒 では感染しない)。数えている間は判定が細かくなる。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void leavingResetsTheCount(GameTestHelper helper) {
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        c.setInvulnerable(true);
        int n = stepsToInfect();
        step(helper, c, exposedPercent(), n - 1);
        int base = DesignData.sporeField().exposureMobIntervalTicks();
        int counting = MobInfection.intervalFor(c, base);
        step(helper, c, 0.0D, 1);
        step(helper, c, exposedPercent(), n - 1);
        if (MobInfection.isInfected(c)) {
            helper.fail("出て数え直しになっていない");
            return;
        }
        if (counting != Math.min(base, spec().countStepTicks())) {
            helper.fail("数えている間の判定の間隔が " + counting);
            return;
        }
        helper.succeed();
    }

    /** 感染した後は、濃度 0 の所でも暴露量が growPerSecond ずつ上がる(減らない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void doseKeepsRisingAfterLeaving(GameTestHelper helper) {
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        c.setInvulnerable(true);
        step(helper, c, exposedPercent(), stepsToInfect());
        double before = SporeExposureTracker.dose(c);
        int interval = DesignData.sporeField().exposureMobIntervalTicks();
        for (int i = 0; i < 3; i++) SporeExposureTracker.tickWith(helper.getLevel(), c, interval, 0.0D);
        double expect = Math.min(DesignData.sporeField().infectDose(), before + spec().growPerSecond() * interval / 20.0D * 3);
        if (Math.abs(SporeExposureTracker.dose(c) - expect) > 1.0E-6D) {
            helper.fail("離れた後の暴露量 " + before + " → " + SporeExposureTracker.dose(c) + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }

    /** 感染した G1 になれる種(牛)は胞子で死に、死体へ崩れ落ち、死んだチャンクに飛び火が残る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 100)
    public static void infectedCowDiesCollapsesAndLeavesJump(GameTestHelper helper) {
        MobInfection.clearJumpCooldownsForTests();
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        MobInfection.infect(c, helper.getLevel().getGameTime());
        BlockPos at = c.blockPosition();
        int jumps = MobInfection.jumpCount;
        // 1500 の少し手前から、汚染の無い所で 1 回 → 届いて 20 ダメージ → 死ぬ
        SporeExposureTracker.setDose(c, DesignData.sporeField().infectDose() - 1.0D, helper.getLevel().getGameTime());
        SporeExposureTracker.tickWith(helper.getLevel(), c, DesignData.sporeField().exposureMobIntervalTicks(), 0.0D);
        if (c.isAlive()) {
            helper.fail("感染した牛が死なない: 体力 " + c.getHealth() + "・暴露 " + SporeExposureTracker.dose(c));
            return;
        }
        if (MobInfection.jumpCount != jumps + 1 || !at.equals(MobInfection.lastJumpForTests)) {
            helper.fail("死んだ所に飛び火が無い: 数 " + jumps + " → " + MobInfection.jumpCount + "・場所 " + MobInfection.lastJumpForTests + "(期待 " + at + ")");
            return;
        }
        if (!CorpseCollapse.isCollapsing(c)) {
            helper.fail("G1 になれる牛が死体へ崩れ落ちない");
            return;
        }
        helper.succeed();
    }

    /**
     * G1 になれない種(狐)は、感染の死の所で死なずに汎用の感染体に変わる(2026-11-15 ユーザー決定)。変わった狐が死ぬと、その所に飛び火を残す
     * (★ 問い 11。死体にはならない)。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 100)
    public static void infectedFoxLeavesJumpToo(GameTestHelper helper) {
        MobInfection.clearJumpCooldownsForTests();
        Mob fox = helper.spawnWithNoFreeWill(EntityType.FOX, new BlockPos(3, 2, 3));
        MobInfection.infect(fox, helper.getLevel().getGameTime());
        BlockPos at = fox.blockPosition();
        SporeExposureTracker.setDose(fox, DesignData.sporeField().infectDose(), helper.getLevel().getGameTime());
        SporeExposureTracker.tickWith(helper.getLevel(), fox, DesignData.sporeField().exposureMobIntervalTicks(), 0.0D);
        if (!fox.isAlive() || !com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(fox)) {
            helper.fail("感染した狐が汎用の感染体に変わらない: 生きている " + fox.isAlive());
            return;
        }
        fox.kill();
        boolean collapsing = CorpseCollapse.isCollapsing(fox);
        helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.BlightFernEntity.class,
                new net.minecraft.world.phys.AABB(at).inflate(4)).forEach(net.minecraft.world.entity.Entity::discard);
        if (!at.equals(MobInfection.lastJumpForTests)) {
            helper.fail("変わった狐の死んだ所に飛び火が無い: " + MobInfection.lastJumpForTests);
            return;
        }
        if (collapsing) {
            helper.fail("狐が死体になった(G1 の表に無い種)");
            return;
        }
        helper.succeed();
    }

    /** 感染していない Mob が死んでも飛び火は残らない。同じチャンクの飛び火は間隔の中では 1 回。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void healthyDeathNoJumpAndCooldown(GameTestHelper helper) {
        MobInfection.clearJumpCooldownsForTests();
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        int jumps = MobInfection.jumpCount;
        c.kill();
        if (MobInfection.jumpCount != jumps) {
            helper.fail("感染していない牛の死で飛び火が起きた");
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos far = new BlockPos(16 * 1_230_001 + 8, 64, 16 * -1_230_001 + 8); // ほかのテストと重ならない遠くのチャンク
        boolean first = MobInfection.jumpAt(level, far);
        boolean second = MobInfection.jumpAt(level, far.east(2));
        MobInfection.clearJumpCooldownsForTests();
        SporeFieldServer.withFieldForTests(f -> {
            f.clearDepositAround(far.getX() >> 4, far.getZ() >> 4, 0.5D);
            f.extractAround(far.getX() >> 4, far.getZ() >> 4, 0.5D, Double.POSITIVE_INFINITY);
            return null;
        });
        if (!first || (spec().jumpCooldownTicks() > 0 && second)) {
            helper.fail("同じチャンクの飛び火の間隔: 1 回目 " + first + "・2 回目 " + second);
            return;
        }
        helper.succeed();
    }

    /** プレイヤーと植物側は感染しない(プレイヤーは今の 10 段階のまま、植物側は測らない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void playersAndPlantSideAreExcluded(GameTestHelper helper) {
        Player p = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        p.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5D, 2.0D, 3.5D)));
        for (int i = 0; i < stepsToInfect() * 2; i++) SporeExposureTracker.tickWith(helper.getLevel(), p, spec().countStepTicks(), exposedPercent());
        LivingEntity plant = (LivingEntity) com.yayuy.overgrownvegetation.registry.ModEntities.BLIGHT_FERN.get().create(helper.getLevel());
        if (plant != null) MobInfection.infect(plant, helper.getLevel().getGameTime());
        if (OvAttach.hasData(p, ModAttachments.SPORE_EXPOSURE) && OvAttach.getData(p, ModAttachments.SPORE_EXPOSURE).infected) {
            helper.fail("プレイヤーが Mob の感染になった");
            return;
        }
        if (plant == null || MobInfection.isInfected(plant)) {
            helper.fail("植物側(萌芽体)が感染した");
            return;
        }
        helper.succeed();
    }

    /**
     * 感染した後は弱って遅くなる(11-14 ユーザー): 歩く速さが infectedSlowdown だけ下がる。読み直した後も次の判定で付き直し、
     * 感染の記録を消すと戻る。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void infectedMobIsSlowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        c.setInvulnerable(true);
        var speed = net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED;
        double base = c.getAttributeValue(speed);
        MobInfection.infect(c, level.getGameTime());
        double slowed = c.getAttributeValue(speed);
        double expect = base * (1.0D - spec().infectedSlowdown());
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        c.saveWithoutId(saved);
        Cow copy = EntityType.COW.create(level);
        copy.load(saved);
        copy.moveTo(c.position());
        SporeExposureTracker.tickWith(level, copy, DesignData.sporeField().exposureMobIntervalTicks(), 0.0D);
        double reloaded = copy.getAttributeValue(speed);
        SporeExposureTracker.setDose(c, 0.0D, level.getGameTime()); // 記録ごと消す
        SporeExposureTracker.tickWith(level, c, DesignData.sporeField().exposureMobIntervalTicks(), 0.0D);
        double cleared = c.getAttributeValue(speed);
        if (Math.abs(slowed - expect) > 1.0E-6D || Math.abs(reloaded - expect) > 1.0E-6D || Math.abs(cleared - base) > 1.0E-6D) {
            helper.fail("速さ: もと " + base + "・感染 " + slowed + "・読み直し " + reloaded + "・消した後 " + cleared + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }

    /** 感染・感染した時刻・逃げる向きは保存して読み直しても残る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void infectionSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        c.setInvulnerable(true);
        MobInfection.infect(c, 1234L);
        SporeExposure ex = OvAttach.getData(c, ModAttachments.SPORE_EXPOSURE);
        ex.dose = 321.0D;
        ex.fleeX = 0.6F;
        ex.fleeZ = -0.8F;
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        c.saveWithoutId(saved);
        Cow copy = EntityType.COW.create(level);
        copy.load(saved);
        SporeExposure ey = OvAttach.hasData(copy, ModAttachments.SPORE_EXPOSURE) ? OvAttach.getData(copy, ModAttachments.SPORE_EXPOSURE) : null;
        if (ey == null || !ey.infected || ey.infectedGameTime != 1234L || Math.abs(ey.dose - 321.0D) > 1.0E-6D
                || Math.abs(ey.fleeX - 0.6F) > 1.0E-6F || Math.abs(ey.fleeZ + 0.8F) > 1.0E-6F) {
            helper.fail("読み直した感染: " + (ey == null ? "null" : ey.infected + "・" + ey.infectedGameTime + "・" + ey.dose + "・" + ey.fleeX + "," + ey.fleeZ));
            return;
        }
        helper.succeed();
    }

    /** 動物は逃げる側、敵とゴーレムは逃げない。感染すると濃度 0 の所でも逃げる向きを持って逃げ続ける。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void animalsFleeHostilesDoNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Cow c = helper.spawn(EntityType.COW, new BlockPos(3, 2, 3)); // spawnWithNoFreeWill は Goal を全部外すので使わない
        c.setInvulnerable(true);
        Mob zombie = EntityType.ZOMBIE.create(level);
        Mob golem = EntityType.IRON_GOLEM.create(level);
        Mob villager = EntityType.VILLAGER.create(level);
        if (!MobInfection.isFleer(c) || !MobInfection.isFleer(villager) || MobInfection.isFleer(zombie) || MobInfection.isFleer(golem)) {
            helper.fail("逃げる側の区別: 牛 " + MobInfection.isFleer(c) + "・村人 " + MobInfection.isFleer(villager)
                    + "・ゾンビ " + MobInfection.isFleer(zombie) + "・ゴーレム " + MobInfection.isFleer(golem));
            return;
        }
        MobInfection.infect(c, level.getGameTime());
        SporeExposureTracker.tickWith(level, c, DesignData.sporeField().exposureMobIntervalTicks(), 0.0D);
        if (!MobInfection.shouldFlee(c, level.getGameTime())) {
            helper.fail("感染した牛が濃度 0 の所で逃げない");
            return;
        }
        boolean hasGoal = c.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof com.yayuy.overgrownvegetation.spore.SporeFleeGoal);
        c.discard();
        if (!hasGoal) {
            helper.fail("牛に逃げる Goal が無い");
            return;
        }
        helper.succeed();
    }

    /**
     * 飛び火 1 回で、汚染の無い遠くのチャンクが発芽するか(場の式。1 日進める)。発芽の抽選があるので 8 か所で試し、割合を記録に出す。
     * 1 か所でも発芽すれば通す(0 か所なら量が足りない)。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_wait", timeoutTicks = 600)
    public static void singleJumpGerminatesFarChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData data = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.get(level.getServer());
        java.util.Map<com.yayuy.overgrownvegetation.difficulty.DifficultySetting, Double> saved = data.overrides();
        com.yayuy.overgrownvegetation.phase.PhaseData phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        int trials = 8, sprouted = 0;
        StringBuilder log = new StringBuilder();
        MobInfection.clearJumpCooldownsForTests();
        long dayTimeBefore = level.getDayTime();
        try {
            data.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.UNLOADED_SPREAD, 0.0D); // 読み込まれていない升は「待ち」で残る
            // 追いつきの 1 日の終わりを夕方にそろえる(11-18 切り分け: 昼に終わる 1 日は 0/4、夕方〜夜に終わる 1 日は 2〜3/4 発芽する。
            // 前のテストの数で走る時刻がずれ、ほかのテストを足しただけで決まって落ちていた)
            level.setDayTime(Math.floorDiv(dayTimeBefore, 24000L) * 24000L + 13000L);
            for (int k = 0; k < trials; k++) {
                int cx = 1_300_000 + k * 40, cz = -1_300_000;
                long key = ChunkPos.asLong(cx, cz);
                SporeFieldServer.watchForTests(cx, cz);
                MobInfection.jumpAt(level, new BlockPos(cx * 16 + 8, 64, cz * 16 + 8));
                // 場の時間で 1 日(24000 tick。夜が入る = 湿る)を 1 回で進める。発芽の抽選は升ごとに違う
                SporeFieldServer.catchUpForTests(level, 24000L, key);
                boolean hit = Boolean.TRUE.equals(SporeFieldServer.withFieldForTests(f -> f.isWaiting(cx, cz) || f.isCorrupted(cx, cz)));
                double dep = SporeFieldServer.withFieldForTests(f -> f.deposited(cx, cz));
                if (hit) sprouted++;
                log.append(hit ? "発芽 " : String.format("-(残り %.3f) ", dep));
                SporeFieldServer.withFieldForTests(f -> {
                    f.unsprout(cx, cz);
                    f.clearDepositAround(cx, cz, 1.5D);
                    f.extractAround(cx, cz, 1.5D, Double.POSITIVE_INFINITY);
                    return null;
                });
                phase.forgetOvergrownChunk(new ChunkPos(cx, cz));
            }
        } finally {
            level.setDayTime(dayTimeBefore);
            data.replaceAll(saved);
            MobInfection.clearJumpCooldownsForTests();
        }
        OvergrownVegetation.LOGGER.info("[mob_infection] 飛び火 1 回で発芽: {}/{}(発芽までの時間 {})", sprouted, trials, log);
        if (sprouted == 0) {
            helper.fail("飛び火 1 回で 1 か所も発芽しない(" + log + ")");
            return;
        }
        helper.succeed();
    }

    /** 重さ: 感染した牛 200 体の 1 回の判定(場の読み込み込み)の費用を測って記録に出す(通す・落とすは決めない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 100)
    public static void costOfInfectedMobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        java.util.List<Cow> cows = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1 + i % 5, 2, 1 + (i / 5) % 5));
            c.setInvulnerable(true);
            MobInfection.infect(c, level.getGameTime());
            cows.add(c);
        }
        Cow healthy = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        healthy.setInvulnerable(true);
        int interval = DesignData.sporeField().exposureMobIntervalTicks();
        for (Cow c : cows) SporeExposureTracker.tick(level, c, interval); // 温める
        long t0 = System.nanoTime();
        int rounds = 10;
        for (int r = 0; r < rounds; r++) for (Cow c : cows) SporeExposureTracker.tick(level, c, interval);
        long infectedNs = (System.nanoTime() - t0) / (rounds * cows.size());
        long t1 = System.nanoTime();
        for (int r = 0; r < rounds * cows.size(); r++) SporeExposureTracker.tick(level, healthy, interval);
        long healthyNs = (System.nanoTime() - t1) / (rounds * cows.size());
        cows.forEach(net.minecraft.world.entity.Entity::discard);
        OvergrownVegetation.LOGGER.info("[mob_infection] 判定 1 回の費用: 感染 {} ns・感染していない {} ns(感染した Mob 1000 体で 1 tick あたり約 {} ms)",
                infectedNs, healthyNs, String.format("%.3f", infectedNs * 1000.0D / interval / 1.0E6D));
        helper.succeed();
    }
}
