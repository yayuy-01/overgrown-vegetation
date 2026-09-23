package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.adaptation.AdaptationData;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ファルキムネス(グループ5)のテスト(2026-09-23)。
 *
 * <p>AI は止めて({@code spawnWithNoFreeWill})、動作は {@link FalcimnesEntity#forceAction} /
 * {@link FalcimnesEntity#grab} で直接始める。動作は {@code aiStep} で進むので AI が無くても進む。
 * 守りたいのは、ユーザーが数字で指定したところ:
 * <ul>
 *     <li>横薙ぎは<b>鎌が正面に出た瞬間</b>に当たる(振りかぶっている間は当たらない)。前方の扇だけ。</li>
 *     <li>噛みつきは 8 で<b>防具を貫く</b>。最後は<b>即死</b>。掴まれた側は自分から降りられない。</li>
 *     <li>掴まれている間に <b>15</b> 削れば離す。</li>
 *     <li>適応値 <b>15%</b> 以上の攻撃はかわす(30 秒に 1 回)。かわせなかった分は適応値ぶん減る。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FalcimnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FalcimnesGameTests.class);
        }

        private Registration() {
        }
    }

    /** +Z を向けて置く(テスト場の奥へ向かって前)。 */
    private static FalcimnesEntity place(GameTestHelper helper) {
        return placeAt(helper, 1);
    }

    /**
     * 掴みのテスト用。獲物は本体の 50/16 × 体格 ブロック前・30/16 × 体格 ブロック上へ持ち上がるので、本来の体格 1.8 だと
     * 7×7×7 のテスト場では獲物の目が外周のバリアに入り、窒息ダメージで噛みつきの体力の勘定が狂う(2026-09-23 に確認)。
     * ここで見たいのは掴み・噛みつき・止めの仕組みなので、体格を 1.0 にして箱の中に収める。
     */
    private static FalcimnesEntity placeForGrab(GameTestHelper helper) {
        FalcimnesEntity f = placeAt(helper, 1);
        f.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        return f;
    }

    private static FalcimnesEntity placeAt(GameTestHelper helper, int z) {
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, z));
        f.setYRot(0.0F);
        f.yBodyRot = 0.0F;
        f.yHeadRot = 0.0F;
        return f;
    }

    /**
     * 振りかぶっている間は当たらず、鎌が正面に出た瞬間に前の相手だけに当たる。後ろには当たらない。
     * 当たるのは 18 tick 目(動作の切り替えの補間 4 tick + アニメの 0.70 秒)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void slashHitsFrontOnlyAtTheStrike(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        Pig front = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 4));
        // 体格 1.8 倍で扇が広く(片側 4.3)、テスト場の中では横に外せないので、外れる側は真後ろに置く。
        Cow behind = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(0, 2, 0));
        f.forceAction(FalcimnesEntity.ACT_ATTACK_R);
        helper.runAfterDelay(16L, () -> {
            if (front.getHealth() < front.getMaxHealth()) {
                helper.fail("鎌が前に出る前に当たっている");
            }
        });
        helper.runAfterDelay(20L, () -> {
            if (front.isAlive() && front.getHealth() >= front.getMaxHealth()) {
                helper.fail("正面 3 ブロックの豚に当たっていない");
                return;
            }
            if (behind.getHealth() < behind.getMaxHealth()) {
                helper.fail("後ろの牛まで斬っている");
                return;
            }
            helper.succeed();
        });
    }

    /** 噛みつきは 8 ずつ防具を貫く。自分からは降りられない。持ち上げている。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void biteIgnoresArmorAndPreyCannotDismount(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        Zombie z = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 2, 4));
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        z.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        z.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        if (!f.grab(z)) {
            helper.fail("ゾンビを掴めない");
            return;
        }
        helper.runAfterDelay(4L, () -> {
            z.stopRiding();
            if (z.getVehicle() != f) {
                helper.fail("掴まれた獲物が自分から降りられた");
                return;
            }
            if (z.getY() < f.getY() + 0.4D) {
                helper.fail("持ち上げていない(獲物の足 y=" + z.getY() + "、本体 y=" + f.getY() + ")");
            }
        });
        // 噛みつきは 14・34 tick 目(補間 4 tick + 0.5 秒、以後 1 秒ごと)。36 tick 目には 20 − 8 − 8 = 4 のはず(ダイヤ一式でも減らない)。
        helper.runAfterDelay(36L, () -> {
            if (Math.abs(z.getHealth() - 4.0F) > 0.01F) {
                helper.fail("噛みつき 2 回で体力 4 のはずが " + z.getHealth() + "(防具で減っている?)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 掴みの突進は前準備(威嚇 → 振りかざし)の 2.5 秒が終わるまで始まらない
     * (ユーザー指定 2026-09-23「プレイヤーが対応できる前準備を」)。突進は 54 tick 目(補間 4 tick + 2.5 秒)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void grabSprintWaitsForTheThreat(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        f.forceAction(FalcimnesEntity.ACT_GRAB_WINDUP);
        helper.runAfterDelay(51L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_GRAB_WINDUP) {
                helper.fail("2.5 秒経つ前に前準備が終わっている(動作 " + f.getAction() + ")");
            }
        });
        helper.runAfterDelay(57L, () -> {
            if (f.getAction() == FalcimnesEntity.ACT_GRAB_WINDUP) {
                helper.fail("2.5 秒経っても突進しない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 最後の噛みつき(94 tick 目)は体力に関係なく即死。殺したら死体を落とし、鎌を下ろす隙(grab_end)に入る
     * —— 掴んだまま待機へ戻ると、死体を顎の前に吊って歩き、鎌が地面を通る(レビューで発覚、2026-09-23)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140)
    public static void grabEndsInExecution(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 4));
        if (!f.grab(golem)) {
            helper.fail("アイアンゴーレムを掴めない");
            return;
        }
        helper.runAfterDelay(80L, () -> {
            // 4 回噛んで 100 − 32 = 68。まだ生きている。
            if (!golem.isAlive() || Math.abs(golem.getHealth() - 68.0F) > 0.01F) {
                helper.fail("止めの前に体力 68 のはずが " + golem.getHealth());
            }
        });
        helper.runAfterDelay(100L, () -> {
            if (golem.isAlive()) {
                helper.fail("止めで死んでいない(体力 " + golem.getHealth() + ")");
                return;
            }
            if (!f.getPassengers().isEmpty()) {
                helper.fail("殺した獲物を乗せたまま");
                return;
            }
            if (f.getAction() != FalcimnesEntity.ACT_RECOVER) {
                helper.fail("殺したあと鎌を下ろす隙になっていない(動作 " + f.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 別の個体が掴んでいる獲物は奪えない(奪うと獲物が 2 体の乗客の表に残る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void cannotGrabPreyHeldByAnother(GameTestHelper helper) {
        FalcimnesEntity f1 = placeForGrab(helper);
        FalcimnesEntity f2 = placeAt(helper, 5);
        f2.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        Zombie z = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 2, 4));
        if (!f1.grab(z)) {
            helper.fail("1 体目が掴めない");
            return;
        }
        if (f2.grab(z)) {
            helper.fail("2 体目が掴んでいる獲物を奪えてしまう");
            return;
        }
        helper.runAfterDelay(2L, () -> {
            if (z.getVehicle() != f1 || !f2.getPassengers().isEmpty()) {
                helper.fail("獲物の乗り先がずれた");
                return;
            }
            helper.succeed();
        });
    }

    /** 威嚇の途中で殴られたら、振りかざした鎌を下ろす動作(0.6 秒)を挟んでから戦いに戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void hitDuringThreatLowersTheScythes(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        DamageSource punch = helper.getLevel().damageSources().playerAttack(player);
        f.forceAction(FalcimnesEntity.ACT_THREAT);
        helper.runAfterDelay(20L, () -> {
            f.hurt(punch, 3.0F);
            if (f.getAction() != FalcimnesEntity.ACT_THREAT_END) {
                helper.fail("殴られても威嚇が止まらない、または鎌を下ろさずに戻った(動作 " + f.getAction() + ")");
            }
        });
        // 鎌を下ろす間は 16 tick(補間 4 + 0.6 秒)。
        helper.runAfterDelay(40L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_NONE) {
                helper.fail("鎌を下ろしたあと待機に戻らない(動作 " + f.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 掴まれている間に 15 以上削れば離す。それ未満では離さない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void fifteenDamageBreaksTheGrip(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        Sheep sheep = helper.spawnWithNoFreeWill(EntityType.SHEEP, new BlockPos(3, 2, 4));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        // 素手(適応の台帳は「unarmed」、誰も積んでいないので回避も軽減も起きない)。
        DamageSource punch = helper.getLevel().damageSources().playerAttack(player);
        if (!f.grab(sheep)) {
            helper.fail("羊を掴めない");
            return;
        }
        helper.runAfterDelay(2L, () -> {
            f.hurt(punch, 5.0F);               // 防御 14 で 2.7 しか通らない
            if (sheep.getVehicle() != f) {
                helper.fail("15 未満で離している");
                return;
            }
            f.invulnerableTime = 0;
            f.hurt(punch, 20.0F);              // 16.8 通る → 合計 15 を超える
            if (sheep.getVehicle() == f) {
                helper.fail("15 以上削られても離さない(体力 " + f.getHealth() + ")");
                return;
            }
            if (f.getAction() != FalcimnesEntity.ACT_RECOVER) {
                helper.fail("振りほどかれたあとの隙になっていない(動作 " + f.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 適応値 15% 以上の武器の一撃はかわし、かわせなかった(クールダウン中の)一撃は適応値ぶん減る。
     * 台帳は種ごと・ワールドに 1 つなので、<b>ここだけ別のバッチ</b>で走らせる(他のテストと同時に剣の台帳を触らない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40, batch = "falcimnes_adaptation")
    public static void dodgesAdaptedWeaponAndResistsTheNext(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String species = Adaptation.speciesId(ModEntities.FALCIMNES.get());
        FalcimnesEntity f = place(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        DamageSource sword = helper.getLevel().damageSources().playerAttack(player);
        try {
            for (int i = 0; i < 15; i++) {
                Adaptation.recordKill(server, ModEntities.FALCIMNES.get(), sword);
            }
            if (f.hurt(sword, 10.0F) || f.getHealth() < f.getMaxHealth()) {
                helper.fail("剣の適応値 30% なのにかわしていない");
                return;
            }
            if (f.getAction() != FalcimnesEntity.ACT_DODGE) {
                helper.fail("回避の動作になっていない(動作 " + f.getAction() + ")");
                return;
            }
            // 2 発目はクールダウン中なので当たる。10 → 防御 14 で 6.4 → 適応 30% で 4.48。
            float before = f.getHealth();
            if (!f.hurt(sword, 10.0F)) {
                helper.fail("クールダウン中なのにまたかわした");
                return;
            }
            float lost = before - f.getHealth();
            if (Math.abs(lost - 4.48F) > 0.05F) {
                helper.fail("被ダメージは 4.48 のはずが " + lost + "(適応が効いていない?)");
                return;
            }
            helper.succeed();
        } finally {
            AdaptationData.get(server).forget(species);
        }
    }

    private FalcimnesGameTests() {
    }
}
