package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import com.yayuy.overgrownvegetation.difficulty.MobStrength;
import com.yayuy.overgrownvegetation.difficulty.ModDifficulty;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultySync;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.network.WorldDifficultySyncPayload;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * MOD の設定ページ({@link WorldDifficultyData}・{@link DifficultyValues}・{@link ModDifficulty}・{@link MobStrength}、2026-11-19)のテスト。
 *
 * <p><b>ワールドの値は世界に 1 つ</b>なので、書き換えるテストは自分たちだけのバッチ({@value #BATCH})に入れ、
 * どれも<b>始めの 1 tick の中で</b>書き換え・測定・元に戻す(戻し忘れると、後のバッチで Mob のダメージなどを見るテストが訳の分からない落ち方をする)。
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
    private static final double EPS = 1.0E-3D;

    /** 入力の読み方と表示: % の項目は「150」を 1.5 で持つ。範囲外・数字でない・個数に小数は NaN。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void settingParseAndFormat(GameTestHelper helper) {
        DifficultySetting hp = DifficultySetting.MOB_HEALTH;
        DifficultySetting speed = DifficultySetting.CORRUPTION_SPEED;
        DifficultySetting adv = DifficultySetting.QUAKE_ADVANCEMENTS;
        check(helper, hp.parse("150") == 1.5D && hp.parse("70") == 0.7D && hp.parse("25600") == 256.0D,
                "% が読めない: " + hp.parse("150") + " / " + hp.parse("70"));
        check(helper, Double.isNaN(hp.parse("25700")) && Double.isNaN(hp.parse("0")) && Double.isNaN(hp.parse("abc")),
                "範囲外・数字でない % を受け付けた");
        check(helper, hp.format(1.5D).equals("150") && hp.format(0.7D).equals("70") && hp.format(3.0D).equals("300"),
                "% の表示: " + hp.format(1.5D) + " / " + hp.format(0.7D));
        check(helper, speed.format(1.0D).equals("1.0") && speed.parse("2.5") == 2.5D, "倍率の表示・読み");
        check(helper, Double.isNaN(adv.parse("2.5")) && adv.parse("6") == 6.0D, "実績の数は整数だけのはず");
        helper.succeed();
    }

    /**
     * MOD の難易度の段(11-19 ユーザー決定): 段を選ぶと段の項目が揃い、詳細が消える。1 つでも違えばカスタム。
     * 全体の % を動かすと、Mob ごとの詳細のその項目だけが消える。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void tierAndCustom(GameTestHelper helper) {
        DifficultyValues v = new DifficultyValues();
        check(helper, v.tier() == ModDifficulty.NORMAL, "何も無いのに Normal でない: " + v.tier());
        v.set(DifficultySetting.CORRUPTION_SPEED, 5.0D);
        check(helper, v.tier() == ModDifficulty.NORMAL, "段で変わらない項目でカスタムになった");

        v.applyTier(ModDifficulty.HARD);
        for (DifficultySetting s : DifficultySetting.values()) {
            if (s.tiered()) check(helper, Math.abs(v.valueOr(s, 1.0D) - 1.5D) < EPS, "Hard で " + s.id() + " が 150% でない");
        }
        check(helper, v.tier() == ModDifficulty.HARD, "Hard を選んだのに " + v.tier());
        check(helper, v.override(DifficultySetting.CORRUPTION_SPEED).orElse(-1) == 5.0D, "段を選んだら段でない項目まで変わった");

        v.applyTier(ModDifficulty.NIGHTMARE);
        check(helper, v.tier() == ModDifficulty.NIGHTMARE && Math.abs(v.valueOr(DifficultySetting.INFECTION_RATE, 1) - 3.0D) < EPS,
                "Nightmare が 300% にならない");
        v.applyTier(ModDifficulty.EASY);
        check(helper, v.tier() == ModDifficulty.EASY && Math.abs(v.valueOr(DifficultySetting.FOREST_CAP, 1) - 0.7D) < EPS,
                "Easy が 70% にならない");

        v.set(DifficultySetting.EXPOSURE_RATE, 1.2D);
        check(helper, v.tier() == ModDifficulty.CUSTOM, "1 つ変えたのにカスタムにならない: " + v.tier());
        v.applyTier(ModDifficulty.NORMAL);
        check(helper, v.tier() == ModDifficulty.NORMAL && v.override(DifficultySetting.EXPOSURE_RATE).isPresent() == false,
                "Normal に戻すと段の項目の値が消えるはず");

        String zombie = OvergrownVegetation.id("parasitized_zombie").toString();
        v.setMob(zombie, DifficultySetting.MOB_HEALTH, 2.0D);
        v.setMob(zombie, DifficultySetting.MOB_ATTACK, 0.5D);
        check(helper, v.tier() == ModDifficulty.CUSTOM, "Mob ごとの詳細があるのにカスタムでない");
        check(helper, v.mobValue(zombie, DifficultySetting.MOB_HEALTH) == 2.0D
                && v.mobValue("overgrown_vegetation:other", DifficultySetting.MOB_HEALTH) == 1.0D, "詳細と全体の % の使い分け");

        v.setGlobalMobStat(DifficultySetting.MOB_HEALTH, 1.3D);
        check(helper, v.mobValue(zombie, DifficultySetting.MOB_HEALTH) == 1.3D, "全体の % を動かしたのに詳細の体力が残った");
        check(helper, v.mobValue(zombie, DifficultySetting.MOB_ATTACK) == 0.5D, "全体の体力を動かしたら攻撃の詳細まで消えた");

        v.applyTier(ModDifficulty.HARD);
        check(helper, v.mobOverrides().isEmpty(), "段を選んだのに詳細が残った");
        helper.succeed();
    }

    /** 保存と復元(詳細も)・通信の往復・前の「敵の強さ」の写し・ワールド作成画面が書くファイルの読み込み。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void saveLoadAndCreateWorldFile(GameTestHelper helper) {
        DifficultyValues v = new DifficultyValues();
        v.applyTier(ModDifficulty.HARD);
        v.set(DifficultySetting.QUAKE_DAY, 5.0D);
        v.setMob("overgrown_vegetation:arbora", DifficultySetting.MOB_DEFENSE, 4.0D);

        DifficultyValues loaded = DifficultyValues.load(v.save());
        check(helper, loaded.equals(v), "保存して読むと変わる: " + v + " → " + loaded);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        v.write(buf);
        check(helper, DifficultyValues.read(buf).equals(v), "通信の往復で変わる");

        CompoundTag old = new CompoundTag();
        CompoundTag oldValues = new CompoundTag();
        oldValues.putDouble("enemy_strength", 2.5D);
        oldValues.putDouble("td_max_mobs", 120.0D);
        old.put("overrides", oldValues);
        DifficultyValues migrated = DifficultyValues.load(old);
        check(helper, migrated.override(DifficultySetting.MOB_ATTACK).orElse(-1) == 2.5D && migrated.overrides().size() == 1,
                "前の敵の強さが Mob の攻撃力へ写らない / 消した項目が残った: " + migrated);

        ServerLevel level = helper.getLevel();
        Path folder = null;
        try {
            folder = Files.createTempDirectory("ov_world_difficulty");
            WorldDifficultyData.writeInitial(folder, v);
            DimensionDataStorage storage = new DimensionDataStorage(folder.resolve("data").toFile(),
                    level.getServer().getFixerUpper(), level.registryAccess());
            WorldDifficultyData data = WorldDifficultyData.readFrom(storage);
            check(helper, data.snapshot().equals(v), "作成画面のファイルが読めない: " + data.snapshot());
            check(helper, data.tier() == ModDifficulty.CUSTOM, "詳細があるのにカスタムでない");
        } catch (IOException e) {
            helper.fail("一時フォルダに書けない: " + e);
        } finally {
            deleteQuietly(folder);
        }

        // サーバーの返事(既定値と Mob の一覧)も往復できる
        WorldDifficultySyncPayload payload = WorldDifficultySync.payloadFor(level.getServer(), true);
        check(helper, !payload.mobs().isEmpty() && payload.mobs().contains(OvergrownVegetation.id("parasitized_zombie").toString()),
                "Mob の一覧が空か、寄生ゾンビが入っていない: " + payload.mobs().size());
        FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
        WorldDifficultySyncPayload.STREAM_CODEC.encode(buf2, payload);
        WorldDifficultySyncPayload back = WorldDifficultySyncPayload.STREAM_CODEC.decode(buf2);
        check(helper, back.mobs().equals(payload.mobs()) && back.defaults().equals(payload.defaults())
                && back.values().equals(payload.values()), "返事の往復で変わる");
        helper.succeed();
    }

    /**
     * Mob の強さ: 体力・防御力は属性の修飾、攻撃力は受けるダメージ。詳細が全体の % より勝つ。今いる Mob にも効き、体力の割合を保つ。
     * バニラの Mob(ハスク)には掛からない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void mobStrengthAppliesToModMobsOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        DifficultyValues saved = data.snapshot();
        try {
            data.replaceAll(new DifficultyValues());
            Mob enemy = helper.spawnWithNoFreeWill(ModG1.PARASITIZED_ZOMBIE.get(), new BlockPos(1, 2, 1));
            Mob vanilla = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(5, 2, 1));
            enemy.setNoAi(true);
            vanilla.setNoAi(true);
            double baseMax = enemy.getMaxHealth();
            double baseArmor = enemy.getAttributeValue(Attributes.ARMOR);
            float enemyBase = damageTaken(helper, level.damageSources().mobAttack(enemy));
            float vanillaBase = damageTaken(helper, level.damageSources().mobAttack(vanilla));

            enemy.setHealth((float) (baseMax / 2.0D));
            DifficultyValues next = new DifficultyValues();
            next.applyTier(ModDifficulty.NIGHTMARE);
            WorldDifficultySync.applyAll(server, next, new ArrayList<>());

            check(helper, Math.abs(enemy.getMaxHealth() - Math.min(1024.0D, baseMax * 3.0D)) < EPS,
                    "Nightmare で今いる寄生ゾンビの最大体力が 3 倍にならない: " + baseMax + " → " + enemy.getMaxHealth());
            check(helper, Math.abs(enemy.getHealth() / enemy.getMaxHealth() - 0.5D) < 0.01D,
                    "体力の割合が保たれない: " + enemy.getHealth() + " / " + enemy.getMaxHealth());
            check(helper, Math.abs(enemy.getAttributeValue(Attributes.ARMOR) - Math.min(30.0D, baseArmor * 3.0D)) < EPS,
                    "防御力が 3 倍にならない: " + baseArmor + " → " + enemy.getAttributeValue(Attributes.ARMOR));
            float enemyTripled = damageTaken(helper, level.damageSources().mobAttack(enemy));
            float vanillaAfter = damageTaken(helper, level.damageSources().mobAttack(vanilla));
            check(helper, enemyBase > 0.0F && Math.abs(enemyTripled - enemyBase * 3.0F) < EPS,
                    "寄生ゾンビの攻撃が 3 倍にならない: " + enemyBase + " → " + enemyTripled);
            check(helper, Math.abs(vanillaAfter - vanillaBase) < EPS && vanilla.getMaxHealth() == EntityType.HUSK.create(level).getMaxHealth(),
                    "バニラのハスクまで変わった");

            // 詳細(寄生ゾンビだけ体力 50%)が全体の 300% より勝つ
            String id = MobStrength.idOf(enemy);
            next.setMob(id, DifficultySetting.MOB_HEALTH, 0.5D);
            WorldDifficultySync.applyAll(server, next, new ArrayList<>());
            check(helper, Math.abs(enemy.getMaxHealth() - baseMax * 0.5D) < EPS,
                    "詳細の 50% が効かない: " + enemy.getMaxHealth());

            // 新しく湧いた Mob は満タンで湧く
            Mob fresh = helper.spawnWithNoFreeWill(ModG1.PARASITIZED_ZOMBIE.get(), new BlockPos(3, 2, 3));
            check(helper, Math.abs(fresh.getMaxHealth() - baseMax * 0.5D) < EPS && fresh.getHealth() == fresh.getMaxHealth(),
                    "新しく湧いた Mob に詳細が効かない・満タンでない: " + fresh.getHealth() + " / " + fresh.getMaxHealth());

            // Normal に戻すと修飾が外れる
            WorldDifficultySync.applyAll(server, new DifficultyValues(), new ArrayList<>());
            check(helper, MobStrength.appliedMultiplier(enemy, DifficultySetting.MOB_HEALTH) == 1.0D
                    && Math.abs(enemy.getMaxHealth() - baseMax) < EPS, "Normal に戻しても修飾が残った");
        } finally {
            WorldDifficultySync.applyAll(server, saved, new ArrayList<>());
        }
        helper.succeed();
    }

    /** 段で変わる倍率が効かせる所で読まれる: 森の予算の上限・暴露量・感染の進み・大きな襲撃の上限・フェーズのポイント。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void tieredMultipliersReachTheirReadPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        DifficultyValues saved = data.snapshot();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_settings_rate".getBytes(StandardCharsets.UTF_8)), "ov_settings_rate"));
        try {
            data.replaceAll(new DifficultyValues());
            double cap1 = ForestDirector.cap(level);
            SporeInfectionEffects.setPlayerStage(player, 0);
            SporeExposureTracker.setDose(player, 0.0D, 0L);
            SporeExposureTracker.addExposure(player, 10.0D, false);
            double dose1 = SporeExposureTracker.dose(player);

            data.applyTier(ModDifficulty.HARD);
            check(helper, WorldDifficultyData.phasePointMultiplier(server) == 1.5D && WorldDifficultyData.forestIncome(server) == 1.5D,
                    "Hard でフェーズ・森の生産量の倍率が 150% でない");
            double cap15 = ForestDirector.cap(level);
            check(helper, cap1 <= 0.0D || Math.abs(cap15 / cap1 - 1.5D) < 1.0E-6D, "森の予算の上限が 1.5 倍にならない: " + cap1 + " → " + cap15);

            SporeExposureTracker.setDose(player, 0.0D, 0L);
            SporeExposureTracker.addExposure(player, 10.0D, false);
            double dose15 = SporeExposureTracker.dose(player);
            check(helper, dose1 > 0.0D && Math.abs(dose15 - dose1 * 1.5D) < EPS, "暴露量が 1.5 倍たまらない: " + dose1 + " → " + dose15);

            // 感染の進み: 段 1 で同じ量を足すと、進みが 1.5 倍
            SporeExposureTracker.setDose(player, 0.0D, 0L);
            SporeInfectionEffects.startPlayer(player);
            int before = SporeInfectionEffects.progressOf(player);
            SporeInfectionEffects.advance(player, 100.0D);
            int after = SporeInfectionEffects.progressOf(player);
            check(helper, SporeInfectionEffects.stageOf(player) == 1 && after - before == 150,
                    "感染の進みが 1.5 倍にならない: " + before + " → " + after);

            data.set(DifficultySetting.LARGE_RAID_MAX_ALIVE, 77.0D);
            check(helper, LargeRaids.maxAlive() == 77, "大きな襲撃の上限 77 が効かない: " + LargeRaids.maxAlive());
            data.reset(DifficultySetting.LARGE_RAID_MAX_ALIVE);
            check(helper, LargeRaids.maxAlive() == LargeRaids.configuredMaxAlive(), "ワールドの値が無いのに設定の値と違う");

            data.reset(DifficultySetting.QUAKE_DAY);
            check(helper, WorldDifficultyData.phase1StartTicks(server) == DesignData.phases().startWorldAgeTicks(), "始まりの猶予の既定");
            data.set(DifficultySetting.QUAKE_DAY, 0.5D);
            check(helper, WorldDifficultyData.phase1StartTicks(server) == 12000L, "半日が 12000 tick にならない");
        } finally {
            SporeInfectionEffects.setPlayerStage(player, 0);
            SporeExposureTracker.setDose(player, 0.0D, 0L);
            data.replaceAll(saved);
        }
        helper.succeed();
    }

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
        }
    }

    private WorldDifficultyGameTests() {
    }
}
