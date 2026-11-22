package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FatigueSpec;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.network.SurvivalHudPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.Fatigue;
import com.yayuy.overgrownvegetation.survival.FatigueTracker;
import com.yayuy.overgrownvegetation.survival.SurvivalSync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentInternals;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * サバイバルの背骨 段 1(2026-11-13): 疲労のたまり方 4 つ・約 2 日で限界・夜に寝ると全部戻る・昼の休みで少しずつ戻る・死亡で 0・
 * 保存と復元・感染の加速の上限・暴露量の抜けにくさ・HUD のバーの値(暴露量と感染中)。期待値は設計値({@code spore_field.json} の fatigue)から引く。
 * 偽プレイヤーは tick しない・ワールドのプレイヤーの一覧に入らないので、判定の口({@link FatigueTracker#step})と出来事の受け口を直に叩く。
 * 夜を飛ばさないこと(昼の休み)は Claude 用クライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SurvivalFatigueGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SurvivalFatigueGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_fatigue";
    private static final double EPS = 1.0E-6D;

    private static FakePlayer player(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        reset(player);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    private static void reset(FakePlayer player) {
        if (player.isSleeping()) player.stopSleeping();
        SporeInfectionEffects.setPlayerStage(player, 0);
        SporeExposureTracker.setDose(player, 0.0D, 0L);
        FatigueTracker.set(player, 0.0D);
        player.setSprinting(false);
    }

    private static FatigueSpec spec() {
        return DesignData.sporeField().fatigue();
    }

    /** 4 つの要因がそれぞれ設計値どおりに足される。創造ではたまらない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fourFactorsAccumulate(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_factors");
        FatigueSpec s = spec();
        try {
            // 1) 起きている時間: 20 秒
            FatigueTracker.step(p, 400, 0.0D, 0, false);
            check(helper, "起きている 20 秒", FatigueTracker.value(p), s.awakePerSecond() * 20.0D);
            // 2) 濃い胞子: 100% で 20 秒(起きている分を引く)。マスクが効けば 0
            FatigueTracker.set(p, 0.0D);
            FatigueTracker.step(p, 400, 100.0D, 0, false);
            check(helper, "濃い胞子 100% 20 秒", FatigueTracker.value(p), (s.awakePerSecond() + s.densePerSecondAt100()) * 20.0D);
            FatigueTracker.set(p, 0.0D);
            FatigueTracker.step(p, 400, 100.0D, 0, true);
            check(helper, "濃い胞子 + マスク", FatigueTracker.value(p), s.awakePerSecond() * 20.0D);
            FatigueTracker.set(p, 0.0D);
            FatigueTracker.step(p, 400, s.denseFromPercent(), 0, false);
            check(helper, "濃い胞子のしきい値ちょうど", FatigueTracker.value(p), s.awakePerSecond() * 20.0D);
            // 3) 走る・戦う・掘る: 20 tick 走って 3 回殴って 5 個掘る
            FatigueTracker.set(p, 0.0D);
            p.getData(ModAttachments.FATIGUE).sprintTicks = 20;
            for (int i = 0; i < 3; i++) FatigueTracker.countAttack(p);
            for (int i = 0; i < 5; i++) FatigueTracker.countBreak(p);
            FatigueTracker.step(p, 20, 0.0D, 0, false);
            check(helper, "走る・戦う・掘る", FatigueTracker.value(p),
                    s.awakePerSecond() + s.sprintPerSecond() + 3 * s.attackEach() + 5 * s.breakEach());
            // 数えた出来事は判定で捨てる
            FatigueTracker.set(p, 0.0D);
            FatigueTracker.step(p, 20, 0.0D, 0, false);
            check(helper, "出来事の持ち越し無し", FatigueTracker.value(p), s.awakePerSecond());
            // 4) 感染の段階: 感染5 で 20 秒
            FatigueTracker.set(p, 0.0D);
            FatigueTracker.step(p, 400, 0.0D, 5, false);
            check(helper, "感染5 20 秒", FatigueTracker.value(p), (s.awakePerSecond() + 5 * s.stagePerSecond()) * 20.0D);
            // 創造ではたまらない
            FatigueTracker.set(p, 10.0D);
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            FatigueTracker.step(p, 400, 100.0D, 5, false);
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            check(helper, "創造", FatigueTracker.value(p), 10.0D);
            helper.succeed();
        } finally {
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            reset(p);
        }
    }

    /**
     * ふつうに過ごして寝なければ約 2 日(48000 tick)で限界(ユーザー指定)。「ふつう」= 起きている + 2 割走る + 20 秒に 1 回殴る +
     * 5 秒に 1 ブロック掘る・胞子なし・感染なし(仮決め。設計値の _comment と同じ)。1.8〜2.2 日の間で 100 に届けばよい。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void normalPlayReachesLimitInAboutTwoDays(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_two_days");
        try {
            int seconds = 0;
            while (FatigueTracker.value(p) < 100.0D - EPS && seconds < 48000) {
                Fatigue f = p.getData(ModAttachments.FATIGUE);
                if (seconds % 5 == 0) f.sprintTicks = 20; // 5 秒のうち 1 秒走る = 2 割
                if (seconds % 20 == 0) FatigueTracker.countAttack(p);
                if (seconds % 5 == 0) FatigueTracker.countBreak(p);
                FatigueTracker.step(p, 20, 0.0D, 0, false);
                seconds++;
            }
            double days = seconds / 1200.0D;
            if (days < 1.8D || days > 2.2D) {
                helper.fail(String.format("ふつうに過ごして 100%% まで %.2f 日(期待 約 2 日)", days));
                return;
            }
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    /** 効き目: 半分までは無し、100% で移動・採掘・攻撃速度が設計値だけ下がる。0 に戻せば外れる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void slowdownFollowsFatigue(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_slow");
        FatigueSpec s = spec();
        try {
            double baseMove = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
            double baseMine = p.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
            double baseAttack = p.getAttributeValue(Attributes.ATTACK_SPEED);
            FatigueTracker.set(p, s.effectsFrom());
            check(helper, "しきい値で移動", p.getAttributeValue(Attributes.MOVEMENT_SPEED), baseMove);
            FatigueTracker.set(p, 100.0D);
            check(helper, "100% で移動", p.getAttributeValue(Attributes.MOVEMENT_SPEED), baseMove * (1.0D - s.moveSlow()));
            check(helper, "100% で採掘", p.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), baseMine * (1.0D - s.mineSlow()));
            check(helper, "100% で攻撃速度", p.getAttributeValue(Attributes.ATTACK_SPEED), baseAttack * (1.0D - s.attackSpeedSlow()));
            FatigueTracker.set(p, 0.0D);
            check(helper, "0 に戻して移動", p.getAttributeValue(Attributes.MOVEMENT_SPEED), baseMove);
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    /** 感染の加速は疲労 100% でも infectionMultiplierMax を超えない。実際の進みも 1 倍の時の上限倍まで。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void infectionAccelerationIsCapped(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_infection_cap");
        FatigueSpec s = spec();
        try {
            for (int v = 0; v <= 100; v += 5) {
                FatigueTracker.set(p, v);
                double m = FatigueTracker.infectionMultiplier(p);
                if (m < 1.0D - EPS || m > s.infectionMultiplierMax() + EPS) {
                    helper.fail("疲労 " + v + "% の感染の倍率 " + m + "(上限 " + s.infectionMultiplierMax() + ")");
                    return;
                }
            }
            FatigueTracker.set(p, 100.0D);
            check(helper, "100% の感染の倍率", FatigueTracker.infectionMultiplier(p), s.infectionMultiplierMax());
            // 実際の進み: 胞子なしで 2000 tick
            SporeInfectionEffects.startPlayer(p);
            FatigueTracker.set(p, 0.0D);
            SporeInfectionEffects.tickInfected(p, p.getData(ModAttachments.SPORE_EXPOSURE), 2000, 0.0D);
            int rested = SporeInfectionEffects.progressOf(p);
            SporeInfectionEffects.setPlayerStage(p, 1);
            p.getData(ModAttachments.SPORE_EXPOSURE).rateCarry = 0.0D;
            FatigueTracker.set(p, 100.0D);
            SporeInfectionEffects.tickInfected(p, p.getData(ModAttachments.SPORE_EXPOSURE), 2000, 0.0D);
            int tired = SporeInfectionEffects.progressOf(p);
            if (rested <= 0 || tired > rested * s.infectionMultiplierMax() + 1 || tired < rested * s.infectionMultiplierMax() - 1) {
                helper.fail("疲労 0 の進み " + rested + "・100% の進み " + tired + "(期待 ×" + s.infectionMultiplierMax() + ")");
                return;
            }
            // 11-15: 疲労は体の中で育つ分に掛かる(マスクは息で浴びる分だけ止めるので、育つ分の疲労の倍率は残る)
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    /** 暴露量の抜けにくさ: 胞子の無い所で半減期 1 回ぶん、疲労 0 なら半分・100% なら 0.5^(1/上限倍)。Mob は変わらない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void exposureDecaysSlowerWhenTired(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_exposure");
        FatigueSpec s = spec();
        SporeFieldTable t = DesignData.sporeField();
        int halfLifeTicks = (int) Math.round(t.exposureHalfLifeSeconds() * 20.0D);
        try {
            SporeExposureTracker.setDose(p, 1000.0D, 0L);
            SporeExposureTracker.accumulate(p, 0.0D, 1.0D, halfLifeTicks, 0L);
            check(helper, "疲労 0 で半減期 1 回", SporeExposureTracker.dose(p), 500.0D);
            SporeExposureTracker.setDose(p, 1000.0D, 0L);
            FatigueTracker.set(p, 100.0D);
            SporeExposureTracker.accumulate(p, 0.0D, 1.0D, halfLifeTicks, 0L);
            check(helper, "疲労 100% で半減期 1 回", SporeExposureTracker.dose(p), 1000.0D * Math.pow(0.5D, 1.0D / s.exposureHalfLifeMax()));
            var zombie = EntityType.ZOMBIE.create(helper.getLevel());
            if (zombie == null || FatigueTracker.exposureHalfLifeMultiplier(zombie) != 1.0D) {
                helper.fail("Mob の半減期の倍率が 1 でない");
                return;
            }
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    /** 夜に寝る: 夜を飛ばして起こされる(すぐ起きない・数え直さない)と全部戻る。ほかの起き方では戻らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void nightSleepRestoresAll(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_night");
        try {
            BlockPos bed = placeBed(helper);
            FatigueTracker.set(p, 80.0D);
            p.startSleeping(bed);
            p.stopSleepInBed(true, true); // 自分で起きた・追い出された
            check(helper, "自分で起きた", FatigueTracker.value(p), 80.0D);
            p.startSleeping(bed);
            p.stopSleepInBed(false, false); // ServerLevel#wakeUpAllPlayers と同じ起き方
            check(helper, "夜を飛ばして起こされた", FatigueTracker.value(p), 0.0D);
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    /**
     * 昼の休み: 横になっている間は 1 秒ごとに restPerSecond 戻る(起きている分はたまらない)。「夜しか眠れない」だけを外し、まわりに敵がいれば
     * 横になれない。朝になっても追い出さない。寝ている間は sleepCounter を「深く眠った」(100)の手前で止める(夜を飛ばさない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dayRestRecoversSlowly(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_day_rest");
        FatigueSpec s = spec();
        try {
            BlockPos bed = placeBed(helper);
            // 横になれるか(昼): 夜しか眠れない → 問題なし
            CanPlayerSleepEvent ev = new CanPlayerSleepEvent(p, bed, Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
            FatigueTracker.onCanSleep(ev);
            if (ev.getProblem() != null) {
                helper.fail("昼にベッドへ横になれない: " + ev.getProblem());
                return;
            }
            // ほかの問題(遠い・塞がっている)は外さない
            CanPlayerSleepEvent far = new CanPlayerSleepEvent(p, bed, Player.BedSleepingProblem.TOO_FAR_AWAY);
            FatigueTracker.onCanSleep(far);
            if (far.getProblem() != Player.BedSleepingProblem.TOO_FAR_AWAY) {
                helper.fail("遠いベッドの問題まで外した");
                return;
            }
            // 敵がいれば横になれない
            var zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
            CanPlayerSleepEvent unsafe = new CanPlayerSleepEvent(p, bed, Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
            FatigueTracker.onCanSleep(unsafe);
            zombie.discard();
            if (s.dayRestNeedsSafety() && unsafe.getProblem() != Player.BedSleepingProblem.NOT_SAFE) {
                helper.fail("敵がいるのに昼に横になれた: " + unsafe.getProblem());
                return;
            }
            // 朝になっても追い出さない
            CanContinueSleepingEvent cont = new CanContinueSleepingEvent(p, Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
            FatigueTracker.onCanContinueSleeping(cont);
            if (!cont.mayContinueSleeping()) {
                helper.fail("昼にベッドから追い出される");
                return;
            }
            // 少しずつ戻る
            FatigueTracker.set(p, 60.0D);
            p.startSleeping(bed);
            FatigueTracker.step(p, 400, 100.0D, 5, false);
            check(helper, "昼の休み 20 秒", FatigueTracker.value(p), 60.0D - s.restPerSecond() * 20.0D);
            // sleepCounter を 100 の手前で止める
            Field f = Player.class.getDeclaredField("sleepCounter");
            f.setAccessible(true);
            f.setInt(p, 100);
            FatigueTracker.holdDayRest(p);
            if (f.getInt(p) >= 100 || p.isSleepingLongEnough()) {
                helper.fail("昼の休みで「深く眠った」になる(夜を飛ばしてしまう)");
                return;
            }
            helper.succeed();
        } catch (ReflectiveOperationException e) {
            helper.fail("sleepCounter に届かない: " + e);
        } finally {
            reset(p);
        }
    }

    /** 死んで復活したら 0(添付を写さない)。死なずに作り直す(エンドから戻る)ときは保存して戻す。値の保存と読み込みも。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void deathResetsAndSaveRestores(GameTestHelper helper) {
        FakePlayer from = player(helper, "ov_fatigue_clone_from");
        FakePlayer died = player(helper, "ov_fatigue_clone_died");
        FakePlayer moved = player(helper, "ov_fatigue_clone_moved");
        try {
            FatigueTracker.set(from, 73.5D);
            AttachmentInternals.copyEntityAttachments(from, died, true);
            check(helper, "死んで復活", FatigueTracker.value(died), 0.0D);
            AttachmentInternals.copyEntityAttachments(from, moved, false);
            check(helper, "死なずに作り直し(保存 → 読み込み)", FatigueTracker.value(moved), 73.5D);
            Tag tag = Fatigue.CODEC.encodeStart(NbtOps.INSTANCE, from.getData(ModAttachments.FATIGUE)).getOrThrow();
            Fatigue back = Fatigue.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            check(helper, "コーデック", back.value, 73.5D);
            helper.succeed();
        } finally {
            reset(from);
            reset(died);
            reset(moved);
        }
    }

    /** HUD のバーの値: 未感染は暴露量の割合、感染中は今の段階の進み。値は 1/1024・1/1000 に丸めて送る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void hudBarValues(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_fatigue_hud");
        SporeFieldTable t = DesignData.sporeField();
        try {
            SporeExposureTracker.setDose(p, t.infectDose() * 0.5D, 0L);
            FatigueTracker.set(p, 37.0D);
            SurvivalHudPayload a = SurvivalSync.payload(p);
            check(helper, "暴露量の割合", SurvivalSync.barFraction(p), 0.5D);
            check(helper, "送る暴露量", a.exposure(), 0.5D);
            check(helper, "送る疲労", a.fatigue(), 0.37D);
            if (a.stage() != 0) {
                helper.fail("未感染なのに段階 " + a.stage());
                return;
            }
            check(helper, "効き目の出始め", a.effectsFrom(), spec().effectsFrom() / 100.0D);
            // 感染3・進み 1/4
            SporeInfectionEffects.setPlayerStage(p, 3);
            int stageTicks = SporeInfectionEffects.stageDoseOf(DesignData.effects().playerInfection(), 3); // 11-15: 進みは暴露量(段 3 の 1 段の量)
            p.setData(ModAttachments.SPORE_STAGE_TICKS, stageTicks / 4);
            SurvivalHudPayload b = SurvivalSync.payload(p);
            if (b.stage() != 3) {
                helper.fail("感染3 の段階が " + b.stage());
                return;
            }
            check(helper, "感染中のバー", SurvivalSync.barFraction(p), (double) (stageTicks / 4) / stageTicks);
            if (Math.abs(b.stageProgress() - 0.25F) > 1.0F / 1024.0F) {
                helper.fail("送る段階の進み " + b.stageProgress() + "(期待 0.25)");
                return;
            }
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    private static BlockPos placeBed(GameTestHelper helper) {
        BlockPos foot = new BlockPos(2, 2, 2);
        BlockPos head = foot.relative(Direction.NORTH);
        BlockState footState = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
        helper.setBlock(foot, footState);
        helper.setBlock(head, footState.setValue(BedBlock.PART, BedPart.HEAD));
        return helper.absolutePos(head);
    }

    private static void check(GameTestHelper helper, String what, double actual, double expected) {
        if (Math.abs(actual - expected) > 1.0E-4D) {
            helper.fail(what + ": " + actual + "(期待 " + expected + ")");
            throw new IllegalStateException("unreachable"); // fail は投げるのでここには来ない
        }
    }

    private SurvivalFatigueGameTests() {
    }
}
