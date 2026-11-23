package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.RootsilkWebBlock;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Move;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1PerchGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1RootsilkBolt;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SpitGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Tangle;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TechniqueGoal;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;

/**
 * G1 の 1 段目の種の技(2026-11-13、能力の第 4 回 = クモ・エンダーマン)。技ごとに 1 本以上。見た目(アニメ・粒・巣の絵・抱えた土)はクライアントで確かめる。
 * バッチは前の回と分けた。テスト場は 7 × 7(床は相対 y = 1、物は y = 2、天井のバリアは y = 8 あたり)。
 * クモは幅 1.4 なので壁から 0.75 以上離して置く。エンダーマンの転移は場の外へ出ないように、距離を上書きして試す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1Skill4GameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1Skill4GameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_skills4";

    private G1Skill4GameTests() {
    }

    private static DevelopedParasitized spawnDev(GameTestHelper helper, G1Developed d, Vec3 at) {
        DevelopedParasitized m = (DevelopedParasitized) helper.spawn(ModG1.developed(d).get(), at);
        m.targetSelector.removeAllGoals(g -> true); // 標的はテストが決める
        return m;
    }

    /** 種の技だけ残す(うろつき・1 撃・待ち伏せを外して、その場に留める)。 */
    private static void onlyTechniques(DevelopedParasitized m) {
        m.goalSelector.removeAllGoals(g -> !(g instanceof G1TechniqueGoal));
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

    /** 東の端(x = 6, z = 3)に、床から天井近くまでの石の柱を立てる(クモが登る壁)。 */
    private static void wall(GameTestHelper helper) {
        for (int y = 2; y <= 7; y++) {
            helper.setBlock(new BlockPos(6, y, 3), Blocks.STONE.defaultBlockState());
        }
    }

    /** 柱の西の面で待ち伏せさせる(立つマス (5, 2, 3)・東を向いて 3 ブロック登る)。 */
    private static void forcePerch(GameTestHelper helper, DevelopedParasitized spider) {
        spider.forcePerch(new G1PerchGoal.Spot(helper.absolutePos(new BlockPos(5, 2, 3)), Direction.EAST, G1PerchGoal.MIN_CLIMB));
    }

    // ------------------------------------------------------------------ クモ
    /**
     * (b1) 壁登りと待ち伏せ: 壁を登って高い所に張り付く。登る速さはバニラのクモ(約 0.118 ブロック / tick)より遅い。
     * 張り付いたら落ちない(重力を切る)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void spiderClimbsWallSlowlyAndPerches(GameTestHelper helper) {
        wall(helper);
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(3.5, 2.0, 3.5));
        spider.goalSelector.removeAllGoals(g -> !(g instanceof G1PerchGoal));
        forcePerch(helper, spider);
        double floorY = spider.getY();
        double[] lastY = {floorY}, fastest = {0.0D}, heldAt = {Double.NaN};
        boolean[] climbAnim = {false};
        int[] held = {0};
        helper.onEachTick(() -> {
            double dy = spider.getY() - lastY[0];
            lastY[0] = spider.getY();
            if (spider.climbingWall()) {
                fastest[0] = Math.max(fastest[0], dy);
                if (spider.action() == G1Move.CLIMB.id()) climbAnim[0] = true;
            }
            if (spider.perched()) {
                if (Double.isNaN(heldAt[0])) heldAt[0] = spider.getY();
                held[0]++;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(spider.perched(), "張り付いていない(y = " + (spider.getY() - floorY) + ")");
            helper.assertTrue(held[0] >= 20, "張り付いてからまだ 20 tick 経っていない");
            helper.assertTrue(spider.getY() - floorY >= G1PerchGoal.MIN_CLIMB - 0.1D, "登った高さが足りない: " + (spider.getY() - floorY));
            helper.assertTrue(Math.abs(spider.getY() - heldAt[0]) < 0.05D, "張り付いてからずり落ちた: " + (spider.getY() - heldAt[0]));
            helper.assertTrue(spider.action() == G1Move.HANG_IDLE.id(), "張り付いた姿勢でない: " + spider.action());
            helper.assertTrue(climbAnim[0], "登りのアニメの段にならなかった");
            helper.assertTrue(fastest[0] > 0.02D && fastest[0] < 0.09D, "登る速さが違う(1 tick の最大): " + fastest[0]);
        });
    }

    /**
     * (b1) 下に獲物が来ると落ちて噛む: 攻撃 4 + 暴露量 +300 + 鈍足。自分は落下のダメージを受けない。着地の隙の後、行動の段が空きへ戻る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void spiderDropsOnPreyBelow(GameTestHelper helper) {
        wall(helper);
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(3.5, 2.0, 3.5));
        spider.goalSelector.removeAllGoals(g -> !(g instanceof G1PerchGoal));
        forcePerch(helper, spider);
        Cow[] cow = {null};
        boolean[] windup = {false}, slowed = {false};
        double[] dose = {0.0D}; // 暴露量は時間で減るので、いちばん多かった値で見る
        helper.onEachTick(() -> {
            if (cow[0] == null && spider.perched()) {
                cow[0] = spawnCow(helper, new Vec3(4.4, 2.0, 3.5)); // 張り付いた真下(水平 1 ブロックほど)
            }
            if (cow[0] != null) dose[0] = Math.max(dose[0], SporeExposureTracker.dose(cow[0]));
            if (spider.action() == G1Move.DROP_WINDUP.id()) windup[0] = true;
            if (cow[0] != null && cow[0].hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) slowed[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(cow[0] != null, "まだ張り付いていない");
            helper.assertTrue(windup[0], "落ちる前の揺れ(drop_windup)が無かった");
            helper.assertTrue(!spider.perched() && spider.onGround(), "まだ落ちていない");
            helper.assertTrue(cow[0].getHealth() <= 196.5F, "落ちて噛んだ 4 ダメージが入っていない: " + cow[0].getHealth());
            helper.assertTrue(cow[0] != null && com.yayuy.overgrownvegetation.spore.MobInfection.isInfected(cow[0]), "当たった Mob が感染していない(11-15 から 1 撃で必ず感染)");
            helper.assertTrue(slowed[0], "鈍足が付いていない");
            helper.assertTrue(spider.getHealth() >= spider.getMaxHealth(), "落下のダメージを受けた: " + spider.getHealth());
            helper.assertTrue(spider.action() == 0, "着地の後に行動の段が空きへ戻らない: " + spider.action());
        });
    }

    /** (b2) 跳びつき: 2〜4 ブロックの見える相手へ、沈んでから跳んで当てる(攻撃 4 + 暴露量 +300)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void spiderLeapsAtTargetAndHits(GameTestHelper helper) {
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(3.5, 2.0, 1.0));
        onlyTechniques(spider);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 4.4));
        keepTarget(helper, spider, cow);
        spider.forceSkillsReady();
        Vec3 start = spider.position();
        boolean[] windup = {false}, airborne = {false};
        double[] dose = {0.0D};
        helper.onEachTick(() -> {
            dose[0] = Math.max(dose[0], SporeExposureTracker.dose(cow));
            if (spider.action() == G1Move.LEAP_WINDUP.id()) windup[0] = true;
            if (spider.action() == G1Move.LEAP.id() && !spider.onGround()) airborne[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(windup[0], "跳ぶ前の沈み(leap_windup)が無かった");
            helper.assertTrue(airborne[0], "跳んでいない");
            helper.assertTrue(spider.position().distanceTo(start) > 1.2D, "前へ進んでいない: " + spider.position().distanceTo(start));
            helper.assertTrue(cow.getHealth() <= 196.5F, "跳びつきの 4 ダメージが入っていない: " + cow.getHealth());
            helper.assertTrue(com.yayuy.overgrownvegetation.spore.MobInfection.isInfected(cow), "当たった Mob が感染していない(11-15 から 1 撃で必ず感染)");
            helper.assertTrue(spider.getHealth() >= spider.getMaxHealth(), "自分が傷ついた: " + spider.getHealth());
        });
    }

    /**
     * (c) 根糸を吐く: 6〜10 ブロックの見える相手へ吐き、当たると鈍足 II + 跳べない(跳ぶ強さ 0)+ 暴露量 +300。時間が来るとほどける。
     * テスト場の対角(約 7 ブロック)を使う。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void spiderSpitTanglesTargetThenReleases(GameTestHelper helper) {
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(1.0, 2.0, 1.0));
        onlyTechniques(spider);
        Cow cow = spawnCow(helper, new Vec3(6.0, 2.0, 6.0));
        keepTarget(helper, spider, cow);
        spider.forceSkillsReady();
        double jump = cow.getAttributeValue(PortAttributes.JUMP_STRENGTH.get());
        boolean[] tangled = {false}, slow2 = {false}, released = {false};
        double[] dose = {0.0D}; // 暴露量は時間で減るので、いちばん多かった値で見る
        helper.onEachTick(() -> {
            dose[0] = Math.max(dose[0], SporeExposureTracker.dose(cow));
            if (!tangled[0] && G1Tangle.isTangled(cow)) {
                tangled[0] = cow.getAttributeValue(PortAttributes.JUMP_STRENGTH.get()) < 1.0E-6D;
                MobEffectInstance slow = cow.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
                slow2[0] = slow != null && slow.getAmplifier() == 1;
                // ほどけるのを見るので、次の根糸を吐かせない
                spider.goalSelector.removeAllGoals(g -> true);
            }
            if (tangled[0] && !G1Tangle.isTangled(cow) && Math.abs(cow.getAttributeValue(PortAttributes.JUMP_STRENGTH.get()) - jump) < 1.0E-6D) {
                released[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(spider.rootsilkShots() >= 1, "まだ吐いていない");
            helper.assertTrue(tangled[0], "当たった相手が跳べなくなっていない");
            helper.assertTrue(slow2[0], "鈍足 II が付いていない");
            helper.assertTrue(dose[0] >= 299.0D, "暴露量が足されていない: " + dose[0]); // 根糸はダメージの無い胞子(G1SporeDose)なので、傷の感染には入らない(11-15)
            helper.assertTrue(cow.getHealth() >= 200.0F, "根糸にダメージがある: " + cow.getHealth());
            helper.assertTrue(released[0], "時間が来ても跳ぶ強さが戻らない");
        });
    }

    /**
     * (c) 外れた根糸は着いた所に根糸の巣を張る。汚染していない地面の上の巣は時間が来ると枯れて消え、汚染した地面の上の巣は残る。
     * (巣の 1〜2 分の時計は、ブロックの tick を直に呼んで進める。)
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void rootsilkMissPlacesWebThatWithersOffCorruption(GameTestHelper helper) {
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(1.0, 2.0, 1.0));
        spider.goalSelector.removeAllGoals(g -> true);
        G1RootsilkBolt bolt = new G1RootsilkBolt(helper.getLevel(), spider).setLoad(G1SpitGoal.DOSE, G1SpitGoal.SLOW_TICKS);
        bolt.setPos(helper.absoluteVec(new Vec3(4.5, 4.0, 4.5)));
        bolt.shoot(0.0D, -1.0D, 0.0D, G1SpitGoal.SPEED, 0.0F);
        helper.getLevel().addFreshEntity(bolt);
        // 汚染した地面(過繁茂の土)の上の巣
        helper.setBlock(new BlockPos(2, 1, 2), ModBlocks.OVERGROWN_DIRT.get().defaultBlockState());
        BlockPos onCorrupted = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.assertTrue(RootsilkWebBlock.placeAt(helper.getLevel(), onCorrupted), "汚染した地面の上に巣を張れない");
        boolean[] checked = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(bolt.isRemoved(), "弾がまだ飛んでいる");
            BlockPos landed = helper.absolutePos(new BlockPos(4, 2, 4));
            if (!checked[0]) {
                helper.assertTrue(bolt.webPlaced(), "外れた根糸が巣を張っていない");
                BlockState web = helper.getLevel().getBlockState(landed);
                helper.assertTrue(web.is(ModG1.ROOTSILK_WEB.get()), "着いた所が根糸の巣でない: " + web);
                helper.assertTrue(!helper.getLevel().getBlockState(landed.below()).isAir(), "着いた所の床が壊れた");
                // 時計を進める(置かれてから 1〜2 分後の確かめ)
                web.tick(helper.getLevel(), landed, helper.getLevel().random);
                BlockState kept = helper.getLevel().getBlockState(onCorrupted);
                kept.tick(helper.getLevel(), onCorrupted, helper.getLevel().random);
                checked[0] = true;
            }
            helper.assertTrue(helper.getLevel().getBlockState(landed).isAir(), "汚染していない地面の上の巣が枯れない");
            helper.assertTrue(helper.getLevel().getBlockState(onCorrupted).is(ModG1.ROOTSILK_WEB.get()), "汚染した地面の上の巣が消えた");
        });
    }

    /** (c) 根糸の巣は中の相手を大きく遅くする。クモ(1 段目)は遅くならない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void rootsilkWebSlowsOthersButNotSpiders(GameTestHelper helper) {
        // 汚染した床の上に巣を 2 つ(枯れないように)
        for (BlockPos p : List.of(new BlockPos(1, 2, 1), new BlockPos(1, 2, 5))) {
            helper.setBlock(p.below(), ModBlocks.OVERGROWN_DIRT.get().defaultBlockState());
            helper.setBlock(p, ModG1.ROOTSILK_WEB.get().defaultBlockState());
        }
        Cow cow = spawnCow(helper, new Vec3(1.5, 2.0, 1.5));
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(1.5, 2.0, 5.5));
        spider.goalSelector.removeAllGoals(g -> true);
        Vec3 cowStart = cow.position(), spiderStart = spider.position();
        int[] t = {0};
        helper.onEachTick(() -> {
            if (t[0]++ < 6) {
                // 東へ押す(巣の中の相手は移動が 1/4 になる)
                cow.setDeltaMovement(0.25D, cow.getDeltaMovement().y, 0.0D);
                spider.setDeltaMovement(0.25D, spider.getDeltaMovement().y, 0.0D);
            }
        });
        helper.runAfterDelay(12, () -> {
            double cowMoved = cow.getX() - cowStart.x, spiderMoved = spider.getX() - spiderStart.x;
            helper.assertTrue(cowMoved < 0.7D, "巣の中の牛が遅くなっていない: " + cowMoved);
            helper.assertTrue(spiderMoved > 1.0D, "クモが巣で遅くなった: " + spiderMoved);
            helper.succeed();
        });
    }

    /** (c) 盾で受ければ絡まらない(暴露もなし)。盾が無ければ絡まって暴露量 +300。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void rootsilkBlockedByShieldDoesNotTangle(GameTestHelper helper) {
        SporeAmbient.isolate(helper, 40); // 隣のテスト場から広がる胞子の場の浮遊を読まない(技が直に与えた分だけを見る。2026-11-18 揺れるテストの調べ)
        DevelopedParasitized spider = spawnDev(helper, G1Developed.SPIDER, new Vec3(3.5, 2.0, 1.0));
        spider.goalSelector.removeAllGoals(g -> true);
        Vec3 at = helper.absoluteVec(new Vec3(3.5, 2.0, 5.0));
        Player shielded = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        Player bare = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        for (Player p : List.of(shielded, bare)) {
            p.setPos(at);
            p.setYRot(180.0F); // 北(−Z)= クモの方を向く
            p.setYHeadRot(180.0F);
        }
        shielded.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        shielded.startUsingItem(InteractionHand.OFF_HAND);
        for (int i = 0; i < 6; i++) shielded.tick(); // 盾は構えて 5 tick 後から防ぐ
        helper.assertTrue(shielded.isBlocking(), "偽プレイヤーが盾を構えられない");
        for (Player p : List.of(shielded, bare)) {
            G1RootsilkBolt bolt = new G1RootsilkBolt(helper.getLevel(), spider).setLoad(G1SpitGoal.DOSE, G1SpitGoal.SLOW_TICKS);
            bolt.setPos(at.x, at.y + 1.2D, at.z - 1.0D); // 正面 1 ブロック
            bolt.hitForTests(p);
        }
        helper.assertTrue(!G1Tangle.isTangled(shielded), "盾で受けたのに絡まった");
        helper.assertTrue(SporeExposureTracker.dose(shielded) < 1.0D, "盾で受けたのに暴露量が足された: " + SporeExposureTracker.dose(shielded));
        helper.assertTrue(G1Tangle.isTangled(bare), "盾なしの相手が絡まらない");
        helper.assertTrue(bare.getAttributeValue(PortAttributes.JUMP_STRENGTH.get()) < 1.0E-6D, "盾なしの相手が跳べる");
        helper.assertTrue(SporeExposureTracker.dose(bare) >= 299.0D, "盾なしの相手に暴露量が足されない: " + SporeExposureTracker.dose(bare));
        G1Tangle.release(bare);
        helper.succeed();
    }

    // ------------------------------------------------------------------ エンダーマン
    /** 床(相対 y = 1)の x = {@code x0}〜{@code x1} の帯を過繁茂の土(汚染した地面)に張り替える。 */
    private static void corruptFloor(GameTestHelper helper, int x0, int x1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), ModBlocks.OVERGROWN_DIRT.get().defaultBlockState());
            }
        }
    }

    /** 場の中で過繁茂の土が置かれているマスの数(高さ {@code y} の 7 × 7)。 */
    private static int soilAt(GameTestHelper helper, int y) {
        int n = 0;
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                if (helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))).is(ModBlocks.OVERGROWN_DIRT.get())) n++;
            }
        }
        return n;
    }

    private static DevelopedParasitized spawnEnderman(GameTestHelper helper, Vec3 at) {
        DevelopedParasitized e = spawnDev(helper, G1Developed.ENDERMAN, at);
        e.setTeleportRangeOverride(2.0D, 2.6D); // 転移が場の外へ出ないように
        return e;
    }

    /**
     * (b) 転移: 被弾すると溜め(teleport_windup 15 tick)の後に渡る。溜めの間はその場にいる(= 当てられる)。渡ったら 6 秒休む
     * (その間にまた被弾しても渡らない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void endermanHurtChargesThenTeleportsWithCooldown(GameTestHelper helper) {
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(ender);
        Cow cow = spawnCow(helper, new Vec3(5.5, 2.0, 5.5));
        cow.setInvulnerable(true); // 標的にしない(被弾のきっかけだけに使う)
        int[] windupTicks = {0}, t = {0};
        boolean[] movedDuringWindup = {false}, arrive = {false};
        Vec3[] held = {null};
        helper.onEachTick(() -> {
            t[0]++;
            if (t[0] == 5 || t[0] == 60) {
                // 押し返しの付かない当て方(爆発の種類・出どころは牛)。殴り(mobAttack)だと跳ね上がって 10 tick ほど滑り、
                // 溜めの間の位置が揺れる。60 tick 目は休みの間(渡らない)
                ender.hurt(helper.getLevel().damageSources().explosion(cow, cow), 1.0F);
            }
            if (ender.action() == G1Move.TELEPORT_WINDUP.id() && ender.teleports() == 0) {
                windupTicks[0]++;
                // 溜めの間はその場にいる(= 当てられる)
                if (windupTicks[0] == 2) held[0] = ender.position();
                if (windupTicks[0] > 2 && held[0] != null && ender.position().distanceTo(held[0]) > 0.15D) movedDuringWindup[0] = true;
            }
            if (ender.action() == G1Move.TELEPORT_IN.id()) arrive[0] = true;
        });
        helper.runAfterDelay(110, () -> {
            helper.assertTrue(windupTicks[0] >= 13 && windupTicks[0] <= 15, "溜めの長さが違う: " + windupTicks[0]);
            helper.assertTrue(!movedDuringWindup[0], "溜めの間に動いた");
            helper.assertTrue(arrive[0], "着いた後の立て直し(teleport_in)が無かった");
            helper.assertTrue(ender.teleports() == 1, "渡った回数が 1 でない(休みの間の被弾でも渡った?): " + ender.teleports());
            helper.assertTrue(ender.lastTeleportFrom() != null && ender.position().distanceTo(ender.lastTeleportFrom()) >= 1.9D, "渡った距離が足りない");
            helper.succeed();
        });
    }

    /** (b) 転移: 標的が遠い(テストでは 4 ブロックより遠いに縮める)と、溜めて標的のそばへ渡る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void endermanTeleportsNearFarTarget(GameTestHelper helper) {
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(1.0, 2.0, 1.0));
        ender.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1TeleportGoal));
        ender.setFarRangeOverride(4.0D);
        ender.setTeleportRangeOverride(3.0D, 3.5D); // 標的(約 6.4 ブロック先)のそばへ 1 回で届く距離
        Cow cow = spawnCow(helper, new Vec3(5.5, 2.0, 5.5));
        keepTarget(helper, ender, cow);
        helper.succeedWhen(() -> {
            helper.assertTrue(ender.teleports() >= 1, "まだ渡っていない");
            helper.assertTrue(ender.distanceTo(cow) <= 4.2D, "標的のそばへ着いていない: " + ender.distanceTo(cow));
        });
    }

    /**
     * (b) 汚染した土を抱えて植える: 汚染した土を拾い(拾ったマスは空気)、抱えてしばらくしたら渡って、着いた所に置く。
     * 置いた土は床の上(相対 y = 2)に 1 つ増える。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void endermanCarriesCorruptedSoilAndPlantsIt(GameTestHelper helper) {
        corruptFloor(helper, 2, 4);
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(3.5, 2.0, 3.5));
        onlyTechniques(ender);
        int before = soilAt(helper, 1);
        boolean picked = false;
        for (int i = 0; i < 20 && !picked; i++) picked = ender.tryPickSoil();
        helper.assertTrue(picked, "汚染した土を拾えない");
        helper.assertTrue(ender.carried() != null && ender.carried().is(ModBlocks.OVERGROWN_DIRT.get()), "抱えた物が違う: " + ender.carried());
        helper.assertTrue(soilAt(helper, 1) == before - 1, "拾ったマスが空気になっていない");
        helper.succeedWhen(() -> {
            helper.assertTrue(ender.teleports() >= 1, "抱えたまま渡っていない(抱えて " + ender.carriedTicks() + " tick)");
            helper.assertTrue(ender.soilPlanted() == 1, "着いた所に置いていない");
            helper.assertTrue(ender.carried() == null, "置いた後も抱えている");
            // 置く所は床の上か、床の穴(拾った跡)。どちらでも土の数は元に戻る
            helper.assertTrue(soilAt(helper, 1) + soilAt(helper, 2) == before, "置いた土が場に無い: 床 " + soilAt(helper, 1) + " + 上 " + soilAt(helper, 2));
        });
    }

    /**
     * (b) 目を合わせると: 見ているかの判定(見ている・そっぽを向いている・カボチャをかぶっている)と、
     * 目が合ったら gaze → 溜め → そのプレイヤーのそばへ渡る、を 1 回だけ。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void endermanGazeTeleportsOnce(GameTestHelper helper) {
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(1.0, 2.0, 1.0));
        // 目を合わせる版の転移だけ残す(渡った後に掴み・叩きつけが続かないように)
        ender.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1TeleportGoal t && t.gaze()));
        ender.setTeleportRangeOverride(3.0D, 3.5D);
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        Vec3 at = helper.absoluteVec(new Vec3(5.5, 2.0, 5.5));
        player.setPos(at);
        // エンダーマンの目の高さを見る向き
        Vec3 to = new Vec3(ender.getX() - at.x, ender.getEyeY() - player.getEyeY(), ender.getZ() - at.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
        helper.assertTrue(ender.lookedAtBy(player), "見ているのに目が合わない");
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        helper.assertTrue(!ender.lookedAtBy(player), "カボチャをかぶっているのに目が合った");
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.setYRot(yaw + 90.0F);
        player.setYHeadRot(yaw + 90.0F);
        helper.assertTrue(!ender.lookedAtBy(player), "そっぽを向いているのに目が合った");

        helper.assertTrue(ender.gazeFrom(player), "目を合わせられない");
        boolean[] gaze = {false}, windupAfterGaze = {false}, again = {false};
        helper.onEachTick(() -> {
            if (ender.action() == G1Move.GAZE.id()) gaze[0] = true;
            if (gaze[0] && ender.action() == G1Move.TELEPORT_WINDUP.id()) windupAfterGaze[0] = true;
            if (ender.teleports() >= 1 && !again[0]) {
                again[0] = true;
                helper.assertTrue(!ender.gazeFrom(player), "同じ出会いで 2 回目の目合わせが効いた");
            }
        });
        helper.runAfterDelay(160, () -> {
            helper.assertTrue(gaze[0], "首を向ける段(gaze)が無かった");
            helper.assertTrue(windupAfterGaze[0], "gaze の後に溜めが無かった");
            helper.assertTrue(ender.teleports() == 1, "目が合って渡った回数が 1 でない: " + ender.teleports());
            helper.assertTrue(ender.position().distanceTo(at) <= 4.2D, "見たプレイヤーのそばへ着いていない: " + ender.position().distanceTo(at));
            helper.succeed();
        });
    }

    /** (c-1) 根で掴んで連れて行く: 近くの相手を掴み、抱えた後に一緒に渡る。ダメージは無い。両端で噴く胞子は浴びる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void endermanGrabsAndCarriesTargetAlong(GameTestHelper helper) {
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(3.5, 2.0, 2.5));
        ender.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1GrabGoal));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 4.8));
        keepTarget(helper, ender, cow);
        ender.forceSkillsReady();
        Vec3 enderStart = ender.position(), cowStart = cow.position();
        boolean[] windup = {false}, hold = {false};
        double[] dose = {0.0D};
        helper.onEachTick(() -> {
            dose[0] = Math.max(dose[0], SporeExposureTracker.dose(cow));
            if (ender.action() == G1Move.GRAB_WINDUP.id()) windup[0] = true;
            if (ender.action() == G1Move.GRAB_HOLD.id()) hold[0] = true;
            if (ender.grabsCarried() >= 1) ender.goalSelector.removeAllGoals(g -> true); // 1 回見れば足りる
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(windup[0] && hold[0], "掴む前触れ・抱える段が無かった");
            helper.assertTrue(ender.grabsCarried() == 1, "まだ連れて行っていない");
            helper.assertTrue(ender.position().distanceTo(enderStart) >= 1.8D, "エンダーマンが渡っていない");
            helper.assertTrue(cow.position().distanceTo(cowStart) >= 1.0D, "相手が一緒に移っていない: " + cow.position().distanceTo(cowStart));
            helper.assertTrue(cow.distanceTo(ender) <= 2.6D, "相手がエンダーマンのそばにいない: " + cow.distanceTo(ender));
            helper.assertTrue(cow.getHealth() >= 200.0F, "掴みにダメージがある: " + cow.getHealth());
            helper.assertTrue(dose[0] >= 49.0D, "転移の胞子を浴びていない: " + dose[0]);
        });
    }

    /** (c-1) 抱えている相手に 6 ダメージ殴られると放す(一緒に渡らない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void endermanGrabReleasesWhenStruck(GameTestHelper helper) {
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(3.5, 2.0, 2.5));
        ender.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1GrabGoal));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 4.8));
        keepTarget(helper, ender, cow);
        ender.forceSkillsReady();
        boolean[] struck = {false};
        helper.onEachTick(() -> {
            if (!struck[0] && ender.action() == G1Move.GRAB_HOLD.id()) {
                struck[0] = true;
                ender.hurt(helper.getLevel().damageSources().mobAttack(cow), 6.0F);
            }
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(struck[0], "抱える段にならなかった");
            helper.assertTrue(ender.grabsCarried() == 0, "殴られたのに連れて行った");
            helper.assertTrue(ender.action() == 0, "放した後に行動の段が空きへ戻らない: " + ender.action());
            helper.succeed();
        });
    }

    /**
     * (c-2) 根が地面から突き上がる: 汚染した地面の上の相手だけに当たる(6 ダメージ + 暴露量 +300 + 上へ)。汚染していない地面の上の相手には当たらない。
     * 使った後、根の技((c-1) 掴み)は 4 秒使えない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void endermanSlamHitsOnlyOnCorruptedGround(GameTestHelper helper) {
        corruptFloor(helper, 0, 3); // 西半分が汚染した地面
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(3.5, 2.0, 3.5));
        ender.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSlamGoal));
        Cow onCorrupted = spawnCow(helper, new Vec3(1.5, 2.0, 3.5));
        Cow onClean = spawnCow(helper, new Vec3(5.5, 2.0, 3.5));
        keepTarget(helper, ender, onCorrupted);
        ender.forceSkillsReady();
        boolean[] windup = {false}, lifted = {false}, lockedAfter = {false};
        double[] dose = {0.0D};
        double floorY = onCorrupted.getY();
        helper.onEachTick(() -> {
            dose[0] = Math.max(dose[0], SporeExposureTracker.dose(onCorrupted));
            if (ender.action() == G1Move.SLAM_WINDUP.id()) windup[0] = true;
            if (onCorrupted.getY() - floorY > 0.3D) lifted[0] = true;
            if (ender.slams() >= 1 && ender.action() == 0 && !ender.rootSkillReady()) lockedAfter[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(windup[0], "振り上げる前触れ(slam_windup)が無かった");
            helper.assertTrue(ender.slams() >= 1, "まだ叩きつけていない");
            helper.assertTrue(ender.lastSlamSpikes() >= 1, "根が 1 本も出ていない");
            helper.assertTrue(onCorrupted.getHealth() <= 194.5F, "汚染した地面の上の相手に 6 ダメージが入っていない: " + onCorrupted.getHealth());
            helper.assertTrue(com.yayuy.overgrownvegetation.spore.MobInfection.isInfected(onCorrupted), "当たった Mob が感染していない(11-15 から 1 撃で必ず感染)");
            helper.assertTrue(lifted[0], "相手が浮いていない");
            helper.assertTrue(onClean.getHealth() >= 200.0F, "汚染していない地面の上の相手に当たった: " + onClean.getHealth());
            helper.assertTrue(lockedAfter[0], "叩きつけの後、根の技の間(4 秒)が掛かっていない");
        });
    }

    /** (c-2) 汚染していない地面の上の相手には、叩きつけを使わない(当たらない所では使わない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void endermanDoesNotSlamOnCleanGround(GameTestHelper helper) {
        DevelopedParasitized ender = spawnEnderman(helper, new Vec3(3.5, 2.0, 2.5));
        ender.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSlamGoal));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 4.8));
        keepTarget(helper, ender, cow);
        ender.forceSkillsReady();
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(ender.slams() == 0, "汚染していない地面の上の相手へ叩きつけた");
            helper.assertTrue(cow.getHealth() >= 200.0F, "当たった: " + cow.getHealth());
            helper.succeed();
        });
    }
}
