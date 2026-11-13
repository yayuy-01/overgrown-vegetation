package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G1 の 1 段目の (a) 種ごとの 1 撃の違い(2026-11-13 ユーザー「入れよう」): 間合い・踏み込み・押し返し。
 * 期待値は各種の仕様書の (a) 欄から(種の表 {@link G1Developed} の値を読み返さない)。
 * テスト場は 7 × 7(床は相対 y = 1、物は y = 2)。1 段目は +Z を向いて出るので、的は +Z の側に置く。
 * 的の牛は幅 0.9(半幅 0.45)。「すきま」は 1 段目の体の中心から的の体の手前の端までの長さ。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1StrikeAGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1StrikeAGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_strike_a";
    /** バニラの素の間合い(体の箱から水平に広げる長さ = √2.04 − 0.6)。 */
    private static final double VANILLA_REACH = Math.sqrt(2.04F) - 0.6F;
    private static final double COW_HALF = 0.45D;
    /** 当たりの素の押し返し 0.4 が、重ねた押し返しで半分になった残り(バニラの {@code knockback} の式)。 */
    private static final double BASE_AFTER = 0.2D;

    private G1StrikeAGameTests() {
    }

    /** 1 撃だけを残した 1 段目(標的はテストが決める・種の技とうろつきは外す)。 */
    private static DevelopedParasitized spawnStriker(GameTestHelper helper, G1Developed d, Vec3 at) {
        DevelopedParasitized m = (DevelopedParasitized) helper.spawn(ModG1.developed(d).get(), at);
        m.targetSelector.removeAllGoals(g -> true);
        m.goalSelector.removeAllGoals(g -> !(g instanceof G1StrikeGoal));
        return m;
    }

    /** 動かない的(体の物理は効く = 押されれば動く)。打たれても死なない。 */
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

    private static double horizontal(Vec3 v) {
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    // ------------------------------------------------------------------ 間合い
    /** 間合いの箱が、種ごとに仕様の長さだけ広がっている(鶏・クリーパーは体の中心から 2.5 ブロック)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void reachPerSpeciesMatchesSpec(GameTestHelper helper) {
        for (G1Developed d : G1Developed.values()) {
            DevelopedParasitized m = (DevelopedParasitized) helper.spawn(ModG1.developed(d).get(), new Vec3(3.5, 2.0, 3.5));
            AABB body = m.getBoundingBox();
            AABB box = m.getAttackBoundingBoxForG1();
            double extra = box.maxX - body.maxX - VANILLA_REACH;
            double fromCenter = box.maxX - m.getX();
            m.discard();
            switch (d) {
                case ZOMBIE, SKELETON, SPIDER -> helper.assertTrue(Math.abs(extra - 0.5D) < 0.01D, d + " の間合い +0.5 が違う: " + extra);
                case ENDERMAN -> helper.assertTrue(Math.abs(extra - 1.0D) < 0.01D, d + " の間合い +1.0 が違う: " + extra);
                case CHICKEN, CREEPER -> helper.assertTrue(Math.abs(fromCenter - 2.5D) < 0.02D, d + " の間合い 2.5 が違う: " + fromCenter);
                default -> helper.assertTrue(Math.abs(extra) < 0.01D, d + " の間合いが素から変わっている: " + extra);
            }
            helper.assertTrue(Math.abs(box.maxZ - body.maxZ - (box.maxX - body.maxX)) < 1.0E-6D, d + " の間合いが前後と左右で違う");
            helper.assertTrue(box.minY == body.minY && box.maxY == body.maxY, d + " の間合いが上下に広がっている");
        }
        helper.succeed();
    }

    /** 鶏の伸びる首: すきま 2.3 ブロックの相手を、近づかずにその場からつつく(素の間合いは 1.08)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void chickenPecksFromLongReachWithoutApproaching(GameTestHelper helper) {
        DevelopedParasitized chicken = spawnStriker(helper, G1Developed.CHICKEN, new Vec3(3.5, 2.0, 1.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.5 + 2.3 + COW_HALF));
        keepTarget(helper, chicken, cow);
        Vec3 start = chicken.position();
        helper.succeedWhen(() -> {
            helper.assertTrue(chicken.strikeHits() >= 1, "つついていない");
            helper.assertTrue(cow.getHealth() <= 196.5F, "攻撃 4 が入っていない: " + cow.getHealth());
            helper.assertTrue(horizontal(chicken.position().subtract(start)) < 0.3D,
                    "近づいてからつついた: " + horizontal(chicken.position().subtract(start)));
        });
    }

    /** 鶏は、届く所では次の 1 撃までの間も寄らずに待つ(押されない相手を 2 回つつく間、その場から動かない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void chickenHoldsDistanceBetweenPecks(GameTestHelper helper) {
        DevelopedParasitized chicken = spawnStriker(helper, G1Developed.CHICKEN, new Vec3(3.5, 2.0, 1.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.5 + 2.3 + COW_HALF));
        cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D); // 押されて間合いの外へ出ないように
        keepTarget(helper, chicken, cow);
        Vec3 start = chicken.position();
        double[] farthest = {0.0D};
        helper.onEachTick(() -> farthest[0] = Math.max(farthest[0], horizontal(chicken.position().subtract(start))));
        helper.succeedWhen(() -> {
            helper.assertTrue(chicken.strikeHits() >= 2, "まだ 2 回つついていない: " + chicken.strikeHits());
            helper.assertTrue(farthest[0] < 0.3D, "次の 1 撃までの間に寄った: " + farthest[0]);
        });
    }

    /** ゾンビ(間合い +0.5)は今までどおり、次の 1 撃までの間も相手へ寄る(待つのは牛・羊・鶏だけ)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void zombieKeepsClosingInBetweenStrikes(GameTestHelper helper) {
        DevelopedParasitized zombie = spawnStriker(helper, G1Developed.ZOMBIE, new Vec3(3.5, 2.0, 1.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.5 + 1.5 + COW_HALF)); // すきま 1.5(間合い 1.63 の中)
        cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        keepTarget(helper, zombie, cow);
        Vec3 start = zombie.position();
        helper.succeedWhen(() -> {
            helper.assertTrue(zombie.strikeHits() >= 2, "まだ 2 回当てていない: " + zombie.strikeHits());
            helper.assertTrue(horizontal(zombie.position().subtract(start)) > 0.3D,
                    "間を待つ間に寄っていない: " + horizontal(zombie.position().subtract(start)));
        });
    }

    /** 鶏の間合いの外(すきま 2.8)には振り出さない(歩けなくして確かめる)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void chickenDoesNotPeckBeyondReach(GameTestHelper helper) {
        DevelopedParasitized chicken = spawnStriker(helper, G1Developed.CHICKEN, new Vec3(3.5, 2.0, 1.5));
        chicken.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.5 + 2.8 + COW_HALF));
        keepTarget(helper, chicken, cow);
        boolean[] windup = {false};
        helper.onEachTick(() -> {
            if (chicken.action() != AbstractParasitizedMonster.ACTION_NONE) windup[0] = true;
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(!windup[0], "間合いの外なのに振り出した");
            helper.assertTrue(chicken.strikeHits() == 0, "間合いの外なのに当たった");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 踏み込み
    /**
     * 踏み込みの土台: すきま {@code gap} の相手へ、歩かずにその場から振り出し、実行の段で前へ出て当てる。
     * 踏み込んだ長さは「すきま − 素の間合い」〜 +1 刻み(届いた所で止まる)、押し返しは向いた方へ 0.2 + 押し返し × 0.5。
     */
    private static void lungeHits(GameTestHelper helper, G1Developed d, double halfWidth, double gap, double lunge, double knock) {
        DevelopedParasitized mob = spawnStriker(helper, d, new Vec3(3.5, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.0 + gap + COW_HALF));
        keepTarget(helper, mob, cow);
        Vec3 start = mob.position();
        double[] movedBeforeStrike = {0.0D};
        helper.onEachTick(() -> {
            if (mob.action() == AbstractParasitizedMonster.ACTION_WINDUP) {
                movedBeforeStrike[0] = Math.max(movedBeforeStrike[0], horizontal(mob.position().subtract(start)));
            }
        });
        double need = gap - (halfWidth + VANILLA_REACH);
        helper.succeedWhen(() -> {
            helper.assertTrue(mob.strikeHits() >= 1, "当たっていない(踏み込み " + mob.lungeMoved() + ")");
            helper.assertTrue(movedBeforeStrike[0] < 0.2D, "歩いて寄ってから振り出した: " + movedBeforeStrike[0]);
            helper.assertTrue(mob.lungeMoved() >= need - 0.01D && mob.lungeMoved() <= Math.min(lunge, need + lunge / 12.0D) + 0.01D,
                    "踏み込みの長さが違う: " + mob.lungeMoved() + "(要る長さ " + need + "・上限 " + lunge + ")");
            helper.assertTrue(cow.getHealth() <= 195.5F, "攻撃 5 が入っていない: " + cow.getHealth());
            Vec3 push = mob.lastStrikePush();
            helper.assertTrue(push != null, "押し返しの記録が無い");
            double expect = BASE_AFTER + knock * 0.5D;
            helper.assertTrue(Math.abs(push.z - expect) < 0.05D && Math.abs(push.x) < 0.08D,
                    "押し返しが違う: " + push + "(前へ " + expect + " のはず)");
        });
    }

    /** 牛の突き上げ: 踏み込み 2 ブロック・押し返し 0.9。すきま 3.0(素の間合い 1.33 の外)から振り出して当てる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void cowLungesTwoBlocksAndKnocksBack(GameTestHelper helper) {
        lungeHits(helper, G1Developed.COW, 0.5D, 3.0D, 2.0D, 0.9D);
    }

    /** 羊の踏み込みの体当たり: 踏み込み 3 ブロック・押し返し 0.8。すきま 4.0 から振り出して当てる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void sheepLungesThreeBlocksAndKnocksBack(GameTestHelper helper) {
        lungeHits(helper, G1Developed.SHEEP, 0.45D, 4.0D, 3.0D, 0.8D);
    }

    /** 踏み込みはまっすぐで曲がらない: 向きが止まった後(前触れ 10 tick 目)に横へ出た相手には当たらず、2 ブロック先まで出る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void cowLungeGoesStraightAndMissesSidestep(GameTestHelper helper) {
        DevelopedParasitized mob = spawnStriker(helper, G1Developed.COW, new Vec3(3.5, 2.0, 1.0));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.0 + 3.0 + COW_HALF));
        keepTarget(helper, mob, cow);
        Vec3 start = mob.position();
        int[] windupTicks = {0};
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (mob.action() == AbstractParasitizedMonster.ACTION_WINDUP) {
                windupTicks[0]++;
                if (windupTicks[0] == 10) {
                    cow.teleportTo(cow.getX() + 2.6D, cow.getY(), cow.getZ()); // 横へ出る
                }
            }
            if (windupTicks[0] >= 10 && mob.action() == AbstractParasitizedMonster.ACTION_RECOVER) {
                done[0] = true;
                mob.goalSelector.removeAllGoals(g -> true); // 次の 1 撃を振らせない
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(done[0], "1 撃がまだ終わっていない");
            helper.assertTrue(mob.strikeHits() == 0, "横へ出た相手に当たった");
            helper.assertTrue(cow.getHealth() >= 200.0F, "横へ出た相手が傷ついた: " + cow.getHealth());
            helper.assertTrue(Math.abs(mob.lungeMoved() - 2.0D) < 0.05D, "踏み込みが 2 ブロックでない: " + mob.lungeMoved());
            Vec3 moved = mob.position().subtract(start);
            helper.assertTrue(Math.abs(moved.x) < 0.1D && moved.z > 1.9D, "まっすぐ前へ出ていない: " + moved);
        });
    }

    /** 道がふさがっていれば遠くからは振り出さない(高さ 1 の壁の向こうの相手。歩けなくして確かめる)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void cowDoesNotLungeThroughWall(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 2, 3), Blocks.STONE.defaultBlockState());
        }
        DevelopedParasitized mob = spawnStriker(helper, G1Developed.COW, new Vec3(3.5, 2.0, 1.5));
        mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 1.5 + 3.0 + COW_HALF));
        keepTarget(helper, mob, cow);
        boolean[] acted = {false};
        helper.onEachTick(() -> {
            if (mob.action() != AbstractParasitizedMonster.ACTION_NONE) acted[0] = true;
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(mob.getSensing().hasLineOfSight(cow), "壁で相手が見えていない(テストの置き方が悪い)");
            helper.assertTrue(!acted[0], "壁の向こうへ振り出した");
            helper.assertTrue(mob.strikeHits() == 0, "壁の向こうに当たった");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 押し返し
    /**
     * 押し返しの土台: 隣の相手に 1 撃を当て、その直後の相手の速さを、1 段目の向きの前・右の成分で見る。
     *
     * @param forward 前(向いた方)へ押す速さの期待値
     * @param right   体の右へ押す速さの期待値
     */
    private static void pushIs(GameTestHelper helper, G1Developed d, double halfWidth, double forward, double right) {
        DevelopedParasitized mob = spawnStriker(helper, d, new Vec3(3.5, 2.0, 2.0));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 2.0 + halfWidth + 0.4D + COW_HALF));
        keepTarget(helper, mob, cow);
        helper.succeedWhen(() -> {
            helper.assertTrue(mob.strikeHits() >= 1, "当たっていない");
            Vec3 push = mob.lastStrikePush();
            helper.assertTrue(push != null, "押し返しの記録が無い");
            float rad = mob.getYRot() * Mth.DEG_TO_RAD;
            double f = push.x * -Mth.sin(rad) + push.z * Mth.cos(rad);
            double r = push.x * -Mth.cos(rad) + push.z * -Mth.sin(rad);
            helper.assertTrue(Math.abs(f - forward) < 0.06D && Math.abs(r - right) < 0.06D,
                    d + " の押し返しが違う: 前 " + f + "・右 " + r + "(前 " + forward + "・右 " + right + " のはず)");
        });
    }

    /** ゾンビの根腕の振り下ろし: 押し返し 0.5(前へ 0.2 + 0.25)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void zombieStrikeKnocksBack(GameTestHelper helper) {
        pushIs(helper, G1Developed.ZOMBIE, 0.3D, BASE_AFTER + 0.25D, 0.0D);
    }

    /** 村人の根の瘤の叩き伏せ: 押し返し 0.8(前へ 0.2 + 0.4)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void villagerStrikeKnocksBackHard(GameTestHelper helper) {
        pushIs(helper, G1Developed.VILLAGER, 0.4D, BASE_AFTER + 0.4D, 0.0D);
    }

    /** スケルトンの根の弓の薙ぎ: 押し返し 0.6(前へ 0.2 + 0.3)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void skeletonStrikeKnocksBack(GameTestHelper helper) {
        pushIs(helper, G1Developed.SKELETON, 0.3D, BASE_AFTER + 0.3D, 0.0D);
    }

    /** エンダーマンの根の腕の横薙ぎ: 横への押し返し 0.6(前へは素の残り 0.2、体の右へ 0.3)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void endermanStrikeKnocksSideways(GameTestHelper helper) {
        pushIs(helper, G1Developed.ENDERMAN, 0.3D, BASE_AFTER, 0.3D);
    }

    /** クモの抱え込み噛み: 引き寄せ 0.3(素の押し返しを打ち消して、手前へ 0.15)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void spiderStrikePullsIn(GameTestHelper helper) {
        pushIs(helper, G1Developed.SPIDER, 0.7D, -0.15D, 0.0D);
    }

    /** 押し返しを持たない種(鶏)は、当たりの素の押し返し 0.4 だけ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void chickenStrikeHasOnlyBaseKnockback(GameTestHelper helper) {
        pushIs(helper, G1Developed.CHICKEN, 0.25D, 0.4D, 0.0D);
    }
}
