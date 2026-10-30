package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.JammingAvoidGoal;
import com.yayuy.overgrownvegetation.base.JammingLampBlock;
import com.yayuy.overgrownvegetation.base.JammingLamps;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.entity.RepellentAvoidGoal;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 妨害灯(2026-10-01、{@link JammingLampBlock})のテスト。
 *
 * <p>守りたいのは 3 つ: 忌避灯の台帳と妨害灯の台帳の両方に出入りする(覆う・剥がす・壊すで揃って動く)/
 * 半径の中の過繁茂の Mob だけに弱体化と移動速度低下が付く / グループ5〜6 にだけ追い出しの Goal が足される
 * (1〜4 は忌避灯の Goal が妨害灯も見る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class JammingLampGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(JammingLampGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos LAMP = new BlockPos(3, 2, 3);

    /** 置くと両方の台帳に載り、覆うと両方から外れ、剥がすと戻り、壊すと消える。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void ledgerFollowsCoverAndBreak(GameTestHelper helper) {
        helper.setBlock(LAMP, BaseBlocks.JAMMING_LAMP.get());
        BlockPos abs = helper.absolutePos(LAMP);
        helper.runAfterDelay(2, () -> {
            var level = helper.getLevel();
            if (!registeredInBoth(helper, abs)) {
                helper.fail("置いた妨害灯が台帳に載っていない");
                return;
            }
            BlockState state = level.getBlockState(abs);
            JammingLampBlock block = (JammingLampBlock) state.getBlock();
            block.setCovered(level, abs, state, true);
            if (JammingLamps.isRegistered(level, abs) || nearestRepellent(helper, abs) != null) {
                helper.fail("覆った妨害灯が台帳に残っている");
                return;
            }
            BlockState covered = level.getBlockState(abs);
            block.setCovered(level, abs, covered, false);
            if (!registeredInBoth(helper, abs)) {
                helper.fail("剥がした妨害灯が台帳に戻らない");
                return;
            }
            helper.setBlock(LAMP, Blocks.AIR);
            if (JammingLamps.isRegistered(level, abs) || nearestRepellent(helper, abs) != null) {
                helper.fail("壊した妨害灯が台帳に残っている");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 半径の中の過繁茂の Mob は弱体化の対象になり、バニラの牛はならない。付けると弱体化と移動速度低下の両方が付く。
     * 灯りのブロックは置かない・対象の全員には付けない —— 並列で走る隣の場の Mob(半径 12 に入る)を巻き込まないため
     * (初回は全員に付けて、隣の場の Mob まで数えて落ちた)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void debuffsOnlyOvergrownMobs(GameTestHelper helper) {
        Mob infected = helper.spawn(ModEntities.PARASITIZED_COW.get(), new BlockPos(5, 2, 3));
        Mob cow = helper.spawn(EntityType.COW, new BlockPos(1, 2, 3));
        infected.setNoAi(true);
        cow.setNoAi(true);
        helper.runAfterDelay(2, () -> {
            var targets = JammingLamps.targets(helper.getLevel(), helper.absolutePos(LAMP));
            if (!targets.contains(infected)) {
                helper.fail("半径の中の過繁茂の牛が対象に入らない");
                return;
            }
            if (targets.contains(cow)) {
                helper.fail("バニラの牛が対象に入った");
                return;
            }
            JammingLamps.debuff(infected);
            if (!infected.hasEffect(MobEffects.WEAKNESS) || !infected.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
                helper.fail("過繁茂の牛に弱体化・移動速度低下が付いていない");
                return;
            }
            infected.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** グループ5(ファルキムネス)には妨害灯の追い出しが足され、グループ1(過繁茂の牛)には足されない(忌避灯の Goal が見る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void avoidGoalOnlyForGroupsFiveAndSix(GameTestHelper helper) {
        Mob falcimnes = helper.spawn(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, 3));
        Mob infected = helper.spawn(ModEntities.PARASITIZED_COW.get(), new BlockPos(1, 2, 1));
        boolean g5Jamming = hasGoal(falcimnes, JammingAvoidGoal.class);
        boolean g1Jamming = hasGoal(infected, JammingAvoidGoal.class);
        boolean g1Repellent = infected.goalSelector.getAvailableGoals().stream()
                .anyMatch(w -> w.getGoal().getClass() == RepellentAvoidGoal.class);
        falcimnes.discard();
        infected.discard();
        if (!g5Jamming) {
            helper.fail("グループ5 に妨害灯の追い出しが無い");
        } else if (g1Jamming) {
            helper.fail("グループ1 に妨害灯の追い出しが二重に足された");
        } else if (!g1Repellent) {
            helper.fail("グループ1 に忌避灯の追い出しが無い(妨害灯もこれで避ける)");
        } else {
            helper.succeed();
        }
    }

    private static boolean hasGoal(Mob mob, Class<?> type) {
        return mob.goalSelector.getAvailableGoals().stream().anyMatch(w -> type.isInstance(w.getGoal()));
    }

    private static boolean registeredInBoth(GameTestHelper helper, BlockPos abs) {
        return JammingLamps.isRegistered(helper.getLevel(), abs) && abs.equals(nearestRepellent(helper, abs));
    }

    private static BlockPos nearestRepellent(GameTestHelper helper, BlockPos abs) {
        return RepellentLamps.nearestActive(helper.getLevel(), Vec3.atCenterOf(abs), 0.5D);
    }

    private JammingLampGameTests() {
    }
}
