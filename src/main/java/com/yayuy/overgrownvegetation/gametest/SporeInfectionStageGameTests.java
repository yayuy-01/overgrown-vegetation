package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * プレイヤーの胞子感染 10 段階(2026-10-14 ユーザー指定)と、11-04 の作り直し(暴露量 1500 で感染1・濃度に比例する加速・
 * フィルターの残る間は効く防護マスク)。濃度は渡す口({@link SporeExposureTracker#tickWith})を直に叩く。
 * 偽プレイヤーはダメージを受けない・tick しないので、段階が上がるときの 1 ダメージと感染10 の先の死はテストクライアントで確かめる。
 * 期待値は設計値({@code effects.json} の sporeInfection.player・{@code spore_field.json} の exposure)から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeInfectionStageGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterSporeInfectionStageGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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
        SporeExposureTracker.setDose(player, 0.0D, 0L);
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.setExperienceLevels(0);
        player.experienceProgress = 0.0F;
        player.totalExperience = 0;
    }

    /** 1 段の暴露量(stageDose)がたまるごとに 1 段(11-15)。症状(防御力・空腹)と最大体力 −(段階 − 1) が表どおり。治すと戻る。 */
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
            int stageDose = SporeInfectionEffects.stageDoseOf(spec, 1); // 段 1 → 2 の量
            SporeInfectionEffects.advance(player, stageDose - 1);
            if (SporeInfectionEffects.stageOf(player) != 1) {
                helper.fail("1 段の量がたまる前に上がった");
                return;
            }
            SporeInfectionEffects.advance(player, 1);
            if (SporeInfectionEffects.stageOf(player) != 2 || SporeInfectionEffects.progressOf(player) != 0) {
                helper.fail("1 段の量で感染2 にならない: 段階 " + SporeInfectionEffects.stageOf(player));
                return;
            }
            double toTen = 0.0D; // 段 2 → 10 の量の和(後の段ほど多い、11-15)
            for (int s = 2; s < spec.maxStage(); s++) toTen += SporeInfectionEffects.stageDoseOf(spec, s);
            SporeInfectionEffects.advance(player, toTen);
            int stage = SporeInfectionEffects.stageOf(player);
            if (stage != spec.maxStage()) {
                helper.fail("8 段ぶん足しても感染10 にならない: " + stage);
                return;
            }
            double expectMax = baseMax - spec.maxHealthPerStage() * (stage - 1);
            if (Math.abs(player.getMaxHealth() - expectMax) > 1.0E-4D) {
                helper.fail("感染10 の最大体力 " + player.getMaxHealth() + "(期待 " + expectMax + ")");
                return;
            }
            EffectTable.StageSpec ten = spec.stage(stage);
            AttributeModifier armor = com.yayuy.overgrownvegetation.port.entity.OvModifiers.get(player.getAttribute(Attributes.ARMOR), ARMOR_ID);
            if (armor == null || Math.abs(armor.getAmount() + ten.armorReduction()) > 1.0E-6D) {
                helper.fail("感染10 の防御力の低下が " + ten.armorReduction() + " でない: " + armor);
                return;
            }
            if (ten.hunger() != 5 || spec.stage(1).hunger() != 1) {
                helper.fail("空腹の段が表と違う: 感染1 = " + spec.stage(1).hunger() + " / 感染10 = " + ten.hunger());
                return;
            }
            SporeInfectionEffects.setPlayerStage(player, 0);
            if (player.getMaxHealth() != baseMax || com.yayuy.overgrownvegetation.port.entity.OvModifiers.get(player.getAttribute(Attributes.ARMOR), ARMOR_ID) != null) {
                helper.fail("治ったのに最大体力・防御力が戻らない");
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    /** 暴露量が 1500 %秒に届いたら感染1(11-04)。届く前はかからない。かかったら暴露量は 0 に戻る(仮決め)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void doseReachingInfectDoseInfects(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_dose");
        SporeFieldTable t = DesignData.sporeField();
        ServerLevel level = helper.getLevel();
        try {
            // 100% に (感染の量/100 − 1) 秒 = あと 100 %秒足りない
            int justShort = (int) Math.round((t.infectDose() / 100.0D - 1.0D) * 20.0D);
            SporeExposureTracker.tickWith(level, player, justShort, 100.0D);
            if (SporeInfectionEffects.stageOf(player) != 0) {
                helper.fail("感染の量に届く前にかかった: 暴露 " + SporeExposureTracker.dose(player));
                return;
            }
            SporeExposureTracker.tickWith(level, player, 20, 100.0D);
            SporeExposure ex = OvAttach.getData(player, ModAttachments.SPORE_EXPOSURE);
            if (SporeInfectionEffects.stageOf(player) != 1) {
                helper.fail("1500 %秒で感染1 にならない: 段階 " + SporeInfectionEffects.stageOf(player) + "・暴露 " + ex.dose);
                return;
            }
            if (ex.dose != 0.0D || ex.reachedCount != 1) {
                helper.fail("かかった後の暴露量 " + ex.dose + "・回数 " + ex.reachedCount + "(期待 0・1)");
                return;
            }
            // 感染中は積まない
            SporeExposureTracker.tickWith(level, player, 200, 100.0D);
            if (SporeExposureTracker.dose(player) != 0.0D) {
                helper.fail("感染中に暴露が積まれた: " + SporeExposureTracker.dose(player));
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    /**
     * 感染中の進み(11-15「混ぜた形」): 体の中で育つ分(growPerSecond)+ 息で浴びた胞子(濃度 5% 以上ならその濃度 × 秒)。
     * 下限未満の濃度は育つ分だけ。場所の倍率は無い。傷の暴露も段の進みへ積む(感染中も積む)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void progressIsDosePlusSlowGrowth(GameTestHelper helper) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        SporeFieldTable t = DesignData.sporeField();
        FakePlayer player = player(helper, "ov_spore_stage_rate");
        try {
            SporeInfectionEffects.startPlayer(player);
            // 濃度 50% に 100 秒: 育つ分 + 50 × 100
            SporeExposureTracker.tickWith(helper.getLevel(), player, 2000, 50.0D);
            int expect = (int) Math.floor((spec.growPerSecond(1) + 50.0D) * 100.0D + 1.0E-6D);
            if (Math.abs(SporeInfectionEffects.progressOf(player) - expect) > 1) {
                helper.fail("50% で 100 秒の進み " + SporeInfectionEffects.progressOf(player) + "(期待 " + expect + ")");
                return;
            }
            // 下限未満の濃度に 100 秒: 育つ分だけ
            SporeInfectionEffects.setPlayerStage(player, 1);
            OvAttach.getData(player, ModAttachments.SPORE_EXPOSURE).rateCarry = 0.0D;
            SporeExposureTracker.tickWith(helper.getLevel(), player, 2000, t.exposureMinPercent() - 0.5D);
            int grown = (int) Math.floor(spec.growPerSecond(1) * 100.0D + 1.0E-6D);
            if (Math.abs(SporeInfectionEffects.progressOf(player) - grown) > 1) {
                helper.fail("下限未満で 100 秒の進み " + SporeInfectionEffects.progressOf(player) + "(期待 育つ分だけ " + grown + ")");
                return;
            }
            // 傷の暴露も感染中に積む(前の「感染中は積まない」は取り消し)
            int before = SporeInfectionEffects.progressOf(player);
            com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.addWoundDose(player, 4.0F);
            double wound = t.mobInfection().woundDosePlayerPerDamage() * 4.0D;
            if (Math.abs(SporeInfectionEffects.progressOf(player) - before - wound) > 1) {
                helper.fail("感染中の傷で段の進みが " + wound + " 進まない: " + (SporeInfectionEffects.progressOf(player) - before));
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    /**
     * 防護マスク: フィルターの残る間は暴露を積まず、毎秒 濃度 × 秒 ずつ減る(5% 未満では減らない)。0 になっても外れず、効かなくなるだけ。
     * 効いている間は息で浴びる分を止める(感染中の進みも)。傷(攻撃の抽選)は止めない(11-15)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void maskFilterWearsAndBlocks(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_mask");
        SporeFieldTable t = DesignData.sporeField();
        ServerLevel level = helper.getLevel();
        try {
            ItemStack mask = new ItemStack(ModItems.PROTECTIVE_MASK.get());
            player.setItemSlot(EquipmentSlot.HEAD, mask);
            ItemStack worn = player.getItemBySlot(EquipmentSlot.HEAD);
            for (int i = 0; i < 10; i++) {
                SporeExposureTracker.tickWith(level, player, 20, 50.0D);
            }
            if (SporeExposureTracker.dose(player) != 0.0D) {
                helper.fail("マスクをかぶっているのに積まれた: " + SporeExposureTracker.dose(player));
                return;
            }
            if (ProtectiveMaskItem.used(worn) != 500) {
                helper.fail("50% に 10 秒でフィルターが 500 減らない: " + ProtectiveMaskItem.used(worn));
                return;
            }
            SporeExposureTracker.tickWith(level, player, 200, t.exposureMinPercent() - 0.5D);
            if (ProtectiveMaskItem.used(worn) != 500) {
                helper.fail("下限未満でフィルターが減った: " + ProtectiveMaskItem.used(worn));
                return;
            }
            // 残り 10 にする → この 1 秒は防ぐ(使い切る)・次の 1 秒から積む
            int cap = (int) Math.ceil(t.maskCapacity());
            OvItemData.set(worn, ModDataComponents.MASK_FILTER_USED, cap - 10);
            SporeExposureTracker.tickWith(level, player, 20, 50.0D);
            if (SporeExposureTracker.dose(player) != 0.0D || ProtectiveMaskItem.used(worn) != cap
                    || ProtectiveMaskItem.isProtecting(player)) {
                helper.fail("使い切りの 1 秒: 暴露 " + SporeExposureTracker.dose(player) + "・吸った量 " + ProtectiveMaskItem.used(worn));
                return;
            }
            SporeExposureTracker.tickWith(level, player, 20, 50.0D);
            if (Math.abs(SporeExposureTracker.dose(player) - 50.0D) > 1.0E-6D
                    || !(player.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ProtectiveMaskItem)) {
                helper.fail("詰まったマスクは外れず、普通に積むはず: 暴露 " + SporeExposureTracker.dose(player));
                return;
            }
            // 効いているマスクは感染中も息で浴びる分を止める(11-15): 濃度 100% に 100 秒でも、体の中で育つ分だけ進む
            SporeExposureTracker.setDose(player, 0.0D, 0L);
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            SporeInfectionEffects.startPlayer(player);
            OvAttach.getData(player, ModAttachments.SPORE_EXPOSURE).rateCarry = 0.0D;
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.PROTECTIVE_MASK.get()));
            SporeExposureTracker.tickWith(level, player, 2000, 100.0D);
            int grown = (int) Math.floor(DesignData.effects().playerInfection().growPerSecond(1) * 100.0D + 1.0E-6D);
            if (Math.abs(SporeInfectionEffects.progressOf(player) - grown) > 1) {
                helper.fail("マスクが効いているのに浴びた分が進んだ: " + SporeInfectionEffects.progressOf(player) + "(期待 育つ分だけ " + grown + ")");
                return;
            }
            // マスクは傷を止めない(11-15): 傷の抽選(30%)はマスクを被っていてもかかる。50 回でかからない見込みは 0.7^50 ≒ 2e-8
            SporeInfectionEffects.setPlayerStage(player, 0);
            for (int i = 0; i < 50 && SporeInfectionEffects.stageOf(player) == 0; i++) {
                SporeInfectionEffects.plantAttack(player);
            }
            if (SporeInfectionEffects.stageOf(player) == 0) {
                helper.fail("マスクを被っていると傷の抽選でかからない");
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
            player.curePotionEffects(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MILK_BUCKET)); // 1.20.1: 牛乳で治す口
            if (SporeInfectionEffects.stageOf(player) != 3) {
                helper.fail("牛乳で治った(段階 " + SporeInfectionEffects.stageOf(player) + ")");
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    /** 10 秒ごとに経験値を吸われる。胞子の無い所では体の中で育つ分だけ進む(11-15)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void xpDrainsWhileInfected(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_spore_stage_xp");
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        try {
            player.giveExperiencePoints(100);
            SporeExposureTracker.tickWith(helper.getLevel(), player, spec.xpDrainIntervalTicks(), 0.0D);
            if (player.totalExperience != 100) {
                helper.fail("感染していないのに経験値が減った: " + player.totalExperience);
                return;
            }
            SporeInfectionEffects.startPlayer(player);
            SporeExposureTracker.tickWith(helper.getLevel(), player, spec.xpDrainIntervalTicks(), 0.0D);
            if (player.totalExperience != 100 - spec.xpDrainPoints()) {
                helper.fail("10 秒で経験値が " + spec.xpDrainPoints() + " 減らない: " + player.totalExperience);
                return;
            }
            int grown = (int) Math.floor(spec.growPerSecond(1) * spec.xpDrainIntervalTicks() / 20.0D + 1.0E-6D); // 11-15: 胞子の無い所では体の中で育つ分だけ
            if (Math.abs(SporeInfectionEffects.progressOf(player) - grown) > 1) {
                helper.fail("胞子の無い所で育つ分だけ進まない: " + SporeInfectionEffects.progressOf(player) + "(期待 " + grown + ")");
                return;
            }
            helper.succeed();
        } finally {
            reset(player);
        }
    }

    private SporeInfectionStageGameTests() {
    }
}
