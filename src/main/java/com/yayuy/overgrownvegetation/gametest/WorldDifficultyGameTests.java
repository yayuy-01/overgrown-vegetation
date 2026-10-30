package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.td.TdTestAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ワールドごとの難易度({@link WorldDifficultyData}、2026-09-25)のテスト。
 *
 * <p><b>ワールドの値は世界に 1 つ</b>なので、書き換えるテストは自分たちだけのバッチ({@value #BATCH})に入れ、
 * どれも<b>始めの 1 tick の中で</b>書き換え・測定・元に戻す(同じバッチのテストが並んで走っても、1 tick の中の処理は順番に
 * 行われるので混ざらない)。戻し忘れると、後のバッチで Mob のダメージや TD の上限を見るテストが訳の分からない落ち方をする。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WorldDifficultyGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(WorldDifficultyGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "worldDifficulty";
    private static final float EPS = 1.0E-3F;

    /**
     * 画面の入力の読み方と表示の仕方。範囲外・数字でない・個数に小数は NaN(画面では赤字になり「完了」が押せない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void settingParseAndFormat(GameTestHelper helper) {
        DifficultySetting enemy = DifficultySetting.ENEMY_STRENGTH;
        DifficultySetting td = DifficultySetting.TD_MAX_MOBS;
        DifficultySetting quake = DifficultySetting.QUAKE_DAY;
        check(helper, enemy.parse("1.5") == 1.5D, "1.5 倍が読めない: " + enemy.parse("1.5"));
        check(helper, enemy.parse("256") == 256.0D, "上限 256 倍が読めない");
        check(helper, Double.isNaN(enemy.parse("256.5")), "256 倍を超える値を受け付けた");
        check(helper, Double.isNaN(enemy.parse("abc")) && Double.isNaN(enemy.parse("")) && Double.isNaN(enemy.parse("1.")),
                "数字でない入力を受け付けた");
        check(helper, Double.isNaN(td.parse("1.5")) && td.parse("500") == 500.0D, "TD の上限は整数だけのはず");
        check(helper, Double.isNaN(td.parse("0")), "TD の上限 0 を受け付けた");
        check(helper, enemy.format(1.0D).equals("1.0") && enemy.format(10.0D).equals("10.0") && enemy.format(1.25D).equals("1.25"),
                "倍率の表示: " + enemy.format(1.0D) + " / " + enemy.format(10.0D) + " / " + enemy.format(1.25D));
        check(helper, quake.format(3.0D).equals("3") && quake.format(2.5D).equals("2.5") && td.format(500.0D).equals("500"),
                "日数・個数の表示: " + quake.format(3.0D) + " / " + quake.format(2.5D) + " / " + td.format(500.0D));
        helper.succeed();
    }

    /**
     * ワールド作成画面が書くファイル({@link WorldDifficultyData#writeInitial})を、サーバーと同じ保存データの読み込みで読めること。
     * ここが崩れると、画面で決めた難易度が黙って既定値に戻る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void createWorldFileLoadsAsSavedData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Path folder = null;
        try {
            folder = Files.createTempDirectory("ov_world_difficulty");
            Map<DifficultySetting, Double> values = new EnumMap<>(DifficultySetting.class);
            values.put(DifficultySetting.ENEMY_STRENGTH, 2.5D);
            values.put(DifficultySetting.QUAKE_DAY, 5.0D);
            values.put(DifficultySetting.TD_MAX_MOBS, 120.0D);
            WorldDifficultyData.writeInitial(folder, values);

            DimensionDataStorage storage = new DimensionDataStorage(folder.resolve("data").toFile(),
                    level.getServer().getFixerUpper(), level.registryAccess());
            WorldDifficultyData data = WorldDifficultyData.readFrom(storage);
            check(helper, data.override(DifficultySetting.ENEMY_STRENGTH).orElse(-1.0D) == 2.5D
                            && data.override(DifficultySetting.QUAKE_DAY).orElse(-1.0D) == 5.0D
                            && data.override(DifficultySetting.TD_MAX_MOBS).orElse(-1.0D) == 120.0D,
                    "書いた値が読めない: " + data.overrides());
            check(helper, !data.isOverridden(DifficultySetting.CORRUPTION_SPEED)
                            && !data.isOverridden(DifficultySetting.PHASE_POINTS)
                            && data.value(DifficultySetting.PHASE_POINTS) == 1.0D,
                    "書いていない項目が既定値になっていない: " + data.overrides());
        } catch (IOException e) {
            helper.fail("一時フォルダに書けない: " + e);
        } finally {
            deleteQuietly(folder);
        }
        helper.succeed();
    }

    /**
     * 敵の強さは、グループの Mob の攻撃にだけ掛かる(バニラの敵対 Mob の攻撃には掛からない)。
     * 受け手は毎回新しく湧かせたブタ(体力 10・防具なし・無敵時間なし)。亜種などで元の量が変わっても比で見るので落ちない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void enemyStrengthScalesEnemyDamageOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldDifficultyData data = WorldDifficultyData.get(level.getServer());
        Map<DifficultySetting, Double> saved = data.overrides();
        try {
            Mob enemy = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new BlockPos(1, 2, 1));
            Mob vanilla = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(5, 2, 1));
            enemy.setNoAi(true);
            vanilla.setNoAi(true);

            data.reset(DifficultySetting.ENEMY_STRENGTH);
            float enemyBase = damageTaken(helper, level.damageSources().mobAttack(enemy));
            float vanillaBase = damageTaken(helper, level.damageSources().mobAttack(vanilla));
            data.set(DifficultySetting.ENEMY_STRENGTH, 3.0D);
            float enemyTripled = damageTaken(helper, level.damageSources().mobAttack(enemy));
            float vanillaAfter = damageTaken(helper, level.damageSources().mobAttack(vanilla));

            check(helper, enemyBase > 0.0F && Math.abs(enemyTripled - enemyBase * 3.0F) < EPS,
                    "寄生ゾンビの攻撃が 3 倍になっていない: 1 倍 " + enemyBase + " → 3 倍 " + enemyTripled);
            check(helper, vanillaBase > 0.0F && Math.abs(vanillaAfter - vanillaBase) < EPS,
                    "バニラのハスクの攻撃まで変わった: " + vanillaBase + " → " + vanillaAfter);
        } finally {
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    /** TD で壁・鉄線・灯りを削る量にも敵の強さが掛かる(ユーザー指定 2026-09-25「壁壊し・ターゲットの削りにも」)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void enemyStrengthScalesTdStrikes(GameTestHelper helper) {
        WorldDifficultyData data = WorldDifficultyData.get(helper.getLevel().getServer());
        Map<DifficultySetting, Double> saved = data.overrides();
        try {
            Mob arbora = helper.spawnWithNoFreeWill(ModEntities.ARBORA.get(), new BlockPos(3, 2, 3));
            arbora.setNoAi(true);
            data.reset(DifficultySetting.ENEMY_STRENGTH);
            float base = TdTestAccess.wallDamage(arbora);
            data.set(DifficultySetting.ENEMY_STRENGTH, 2.5D);
            float scaled = TdTestAccess.wallDamage(arbora);
            check(helper, base > 0.0F && Math.abs(scaled - base * 2.5F) < EPS,
                    "アルボラが壁を叩く量が 2.5 倍になっていない: " + base + " → " + scaled);
        } finally {
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    /** 地割れの日と TD の上限: ワールドの値があればそれ、無ければ design の値。範囲外は収める。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void quakeDayAndTdCapOverrides(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        Map<DifficultySetting, Double> saved = data.overrides();
        try {
            data.reset(DifficultySetting.QUAKE_DAY);
            data.reset(DifficultySetting.TD_MAX_MOBS);
            check(helper, WorldDifficultyData.phase1StartTicks(server) == DesignData.phases().startWorldAgeTicks(),
                    "ワールドの値が無いのに startWorldAgeTicks と違う: " + WorldDifficultyData.phase1StartTicks(server));
            check(helper, WorldDifficultyData.tdConcurrentCap(server, DesignData.td()) == DesignData.td().concurrentCap(),
                    "ワールドの値が無いのに concurrentCap と違う");

            data.set(DifficultySetting.QUAKE_DAY, 5.0D);
            check(helper, WorldDifficultyData.phase1StartTicks(server) == 5L * 24000L,
                    "5 日が 120000 tick にならない: " + WorldDifficultyData.phase1StartTicks(server));
            data.set(DifficultySetting.QUAKE_DAY, 0.5D);
            check(helper, WorldDifficultyData.phase1StartTicks(server) == 12000L, "半日が 12000 tick にならない");

            data.set(DifficultySetting.TD_MAX_MOBS, 42.0D);
            check(helper, WorldDifficultyData.tdConcurrentCap(server, DesignData.td()) == 42, "TD の上限 42 が効かない");
            data.set(DifficultySetting.TD_MAX_MOBS, 5000.0D);
            check(helper, WorldDifficultyData.tdConcurrentCap(server, DesignData.td()) == 2000,
                    "範囲外の TD の上限が 2000 に収まらない: " + WorldDifficultyData.tdConcurrentCap(server, DesignData.td()));
        } finally {
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    /**
     * 地割れに要る実績の数(2026-10-10): ワールドの値が無ければ phases.json の startAdvancements、あればその数
     * (0 = 日数だけ)。範囲外は 0〜1000 に収め、整数に丸める。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void quakeAdvancementsOverride(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        Map<DifficultySetting, Double> saved = data.overrides();
        try {
            data.reset(DifficultySetting.QUAKE_ADVANCEMENTS);
            check(helper, WorldDifficultyData.phase1Advancements(server) == DesignData.phases().startAdvancements(),
                    "ワールドの値が無いのに startAdvancements と違う: " + WorldDifficultyData.phase1Advancements(server));
            data.set(DifficultySetting.QUAKE_ADVANCEMENTS, 2.0D);
            check(helper, WorldDifficultyData.phase1Advancements(server) == 2, "実績 2 種類が効かない");
            data.set(DifficultySetting.QUAKE_ADVANCEMENTS, 0.0D);
            check(helper, WorldDifficultyData.phase1Advancements(server) == 0, "実績 0(日数だけ)が効かない");
            data.set(DifficultySetting.QUAKE_ADVANCEMENTS, 5000.0D);
            check(helper, WorldDifficultyData.phase1Advancements(server) == 1000,
                    "範囲外の実績の数が 1000 に収まらない: " + WorldDifficultyData.phase1Advancements(server));
            check(helper, Double.isNaN(DifficultySetting.QUAKE_ADVANCEMENTS.parse("2.5")),
                    "実績の数に小数が通った");
        } finally {
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    /**
     * 読み込まれていない範囲への侵食(2026-10-10): 既定はオンで、どのチャンクも止めない。オフにすると、
     * 読み込まれていない遠くのチャンクだけ台帳の広がりから外れ、読み込まれているテスト場のチャンクは外れない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void unloadedSpreadToggle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        Map<DifficultySetting, Double> saved = data.overrides();
        net.minecraft.world.level.ChunkPos here = new net.minecraft.world.level.ChunkPos(helper.absolutePos(BlockPos.ZERO));
        net.minecraft.world.level.ChunkPos far = new net.minecraft.world.level.ChunkPos(here.x + 100_000, here.z - 100_000);
        try {
            data.reset(DifficultySetting.UNLOADED_SPREAD);
            check(helper, WorldDifficultyData.unloadedSpread(server), "既定がオンでない");
            check(helper, level.getChunkSource().getChunkNow(far.x, far.z) == null, "遠くのチャンクが読み込まれている");
            check(helper, !com.yayuy.overgrownvegetation.corruption.LedgerSpreadTestHooks.unloadedBlocked(level, far),
                    "オンなのに読み込まれていないチャンクが止められた");

            data.set(DifficultySetting.UNLOADED_SPREAD, 0.0D);
            check(helper, !WorldDifficultyData.unloadedSpread(server), "オフにしたのにオンのまま");
            check(helper, com.yayuy.overgrownvegetation.corruption.LedgerSpreadTestHooks.unloadedBlocked(level, far),
                    "オフなのに読み込まれていないチャンクが止められない");
            check(helper, !com.yayuy.overgrownvegetation.corruption.LedgerSpreadTestHooks.unloadedBlocked(level, here),
                    "オフでも読み込まれているチャンクまで止められた");
        } finally {
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    /** {@code /ov corruption speed} が、このワールドの値を書き換える({@code /ov difficulty} は 2026-10-10 に削除。ほかの項目は画面から)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void commandsEditWorldValues(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        Map<DifficultySetting, Double> saved = data.overrides();
        try {
            CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            data.replaceAll(Map.of());

            run(server, source, "ov corruption speed 3");
            check(helper, data.override(DifficultySetting.CORRUPTION_SPEED).orElse(-1.0D) == 3.0D,
                    "/ov corruption speed 3 が効かない: " + data.overrides());
        } finally {
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 道具

    /** 新しいブタに 1 ダメージの攻撃を当て、減った体力を返す(ブタはすぐ消す)。 */
    private static float damageTaken(GameTestHelper helper, DamageSource source) {
        Mob victim = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 5));
        victim.setNoAi(true);
        float before = victim.getHealth();
        victim.hurt(source, 1.0F);
        float taken = before - victim.getHealth();
        victim.discard();
        return taken;
    }

    private static void run(MinecraftServer server, CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    private static void deleteQuietly(Path folder) {
        if (folder == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // 一時フォルダが残るだけ
        }
    }

    private WorldDifficultyGameTests() {
    }
}
