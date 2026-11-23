package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.corpse.PlantSideBurst;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import com.yayuy.overgrownvegetation.registry.ModRunners;
import com.yayuy.overgrownvegetation.spore.MobInfection;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 植物側の死の破裂と攻撃の感染(2026-11-15 ユーザー決定。{@link PlantSideBurst}・{@code ParasitizedAttackInfectionHandler}・{@link PlantSideRules})。
 * 膨らみの見た目はクライアントで確かめる。環境生物のタグは今は空(種がいない)ので、湧かせて試せない —— 判定の段で外れることだけを見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PlantSideBurstGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PlantSideBurstGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "plant_side_burst";

    private PlantSideBurstGameTests() {
    }

    // ------------------------------------------------------------------ 破裂

    /** 破裂する: 膨らむ間(15 tick より前)は残り、19 tick までに消える。消えたあともドロップは出ていて、周りが汚れ、升の浮遊が上がる。 */
    private static void burstsAndPollutes(GameTestHelper helper, EntityType<? extends Mob> type, boolean checkBlocks) {
        Mob mob = helper.spawnWithNoFreeWill(type, new Vec3(3.5, 2.0, 3.5));
        mob.setNoAi(true);
        helper.assertTrue(PlantSideRules.bursts(mob), type + ": 破裂の対象になっていない");
        BlockPos feet = mob.blockPosition();
        Map<BlockPos, BlockState> before = snapshot(helper, feet);
        mob.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + PlantSideRules.BURST_TICK_MIN - 2, () ->
                helper.assertTrue(!mob.isRemoved(), type + ": 膨らむ前に消えた"));
        helper.runAtTickTime(t0 + 19, () -> {
            helper.assertTrue(mob.isRemoved(), type + ": 19 tick で破裂して消えない");
            helper.assertTrue(PlantSideBurst.AIR_BY_ID.containsKey(mob.getId()), type + ": 破裂が数えられていない");
            helper.assertTrue(PlantSideBurst.AIR_BY_ID.getOrDefault(mob.getId(), 0.0D) > 0.0D, type + ": 升の浮遊が上がっていない");
        });
        // 汚染の球は 1 tick 3 個の上限で順に作るので、少し待ってから見る
        helper.runAtTickTime(t0 + 30, () -> {
            // 半径 1 の球(小さい体)は輪郭の揺らぎ ±0.8 で 1 マスも取らないことがあるので、ブロックは半径 2 以上の体で見る
            if (checkBlocks) helper.assertTrue(changed(helper, before) > 0, type + ": 周りのブロックが汚れていない");
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(feet).inflate(5.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g1Stage0CowBurstsAndDrops(GameTestHelper helper) {
        Mob mob = helper.spawnWithNoFreeWill(G1Species.COW.entityType(), new Vec3(3.5, 2.0, 3.5));
        mob.setNoAi(true);
        BlockPos feet = mob.blockPosition();
        mob.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        // ドロップはバニラどおり死んだ瞬間に出る(汚染の牛は肉 1〜3 を必ず落とす)
        helper.runAtTickTime(helper.getTick() + 2, () -> {
            boolean dropped = !helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(feet).inflate(3.0)).isEmpty();
            helper.assertTrue(dropped, "段階 0 の牛がドロップを出していない");
        });
        helper.runAtTickTime(helper.getTick() + 19, () -> {
            helper.assertTrue(mob.isRemoved(), "段階 0 の牛が 19 tick で破裂して消えない");
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(feet).inflate(5.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g1Stage0ZombieBursts(GameTestHelper helper) {
        burstsAndPollutes(helper, G1Species.ZOMBIE.entityType(), false);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g2GregariaBursts(GameTestHelper helper) {
        burstsAndPollutes(helper, ModCrawlers.GREGARIA.get(), false);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g3VenamnesBursts(GameTestHelper helper) {
        burstsAndPollutes(helper, ModRunners.VENAMNES.get(), false);
    }

    /** 大きい体(汚染の半径 2)は周りのブロックを必ず汚す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g1Stage0IronGolemBurstsAndPollutes(GameTestHelper helper) {
        burstsAndPollutes(helper, G1Species.IRON_GOLEM.entityType(), true);
    }

    /** 汎用の感染体(変わった印)も破裂する。印の口に、この 1 体だけを変わったと見る問い合わせを差し込んで試す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void transformedHorseBursts(GameTestHelper helper) {
        Mob horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new Vec3(3.5, 2.0, 3.5));
        horse.setNoAi(true);
        markTurned(horse);
        horse.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        helper.runAtTickTime(helper.getTick() + 19, () -> {
            helper.assertTrue(horse.isRemoved(), "変わった馬が 19 tick で破裂して消えない");
            helper.succeed();
        });
    }

    /** 破裂しない: 植物側でないバニラ(20 tick 目にバニラの煙で消える)・自前の死に方の種(外しのタグ)。環境生物は判定で外れる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void othersDoNotBurst(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(2.5, 2.0, 2.5));
        Mob phyllosens = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModEntities.BLIGHT_FERN.get(), new Vec3(4.5, 2.0, 4.5));
        cow.setNoAi(true);
        phyllosens.setNoAi(true);
        helper.assertTrue(!PlantSideRules.bursts(cow), "ただの牛が破裂の対象になっている");
        helper.assertTrue(phyllosens.getType().is(PlantSideRules.DEATH_BURST_EXEMPT) && !PlantSideRules.bursts(phyllosens),
                "外しのタグの萌芽体が破裂の対象になっている");
        helper.assertTrue(!PlantSideRules.inBurstGroups(cow.getType()), "グループの判定が広すぎる");
        // 環境生物のタグに入った種は判定の段で外れる(タグは今は空)
        for (var tag : java.util.List.of(ModTags.CRITTER_PREY, ModTags.CRITTER_HUNTER)) {
            for (var holder : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(tag)) {
                EntityType<?> t = holder.value();
                boolean other = t.is(ModTags.GROUP_1) || t.is(ModTags.GROUP_2) || t.is(ModTags.GROUP_3) || t.is(ModTags.GROUP_4)
                        || t.is(ModTags.GROUP_5) || t.is(ModTags.GROUP_6) || t.is(ModTags.GROUP_7) || t.is(ModTags.GROUP_8);
                helper.assertTrue(other || !PlantSideRules.inBurstGroups(t), t + ": 環境生物の種が破裂・感染の対象になっている");
            }
        }
        cow.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        phyllosens.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        helper.runAtTickTime(helper.getTick() + 18, () -> {
            helper.assertTrue(!cow.isRemoved(), "ただの牛が破裂の tick で消えた");
            helper.assertTrue(!phyllosens.isRemoved(), "萌芽体が破裂の tick で消えた(外しのタグ = バニラの 20 tick)");
            helper.succeed();
        });
    }

    /**
     * 死に際のアニメを持つ種(11-15 ユーザー決定「アニメの後に破裂」): アニメの長さの間は消えず、その後 15〜18 tick 膨らんで破裂する。
     * 汚染の球の確かめは上(半径 2 以上の体)で済んでいるので、ここは消える時刻と浮遊だけ。
     */
    private static void burstsAfterAnim(GameTestHelper helper, EntityType<? extends Mob> type) {
        Mob mob = helper.spawnWithNoFreeWill(type, new Vec3(3.5, 2.0, 3.5));
        mob.setNoAi(true);
        int anim = PlantSideRules.deathAnimTicks(mob);
        helper.assertTrue(anim > 0 && PlantSideRules.bursts(mob), type + ": アニメの後に破裂する種になっていない");
        BlockPos feet = mob.blockPosition();
        mob.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + anim + PlantSideRules.BURST_TICK_MIN - 2, () ->
                helper.assertTrue(!mob.isRemoved(), type + ": アニメと膨らみが終わる前に消えた"));
        helper.runAtTickTime(t0 + anim + PlantSideRules.BURST_TICK_MIN + PlantSideRules.BURST_TICK_SPREAD, () -> {
            helper.assertTrue(mob.isRemoved(), type + ": アニメの後に破裂して消えない");
            helper.assertTrue(PlantSideBurst.AIR_BY_ID.getOrDefault(mob.getId(), 0.0D) > 0.0D, type + ": 升の浮遊が上がっていない");
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(feet).inflate(5.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void phyllosensBurstsAfterAnim(GameTestHelper helper) {
        burstsAfterAnim(helper, ModPhyllosens.PHYLLOSENS.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void falcimnesBurstsAfterAnim(GameTestHelper helper) {
        burstsAfterAnim(helper, com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void acumnesBurstsAfterAnim(GameTestHelper helper) {
        burstsAfterAnim(helper, com.yayuy.overgrownvegetation.registry.ModFlyers.ACUMNES.get());
    }

    /**
     * 汎用の感染体のドラゴン(11-15 ユーザー決定「死に際の後に破裂」): 膨らまず(破裂の対象の判定からは外れる)、バニラの 200 tick の終わりで破裂する。
     * 死に際を 198 tick まで早送りして、消える tick に破裂が数えられることを見る。
     */
    // ドラゴンの体(幅 16)はテスト場からはみ出し、隣のテストが終わるときの片付けで消されるので、ひとりの束で流す(11-16)
    @GameTest(template = TEMPLATE, batch = "plant_side_dragon", timeoutTicks = 40)
    public static void transformedDragonBurstsAtTheEndOfItsDeath(GameTestHelper helper) {
        Mob dragon = helper.spawnWithNoFreeWill(EntityType.ENDER_DRAGON, new Vec3(3.5, 3.0, 3.5));
        markTurned(dragon);
        helper.assertTrue(!PlantSideRules.bursts(dragon), "ドラゴンが膨らむ破裂の対象になっている");
        dragon.setHealth(0.0F); // kill() はドラゴンだけその場で消す作りなので、体力 0 で死に際(tickDeath)を通す
        helper.runAtTickTime(helper.getTick() + 3, () -> {
            helper.assertTrue(!dragon.isRemoved() && !PlantSideBurst.AIR_BY_ID.containsKey(dragon.getId()), "ドラゴンが死に際の途中で消えた・破裂した");
            ((net.minecraft.world.entity.boss.enderdragon.EnderDragon) dragon).dragonDeathTime = 197;
        });
        helper.runAtTickTime(helper.getTick() + 8, () -> {
            helper.assertTrue(dragon.isRemoved(), "ドラゴンが 200 tick で消えていない");
            helper.assertTrue(PlantSideBurst.AIR_BY_ID.containsKey(dragon.getId()), "ドラゴンが死に際の終わりに破裂していない");
            helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(30.0))
                    .forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** 胞子の技(傷の無い胞子。G1SporeDose を通る)を浴びた Mob も必ず感染する(11-15 ユーザー決定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporeTechniqueInfects(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(3.5, 2.0, 3.5));
        com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeDose.add(cow, 10.0D);
        helper.assertTrue(MobInfection.isInfected(cow), "胞子の技を浴びた牛が感染していない");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 攻撃で必ず感染

    /** G1 に 1 回傷を付けられた Mob は感染する(アイアンゴーレムも)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void oneG1HitInfects(GameTestHelper helper) {
        Mob g1 = helper.spawnWithNoFreeWill(G1Species.ZOMBIE.entityType(), new Vec3(1.5, 2.0, 1.5));
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(3.5, 2.0, 3.5));
        Mob golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new Vec3(5.5, 2.0, 5.5));
        cow.hurt(helper.getLevel().damageSources().mobAttack(g1), 1.0F);
        golem.hurt(helper.getLevel().damageSources().mobAttack(g1), 1.0F);
        helper.assertTrue(MobInfection.isInfected(cow), "G1 に 1 回殴られた牛が感染していない");
        helper.assertTrue(MobInfection.isInfected(golem), "G1 に 1 回殴られたアイアンゴーレムが感染していない");
        helper.succeed();
    }

    /** G2・G3 と、植物側の撃った矢でも感染する。植物側でない Mob の撃ちでは感染しない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void g2G5AndProjectilesInfectButOthersDoNot(GameTestHelper helper) {
        Mob gregaria = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new Vec3(1.5, 2.0, 1.5));
        Mob venamnes = helper.spawnWithNoFreeWill(ModRunners.VENAMNES.get(), new Vec3(1.5, 2.0, 5.5));
        Mob skeleton = helper.spawnWithNoFreeWill(G1Species.SKELETON.entityType(), new Vec3(5.5, 2.0, 1.5));
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(5.5, 2.0, 5.5));
        Mob a = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(3.5, 2.0, 2.5));
        Mob b = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(3.5, 2.0, 3.5));
        Mob c = helper.spawnWithNoFreeWill(EntityType.SHEEP, new Vec3(3.5, 2.0, 4.5));
        Mob d = helper.spawnWithNoFreeWill(EntityType.SHEEP, new Vec3(2.5, 2.0, 3.5));
        a.hurt(helper.getLevel().damageSources().mobAttack(gregaria), 1.0F);
        b.hurt(helper.getLevel().damageSources().mobAttack(venamnes), 1.0F);
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setOwner(skeleton);
        c.hurt(helper.getLevel().damageSources().arrow(arrow, skeleton), 1.0F);
        d.hurt(helper.getLevel().damageSources().mobAttack(zombie), 1.0F);
        helper.assertTrue(MobInfection.isInfected(a), "G2 に殴られた豚が感染していない");
        helper.assertTrue(MobInfection.isInfected(b), "G3 に殴られた豚が感染していない");
        helper.assertTrue(MobInfection.isInfected(c), "G1 のスケルトンの矢で感染していない");
        helper.assertTrue(!MobInfection.isInfected(d), "ただのゾンビに殴られた羊が感染した");
        arrow.discard();
        helper.succeed();
    }

    /** 汎用の感染体(変わった印)に殴られても感染する。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void transformedAttackerInfects(GameTestHelper helper) {
        Mob wolf = helper.spawnWithNoFreeWill(EntityType.WOLF, new Vec3(1.5, 2.0, 1.5));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(3.5, 2.0, 3.5));
        markTurned(wolf);
        pig.hurt(helper.getLevel().damageSources().mobAttack(wolf), 1.0F);
        helper.assertTrue(MobInfection.isInfected(pig), "変わった狼に殴られた豚が感染していない");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 傷の暴露・倒された Mob も感染体に(§4、11-15 ユーザー決定)

    /** Mob: 傷のダメージに比例して暴露量が上がる(G2 の 1 撃)。環境生物以外の植物側でない Mob の撃ちでは上がらない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void woundRaisesMobDoseByDamage(GameTestHelper helper) {
        Mob gregaria = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new Vec3(1.5, 2.0, 1.5));
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(5.5, 2.0, 5.5));
        Mob a = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new Vec3(3.5, 2.0, 2.5));
        Mob b = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new Vec3(3.5, 2.0, 5.0));
        double per = com.yayuy.overgrownvegetation.data.DesignData.sporeField().mobInfection().woundDoseMobPerDamage();
        a.hurt(helper.getLevel().damageSources().mobAttack(gregaria), 3.0F);
        b.hurt(helper.getLevel().damageSources().mobAttack(zombie), 3.0F);
        double dose = com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(a);
        helper.assertTrue(Math.abs(dose - per * 3.0D) < 1.0E-3D, "3 ダメージの傷で暴露量が " + per * 3.0D + " 上がっていない: " + dose);
        helper.assertTrue(com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(b) == 0.0D, "ただのゾンビの傷で暴露量が上がった");
        helper.succeed();
    }

    /** プレイヤー: 傷で暴露量が上がる(防護マスクを被っていても)。今の 30% の抽選で感染に入ったら、その撃ちでは積まない(感染中は積まない決まり)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void woundRaisesPlayerDoseThroughMask(GameTestHelper helper) {
        net.minecraft.world.entity.player.Player player = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.SURVIVAL);
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(com.yayuy.overgrownvegetation.registry.ModItems.PROTECTIVE_MASK.get()));
        double per = com.yayuy.overgrownvegetation.data.DesignData.sporeField().mobInfection().woundDosePlayerPerDamage();
        com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.addWoundDose(player, 4.0F);
        double dose = com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(player);
        helper.assertTrue(Math.abs(dose - per * 4.0D) < 1.0E-3D, "マスクを被ったプレイヤーの暴露量が傷で " + per * 4.0D + " 上がっていない: " + dose);
        // 入口から: G1 の撃ちで、暴露量が上がるか、抽選で感染に入る
        Mob g1 = helper.spawnWithNoFreeWill(G1Species.ZOMBIE.entityType(), new Vec3(1.5, 2.0, 1.5));
        player.hurt(helper.getLevel().damageSources().mobAttack(g1), 2.0F);
        double after = com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(player);
        boolean infected = com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.stageOf(player) > 0;
        helper.assertTrue(infected || after > dose, "G1 の撃ちで暴露量も感染も上がらない: " + after);
        helper.succeed();
    }

    /** G2 に倒された Mob も感染の死: 表にある種は死体へ崩れ落ちる(11-15 ユーザー決定)。ただのゾンビに倒されたらならない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void killedByG2BecomesCorpse(GameTestHelper helper) {
        Mob venamnes = helper.spawnWithNoFreeWill(ModRunners.VENAMNES.get(), new Vec3(1.5, 2.0, 1.5));
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(5.5, 2.0, 5.5));
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(3.5, 2.0, 2.5));
        Mob cow2 = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(3.5, 2.0, 5.0));
        helper.assertTrue(com.yayuy.overgrownvegetation.corpse.G1Infection.isInfectionDeath(cow, helper.getLevel().damageSources().mobAttack(venamnes)),
                "G2 の撃ちが感染の死に数えられていない");
        cow.hurt(helper.getLevel().damageSources().mobAttack(venamnes), 1000.0F);
        cow2.hurt(helper.getLevel().damageSources().mobAttack(zombie), 1000.0F);
        helper.assertTrue(com.yayuy.overgrownvegetation.corpse.CorpseCollapse.isCollapsing(cow), "G2 に倒された牛が死体へ崩れ落ちない");
        helper.assertTrue(!com.yayuy.overgrownvegetation.corpse.CorpseCollapse.isCollapsing(cow2), "ただのゾンビに倒された牛が死体になった");
        helper.succeed();
    }

    /**
     * 一撃で倒されても感染体になる(11-15 ユーザー「一撃で倒されたときも感染体に」): 感染の印も生きていた時間も見ず、死因の元で決める。
     * G2 の一撃で村人が死体へ崩れ落ちる。飛び道具も撃った Mob を元に見る(G2 が撃った矢の一撃で倒れた村人も死体へ)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void oneHitKillByG2BecomesCorpse(GameTestHelper helper) {
        Mob gregaria = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new Vec3(1.5, 2.0, 1.5));
        Mob villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(3.5, 2.0, 2.5));
        Mob villager2 = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(3.5, 2.0, 5.0));
        helper.assertTrue(!MobInfection.isInfected(villager), "殴られる前から感染している");
        villager.hurt(helper.getLevel().damageSources().mobAttack(gregaria), 1000.0F);
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setOwner(gregaria);
        villager2.hurt(helper.getLevel().damageSources().arrow(arrow, gregaria), 1000.0F);
        helper.assertTrue(villager.isDeadOrDying() && com.yayuy.overgrownvegetation.corpse.CorpseCollapse.isCollapsing(villager),
                "G2 の一撃で倒れた村人が死体へ崩れ落ちない");
        helper.assertTrue(villager2.isDeadOrDying() && com.yayuy.overgrownvegetation.corpse.CorpseCollapse.isCollapsing(villager2),
                "G2 が撃った矢の一撃で倒れた村人が死体へ崩れ落ちない");
        arrow.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ コードの見直し 第 1 便 ③(11-16)

    /** 感染の死(胞子の 20 ダメージ)で牛を倒す。 */
    private static Mob sporeKillCow(GameTestHelper helper, Vec3 at) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, at);
        cow.setNoAi(true);
        cow.hurt(com.yayuy.overgrownvegetation.registry.ModDamageTypes.sporeInfection(helper.getLevel()), 1000.0F);
        return cow;
    }

    private static boolean isCorpse(GameTestHelper helper, BlockPos abs) {
        return helper.getLevel().getBlockEntity(abs) instanceof com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
    }

    /**
     * ③3: 死体ブロックは足元のチェスト(中身)・ハーフを消さず、近くの空いた所に置く。同じマスで 2 体死んでも、先の死体を上書きしない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void corpseDoesNotReplaceChestSlabOrCorpse(GameTestHelper helper) {
        BlockPos chestRel = new BlockPos(1, 2, 1), slabRel = new BlockPos(5, 2, 1), twinRel = new BlockPos(3, 2, 5);
        helper.setBlock(chestRel, net.minecraft.world.level.block.Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        if (helper.getLevel().getBlockEntity(chestAbs) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
        }
        helper.setBlock(slabRel, net.minecraft.world.level.block.Blocks.OAK_SLAB);
        // チェスト・ハーフの上に立った牛(足元のマス = そのブロックのマス)と、同じマスで続けて 2 体
        Mob chestCow = sporeKillCow(helper, Vec3.atBottomCenterOf(chestRel).add(0.0D, 0.875D, 0.0D));
        sporeKillCow(helper, Vec3.atBottomCenterOf(slabRel).add(0.0D, 0.5D, 0.0D));
        sporeKillCow(helper, Vec3.atBottomCenterOf(twinRel));
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 25, () -> sporeKillCow(helper, Vec3.atBottomCenterOf(twinRel))); // 先の死体ができてから同じマスで
        // ずらして置いた死体: 倒れた Mob も死体のマスへ移る(クライアントの引き継ぎが合う)
        helper.runAtTickTime(t0 + 22, () -> helper.assertTrue(!chestCow.isRemoved() && isCorpse(helper, chestCow.blockPosition())
                && !chestCow.blockPosition().equals(chestAbs), "ずらした死体のマスへ倒れた牛が移っていない: " + chestCow.blockPosition()));
        helper.runAtTickTime(t0 + 55, () -> {
            helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.CHEST, chestRel);
            boolean diamond = helper.getLevel().getBlockEntity(chestAbs) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c
                    && c.getItem(0).is(net.minecraft.world.item.Items.DIAMOND);
            helper.assertTrue(diamond, "チェストの中身が消えた");
            helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.OAK_SLAB, slabRel);
            helper.assertTrue(isCorpse(helper, helper.absolutePos(twinRel)), "先の死体が残っていない(上書きされた)");
            int corpses = 0;
            for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(6, 4, 6)))) {
                if (isCorpse(helper, p)) corpses++;
            }
            helper.assertTrue(corpses == 4, "死体が 4 つ置かれていない(近くの空きへ置けていない): " + corpses);
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(10.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /**
     * ③1: エンダードラゴンには胞子の 20 ダメージが入らない(胴の部位が弾いて true を返す)。そのとき最大体力を減らさない。
     * 弾かれた分は「耐性を無視して胞子で変える」(11-15)に数え、体力を超えたら汎用の感染体に変わる(→ 死に際の後に破裂)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void dragonKeepsMaxHealthAndTurnsFromBlockedSpores(GameTestHelper helper) {
        Mob dragon = helper.spawnWithNoFreeWill(EntityType.ENDER_DRAGON, new Vec3(3.5, 3.0, 3.5));
        float max0 = dragon.getMaxHealth();
        double dose = com.yayuy.overgrownvegetation.data.DesignData.sporeField().infectDose();
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        int hits = 0;
        while (!com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(dragon) && hits < 30) {
            com.yayuy.overgrownvegetation.spore.SporeExposureTracker.setDose(dragon, dose, level.getGameTime());
            com.yayuy.overgrownvegetation.spore.SporeExposureTracker.tickWith(level, dragon, 40, 0.0D);
            hits++;
            helper.assertTrue(com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(dragon) || dragon.getMaxHealth() == max0,
                    "胞子が入らないのにドラゴンの最大体力が減った: " + dragon.getMaxHealth());
        }
        helper.assertTrue(com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(dragon),
                "弾かれた胞子が " + hits + " 回たまってもドラゴンが汎用の感染体に変わらない");
        dragon.discard();
        helper.succeed();
    }

    /** ③1: 吸収体力で受けた胞子の 20 ダメージは「入った」(最大体力の傷が付く。弾かれた扱いにしない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void absorbedSporeHitStillLands(GameTestHelper helper) {
        Mob golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new Vec3(3.5, 2.0, 3.5));
        // 1.21 は吸収体力の上限の属性があり、効果なしでは 0 に切り詰められる。衝撃吸収 VII(28)を付ける
        golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 600, 6));
        float abs0 = golem.getAbsorptionAmount();
        float max0 = golem.getMaxHealth(), hp0 = golem.getHealth();
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        com.yayuy.overgrownvegetation.spore.SporeExposureTracker.setDose(golem, com.yayuy.overgrownvegetation.data.DesignData.sporeField().infectDose(), level.getGameTime());
        com.yayuy.overgrownvegetation.spore.SporeExposureTracker.tickWith(level, golem, 40, 0.0D);
        // 吸収体力から 20 減る(体力は最大体力の傷で上限が下がった分だけ切り詰められる)
        helper.assertTrue(abs0 >= 20.0F && Math.abs(golem.getAbsorptionAmount() - (abs0 - 20.0F)) < 1.0E-3F, "吸収体力で受けていない: 吸収 " + abs0 + " → " + golem.getAbsorptionAmount() + "・体力 " + golem.getHealth() + "(はじめ " + hp0 + ")");
        helper.assertTrue(golem.getMaxHealth() < max0, "吸収体力で受けた被弾が「入らなかった」扱いになった(傷が付かない)");
        helper.assertTrue(!com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(golem), "吸収体力で受けた被弾で変わった");
        helper.succeed();
    }

    /** ③2: 場では汚染済みなのに台帳に無い升は、起動時の照らし合わせで取り消す。止める前に入力を流す口が場へ届く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fieldReconcilesWithLedgerAndDrainsInputs(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.spore.field.SporeField f =
                new com.yayuy.overgrownvegetation.spore.field.SporeField(42L, com.yayuy.overgrownvegetation.data.SporeFieldTable.gameDefaults());
        f.setCorrupted(0, 0, 1L);
        f.setCorrupted(5, 3, 1L);
        f.setCorrupted(-40, 70, 1L);
        long keep = net.minecraft.world.level.ChunkPos.asLong(5, 3);
        int stray = com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.reconcileFieldWithLedger(f, k -> k == keep);
        helper.assertTrue(stray == 2, "台帳に無い升の数が 2 でない: " + stray);
        helper.assertTrue(f.isCorrupted(5, 3) && !f.isCorrupted(0, 0) && !f.isCorrupted(-40, 70), "台帳に無い升が取り消されていない");
        // 入力を流す: 遠くのチャンクを台帳に載せた知らせ → 今すぐ流すと場が汚染済みになる
        int cx = 900_000 >> 4, cz = -900_000 >> 4;
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.onLedgerAdded(net.minecraft.world.level.ChunkPos.asLong(cx, cz), 1L);
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.drainInputsNow();
        Boolean corrupted = com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.withFieldForTests(live -> live == null ? null : live.isCorrupted(cx, cz));
        helper.assertTrue(corrupted == null || corrupted, "流した入力が場に届いていない");
        if (corrupted != null) {
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.withFieldForTests(live -> {
                live.unsprout(cx, cz); // 片付け
                return null;
            });
        }
        helper.succeed();
    }

    /** ③3 の続き: 死体の置き場がどこにも無ければ、崩れ落ちを始めずにふつうに死ぬ(元のドロップが出る・萌芽体は生えない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void noCorpseSpotMeansOrdinaryDeath(GameTestHelper helper) {
        BlockPos c = new BlockPos(3, 2, 3);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, -1, -2), c.offset(2, 1, 2))) {
            BlockPos rel = p.immutable();
            if (rel.equals(c) || rel.equals(c.above())) continue;
            helper.setBlock(rel, net.minecraft.world.level.block.Blocks.STONE);
        }
        helper.setBlock(c, net.minecraft.world.level.block.Blocks.CHEST); // 足元はチェスト(置けない)・頭の上は下がチェストなので置けない
        Mob cow = sporeKillCow(helper, Vec3.atBottomCenterOf(c).add(0.0D, 0.875D, 0.0D));
        helper.assertTrue(!com.yayuy.overgrownvegetation.corpse.CorpseCollapse.isCollapsing(cow), "置き場が無いのに崩れ落ちを始めた");
        BlockPos abs = helper.absolutePos(c);
        helper.runAtTickTime(helper.getTick() + 30, () -> {
            helper.assertTrue(cow.isRemoved(), "ふつうの死(20 tick)で消えていない");
            helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.CHEST, c);
            boolean drops = !helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(3.0)).isEmpty();
            helper.assertTrue(drops, "ふつうの死なのに元のドロップが出ていない");
            boolean fern = !helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.BlightFernEntity.class, new AABB(abs).inflate(4.0)).isEmpty();
            helper.assertTrue(!fern, "萌芽体が生えた");
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(5.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ コードの見直し 第 3 便 ③ の計測(11-16。ログに PERF3 の行を出すだけ)

    /**
     * ③5: 死んだときの汚染の球 1 個の費用(半径 2、草の床)。③10: 植物側 {@code n} 体のそばで「狙いを外す」見張り 1 回の費用。
     * ③13: 部屋の汚れ 4096 個・汚れの無い箱 256 個で、毎秒の減らし 1 回の費用。
     */
    @GameTest(template = TEMPLATE, batch = "plant_side_perf3", timeoutTicks = 100)
    public static void measureBatch3(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        // ③5 球 1 個(床を草にして毎回作り直す)
        int spheres = 40;
        long sphereNs = 0;
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 3));
        for (int i = 0; i < spheres; i++) {
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -1, -3), base.offset(3, 0, 3))) level.setBlock(p, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            long t0 = System.nanoTime();
            com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.sphere(level, Vec3.atCenterOf(base.above()), 2, 0.8D);
            sphereNs += System.nanoTime() - t0;
        }
        // ③10 植物側 n 体のそばで見張り 1 回
        int n = 120;
        for (int i = 0; i < n; i++) {
            Mob m = helper.spawnWithNoFreeWill(G1Species.ZOMBIE.entityType(), new Vec3(1.5 + (i % 5), 2.0, 1.5 + (i / 5 % 5)));
            m.setNoAi(true);
        }
        Mob turned = helper.spawnWithNoFreeWill(EntityType.HORSE, new Vec3(3.5, 2.0, 3.5));
        int calls = 200;
        long t1 = System.nanoTime();
        for (int i = 0; i < calls; i++) com.yayuy.overgrownvegetation.infect.GenericInfection.releaseAttackers(turned);
        long releaseNs = (System.nanoTime() - t1) / calls;
        // ③13 部屋の汚れの毎秒の減らし
        long decayNs = com.yayuy.overgrownvegetation.survival.room.RoomDirt.benchDecayForTests(level, 4096, 256);
        com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(
                "PERF3 球 1 個 {} us(半径 2・{} 回の平均)/ 狙いを外す見張り 1 回 {} us(植物側 {} 体のそば)/ 部屋の汚れの減らし 1 回 {} us(汚れ 4096・空の箱 256)",
                sphereNs / spheres / 1000, spheres, releaseNs / 1000, n, decayNs / 1000);
        helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(12.0)).forEach(e -> e.discard());
        helper.succeed();
    }

    /**
     * ③5: 死んだときの汚染の球の待ち行列は上限(600)までで、あふれた分は球を作らず胞子の場の降り積もりに替える。
     * 待ち行列を使い切るので、ほかのテストと同じ束では流さない(計測と同じ束)。
     */
    @GameTest(template = TEMPLATE, batch = "plant_side_perf3", timeoutTicks = 20)
    public static void deathSphereQueueIsCapped(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        long dropped0 = com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.droppedToDeposit;
        Vec3 c = Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        try {
            for (int i = 0; i < 1000; i++) com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.deathSphere(level, c, 1, 0.0D, false);
            int pending = com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.pendingForTests(level);
            long dropped = com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.droppedToDeposit - dropped0;
            helper.assertTrue(pending <= 600, "待ち行列が上限を超えた: " + pending);
            helper.assertTrue(dropped >= 1000 - 600 - 3, "あふれた球が降り積もりに替わっていない: " + dropped);
        } finally {
            com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst.clearPendingForTests(level);
        }
        helper.succeed();
    }

    /**
     * ③10: 汎用の感染体が植物側に殴られたら(撃ちは止める所 = GenericInfection.onIncomingDamage で止まる)、その場で相手の狙いを外す。
     * 変わった後に握ったままの狙い(見張りの間に残った物)は setTarget では作れない(guardTarget が止める)ので、テストの中だけ直に置く。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void hitTurnedMobDropsAttackerTarget(GameTestHelper helper) {
        Mob horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new Vec3(3.5, 2.0, 3.5));
        Mob g1 = helper.spawnWithNoFreeWill(G1Species.ZOMBIE.entityType(), new Vec3(1.5, 2.0, 1.5));
        helper.assertTrue(com.yayuy.overgrownvegetation.infect.GenericInfection.turn(horse, true), "前提: 馬が汎用の感染体に変わらない");
        try {
            java.lang.reflect.Field target = Mob.class.getDeclaredField("target");
            target.setAccessible(true);
            target.set(g1, horse);
        } catch (ReflectiveOperationException e) {
            helper.fail("狙いを直に置けない: " + e);
            return;
        }
        helper.assertTrue(g1.getTarget() == horse, "前提: 狙いを置けない");
        float hp = horse.getHealth();
        horse.hurt(helper.getLevel().damageSources().mobAttack(g1), 2.0F); // 本物の撃ち
        helper.assertTrue(horse.getHealth() == hp, "植物側の撃ちが汎用の感染体に入った(止める所を通っていない)");
        helper.assertTrue(g1.getTarget() != horse, "汎用の感染体を殴った G1 の狙いが外れていない");
        helper.succeed();
    }

    /**
     * ③11 の計測: 区画 {@code n} 個(1 区画 = 1024 チャンク)の場のメモリ(ヒープの増え・粗い)と、保存の写し(鍵を持ったまま)の時間。
     * 1 回目 = 全部作る、2 回目 = 変わっていない(指紋だけ)。1 回 step した後の 3 回目 = 動いた後(ふだんの自動保存)。ログに PERF3F の行。
     */
    @GameTest(template = TEMPLATE, batch = "plant_side_perf3", timeoutTicks = 200)
    public static void measureSporeFieldSave(GameTestHelper helper) {
        int n = 2000;
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heap0 = rt.totalMemory() - rt.freeMemory();
        com.yayuy.overgrownvegetation.spore.field.SporeField f =
                new com.yayuy.overgrownvegetation.spore.field.SporeField(42L, com.yayuy.overgrownvegetation.data.SporeFieldTable.gameDefaults());
        int side = (int) Math.ceil(Math.sqrt(n));
        for (int i = 0; i < n; i++) {
            int tx = i % side, tz = i / side;
            f.setCorrupted(tx << com.yayuy.overgrownvegetation.spore.field.SporeField.TB, tz << com.yayuy.overgrownvegetation.spore.field.SporeField.TB, 1L);
            f.emitPoint((tx << com.yayuy.overgrownvegetation.spore.field.SporeField.TB) + 3, (tz << com.yayuy.overgrownvegetation.spore.field.SporeField.TB) + 3, 1.0D);
        }
        System.gc();
        long heap1 = rt.totalMemory() - rt.freeMemory();
        long[] snap = com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.benchSnapshotForTests(f);
        long t0 = System.nanoTime();
        f.step(new com.yayuy.overgrownvegetation.spore.field.SporeField.Conditions(100L, false, false, false),
                new com.yayuy.overgrownvegetation.spore.field.PrevailingWind.Sample());
        long stepNs = System.nanoTime() - t0;
        long[] snap2 = com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.benchSnapshotForTests(f);
        com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(
                "PERF3F 区画 {} 個(約 {} 万チャンク)/ ヒープの増え 約 {} MB(区画 1 つ 約 {} KB)/ 写し 1 回目(全部) {} ms・2 回目(変わらず・指紋だけ) {} ms / step 1 回 {} ms / step の後の写し(全部書く想定) {} ms・指紋だけ {} ms",
                f.tileCount(), f.tileCount() * 1024 / 10000, (heap1 - heap0) / (1024 * 1024), (heap1 - heap0) / 1024 / Math.max(1, f.tileCount()),
                snap[0] / 1_000_000, snap[1] / 1_000_000, stepNs / 1_000_000, snap2[0] / 1_000_000, snap2[1] / 1_000_000);
        helper.succeed();
    }

    // ------------------------------------------------------------------ コードの見直し 第 2 便 ③(11-16)

    /** ③4: プレイヤーの 30% の抽選は植物側(isInfector)の傷だけ。傷 0・植物側でない相手では、何度殴られてもかからない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void playerLotteryOnlyOnPlantSideWounds(GameTestHelper helper) {
        net.minecraft.world.entity.player.Player player = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.SURVIVAL);
        Mob g1 = helper.spawnWithNoFreeWill(G1Species.ZOMBIE.entityType(), new Vec3(1.5, 2.0, 1.5));
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(5.5, 2.0, 5.5));
        for (int i = 0; i < 60; i++) {
            player.invulnerableTime = 0;
            player.hurt(helper.getLevel().damageSources().mobAttack(zombie), 1.0F);
            player.setHealth(player.getMaxHealth());
        }
        helper.assertTrue(com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.stageOf(player) == 0, "ただのゾンビの傷で感染した");
        for (int i = 0; i < 60; i++) {
            player.invulnerableTime = 0;
            com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.onLivingDamageForTests(player, g1, 0.0F);
        }
        helper.assertTrue(com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.stageOf(player) == 0, "傷 0 の撃ちで感染した");
        for (int i = 0; i < 60 && com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.stageOf(player) == 0; i++) {
            com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.onLivingDamageForTests(player, g1, 1.0F);
        }
        helper.assertTrue(com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.stageOf(player) > 0, "G1 の傷 60 回で一度も感染しない(30%)");
        helper.succeed();
    }

    /** ③9: 創造モードのプレイヤーには暴露量がたまらない(観戦と同じ)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void creativePlayerGetsNoExposure(GameTestHelper helper) {
        net.minecraft.world.entity.player.Player player = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.CREATIVE);
        com.yayuy.overgrownvegetation.spore.SporeExposureTracker.tickWith(helper.getLevel(), player, 200, 80.0D);
        helper.assertTrue(com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(player) == 0.0D,
                "創造モードで暴露量がたまった: " + com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(player));
        helper.succeed();
    }

    /**
     * ③12: 部屋をたどる 1 tick の上限を Mob の分で使い切っても、プレイヤーの判定(priority)は断られない。
     * 石で囲った小部屋を 3 つ作り、上限 2 回を使い切ってから 3 つ目を引く。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void playerRoomCheckIsNotRefused(GameTestHelper helper) {
        int max = com.yayuy.overgrownvegetation.data.DesignData.sporeField().room().maxSearchesPerTick();
        BlockPos[] cells = new BlockPos[max + 1];
        for (int i = 0; i < cells.length; i++) {
            BlockPos c = new BlockPos(1 + (i % 3) * 2, 2 + (i / 3) * 3, 1 + (i / 3) * 2);
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 2, 1))) helper.setBlock(p, net.minecraft.world.level.block.Blocks.STONE);
            helper.setBlock(c, net.minecraft.world.level.block.Blocks.AIR);
            helper.setBlock(c.above(), net.minecraft.world.level.block.Blocks.AIR);
            cells[i] = helper.absolutePos(c);
        }
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        boolean was = com.yayuy.overgrownvegetation.survival.room.RoomCache.unlimitedForTests;
        com.yayuy.overgrownvegetation.survival.room.RoomCache.unlimitedForTests = false;
        try {
            for (int i = 0; i < max; i++) com.yayuy.overgrownvegetation.survival.room.RoomCache.scanAt(level, cells[i], false);
            com.yayuy.overgrownvegetation.survival.room.RoomScan mob = com.yayuy.overgrownvegetation.survival.room.RoomCache.scanAt(level, cells[max], false);
            com.yayuy.overgrownvegetation.survival.room.RoomScan player = com.yayuy.overgrownvegetation.survival.room.RoomCache.scanAt(level, cells[max], true);
            // 断られると null(「部屋でない」= 外の濃度を読む)。断られなければ答え(石だけの小部屋は自然の空洞 NATURAL)が返る
            helper.assertTrue(player != null, "上限を使い切った tick に、プレイヤーの部屋の判定が断られた");
            // Mob の判定は同じ tick なら断られる(前提の確かめ)
            helper.assertTrue(mob == null, "上限を使い切った tick に、Mob の判定が断られない(前提が崩れた): " + mob);
        } finally {
            com.yayuy.overgrownvegetation.survival.room.RoomCache.unlimitedForTests = was;
        }
        helper.succeed();
    }

    /** ③14: 自前の死に際を持つほかの MOD の Mob の判定(この MOD の種・バニラの 20 tick の種は入らない。ドラゴンは別の道)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ownLongDeathIsDetected(GameTestHelper helper) {
        Mob dragon = helper.spawnWithNoFreeWill(EntityType.ENDER_DRAGON, new Vec3(3.5, 3.0, 3.5));
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(1.5, 2.0, 1.5));
        Mob falcimnes = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get(), new Vec3(5.5, 2.0, 5.5));
        helper.assertTrue(PlantSideRules.ownsLongDeath(dragon), "tickDeath を上書きするバニラ外の作り(ドラゴン)を見つけない");
        helper.assertTrue(!PlantSideRules.ownsLongDeath(cow), "牛(バニラの 20 tick)を長い死に際と見た");
        helper.assertTrue(!PlantSideRules.ownsLongDeath(falcimnes), "この MOD の種(自分で長さを持つ)を長い死に際と見た");
        helper.assertTrue(!PlantSideRules.burstsWith(dragon, true) && !PlantSideRules.burstsAtRemoval(dragon, true),
                "ドラゴンが膨らむ破裂・消える瞬間の破裂の対象になった(ドラゴンは死に際の終わりの別の道)");
        dragon.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 道具

    /** テストで「変わった」と見る Mob の id。同じ束のテストは並んで走るので、印の口の差し替え・戻しはせず、1 度だけ表を足して残す。 */
    private static final java.util.Set<Integer> TEST_TURNED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean testHookInstalled;

    private static synchronized void markTurned(LivingEntity e) {
        if (!testHookInstalled) {
            Predicate<LivingEntity> base = PlantSideRules.TRANSFORMED;
            PlantSideRules.TRANSFORMED = x -> TEST_TURNED.contains(x.getId()) || base.test(x);
            testHookInstalled = true;
        }
        TEST_TURNED.add(e.getId());
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper, BlockPos center) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-3, -2, -3), center.offset(3, 2, 3))) {
            m.put(p.immutable(), helper.getLevel().getBlockState(p));
        }
        return m;
    }

    private static int changed(GameTestHelper helper, Map<BlockPos, BlockState> before) {
        int n = 0;
        for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
            if (helper.getLevel().getBlockState(e.getKey()) != e.getValue()) n++;
        }
        return n;
    }
}
