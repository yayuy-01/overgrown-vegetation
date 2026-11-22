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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

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
            if (s.tiered()) check(helper, Math.abs(v.valueOr(s, s.tierValue(ModDifficulty.NORMAL)) - s.tierValue(ModDifficulty.HARD)) < EPS,
                    "Hard で " + s.id() + " が段の値でない");
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
            // もとの防御・強靭が 0 だと × 3 も 0 で確かめにならないので、0 でない値を置く(上限 30・20 の内側)
            enemy.getAttribute(Attributes.ARMOR).setBaseValue(4.0D);
            enemy.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(2.0D);
            double baseMax = enemy.getMaxHealth();
            double baseArmor = enemy.getAttributeValue(Attributes.ARMOR);
            double baseTough = enemy.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
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
            check(helper, baseArmor == 4.0D && Math.abs(enemy.getAttributeValue(Attributes.ARMOR_TOUGHNESS) - baseTough * 3.0D) < EPS,
                    "強靭が 3 倍にならない: " + baseTough + " → " + enemy.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
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

            // 進化・入れ替えで出た Mob(ワールドに入る前に減っていた割合を写される)は満タンに戻らない
            Mob evolved = ModG1.PARASITIZED_ZOMBIE.get().create(level);
            BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
            evolved.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            evolved.setNoAi(true);
            evolved.setHealth(evolved.getMaxHealth() * 0.5F);
            level.addFreshEntity(evolved);
            check(helper, Math.abs(evolved.getHealth() / evolved.getMaxHealth() - 0.5F) < 0.01F,
                    "減っていた割合で入った Mob が満タンになった: " + evolved.getHealth() + " / " + evolved.getMaxHealth());
            evolved.discard();
            fresh.discard();

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

    /**
     * 第 2 便の項目(11-19): 既定値が設計データ・config と同じこと、変えると読む所で効くこと(フェーズの上限・汚染しない場所・襲撃の口・
     * 前触れ・湧く量・動物の感染・適応・疲労・地割れ・遠くの群れ)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void secondBatchReadPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        DifficultyValues saved = data.snapshot();
        try {
            data.replaceAll(new DifficultyValues());
            com.yayuy.overgrownvegetation.difficulty.RaidSettings.push(server);
            // 既定値
            // 合計の上限の既定は 3 つとも上限なし(11-20 夜「襲撃の大きさは貯めた予算だけで決める」)
            check(helper, data.value(DifficultySetting.RAID_MAX_TOTAL_SMALL) == 0.0D
                    && data.value(DifficultySetting.RAID_MAX_TOTAL_MEDIUM) == 0.0D && data.value(DifficultySetting.RAID_MAX_TOTAL_LARGE) == 0.0D
                    && com.yayuy.overgrownvegetation.director.RaidScales.maxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.SMALL, 0) <= 0
                    && com.yayuy.overgrownvegetation.director.RaidScales.maxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.MEDIUM, 0) <= 0
                    && data.value(DifficultySetting.RAID_MAX_ALIVE_MEDIUM) == 40.0D,
                    "襲撃の上限の既定が設計データと違う: 小 " + data.value(DifficultySetting.RAID_MAX_TOTAL_SMALL) + " 中 "
                            + data.value(DifficultySetting.RAID_MAX_TOTAL_MEDIUM) + " 大 " + data.value(DifficultySetting.RAID_MAX_TOTAL_LARGE));
            check(helper, data.value(DifficultySetting.REAL_CAP) == DesignData.perf().virtualRealCap
                    && data.value(DifficultySetting.VIRTUAL_CAP) == DesignData.perf().virtualCap
                    && data.value(DifficultySetting.PHASE_CAP) == com.yayuy.overgrownvegetation.data.PhaseTable.MAX_PHASE,
                    "重さ・フェーズの上限の既定が違う");
            check(helper, com.yayuy.overgrownvegetation.director.RaidScales.Knobs.raidsEnabled
                    && com.yayuy.overgrownvegetation.director.RaidScales.Knobs.intervalFactor == 1.0D
                    && com.yayuy.overgrownvegetation.director.RaidScales.maxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.MEDIUM, -9) <= 0,
                    "既定で襲撃の口が上限なしでない");

            // フェーズの上限: 切り離したフェーズの記録で、上限 2 なら F2 で止まり、上限を戻すと次の点で上がる
            com.yayuy.overgrownvegetation.data.PhaseTable pt = DesignData.phases();
            if (pt.autoAdvance(3) && pt.stageSpanPoints(2) != Long.MAX_VALUE) {
                com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.createDetachedForTests(e -> { });
                pd.setPhase(level, 2);
                data.set(DifficultySetting.PHASE_CAP, 2.0D);
                pd.addPointsUnscaledForTests(level, pt.stageSpanPoints(2) + 10);
                check(helper, pd.getPhase() == 2, "フェーズの上限 2 なのに F" + pd.getPhase() + " へ上がった");
                data.reset(DifficultySetting.PHASE_CAP);
                pd.addPointsUnscaledForTests(level, 1);
                check(helper, pd.getPhase() == 3, "上限を戻しても F3 へ上がらない: F" + pd.getPhase());
            }

            // 汚染しない場所: スポーン地点の石は、半径を付けると汚染できない
            BlockPos spawn = level.getSharedSpawnPos();
            net.minecraft.world.level.block.state.BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            boolean before = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, spawn, stone, false);
            data.set(DifficultySetting.SPAWN_SAFE_RADIUS, 32.0D);
            check(helper, before && !com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, spawn, stone, false)
                            && com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, spawn.offset(100, 0, 0), stone, false),
                    "汚染しない場所が効かない(前 " + before + ")");

            // 襲撃の口(RaidScales.Knobs へ写す)と前触れ
            double omen = com.yayuy.overgrownvegetation.director.RaidScales.omenDays(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.LARGE);
            data.set(DifficultySetting.RAIDS, 0.0D);
            data.set(DifficultySetting.RAID_FREQUENCY, 2.0D);
            data.set(DifficultySetting.RAID_COUNT_LARGE, 3.0D);
            data.set(DifficultySetting.RAID_MAX_TOTAL_MEDIUM, 0.0D);
            data.set(DifficultySetting.RAID_MAX_TOTAL_SMALL, 12.0D);
            data.set(DifficultySetting.RAID_OMEN, 0.0D);
            com.yayuy.overgrownvegetation.difficulty.RaidSettings.push(server);
            check(helper, !com.yayuy.overgrownvegetation.director.RaidScales.Knobs.raidsEnabled
                            && Math.abs(com.yayuy.overgrownvegetation.director.RaidScales.Knobs.intervalFactor - 0.5D) < EPS
                            && com.yayuy.overgrownvegetation.director.RaidScales.Knobs.countFactor[2] == 3.0D
                            && com.yayuy.overgrownvegetation.director.RaidScales.maxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.MEDIUM, 80) <= 0
                            && com.yayuy.overgrownvegetation.director.RaidScales.maxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.SMALL, 40) == 12,
                    "襲撃の項目が RaidScales.Knobs へ写らない");
            check(helper, omen > 0.0D && com.yayuy.overgrownvegetation.director.RaidScales.omenDays(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.LARGE) == 0.0D,
                    "前触れをオフにしても前触れの日数が 0 にならない: " + omen);

            // 湧く量
            int cap1 = 0, group = 0;
            for (int g = 1; g <= 10 && cap1 <= 0; g++) {
                data.reset(DifficultySetting.MOB_SPAWN_AMOUNT);
                cap1 = com.yayuy.overgrownvegetation.spawn.EnemySpawns.edgeCap(level, g);
                group = g;
            }
            data.set(DifficultySetting.MOB_SPAWN_AMOUNT, 2.0D);
            int cap2 = com.yayuy.overgrownvegetation.spawn.EnemySpawns.edgeCap(level, group);
            check(helper, cap1 <= 0 || Math.abs(cap2 - cap1 * 2) <= 1, "湧く量 200% で縁の上限が倍にならない: " + cap1 + " → " + cap2);

            // 動物の感染・適応・疲労・地割れ・遠くの群れ
            Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(2, 2, 2));
            DamageSource spores = com.yayuy.overgrownvegetation.registry.ModDamageTypes.sporeInfection(level);
            boolean infects = com.yayuy.overgrownvegetation.corpse.G1Infection.isInfectionDeath(pig, spores);
            double adaptCap = com.yayuy.overgrownvegetation.adaptation.Adaptation.cap(server);
            data.set(DifficultySetting.MOB_INFECTION, 0.0D);
            data.set(DifficultySetting.ADAPTATION, 0.0D);
            data.set(DifficultySetting.FATIGUE, 0.0D);
            data.set(DifficultySetting.QUAKE, 0.0D);
            data.set(DifficultySetting.VIRTUAL_HORDES, 0.0D);
            check(helper, infects && !com.yayuy.overgrownvegetation.corpse.G1Infection.isInfectionDeath(pig, spores),
                    "動物の感染をオフにしてもブタが感染の死になる");
            check(helper, adaptCap > 0.0D && com.yayuy.overgrownvegetation.adaptation.Adaptation.cap(server) == 0.0D, "適応をオフにしても上限が 0 にならない");
            check(helper, !com.yayuy.overgrownvegetation.survival.FatigueTracker.on(), "疲労をオフにしても働いている");
            check(helper, !com.yayuy.overgrownvegetation.quake.EarthquakeEvent.trigger(level, null), "地割れをオフにしても起きた");
            check(helper, !com.yayuy.overgrownvegetation.horde.VirtualHordes.enabled(), "遠くの群れをオフにしても使われる");
            pig.discard();
        } finally {
            data.replaceAll(saved);
            com.yayuy.overgrownvegetation.difficulty.RaidSettings.push(server);
        }
        helper.succeed();
    }

    /**
     * 第 3 便: 「死んでも感染が残る」は Nightmare だけオン(Nightmare の値でもオフならカスタム)、オンなら死んで復活したとき
     * 段階と進みが戻る(オフなら治る)。「押し返す道具の効き」は帯の回数 × %、杭の FE と換気機の詰まり ÷ %。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void thirdBatchKeepInfectionAndPushBack(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        // 段
        DifficultyValues v = new DifficultyValues();
        check(helper, DifficultySetting.KEEP_INFECTION.defaultValue() == 0.0D, "死んでも感染が残るの既定がオフでない");
        v.applyTier(ModDifficulty.HARD);
        check(helper, v.valueOr(DifficultySetting.KEEP_INFECTION, 0.0D) == 0.0D, "Hard で感染が残るがオンになった");
        v.applyTier(ModDifficulty.NIGHTMARE);
        check(helper, v.valueOr(DifficultySetting.KEEP_INFECTION, 0.0D) == 1.0D && v.tier() == ModDifficulty.NIGHTMARE,
                "Nightmare で感染が残るがオンにならない: " + v.tier());
        v.set(DifficultySetting.KEEP_INFECTION, 0.0D);
        check(helper, v.tier() == ModDifficulty.CUSTOM, "Nightmare の値で感染が残るをオフにしたのにカスタムでない");
        v.applyTier(ModDifficulty.NORMAL);
        check(helper, v.override(DifficultySetting.KEEP_INFECTION).isEmpty() && v.tier() == ModDifficulty.NORMAL,
                "Normal に戻しても感染が残るの値が残った");
        v.applyTier(ModDifficulty.HARD);
        v.set(DifficultySetting.KEEP_INFECTION, 1.0D);
        check(helper, v.tier() == ModDifficulty.CUSTOM, "Hard で感染が残るをオンにしたのにカスタムでない");

        WorldDifficultyData data = WorldDifficultyData.get(server);
        DifficultyValues saved = data.snapshot();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_settings_keep".getBytes(StandardCharsets.UTF_8)), "ov_settings_keep"));
        BlockPos bandRel = new BlockPos(1, 2, 1);
        BlockPos stakeRel = new BlockPos(5, 2, 5);
        try {
            data.replaceAll(new DifficultyValues());
            // オフ: 死んでも戻らない
            SporeInfectionEffects.setPlayerStage(player, 4);
            OvAttach.setData(player, com.yayuy.overgrownvegetation.registry.ModAttachments.SPORE_STAGE_TICKS, 123);
            DamageSource src = level.damageSources().generic();
            com.yayuy.overgrownvegetation.effect.InfectionOnDeath.onDeath(new net.minecraftforge.event.entity.living.LivingDeathEvent(player, src));
            SporeInfectionEffects.setPlayerStage(player, 0); // 新しい体の代わり(感染は写らない)
            com.yayuy.overgrownvegetation.effect.InfectionOnDeath.onRespawn(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(player, false));
            check(helper, SporeInfectionEffects.stageOf(player) == 0, "オフなのに死んだ後も感染が戻った");
            // オン: 段階と進みが戻る
            data.set(DifficultySetting.KEEP_INFECTION, 1.0D);
            SporeInfectionEffects.setPlayerStage(player, 4);
            OvAttach.setData(player, com.yayuy.overgrownvegetation.registry.ModAttachments.SPORE_STAGE_TICKS, 123);
            com.yayuy.overgrownvegetation.effect.InfectionOnDeath.onDeath(new net.minecraftforge.event.entity.living.LivingDeathEvent(player, src));
            SporeInfectionEffects.setPlayerStage(player, 0);
            com.yayuy.overgrownvegetation.effect.InfectionOnDeath.onRespawn(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(player, false));
            check(helper, SporeInfectionEffects.stageOf(player) == 4 && SporeInfectionEffects.progressOf(player) == 123,
                    "オンなのに死んだ後の感染が戻らない: 段階 " + SporeInfectionEffects.stageOf(player) + " 進み " + SporeInfectionEffects.progressOf(player));

            // 押し返す道具の効き: 換気機の詰まり ÷ %
            com.yayuy.overgrownvegetation.data.SporeFieldTable table = DesignData.sporeField();
            double per1 = com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity.loadPerUnit(table);
            data.set(DifficultySetting.PUSHBACK_EFFICIENCY, 2.0D);
            double per2 = com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity.loadPerUnit(table);
            check(helper, per1 > 0.0D && Math.abs(per2 - per1 / 2.0D) < 1.0E-9D, "効き 200% で換気機の詰まりが半分にならない: " + per1 + " → " + per2);
            // 帯: 回数 × %(50% なら 16 → 8 回)
            data.set(DifficultySetting.PUSHBACK_EFFICIENCY, 0.5D);
            int expectHits = (int) Math.max(1, Math.round(DesignData.pushBack().ring.bandHits * 0.5D));
            helper.setBlock(bandRel, com.yayuy.overgrownvegetation.registry.ModPushBack.ASH_LIME_BAND.get().defaultBlockState());
            java.lang.reflect.Method wear = com.yayuy.overgrownvegetation.corruption.pushback.AshLimeBandBlock.class
                    .getDeclaredMethod("wear", ServerLevel.class, BlockPos.class);
            wear.setAccessible(true);
            int n = 0;
            while (n < 40 && helper.getBlockState(bandRel).getBlock() instanceof com.yayuy.overgrownvegetation.corruption.pushback.AshLimeBandBlock) {
                wear.invoke(null, level, helper.absolutePos(bandRel));
                n++;
            }
            check(helper, n == expectHits, "効き 50% で帯の回数が違う: " + n + "(期待 " + expectHits + ")");
            // 杭: 止めるたびの FE ÷ %(200% なら半分)
            data.set(DifficultySetting.PUSHBACK_EFFICIENCY, 2.0D);
            helper.setBlock(stakeRel, com.yayuy.overgrownvegetation.registry.ModPushBack.LEY_STAKE.get().defaultBlockState());
            com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlockEntity stake =
                    (com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlockEntity) helper.getBlockEntity(stakeRel);
            stake.setEnergy(1000);
            com.yayuy.overgrownvegetation.corruption.pushback.RingWards.resetCooldownForTests(level);
            int e0 = stake.energy();
            boolean blocked = com.yayuy.overgrownvegetation.corruption.pushback.RingWards.blocks(level, helper.absolutePos(new BlockPos(5, 1, 5)));
            int expectPay = DesignData.pushBack().ring.stakeFePerBlock / 2;
            check(helper, blocked && e0 - stake.energy() == expectPay,
                    "効き 200% で杭の払う FE が半分にならない: " + (e0 - stake.energy()) + "(期待 " + expectPay + ")");
        } finally {
            SporeInfectionEffects.setPlayerStage(player, 0);
            helper.setBlock(bandRel, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            helper.setBlock(stakeRel, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            data.replaceAll(saved);
        }
        helper.succeed();
    }

    /** OP でもホストでもない人が「完了」を送っても、ワールドの値は変わらない(11-19: サーバーでは OP だけ)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void nonOperatorCannotApply(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldDifficultyData data = WorldDifficultyData.get(level.getServer());
        DifficultyValues saved = data.snapshot();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_settings_guest".getBytes(StandardCharsets.UTF_8)), "ov_settings_guest"));
        try {
            check(helper, !WorldDifficultySync.canEdit(player), "OP でもホストでもない人が変えられる扱いになっている");
            DifficultyValues next = new DifficultyValues();
            next.applyTier(ModDifficulty.NIGHTMARE);
            WorldDifficultySync.apply(player, next);
            check(helper, data.snapshot().equals(saved), "権限の無い人の送った値が書かれた: " + data.snapshot());
        } finally {
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
