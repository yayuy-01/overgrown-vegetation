package com.yayuy.overgrownvegetation.gametest;

import com.mojang.serialization.JsonOps;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.infect.GenericInfected;
import com.yayuy.overgrownvegetation.infect.GenericInfection;
import com.yayuy.overgrownvegetation.network.GenericInfectionPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 汎用の感染体の区切り 1(印・保存・同期の中身・なれる種の判定。2026-11-15、{@link GenericInfection})。
 * クライアントに届くか(put / remove)は GameTest では見られない —— Claude 用クライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GenericInfectionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGenericInfectionGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(GenericInfectionGameTests.class);
        }

        private Registration() {
        }
    }

    private GenericInfectionGameTests() {
    }

    private static final String BATCH = "generic_infection";

    /** 専用の G1 が無い馬は変わる・植物側になる(暴露量を測らない)・変わる動きの長さは設計値。戻すと元どおり。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void horseTurnsAndReverts(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        if (!GenericInfection.canTurn(h) || !GenericInfection.turn(h, false)) {
            helper.fail("馬が変わらない");
            return;
        }
        if (!GenericInfection.isTurned(h) || !SporeExposureTracker.isPlantSide(h) || SporeExposureTracker.isMeasuredMob(h)) {
            helper.fail("変わった馬が植物側になっていない");
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isTransformed(h)) {
            helper.fail("植物側の決まり(PlantSideRules.TRANSFORMED)に印が差し込まれていない");
            return;
        }
        GenericInfected g = OvAttach.getData(h, ModAttachments.GENERIC_INFECTED);
        if (g.transformTicks != GenericInfection.spec().transformTicks() || !GenericInfection.isTransforming(h) == (g.transformTicks > 0)) {
            helper.fail("変わる動きの長さが " + g.transformTicks + "(設計値 " + GenericInfection.spec().transformTicks() + ")");
            return;
        }
        if (GenericInfection.turn(h, false)) {
            helper.fail("二度変わった");
            return;
        }
        if (!GenericInfection.revert(h) || GenericInfection.isTurned(h) || SporeExposureTracker.isPlantSide(h)) {
            helper.fail("戻らない");
            return;
        }
        helper.succeed();
    }

    /** 専用の G1 がある種(大人の牛)と植物側(G1)は変わらない。表の種の子ども(子牛)・ウォーデンは変わる(★ 問い 3・4)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void listedAdultsDoNotTurnButCalvesAndWardenDo(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1, 2, 1));
        Mob calf = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 1));
        calf.setBaby(true);
        Mob warden = helper.spawnWithNoFreeWill(EntityType.WARDEN, new BlockPos(3, 2, 5));
        Mob g1 = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new BlockPos(1, 2, 5));
        for (Mob m : new Mob[]{cow, g1}) {
            if (GenericInfection.turn(m, true)) {
                helper.fail(m.getType().getDescriptionId() + " が変わった");
                return;
            }
        }
        for (Mob m : new Mob[]{calf, warden}) {
            if (!GenericInfection.turn(m, true)) {
                helper.fail(m.getType().getDescriptionId() + (m.isBaby() ? "(子ども)" : "") + " が変わらない");
                return;
            }
        }
        warden.discard();
        helper.succeed();
    }

    /**
     * ★ 問い 1・2: 胞子の 20 ダメージで死ぬ所で、死なずに変わる。胞子の傷を外して元の最大体力・満タン。変わった後は暴露量を測らない。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void lethalSporeHitTurnsInsteadOfKilling(GameTestHelper helper) {
        if (helper.getLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            helper.fail("テストのワールドがピースフル(変わらない決まり)");
            return;
        }
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        double base = h.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).getBaseValue();
        SporeExposureTracker.scarMaxHealth(h, Math.min(5.0D, base - 2.0D));
        h.setHealth(2.0F);
        SporeExposureTracker.setDose(h, com.yayuy.overgrownvegetation.data.DesignData.sporeField().infectDose(), helper.getLevel().getGameTime());
        SporeExposureTracker.tickWith(helper.getLevel(), h, 40, 0.0D);
        if (!h.isAlive() || h.isRemoved()) {
            helper.fail("死んだ");
            return;
        }
        if (!GenericInfection.isTurned(h)) {
            helper.fail("変わっていない(体力 " + h.getHealth() + ")");
            return;
        }
        if (Math.abs(h.getMaxHealth() - base) > 1.0E-3 || Math.abs(h.getHealth() - h.getMaxHealth()) > 1.0E-3) {
            helper.fail("体力が " + h.getHealth() + "/" + h.getMaxHealth() + "(元の最大 " + base + ")");
            return;
        }
        if (SporeExposureTracker.isMeasuredMob(h) || com.yayuy.overgrownvegetation.spore.MobInfection.isInfected(h)) {
            helper.fail("変わった後も感染の仕組みが測っている");
            return;
        }
        helper.succeed();
    }

    /** ★ 問い 2: G1 に殴り殺された、専用の G1 が無い種(馬)も変わる。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void killedByG1TurnsInstead(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob g1 = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new BlockPos(5, 2, 5));
        h.hurt(helper.getLevel().damageSources().mobAttack(g1), 1000.0F);
        if (!h.isAlive() || !GenericInfection.isTurned(h)) {
            helper.fail("G1 に殴り殺された馬が変わらない(生きている " + h.isAlive() + ")");
            return;
        }
        helper.succeed();
    }

    /** ★ ボスの体力: ウィザーは最大体力が元の 0.6 倍(倍率は設計値)・満タン。戻すと元に戻る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void bossMaxHealthIsScaled(GameTestHelper helper) {
        LivingEntity wither = helper.spawnWithNoFreeWill(EntityType.WITHER, new BlockPos(3, 2, 3));
        double base = wither.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).getBaseValue();
        GenericInfection.turn(wither, true);
        double want = base * GenericInfection.spec().bossMaxHealthFactor();
        float got = wither.getMaxHealth(), hp = wither.getHealth();
        GenericInfection.revert(wither);
        float back = wither.getMaxHealth();
        wither.discard();
        if (Math.abs(got - want) > 0.01D || Math.abs(hp - got) > 0.01D) {
            helper.fail("ボスの体力が " + hp + "/" + got + "(期待 " + want + ")");
            return;
        }
        if (Math.abs(back - base) > 0.01D) {
            helper.fail("戻した後の最大体力が " + back);
            return;
        }
        helper.succeed();
    }

    /** ★ 問い 3・6・13: 攻撃を持たない馬が、植物側でない豚を狙って体当たりで傷を付ける(視線を問わない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 200)
    public static void turnedHorseRamsPig(GameTestHelper helper) {
        Horse h = helper.spawn(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 5));
        pig.setHealth(pig.getMaxHealth());
        GenericInfection.turn(h, true);
        if (!GenericInfectionAiAccess.usesRam(h)) {
            helper.fail("馬が体当たりを使わない");
            return;
        }
        helper.succeedWhen(() -> {
            if (pig.getHealth() >= pig.getMaxHealth()) helper.fail("豚が傷を受けていない(馬の狙い " + h.getTarget() + ")");
        });
    }

    /**
     * ★ 問い 7 = 1(11-15 に戻した): Brain で動き、getTarget を Brain の狙いで上書きする種(ホグリン)は、ATTACK_TARGET を書かれて元の突き上げで豚を傷付ける。
     * オーバーワールドのホグリンは本来ゾグリンに変わるが、変わった個体は変身しない(★ 問い 14)。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 200)
    public static void turnedHoglinRamsPig(GameTestHelper helper) {
        Mob hoglin = helper.spawn(EntityType.HOGLIN, new BlockPos(1, 2, 1));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 5));
        GenericInfection.turn(hoglin, true);
        if (GenericInfectionAiAccess.usesRam(hoglin)) {
            helper.fail("ホグリン(Brain の Mob・元の攻撃を持つ)が汎用の近接だけになった(★ 問い 7 = 1 では元の突き上げで戦う)");
            return;
        }
        helper.succeedWhen(() -> {
            if (!hoglin.isAlive() || hoglin.isRemoved()) helper.fail("ホグリンが消えた(変身した?)");
            if (pig.getHealth() >= pig.getMaxHealth()) {
                helper.fail("豚が傷を受けていない(自前の相手 " + com.yayuy.overgrownvegetation.infect.GenericInfectionAi.targetOf(hoglin) + ")");
            }
        });
    }

    /** 変わった Mob と植物側(G1)は互いを狙わない(狙いの変更の知らせで止める)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void turnedAndPlantSideDoNotTargetEachOther(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob g1 = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new BlockPos(5, 2, 5));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 1));
        GenericInfection.turn(h, true);
        g1.setTarget(h);
        h.setTarget(g1);
        if (g1.getTarget() == h || h.getTarget() == g1) {
            helper.fail("植物側どうしで狙った: G1 → " + g1.getTarget() + "・馬 → " + h.getTarget());
            return;
        }
        h.setTarget(pig);
        if (h.getTarget() != pig) {
            helper.fail("植物側でない豚を狙えない");
            return;
        }
        helper.succeed();
    }

    /** ★ 問い 9・14: 飼い慣らした狼は飼い主を忘れる。変わったハスクはゾンビに変身しない。戻すと標的の Goal が元の数に戻る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void ownerConversionAndRevert(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Wolf wolf = helper.spawnWithNoFreeWill(EntityType.WOLF, new BlockPos(1, 2, 1));
        wolf.setTame(true);
        wolf.setOwnerUUID(java.util.UUID.randomUUID());
        int targetsBefore = wolf.targetSelector.getAvailableGoals().size();
        GenericInfection.turn(wolf, true);
        if (wolf.isTame() || wolf.getOwnerUUID() != null) {
            helper.fail("狼が飼い主を忘れていない");
            return;
        }
        Mob husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(5, 2, 5));
        GenericInfection.turn(husk, true);
        if (net.minecraftforge.event.ForgeEventFactory.canLivingConvert(husk, EntityType.ZOMBIE, t -> {
        })) {
            helper.fail("変わったハスクが変身できる");
            return;
        }
        GenericInfection.revert(wolf);
        int targetsAfter = wolf.targetSelector.getAvailableGoals().size();
        if (targetsAfter != targetsBefore) {
            helper.fail("戻した後の標的の Goal が " + targetsAfter + "(元 " + targetsBefore + ")");
            return;
        }
        helper.succeed();
    }

    /** ★ K6: 変わった Mob の鳴き声は加工した音に差し替わる(元の音は止める)。変わっていない Mob は今までどおり。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void turnedVoiceIsCrushed(GameTestHelper helper) {
        Horse plain = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 2, 5));
        GenericInfection.turn(h, true);
        int before = com.yayuy.overgrownvegetation.infect.GenericInfectionVoice.playedCount;
        plain.playAmbientSound(); // 1.20.1: makeSound は無い(G7 の mixin は playAmbientSound の中の playSound を見る)
        int afterPlain = com.yayuy.overgrownvegetation.infect.GenericInfectionVoice.playedCount;
        h.playAmbientSound(); // 1.20.1: makeSound は無い(G7 の mixin は playAmbientSound の中の playSound を見る)
        int afterTurned = com.yayuy.overgrownvegetation.infect.GenericInfectionVoice.playedCount;
        if (afterPlain != before || afterTurned != before + 1) {
            helper.fail("声の差し替えの数が違う: 変わっていない馬で +" + (afterPlain - before) + "・変わった馬で +" + (afterTurned - afterPlain));
            return;
        }
        helper.succeed();
    }

    /** ★ G7: 監視塔の呼び寄せに応じる(半径の中の変わった Mob が印の相手の居場所へ向かう)。変わっていない Mob は応じない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void answersWatchtowerCall(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Horse plain = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 1));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 5));
        GenericInfection.turn(h, true);
        int n = GenericInfection.call(helper.getLevel(), h.position(), pig, 1.0D, 200); // 隣のテストの変わった Mob を拾わないよう、馬の所だけ
        if (n != 1 || !GenericInfection.isCalled(h) || GenericInfection.isCalled(plain)) {
            helper.fail("呼び寄せに応じた数 " + n + "・変わった馬 " + GenericInfection.isCalled(h) + "・変わっていない馬 " + GenericInfection.isCalled(plain));
            return;
        }
        helper.succeed();
    }

    /**
     * 11-15 ユーザーの不具合「感染後の Mob が依然として G1、4〜 に敵対され続けている」: 変わる前から狙っていた植物側(G1・G3)の狙いが、
     * 変わった瞬間に外れる(G1 に殴られて感染 → 変わる、の流れでは G1 が狙いを握ったまま)。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void turningReleasesPlantSideTargets(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        Mob g1 = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new BlockPos(1, 2, 1));
        Mob falc = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get(), new BlockPos(5, 2, 5));
        Mob ven = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModRunners.VENAMNES.get(), new BlockPos(1, 2, 5));
        for (Mob m : new Mob[]{g1, falc, ven}) m.setTarget(h);
        if (g1.getTarget() != h || falc.getTarget() != h || ven.getTarget() != h) {
            helper.fail("変わる前の狙いが付かない(テストの前提)");
            return;
        }
        GenericInfection.turn(h, false);
        for (Mob m : new Mob[]{g1, falc, ven}) {
            if (m.getTarget() == h) {
                helper.fail(m.getType().getDescriptionId() + " が変わった馬を狙ったまま");
                return;
            }
            m.setTarget(h);
            if (m.getTarget() == h) {
                helper.fail(m.getType().getDescriptionId() + " が変わった馬を狙い直せた");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 植物側(G1 の近接・G1 の飛び道具・G2・G3 の代表)から変わった Mob へのダメージは入らない(狙いを通さない技・範囲攻撃も止める)。
     * 変わった Mob から植物側へも入らない。植物側でない豚へは入る。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void plantSideCannotHurtTurned(GameTestHelper helper) {
        var level = helper.getLevel();
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 1));
        Mob[] plant = {
                helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new BlockPos(1, 2, 1)),
                helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.SKELETON.entityType(), new BlockPos(1, 2, 3)),
                helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModCrawlers.STROBILA.get(), new BlockPos(1, 2, 5)),
                helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get(), new BlockPos(5, 2, 5)),
                helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModFlyers.ACUMNES.get(), new BlockPos(3, 2, 5)),
                helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.registry.ModRunners.VENAMNES.get(), new BlockPos(5, 2, 3))};
        GenericInfection.turn(h, true);
        float hp = h.getHealth();
        for (Mob m : plant) {
            if (!SporeExposureTracker.isPlantSide(m)) {
                helper.fail(m.getType().getDescriptionId() + " が植物側に数えられていない(テストの前提)");
                return;
            }
            h.invulnerableTime = 0;
            h.hurt(level.damageSources().mobAttack(m), 4.0F);
            net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(EntityType.ARROW, level);
            h.invulnerableTime = 0;
            h.hurt(level.damageSources().arrow(arrow, m), 4.0F);
            if (h.getHealth() < hp) {
                helper.fail(m.getType().getDescriptionId() + " のダメージが変わった馬に入った: " + h.getHealth() + "/" + hp);
                return;
            }
            float mhp = m.getHealth();
            m.invulnerableTime = 0;
            m.hurt(level.damageSources().mobAttack(h), 4.0F);
            if (m.getHealth() < mhp) {
                helper.fail("変わった馬のダメージが " + m.getType().getDescriptionId() + " に入った");
                return;
            }
        }
        float php = pig.getHealth();
        pig.hurt(level.damageSources().mobAttack(h), 2.0F);
        if (pig.getHealth() >= php) {
            helper.fail("変わった馬のダメージが豚に入らない");
            return;
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 攻撃できるか(11-15 ユーザーの不具合「敵対はするが攻撃できていない」)

    private static final String ATTACK_BATCH = "generic_infection_attack";

    /** {@code type} を変えて、4 ブロック先の豚(動かない)を狙わせ、{@code timeout} の間に豚の体力が減れば成功。 */
    private static void attacksPig(GameTestHelper helper, EntityType<? extends Mob> type) {
        attacksPig(helper, type, m -> {
        });
    }

    /** {@code setup} = 変える前の支度(自然に湧くときの武器を持たせるなど)。 */
    private static void attacksPig(GameTestHelper helper, EntityType<? extends Mob> type, java.util.function.Consumer<Mob> setup) {
        Mob attacker = helper.spawn(type, new BlockPos(1, 2, 1));
        setup.accept(attacker);
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 5));
        pig.setHealth(pig.getMaxHealth());
        if (!GenericInfection.turn(attacker, true)) {
            helper.fail(EntityType.getKey(type) + " が変わらない");
            return;
        }
        attacker.setTarget(pig);
        OvAttach.getData(attacker, ModAttachments.GENERIC_INFECTED).prey = pig;
        helper.succeedWhen(() -> {
            if (!attacker.isAlive()) helper.fail(EntityType.getKey(type) + " が死んだ");
            if (pig.getHealth() >= pig.getMaxHealth()) {
                helper.fail(EntityType.getKey(type) + " が豚を傷付けない(狙い " + com.yayuy.overgrownvegetation.infect.GenericInfectionAi.targetOf(attacker)
                        + "・体当たり " + GenericInfectionAiAccess.usesRam(attacker) + ")");
            }
        });
    }

    /** 元から近接を持つ種(狼)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackWolf(GameTestHelper helper) {
        attacksPig(helper, EntityType.WOLF);
    }

    /** 元から近接を持つ種(シロクマ)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackPolarBear(GameTestHelper helper) {
        attacksPig(helper, EntityType.POLAR_BEAR);
    }

    /** 元から近接を持つが怒らないと攻めない種(ハチ = NeutralMob)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackBee(GameTestHelper helper) {
        attacksPig(helper, EntityType.BEE);
    }

    /** 元から近接を持つ敵対の種(ヴィンディケーター)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackVindicator(GameTestHelper helper) {
        attacksPig(helper, EntityType.VINDICATOR);
    }

    /** 撃つ種(ストレイ = 弓)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackStray(GameTestHelper helper) {
        attacksPig(helper, EntityType.STRAY, m -> m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW)));
    }

    /** 撃つ種(ピリジャー = クロスボウ)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackPillager(GameTestHelper helper) {
        attacksPig(helper, EntityType.PILLAGER, m -> m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW)));
    }

    /** 予備の体当たり: 攻撃の属性はあるが武器を持たないピリジャー(元の攻撃が出ない)も、しばらくして体当たりで当てる。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 240)
    public static void attackUnarmedPillagerFallsBackToRam(GameTestHelper helper) {
        attacksPig(helper, EntityType.PILLAGER);
    }

    /**
     * 相手がスライム(11-15 ユーザーの不具合の場面)。変わった狼が大きさ 2 のスライム(体力 4)に当てる → 体力が減るか、倒されて変わる
     * (9ff1c2b から、変わった Mob に倒された Mob は死なずに満タンで変わる = 外からは「当たっていない」ように見える)。どちらになったかを記録する。
     */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackSlime(GameTestHelper helper) {
        Mob wolf = helper.spawn(EntityType.WOLF, new BlockPos(1, 2, 1));
        net.minecraft.world.entity.monster.Slime slime = helper.spawnWithNoFreeWill(EntityType.SLIME, new BlockPos(5, 2, 5));
        slime.setSize(2, true);
        GenericInfection.turn(wolf, true);
        wolf.setTarget(slime);
        helper.succeedWhen(() -> {
            boolean hurt = slime.getHealth() < slime.getMaxHealth();
            boolean turned = GenericInfection.isTurned(slime);
            if (!hurt && !turned) helper.fail("スライムに当たっていない(体力 " + slime.getHealth() + "/" + slime.getMaxHealth() + ")");
            if (!lastSlimeLogged) { lastSlimeLogged = true; OvergrownVegetation.LOGGER.info("GENINFECT_SLIME " + (turned ? "倒されて変わった(体力 " + slime.getHealth() + "/" + slime.getMaxHealth() + ")" : "体力が減った")); }
        });
    }

    /** 確かめ用: スライムの場面の結果(報告に書く)。 */
    private static volatile boolean lastSlimeLogged;

    /** 相手が村人(専用の G1 がある種)。変わった狼が村人の体力を減らす。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackVillager(GameTestHelper helper) {
        Mob wolf = helper.spawn(EntityType.WOLF, new BlockPos(1, 2, 1));
        Mob villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(5, 2, 5));
        GenericInfection.turn(wolf, true);
        wolf.setTarget(villager);
        helper.succeedWhen(() -> {
            if (villager.getHealth() >= villager.getMaxHealth() && villager.isAlive()) helper.fail("村人に当たっていない");
        });
    }

    /**
     * 体力の少ない相手(大きさ 1 のスライム = 体力 1)を変わった Mob が倒すと、死なずに満タンで変わり、植物側になる(9ff1c2b の連鎖)。
     * 倒した側の狙いは外れる(植物側どうしは狙わない)。
     */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void killedByTurnedTurnsToo(GameTestHelper helper) {
        Mob wolf = helper.spawn(EntityType.WOLF, new BlockPos(1, 2, 1));
        net.minecraft.world.entity.monster.Slime slime = helper.spawnWithNoFreeWill(EntityType.SLIME, new BlockPos(5, 2, 5));
        slime.setSize(1, true);
        GenericInfection.turn(wolf, true);
        wolf.setTarget(slime);
        helper.succeedWhen(() -> {
            if (!GenericInfection.isTurned(slime) || !slime.isAlive()) helper.fail("体力 1 のスライムが倒されて変わらない");
            if (GenericInfectionAiAccess.targetOf(wolf) == slime) helper.fail("変わったスライムを狼が狙ったまま");
        });
    }

    /**
     * 11-15 ユーザーの不具合「ほかの MOD の Mob が変わった後に襲いに行かない」: G1 に殴られて仕返しの Goal(HurtByTarget、優先度 1)が
     * 動いているまま胞子の死で変わった Mob も、豚を狙って傷付ける。以前は動いている標的の Goal を止めずに外していたので、外した Goal が
     * 狙いの旗を握ったまま残り、同じ優先度 1 の PreyGoal が二度と動けなかった(バニラ・ほかの MOD を問わず、殴られてから変わった Mob で起きる)。
     */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH)
    public static void runningTargetGoalDoesNotBlockPreyGoal(GameTestHelper helper) {
        Mob husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 2, 1));
        helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 3));
        // 変わる前に動いている標的の Goal(仕返しの Goal と同じ: 優先度 1・狙いの旗)を作る
        HoldTargetFlag holder = new HoldTargetFlag();
        husk.targetSelector.addGoal(1, holder);
        husk.targetSelector.tick();
        if (!holder.running) {
            helper.fail("テストの前提: 旗を握る Goal が動いていない");
            return;
        }
        GenericInfection.turn(husk, true);
        if (holder.running) {
            helper.fail("変わったときに、動いていた標的の Goal が止められていない(旗を握ったまま残る)");
            return;
        }
        boolean preyRan = false;
        for (int i = 0; i < 200 && !preyRan; i++) {
            husk.targetSelector.tick();
            preyRan = husk.targetSelector.getAvailableGoals().stream()
                    .anyMatch(w -> w.isRunning() && w.getGoal() instanceof com.yayuy.overgrownvegetation.infect.GenericInfectionAi.PreyGoal);
        }
        if (!preyRan) {
            helper.fail("変わった後に PreyGoal が動けない(旗が残っている)");
            return;
        }
        helper.succeed();
    }

    /** テスト用: いつでも動き、狙いの旗を握る Goal(止められたら running が false)。 */
    private static final class HoldTargetFlag extends net.minecraft.world.entity.ai.goal.Goal {
        boolean running;

        HoldTargetFlag() {
            setFlags(java.util.EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            return true;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    /**
     * ★ 11-15「ゾンビのように触れて攻撃・草を食べるしぐさも無くして、ただ移動して攻撃するだけ」: 変わった馬は、しぐさの Goal(見回す・後ろ足立ち・
     * 繁殖・誘い など)が外れ、残るのは止める・近接・浮く・うろつく だけ。草食み(馬の中の処理)も止まる。元の攻撃を持つ狼はしぐさも元のまま。
     */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH)
    public static void attacklessHorseLosesIdleBehaviour(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob wolf = helper.spawnWithNoFreeWill(EntityType.WOLF, new BlockPos(5, 2, 5));
        int wolfGoals = wolf.goalSelector.getAvailableGoals().size();
        GenericInfection.turn(h, true);
        GenericInfection.turn(wolf, true);
        for (var w : h.goalSelector.getAvailableGoals()) {
            String n = w.getGoal().getClass().getSimpleName();
            boolean ok = n.equals("FreezeGoal") || n.equals("MeleeGoal") || n.equals("AddedGoal")
                    || n.contains("Float") || n.contains("Stroll") || n.contains("Swim") || n.contains("Wander") || n.contains("Movement");
            if (!ok) {
                helper.fail("変わった馬にしぐさの Goal が残っている: " + n);
                return;
            }
        }
        h.setEating(true);
        helper.runAfterDelay(2, () -> {
            if (h.isEating()) {
                helper.fail("変わった馬が草を食べている");
                return;
            }
            if (wolf.goalSelector.getAvailableGoals().size() < wolfGoals) {
                helper.fail("元の攻撃を持つ狼のしぐさまで外れた: " + wolf.goalSelector.getAvailableGoals().size() + "(元 " + wolfGoals + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 攻撃の手段を持たない Brain の種(ラクダ): 振る舞いを外し、ゾンビのような近接で豚を傷付ける。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackCamel(GameTestHelper helper) {
        attacksPig(helper, EntityType.CAMEL);
    }

    /** 相手が村人(攻撃の手段を持たない馬が殴る)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void horseAttacksVillager(GameTestHelper helper) {
        Mob horse = helper.spawn(EntityType.HORSE, new BlockPos(1, 2, 1));
        Mob villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(5, 2, 5));
        GenericInfection.turn(horse, true);
        horse.setTarget(villager);
        helper.succeedWhen(() -> {
            if (villager.isAlive() && villager.getHealth() >= villager.getMaxHealth()) helper.fail("馬が村人に当たっていない");
        });
    }

    /**
     * 11-16 ユーザーの不具合「変わったスライムが攻撃のときだけ跳ねずに水平に滑って飛ぶ」: 変わった小さなスライム(元の攻撃はプレイヤーと
     * アイアンゴーレムにしか出ないので、豚へは予備の近接になる)は、元の跳ねで近づき(宙に浮いたまま長く進まない)、触れて豚を傷付ける。
     */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 400)
    public static void turnedSlimeHopsInsteadOfGliding(GameTestHelper helper) {
        net.minecraft.world.entity.monster.Slime slime = helper.spawn(EntityType.SLIME, new BlockPos(1, 2, 1));
        slime.setSize(1, true);
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 3)); // 近くに置く(小さなスライムの跳ねの間は乱数でばらつき、5,5 では 300 tick に届かない回があった。11-16)
        GenericInfection.turn(slime, true);
        slime.setTarget(pig);
        int[] air = {0, 0}; // 今続いている宙の tick・その最長
        int[] glide = {0}; // 宙で上下がほぼ止まったまま横へ進んだ tick
        helper.onEachTick(() -> {
            if (!slime.onGround()) {
                air[0]++;
                air[1] = Math.max(air[1], air[0]);
                var v = slime.getDeltaMovement();
                if (Math.abs(v.y) < 0.02D && v.horizontalDistance() > 0.05D) glide[0]++;
            } else {
                air[0] = 0;
            }
        });
        helper.succeedWhen(() -> {
            if (air[1] > 30 || glide[0] > 12) {
                helper.fail("スライムが跳ねずに滑っている: 宙の最長 " + air[1] + " tick・滑り " + glide[0] + " tick");
            }
            if (pig.isAlive() && pig.getHealth() >= pig.getMaxHealth()) helper.fail("スライムが豚を傷付けない(宙の最長 " + air[1] + "・滑り " + glide[0] + ")");
        });
    }

    /** 撃つ種(ラマ = 唾)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackLlama(GameTestHelper helper) {
        attacksPig(helper, EntityType.LLAMA);
    }

    /** 攻撃を持たない種(馬)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackHorse(GameTestHelper helper) {
        attacksPig(helper, EntityType.HORSE);
    }

    /** 攻撃を持たない種(ウサギ)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackRabbit(GameTestHelper helper) {
        attacksPig(helper, EntityType.RABBIT);
    }

    /** Brain で動く種(ヤギ)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackGoat(GameTestHelper helper) {
        attacksPig(helper, EntityType.GOAT);
    }

    /** Brain で動き、getTarget を Brain で上書きする種(ピグリン)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackPiglin(GameTestHelper helper) {
        attacksPig(helper, EntityType.PIGLIN, m -> m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_SWORD)));
    }

    /** 飛ぶ・攻撃を持たない種(コウモリ。歩けない)。 */
    @GameTest(template = "gametest_empty7", batch = ATTACK_BATCH, timeoutTicks = 200)
    public static void attackBat(GameTestHelper helper) {
        attacksPig(helper, EntityType.BAT);
    }

    // ------------------------------------------------------------------ 胞子のダメージに耐性を持つ Mob(11-15 ユーザー決定「耐性を無視して胞子で変える」)

    /** テスト用の印: この印を持つ Mob は胞子のダメージの種類を受け付けない(Cataclysm のコボレディエーター・ワジェットの代わり)。 */
    private static final String SPORE_IMMUNE_TAG = "ov_test_spore_immune";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
    public static final class SporeImmunity {
        @SubscribeEvent
        public static void onIncoming(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
            if (event.getEntity().getTags().contains(SPORE_IMMUNE_TAG)
                    && event.getSource().is(com.yayuy.overgrownvegetation.registry.ModDamageTypes.SPORE_INFECTION)) {
                event.setCanceled(true);
            }
        }

        private SporeImmunity() {
        }
    }

    /**
     * 胞子のダメージの種類を受け付けない Mob(体力 100 のラヴェジャー)も、暴露で変わる。弾かれた 20 ダメージを入ったことにして数え、
     * 体力に届いた回(5 回目)で変わる【仮】。4 回目では変わらない。体力は減らない(弾かれている)。完全な無敵(Invulnerable)の Mob は変わらない。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void sporeImmuneMobStillTurns(GameTestHelper helper) {
        if (helper.getLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            helper.fail("テストのワールドがピースフル");
            return;
        }
        Mob immune = helper.spawnWithNoFreeWill(EntityType.RAVAGER, new BlockPos(2, 2, 2));
        Mob invulnerable = helper.spawnWithNoFreeWill(EntityType.RAVAGER, new BlockPos(5, 2, 5));
        immune.addTag(SPORE_IMMUNE_TAG);
        invulnerable.setInvulnerable(true);
        float amount = (float) com.yayuy.overgrownvegetation.data.DesignData.sporeField().mobExposureDamage();
        int need = (int) Math.ceil(immune.getHealth() / amount);
        double dose = com.yayuy.overgrownvegetation.data.DesignData.sporeField().infectDose();
        for (int i = 1; i <= need; i++) {
            for (Mob m : new Mob[]{immune, invulnerable}) {
                SporeExposureTracker.setDose(m, dose, helper.getLevel().getGameTime());
                SporeExposureTracker.tickWith(helper.getLevel(), m, 40, 0.0D);
            }
            if (i < need && GenericInfection.isTurned(immune)) {
                helper.fail("耐性のある Mob が " + i + " 回目で変わった(期待 " + need + " 回目)");
                return;
            }
        }
        if (!GenericInfection.isTurned(immune)) {
            helper.fail("耐性のある Mob が " + need + " 回の暴露で変わらない(数え " + OvAttach.getData(immune, ModAttachments.GENERIC_INFECTED).blockedDamage + ")");
            return;
        }
        if (GenericInfection.isTurned(invulnerable)) {
            helper.fail("完全な無敵の Mob が変わった");
            return;
        }
        helper.succeed();
    }

    /** テストから体当たりの判定を読む口。 */
    private static final class GenericInfectionAiAccess {
        static LivingEntity targetOf(Mob m) {
            return com.yayuy.overgrownvegetation.infect.GenericInfectionAi.targetOf(m);
        }

        static boolean usesRam(Mob m) {
            return com.yayuy.overgrownvegetation.infect.GenericInfectionAi.usesRam(m);
        }
    }

    /** 印は保存される(エンティティの保存 → 読み直しで種・時刻・長さ・ボスが残る)。変わっていない個体は保存しない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void markSurvivesSaveAndLoad(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        Horse plain = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 2, 5));
        OvAttach.getData(plain, ModAttachments.GENERIC_INFECTED); // 添付だけ作る(turned = false)
        GenericInfection.turn(h, false);
        GenericInfected before = OvAttach.getData(h, ModAttachments.GENERIC_INFECTED);
        CompoundTag tag = h.saveWithoutId(new CompoundTag());
        Horse copy = EntityType.HORSE.create(helper.getLevel());
        if (copy == null) {
            helper.fail("馬を作れない");
            return;
        }
        copy.load(tag);
        GenericInfected after = OvAttach.getData(copy, ModAttachments.GENERIC_INFECTED);
        if (!after.turned || after.seed != before.seed || after.startTick != before.startTick
                || after.transformTicks != before.transformTicks || after.boss != before.boss) {
            helper.fail("読み直した印が違う: turned " + after.turned + " seed " + after.seed + "/" + before.seed);
            return;
        }
        if (GenericInfected.CODEC.encodeStart(JsonOps.INSTANCE, before).result().isEmpty()) {
            helper.fail("印を書き出せない");
            return;
        }
        CompoundTag plainTag = plain.saveWithoutId(new CompoundTag());
        if (plainTag.toString().contains("generic_infected")) {
            helper.fail("変わっていない馬に印が保存された");
            return;
        }
        helper.succeed();
    }

    /** 送る中身: 変わった個体は印のまま・戻した個体は「消す」。通信の書き出し → 読み込みで変わらない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void payloadCarriesTheMark(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        GenericInfection.turn(h, false);
        GenericInfectionPayload p = GenericInfection.payloadOf(h);
        GenericInfected g = OvAttach.getData(h, ModAttachments.GENERIC_INFECTED);
        if (!p.turned() || p.entityId() != h.getId() || p.seed() != g.seed || p.startTick() != g.startTick || p.transformTicks() != g.transformTicks) {
            helper.fail("中身が印と違う: " + p);
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        GenericInfectionPayload.STREAM_CODEC.encode(buf, p);
        GenericInfectionPayload back = GenericInfectionPayload.STREAM_CODEC.decode(buf);
        if (!back.equals(p)) {
            helper.fail("書き出し → 読み込みで変わった: " + back + " / " + p);
            return;
        }
        GenericInfection.revert(h);
        if (GenericInfection.payloadOf(h).turned()) {
            helper.fail("戻した後も印を送る");
            return;
        }
        helper.succeed();
    }

    /** ボス: タグ c:bosses の種(ウィザー)はボスとして変わる。馬はボスでない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void witherTurnsAsBoss(GameTestHelper helper) {
        LivingEntity wither = helper.spawnWithNoFreeWill(EntityType.WITHER, new BlockPos(3, 2, 3));
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(1, 2, 1));
        boolean ok = GenericInfection.turn(wither, true) && GenericInfection.turn(h, true);
        boolean witherBoss = OvAttach.getData(wither, ModAttachments.GENERIC_INFECTED).boss;
        boolean horseBoss = OvAttach.getData(h, ModAttachments.GENERIC_INFECTED).boss;
        wither.discard();
        if (!ok) {
            helper.fail("ウィザーか馬が変わらない");
            return;
        }
        if (!witherBoss || horseBoss) {
            helper.fail("ボスの印が違う: ウィザー " + witherBoss + "・馬 " + horseBoss);
            return;
        }
        helper.succeed();
    }
}
