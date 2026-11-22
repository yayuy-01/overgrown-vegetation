package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * G1 の 1 段目の種の技(2026-11-12、能力の第 1 回 = ゾンビ・チビゾンビ・村人・豚)。技ごとに 1 本以上。見た目(アニメ・粒・霞み)はクライアントで確かめる。
 * テスト場は 7 × 7(床は相対 y = 1、物は y = 2 に置く)。同じバッチは隣り合って並列に走るので、標的を選ぶ Goal を外したり、標的を毎 tick 決め直したりして、
 * 隣のテストの Mob を狙わないようにする(記憶 gametest-arena-layout)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1SkillGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1SkillGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_skills";

    private G1SkillGameTests() {
    }

    private static DevelopedParasitized spawnDev(GameTestHelper helper, G1Developed d, Vec3 at) {
        DevelopedParasitized m = (DevelopedParasitized) helper.spawn(ModG1.developed(d).get(), at);
        m.targetSelector.removeAllGoals(g -> true); // 標的はテストが決める
        return m;
    }

    /** 動かない的(体の物理は効く = 引かれる・押される)。体力を上げ、打たれても死なない。 */
    private static Cow spawnCow(GameTestHelper helper, Vec3 at) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, at);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        cow.setHealth(200.0F);
        return cow;
    }

    private static void keepTarget(GameTestHelper helper, Mob mob, net.minecraft.world.entity.LivingEntity target) {
        helper.onEachTick(() -> {
            if (mob.isAlive() && mob.getTarget() != target) mob.setTarget(target);
        });
    }

    /** 標的を持たせない(隣のテスト場のゾンビの「仲間を呼ぶ」が 16 ブロック先の G1 にも標的を渡すので、毎 tick 外す)。 */
    private static void noTarget(GameTestHelper helper, Mob mob) {
        helper.onEachTick(() -> {
            if (mob.getTarget() != null) mob.setTarget(null);
        });
    }

    private static List<G1SporeCloud> clouds(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(G1SporeCloud.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(9.0D));
    }

    private static void wall(GameTestHelper helper, int z, int height) {
        for (int x = 0; x < 7; x++) {
            for (int y = 2; y < 2 + height; y++) {
                helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            }
        }
    }

    private static void door(GameTestHelper helper, BlockPos lower) {
        BlockState d = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        helper.setBlock(lower, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(lower.above(), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    // ------------------------------------------------------------------ ゾンビ
    /** (b-1) 扉の向こうの標的へ: 木の扉を 4 回叩いて壊す(1 回 40 tick)。壊れた扉は落ちない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 500)
    public static void zombieBashesWoodenDoor(GameTestHelper helper) {
        wall(helper, 4, 3);
        BlockPos doorPos = new BlockPos(3, 2, 4);
        door(helper, doorPos);
        DevelopedParasitized z = spawnDev(helper, G1Developed.ZOMBIE, new Vec3(3.5, 2.0, 1.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 6.0));
        // 無敵にしない(無敵の相手は標的にできない = canAttack が false)
        keepTarget(helper, z, cow);
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_DOOR, doorPos);
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new AABB(helper.absolutePos(doorPos)).inflate(3.0D)).isEmpty(), "壊した扉が落ちた");
        });
    }

    /** (b-2) 近接で殴られると: 胞子の煙を噴き、周りの G1 が殴った相手を標的にする。冷却中は呼ばない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void zombieCallsAlliesWhenStruck(GameTestHelper helper) {
        DevelopedParasitized z = spawnDev(helper, G1Developed.ZOMBIE, new Vec3(1.5, 2.0, 1.5));
        AbstractParasitizedMonster ally = (AbstractParasitizedMonster) helper.spawn(ModG1.PARASITIZED_ZOMBIE.get(), new Vec3(5.5, 2.0, 5.5));
        ally.targetSelector.removeAllGoals(g -> true);
        Cow cow = spawnCow(helper, new Vec3(2.5, 2.0, 1.5));
        // 無敵にしない(無敵の相手は標的にできない = canAttack が false)
        z.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
        helper.assertTrue(ally.getTarget() == cow, "仲間が殴った相手を標的にしない: " + ally.getTarget());
        helper.assertTrue(clouds(helper).stream().anyMatch(c -> c.distanceTo(z) < 1.5D && Math.abs(c.radius() - 2.0F) < 0.01F),
                "殴られて煙を噴いていない");
        // 冷却中(10 秒)にもう一度殴っても噴かない(無敵時間を外して確かめる)
        z.invulnerableTime = 0;
        int before = clouds(helper).size();
        z.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
        helper.assertTrue(clouds(helper).size() == before, "冷却中にまた噴いた");
        helper.succeed();
    }

    /** (c) 3〜5 ブロック先の標的へ肩の蔦: 巻き付いて鈍足になり、引き寄せられる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void zombieVineEntanglesAndPulls(GameTestHelper helper) {
        DevelopedParasitized z = spawnDev(helper, G1Developed.ZOMBIE, new Vec3(1.5, 2.0, 3.5));
        z.goalSelector.removeAllGoals(g -> g instanceof com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal); // 追わせない(距離で引かれたかを見る)
        Cow cow = spawnCow(helper, new Vec3(5.5, 2.0, 3.5));
        keepTarget(helper, z, cow);
        double start = cow.distanceTo(z);
        boolean[] tethered = {false};
        helper.onEachTick(() -> {
            if (z.tether() != null) tethered[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(tethered[0], "蔦が巻き付いていない");
            helper.assertTrue(cow.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "巻かれて鈍足になっていない");
            helper.assertTrue(cow.distanceTo(z) < start - 1.0D, "引き寄せられていない: " + cow.distanceTo(z));
        });
    }

    // ------------------------------------------------------------------ チビゾンビ
    /** (b) 柵(1.5 ブロック)を乗り越えて向こうの標的へ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void babyZombieClimbsOverFence(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) helper.setBlock(new BlockPos(x, 2, 3), Blocks.OAK_FENCE);
        DevelopedParasitized b = spawnDev(helper, G1Developed.BABY_ZOMBIE, new Vec3(3.5, 2.0, 1.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.5));
        // 無敵にしない(無敵の相手は標的にできない = canAttack が false)
        keepTarget(helper, b, cow);
        helper.succeedWhen(() -> helper.assertTrue(b.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.0)).z,
                "柵を越えていない: z = " + (b.getZ() - helper.absolutePos(BlockPos.ZERO).getZ())));
    }

    /** (b) 高さ 1 の隙間をくぐって向こうの標的へ(大人の G1 は通れない高さ)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void babyZombieSqueezesThroughLowGap(GameTestHelper helper) {
        wall(helper, 3, 4);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR); // 高さ 1 の穴
        DevelopedParasitized b = spawnDev(helper, G1Developed.BABY_ZOMBIE, new Vec3(3.5, 2.0, 1.5));
        Cow cow = spawnCow(helper, new Vec3(3.5, 2.0, 5.5));
        // 無敵にしない(無敵の相手は標的にできない = canAttack が false)
        keepTarget(helper, b, cow);
        helper.succeedWhen(() -> helper.assertTrue(b.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.2)).z, "隙間をくぐっていない"));
    }

    /** (a) が当たると取り付き(標的が鈍足に)、取り付いて 1.5 秒で (c) 頭の袋が霞みの煙を噴く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void babyZombieLatchesThenPuffs(GameTestHelper helper) {
        DevelopedParasitized b = spawnDev(helper, G1Developed.BABY_ZOMBIE, new Vec3(2.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.7, 2.0, 3.5));
        keepTarget(helper, b, cow);
        boolean[] latched = {false};
        helper.onEachTick(() -> {
            if (b.latch() != null) latched[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(latched[0], "取り付いていない");
            helper.assertTrue(clouds(helper).stream().anyMatch(G1SporeCloud::haze), "袋が霞みの煙を噴いていない");
        });
    }

    // ------------------------------------------------------------------ 村人
    /** (b) 昼: 胞子の塊を投げ渡す。生きた動物は暴露量 +300、傷ついた G1 は回復 +4(傷ついた G1 を先に)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 560)
    public static void villagerGivesSporesByDay(GameTestHelper helper) {
        DevelopedParasitized v = spawnDev(helper, G1Developed.VILLAGER, new Vec3(1.5, 2.0, 3.5));
        v.setDayOverride(true);
        noTarget(helper, v);
        v.goalSelector.removeAllGoals(g -> g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeMistGoal);
        AbstractParasitizedMonster ally = (AbstractParasitizedMonster) helper.spawnWithNoFreeWill(ModG1.PARASITIZED_ZOMBIE.get(), new Vec3(5.5, 2.0, 2.0));
        ally.setHealth(ally.getMaxHealth() - 3.0F); // 1 回(+4)で治る傷。2 回目(10 秒後)は動物へ
        float before = ally.getHealth();
        Cow cow = spawnCow(helper, new Vec3(5.5, 2.0, 5.0));
        String[] seen = {""};
        helper.onEachTick(() -> helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump.class,
                new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(12.0D)).forEach(l -> {
            if (seen[0].length() < 300) seen[0] += helper.getTick() + ":" + helper.relativeVec(l.position()) + " ";
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(ally.getHealth() >= before + 2.9F, "傷ついた G1 が回復していない: " + ally.getHealth()
                    + " 次に配れる " + (v.giftReadyAt() - helper.getLevel().getGameTime()) + " 行動 " + v.action() + " 標的 " + v.getTarget()
                    + " 村人 " + helper.relativeVec(v.position()) + " 塊 " + seen[0]);
            helper.assertTrue(SporeExposureTracker.dose(cow) >= 299.0D, "動物に胞子が渡っていない: " + SporeExposureTracker.dose(cow));
        });
    }

    /**
     * (b) 夜: その場で眠って嚢を開く(被弾 2 倍)。殴られると目を覚まし、しばらく眠り直さない。
     * 村人は場の真ん中に置く(隣のテスト場の Mob が 8 ブロック以内に入ると眠らないので)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void villagerSleepsOpenAtNightAndTakesDouble(GameTestHelper helper) {
        DevelopedParasitized v = spawnDev(helper, G1Developed.VILLAGER, new Vec3(3.5, 2.0, 3.5));
        v.setDayOverride(false);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 30, () -> {
            helper.assertTrue(v.isSleepingOpen(), "夜に眠らない: 行動 " + v.action());
            float h = v.getHealth();
            v.hurt(helper.getLevel().damageSources().generic(), 2.0F);
            helper.assertTrue(Math.abs(h - v.getHealth() - 4.0F) < 0.01F, "眠っている間の被弾が 2 倍でない: " + (h - v.getHealth()));
        });
        helper.runAtTickTime(t0 + 60, () -> {
            helper.assertTrue(!v.isSleepingOpen(), "殴られても目を覚まさない(か、すぐ眠り直した)");
            helper.succeed();
        });
    }

    /** (b) 夜: 4 ブロックより遠い獲物は、標的になっていても眠りを妨げない。4 ブロック以内に来ると目を覚ます。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void villagerWakesWhenPreyComesClose(GameTestHelper helper) {
        DevelopedParasitized v = spawnDev(helper, G1Developed.VILLAGER, new Vec3(3.5, 2.0, 3.5));
        v.setDayOverride(false);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + 30, () -> {
            helper.assertTrue(v.isSleepingOpen(), "夜に眠らない: 行動 " + v.action());
            v.setTarget(spawnCow(helper, new Vec3(0.4, 2.0, 0.4))); // 4.4 ブロック先
        });
        helper.runAtTickTime(t0 + 55, () -> {
            helper.assertTrue(v.isSleepingOpen(), "4 ブロックより遠い獲物で目を覚ました");
            spawnCow(helper, new Vec3(3.5, 2.0, 5.5)); // 2 ブロック先
        });
        helper.runAtTickTime(t0 + 85, () -> {
            helper.assertTrue(!v.isSleepingOpen(), "獲物が近くに来ても目を覚まさない");
            helper.succeed();
        });
    }

    /** (c) 近くに獲物が 2 体以上いると背の瘤から霧: 中の獲物は鈍足 I。片方と殴り合っている最中でも、1 撃の合間に吹く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void villagerSporeMistSlows(GameTestHelper helper) {
        DevelopedParasitized v = spawnDev(helper, G1Developed.VILLAGER, new Vec3(3.5, 2.0, 3.5));
        v.goalSelector.removeAllGoals(g -> g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1SleepGoal || g instanceof com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeGiftGoal);
        Cow a = spawnCow(helper, new Vec3(1.8, 2.0, 3.5));
        keepTarget(helper, v, a);
        // 2 体目は、最初の 1 撃を振ってから置く(殴り合いが始まってから条件がそろう形)
        Cow[] second = {null};
        String[] trace = {""};
        int[] last = {-1};
        helper.onEachTick(() -> {
            if (v.action() != last[0] && trace[0].length() < 400) {
                last[0] = v.action();
                trace[0] += helper.getTick() + ":" + last[0] + (v.techniqueReady() ? "r " : " ");
            }
            if (second[0] == null && v.action() == AbstractParasitizedMonster.ACTION_STRIKE) {
                // 1 体目のすぐ隣(追われて押されても 4 ブロックの境目を出入りしない所)
                second[0] = spawnCow(helper, helper.relativeVec(a.position()).add(0.0D, 0.0D, a.getZ() - helper.absoluteVec(new Vec3(0, 0, 3.5)).z > 0 ? -1.2D : 1.2D));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(second[0] != null, "まだ 1 撃を振っていない");
            helper.assertTrue(clouds(helper).stream().anyMatch(c -> Math.abs(c.radius() - 2.5F) < 0.01F), "霧を吹いていない: 行動の移り " + trace[0] + " 技 " + v.techniques().size() + " 距離 " + v.distanceTo(a) + "/" + v.distanceTo(second[0])
                    + " 動いている " + v.goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                    .map(g -> g.getGoal().getClass().getSimpleName()).toList());
            // 追われている牛は押されて動くので、2 体目は霧の半径(2.5)の外に出ることがある。霧の中にいる 1 体目で見る
            helper.assertTrue(a.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "霧の中の獲物が鈍足にならない");
        });
    }

    // ------------------------------------------------------------------ 豚
    /** (b) 敵がいないと近くのチビゾンビ(段階 0)のそばで伏せ、背に乗せる。乗せている間は少し速い。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void pigLoadsBabyZombie(GameTestHelper helper) {
        DevelopedParasitized pig = spawnDev(helper, G1Developed.PIG, new Vec3(1.5, 2.0, 3.5));
        AbstractParasitizedMonster baby = (AbstractParasitizedMonster) helper.spawn(ModG1.PARASITIZED_BABY_ZOMBIE.get(), new Vec3(5.0, 2.0, 3.5));
        baby.targetSelector.removeAllGoals(g -> true);
        noTarget(helper, pig);
        noTarget(helper, baby);
        double base = pig.getAttributeValue(Attributes.MOVEMENT_SPEED);
        helper.succeedWhen(() -> {
            helper.assertTrue(baby.getVehicle() == pig, "背に乗せていない");
            helper.assertTrue(pig.getAttributeValue(Attributes.MOVEMENT_SPEED) > base * 1.15D, "乗せても速くならない");
        });
    }

    /** (b) 乗せたまま標的へ: 4〜6 ブロックで乗り手を放り出し、1 段目のチビゾンビは着地して取り付く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void pigTossesRiderWhoLatches(GameTestHelper helper) {
        DevelopedParasitized pig = spawnDev(helper, G1Developed.PIG, new Vec3(0.8, 2.0, 3.5));
        pig.goalSelector.removeAllGoals(g -> g instanceof com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal); // その場で放らせる
        DevelopedParasitized baby = spawnDev(helper, G1Developed.BABY_ZOMBIE, new Vec3(0.8, 3.0, 3.5));
        baby.startRiding(pig, true);
        Cow cow = spawnCow(helper, new Vec3(5.6, 2.0, 3.5));
        // 無敵にしない(無敵の相手は標的にできない = canAttack が false)
        keepTarget(helper, pig, cow);
        boolean[] latched = {false};
        helper.onEachTick(() -> {
            if (baby.latch() != null && baby.latch().target() == cow) latched[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!baby.isPassenger(), "放り出していない");
            helper.assertTrue(latched[0], "放り出されたチビゾンビが取り付いていない: 距離 " + baby.distanceTo(cow));
        });
    }

    /** (c) 2〜3 ブロック正面の標的へ喉袋の円錐: 暴露量 +250。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void pigThroatPuffDosesCone(GameTestHelper helper) {
        SporeAmbient.isolate(helper, 80); // 隣のテスト場から広がる胞子の場の浮遊を読まない(技が直に与えた分だけを見る。2026-11-18 揺れるテストの調べ)
        DevelopedParasitized pig = spawnDev(helper, G1Developed.PIG, new Vec3(1.5, 2.0, 3.5));
        pig.goalSelector.removeAllGoals(g -> g instanceof com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal); // 噛みに行かせない
        pig.setYRot(-90.0F);
        pig.setYBodyRot(-90.0F);
        pig.yHeadRot = -90.0F;
        Cow cow = spawnCow(helper, new Vec3(4.0, 2.0, 3.5));
        Cow behind = spawnCow(helper, new Vec3(0.3, 2.0, 3.5));
        keepTarget(helper, pig, cow);
        helper.succeedWhen(() -> {
            helper.assertTrue(SporeExposureTracker.dose(cow) >= 249.0D, "円錐の中の暴露量が足りない: " + SporeExposureTracker.dose(cow));
            helper.assertTrue(SporeExposureTracker.dose(behind) < 1.0D, "後ろまで浴びた");
        });
    }

    /**
     * (a) 食らいつき: 1 撃が当たると 40 tick 離さない。その間は鈍足 III・暴露量 +100 / 秒・傷は増えない。離したら隙(attack_recover)を流し、
     * 隙の後すぐには次の 1 撃を振り出さない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void pigBiteHoldsAndDoses(GameTestHelper helper) {
        DevelopedParasitized pig = spawnDev(helper, G1Developed.PIG, new Vec3(2.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.8, 2.0, 3.5));
        keepTarget(helper, pig, cow);
        int[] phase = {0}; // 0 = まだ・1 = 噛んでいる・2 = 離した
        int[] held = {0};
        long[] releasedAt = {0L};
        float[] hp = {0.0F, 0.0F};
        double[] dose = {0.0D, 0.0D};
        String[] bad = {null};
        helper.onEachTick(() -> {
            var latch = pig.latch();
            if (phase[0] == 0) {
                if (latch != null) {
                    phase[0] = 1;
                    hp[0] = cow.getHealth();
                    dose[0] = SporeExposureTracker.dose(cow);
                }
            } else if (phase[0] == 1) {
                if (latch != null) {
                    held[0] = latch.ticks();
                    hp[1] = cow.getHealth();
                    dose[1] = SporeExposureTracker.dose(cow);
                    if (pig.action() != com.yayuy.overgrownvegetation.entity.g1.skill.G1Move.BITE_HOLD.id()) bad[0] = "噛んでいる間の行動が attack_hold でない: " + pig.action();
                    var slow = cow.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    if (latch.ticks() >= 3 && (slow == null || slow.getAmplifier() < 2)) bad[0] = "噛まれた相手が鈍足 III になっていない";
                } else {
                    phase[0] = 2;
                    releasedAt[0] = helper.getTick();
                    if (pig.action() != com.yayuy.overgrownvegetation.entity.g1.skill.G1Move.BITE_RECOVER.id()) bad[0] = "離した後に隙(attack_recover)を流していない: " + pig.action();
                }
            } else if (helper.getTick() - releasedAt[0] <= DevelopedParasitized.BITE_RECOVER_TICKS + 8
                    && pig.action() == AbstractParasitizedMonster.ACTION_STRIKE) {
                bad[0] = "離してすぐにまた噛みに行った: " + (helper.getTick() - releasedAt[0]) + " tick 後";
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 2 && helper.getTick() - releasedAt[0] > DevelopedParasitized.BITE_RECOVER_TICKS + 8, "まだ噛んで離していない: 段 " + phase[0]);
            helper.assertTrue(bad[0] == null, String.valueOf(bad[0]));
            helper.assertTrue(held[0] >= DevelopedParasitized.BITE_HOLD_TICKS - 1, "離すのが早い: " + held[0] + " tick");
            helper.assertTrue(dose[1] - dose[0] >= 2.0D * DevelopedParasitized.BITE_DOSE, "噛んでいる間の暴露量が足りない: +" + (dose[1] - dose[0]));
            helper.assertTrue(hp[1] >= hp[0], "噛んでいる間に傷が増えた: " + hp[0] + " → " + hp[1]);
        });
    }

    /** (a) 食らいつき: 噛まれた相手に 1 回殴られると振りほどかれ、よろける(その間は動かず殴らない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void pigBiteShakenOffByOneHit(GameTestHelper helper) {
        DevelopedParasitized pig = spawnDev(helper, G1Developed.PIG, new Vec3(2.5, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(3.8, 2.0, 3.5));
        keepTarget(helper, pig, cow);
        boolean[] done = {false};
        String[] bad = {null};
        helper.onEachTick(() -> {
            if (done[0] || pig.latch() == null || pig.latch().ticks() < 5) return;
            done[0] = true;
            pig.invulnerableTime = 0;
            pig.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
            if (pig.latch() != null) bad[0] = "殴られても離さない";
            else if (!pig.isStunned()) bad[0] = "振りほどかれてもよろけない";
            else if (pig.action() != com.yayuy.overgrownvegetation.entity.g1.skill.G1Move.BITE_RECOVER.id()) bad[0] = "よろけのアニメ(attack_recover)を流していない: " + pig.action();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(done[0], "噛みついていない");
            helper.assertTrue(bad[0] == null, String.valueOf(bad[0]));
        });
    }

    /** (a) 食らいつき: 噛まれた相手が歩いても離さず、引きずられる(引きずられなければ 3 ブロックで外れる)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void pigBiteDraggedAlong(GameTestHelper helper) {
        DevelopedParasitized pig = spawnDev(helper, G1Developed.PIG, new Vec3(1.2, 2.0, 3.5));
        Cow cow = spawnCow(helper, new Vec3(2.5, 2.0, 3.5));
        keepTarget(helper, pig, cow);
        double endX = helper.absoluteVec(new Vec3(6.0, 2.0, 3.5)).x;
        double[] pigStart = {Double.NaN};
        boolean[] lost = {false};
        boolean[] arrived = {false};
        helper.onEachTick(() -> {
            if (arrived[0]) return;
            if (Double.isNaN(pigStart[0])) {
                if (pig.latch() == null) return;
                pigStart[0] = pig.getX();
            }
            if (pig.latch() == null) {
                lost[0] = true;
                return;
            }
            if (cow.getX() < endX) {
                cow.setPos(Math.min(endX, cow.getX() + 0.1D), cow.getY(), cow.getZ()); // 2 ブロック / 秒で歩いて離れる
            } else {
                arrived[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!lost[0], "歩いて離れたら外れた");
            helper.assertTrue(arrived[0], "まだ歩いている");
            helper.assertTrue(pig.getX() - pigStart[0] >= 2.0D, "引きずられていない: " + (pig.getX() - pigStart[0]));
        });
    }
}
