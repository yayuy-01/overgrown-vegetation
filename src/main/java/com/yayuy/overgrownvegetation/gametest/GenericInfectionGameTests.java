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

    /** 専用の G1 がある種(大人の牛)・表の種の子ども・除外の種(ウォーデン)・植物側は変わらない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void listedSpeciesAndExcludedDoNotTurn(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1, 2, 1));
        Mob calf = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 1));
        calf.setBaby(true);
        Mob warden = helper.spawnWithNoFreeWill(EntityType.WARDEN, new BlockPos(3, 2, 5));
        Mob g1 = helper.spawnWithNoFreeWill(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType(), new BlockPos(1, 2, 5));
        for (Mob m : new Mob[]{cow, calf, warden, g1}) {
            if (GenericInfection.turn(m, true)) {
                helper.fail(m.getType().getDescriptionId() + (m.isBaby() ? "(子ども)" : "") + " が変わった");
                return;
            }
        }
        helper.succeed();
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
