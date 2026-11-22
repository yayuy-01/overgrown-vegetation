package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedEggBlock;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffDrift;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TechniqueGoal;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * G1 の 1 段目の種の技(2026-11-13、能力の第 2 回 = 牛・羊・鶏)。技ごとに 1 本以上。見た目(アニメ・粒・乳だまりの絵)はクライアントで確かめる。
 * 第 1 回({@link G1SkillGameTests}、バッチ g1_skills)とはバッチを分けた(ゾンビの「仲間を呼ぶ」が 16 ブロック先まで届くので、隣り合わせにしない)。
 * テスト場は 7 × 7(床は相対 y = 1、物は y = 2 に置く)。升の浮遊・降り積もりは列に積まれてから場へ反映されるので、場の値は読まず、Mob が持つ
 * 回数・着地点で確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1Skill2GameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1Skill2GameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_skills2";

    private G1Skill2GameTests() {
    }

    private static DevelopedParasitized spawnDev(GameTestHelper helper, G1Developed d, Vec3 at) {
        DevelopedParasitized m = (DevelopedParasitized) helper.spawn(ModG1.developed(d).get(), at);
        m.targetSelector.removeAllGoals(g -> true); // 標的はテストが決める
        return m;
    }

    /** 種の技だけ残す(うろつき・1 撃を外して、その場に留める)。 */
    private static void onlyTechniques(DevelopedParasitized m) {
        m.goalSelector.removeAllGoals(g -> !(g instanceof G1TechniqueGoal));
    }

    private static void face(Mob m, float yaw) {
        m.setYRot(yaw);
        m.setYBodyRot(yaw);
        m.yHeadRot = yaw;
    }

    /** 動かない的(体の物理は効く)。体力を上げ、打たれても死なない。 */
    private static Cow spawnCow(GameTestHelper helper, Vec3 at) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, at);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        cow.setHealth(200.0F);
        return cow;
    }

    private static void keepTarget(GameTestHelper helper, Mob mob, LivingEntity target) {
        helper.onEachTick(() -> {
            if (mob.isAlive() && mob.getTarget() != target) mob.setTarget(target);
        });
    }

    private static void noTarget(GameTestHelper helper, Mob mob) {
        helper.onEachTick(() -> {
            if (mob.getTarget() != null) mob.setTarget(null);
        });
    }

    private static List<G1MilkPuddle> puddles(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(G1MilkPuddle.class, new AABB(helper.absolutePos(new BlockPos(3, 2, 3))).inflate(4.0D));
    }

    // ------------------------------------------------------------------ 牛
    /**
     * (b) 胞子の乳: 後ろにいる獲物へ噴く。浴びた相手は良い効果だけ消え(悪い効果は残る)、暴露量 +200。正面の相手は浴びない。地面に乳だまりが残る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void cowMilkStripsBuffsBehindAndLeavesPuddles(GameTestHelper helper) {
        SporeAmbient.isolate(helper, 100); // 隣のテスト場から広がる胞子の場の浮遊を読まない(技が直に与えた分だけを見る。2026-11-18 揺れるテストの調べ)
        DevelopedParasitized cow = spawnDev(helper, G1Developed.COW, new Vec3(3.5, 2.0, 3.8));
        onlyTechniques(cow);
        face(cow, 0.0F); // 南(+Z)向き
        noTarget(helper, cow);
        Cow behind = spawnCow(helper, new Vec3(3.5, 2.0, 1.6));
        Cow front = spawnCow(helper, new Vec3(3.5, 2.0, 6.0));
        for (Cow c : List.of(behind, front)) {
            c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 1200, 0));
            c.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 1200, 0));
        }
        cow.forceSkillsReady();
        helper.succeedWhen(() -> {
            helper.assertTrue(!behind.hasEffect(MobEffects.DAMAGE_RESISTANCE), "後ろの相手の良い効果が消えていない");
            helper.assertTrue(behind.hasEffect(MobEffects.WEAKNESS), "悪い効果まで消えた");
            helper.assertTrue(SporeExposureTracker.dose(behind) >= 199.0D, "浴びた暴露量が足りない: " + SporeExposureTracker.dose(behind));
            helper.assertTrue(front.hasEffect(MobEffects.DAMAGE_RESISTANCE), "正面の相手まで浴びた");
            helper.assertTrue(SporeExposureTracker.dose(front) < 1.0D, "正面の相手に暴露量が足された " + SporeAmbient.describe(helper.getLevel(), front));
            helper.assertTrue(!puddles(helper).isEmpty(), "乳だまりが残っていない");
        });
    }

    /** (b) 乳だまりを踏むと良い効果だけ消える(悪い効果は残る・暴露量は足さない)。6 秒で消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void milkPuddleStripsBuffsOnStepThenFades(GameTestHelper helper) {
        SporeAmbient.isolate(helper, 200); // 隣のテスト場から広がる胞子の場の浮遊を読まない(技が直に与えた分だけを見る。2026-11-18 揺れるテストの調べ)
        G1MilkPuddle puddle = G1MilkPuddle.spawn(helper.getLevel(), helper.absoluteVec(new Vec3(3.5, 2.0, 3.5)), 1.0F, 120);
        helper.assertTrue(puddle != null, "床の上に乳だまりを置けない");
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 3.5));
        cow.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 0));
        cow.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 1200, 0));
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 20, () -> {
            helper.assertTrue(!cow.hasEffect(MobEffects.MOVEMENT_SPEED), "踏んでも良い効果が消えない");
            helper.assertTrue(cow.hasEffect(MobEffects.WEAKNESS), "悪い効果まで消えた");
            helper.assertTrue(SporeExposureTracker.dose(cow) < 1.0D, "踏んだだけで暴露量が足された " + SporeAmbient.describe(helper.getLevel(), cow));
        });
        helper.runAtTickTime(t0 + 130, () -> {
            helper.assertTrue(puddles(helper).isEmpty(), "乳だまりが 6 秒で消えない");
            helper.succeed();
        });
    }

    /** (c) 歩く発生源: 歩いている牛だけが 100 tick ごとに放出する。止まっている牛は出さない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void cowWalkingSourceEmitsOnlyWhileMoving(GameTestHelper helper) {
        DevelopedParasitized walker = spawnDev(helper, G1Developed.COW, new Vec3(1.5, 2.0, 1.5));
        walker.goalSelector.removeAllGoals(g -> true);
        DevelopedParasitized stander = spawnDev(helper, G1Developed.COW, new Vec3(5.5, 2.0, 3.5));
        stander.goalSelector.removeAllGoals(g -> true);
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5)), b = helper.absoluteVec(new Vec3(1.5, 2.0, 5.5));
        boolean[] toB = {true};
        helper.onEachTick(() -> {
            Vec3 goal = toB[0] ? b : a;
            if (walker.position().distanceTo(goal) < 0.8D) toB[0] = !toB[0];
            if (walker.getNavigation().isDone()) walker.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
        });
        helper.runAfterDelay(215, () -> {
            helper.assertTrue(walker.walkingSource().emits() >= 1, "歩いた牛が放出していない");
            helper.assertTrue(stander.walkingSource().emits() == 0, "止まっている牛が放出した: " + stander.walkingSource().emits());
            helper.succeed();
        });
    }

    /** 破裂の跡に乳だまりを 1 つ残す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void cowDeathLeavesPuddle(GameTestHelper helper) {
        DevelopedParasitized cow = spawnDev(helper, G1Developed.COW, new Vec3(3.5, 2.0, 3.5));
        cow.goalSelector.removeAllGoals(g -> true);
        helper.runAfterDelay(2, cow::kill);
        helper.succeedWhen(() -> {
            helper.assertTrue(cow.isRemoved(), "まだ破裂していない");
            helper.assertTrue(puddles(helper).size() == 1, "破裂の跡の乳だまりが 1 つでない: " + puddles(helper).size());
        });
    }

    // ------------------------------------------------------------------ 羊
    /**
     * (b) 草を食べる: 草ブロックの跡が汚染した土(過繁茂の土)になり、綿毛が 1 段戻る。遠くに標的がいても(近くに獲物がいなければ)食べる。
     * 狭いテスト場なので「近く」の半径を 2 ブロックに縮め、3 ブロック先の牛を標的にさせる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void sheepGrazesGrassIntoOvergrownDirtAndRegrowsFluff(GameTestHelper helper) {
        BlockPos grass = new BlockPos(3, 1, 4);
        helper.setBlock(grass, Blocks.GRASS_BLOCK);
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 4.5));
        sheep.goalSelector.removeAllGoals(g -> g instanceof G1StrikeGoal); // 追わせない(その場で食べる)
        sheep.setFluff(0); // 綿毛が残っていると先に放つので、0 段から
        sheep.setCalmRadiusOverride(2.0D);
        Cow far = spawnCow(helper, new Vec3(3.5, 2.0, 1.0));
        keepTarget(helper, sheep, far);
        sheep.forceSkillsReady();
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(ModBlocks.OVERGROWN_DIRT.get(), grass);
            helper.assertTrue(sheep.fluff() == 1, "綿毛が 1 段戻っていない: " + sheep.fluff());
        });
    }

    /** (b) 草の無い所(石)では食べられず、綿毛は戻らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void sheepCannotGrazeWithoutGrass(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(sheep);
        sheep.setFluff(0); // 綿毛が無いので放てもしない
        noTarget(helper, sheep);
        helper.onEachTick(sheep::forceSkillsReady);
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(sheep.fluff() == 0, "草が無いのに綿毛が戻った: " + sheep.fluff());
            helper.succeed();
        });
    }

    /** (b) 鋏で刈れる: 綿毛が 0 段になり、覆われた羊毛が落ち、そばの相手に暴露量 +300。綿毛の無い羊は刈れない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void sheepShearedDropsWoolAndPuffsSpores(GameTestHelper helper) {
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 3.5));
        sheep.goalSelector.removeAllGoals(g -> true);
        Cow near = spawnCow(helper, new Vec3(3.5, 2.0, 2.2));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(sheep.interact(player, InteractionHand.MAIN_HAND).consumesAction(), "鋏で刈れない");
            helper.assertTrue(sheep.fluff() == 0, "刈っても綿毛が残った: " + sheep.fluff());
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, sheep.getBoundingBox().inflate(3.0D),
                    e -> e.getItem().is(ModItems.OVERGROWN_WOOL.get()));
            int wool = drops.stream().mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(wool >= 1 && wool <= 2, "覆われた羊毛が 1〜2 でない: " + wool);
            helper.assertTrue(SporeExposureTracker.dose(near) >= 299.0D, "刈ったときの胞子がそばの相手に届いていない: " + SporeExposureTracker.dose(near));
            helper.assertTrue(player.getMainHandItem().getDamageValue() == 1, "鋏がすり減っていない");
            helper.assertTrue(!sheep.interact(player, InteractionHand.MAIN_HAND).consumesAction(), "綿毛の無い羊を刈れた");
            helper.succeed();
        });
    }

    /**
     * (c) 綿毛を風に飛ばす: 綿毛が 1 段減り、着地点は風下 20〜40 ブロック(世界の卓越風の吹いていく向き ±10°)。
     * 遠くに標的がいても(近くに獲物がいなければ)放つ。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void sheepLoosesFluffDownwind(GameTestHelper helper) {
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 4.5));
        sheep.goalSelector.removeAllGoals(g -> g instanceof G1StrikeGoal);
        sheep.setCalmRadiusOverride(2.0D);
        Cow far = spawnCow(helper, new Vec3(3.5, 2.0, 1.0));
        keepTarget(helper, sheep, far);
        sheep.forceSkillsReady();
        helper.succeedWhen(() -> {
            helper.assertTrue(sheep.fluff() == DevelopedParasitized.FLUFF_MAX - 1, "綿毛が 1 段減っていない: " + sheep.fluff());
            Vec3 to = sheep.lastFluffLanding();
            helper.assertTrue(to != null, "着地点が決まっていない");
            double dx = to.x - sheep.getX(), dz = to.z - sheep.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            helper.assertTrue(d >= G1FluffDrift.MIN_DISTANCE - 0.5D && d <= G1FluffDrift.MAX_DISTANCE + 0.5D, "着地点が 20〜40 ブロックでない: " + d);
            PrevailingWind.Sample wind = SporeFieldServer.windNow(helper.getLevel());
            if (wind != null) {
                double dot = (dx * wind.dirX + dz * wind.dirZ) / d;
                helper.assertTrue(dot >= Math.cos(Math.toRadians(25.0D)), "着地点が風下でない: 内積 " + dot);
            }
        });
    }

    /** 着地点の計算(純関数): 凪で 20・ふだんの午後以上で 40 ブロック、向きは風の吹いていく向き。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fluffLandingFollowsWindStrength(GameTestHelper helper) {
        Vec3 calm = G1FluffDrift.landing(Vec3.ZERO, 1.0D, 0.0D, 0.0D, 0.0D);
        Vec3 breezy = G1FluffDrift.landing(Vec3.ZERO, 0.0D, 1.0D, 1.0D, 0.0D);
        Vec3 storm = G1FluffDrift.landing(Vec3.ZERO, 0.0D, -1.0D, 3.0D, 0.0D);
        helper.assertTrue(Math.abs(calm.x - 20.0D) < 1e-6 && Math.abs(calm.z) < 1e-6, "凪の着地点が東 20 でない: " + calm);
        helper.assertTrue(Math.abs(breezy.z - 40.0D) < 1e-6 && Math.abs(breezy.x) < 1e-6, "午後の風の着地点が南 40 でない: " + breezy);
        helper.assertTrue(Math.abs(storm.z + 40.0D) < 1e-6, "嵐で 40 を超えた / 北でない: " + storm);
        helper.succeed();
    }


    /**
     * 綿毛の降り積もりが胞子の場へ届く: 放つと、着地点の升(汚染されていない風下の升)の降り積もりが発芽のしきい値の 15% ぶん増える。
     * 場を直に回して読む(バッチは場のテストと同じ spore_field_world)。
     */
    @GameTest(template = TEMPLATE, batch = "spore_field_world", timeoutTicks = 100)
    public static void fluffDepositReachesFieldAtLanding(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        Vec3 from = helper.absoluteVec(new Vec3(3.5, 3.0, 3.5));
        Vec3 to = G1FluffDrift.loose(level, from, com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffLooseGoal.DEPOSIT_PERCENT, level.random);
        int cx = net.minecraft.util.Mth.floor(to.x) >> 4, cz = net.minecraft.util.Mth.floor(to.z) >> 4;
        SporeFieldServer.watchForTests(cx, cz);
        // 先に升を空にする(降り積もりの入力は列に積まれていて、場を回したときに足される)
        SporeFieldServer.withFieldForTests(field -> {
            field.clearDepositAround(cx, cz, 0.0D);
            // まわりの浮遊も消す: 隣のテスト場の技・場のテストが撒いた浮遊が 1 刻みの間に降り積もり、+30% ほど多く読めていた(2026-11-18 揺れるテストの調べ)
            field.extractAround(cx, cz, 2.0D, Double.POSITIVE_INFINITY);
            return null;
        });
        SporeFieldServer.catchUpForTests(level, com.yayuy.overgrownvegetation.data.DesignData.sporeField().updateTicks());
        SporeFieldServer.Cell cell = SporeFieldServer.cell(cx, cz);
        SporeFieldServer.withFieldForTests(field -> {
            field.clearDepositAround(cx, cz, 0.0D);
            return null;
        });
        double want = com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold * 0.15D;
        if (cell == null) {
            helper.fail("着地点の升を場から読めない");
            return;
        }
        helper.assertTrue(cell.dep() >= want * 0.7D && cell.dep() <= want * 1.3D,
                "着地点の降り積もりがしきい値の 15% でない: " + cell.dep() + "(期待 " + want + ")");
        helper.succeed();
    }

    /** 燃えやすい: 火のダメージ 1.5 倍。燃えている間は綿毛を飛ばせない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void sheepBurnsFasterAndCannotLooseOnFire(GameTestHelper helper) {
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(sheep);
        noTarget(helper, sheep);
        float h = sheep.getHealth();
        sheep.hurt(helper.getLevel().damageSources().onFire(), 2.0F);
        helper.assertTrue(Math.abs(h - sheep.getHealth() - 3.0F) < 0.01F, "火のダメージが 1.5 倍でない: " + (h - sheep.getHealth()));
        sheep.setHealth(sheep.getMaxHealth());
        helper.onEachTick(() -> {
            sheep.setRemainingFireTicks(40);
            sheep.setHealth(sheep.getMaxHealth()); // 焼け死なせない
            sheep.forceSkillsReady();
        });
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(sheep.fluff() == DevelopedParasitized.FLUFF_MAX, "燃えているのに綿毛を放った: " + sheep.fluff());
            helper.succeed();
        });
    }

    /**
     * (c) の冷却は 2 分【決定 11-13 ユーザー】: 1 回放ったあと、前の冷却(20 秒)なら 2 回目を放っていた 500 tick 後にも、綿毛は 1 段減ったまま。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 700)
    public static void sheepLooseCooldownIsTwoMinutes(GameTestHelper helper) {
        helper.assertTrue(com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffLooseGoal.COOLDOWN == 2400, "冷却が 2 分(2400 tick)でない");
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 4.5));
        onlyTechniques(sheep);
        noTarget(helper, sheep);
        sheep.forceSkillsReady();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(sheep.fluff() == DevelopedParasitized.FLUFF_MAX - 1, "まだ 1 回目を放っていない"))
                .thenIdle(500)
                .thenExecute(() -> helper.assertTrue(sheep.fluff() == DevelopedParasitized.FLUFF_MAX - 1,
                        "冷却 2 分の間に 2 回目を放った: 綿毛 " + sheep.fluff()))
                .thenSucceed();
    }

    /**
     * 放つ冷却と「綿毛が戻る間」は保存する【決定 11-13 ユーザー「羊のやつは一応直しておこう。」】: 草の上で 1 回放った羊を保存して読み込み直しても、
     * 2 回目をすぐ放たず(前は読み込み直すたびに 1 回放った)、草も食べ直さない(前は放った数秒後に食べて戻した)。
     * 読み込んだ側では冷却を飛ばさない(実際の保存 → 読み込みの経路)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void sheepCooldownsSurviveReload(GameTestHelper helper) {
        BlockPos grass = new BlockPos(3, 1, 4);
        helper.setBlock(grass, Blocks.GRASS_BLOCK);
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 4.5));
        onlyTechniques(sheep);
        noTarget(helper, sheep);
        sheep.forceSkillsReady();
        DevelopedParasitized[] loaded = new DevelopedParasitized[1];
        int looseCooldown = com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffLooseGoal.COOLDOWN;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(sheep.fluff() == DevelopedParasitized.FLUFF_MAX - 1, "まだ 1 回目を放っていない"))
                .thenIdle(60) // 技が終わって冷却を数え始めるまで
                .thenExecute(() -> {
                    helper.assertTrue(sheep.fluff() == DevelopedParasitized.FLUFF_MAX - 1, "放った数秒後に綿毛が変わった(食べ直した?): " + sheep.fluff());
                    net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                    sheep.saveWithoutId(tag);
                    long looseIn = tag.getLong("LooseIn");
                    long regrowIn = tag.getLong("FluffRegrowIn");
                    helper.assertTrue(looseIn > looseCooldown - 200 && looseIn <= looseCooldown, "放つ冷却の残りが保存されていない: " + looseIn);
                    helper.assertTrue(regrowIn > DevelopedParasitized.FLUFF_REGROW_TICKS - 200 && regrowIn <= DevelopedParasitized.FLUFF_REGROW_TICKS,
                            "綿毛が戻る間の残りが保存されていない: " + regrowIn);
                    tag.remove("UUID"); // 新しい個体として読み込む(同じ UUID を 2 つ置かない)
                    sheep.discard();
                    DevelopedParasitized fresh = (DevelopedParasitized) ModG1.developed(G1Developed.SHEEP).get().create(helper.getLevel());
                    helper.assertTrue(fresh != null, "羊を作れない");
                    fresh.load(tag);
                    helper.getLevel().addFreshEntity(fresh);
                    fresh.targetSelector.removeAllGoals(g -> true);
                    onlyTechniques(fresh);
                    noTarget(helper, fresh);
                    loaded[0] = fresh;
                })
                .thenIdle(300)
                .thenExecute(() -> {
                    helper.assertTrue(loaded[0].isAlive(), "読み込んだ羊がいない");
                    helper.assertTrue(loaded[0].fluff() == DevelopedParasitized.FLUFF_MAX - 1,
                            "読み込み直した後に放った・食べ直した: 綿毛 " + loaded[0].fluff());
                    helper.assertBlockPresent(Blocks.GRASS_BLOCK, grass);
                })
                .thenSucceed();
    }

    /**
     * 刈った後は「綿毛が戻る間」(1 分【仮】)が明けるまで草を食べ直さない(前は刈っても 10 秒ほどで 1 段戻り、すぐ放てた)。間が明ければ食べて戻す。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void sheepShornFluffDoesNotRegrowAtOnce(GameTestHelper helper) {
        helper.assertTrue(DevelopedParasitized.FLUFF_REGROW_TICKS == 1200, "綿毛が戻る間が 1 分(1200 tick)でない");
        BlockPos grass = new BlockPos(3, 1, 4);
        helper.setBlock(grass, Blocks.GRASS_BLOCK);
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 4.5));
        onlyTechniques(sheep);
        noTarget(helper, sheep);
        sheep.shear();
        helper.assertTrue(sheep.fluff() == 0, "刈れていない");
        helper.startSequence()
                .thenIdle(300)
                .thenExecute(() -> {
                    helper.assertTrue(sheep.fluff() == 0, "刈った直後に綿毛が戻った: " + sheep.fluff());
                    helper.assertBlockPresent(Blocks.GRASS_BLOCK, grass);
                    sheep.forceSkillsReady(); // 間を飛ばす = 間が明けたら
                })
                .thenWaitUntil(() -> {
                    helper.assertBlockPresent(ModBlocks.OVERGROWN_DIRT.get(), grass);
                    helper.assertTrue(sheep.fluff() >= 1 || sheep.lastFluffLanding() != null, "間が明けても綿毛が戻らない");
                })
                .thenSucceed();
    }

    /**
     * 爆ぜたとき、残っていた綿毛の一部が風下へ流れる【決定 11-13 ユーザー】: 綿毛を残して倒すと、破裂の所から風下 20〜40 ブロックに着地点が決まる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void sheepDeathDriftsFluffDownwind(GameTestHelper helper) {
        DevelopedParasitized sheep = spawnDev(helper, G1Developed.SHEEP, new Vec3(3.5, 2.0, 3.5));
        sheep.goalSelector.removeAllGoals(g -> true);
        helper.runAfterDelay(2, sheep::kill);
        helper.succeedWhen(() -> {
            helper.assertTrue(sheep.isRemoved(), "まだ破裂していない");
            Vec3 to = sheep.lastFluffLanding();
            helper.assertTrue(to != null, "爆ぜても綿毛が流れなかった");
            double dx = to.x - sheep.getX(), dz = to.z - sheep.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            helper.assertTrue(d >= G1FluffDrift.MIN_DISTANCE - 0.5D && d <= G1FluffDrift.MAX_DISTANCE + 0.5D, "着地点が 20〜40 ブロックでない: " + d);
            PrevailingWind.Sample wind = SporeFieldServer.windNow(helper.getLevel());
            if (wind != null) {
                double dot = (dx * wind.dirX + dz * wind.dirZ) / d;
                helper.assertTrue(dot >= Math.cos(Math.toRadians(25.0D)), "着地点が風下でない: 内積 " + dot);
            }
        });
    }

    /** 爆ぜの綿毛の対処: 刈ってから(綿毛 0 で)倒す・燃やして倒すと、綿毛は流れない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void sheepDeathDoesNotDriftWhenShornOrBurning(GameTestHelper helper) {
        DevelopedParasitized shorn = spawnDev(helper, G1Developed.SHEEP, new Vec3(1.5, 2.0, 1.5));
        DevelopedParasitized burning = spawnDev(helper, G1Developed.SHEEP, new Vec3(5.5, 2.0, 5.5));
        shorn.goalSelector.removeAllGoals(g -> true);
        burning.goalSelector.removeAllGoals(g -> true);
        shorn.setFluff(0);
        helper.onEachTick(() -> {
            if (!burning.isRemoved()) burning.setRemainingFireTicks(40);
        });
        helper.runAfterDelay(2, () -> {
            shorn.kill();
            burning.kill();
        });
        helper.runAfterDelay(50, () -> {
            helper.assertTrue(shorn.isRemoved() && burning.isRemoved(), "まだ破裂していない");
            helper.assertTrue(shorn.lastFluffLanding() == null, "綿毛が無いのに流れた");
            helper.assertTrue(burning.lastFluffLanding() == null, "燃えているのに綿毛が流れた");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 鶏
    /** (c) そ嚢の胞子: 正面の円錐の中に居続けた相手へ 1.5 秒で +225(150 / 秒)。後ろは浴びない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void chickenSpewDosesFrontCone(GameTestHelper helper) {
        SporeAmbient.isolate(helper, 120); // 隣のテスト場から広がる胞子の場の浮遊を読まない(技が直に与えた分だけを見る。2026-11-18 揺れるテストの調べ)
        DevelopedParasitized chicken = spawnDev(helper, G1Developed.CHICKEN, new Vec3(1.8, 2.0, 3.5));
        chicken.goalSelector.removeAllGoals(g -> g instanceof G1StrikeGoal); // つつきに行かせない
        face(chicken, -90.0F); // 東(+X)向き
        Cow cow = spawnCow(helper, new Vec3(4.0, 2.0, 3.5));
        Cow behind = spawnCow(helper, new Vec3(0.5, 2.0, 3.5));
        keepTarget(helper, chicken, cow);
        chicken.forceSkillsReady();
        helper.succeedWhen(() -> {
            helper.assertTrue(SporeExposureTracker.dose(cow) >= 220.0D, "円錐の中の暴露量が足りない: " + SporeExposureTracker.dose(cow));
            helper.assertTrue(SporeExposureTracker.dose(behind) < 1.0D, "後ろまで浴びた " + SporeAmbient.describe(helper.getLevel(), behind));
        });
    }

    /**
     * (b) 汚染された卵を産む: 足元に卵を置く → 同じ升にもう 1 個産むと 2 個になる → まわりの卵が 6 個に達すると産まない。
     * 卵は何もしない(段は進まない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 500)
    public static void chickenLaysCorruptedEggsUpToLimit(GameTestHelper helper) {
        DevelopedParasitized chicken = spawnDev(helper, G1Developed.CHICKEN, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(chicken);
        noTarget(helper, chicken);
        BlockPos nest = new BlockPos(3, 2, 3);
        chicken.forceSkillsReady();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(chicken.eggsLaid() == 1, "まだ 1 個目を産んでいない");
                    helper.assertBlockPresent(ModG1.CORRUPTED_EGG.get(), nest);
                })
                .thenIdle(35) // 立ち上がるまで(産む 10 + 立ち上がる 20)
                .thenExecute(() -> {
                    helper.assertTrue(chicken.layReadyAt() - helper.getLevel().getGameTime() >= 3000, "次の産卵まで 3 分以上空いていない");
                    chicken.forceSkillsReady();
                })
                .thenWaitUntil(() -> {
                    BlockState s = helper.getBlockState(nest);
                    helper.assertTrue(chicken.eggsLaid() == 2 && s.getBlock() instanceof CorruptedEggBlock && s.getValue(CorruptedEggBlock.EGGS) == 2,
                            "同じ升の卵が 2 個になっていない");
                    helper.assertTrue(s.getValue(CorruptedEggBlock.HATCH) == 0, "卵の段が進んだ");
                })
                .thenIdle(35)
                .thenExecute(() -> {
                    // まわりの卵を 6 個にする(足元 2 + 4)
                    helper.setBlock(new BlockPos(1, 2, 1), ModG1.CORRUPTED_EGG.get().defaultBlockState().setValue(CorruptedEggBlock.EGGS, 4));
                    chicken.forceSkillsReady();
                })
                .thenIdle(80)
                .thenExecute(() -> helper.assertTrue(chicken.eggsLaid() == 2, "上限(6 個)を超えて産んだ: " + chicken.eggsLaid()))
                .thenSucceed();
    }

    /**
     * 汚染された卵は踏むと割れて消える【決定 11-13 ユーザー】: 植物側でない生き物(バニラの牛)が上に乗ると、升ごと(3 個まとめて)消え、何も落とさない。
     * 植物側(1 段目の鶏)が乗っていても割れない(産んだ鶏が自分の足元の卵を割らない)。プレイヤーは創造でも割り、観戦は割らない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void corruptedEggBreaksUnderFootButNotUnderPlantSide(GameTestHelper helper) {
        BlockPos trampled = new BlockPos(4, 2, 4);
        BlockPos kept = new BlockPos(1, 2, 1);
        helper.setBlock(trampled, ModG1.CORRUPTED_EGG.get().defaultBlockState().setValue(CorruptedEggBlock.EGGS, 3));
        helper.setBlock(kept, ModG1.CORRUPTED_EGG.get().defaultBlockState().setValue(CorruptedEggBlock.EGGS, 3));
        // 卵の上へ落とす(実際に踏む経路 = Entity#move → Block#stepOn)
        spawnCow(helper, new Vec3(4.5, 3.2, 4.5));
        DevelopedParasitized chicken = spawnDev(helper, G1Developed.CHICKEN, new Vec3(1.5, 3.2, 1.5));
        chicken.goalSelector.removeAllGoals(g -> true);
        helper.assertTrue(CorruptedEggBlock.tramples(helper.getLevel(), helper.makeMockPlayer(GameType.SURVIVAL)), "サバイバルのプレイヤーが割れない");
        helper.assertTrue(CorruptedEggBlock.tramples(helper.getLevel(), helper.makeMockPlayer(GameType.CREATIVE)), "創造のプレイヤーが割れない");
        helper.assertTrue(!CorruptedEggBlock.tramples(helper.getLevel(), helper.makeMockPlayer(GameType.SPECTATOR)), "観戦のプレイヤーが割れる");
        helper.assertTrue(!CorruptedEggBlock.tramples(helper.getLevel(), chicken), "植物側が割れる");
        helper.runAfterDelay(60, () -> {
            helper.assertBlockPresent(Blocks.AIR, trampled);
            BlockState s = helper.getBlockState(kept);
            helper.assertTrue(s.getBlock() instanceof CorruptedEggBlock && s.getValue(CorruptedEggBlock.EGGS) == 3, "植物側の鶏が乗った卵が割れた");
            helper.assertTrue(chicken.getY() > helper.absoluteVec(new Vec3(0.0, 2.3, 0.0)).y, "鶏が卵の上に乗っていない(テストの置き方の誤り)");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(trampled)).inflate(3.0D));
            helper.assertTrue(drops.isEmpty(), "割れた卵が何か落とした: " + drops.size());
            helper.succeed();
        });
    }
}
