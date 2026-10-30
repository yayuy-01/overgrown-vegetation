package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob;
import com.yayuy.overgrownvegetation.entity.BathymnesBody;
import com.yayuy.overgrownvegetation.entity.BathymnesEntity;
import com.yayuy.overgrownvegetation.entity.BathymnesPart;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * バティムネス(G5 の海の種、2026-10-01)のテスト。守りたいのは採用の案(ユーザーが採った能力)と、実装で約束したこと:
 * <ul>
 *     <li><b>ボートを一撃で壊し、乗り手を水へ落とす</b>(ram)。</li>
 *     <li><b>掴んで水中へ引きずり込む</b>(grab → drag で潜る)。ボートの乗り手も引き剥がす。殴れば振りほどける。</li>
 *     <li><b>陸に上げるとほぼ動けない</b>。</li>
 *     <li>当たり判定の部位はどこを殴っても本体の体力が減り、同じダメージ源は 1 回だけ(爆発が何倍にもならない)。</li>
 *     <li>深い水にだけ湧く(自然湧きの関門 {@link BathymnesBody#spawnHeight} を直接)。水中と陸で死に方が違う。</li>
 * </ul>
 * テスト場は 7×7(床は y=1、水は y=2〜)しかないので、動きを見るテストは体格を 1 に縮める(体格 2 は長さ 11 ブロック)。
 * 的はハスク(日光で燃えない)。AI を動かすテストは標的を選ぶ Goal を外す(隣のテスト場の Mob を狙わないように)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BathymnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BathymnesGameTests.class);
        }

        private Registration() {
        }
    }

    /** テスト場の中(x, z = 0〜6)を y の範囲だけ水で埋める。 */
    private static void fillWater(GameTestHelper helper, int yFrom, int yTo) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = yFrom; y <= yTo; y++) {
                    helper.setBlock(x, y, z, Blocks.WATER);
                }
            }
        }
    }

    /** 設計値の体格(mobs.json の scale)。 */
    private static double designScale() {
        MobStat stat = DesignData.mobs().forId("bathymnes");
        return stat.scale != null ? stat.scale : 1.0D;
    }

    /** 体格を 1 に縮めて置く(AI あり・標的を選ぶ Goal なし)。向きは {@code yaw}(絶対座標)。 */
    private static BathymnesEntity smallBathymnes(GameTestHelper helper, Vec3 relPos, float yaw, boolean noAi) {
        BathymnesEntity b = helper.spawn(ModEntities.BATHYMNES.get(), relPos);
        b.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        b.targetSelector.removeAllGoals(g -> true);
        b.goalSelector.removeAllGoals(g -> true);
        b.setNoAi(noAi);
        Vec3 abs = helper.absoluteVec(relPos);
        b.moveTo(abs.x, abs.y, abs.z, yaw, 0.0F);
        return b;
    }

    private static float yawFromTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** G5(適応種)・水生・体格は設計値。部位 5 つが本体の続きの id で世界に登録されている。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnsAsAquaticG5WithParts(GameTestHelper helper) {
        BathymnesEntity b = helper.spawnWithNoFreeWill(ModEntities.BATHYMNES.get(), new BlockPos(3, 2, 3));
        b.setNoAi(true);
        if (!Adaptation.isAdaptive(b.getType())) {
            helper.fail("group_5 に入っていない(適応が効かない)");
            return;
        }
        if (!(b instanceof AquaticOvergrownMob) || !b.getType().is(EntityTypeTags.CAN_BREATHE_UNDER_WATER)) {
            helper.fail("水生の印(AquaticOvergrownMob・#can_breathe_under_water)が無い(水ぐされ・水から上がる Goal が付く)");
            return;
        }
        if (Math.abs(b.getScale() - designScale()) > 1.0E-3D) {
            helper.fail("体格が設計値でない: " + b.getScale() + "(設計 " + designScale() + ")");
            return;
        }
        BathymnesPart[] parts = b.getBodyParts();
        if (!b.isMultipartEntity() || parts.length != BathymnesBody.PARTS.length) {
            helper.fail("部位が無い");
            return;
        }
        ServerLevel level = helper.getLevel();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].getId() != b.getId() + i + 1 || !level.getPartEntities().contains(parts[i])) {
                helper.fail("部位 " + i + " の id が本体の続きでない・世界に登録されていない");
                return;
            }
        }
        helper.succeed();
    }

    /** 浅い水(2 段)には、どの体格でも湧かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnCheckRejectsShallowWater(GameTestHelper helper) {
        fillWater(helper, 2, 3);
        BlockPos c = helper.absolutePos(new BlockPos(3, 2, 3));
        ServerLevel level = helper.getLevel();
        double atDesign = BathymnesBody.spawnHeight(level, c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, designScale());
        double atOne = BathymnesBody.spawnHeight(level, c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, 1.0D);
        if (!Double.isNaN(atDesign) || !Double.isNaN(atOne)) {
            helper.fail("深さ 2 の水に湧いてよいことになった(" + atDesign + " / " + atOne + ")");
            return;
        }
        // 湧かせ役が呼ぶ関門そのもの(世界には足さない)
        BathymnesEntity e = ModEntities.BATHYMNES.get().create(level);
        if (e == null) {
            helper.fail("作れない");
            return;
        }
        e.moveTo(c.getX() + 0.5D, c.getY() + 0.5D, c.getZ() + 0.5D, 0.0F, 0.0F);
        if (e.acceptOvergrownSpawn(level)) {
            helper.fail("湧きの関門(acceptOvergrownSpawn)が深さ 2 の水を通した");
            return;
        }
        helper.succeed();
    }

    /**
     * 深さ 5・テスト場いっぱいの水: 体格 1(要る深さ 4)なら湧いて、体は水の中(底から 1 マス上〜水面の下)に置かれる。
     * 体格 2(要る深さ 8)では湧かない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnCheckNeedsDepthForTheBody(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        BlockPos c = helper.absolutePos(new BlockPos(3, 4, 3));
        ServerLevel level = helper.getLevel();
        double feet = BathymnesBody.spawnHeight(level, c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, 1.0D);
        if (Double.isNaN(feet)) {
            helper.fail("体格 1 で深さ 5 の広い水に湧かない");
            return;
        }
        double floorTop = helper.absolutePos(new BlockPos(3, 2, 3)).getY();
        double surface = helper.absolutePos(new BlockPos(3, 7, 3)).getY();
        if (feet < floorTop + 1.0D - 1.0E-6D || feet + BathymnesBody.BASE_BOX > surface - 1.0D + 1.0E-6D) {
            helper.fail("置く高さが水の中でない: 足元 " + (feet - floorTop) + "(底からの高さ)");
            return;
        }
        // 湧かせ役が呼ぶ関門そのもの: 体格 1 の個体は通って、その高さへ置き直される(世界には足さない)
        BathymnesEntity e = ModEntities.BATHYMNES.get().create(level);
        if (e == null) {
            helper.fail("作れない");
            return;
        }
        e.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        e.moveTo(c.getX() + 0.5D, c.getY() + 0.5D, c.getZ() + 0.5D, 0.0F, 0.0F);
        if (!e.acceptOvergrownSpawn(level)) {
            helper.fail("湧きの関門(acceptOvergrownSpawn)が体格 1 で深さ 5 の広い水を通さない");
            return;
        }
        if (Math.abs(e.getY() - feet) > 1.0E-6D) {
            helper.fail("関門が置き直した高さが spawnHeight と違う: " + e.getY() + " / " + feet);
            return;
        }
        if (designScale() >= 1.5D) {
            double big = BathymnesBody.spawnHeight(level, c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, designScale());
            if (!Double.isNaN(big)) {
                helper.fail("体格 " + designScale() + " で深さ 5 の水に湧いてよいことになった");
                return;
            }
        }
        helper.succeed();
    }

    /** 深くても細い縦穴(1 マス)には湧かない(体の長さの半分の周りが水でない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnCheckRejectsNarrowShaft(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = 2; y <= 6; y++) {
                    helper.setBlock(x, y, z, x == 3 && z == 3 ? Blocks.WATER : Blocks.STONE);
                }
            }
        }
        BlockPos c = helper.absolutePos(new BlockPos(3, 4, 3));
        double feet = BathymnesBody.spawnHeight(helper.getLevel(), c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, 1.0D);
        if (!Double.isNaN(feet)) {
            helper.fail("1 マスの縦穴に湧いてよいことになった");
            return;
        }
        helper.succeed();
    }

    /**
     * ram: ボートに乗ったハスクの下へ位置を取って突き上げると、ボートが一撃で壊れ、乗り手は水へ落ちて削られる。
     * ボートが浮き上がって落ち着くまで待ってから、位置取りの式({@link BathymnesEntity#ramOriginFor})の点に置いて出す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void ramBreaksBoatAndDropsRider(GameTestHelper helper) {
        fillWater(helper, 2, 5);
        // ボートは水面(y=6)に置いて水へ落とす(落ちた瞬間に水面へ合わせて浮く)。水の中(y=5)に置くと箱の上まで水に浸かった「水没」の扱いで、
        // バニラのボートは浮かずに底まで沈み(2026-10-01 の GameTest でボートが床 y=2 に居た)、本体は床の下へ位置を取って陸に上がっていた
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(3.5D, 6.0D, 2.5D));
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 6, 2));
        husk.startRiding(boat, true);
        helper.runAfterDelay(20L, () -> {
            if (husk.getVehicle() != boat) {
                helper.fail("前提: ハスクがボートに乗っていない");
                return;
            }
            if (helper.relativeVec(boat.position()).y < 5.0D) {
                helper.fail("前提: ボートが水面に浮いていない(高さ " + helper.relativeVec(boat.position()).y + ")");
                return;
            }
            // 体は +Z 向き(相手は後ろ斜め上)。向きは絶対座標で決める(テスト場は回ることがある)。
            Vec3 boatPos = boat.position();
            Vec3 away = helper.absoluteVec(new Vec3(3.5D, 3.0D, 5.5D));
            float yaw = yawFromTo(boatPos, away);
            BathymnesEntity b = smallBathymnes(helper, helper.relativeVec(boatPos.add(0.0D, -3.0D, 0.0D)), yaw, true);
            Vec3 o = b.ramOriginFor(husk, yaw);
            b.moveTo(o.x, o.y, o.z, yaw, 0.0F);
            float before = husk.getHealth();
            b.forceRam(husk);
            helper.runAfterDelay(BathymnesEntity.RAM_HIT_TICK + 4L, () -> {
                if (boat.isAlive()) {
                    double sc = b.getScale();
                    Vec3 a = b.position().add(BathymnesBody.ramHitStart(b.getYRot(), sc));
                    Vec3 e = b.position().add(BathymnesBody.ramHitEnd(b.getYRot(), sc));
                    Vec3 bc = boat.position().add(0.0D, boat.getBbHeight() * 0.5D, 0.0D);
                    helper.fail("ボートが壊れていない(ボートの削れ " + boat.getDamage() + "、動作 " + b.getAction() + "・陸 " + b.isBeached()
                            + "・本体 " + helper.relativeVec(b.position()) + "(置いた所 " + helper.relativeVec(o) + ")・ボート " + helper.relativeVec(boat.position())
                            + "・線分までの距離 " + String.format("%.2f", BathymnesBody.segmentDistance(bc, a, e))
                            + "(当たり " + String.format("%.2f", BathymnesBody.ramHitRadius(sc) + boat.getBbWidth() * 0.5D) + ")・体格 " + sc + ")");
                    return;
                }
                if (husk.getVehicle() != null) {
                    helper.fail("乗り手がまだ乗っている");
                    return;
                }
                if (husk.getHealth() >= before - 1.0F) {
                    helper.fail("乗り手に当たっていない(体力 " + husk.getHealth() + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** grab → drag: 掴んだ相手を乗せたまま潜る(足元が下がる)。放さない(時間切れより前)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void grabDragsPreyDown(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 4, 5));
        husk.setInvulnerable(true);
        Vec3 rel = new Vec3(3.5D, 4.6D, 1.5D);
        float yaw = yawFromTo(helper.absoluteVec(rel), husk.position());
        BathymnesEntity b = smallBathymnes(helper, rel, yaw, false);
        // 隣のテスト場の射程の長い設備に撃たれても振りほどきの量が溜まらないように(見たいのは潜ることだけ)
        b.setInvulnerable(true);
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        double y0 = b.getY();
        helper.runAfterDelay(70L, () -> {
            if (husk.getVehicle() != b) {
                helper.fail("引き込みの途中で放した(動作 " + b.getAction() + ")");
                return;
            }
            if (b.getAction() != BathymnesEntity.ACT_DRAG) {
                helper.fail("drag が続いていない(動作 " + b.getAction() + ")");
                return;
            }
            if (b.getY() > y0 - 0.8D) {
                helper.fail("潜っていない(下がった量 " + (y0 - b.getY()) + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * grab はボートの乗り手をボートから引き剥がす(ボートは残る)。ボートは ram のテストと同じく水面(相対 y=6.0)に置いて浮かせる
     * (水の中に置くと「水没」の扱いで浮かず、3 秒で乗り手を勝手に降ろす。2026-10-01 点検で揃えた)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void grabPullsRiderOutOfBoat(GameTestHelper helper) {
        fillWater(helper, 2, 5);
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(3.5D, 6.0D, 4.5D));
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 6, 4));
        husk.setInvulnerable(true);
        husk.startRiding(boat, true);
        helper.runAfterDelay(20L, () -> {
            if (husk.getVehicle() != boat || helper.relativeVec(boat.position()).y < 5.0D) {
                helper.fail("前提: ハスクがボートに乗って水面に浮いていない(乗り物 " + husk.getVehicle()
                        + "・高さ " + helper.relativeVec(boat.position()).y + ")");
                return;
            }
            BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 2.5D, 1.5D), 0.0F, true);
            if (!b.forceGrab(husk)) {
                helper.fail("ボートの乗り手を掴めない");
                return;
            }
            if (husk.getVehicle() != b) {
                helper.fail("乗り手がバティムネスに乗っていない(乗り物 " + husk.getVehicle() + ")");
                return;
            }
            if (!boat.isAlive() || !boat.getPassengers().isEmpty()) {
                helper.fail("ボートが消えた・まだ誰か乗っている");
                return;
            }
            helper.succeed();
        });
    }

    /** 掴まれている間に {@link BathymnesEntity#BREAK_FREE_DAMAGE} 以上削れば放す。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void shakeOffReleasesPrey(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        husk.setInvulnerable(true);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        // 防具(16)と適応(最大 30%)を引いても 25 を超える量
        float hit = 80.0F;
        // 振りほどき 25 は体力 160 のときに決めた量。設計値の体力は 10-12 から 20 で、殴ると放す前に倒れる(倒れて放す)ので、
        // このテストだけ体力を足して振りほどきの仕組みを見る。
        b.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hit * 2.0F);
        b.setHealth(b.getMaxHealth());
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (husk.getVehicle() != b) {
                helper.fail("前提: 掴んでいない");
                return;
            }
            b.hurt(helper.getLevel().damageSources().mobAttack(husk), hit);
            if (husk.getVehicle() == b || b.isHoldingPrey()) {
                helper.fail("殴っても放さない(削れた体力 " + (b.getMaxHealth() - b.getHealth()) + ")");
                return;
            }
            if (b.getAction() != BathymnesEntity.ACT_NONE) {
                helper.fail("放した後の動作が終わっていない(動作 " + b.getAction() + ")");
                return;
            }
            // 放した所は抱えていた所(嘴の前)。バニラの既定(本体の箱の上の真ん中)へ跳んでいない。
            double ahead = husk.position().subtract(b.position()).dot(BathymnesBody.forward(b.getYRot()));
            if (ahead < 1.0D) {
                helper.fail("放した獲物が嘴の前でなく本体の上へ跳んだ(前へ " + ahead + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 掴む動作の外で乗っているもの(保存から戻った獲物など —— 動作は保存しない)は次の tick に降ろす。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void strayRiderIsDropped(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        if (!husk.startRiding(b, true)) {
            helper.fail("前提: 乗せられない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (husk.getVehicle() == b) {
                helper.fail("掴んでいないのに乗せたまま");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 掴んでいる途中の保存: チャンクの保存と同じく乗客ごと NBT に書き、元の個体を消して読み直す。
     * 読み直した個体は動作を持たない(保存しない)ので、次の tick に獲物を降ろし、降りた獲物は普通に降りられる。
     * 冷却(保存する値)は戻る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void savedMidGrabLoadsReleased(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        husk.setInvulnerable(true);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (!b.isHoldingPrey()) {
                helper.fail("前提: 掴んでいない(動作 " + b.getAction() + ")");
                return;
            }
            ServerLevel level = helper.getLevel();
            CompoundTag tag = new CompoundTag();
            if (!b.saveAsPassenger(tag) || !tag.contains("Passengers", Tag.TAG_LIST)) {
                helper.fail("乗客ごと保存できない");
                return;
            }
            b.discard();
            husk.discard();
            Entity loaded = EntityType.loadEntityRecursive(tag, level, e -> {
                e.setUUID(Mth.createInsecureUUID(level.getRandom()));
                return e;
            });
            if (!(loaded instanceof BathymnesEntity b2) || !(b2.getFirstPassenger() instanceof Husk h2)) {
                helper.fail("読み直したバティムネスに獲物が乗っていない: " + loaded);
                return;
            }
            if (!level.tryAddFreshEntityWithPassengers(b2)) {
                helper.fail("読み直した個体を世界に足せない");
                return;
            }
            helper.runAfterDelay(3L, () -> {
                if (h2.getVehicle() == b2 || b2.isVehicle()) {
                    helper.fail("読み直した後も獲物を乗せたまま(動作 " + b2.getAction() + ")");
                    return;
                }
                if (b2.getAction() != BathymnesEntity.ACT_NONE) {
                    helper.fail("読み直した個体が動作を持っている: " + b2.getAction());
                    return;
                }
                if (!b2.isAlive() || !h2.isAlive()) {
                    helper.fail("読み直した個体・獲物が消えた");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * 水の中の相手なら、敵対 Mob でなくても狙う(イカ・牛)。過繁茂側(過繁茂した牛)・防具立ては狙わない・当てない
     * (2026-10-01 夜、ユーザー「バティムネスが他のmobを攻撃しない」。前はプレイヤーと敵対 Mob だけだった)。
     * 実際に索敵させて、近くのイカか牛を標的に選ぶことも見る(動く Goal は外す)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void targetsAnyNonOvergrownMobInWater(GameTestHelper helper) {
        fillWater(helper, 1, 5);
        BathymnesEntity b = helper.spawn(ModEntities.BATHYMNES.get(), new Vec3(1.5D, 2.0D, 1.5D));
        b.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        b.goalSelector.removeAllGoals(g -> true);
        Squid squid = helper.spawnWithNoFreeWill(EntityType.SQUID, new BlockPos(4, 3, 4));
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 3, 1));
        Mob overgrown = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_COW.get(), new BlockPos(1, 3, 4));
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(3, 2, 5));
        // イカ・牛は無敵にしない(バニラは無敵の相手を標的に選ばない。動く Goal を外してあるので、このテストでは殴られない)
        overgrown.setInvulnerable(true);
        if (!BathymnesEntity.isPrey(b, squid) || !BathymnesEntity.isPrey(b, cow)) {
            helper.fail("敵対でない Mob(イカ・牛)を狙う相手に数えない");
            return;
        }
        if (BathymnesEntity.isPrey(b, overgrown) || b.isFoe(overgrown)) {
            helper.fail("過繁茂した牛(過繁茂側)を狙う相手に数えた");
            return;
        }
        if (BathymnesEntity.isPrey(b, stand) || b.isFoe(stand)) {
            helper.fail("防具立てを狙う相手・当てる相手に数えた");
            return;
        }
        helper.succeedWhen(() -> {
            if (b.getTarget() != squid && b.getTarget() != cow) {
                helper.fail("水の中のイカ・牛を標的に選ばない(標的 " + b.getTarget() + ")");
            }
        });
    }

    /**
     * 深い所を漂っていても、真上の水面の相手が索敵の箱に入る(バニラの索敵は上下 4 ブロックしか見ない)。
     * テスト場は高さ 7 しかないので、実際に狙わせる代わりに Goal の箱の大きさを見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void targetSearchReachesUpAndDown(GameTestHelper helper) {
        BathymnesEntity b = helper.spawnWithNoFreeWill(ModEntities.BATHYMNES.get(), new BlockPos(3, 2, 3));
        b.setNoAi(true);
        AABB box = b.targetSearchArea();
        if (box == null) {
            helper.fail("水の相手の索敵 Goal が無い");
            return;
        }
        // 水平の半径(追跡の距離 + 胞子感染の被検知ボーナス)と同じだけ上下にも広がっている。バニラの 4 よりずっと広い。
        double horiz = (box.getXsize() - b.getBbWidth()) * 0.5D;
        double up = box.maxY - b.getBoundingBox().maxY;
        double down = b.getBoundingBox().minY - box.minY;
        if (up < horiz - 1.0E-6D || down < horiz - 1.0E-6D || up < 16.0D) {
            helper.fail("索敵の箱の上下が狭い: 上 " + up + " / 下 " + down + "(水平 " + horiz + ")");
            return;
        }
        helper.succeed();
    }

    /**
     * 陸(水の無いテスト場)では beached になり、標的が近くに居ても這う速さ({@link BathymnesEntity#CRAWL_SPEED})より速くは動かない。
     * 体格は設計値のまま。泳ぐ・追う Goal は付けたまま(陸では動かないことを見る)。
     *
     * <p>「全く動かない」とは見ない(2026-10-01 点検): 水を探す半径 10 には<b>隣のテスト場</b>(列はおよそ 12・行は 13 ブロックおき)の
     * 端のマスが入るので、隣が水を張るテストだと、そちらを向いて這い出す(真南の隣なら 5 秒で 2.1 ブロック)。並びの順で揺れないように、
     * 這う速さの上限(+ 余裕 0.3)で見る。泳ぐ・追う速さ(毎 tick 0.1〜0.4)ならこの何倍も動く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140)
    public static void beachedOnLandBarelyMoves(GameTestHelper helper) {
        BathymnesEntity b = helper.spawn(ModEntities.BATHYMNES.get(), new BlockPos(3, 2, 3));
        b.targetSelector.removeAllGoals(g -> true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 6));
        husk.setInvulnerable(true);
        b.setTarget(husk);
        helper.runAfterDelay(20L, () -> {
            Vec3 p0 = b.position();
            if (!b.isBeached()) {
                helper.fail("陸で beached にならない");
                return;
            }
            helper.runAfterDelay(100L, () -> {
                double moved = b.position().distanceTo(p0);
                double limit = BathymnesEntity.CRAWL_SPEED * b.getScale() / 2.0D * 100.0D + 0.3D;
                if (moved > limit) {
                    helper.fail("陸で這う速さより速く動いている(5 秒で " + moved + " ブロック、上限 " + limit + ")");
                    return;
                }
                if (!b.isBeached()) {
                    helper.fail("陸のまま beached が解けた");
                    return;
                }
                if (b.getAction() != BathymnesEntity.ACT_NONE) {
                    helper.fail("陸で攻撃の動作を始めた(動作 " + b.getAction() + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** どの部位を殴っても本体の体力が減る。同じ tick の同じダメージ源は 2 つめの部位では入らない(爆発が何倍にもならない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void partHitDamagesBodyOncePerSource(GameTestHelper helper) {
        BathymnesEntity b = helper.spawnWithNoFreeWill(ModEntities.BATHYMNES.get(), new BlockPos(3, 2, 3));
        b.setNoAi(true);
        helper.runAfterDelay(2L, () -> {
            BathymnesPart[] parts = b.getBodyParts();
            Vec3 f = BathymnesBody.forward(b.getYRot());
            double head = parts[0].position().subtract(b.position()).dot(f);
            double tip = parts[parts.length - 1].position().subtract(b.position()).dot(f);
            if (head < 1.0D || tip > -2.0D) {
                helper.fail("部位が体の前後に並んでいない(頭 " + head + " / 切っ先 " + tip + ")");
                return;
            }
            // 2 回目は大きくする: バニラの無敵時間だけなら「前より大きい分」が入ってしまうので、まとめる処理が無いと落ちる。
            DamageSource src = helper.getLevel().damageSources().generic();
            float h0 = b.getHealth();
            boolean first = parts[0].hurt(src, 10.0F);
            float h1 = b.getHealth();
            boolean second = parts[3].hurt(src, 20.0F);
            float h2 = b.getHealth();
            if (!first || h1 >= h0) {
                helper.fail("頭の部位を殴っても本体が減らない");
                return;
            }
            if (second || h2 != h1) {
                helper.fail("同じダメージ源が 2 つの部位で 2 回入った");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 湧きの関門は体格込みの大きさにしてから世界へ渡す(2026-10-01 点検)。素の大きさ(1.25)のまま渡すと、最初の tick に育つとき
     * バニラの fudgePositionAfterSizeChange が箱の中心を保とうとして足元を下げ、関門が決めた高さからずれる(体格 2 で 0.625)。
     * テスト場の深さ 5 で関門を通る体格 1.2 で見る(体格 1 は素の大きさと同じで差が出ない)。世界には足さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnCheckLeavesBodyFullSize(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        BlockPos c = helper.absolutePos(new BlockPos(3, 4, 3));
        ServerLevel level = helper.getLevel();
        BathymnesEntity e = ModEntities.BATHYMNES.get().create(level);
        if (e == null) {
            helper.fail("作れない");
            return;
        }
        double scale = 1.2D;
        e.getAttribute(Attributes.SCALE).setBaseValue(scale);
        e.moveTo(c.getX() + 0.5D, c.getY() + 0.5D, c.getZ() + 0.5D, 0.0F, 0.0F);
        if (!e.acceptOvergrownSpawn(level)) {
            helper.fail("前提: 体格 1.2 で深さ 5 の広い水の関門を通らない");
            return;
        }
        double want = BathymnesBody.BASE_BOX * scale;
        if (Math.abs(e.getBbWidth() - want) > 1.0E-3D || Math.abs(e.getBbHeight() - want) > 1.0E-3D) {
            helper.fail("関門を通った体が素の大きさのまま: " + e.getBbWidth() + " x " + e.getBbHeight() + "(体格込み " + want + ")");
            return;
        }
        helper.succeed();
    }

    /**
     * 掴まれている獲物は別の乗り物に乗れない(2026-10-01 点検)。バニラの {@code startRiding} は、今の乗り物から降りる処理が
     * 止められても先へ進んで新しい乗り物にも載せてしまう —— 両方の乗客の表に残り、放しても本体の表から消えなかった。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void heldPreyCannotBoardAnotherVehicle(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        husk.setInvulnerable(true);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(1.5D, 6.0D, 5.5D));
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (husk.startRiding(boat, true)) {
                helper.fail("掴まれている獲物がボートに乗れた");
                return;
            }
            if (husk.getVehicle() != b || !boat.getPassengers().isEmpty()) {
                helper.fail("獲物の乗り物が変わった(乗り物 " + husk.getVehicle() + "・ボートの乗客 " + boat.getPassengers().size() + ")");
                return;
            }
            // 振りほどけば本体の表からも消える(防具と適応を引いても 25 を超える量)
            b.hurt(helper.getLevel().damageSources().mobAttack(husk), 80.0F);
            if (b.isVehicle() || husk.getVehicle() != null) {
                helper.fail("放した後も乗客の表に残っている(本体の乗客 " + b.getPassengers().size() + "・獲物の乗り物 " + husk.getVehicle() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * ログアウトの手当て({@link BathymnesEntity.Events#releaseHeldRider}、2026-10-01 点検): 掴んでいる獲物をその場で放し、本体は世界に残る。
     * 放さないと、バニラはログアウトしたプレイヤーの乗り物(= バティムネス)ごとプレイヤーのデータへ保存して世界から消す。
     * 本物のログアウトは GameTest で起こせないので、イベントから呼ぶ関数をハスクで直接見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void logoutHandlerReleasesHeldPrey(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        husk.setInvulnerable(true);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (!BathymnesEntity.Events.releaseHeldRider(husk)) {
                helper.fail("掴まれている獲物を放さない");
                return;
            }
            if (husk.getVehicle() != null || b.isVehicle() || b.getAction() != BathymnesEntity.ACT_NONE) {
                helper.fail("放した後の状態が違う(獲物の乗り物 " + husk.getVehicle() + "・動作 " + b.getAction() + ")");
                return;
            }
            if (!b.isAlive() || b.isRemoved()) {
                helper.fail("本体が世界から消えた");
                return;
            }
            if (BathymnesEntity.Events.releaseHeldRider(husk)) {
                helper.fail("掴まれていない相手で放す扱いになった");
                return;
            }
            helper.succeed();
        });
    }

    /** drag は締め 5 回(時間切れ {@link BathymnesEntity#DRAG_MAX_TICKS})で放す。それより前には放さない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = BathymnesEntity.DRAG_MAX_TICKS + 40)
    public static void dragReleasesAtTimeLimit(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        husk.setInvulnerable(true);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        // 隣のテスト場の設備に撃たれて振りほどきの量が溜まらないように(見たいのは時間切れだけ)
        b.setInvulnerable(true);
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(BathymnesEntity.DRAG_MAX_TICKS - 20L, () -> {
            if (husk.getVehicle() != b) {
                helper.fail("時間切れより前に放した(動作 " + b.getAction() + ")");
                return;
            }
            helper.runAfterDelay(25L, () -> {
                if (husk.getVehicle() == b || b.isVehicle()) {
                    helper.fail("時間切れでも放さない(動作 " + b.getAction() + ")");
                    return;
                }
                if (b.getAction() != BathymnesEntity.ACT_NONE) {
                    helper.fail("放した後の動作が終わっていない(動作 " + b.getAction() + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** 掴んだまま水が無くなる(陸に上がる)と放す。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void beachingReleasesPrey(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 3, 5));
        husk.setInvulnerable(true);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 3.5D, 1.5D), 0.0F, true);
        b.setInvulnerable(true);
        if (!b.forceGrab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (husk.getVehicle() != b) {
                helper.fail("前提: 掴んでいない");
                return;
            }
            // 水を抜く(AI なしなので本体は宙に止まったまま。陸に上がった判定は水の深さだけで見る)
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    for (int y = 2; y <= 6; y++) {
                        helper.setBlock(x, y, z, Blocks.AIR);
                    }
                }
            }
            helper.runAfterDelay(BathymnesEntity.BEACH_ENTER_TICKS + 10L, () -> {
                if (!b.isBeached()) {
                    helper.fail("水が無くなっても beached にならない");
                    return;
                }
                if (husk.getVehicle() == b || b.isVehicle() || b.getAction() != BathymnesEntity.ACT_NONE) {
                    helper.fail("陸に上がっても放さない(動作 " + b.getAction() + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** 水中で死ぬと death(海底まで沈む)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void diesInWaterAndSinks(GameTestHelper helper) {
        fillWater(helper, 2, 6);
        BathymnesEntity b = smallBathymnes(helper, new Vec3(3.5D, 5.2D, 3.5D), 0.0F, false);
        helper.runAfterDelay(3L, () -> {
            double y0 = b.getY();
            b.hurt(helper.getLevel().damageSources().genericKill(), 10000.0F);
            if (!b.isDeadOrDying() || b.getAction() != BathymnesEntity.ACT_DEATH) {
                helper.fail("水中の死に方が death でない(動作 " + b.getAction() + ")");
                return;
            }
            helper.runAfterDelay(50L, () -> {
                if (b.getY() > y0 - 1.0D) {
                    helper.fail("死体が沈まない(下がった量 " + (y0 - b.getY()) + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** 陸で死ぬと death_beached。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void diesOnLandBeached(GameTestHelper helper) {
        BathymnesEntity b = helper.spawn(ModEntities.BATHYMNES.get(), new BlockPos(3, 2, 3));
        b.targetSelector.removeAllGoals(g -> true);
        helper.runAfterDelay(20L, () -> {
            if (!b.isBeached()) {
                helper.fail("前提: 陸で beached にならない");
                return;
            }
            b.hurt(helper.getLevel().damageSources().genericKill(), 10000.0F);
            if (b.getAction() != BathymnesEntity.ACT_DEATH_BEACHED) {
                helper.fail("陸の死に方が death_beached でない(動作 " + b.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 体の点の式: 鼻先を下げる(ピッチ +)と頭が下がり殻の後ろが上がる。向き 90°(西)で前が −X。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bodyPointsFollowYawAndPitch(GameTestHelper helper) {
        double[] headPart = BathymnesBody.PARTS[0];
        double[] tipPart = BathymnesBody.PARTS[BathymnesBody.PARTS.length - 1];
        Vec3 headLevel = BathymnesBody.toWorld(headPart[0], headPart[1], headPart[2], 0.0F, 0.0F, 2.0D);
        Vec3 headDown = BathymnesBody.toWorld(headPart[0], headPart[1], headPart[2], 0.0F, 30.0F, 2.0D);
        Vec3 tipDown = BathymnesBody.toWorld(tipPart[0], tipPart[1], tipPart[2], 0.0F, 30.0F, 2.0D);
        Vec3 tipLevel = BathymnesBody.toWorld(tipPart[0], tipPart[1], tipPart[2], 0.0F, 0.0F, 2.0D);
        if (!(headDown.y < headLevel.y - 0.5D) || !(tipDown.y > tipLevel.y + 0.5D)) {
            helper.fail("ピッチ + で頭が下がらない・殻の後ろが上がらない");
            return;
        }
        if (!(headLevel.z > 1.5D)) {
            helper.fail("向き 0(南)で頭が +Z に無い: " + headLevel);
            return;
        }
        Vec3 west = BathymnesBody.toWorld(headPart[0], headPart[1], headPart[2], 90.0F, 0.0F, 2.0D);
        if (!(west.x < -1.5D) || Math.abs(west.z) > 1.0E-6D) {
            helper.fail("向き 90 で頭が −X に無い: " + west);
            return;
        }
        helper.succeed();
    }
}
