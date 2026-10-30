package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.NearestFirstTargetGoal;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 0.1.9 の前の重さの直し(2026-10-10)のうち、挙動が変わるもの・選ぶ相手が変わってはいけないものの確かめ。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PerfFixGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PerfFixGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";

    /**
     * 苗床は足場の上で止まっている間、動きの計算を 20 tick に 1 回にする(ユーザー了承)。止まっている tick は動かず、
     * 1 秒ごとの計算では足元を消せば落ち始める。根を伸ばして周りを壊さないよう、世界に入れずに作って直接動かす(体も小さくする)。
     */
    @GameTest(template = TEMPLATE)
    public static void settledNexusSkipsTravelButStillFalls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        OvergrownNexusEntity nexus = ModEntities.OVERGROWN_NEXUS.get().create(level);
        if (nexus == null) {
            helper.fail("苗床を作れない");
            return;
        }
        nexus.getAttribute(Attributes.SCALE).setBaseValue(0.0625D);
        nexus.refreshDimensions();
        BlockPos feet = helper.absolutePos(new BlockPos(3, 2, 3));
        nexus.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        int base = Math.floorMod(-nexus.getId(), 20); // (tickCount + id) % 20 == 0 の tick = 毎秒の計算

        nexus.tickCount = base;
        for (int i = 0; i < 3; i++) {
            nexus.travel(Vec3.ZERO); // バニラは前の速さで動いてから重力を足すので、足場に着くのは 2 回目から
        }
        if (!nexus.onGround()) {
            helper.fail("床の上で足場に着かない: y=" + nexus.getY());
            return;
        }
        double settledY = nexus.getY();
        level.setBlock(feet.below(), Blocks.AIR.defaultBlockState(), 3);

        nexus.tickCount = base + 1;
        nexus.travel(Vec3.ZERO);
        if (nexus.getY() != settledY) {
            helper.fail("止まっている間の tick なのに動きを計算した: " + settledY + " → " + nexus.getY());
            return;
        }
        nexus.tickCount = base + 20;
        nexus.travel(Vec3.ZERO);
        if (!(nexus.getY() < settledY - 0.01D)) {
            helper.fail("足元を消して 1 秒ごとの計算が来ても落ちない: " + settledY + " → " + nexus.getY());
            return;
        }
        helper.succeed();
    }

    /**
     * G1 は届かない相手(壁で仕切った向こう)を見失い、200 tick は選び直さない(ユーザー了承)。前は途中までの経路でも「届く」と
     * 判断して追い続けていた。射程が長いので隣のテスト場の Mob を拾わないよう、バッチを分ける。
     */
    @GameTest(template = TEMPLATE, batch = "g1_unreachable", timeoutTicks = 420)
    public static void g1DropsUnreachableTargetAndIgnoresIt(GameTestHelper helper) {
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
            }
        }
        Mob zombie = helper.spawn(ModEntities.PARASITIZED_ZOMBIE.get(), new BlockPos(1, 2, 3));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // 日に焼けないように
        Mob husk = helper.spawn(EntityType.HUSK, new BlockPos(5, 2, 3));
        husk.setNoAi(true);
        boolean[] targeted = {false};
        long[] dropTick = {-1L};
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (zombie.getTarget() == husk) {
                if (dropTick[0] >= 0L) {
                    helper.fail("見失った相手を " + (t - dropTick[0]) + " tick で選び直した(200 tick は選ばないはず)");
                }
                targeted[0] = true;
            } else if (targeted[0] && dropTick[0] < 0L) {
                dropTick[0] = t; // 見失った
            }
            if (dropTick[0] >= 0L && t >= dropTick[0] + 150L) {
                helper.succeed();
            } else if (t >= 260L && dropTick[0] < 0L) {
                helper.fail(targeted[0] ? "届かない相手を 260 tick たっても追い続けている"
                        : "G1 が壁の向こうの相手を一度も狙わなかった(テストの前提が崩れた)");
            }
        });
    }

    /**
     * 届く相手は「届く」と判定される: ハーフブロック・階段・カーペット・雪の層の上に立つ相手でも、G1 の経路の判定({@code ParasitizedTargetGoal#reachesTarget}
     * と同じ引き方)で届く。行き先のマス(相手の足のマス)と経路の終わりのマスがずれる足場で、届くのに諦めないことの確かめ(2026-10-10)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void g1ReachesTargetsOnPartialBlocks(GameTestHelper helper) {
        BlockPos[] spots = {new BlockPos(5, 2, 0), new BlockPos(5, 2, 2), new BlockPos(5, 2, 4), new BlockPos(5, 2, 6)};
        helper.setBlock(spots[0], Blocks.STONE_SLAB);
        helper.setBlock(spots[1], Blocks.STONE_STAIRS);
        helper.setBlock(spots[2], Blocks.WHITE_CARPET);
        helper.setBlock(spots[3], Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 2));
        Mob zombie = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new BlockPos(1, 2, 3));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        Mob[] husks = new Mob[spots.length];
        for (int i = 0; i < spots.length; i++) {
            husks[i] = helper.spawnWithNoFreeWill(EntityType.HUSK, spots[i].above());
            husks[i].setNoAi(true);
        }
        helper.runAfterDelay(20, () -> {
            StringBuilder bad = new StringBuilder();
            String[] names = {"ハーフブロック", "階段", "カーペット", "雪の層"};
            for (int i = 0; i < husks.length; i++) {
                net.minecraft.world.level.pathfinder.Path path = zombie.getNavigation().createPath(husks[i], 0);
                boolean reached = com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal.pathReaches(path);
                if (!reached) {
                    bad.append(names[i]).append("(経路 ").append(path == null ? "無し" : "終わりまで " + path.getDistToTarget()
                            + "・canReach " + path.canReach() + "・行き先 " + path.getTarget() + "・相手 " + husks[i].blockPosition()).append(") ");
                }
            }
            if (bad.length() > 0) {
                helper.fail("届く相手を届かないと判定した: " + bad);
            } else {
                helper.succeed();
            }
        });
    }

    /** 兵士の標的探し(近い順に確かめて止める)は、バニラの全員を確かめる探し方と同じ相手を選ぶ。壁の陰の近い敵は見えないので飛ばす。 */
    @GameTest(template = TEMPLATE)
    public static void nearestFirstTargetGoalPicksSameAsVanilla(GameTestHelper helper) {
        for (int y = 2; y <= 4; y++) {
            helper.setBlock(new BlockPos(2, y, 1), Blocks.STONE); // 兵士と近い敵の間の壁
        }
        SoldierEntity soldier = (SoldierEntity) helper.spawnWithNoFreeWill(AllyEntities.type(SoldierKind.RIFLEMAN), new BlockPos(1, 2, 1));
        LivingEntity hidden = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 1)); // 近いが壁の陰
        LivingEntity seen = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 2, 5));
        helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(5, 2, 5));
        helper.runAfterDelay(2, () -> {
            NearestAttackableTargetGoal<Mob> vanilla = new NearestAttackableTargetGoal<>(soldier, Mob.class, 0, true, false, SoldierEntity::isFoe);
            NearestFirstTargetGoal<Mob> mine = new NearestFirstTargetGoal<>(soldier, Mob.class, 0, true, false, SoldierEntity::isFoe);
            soldier.setTarget(null);
            boolean vanillaFound = vanilla.canUse();
            if (vanillaFound) {
                vanilla.start();
            }
            LivingEntity vanillaPick = soldier.getTarget();
            soldier.setTarget(null);
            boolean mineFound = mine.canUse();
            if (mineFound) {
                mine.start();
            }
            LivingEntity minePick = soldier.getTarget();
            soldier.setTarget(null);
            if (vanillaPick != minePick) {
                helper.fail("選んだ相手がバニラと違う: バニラ " + vanillaPick + " / 近い順 " + minePick);
            } else if (minePick != seen) {
                helper.fail("見えるいちばん近い敵を選んでいない(壁の陰 " + hidden + " を飛ばすはず): " + minePick);
            } else {
                helper.succeed();
            }
        });
    }

    private PerfFixGameTests() {
    }
}
