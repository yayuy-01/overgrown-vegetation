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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
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
        GenericInfected g = h.getData(ModAttachments.GENERIC_INFECTED);
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
     * ★ 問い 7: Brain で動き、getTarget を Brain の狙いで上書きする種(ホグリン)も、体当たりで豚を傷付ける(自前の相手を見る)。
     * オーバーワールドのホグリンは本来ゾグリンに変わるが、変わった個体は変身しない(★ 問い 14)。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 200)
    public static void turnedHoglinRamsPig(GameTestHelper helper) {
        Mob hoglin = helper.spawn(EntityType.HOGLIN, new BlockPos(1, 2, 1));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 5));
        GenericInfection.turn(hoglin, true);
        if (!GenericInfectionAiAccess.usesRam(hoglin)) {
            helper.fail("ホグリン(Brain の Mob)が体当たりを使わない");
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
        wolf.setTame(true, false);
        wolf.setOwnerUUID(java.util.UUID.randomUUID());
        int targetsBefore = wolf.targetSelector.getAvailableGoals().size();
        GenericInfection.turn(wolf, true);
        if (wolf.isTame() || wolf.getOwnerUUID() != null) {
            helper.fail("狼が飼い主を忘れていない");
            return;
        }
        Mob husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(5, 2, 5));
        GenericInfection.turn(husk, true);
        if (net.neoforged.neoforge.event.EventHooks.canLivingConvert(husk, EntityType.ZOMBIE, t -> {
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
        plain.makeSound(net.minecraft.sounds.SoundEvents.HORSE_AMBIENT);
        int afterPlain = com.yayuy.overgrownvegetation.infect.GenericInfectionVoice.playedCount;
        h.makeSound(net.minecraft.sounds.SoundEvents.HORSE_AMBIENT);
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

    /** テストから体当たりの判定を読む口。 */
    private static final class GenericInfectionAiAccess {
        static boolean usesRam(Mob m) {
            return com.yayuy.overgrownvegetation.infect.GenericInfectionAi.usesRam(m);
        }
    }

    /** 印は保存される(エンティティの保存 → 読み直しで種・時刻・長さ・ボスが残る)。変わっていない個体は保存しない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void markSurvivesSaveAndLoad(GameTestHelper helper) {
        Horse h = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(3, 2, 3));
        Horse plain = helper.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 2, 5));
        plain.getData(ModAttachments.GENERIC_INFECTED); // 添付だけ作る(turned = false)
        GenericInfection.turn(h, false);
        GenericInfected before = h.getData(ModAttachments.GENERIC_INFECTED);
        CompoundTag tag = h.saveWithoutId(new CompoundTag());
        Horse copy = EntityType.HORSE.create(helper.getLevel());
        if (copy == null) {
            helper.fail("馬を作れない");
            return;
        }
        copy.load(tag);
        GenericInfected after = copy.getData(ModAttachments.GENERIC_INFECTED);
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
        GenericInfected g = h.getData(ModAttachments.GENERIC_INFECTED);
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
        boolean witherBoss = wither.getData(ModAttachments.GENERIC_INFECTED).boss;
        boolean horseBoss = h.getData(ModAttachments.GENERIC_INFECTED).boss;
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
