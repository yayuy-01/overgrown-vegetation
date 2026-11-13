package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1BowShotGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Move;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SeedPod;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TechniqueGoal;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * G1 の 1 段目の種の技(2026-11-13、能力の第 3 回 = クリーパー・スケルトン)。技ごとに 1 本以上。見た目(アニメ・粒・莢の絵)はクライアントで確かめる。
 * バッチは第 1・2 回と分けた(爆ぜは半径 4 まで届き、床も覆う)。テスト場は 7 × 7(床は相対 y = 1、物は y = 2)。爆ぜる Mob は場の真ん中に置く
 * (覆う半径 3 + 揺らぎ 1・暴露の半径 4 が隣の場へ届かないように)。床は草ブロックに張り替えてから試す(覆える素材にそろえる)。
 * 升の浮遊は場の値では読まない(第 2 回と同じ)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1Skill3GameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1Skill3GameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_skills3";

    private G1Skill3GameTests() {
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

    /** 床(相対 y = 1 の 7 × 7)を草ブロックに張り替える。 */
    private static void grassFloor(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
    }

    /** 床のうち、汚染された(汚染ブロックに変わった・覆われた)マスの数。空気になったマスがあれば失敗(= ブロックを壊した)。 */
    private static int corruptedFloor(GameTestHelper helper) {
        int n = 0;
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                BlockPos abs = helper.absolutePos(new BlockPos(x, 1, z));
                BlockState s = helper.getLevel().getBlockState(abs);
                helper.assertTrue(!s.isAir(), "床のブロックが壊れた: " + x + "," + z);
                if (s.is(ModTags.CORRUPTED) || !s.is(Blocks.GRASS_BLOCK) || OvergrownLayerData.isCovered(helper.getLevel(), abs)) n++;
            }
        }
        return n;
    }

    private static List<G1SeedPod> pods(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(G1SeedPod.class, new AABB(helper.absolutePos(new BlockPos(3, 2, 3))).inflate(5.0D));
    }

    // ------------------------------------------------------------------ クリーパー
    /**
     * (b) 胞子の爆ぜ: 近くの獲物へ導火 → 爆ぜる。相手は 4 ダメージ + 暴露量(中心 600 → 縁 200)、床は壊れずに汚染される。
     * 自分は死なず、莢が空になる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void creeperBurstCoversGroundWithoutBreakingAndSurvives(GameTestHelper helper) {
        grassFloor(helper);
        DevelopedParasitized creeper = spawnDev(helper, G1Developed.CREEPER, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(creeper);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.3));
        keepTarget(helper, creeper, cow);
        creeper.forceSkillsReady();
        helper.succeedWhen(() -> {
            helper.assertTrue(!creeper.podsFull(), "まだ爆ぜていない");
            helper.assertTrue(creeper.isAlive(), "爆ぜで自分が死んだ");
            helper.assertTrue(cow.getHealth() <= 196.5F, "爆ぜのダメージ 4 が入っていない: " + cow.getHealth());
            helper.assertTrue(SporeExposureTracker.dose(cow) >= 199.0D, "浴びた暴露量が足りない: " + SporeExposureTracker.dose(cow));
            helper.assertTrue(corruptedFloor(helper) > 0, "床が汚染されていない");
        });
    }

    /** (b) 導火の間に 5 ブロックより離れると止まる(爆ぜない・莢は満ちたまま)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void creeperFuseStopsWhenTargetLeaves(GameTestHelper helper) {
        DevelopedParasitized creeper = spawnDev(helper, G1Developed.CREEPER, new Vec3(0.7, 2.0, 3.5));
        onlyTechniques(creeper);
        Cow cow = spawnCow(helper, new Vec3(2.7, 2.0, 3.5));
        keepTarget(helper, creeper, cow);
        creeper.forceSkillsReady();
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 15, () -> {
            helper.assertTrue(creeper.action() == G1Move.FUSE.id(), "導火が始まっていない: " + creeper.action());
            Vec3 far = helper.absoluteVec(new Vec3(6.3, 2.0, 3.5));
            cow.teleportTo(far.x, far.y, far.z);
        });
        helper.runAtTickTime(t0 + 70, () -> {
            helper.assertTrue(creeper.podsFull(), "離れたのに爆ぜた");
            helper.assertTrue(creeper.action() != G1Move.FUSE.id() && creeper.action() != G1Move.BURST.id(), "導火が止まっていない");
            helper.assertTrue(SporeExposureTracker.dose(cow) < 1.0D, "離れた相手に暴露量が足された");
            helper.succeed();
        });
    }

    /** (b) 爆ぜは交戦 6 秒 + 薙ぎ払い 1 回の後で解禁される(それまでは近くにいても導火しない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void creeperFuseUnlocksAfterEngagingAndOneStrike(GameTestHelper helper) {
        DevelopedParasitized creeper = spawnDev(helper, G1Developed.CREEPER, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(creeper);
        creeper.setPodDropReadyAt(Long.MAX_VALUE); // 莢落としはこのテストでは見ない
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.3));
        keepTarget(helper, creeper, cow);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 150, () -> {
            helper.assertTrue(creeper.podsFull() && !creeper.fuseUnlocked(), "薙ぎ払いを振っていないのに解禁された");
            creeper.onStrikeEnd(10); // 薙ぎ払いを 1 回振り終えた
            helper.assertTrue(creeper.fuseUnlocked(), "交戦 6 秒 + 薙ぎ払い 1 回で解禁されない");
        });
        helper.runAtTickTime(t0 + 240, () -> {
            helper.assertTrue(!creeper.podsFull(), "解禁の後も爆ぜない");
            helper.succeed();
        });
    }

    /** (b) の後: 莢が空の間は導火しない・莢も落とさない。育ち直すとまた爆ぜられる。満ち具合は同期の値で分かる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void creeperEmptyPodsRegrowBeforeNextBurst(GameTestHelper helper) {
        DevelopedParasitized creeper = spawnDev(helper, G1Developed.CREEPER, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(creeper);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.3));
        keepTarget(helper, creeper, cow);
        creeper.forceSkillsReady();
        creeper.burstPods(); // 爆ぜた直後の状態(確かめ用の解禁は残る)
        boolean[] fusedWhileEmpty = {false};
        long t0 = helper.getTick();
        helper.onEachTick(() -> {
            if (helper.getTick() < t0 + 60 && creeper.action() == G1Move.FUSE.id()) fusedWhileEmpty[0] = true;
        });
        helper.runAtTickTime(t0 + 60, () -> {
            helper.assertTrue(!fusedWhileEmpty[0], "莢が空なのに導火した");
            helper.assertTrue(pods(helper).isEmpty(), "莢が空なのに莢を落とした");
            helper.assertTrue(creeper.pods() < 20, "空の莢の満ち具合が同期されていない: " + creeper.pods());
            creeper.setPodRegrowLeft(3);
        });
        helper.runAtTickTime(t0 + 70, () -> helper.assertTrue(creeper.pods() == 100, "育ち直した莢の満ち具合が 100 でない: " + creeper.pods()));
        helper.runAtTickTime(t0 + 180, () -> {
            // 育ち直した後にもう一度爆ぜている(= また空になっている)
            helper.assertTrue(!creeper.podsFull(), "育ち直したのに爆ぜない");
            helper.assertTrue(SporeExposureTracker.dose(cow) >= 199.0D, "2 回目の爆ぜの暴露量が無い");
            helper.succeed();
        });
    }

    /** 莢が空の間に倒されると、小さな破裂で消える(死体は残らない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void creeperDiesWithEmptyPods(GameTestHelper helper) {
        DevelopedParasitized creeper = spawnDev(helper, G1Developed.CREEPER, new Vec3(3.5, 2.0, 3.5));
        creeper.goalSelector.removeAllGoals(g -> true);
        creeper.burstPods();
        helper.runAfterDelay(2, creeper::kill);
        helper.succeedWhen(() -> helper.assertTrue(creeper.isRemoved(), "まだ破裂していない"));
    }

    /** (c) 莢を落とす: 獲物のそばで背の後ろへ莢を 1 つ落とす。莢は時間で割れ、近くの相手へ暴露量 +150、床を壊さずに汚染する。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void creeperDropsPodThatBurstsLater(GameTestHelper helper) {
        grassFloor(helper);
        DevelopedParasitized creeper = spawnDev(helper, G1Developed.CREEPER, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(creeper);
        face(creeper, 0.0F); // 南(+Z)向き → 莢は北(−Z)へ落ちる
        creeper.setPodDropReadyAt(0L); // 解禁は飛ばさない(爆ぜは交戦 6 秒 + 薙ぎ払いが要るので、このテストでは起きない)
        Cow target = spawnCow(helper, new Vec3(3.5, 2.0, 6.0));
        Cow victim = spawnCow(helper, new Vec3(3.5, 2.0, 1.6));
        keepTarget(helper, creeper, target);
        boolean[] shortened = {false};
        helper.succeedWhen(() -> {
            if (!shortened[0]) {
                List<G1SeedPod> found = pods(helper);
                helper.assertTrue(!found.isEmpty(), "莢が落ちていない");
                helper.assertTrue(found.size() == 1, "莢が 1 つでない: " + found.size());
                helper.assertTrue(SporeExposureTracker.dose(victim) < 1.0D, "割れる前に暴露量が足された");
                G1SeedPod pod = found.get(0);
                helper.assertTrue(pod.onGround(), "莢がまだ落ちている途中");
                pod.setFuse(pod.tickCount + 10); // 10 秒を待たずに割る
                shortened[0] = true;
            }
            helper.assertTrue(pods(helper).isEmpty(), "莢がまだ割れていない");
            helper.assertTrue(SporeExposureTracker.dose(victim) >= 149.0D, "割れた莢の暴露量が足りない: " + SporeExposureTracker.dose(victim));
            helper.assertTrue(victim.getHealth() >= 199.5F, "莢の破裂でダメージが入った");
            helper.assertTrue(corruptedFloor(helper) > 0, "莢の破裂で床が汚染されていない");
            helper.assertTrue(creeper.podsFull(), "莢を落としただけで背の莢が空になった");
        });
    }

    /** (c) 落ちた莢を叩くと、割れずに潰れて消える(撒かない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void seedPodSquashedByHitDoesNotSpread(GameTestHelper helper) {
        grassFloor(helper);
        G1SeedPod pod = G1SeedPod.drop(helper.getLevel(), helper.absoluteVec(new Vec3(3.5, 2.2, 3.5)), Vec3.ZERO);
        pod.setFuse(40);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 4.6));
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 10, () -> {
            helper.assertTrue(pod.hurt(helper.getLevel().damageSources().generic(), 1.0F), "莢を叩けない");
            helper.assertTrue(pod.isRemoved(), "叩いた莢が消えない");
        });
        helper.runAtTickTime(t0 + 60, () -> {
            helper.assertTrue(SporeExposureTracker.dose(cow) < 1.0D, "潰した莢が撒いた");
            helper.assertTrue(corruptedFloor(helper) == 0, "潰した莢が床を汚染した");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ スケルトン
    /** (b) 胞子の塊を射る: 3 ブロックより離れた見える相手へ射て、当たれば 2 ダメージ + 暴露量 +500。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void skeletonShootsBoltThatHurtsAndDoses(GameTestHelper helper) {
        DevelopedParasitized skeleton = spawnDev(helper, G1Developed.SKELETON, new Vec3(3.5, 2.0, 1.0));
        onlyTechniques(skeleton);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.6));
        keepTarget(helper, skeleton, cow);
        skeleton.forceSkillsReady();
        helper.succeedWhen(() -> {
            helper.assertTrue(skeleton.boltsShot() >= 1, "まだ射ていない");
            helper.assertTrue(!skeleton.anchored(), "10 ブロックより近い相手に据えた");
            helper.assertTrue(SporeExposureTracker.dose(cow) >= 499.0D, "当たった弾の暴露量が足りない: " + SporeExposureTracker.dose(cow));
            helper.assertTrue(cow.getHealth() <= 198.5F, "弾のダメージ 2 が入っていない: " + cow.getHealth());
        });
    }

    /** (b) 外れた弾は着弾点を汚染する(ブロックは壊さない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void sporeBoltMissCorruptsLanding(GameTestHelper helper) {
        grassFloor(helper);
        DevelopedParasitized skeleton = spawnDev(helper, G1Developed.SKELETON, new Vec3(1.0, 2.0, 1.0));
        skeleton.goalSelector.removeAllGoals(g -> true);
        G1SporeBolt bolt = new G1SporeBolt(helper.getLevel(), skeleton).setLoad(G1BowShotGoal.DOSE, G1BowShotGoal.DAMAGE);
        Vec3 from = helper.absoluteVec(new Vec3(4.5, 4.0, 4.5));
        bolt.setPos(from);
        bolt.shoot(0.0D, -1.0D, 0.0D, G1BowShotGoal.SPEED, 0.0F);
        helper.getLevel().addFreshEntity(bolt);
        helper.succeedWhen(() -> {
            helper.assertTrue(bolt.isRemoved(), "弾がまだ飛んでいる");
            BlockPos abs = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockState s = helper.getLevel().getBlockState(abs);
            helper.assertTrue(!s.isAir(), "着弾点のブロックが壊れた");
            helper.assertTrue(s.is(ModTags.CORRUPTED) || !s.is(Blocks.GRASS_BLOCK) || OvergrownLayerData.isCovered(helper.getLevel(), abs),
                    "着弾点が汚染されていない: " + s);
        });
    }

    /** (b) 盾で受ければ暴露なし。盾が無ければ暴露量 +500。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void sporeBoltBlockedByShieldGivesNoDose(GameTestHelper helper) {
        DevelopedParasitized skeleton = spawnDev(helper, G1Developed.SKELETON, new Vec3(3.5, 2.0, 1.0));
        skeleton.goalSelector.removeAllGoals(g -> true);
        Vec3 at = helper.absoluteVec(new Vec3(3.5, 2.0, 5.0));
        Player shielded = helper.makeMockPlayer(GameType.SURVIVAL);
        Player bare = helper.makeMockPlayer(GameType.SURVIVAL);
        for (Player p : List.of(shielded, bare)) {
            p.setPos(at);
            p.setYRot(180.0F); // 北(−Z)= スケルトンの方を向く
            p.setYHeadRot(180.0F);
        }
        shielded.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        shielded.startUsingItem(InteractionHand.OFF_HAND);
        for (int i = 0; i < 6; i++) shielded.tick(); // 盾は構えて 5 tick 後から防ぐ
        helper.assertTrue(shielded.isBlocking(), "偽プレイヤーが盾を構えられない");
        for (Player p : List.of(shielded, bare)) {
            G1SporeBolt bolt = new G1SporeBolt(helper.getLevel(), skeleton).setLoad(G1BowShotGoal.DOSE, G1BowShotGoal.DAMAGE);
            bolt.setPos(at.x, at.y + 1.2D, at.z - 1.0D); // 正面 1 ブロック
            bolt.hitForTests(p);
        }
        helper.assertTrue(SporeExposureTracker.dose(shielded) < 1.0D, "盾で受けたのに暴露量が足された: " + SporeExposureTracker.dose(shielded));
        helper.assertTrue(SporeExposureTracker.dose(bare) >= 499.0D, "盾なしの相手に暴露量が足されない: " + SporeExposureTracker.dose(bare));
        helper.succeed();
    }

    /** 弓の間合い(3〜16 ブロックで見えている)では、近づかずに立って射る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void skeletonHoldsGroundInBowRange(GameTestHelper helper) {
        DevelopedParasitized skeleton = spawnDev(helper, G1Developed.SKELETON, new Vec3(3.5, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.6));
        keepTarget(helper, skeleton, cow);
        Vec3 start = skeleton.position();
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(skeleton.position().distanceTo(start) < 0.6D, "弓の間合いなのに近づいた: " + skeleton.position().distanceTo(start));
            helper.assertTrue(skeleton.boltsShot() >= 1, "立ったまま射ていない");
            helper.succeed();
        });
    }

    /**
     * (c) 根を張って据え撃ち: 離れた見える相手に据える。据えている間は押し返されず、弾が速い。時間が来たら根を抜く。
     * 狭いテスト場なので据える距離を 3 ブロックに縮める。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void skeletonAnchorsShootsFasterAndReleases(GameTestHelper helper) {
        DevelopedParasitized skeleton = spawnDev(helper, G1Developed.SKELETON, new Vec3(3.5, 2.0, 1.0));
        onlyTechniques(skeleton);
        skeleton.setAnchorMinRangeOverride(3.0D);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.6));
        keepTarget(helper, skeleton, cow);
        skeleton.forceSkillsReady();
        boolean[] anchored = {false}, fast = {false}, pushed = {false}, released = {false};
        int[] seen = {0};
        Vec3[] held = {null};
        helper.onEachTick(() -> {
            if (skeleton.anchored() && !anchored[0]) {
                anchored[0] = true;
                held[0] = skeleton.position();
                skeleton.knockback(1.0D, 0.0D, 1.0D); // 据えている間は押し返されない
            }
            if (anchored[0] && !released[0] && skeleton.anchored() && held[0] != null && skeleton.position().distanceTo(held[0]) > 0.1D) {
                pushed[0] = true;
            }
            if (skeleton.anchored() && skeleton.boltsShot() > seen[0]) {
                seen[0] = skeleton.boltsShot();
                G1SporeBolt bolt = skeleton.lastBolt();
                if (bolt != null && bolt.tickCount <= 1 && bolt.getDeltaMovement().length() > 1.2D) {
                    fast[0] = true;
                    skeleton.setAnchorLeft(1); // 速い弾を見たら、10 秒を待たずに根を抜かせる
                }
            }
            if (anchored[0] && !skeleton.anchored()) released[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(anchored[0], "据えていない");
            helper.assertTrue(fast[0], "据えている間の弾が速くない");
            helper.assertTrue(!pushed[0], "据えている間に押し返された");
            helper.assertTrue(released[0], "根を抜いていない");
            helper.assertTrue(skeleton.action() == 0, "根を抜いた後に行動の段が空きへ戻らない: " + skeleton.action());
        });
    }
}
