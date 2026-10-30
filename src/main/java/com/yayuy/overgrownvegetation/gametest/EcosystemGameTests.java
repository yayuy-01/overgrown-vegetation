package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.Gson;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.EcosystemTable;
import com.yayuy.overgrownvegetation.ecosystem.Ecosystem;
import com.yayuy.overgrownvegetation.ecosystem.EcosystemData;
import com.yayuy.overgrownvegetation.ecosystem.EcosystemDefense;
import com.yayuy.overgrownvegetation.ecosystem.EcosystemMath;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <b>生態系の台帳(兵站)</b>のテスト(2026-09-24)。
 *
 * <p>守りたいのは 4 つ。
 * <ul>
 *     <li><b>ユーザー指定の数値</b> —— 5 日で 0→100%、毎秒 1 + 過繁茂 100 チャンクごとに +1(× 多様性。10-14)、しきい値 70/40/15%、
 *     防衛の兵 1 体の値段 100 / 1500 / 4500。</li>
 *     <li><b>数える死</b> —— プレイヤーと防衛設備(機関砲台)に倒されたグループ2・3 だけ。
 *     それ以外の死、グループ4 の死では減らない。</li>
 *     <li><b>防衛の選び方</b> —— 段階が深いほど強いグループまで、強い方から、払えなければ 1 つ下へ。</li>
 *     <li><b>同梱の json と Java の既定値が食い違わない</b>。</li>
 * </ul>
 *
 * <p>計算のテストは世界に触らない({@link EcosystemMath})。実際に倒すテストは<b>世界に 1 つの台帳</b>を触るので、
 * 自分だけのバッチに入れてある(多様性は 10-14 からワールドで 1 つ。同じバッチは並列に走るので、ほかのテストの撃破と混ざらないように)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class EcosystemGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(EcosystemGameTests.class);
        }

        private Registration() {
        }
    }

    private static final double EPS = 1.0E-6D;
    /** 実際に倒すテストの許容差。数 tick のうちに戻るぶん(5 日で 100% → 1 tick で 0.0008%)を見込む。 */
    private static final double KILL_EPS = 1.0E-3D;

    /** 区域は 8 チャンク四方。負の座標も床で切る(-1 チャンクは区域 -1)。 */
    @GameTest(template = "gametest_empty7")
    public static void regionKeysFloorNegativeChunks(GameTestHelper helper) {
        int size = EcosystemTable.DEFAULT.regionChunks();
        expectInt(helper, size, 8, "区域の一辺");
        expectTrue(helper, EcosystemMath.regionKey(0, 0, size) == EcosystemMath.regionKey(7, 7, size),
                "(0,0) と (7,7) は同じ区域");
        expectTrue(helper, EcosystemMath.regionKey(7, 0, size) != EcosystemMath.regionKey(8, 0, size),
                "(7,0) と (8,0) は別の区域");
        expectTrue(helper, EcosystemMath.regionKey(-1, 0, size) == EcosystemMath.regionKey(-8, 0, size),
                "(-1,0) と (-8,0) は同じ区域");
        expectTrue(helper, EcosystemMath.regionKey(-1, 0, size) != EcosystemMath.regionKey(0, 0, size),
                "(-1,0) は区域 0 に入らない");
        expectInt(helper, ChunkPos.getX(EcosystemMath.regionKey(-9, 0, size)), -2, "チャンク -9 は区域 -2");
        helper.succeed();
    }

    /** 0% から 100% まで 5 日(120000 tick)で直線に戻り、100% で止まる。 */
    @GameTest(template = "gametest_empty7")
    public static void healthRecoversFullyInFiveDays(GameTestHelper helper) {
        double perTick = EcosystemTable.DEFAULT.recoveryPerTick();
        expect(helper, EcosystemMath.recovered(0.0D, 120000L, perTick), 1.0D, "5 日で 100%");
        expect(helper, EcosystemMath.recovered(0.0D, 60000L, perTick), 0.5D, "2.5 日で 50%");
        expect(helper, EcosystemMath.recovered(0.9D, 120000L, perTick), 1.0D, "100% で止まる");
        expect(helper, EcosystemMath.recovered(0.5D, 0L, perTick), 0.5D, "時間が経たなければそのまま");
        helper.succeed();
    }

    /** 段階はしきい値を下回った瞬間に上がり、しきい値 + 5% まで戻ってから下がる。 */
    @GameTest(template = "gametest_empty7")
    public static void defenseTiersHaveHysteresis(GameTestHelper helper) {
        double[] t = EcosystemTable.DEFAULT.thresholds();
        double h = EcosystemTable.DEFAULT.hysteresis();
        expectTrue(helper, List.of(t[0], t[1], t[2]).equals(List.of(0.70D, 0.40D, 0.15D)), "しきい値 70/40/15%");
        expectInt(helper, EcosystemMath.tierFor(0.69D, 0, t, h), 1, "69% で段階 1");
        expectInt(helper, EcosystemMath.tierFor(0.39D, 0, t, h), 2, "39% で段階 2");
        expectInt(helper, EcosystemMath.tierFor(0.14D, 0, t, h), 3, "14% で段階 3");
        expectInt(helper, EcosystemMath.tierFor(0.72D, 1, t, h), 1, "72% に戻っても段階 1 のまま(余裕 5%)");
        expectInt(helper, EcosystemMath.tierFor(0.76D, 1, t, h), 0, "76% まで戻れば防衛終わり");
        expectInt(helper, EcosystemMath.tierFor(0.42D, 3, t, h), 2, "段階 3 から 42% に戻ると段階 2");
        expectInt(helper, EcosystemMath.tierFor(0.46D, 2, t, h), 1, "段階 2 から 46% に戻ると段階 1");
        expectInt(helper, EcosystemMath.tierFor(1.0D, 3, t, h), 0, "満タンなら防衛なし");
        helper.succeed();
    }

    /** グループ2・3 の目標個体数は 目標 × 豊かさ(端数は乱数で切り上げ)。 */
    @GameTest(template = "gametest_empty7")
    public static void spawnTargetsShrinkWithHealth(GameTestHelper helper) {
        expectInt(helper, EcosystemMath.scaledTarget(10, 0.5D, 0.99D), 5, "10 × 50%");
        expectInt(helper, EcosystemMath.scaledTarget(10, 0.55D, 0.49D), 6, "10 × 55% の端数を切り上げる");
        expectInt(helper, EcosystemMath.scaledTarget(10, 0.55D, 0.51D), 5, "10 × 55% の端数を切り捨てる");
        expectInt(helper, EcosystemMath.scaledTarget(1, 0.0D, 0.0D), 0, "豊かさ 0% なら湧かない");
        expectInt(helper, EcosystemMath.scaledTarget(3, 1.0D, 0.99D), 3, "満タンなら目標どおり");
        helper.succeed();
    }

    /** 毎秒 1、過繁茂のチャンク 100 ごとに +1、× ワールドの多様性(2026-10-14 ユーザー指定)。 */
    @GameTest(template = "gametest_empty7")
    public static void incomeIsOnePerSecondPlusOnePerHundredChunks(GameTestHelper helper) {
        EcosystemTable t = EcosystemTable.DEFAULT;
        expect(helper, t.biomassPerSecond(), 1.0D, "毎秒の基本");
        expect(helper, t.biomassPerStep(), 1.0D, "100 チャンクごとの上乗せ");
        expectInt(helper, t.biomassChunksPerStep(), 100, "上乗せの刻み");
        expectInt(helper, t.incomeIntervalTicks(), 20, "1 秒ごとに足す");
        expect(helper, income(t, 0, 1.0D), 1.0D, "過繁茂 0 チャンク");
        expect(helper, income(t, 99, 1.0D), 1.0D, "99 チャンクはまだ 1");
        expect(helper, income(t, 100, 1.0D), 2.0D, "100 チャンクで 2");
        expect(helper, income(t, 1250, 1.0D), 13.0D, "1250 チャンクで 13");
        expect(helper, income(t, 1250, 0.5D), 6.5D, "多様性 50% で半分");
        expect(helper, income(t, 1250, 0.0D), 0.0D, "多様性 0% なら稼げない");
        helper.succeed();
    }

    private static double income(EcosystemTable t, int chunks, double health) {
        return EcosystemMath.incomePerSecond(t.biomassPerSecond(), t.biomassPerStep(), t.biomassChunksPerStep(), chunks, health);
    }

    /** 段階が深いほど強いグループまで・強い方から・払えなければ 1 つ下。値段は 100 / 1500 / 4500。 */
    @GameTest(template = "gametest_empty7")
    public static void defenderChoiceFollowsTierAndBiomass(GameTestHelper helper) {
        EcosystemTable t = EcosystemTable.DEFAULT;
        expect(helper, t.cost(4), 100.0D, "グループ4 の値段");
        expect(helper, t.cost(5), 1500.0D, "グループ5 の値段");
        expect(helper, t.cost(6), 4500.0D, "グループ6 の値段");

        Map<Integer, Integer> none = Map.of();
        expectInt(helper, choose(t, 0, none, 100000.0D, true), 0, "段階 0 なら出さない");
        expectInt(helper, choose(t, 1, none, 150.0D, true), 4, "段階 1 はグループ4");
        expectInt(helper, choose(t, 1, none, 50.0D, true), 0, "100 に足りなければ出さない");
        expectInt(helper, choose(t, 1, Map.of(4, 3), 100000.0D, true), 0, "段階 1 のグループ4 は 3 体で足りている");
        expectInt(helper, choose(t, 2, none, 2000.0D, true), 5, "段階 2 は強いグループ5 から");
        expectInt(helper, choose(t, 2, none, 1000.0D, true), 4, "1500 が払えなければグループ4");
        expectInt(helper, choose(t, 2, Map.of(5, 1), 2000.0D, true), 4, "グループ5 が足りていればグループ4");
        expectInt(helper, choose(t, 3, none, 10000.0D, true), 6, "段階 3 はグループ6 から");
        expectInt(helper, choose(t, 3, none, 10000.0D, false), 5, "グループ6 がまだ解禁されていなければグループ5");
        helper.succeed();
    }

    /** 防衛の兵はフェーズで解禁された地表の種だけ(空を漂う種・weight 0 の種は出さない)。 */
    @GameTest(template = "gametest_empty7")
    public static void defenderCandidatesRespectPhaseAndKind(GameTestHelper helper) {
        // グループ4 はフェーズ4 から(2026-10-10 ユーザー指定で 5 から 1 つ前へ)。
        List<EntityType<?>> g4AtPhase4 = EcosystemDefense.candidatesFor(4, 4);
        expectTrue(helper, g4AtPhase4.contains(ModEntities.ARBORA.get()), "フェーズ4 のグループ4 にアルボラがいる: " + g4AtPhase4);
        expectTrue(helper, !g4AtPhase4.contains(ModEntities.PEDICULA.get()), "空を漂うペディキュラは出さない");
        expectTrue(helper, !g4AtPhase4.contains(ModEntities.BLIGHT_FERN.get()), "動かない汚染シダは出さない");
        expectTrue(helper, EcosystemDefense.candidatesFor(4, 3).isEmpty(), "グループ4 はフェーズ3 ではまだ出ない");
        expectTrue(helper, EcosystemDefense.candidatesFor(6, 10).isEmpty(), "グループ6 はまだ種がいない");
        helper.succeed();
    }

    /** 同梱の {@code design/ecosystem.json} と Java の既定値が同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void bundledJsonMatchesDefault(GameTestHelper helper) {
        EcosystemTable bundled;
        try (InputStream in = EcosystemGameTests.class
                .getResourceAsStream("/overgrown_vegetation/design/ecosystem.json")) {
            if (in == null) {
                helper.fail("design/ecosystem.json が同梱されていない");
                return;
            }
            bundled = EcosystemTable.fromJson(new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), EcosystemTable.Json.class));
        } catch (Exception e) {
            helper.fail("design/ecosystem.json が読めない: " + e);
            return;
        }
        String json = bundled.describe();
        String java = EcosystemTable.DEFAULT.describe();
        if (!json.equals(java)) {
            helper.fail("ecosystem.json が Java の既定値と違う\njson: " + json + "\njava: " + java);
            return;
        }
        helper.succeed();
    }

    /**
     * 通しで: プレイヤー・機関砲台に倒されたグループ2(-2%)・グループ3(-5%)だけがワールドの多様性を減らす。
     * 死因が数えないもの(誰でもない一撃)と、グループ4 の死では減らない。
     */
    @GameTest(template = "gametest_empty7", batch = "ecosystem_kills")
    public static void onlyPlayersAndDefensesLowerHealth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        EcosystemData data = EcosystemData.get(server);
        BlockPos at = new BlockPos(3, 2, 3);
        long now = server.overworld().getGameTime();
        double before = Ecosystem.health(level);
        data.setHealth(now, 1.0D); // 前回の実行やほかのバッチの撃破の残り(テスト用の世界は実行をまたいで残る)を消してから始める
        try {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            DamageSource byPlayer = level.damageSources().playerAttack(player);

            kill(helper, ModEntities.PROTOPHASMA.get(), at, level.damageSources().generic());
            expectNear(helper, Ecosystem.health(level), 1.0D, "誰でもない一撃ではグループ2 が死んでも減らない");

            kill(helper, ModEntities.PROTOPHASMA.get(), at, byPlayer);
            expectNear(helper, Ecosystem.health(level), 0.98D, "プレイヤーがグループ2 を倒すと -2%");

            kill(helper, ModEntities.PROTOPHASMA.get(), at, ModDamageTypes.autocannon(level));
            expectNear(helper, Ecosystem.health(level), 0.96D, "機関砲台がグループ2 を倒すと -2%");

            kill(helper, ModEntities.PHALANGIOTARBUS.get(), at, byPlayer);
            expectNear(helper, Ecosystem.health(level), 0.91D, "プレイヤーがグループ3 を倒すと -5%");

            kill(helper, ModEntities.ARBORA.get(), at, byPlayer);
            expectNear(helper, Ecosystem.health(level), 0.91D, "グループ4 を倒しても減らない");
            helper.succeed();
        } finally {
            data.setHealth(server.overworld().getGameTime(), before);
        }
    }

    // --- 道具 ---

    private static int choose(EcosystemTable table, int tier, Map<Integer, Integer> present, double biomass,
                              boolean group6Unlocked) {
        return EcosystemMath.chooseDefenderGroup(tier, g -> present.getOrDefault(g, 0), biomass, table,
                g -> g != 6 || group6Unlocked);
    }

    private static void kill(GameTestHelper helper, EntityType<? extends LivingEntity> type, BlockPos at,
                             DamageSource source) {
        LivingEntity victim = helper.spawn(type, at);
        victim.hurt(source, 10000.0F);
        if (!victim.isDeadOrDying()) {
            victim.discard();
            helper.fail(type + " が " + source.getMsgId() + " で倒れなかった");
        }
    }

    private static void expect(GameTestHelper helper, double actual, double expected, String what) {
        if (Math.abs(actual - expected) > EPS) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private static void expectNear(GameTestHelper helper, double actual, double expected, String what) {
        if (Math.abs(actual - expected) > KILL_EPS) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private static void expectInt(GameTestHelper helper, int actual, int expected, String what) {
        if (actual != expected) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private static void expectTrue(GameTestHelper helper, boolean ok, String what) {
        if (!ok) {
            helper.fail(what);
        }
    }

    private EcosystemGameTests() {
    }
}
