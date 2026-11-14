package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.RootedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Rooted;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * G1 の根付き(ゾンビ = 蔦の柱の型。2026-11-13、司令塔 第 1 段の区切り 2)。期待値は種の表 {@link G1Rooted} の長さと仕様(根付きの共通)から。
 * テスト場は 7 × 7(床は相対 y = 1、物は y = 2。天井のバリアは y = 8 で、高さ 4.4 の柱は収まる)。
 * 根付きは見つける距離が 24 で隣のテスト場の Mob に届くので、標的を選ぶ Goal は外し、標的はテストが決める。根付きは +Z を向いて出る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RootedGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(RootedGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_rooted";
    private static final G1Rooted R = G1Rooted.ZOMBIE;

    private RootedGameTests() {
    }

    private static RootedParasitized spawnRooted(GameTestHelper helper, Vec3 at) {
        return spawnRooted(helper, R, at);
    }

    private static RootedParasitized spawnRooted(GameTestHelper helper, G1Rooted kind, Vec3 at) {
        RootedParasitized m = helper.spawn(ModG1.rooted(kind).get(), at);
        m.targetSelector.removeAllGoals(g -> true);
        m.stopCorruptingForTests();
        m.setRangeLimitForTests(7.2D); // 鐘・暴露の輪・引き込みの巻き添えを、このテスト場の中だけに
        return m;
    }

    /** 動かない的(体の物理は効く)。打たれても死なない。 */
    private static Cow spawnCow(GameTestHelper helper, Vec3 at) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, at);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        cow.setHealth(200.0F);
        return cow;
    }

    private static void keepTarget(GameTestHelper helper, RootedParasitized mob, LivingEntity target) {
        helper.onEachTick(() -> {
            if (mob.isAlive() && mob.getTarget() != target) mob.setTarget(target);
        });
    }

    /** 動かない・向きを変えない: 殴っても押しても、位置と向きはそのまま。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void rootedDoesNotMoveOrTurn(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, new Vec3(3.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.5));
        helper.runAfterDelay(5, () -> {
            Vec3 at = m.position();
            float yaw = m.getYRot();
            m.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
            m.knockback(2.0D, 1.0D, 0.0D);
            m.push(0.5D, 0.0D, 0.5D);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(m.position().distanceTo(at) < 0.01D, "根付きが動いた: " + m.position().distanceTo(at));
                helper.assertTrue(Math.abs(m.getYRot() - yaw) < 0.01F && Math.abs(m.yBodyRot - yaw) < 0.01F, "根付きの向きが変わった");
                m.discard();
                cow.discard();
                helper.succeed();
            });
        });
    }

    /** 薙ぎ払いは全周: 正面の相手を標的に振り出すと、真後ろの相手にも当たる。溜めの間は当たらない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void lashHitsAllAroundAfterWindup(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, new Vec3(3.5, 2.0, 3.5));
        Cow front = spawnCow(helper, new Vec3(3.5, 2.0, 5.4));
        Cow back = spawnCow(helper, new Vec3(3.5, 2.0, 1.6));
        m.setCooldownsForTests(0, 9999, 9999);
        keepTarget(helper, m, front);
        boolean[] sawWindup = {false};
        helper.onEachTick(() -> {
            int a = m.action();
            if (a == RootedParasitized.ACTION_LASH_WINDUP) {
                sawWindup[0] = true;
                helper.assertTrue(front.getHealth() == 200.0F && back.getHealth() == 200.0F, "溜めの間に当たった");
            }
            if (a == RootedParasitized.ACTION_LASH_RECOVER) {
                helper.assertTrue(sawWindup[0], "溜めなしに打った");
                helper.assertTrue(front.getHealth() < 200.0F, "正面の相手に当たっていない");
                helper.assertTrue(back.getHealth() < 200.0F, "真後ろの相手に当たっていない(全周でない)");
                m.discard();
                front.discard();
                back.discard();
                helper.succeed();
            }
        });
    }

    /** 撃つ: 離れた相手へ、溜めの後に、てっぺんの高さから胞子の塊が出る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void shootsSporeLumpFromTheTop(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, new Vec3(1.0, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(6.0, 2.0, 6.0));
        m.setCooldownsForTests(9999, 0, 9999);
        keepTarget(helper, m, cow);
        int[] windupTicks = {0};
        helper.onEachTick(() -> {
            if (m.action() == RootedParasitized.ACTION_SHOOT_WINDUP) windupTicks[0]++;
            List<G1SporeLump> lumps = helper.getLevel().getEntitiesOfClass(G1SporeLump.class, helper.getBounds().inflate(1.0D),
                    l -> l.getOwner() == m);
            if (lumps.isEmpty()) return;
            helper.assertTrue(windupTicks[0] >= R.shootWindup - 1, "溜めが短い: " + windupTicks[0] + " < " + R.shootWindup);
            helper.assertTrue(lumps.get(0).getY() > m.getY() + 3.0D, "塊がてっぺんから出ていない: " + (lumps.get(0).getY() - m.getY()));
            lumps.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 絡め取り: 離れた相手を蔦で絡め、柱の方へ引き寄せる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void grabsAndPullsPreyCloser(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, new Vec3(1.0, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(6.0, 2.0, 3.5));
        m.setCooldownsForTests(9999, 9999, 0);
        keepTarget(helper, m, cow);
        double start = cow.distanceTo(m);
        boolean[] tethered = {false};
        helper.onEachTick(() -> {
            if (m.isTethering()) tethered[0] = true;
            if (tethered[0] && cow.distanceTo(m) < start - 1.5D) {
                m.discard();
                cow.discard();
                helper.succeed();
            }
        });
        helper.runAfterDelay(180, () -> helper.fail("絡めて引き寄せていない: 絡めた " + tethered[0] + "・距離 " + start + " → " + cow.distanceTo(m)));
    }

    /** 生える: 入れ替わった直後は emerge を流し、その間は攻撃しない。終わると待機に戻る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void emergesBeforeAttacking(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, new Vec3(3.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.4));
        m.setCooldownsForTests(0, 0, 0);
        m.startEmerge();
        keepTarget(helper, m, cow);
        helper.runAfterDelay(G1Rooted.EMERGE - 5, () -> {
            helper.assertTrue(m.isEmerging(), "emerge が早く終わった");
            helper.assertTrue(cow.getHealth() == 200.0F, "生えている間に攻撃した");
        });
        helper.runAfterDelay(G1Rooted.EMERGE + 3, () -> {
            helper.assertTrue(!m.isEmerging(), "emerge が終わらない");
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 枯れて崩れる: 倒してもすぐには消えず、death_wither の長さで消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void withersForItsClipLengthThenVanishes(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, new Vec3(3.5, 2.0, 3.5));
        helper.runAfterDelay(3, m::kill);
        helper.runAfterDelay(3 + G1Rooted.WITHER - 6, () -> helper.assertTrue(!m.isRemoved(), "枯れ終わる前に消えた"));
        helper.runAfterDelay(3 + G1Rooted.WITHER + 4, () -> {
            helper.assertTrue(m.isRemoved(), "枯れ終わっても消えない");
            helper.succeed();
        });
    }

    /** 司令塔の道: 根付きに決まった段階 0 のゾンビは、着くと根を下ろし、根付きのゾンビに入れ替わって生え始める。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void stageZeroZombieBecomesRootedZombie(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
        zombie.goalSelector.removeAllGoals(g -> true);
        zombie.targetSelector.removeAllGoals(g -> true);
        G1Fate fate = zombie.fate();
        helper.assertTrue(fate.rootedForm() != null, "ゾンビに根付きの姿が登録されていない");
        // 森が見当たらない = もう十分に離れている → 最初の見直し(20 tick)で根を下ろす
        fate.setForestForTests(new com.yayuy.overgrownvegetation.director.ForestEdge() {
            @Override
            public double distance(Vec3 pos) {
                return Double.POSITIVE_INFINITY;
            }

            @Override
            public Vec3 away(Vec3 pos) {
                return null;
            }
        });
        fate.beginRooted();
        int omen = DesignData.director().rootOmenTicks;
        helper.runAfterDelay(20 + omen + 6, () -> {
            helper.assertTrue(zombie.isRemoved(), "根を下ろしても段階 0 が残っている");
            List<RootedParasitized> found = helper.getLevel().getEntitiesOfClass(RootedParasitized.class, helper.getBounds());
            helper.assertTrue(found.size() == 1, "根付きのゾンビが 1 体でない: " + found.size());
            RootedParasitized rooted = found.get(0);
            helper.assertTrue(rooted.rooted() == G1Rooted.ZOMBIE && rooted.isEmerging(), "生え始めていない");
            rooted.discard();
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 蔦の柱の残り 4 種の能力(区切り 3)
    /** チビゾンビ: 叩かれると、袋から胞子の煙幕が出る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void babyZombiePuffsSmokeWhenHit(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.BABY_ZOMBIE, new Vec3(3.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(5.5, 2.0, 5.5));
        m.setCooldownsForTests(9999, 9999, 0);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class,
                    helper.getBounds()).isEmpty(), "叩かれる前から煙が出ている");
            m.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
        });
        helper.onEachTick(() -> {
            List<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud> clouds = helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class, helper.getBounds());
            if (clouds.isEmpty()) return;
            clouds.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 村人: 動物がそばにいると鐘を鳴らし、引き寄せを始める。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void villagerRingsBellForNearbyAnimals(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.VILLAGER, new Vec3(1.0, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(6.0, 2.0, 6.0));
        m.setCooldownsForTests(9999, 9999, 0);
        helper.onEachTick(() -> {
            if (!m.isBellPulling()) return;
            helper.assertTrue(m.action() == RootedParasitized.ACTION_SKILL_ONE, "鐘のアニメを流さずに引き寄せている");
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** エンダーマン: 離れた見えている相手を、転移で薙ぎ払いの間合いの中へ引き込む。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void endermanPullsPreyToItsFeet(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.ENDERMAN, new Vec3(1.0, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(6.0, 2.0, 6.0));
        m.setCooldownsForTests(9999, 9999, 0);
        keepTarget(helper, m, cow);
        double start = cow.distanceTo(m);
        helper.onEachTick(() -> {
            double d = cow.distanceTo(m);
            if (d > start - 2.0D) return;
            helper.assertTrue(d < G1Rooted.ENDERMAN.lashRange + 0.6D, "引き込んだ先が薙ぎ払いの間合いの外: " + d);
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** スケルトン: 離れた相手へ、柱の上の弓の高さから弾を射る。弓の向き(同期する角度)は標的の方を向く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void skeletonSnipesFromTheBow(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.SKELETON, new Vec3(1.0, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(6.0, 2.0, 6.0));
        m.setCooldownsForTests(9999, 0, 9999);
        keepTarget(helper, m, cow);
        helper.onEachTick(() -> {
            List<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt> bolts = helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt.class, helper.getBounds().inflate(1.0D), b -> b.getOwner() == m);
            if (bolts.isEmpty()) return;
            helper.assertTrue(bolts.get(0).getY() > m.getY() + 3.5D, "弾が弓の高さから出ていない: " + (bolts.get(0).getY() - m.getY()));
            // 根付きは +Z を向く。牛は (+X, +Z) の斜め = 正面から左へ 45°(左 = +X)
            float aim = net.minecraft.util.Mth.wrapDegrees(m.aimYaw());
            helper.assertTrue(Math.abs(aim - 45.0F) < 8.0F, "弓が標的の方を向いていない: " + aim);
            bolts.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 村人: 攻撃されると、足元の守り手のもとが膨らみ、根の守り手が 1 体立ち上がって殴ってきた相手へ向かう。同時に 1 体だけ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void villagerGrowsOneRootGuardianWhenAttacked(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.VILLAGER, new Vec3(3.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(1.2, 2.0, 5.8));
        m.setCooldownsForTests(9999, 9999, 9999);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(m.guardian() == null && m.seedState() == RootedParasitized.SEED_NONE, "攻撃される前から守り手がいる");
            m.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
            helper.assertTrue(m.seedState() == RootedParasitized.SEED_SHELL, "攻撃されても守り手のもとが膨らまない");
        });
        helper.runAfterDelay(5 + 15, () -> helper.assertTrue(m.guardian() == null, "守り手のもとが膨らみきる前に守り手が出た"));
        helper.runAfterDelay(5 + 26, () -> {
            com.yayuy.overgrownvegetation.entity.RootGuardian g = m.guardian();
            helper.assertTrue(g != null, "守り手が立ち上がらない");
            helper.assertTrue(g.isEmerging(), "守り手が立ち上がりの動きをしていない");
            m.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
        });
        helper.runAfterDelay(5 + 26 + 45, () -> {
            List<com.yayuy.overgrownvegetation.entity.RootGuardian> all = helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.RootGuardian.class, helper.getBounds());
            helper.assertTrue(all.size() == 1, "守り手が 1 体でない: " + all.size());
            helper.assertTrue(!all.get(0).isEmerging() && all.get(0).getTarget() == cow, "守り手が殴ってきた相手を狙っていない");
            all.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 根の守り手: 立ち上がった後、標的へ歩いて殴る(前触れの後に当たる)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void rootGuardianWalksUpAndPunches(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.RootGuardian g = helper.spawn(ModG1.ROOT_GUARDIAN.get(), new Vec3(1.5, 2.0, 3.5));
        g.targetSelector.removeAllGoals(x -> true);
        Cow cow = spawnCow(helper, new Vec3(5.5, 2.0, 3.5));
        boolean[] sawWindup = {false};
        helper.onEachTick(() -> {
            if (g.isAlive() && g.getTarget() != cow) g.setTarget(cow);
            if (g.action() == com.yayuy.overgrownvegetation.entity.RootGuardian.ACTION_WINDUP) {
                sawWindup[0] = true;
                helper.assertTrue(cow.getHealth() == 200.0F, "前触れの間に当たった");
            }
            if (cow.getHealth() < 200.0F) {
                helper.assertTrue(sawWindup[0], "前触れなしに殴った");
                g.discard();
                cow.discard();
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 背から咲く型(区切り 4)
    /** 背から咲く(牛): 獲物が近づくと房を膨らませ、溜めの後に胞子の雲を出す。薙ぎ払い・塊は無い。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void bloomPuffsSporeCloudAtNearbyPrey(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.COW, new Vec3(3.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.8));
        m.setCooldownsForTests(0, 0, 9999);
        keepTarget(helper, m, cow);
        int[] windup = {0};
        helper.onEachTick(() -> {
            if (m.action() == RootedParasitized.ACTION_SHOOT_WINDUP) windup[0]++;
            List<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud> clouds = helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class, helper.getBounds());
            if (clouds.isEmpty()) return;
            helper.assertTrue(windup[0] >= G1Rooted.COW.shootWindup - 1, "溜めが短い: " + windup[0]);
            helper.assertTrue(cow.getHealth() == 200.0F, "背から咲く型が獲物を打った(薙ぎ払いは無いはず)");
            clouds.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 牛: 周りに乳だまりを広げる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void cowSpreadsMilkPuddles(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.COW, new Vec3(3.5, 2.0, 3.5));
        m.setCooldownsForTests(9999, 9999, 0);
        helper.onEachTick(() -> {
            List<com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle> puddles = helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle.class, helper.getBounds().inflate(6.0D),
                    p -> p.distanceTo(m) < 7.0D);
            if (puddles.isEmpty()) return;
            puddles.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            helper.succeed();
        });
    }

    /** 鶏: 根元のまわりに汚染された卵を産み落とす。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void chickenLaysCorruptedEggNearby(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.CHICKEN, new Vec3(3.5, 2.0, 3.5));
        m.setCooldownsForTests(9999, 9999, 0);
        net.minecraft.core.BlockPos center = m.blockPosition();
        helper.onEachTick(() -> {
            int eggs = 0;
            for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(center.offset(-3, -1, -3), center.offset(3, 1, 3))) {
                if (helper.getLevel().getBlockState(p).getBlock() instanceof com.yayuy.overgrownvegetation.block.CorruptedEggBlock) {
                    eggs++;
                    helper.getLevel().removeBlock(p, false);
                }
            }
            if (eggs == 0) return;
            m.discard();
            helper.succeed();
        });
    }

    /** 豚: 近づいた相手を根の顎で縛ってその場に留め、上限の長さで放す。放した後しばらくは同じ相手を巻かない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 500)
    public static void pigBindsNearbyPreyThenLetsGo(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.PIG, new Vec3(1.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(4.0, 2.0, 3.5));
        m.setCooldownsForTests(9999, 9999, 0);
        keepTarget(helper, m, cow);
        Vec3[] boundAt = {null};
        int[] releasedAt = {-1};
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (m.isBinding()) {
                if (boundAt[0] == null) boundAt[0] = cow.position();
                cow.push(0.3D, 0.0D, 0.0D); // 逃げようとしても動けない
                helper.assertTrue(cow.position().distanceTo(boundAt[0]) < 0.4D, "縛られているのに動いた: " + cow.position().distanceTo(boundAt[0]));
                return;
            }
            if (boundAt[0] == null) return;
            if (releasedAt[0] < 0) releasedAt[0] = tick[0];
            // 放してから 40 tick は、また縛られていないこと(抜けた後は同じ相手を巻かない)
            if (tick[0] - releasedAt[0] >= 40) {
                m.discard();
                cow.discard();
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 地面の捕食植物(区切り 5)
    /** クモ: 葉の輪に入った獲物を、葉が閉じる動きの後に捕まえて留め、数周締めてから放す。締めている間は少し傷つける。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void spiderTrapsPreyOnItsLeafRing(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.SPIDER, new Vec3(3.5, 2.0, 3.5));
        m.setCooldownsForTests(0, 9999, 9999);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.3));
        boolean[] trapped = {false};
        Vec3[] heldAt = {null};
        helper.onEachTick(() -> {
            if (m.isTrapping()) {
                if (!trapped[0]) {
                    trapped[0] = true;
                    heldAt[0] = cow.position();
                    helper.assertTrue(cow.distanceTo(m) < G1Rooted.SPIDER.lashRange, "捕まえた獲物が輪の外にいる");
                }
                cow.push(0.3D, 0.0D, 0.0D);
                helper.assertTrue(cow.position().distanceTo(heldAt[0]) < 0.4D, "捕まっているのに動いた");
                return;
            }
            if (!trapped[0]) return;
            helper.assertTrue(cow.getHealth() < 200.0F, "締めている間に傷ついていない");
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** クモ: 周りに根糸の網を張り、張るたびに半径が広がる。倒すと網は消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void spiderSpinsWebsThatWitherWhenItDies(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.SPIDER, new Vec3(3.5, 2.0, 3.5));
        m.setCooldownsForTests(9999, 9999, 0);
        net.minecraft.core.BlockPos c = m.blockPosition();
        int startRadius = m.webRadius();
        boolean[] killed = {false};
        helper.onEachTick(() -> {
            int webs = 0;
            for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(c.offset(-3, -1, -3), c.offset(3, 1, 3))) {
                if (helper.getLevel().getBlockState(p).getBlock() instanceof com.yayuy.overgrownvegetation.block.RootsilkWebBlock) webs++;
            }
            if (!killed[0]) {
                if (webs == 0) return;
                helper.assertTrue(m.webRadius() > startRadius, "網を張っても半径が広がらない");
                killed[0] = true;
                m.kill();
                return;
            }
            if (webs == 0) helper.succeed();
        });
    }

    /** クリーパー: 捕まえた獲物を 1 周締めてから、蕾の中で莢を爆ぜさせる(ダメージ)。爆ぜたら放す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void creeperBurstsPodsOnTrappedPrey(GameTestHelper helper) {
        RootedParasitized m = spawnRooted(helper, G1Rooted.CREEPER, new Vec3(3.5, 2.0, 3.5));
        m.setCooldownsForTests(0, 9999, 9999);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.2));
        boolean[] trapped = {false};
        float[] beforeBurst = {200.0F};
        helper.onEachTick(() -> {
            if (m.isTrapping()) {
                trapped[0] = true;
                if (m.action() == RootedParasitized.ACTION_TRAP_HOLD || m.action() == RootedParasitized.ACTION_SKILL_WINDUP) {
                    beforeBurst[0] = cow.getHealth();
                }
                return;
            }
            if (!trapped[0]) return;
            helper.assertTrue(cow.getHealth() <= beforeBurst[0] - 3.0F, "爆ぜのダメージが入っていない: " + beforeBurst[0] + " → " + cow.getHealth());
            m.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 着いた所に根付きの姿が入らない(低い天井)とき、諦めずに、根を下ろすのをやめてまた歩く(本物の地形の木の下で止まっていた)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void noRoomForPillarWalksOnInsteadOfGivingUp(GameTestHelper helper) {
        // 段階 0 のゾンビ(高さ 1.95)は入るが、根付きのゾンビ(高さ 4.4)は入らない天井
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) helper.setBlock(new net.minecraft.core.BlockPos(x, 5, z), net.minecraft.world.level.block.Blocks.STONE);
        }
        AbstractParasitizedMonster zombie = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
        zombie.goalSelector.removeAllGoals(g -> true);
        zombie.targetSelector.removeAllGoals(g -> true);
        G1Fate fate = zombie.fate();
        fate.setForestForTests(new com.yayuy.overgrownvegetation.director.ForestEdge() {
            @Override
            public double distance(Vec3 pos) {
                return Double.POSITIVE_INFINITY;
            }

            @Override
            public Vec3 away(Vec3 pos) {
                return null;
            }
        });
        fate.beginRooted();
        int omen = DesignData.director().rootOmenTicks;
        boolean[] sawOmen = {false};
        helper.onEachTick(() -> {
            if (zombie.omen() == G1Fate.OMEN_ROOT) sawOmen[0] = true;
        });
        helper.runAfterDelay(20 + omen + 10, () -> {
            helper.assertTrue(sawOmen[0], "根を下ろそうとしていない");
            helper.assertTrue(zombie.isAlive(), "入らないのに入れ替わった");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(RootedParasitized.class, helper.getBounds()).isEmpty(), "入らない所に根付きが出た");
            helper.assertTrue(fate.route() == com.yayuy.overgrownvegetation.director.ForestDirector.Route.ROOTED && fate.walkingAway(),
                    "入らなかっただけで根付きを諦めた: " + fate.route() + " / 前兆 " + zombie.omen());
            zombie.discard();
            helper.succeed();
        });
    }
}
