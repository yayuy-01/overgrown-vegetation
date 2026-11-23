package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.GenericInfectionSpec;
import com.yayuy.overgrownvegetation.infect.GenericInfection;
import com.yayuy.overgrownvegetation.infect.InfectionProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 感染の試し棒(2026-11-19、{@link InfectionProbe})。殴る口は偽プレイヤーを使わず {@link InfectionProbe#strike} を直に呼ぶ
 * (アイテムは onLeftClickEntity からこれを呼ぶだけ)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class InfectionProbeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterInfectionProbeGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(InfectionProbeGameTests.class);
        }

        private Registration() {
        }
    }

    private InfectionProbeGameTests() {
    }

    private static final String BATCH = "infection_probe";

    private static boolean peaceful(GameTestHelper helper) {
        if (helper.getLevel().getDifficulty() == Difficulty.PEACEFUL) {
            helper.fail("テストのワールドがピースフル(変わらない決まり)");
            return true;
        }
        return false;
    }

    /** 専用の G1 が無い馬は、試し棒で殴ると死なずに汎用の感染体になる。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void strikeHorseTurnsGeneric(GameTestHelper helper) {
        if (peaceful(helper)) return;
        Mob horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        InfectionProbe.Outcome o = InfectionProbe.strike(helper.getLevel(), horse);
        if (o.kind() != InfectionProbe.Kind.GENERIC || !horse.isAlive() || !GenericInfection.isTurned(horse)) {
            helper.fail("馬: " + o + "(生きている " + horse.isAlive() + "・変わった " + GenericInfection.isTurned(horse) + ")");
            return;
        }
        if (Math.abs(horse.getHealth() - horse.getMaxHealth()) > 1.0E-3F) {
            helper.fail("変わった馬が満タンでない: " + horse.getHealth() + "/" + horse.getMaxHealth());
            return;
        }
        helper.succeed();
    }

    /** 専用の G1 がある大人のゾンビは、試し棒で G1 の死体へ崩れ落ちる(汎用の感染体にはならない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void strikeZombieBecomesG1(GameTestHelper helper) {
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        zombie.setBaby(false);
        InfectionProbe.Outcome o = InfectionProbe.strike(helper.getLevel(), zombie);
        if (o.kind() != InfectionProbe.Kind.G1 || o.species() != G1Species.ZOMBIE || !CorpseCollapse.isCollapsing(zombie)) {
            helper.fail("ゾンビ: " + o + "(崩れ落ち " + CorpseCollapse.isCollapsing(zombie) + ")");
            return;
        }
        if (GenericInfection.isTurned(zombie)) {
            helper.fail("ゾンビが汎用の感染体になった");
            return;
        }
        helper.succeed();
    }

    /** もう植物側(G1)は殺さない。無敵の馬は死なずに「死ななかった」と出る(弾かれた道筋も無敵は数えない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void plantSideAndInvulnerableAreReported(GameTestHelper helper) {
        Mob g1 = helper.spawnWithNoFreeWill(G1Species.ZOMBIE.entityType(), new BlockPos(1, 2, 1));
        InfectionProbe.Outcome a = InfectionProbe.strike(helper.getLevel(), g1);
        if (a.kind() != InfectionProbe.Kind.PLANT_SIDE || !g1.isAlive()) {
            helper.fail("G1: " + a + "(生きている " + g1.isAlive() + ")");
            return;
        }
        Mob horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 2, 5));
        horse.setInvulnerable(true);
        InfectionProbe.Outcome b = InfectionProbe.strike(helper.getLevel(), horse);
        if (b.kind() != InfectionProbe.Kind.SURVIVED || !horse.isAlive() || GenericInfection.isTurned(horse)) {
            helper.fail("無敵の馬: " + b);
            return;
        }
        helper.succeed();
    }

    /** なれない理由: 設計値の enabled false・除外の表・専用の G1 がある種・Mob でない(防具立て)。判定(canTurn)と理由が揃う。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void refusalReasons(GameTestHelper helper) {
        Mob horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 1));
        LivingEntity stand = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(3, 2, 5));
        GenericInfectionSpec s = GenericInfection.spec();
        GenericInfectionSpec off = new GenericInfectionSpec(false, s.transformTicks(), s.bossMaxHealthFactor(), s.bossEntities(),
                s.excludedEntities(), s.ramDamage(), s.ramCooldownTicks(), s.ramSpeed(), s.targetRange(), s.fallbackTicks());
        GenericInfectionSpec excl = new GenericInfectionSpec(true, s.transformTicks(), s.bossMaxHealthFactor(), s.bossEntities(),
                List.of("minecraft:horse"), s.ramDamage(), s.ramCooldownTicks(), s.ramSpeed(), s.targetRange(), s.fallbackTicks());
        String bad = "";
        if (GenericInfection.refusal(horse, s) != null) bad += " 馬(既定)=" + GenericInfection.refusal(horse, s);
        if (GenericInfection.refusal(horse, off) != GenericInfection.Refusal.DISABLED) bad += " 馬(切)=" + GenericInfection.refusal(horse, off);
        if (GenericInfection.refusal(horse, excl) != GenericInfection.Refusal.EXCLUDED) bad += " 馬(除外)=" + GenericInfection.refusal(horse, excl);
        if (GenericInfection.refusal(cow, s) != GenericInfection.Refusal.HAS_G1) bad += " 牛=" + GenericInfection.refusal(cow, s);
        if (GenericInfection.refusal(stand, s) != GenericInfection.Refusal.NOT_MOB) bad += " 防具立て=" + GenericInfection.refusal(stand, s);
        if (GenericInfection.canTurn(horse) != (GenericInfection.refusal(horse, s) == null) || GenericInfection.canTurn(cow)) bad += " canTurn と理由がずれた";
        InfectionProbe.Outcome d = InfectionProbe.diagnose(stand);
        if (d.kind() != InfectionProbe.Kind.DIED || d.refusal() != GenericInfection.Refusal.NOT_MOB) bad += " 防具立ての見立て=" + d;
        if (!bad.isEmpty()) {
            helper.fail(bad.trim());
            return;
        }
        helper.succeed();
    }

    /** 見るだけ(スニーク + 右クリック)は殺さない: 馬 = 汎用の感染体になる・牛 = G1(牛)・ウィザー = ボスの印。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void diagnoseDoesNotKill(GameTestHelper helper) {
        Mob horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 1));
        Mob wither = helper.spawnWithNoFreeWill(EntityType.WITHER, new BlockPos(3, 2, 5));
        InfectionProbe.Outcome h = InfectionProbe.diagnose(horse), c = InfectionProbe.diagnose(cow), w = InfectionProbe.diagnose(wither);
        wither.discard();
        String bad = "";
        if (h.kind() != InfectionProbe.Kind.GENERIC) bad += " 馬=" + h;
        if (c.kind() != InfectionProbe.Kind.G1 || c.species() != G1Species.COW) bad += " 牛=" + c;
        if (w.kind() != InfectionProbe.Kind.GENERIC || !w.boss()) bad += " ウィザー=" + w;
        if (!horse.isAlive() || !cow.isAlive() || GenericInfection.isTurned(horse)) bad += " 見るだけで変わった・死んだ";
        if (!bad.isEmpty()) {
            helper.fail(bad.trim());
            return;
        }
        helper.succeed();
    }
}
