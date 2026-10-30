package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.effect.SporeInfectionTracker;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * プレイヤーの胞子感染 10 段階(2026-10-14 ユーザー指定で作り直し)。
 * <ul>
 *     <li>10 分ごとに 1 段上がり、症状(空腹・被検知距離・防御力)と最大体力 −(段階 − 1) が表どおり({@link #stagesClimbEveryTenMinutes})。</li>
 *     <li>防護マスク: かかっていなければかからない・攻撃でも進まない({@link #maskBlocksInfection})。</li>
 *     <li>牛乳では治らない({@link #milkDoesNotCure})。</li>
 *     <li>10 秒ごとに経験値を吸われ、進みは判定のたびに足される({@link #xpDrainsWhileInfected})。</li>
 *     <li>Mob は今までどおり(胞子感染1 = 1 分・防御力 −20%。{@link #mobsKeepOldInfection})。</li>
 * </ul>
 * 偽プレイヤーはダメージを受けない・tick しないので、段階が上がるときの 1 ダメージと感染10 の先の死はテストクライアントで確かめる。
 * 期待値は設計値({@code effects.json} の {@code sporeInfection.player})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeInfectionStageGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SporeInfectionStageGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "spore_stages";
    private static final ResourceLocation ARMOR_ID = OvergrownVegetation.id("effect.spore_infection.armor");

    /** テストごとに別の偽プレイヤー(偽プレイヤーは名前ごとに使い回されるので、状態を混ぜない)。 */
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
        SporeInfectionEffects.setPlayerStage(player, 0);
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.setExperienceLevels(0);
        player.experienceProgress = 0.0F;
        player.totalExperience = 0;
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void stagesClimbEveryTenMinutes(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_climb");
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        try {
            double baseMax = player.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
            if (!SporeInfectionEffects.startPlayer(player) || SporeInfectionEffects.stageOf(player) != 1) {
                helper.fail("胞子感染1 にかからない");
                return;
            }
            if (player.getMaxHealth() != baseMax) {
                helper.fail("感染1 で最大体力が減った: " + player.getMaxHealth());
                return;
            }
            SporeInfectionEffects.advance(player, spec.stageTicks() - 1);
            if (SporeInfectionEffects.stageOf(player) != 1) {
                helper.fail("10 分たつ前に上がった");
                return;
            }
            SporeInfectionEffects.advance(player, 1);
            if (SporeInfectionEffects.stageOf(player) != 2 || SporeInfectionEffects.progressOf(player) != 0) {
                helper.fail("10 分で感染2 にならない: 段階 " + SporeInfectionEffects.stageOf(player));
                return;
            }
            SporeInfectionEffects.advance(player, spec.stageTicks() * 8);
            int stage = SporeInfectionEffects.stageOf(player);
            if (stage != spec.maxStage()) {
                helper.fail("80 分足しても感染10 にならない: " + stage);
                return;
            }
            double expectMax = baseMax - spec.maxHealthPerStage() * (stage - 1);
            if (Math.abs(player.getMaxHealth() - expectMax) > 1.0E-4D) {
                helper.fail("感染10 の最大体力 " + player.getMaxHealth() + "(期待 " + expectMax + ")");
                return;
            }
            EffectTable.StageSpec ten = spec.stage(stage);
            AttributeModifier armor = player.getAttribute(Attributes.ARMOR).getModifier(ARMOR_ID);
            if (armor == null || Math.abs(armor.amount() + ten.armorReduction()) > 1.0E-6D) {
                helper.fail("感染10 の防御力の低下が " + ten.armorReduction() + " でない: " + armor);
                return;
            }
            if (SporeInfectionDetection.bonusFor(player) != ten.detectionRangeBonus()) {
                helper.fail("感染10 の被検知距離 " + SporeInfectionDetection.bonusFor(player));
                return;
            }
            if (ten.hunger() != 5 || spec.stage(1).hunger() != 1) {
                helper.fail("空腹の段が表と違う: 感染1 = " + spec.stage(1).hunger() + " / 感染10 = " + ten.hunger());
                return;
            }
            SporeInfectionEffects.setPlayerStage(player, 0);
            if (player.getMaxHealth() != baseMax || player.getAttribute(Attributes.ARMOR).getModifier(ARMOR_ID) != null) {
                helper.fail("治ったのに最大体力・防御力が戻らない");
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void maskBlocksInfection(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_mask");
        try {
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.PROTECTIVE_MASK.get()));
            for (int i = 0; i < 50; i++) {
                SporeInfectionEffects.plantAttack(player, 0);
            }
            if (SporeInfectionEffects.startPlayer(player) || SporeInfectionEffects.stageOf(player) != 0) {
                helper.fail("防護マスクをかぶっているのにかかった");
                return;
            }
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            SporeInfectionEffects.startPlayer(player);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.PROTECTIVE_MASK.get()));
            for (int i = 0; i < 200; i++) {
                SporeInfectionEffects.plantAttack(player, 0);
            }
            if (SporeInfectionEffects.progressOf(player) != 0) {
                helper.fail("マスクをかぶっているのに攻撃で進んだ: " + SporeInfectionEffects.progressOf(player));
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void milkDoesNotCure(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_milk");
        try {
            SporeInfectionEffects.setPlayerStage(player, 3);
            player.removeEffectsCuredBy(EffectCures.MILK);
            if (SporeInfectionEffects.stageOf(player) != 3) {
                helper.fail("牛乳で治った(段階 " + SporeInfectionEffects.stageOf(player) + ")");
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void xpDrainsWhileInfected(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_xp");
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        try {
            player.giveExperiencePoints(100);
            SporeInfectionTracker.tickPlayer(helper.getLevel(), player, spec.xpDrainIntervalTicks());
            if (player.totalExperience != 100) {
                helper.fail("感染していないのに経験値が減った: " + player.totalExperience);
                return;
            }
            SporeInfectionEffects.startPlayer(player);
            SporeInfectionTracker.tickPlayer(helper.getLevel(), player, spec.xpDrainIntervalTicks());
            if (player.totalExperience != 100 - spec.xpDrainPoints()) {
                helper.fail("10 秒で経験値が " + spec.xpDrainPoints() + " 減らない: " + player.totalExperience);
                return;
            }
            // テスト場は濃い胞子でも過繁茂のバイオームでもないので、進みは素の速さ。
            if (SporeInfectionEffects.progressOf(player) != spec.xpDrainIntervalTicks()) {
                helper.fail("進みが判定の間隔ぶん足されない: " + SporeInfectionEffects.progressOf(player));
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void mobsKeepOldInfection(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        EffectTable.LevelSpec spec = DesignData.effects().level(0);
        SporeInfectionEffects.infect(cow, 0);
        var effect = cow.getEffect(ModMobEffects.SPORE_INFECTION);
        if (effect == null || effect.getAmplifier() != 0 || effect.getDuration() != spec.mobDurationTicks()) {
            helper.fail("牛の胞子感染1 が今までどおりでない: " + effect);
            return;
        }
        AttributeModifier armor = cow.getAttribute(Attributes.ARMOR).getModifier(ARMOR_ID);
        if (spec.armorReduction() > 0.0D && (armor == null || Math.abs(armor.amount() + spec.armorReduction()) > 1.0E-6D)) {
            helper.fail("牛の防御力の低下が " + spec.armorReduction() + " でない: " + armor);
            return;
        }
        helper.succeed();
    }

    private SporeInfectionStageGameTests() {
    }
}
