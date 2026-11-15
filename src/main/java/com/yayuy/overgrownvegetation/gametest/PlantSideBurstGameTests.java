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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 植物側の死の破裂と攻撃の感染(2026-11-15 ユーザー決定。{@link PlantSideBurst}・{@code ParasitizedAttackInfectionHandler}・{@link PlantSideRules})。
 * 膨らみの見た目はクライアントで確かめる。G2・G3 のタグは今は空(種がいない)ので、湧かせて試せない —— 判定の段で外れることだけを見る。
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
        int bursts0 = PlantSideBurst.burstCount;
        PlantSideBurst.lastAirPercent = 0.0D;
        mob.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + PlantSideRules.BURST_TICK_MIN - 2, () ->
                helper.assertTrue(!mob.isRemoved(), type + ": 膨らむ前に消えた"));
        helper.runAtTickTime(t0 + 19, () -> {
            helper.assertTrue(mob.isRemoved(), type + ": 19 tick で破裂して消えない");
            helper.assertTrue(PlantSideBurst.burstCount > bursts0, type + ": 破裂が数えられていない");
            helper.assertTrue(PlantSideBurst.lastAirPercent > 0.0D, type + ": 升の浮遊が上がっていない");
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
    public static void g4GregariaBursts(GameTestHelper helper) {
        burstsAndPollutes(helper, ModCrawlers.GREGARIA.get(), false);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g5VenamnesBursts(GameTestHelper helper) {
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
        Predicate<LivingEntity> old = PlantSideRules.TRANSFORMED;
        PlantSideRules.TRANSFORMED = e -> e == horse || old.test(e);
        horse.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        helper.runAtTickTime(helper.getTick() + 19, () -> {
            PlantSideRules.TRANSFORMED = old;
            helper.assertTrue(horse.isRemoved(), "変わった馬が 19 tick で破裂して消えない");
            helper.succeed();
        });
    }

    /** 破裂しない: 植物側でないバニラ(20 tick 目にバニラの煙で消える)・自前の死に方の種(外しのタグ)。G2・G3 は判定で外れる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void othersDoNotBurst(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(2.5, 2.0, 2.5));
        Mob phyllosens = helper.spawnWithNoFreeWill(ModPhyllosens.PHYLLOSENS.get(), new Vec3(4.5, 2.0, 4.5));
        cow.setNoAi(true);
        phyllosens.setNoAi(true);
        helper.assertTrue(!PlantSideRules.bursts(cow), "ただの牛が破裂の対象になっている");
        helper.assertTrue(phyllosens.getType().is(PlantSideRules.DEATH_BURST_EXEMPT) && !PlantSideRules.bursts(phyllosens),
                "外しのタグのフィロセンスが破裂の対象になっている");
        helper.assertTrue(!PlantSideRules.inBurstGroups(cow.getType()), "グループの判定が広すぎる");
        // G2・G3 のタグに入った種は判定の段で外れる(タグは今は空)
        for (var tag : java.util.List.of(ModTags.GROUP_2, ModTags.GROUP_3)) {
            for (var holder : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(tag)) {
                EntityType<?> t = holder.value();
                boolean other = t.is(ModTags.GROUP_1) || t.is(ModTags.GROUP_4) || t.is(ModTags.GROUP_5) || t.is(ModTags.GROUP_6)
                        || t.is(ModTags.GROUP_7) || t.is(ModTags.GROUP_8) || t.is(ModTags.GROUP_9) || t.is(ModTags.GROUP_10);
                helper.assertTrue(other || !PlantSideRules.inBurstGroups(t), t + ": G2・G3 の種が破裂・感染の対象になっている");
            }
        }
        cow.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        phyllosens.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        helper.runAtTickTime(helper.getTick() + 18, () -> {
            helper.assertTrue(!cow.isRemoved(), "ただの牛が破裂の tick で消えた");
            helper.assertTrue(!phyllosens.isRemoved(), "フィロセンスが破裂の tick で消えた(自前の倒れ方は 40 tick)");
            helper.succeed();
        });
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

    /** G4・G5 と、植物側の撃った矢でも感染する。植物側でない Mob の撃ちでは感染しない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void g4G5AndProjectilesInfectButOthersDoNot(GameTestHelper helper) {
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
        helper.assertTrue(MobInfection.isInfected(a), "G4 に殴られた豚が感染していない");
        helper.assertTrue(MobInfection.isInfected(b), "G5 に殴られた豚が感染していない");
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
        Predicate<LivingEntity> old = PlantSideRules.TRANSFORMED;
        PlantSideRules.TRANSFORMED = e -> e == wolf || old.test(e);
        try {
            pig.hurt(helper.getLevel().damageSources().mobAttack(wolf), 1.0F);
        } finally {
            PlantSideRules.TRANSFORMED = old;
        }
        helper.assertTrue(MobInfection.isInfected(pig), "変わった狼に殴られた豚が感染していない");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 道具

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
