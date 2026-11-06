package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.adaptation.AdaptationData;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.SpecialPacing;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
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
 *     <li><b>最初から大技を使わず、通常攻撃の合間に時々</b>(2026-09-23)。数値は仮({@link SpecialPacing#standard()})。
 *     勘定そのもの・ファルキムネスの数え方・AI を動かした交戦の出だしの 3 本。</li>
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
     * ここで見たいのは掴み・噛みつき・止めの仕組みなので、体格を 1.0(11-05 から SCALE は 1 ÷ 1.8)にして箱の中に収める。
     */
    private static FalcimnesEntity placeForGrab(GameTestHelper helper) {
        FalcimnesEntity f = placeAt(helper, 1);
        f.getAttribute(Attributes.SCALE).setBaseValue(1.0D / FalcimnesEntity.BAKE); // 11-05: 描画倍率 1.8 を geo に焼き込んだので、焼き込む前の体格 1.0 = SCALE 1 / 1.8
        return f;
    }

    private static FalcimnesEntity placeAt(GameTestHelper helper, int z) {
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, z));
        f.snapFacing(0.0F);
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
     * 掴みの終わりは止めの一撃(11-05、ユーザー決定「即死をやめて特大ダメージ」)。噛みつきを決めた回数(actions.json の grab/bites)だけ噛み、
     * そのあと止め(grab/damage、防具は効く)を 1 度入れて放し、鎌を下ろす隙(grab_end)に入る。体力の多い相手(アイアンゴーレム 100)は生き延びる。
     * 期待値は設計値から引く(記憶 gametest-derive-from-design-values)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void grabEndsInFinisher(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 4));
        golem.getAttribute(Attributes.ARMOR).setBaseValue(0.0D);
        if (!f.grab(golem)) {
            helper.fail("アイアンゴーレムを掴めない");
            return;
        }
        var g = FalcimnesEntity.def("grab");
        int bites = (int) Math.round(g.get("bites", 3.0D));
        float afterBites = 100.0F - bites * (float) g.get("bite", 8.0D);
        // 止めへ移るのは掴んで 4 + 20 × 噛む回数 tick、噛むのはそこから 14 tick、放すのは 28 tick(FINISH_*)。
        long finishStart = 4L + 20L * bites;
        helper.runAfterDelay(finishStart - 2L, () -> {
            if (!golem.isAlive() || Math.abs(golem.getHealth() - afterBites) > 0.01F) {
                helper.fail("止めの前に体力 " + afterBites + " のはずが " + golem.getHealth());
            }
        });
        helper.runAfterDelay(finishStart + 20L, () -> {
            float want = afterBites - (float) g.damage();
            if (!golem.isAlive() || Math.abs(golem.getHealth() - want) > 0.01F) {
                helper.fail("止めの後の体力 " + want + " のはずが " + golem.getHealth() + "(即死のまま?)");
            }
        });
        helper.runAfterDelay(finishStart + 34L, () -> {
            if (!f.getPassengers().isEmpty()) {
                helper.fail("止めの後も獲物を乗せたまま");
                return;
            }
            if (f.getAction() != FalcimnesEntity.ACT_RECOVER) {
                helper.fail("止めの後に鎌を下ろす隙になっていない(動作 " + f.getAction() + ")");
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
        f2.getAttribute(Attributes.SCALE).setBaseValue(1.0D / FalcimnesEntity.BAKE); // 11-05: 描画倍率 1.8 を geo に焼き込んだので、焼き込む前の体格 1.0 = SCALE 1 / 1.8
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

    /**
     * 掴まれている獲物は別の乗り物に乗れない(2026-10-01 点検、バティムネスと同じ穴)。バニラの {@code startRiding} は、
     * 今の乗り物から降りる処理が止められても先へ進んで新しい乗り物にも載せてしまう —— 両方の乗客の表に残り、離しても本体の表から消えなかった。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void heldPreyCannotBoardAnotherVehicle(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 4));
        husk.setInvulnerable(true);
        // 本体(x 2.7〜4.3・z 0.7〜2.3)に触れない隅に置く(触れるとボートが本体を乗せてしまう)。
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(1.0D, 2.0D, 5.5D));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        DamageSource punch = helper.getLevel().damageSources().playerAttack(player);
        if (!f.grab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (husk.startRiding(boat, true)) {
                helper.fail("掴まれている獲物がボートに乗れた");
                return;
            }
            if (husk.getVehicle() != f || !boat.getPassengers().isEmpty()) {
                helper.fail("獲物の乗り物が変わった(乗り物 " + husk.getVehicle() + "・ボートの乗客 " + boat.getPassengers().size() + ")");
                return;
            }
            // 振りほどけば本体の表からも消える(素手 20 → 防御 14 で 16.8 通る、15 を超える)
            f.hurt(punch, 20.0F);
            if (f.isVehicle() || husk.getVehicle() != null) {
                helper.fail("離した後も乗客の表に残っている(本体の乗客 " + f.getPassengers().size() + "・獲物の乗り物 " + husk.getVehicle() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * ログアウトの手当て({@link FalcimnesEntity.Events#releaseHeldRider}、2026-10-01 点検): 掴んでいる獲物をその場で離し、本体は世界に残る。
     * 離さないと、バニラはログアウトしたプレイヤーの乗り物(= ファルキムネス)ごとプレイヤーのデータへ保存して世界から消す。
     * 本物のログアウトは GameTest で起こせないので、イベントから呼ぶ関数をハスクで直接見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void logoutHandlerReleasesHeldPrey(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 4));
        husk.setInvulnerable(true);
        if (!f.grab(husk)) {
            helper.fail("ハスクを掴めない");
            return;
        }
        helper.runAfterDelay(3L, () -> {
            if (!FalcimnesEntity.Events.releaseHeldRider(husk)) {
                helper.fail("掴まれている獲物を離さない");
                return;
            }
            if (husk.getVehicle() != null || f.isVehicle() || f.getAction() != FalcimnesEntity.ACT_RECOVER) {
                helper.fail("離した後の状態が違う(獲物の乗り物 " + husk.getVehicle() + "・動作 " + f.getAction() + ")");
                return;
            }
            if (!f.isAlive() || f.isRemoved()) {
                helper.fail("本体が世界から消えた");
                return;
            }
            if (FalcimnesEntity.Events.releaseHeldRider(husk)) {
                helper.fail("掴まれていない相手で離す扱いになった");
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

    /**
     * 待機の仕草(2026-09-24)。鎌の手入れの途中で標的が現れたら、持ち上げた鎌を下ろす動作(0.4 秒)を挟んでから戻る
     * (待機へ直に補間すると刃が前脚を通る)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void groomLowersTheScytheWhenATargetAppears(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        // 標的を選ぶ Goal は spawnWithNoFreeWill では外れない(外れるのは行動の Goal だけ)ので、AI を止める —— 止めないとハスクや隣のテスト場の Mob を
        // 自分で標的にして、仕草が勝手に止まる。動作は aiStep で進むので NoAI でも進む。
        f.setNoAi(true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 5));
        f.startIdle(FalcimnesEntity.IDLE_GROOM_R);
        // 30 tick 目 = アニメの 1.3 秒(鎌を口元でしごいている)。
        helper.runAfterDelay(30L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_IDLE) {
                helper.fail("鎌の手入れが途中で終わっている(動作 " + f.getAction() + " " + why(f) + ")");
                return;
            }
            f.setTarget(husk);
        });
        helper.runAfterDelay(32L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_GROOM_END) {
                helper.fail("標的が現れても手入れをやめない、または鎌を下ろさずに戻った(動作 " + f.getAction() + ")");
            }
        });
        // 鎌を下ろす間は 12 tick(補間 4 + 0.4 秒)。
        helper.runAfterDelay(50L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_NONE) {
                helper.fail("鎌を下ろしたあと待機に戻らない(動作 " + f.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 失敗したときの手がかり(体力・最後に受けたダメージ・標的)。 */
    private static String why(FalcimnesEntity f) {
        return "体力 " + f.getHealth() + "、最後のダメージ " + (f.getLastDamageSource() == null ? "なし" : f.getLastDamageSource().getMsgId())
                + "、標的 " + f.getTarget() + "、NoAI " + f.isNoAi();
    }

    /** 首と頭だけの仕草(あくび)は、殴られたらすぐやめる(鎌を下ろす動作は挟まない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 30)
    public static void hitStopsAnIdleFidget(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        f.setNoAi(true);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        DamageSource punch = helper.getLevel().damageSources().playerAttack(player);
        f.startIdle(FalcimnesEntity.IDLE_GAPE);
        helper.runAfterDelay(10L, () -> {
            f.hurt(punch, 3.0F);
            if (f.getAction() != FalcimnesEntity.ACT_NONE) {
                helper.fail("殴られてもあくびを続けている(動作 " + f.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 待機の仕草はアニメの長さ(翅の身震い: 補間 4 + 1.8 秒 = 40 tick)で終わり、待機へ戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void idleFidgetEndsOnItsOwn(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        f.setNoAi(true);
        f.startIdle(FalcimnesEntity.IDLE_SHAKE);
        helper.runAfterDelay(30L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_IDLE) {
                helper.fail("翅の身震いが早く終わった(動作 " + f.getAction() + " " + why(f) + ")");
            }
        });
        helper.runAfterDelay(45L, () -> {
            if (f.getAction() != FalcimnesEntity.ACT_NONE) {
                helper.fail("翅の身震いが終わらない(動作 " + f.getAction() + ")");
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
            if (!f.isDodging()) {
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

    // ------------------------------------------------------------------ 大技の出し方(SpecialPacing)

    /**
     * 大技の出し方の勘定そのもの(乱数を固定して {@link SpecialPacing} を直接叩く)。ユーザー指定 2026-09-23
     * 「最初から特殊攻撃を使わず、通常攻撃を使いながら、時々特殊攻撃を織り交ぜる」。数値は仮(暖機 120 tick・通常攻撃 2 回・35%):
     * <ul>
     *     <li>交戦から 119 tick では、通常攻撃を 2 回出していても出さない。120 tick で出してよくなる。</li>
     *     <li>出してよい間は roll がおよそ 35% で当たる。出してはいけない間は 1 度も当たらない。</li>
     *     <li>大技の後は通常攻撃 2 回で再び出してよくなる(暖機はやり直さない)。</li>
     *     <li>標的が替わってもやり直さない(2026-09-24 に変えた。殴られるたびに標的が替わるので、替わるたびにやり直すと
     *     2 人で交互に殴るだけで大技が出なくなる)。標的が居ない状態は 199 tick までは続き、200 tick(10 秒)でやり直し。</li>
     * </ul>
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pacingWaitsForWarmupAndNormals(GameTestHelper helper) {
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 1));
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 5));
        RandomSource random = RandomSource.create(20260923L);
        SpecialPacing p = SpecialPacing.standard();

        if (p.ready()) {
            helper.fail("交戦していないのに大技を出してよいことになっている");
            return;
        }
        for (int i = 0; i < 119; i++) p.tick(pig);
        p.onNormalAttack();
        p.onNormalAttack();
        if (p.ready()) {
            helper.fail("交戦から 119 tick(6 秒未満)で大技を出してよいことになっている");
            return;
        }
        for (int i = 0; i < 1000; i++) {
            if (p.roll(random)) {
                helper.fail("暖機の間に roll が当たった");
                return;
            }
        }
        p.tick(pig);
        if (!p.ready()) {
            helper.fail("交戦から 120 tick・通常攻撃 2 回で大技を出してよくならない");
            return;
        }
        int hits = 0;
        for (int i = 0; i < 1000; i++) {
            if (p.roll(random)) hits++;
        }
        if (hits < 280 || hits > 420) {
            helper.fail("roll の当たりが 1000 回中 " + hits + " 回(35% のはず)");
            return;
        }

        p.onSpecial();
        p.onNormalAttack();
        if (p.ready()) {
            helper.fail("大技の後、通常攻撃 1 回で次の大技を出してよいことになっている");
            return;
        }
        p.onNormalAttack();
        if (!p.ready()) {
            helper.fail("大技の後、通常攻撃 2 回で次の大技を出してよくならない(暖機をやり直している?)");
            return;
        }

        // 標的が替わってもやり直さない(交互に替わっても)。
        for (int i = 0; i < 40; i++) p.tick(i % 2 == 0 ? cow : pig);
        if (!p.ready()) {
            helper.fail("標的が替わっただけで交戦をやり直している(2 人で交互に殴ると大技が出なくなる)");
            return;
        }
        // 標的が居ない状態が 199 tick なら続き、200 tick でやり直し。
        for (int i = 0; i < 199; i++) p.tick(null);
        if (!p.ready()) {
            helper.fail("標的が居ない状態 199 tick(10 秒未満)で交戦をやり直している");
            return;
        }
        p.tick(null);
        if (p.ready()) {
            helper.fail("標的が居ない状態が 10 秒続いても交戦をやり直していない");
            return;
        }
        // 同じ標的が戻ってきても、やり直した後なので最初から。
        p.tick(cow);
        p.onNormalAttack();
        p.onNormalAttack();
        if (p.ready()) {
            helper.fail("やり直した後に暖機なしで大技を出してよいことになっている");
            return;
        }
        helper.succeed();
    }

    /**
     * ファルキムネスが横薙ぎを通常攻撃として、地走りの光の帯を大技として数えている(11-05: 連撃・掴みは通常技になった)。AI は止め({@code setNoAi})、
     * 標的はテスト側で決める(標的を選ぶ Goal が隣のテスト場の個体を選ぶと交戦がやり直しになるため)。
     * 横薙ぎ 2 回を出しても 6 秒経つまでは出してよくならず、経てば出してよくなり、連撃を出すとまた出してはいけなくなる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void falcimnesCountsSlashesTowardSpecials(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        f.setNoAi(true);
        // 横薙ぎで死なない相手(体力 100、横薙ぎ 16 × 2)。
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 5));
        f.setTarget(golem);
        SpecialPacing p = f.getSpecialPacing();
        helper.runAfterDelay(2L, () -> f.forceAction(FalcimnesEntity.ACT_ATTACK_R));
        // 横薙ぎは 36 tick(11-05)。1 回目が終わってから 2 回目。
        helper.runAfterDelay(40L, () -> {
            if (p.ready()) {
                helper.fail("横薙ぎ 1 回で大技を出してよいことになっている");
                return;
            }
            f.forceAction(FalcimnesEntity.ACT_ATTACK_L);
        });
        helper.runAfterDelay(100L, () -> {
            if (p.ready()) {
                helper.fail("横薙ぎ 2 回でも交戦から 6 秒経つ前に大技を出してよいことになっている");
            }
        });
        helper.runAfterDelay(130L, () -> {
            if (!p.ready()) {
                helper.fail("横薙ぎ 2 回・交戦から 6 秒で大技を出してよくならない(横薙ぎを数えていない?)");
                return;
            }
            f.forceAction(FalcimnesEntity.ACT_RIFT_WINDUP);
            if (p.ready()) {
                helper.fail("地走り(特殊技)を出したのに、続けて大技を出してよいままになっている");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 標的が替わっても交戦をやり直さない(2026-09-24 に決めた)。2 体の相手が交互に殴ってくる(= 殴られるたびにバニラの
     * {@code HurtByTargetGoal} が標的を替える)ときを、10 tick ごとに標的を替えて真似る。横薙ぎ 2 回と暖機 6 秒の後は大技を出してよくなり、
     * 標的が替わり続けてもそのまま。以前の「標的が替わったらやり直し」だと、ここは一度も出してよくならなかった。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void pacingKeepsGoingWhenTargetSwitches(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        f.setNoAi(true);
        IronGolem left = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(1, 2, 5));
        IronGolem right = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(5, 2, 5));
        f.setTarget(left);
        SpecialPacing p = f.getSpecialPacing();
        helper.onEachTick(() -> {
            left.setHealth(left.getMaxHealth());
            right.setHealth(right.getMaxHealth());
            f.setTarget(helper.getTick() / 10 % 2 == 0 ? left : right);
        });
        helper.runAfterDelay(2L, () -> f.forceAction(FalcimnesEntity.ACT_ATTACK_R));
        helper.runAfterDelay(40L, () -> f.forceAction(FalcimnesEntity.ACT_ATTACK_L));
        helper.runAfterDelay(130L, () -> {
            if (!p.ready()) {
                helper.fail("標的が 10 tick ごとに替わると、横薙ぎ 2 回・交戦から 6 秒でも大技を出してよくならない(替わるたびにやり直している)");
            }
        });
        helper.runAfterDelay(180L, () -> {
            if (!p.ready()) {
                helper.fail("標的が替わり続けている間に大技を出してよくなくなった");
                return;
            }
            helper.succeed();
        });
    }


    /**
     * AI を動かした本物の交戦で、最初の 5.5 秒は通常技(横薙ぎ・連撃)だけを出し、特殊技(地走りの光の帯)を出さない。
     * 変更前は交戦直後から連撃(毎 tick 1/12、最初の 3 秒はクールダウン)が出ていた。乱数に頼るのは「出さない」側だけなので、
     * このテストが偶然通ることはあっても、正しい実装で偶然落ちることは無い。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 130)
    public static void falcimnesOpensWithSlashesNotSpecials(GameTestHelper helper) {
        FalcimnesEntity f = helper.spawn(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, 1));
        f.snapFacing(0.0F);
        // 正面 3 ブロック(横薙ぎの間合いの内側、連撃の間合いの内側)。横薙ぎ 16 × 3 回でも死なない。
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 4));
        f.setTarget(golem);
        boolean[] sawSlash = {false};
        int[] special = {FalcimnesEntity.ACT_NONE};
        helper.onEachTick(() -> {
            int act = f.getAction();
            if (act == FalcimnesEntity.ACT_ATTACK_R || act == FalcimnesEntity.ACT_ATTACK_L) {
                sawSlash[0] = true;
            } else if (act == FalcimnesEntity.ACT_FLURRY_WINDUP) {
                sawSlash[0] = true;   // 11-05: 連撃は通常技(近い間合いの選び方の候補)
            } else if (act == FalcimnesEntity.ACT_RIFT_WINDUP) {
                special[0] = act;
            }
        });
        helper.runAfterDelay(110L, () -> {
            if (special[0] != FalcimnesEntity.ACT_NONE) {
                helper.fail("交戦から 6 秒経つ前に大技を出した(動作 " + special[0] + ")");
                return;
            }
            if (!sawSlash[0]) {
                helper.fail("正面 3 ブロックの標的に横薙ぎを出していない(交戦していない?)");
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 回る速さの上限(ユーザー指定 2026-09-24「毎秒 140 度」)

    /**
     * 向きは 1 tick に 7° を超えて変わらない。真後ろの相手へ、バニラの移動(1 tick 90°)・{@code lookAt}・視線・向きの直書き(+90°)で
     * 毎 tick 急かしても同じ(スクティムネスの同じテストの写し)。ちゃんと相手の方へは回っていき、半周(180°)に 26 tick 以上かかる。
     * 体・頭の向きは常に yRot と同じ。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void turnIsLimitedToSevenDegreesPerTick(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        Player behind = helper.makeMockPlayer(GameType.SURVIVAL);
        behind.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5D, 2.0D, -1.5D)));
        float[] last = {Float.NaN};
        float[] turned = {0.0F};
        helper.onEachTick(() -> {
            float yaw = f.getYRot();
            if (!Float.isNaN(last[0])) {
                float step = net.minecraft.util.Mth.wrapDegrees(yaw - last[0]);
                if (Math.abs(step) > FalcimnesEntity.MAX_TURN_PER_TICK + 1.0E-3F) {
                    helper.fail("1 tick に " + step + "° 回った");
                    return;
                }
                turned[0] += Math.abs(step);
            }
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(f.yBodyRot - yaw)) > 1.0E-3F
                    || Math.abs(net.minecraft.util.Mth.wrapDegrees(f.yHeadRot - yaw)) > 1.0E-3F) {
                helper.fail("体・頭の向きが yRot とずれている(体 " + f.yBodyRot + " 頭 " + f.yHeadRot + " yRot " + yaw + ")");
                return;
            }
            last[0] = yaw;
            // よそからの急かし(次の tick の終わりに旋回の上限で上書きされるはず)。
            f.lookAt(behind, 180.0F, 90.0F);
            f.getLookControl().setLookAt(behind);
            f.getMoveControl().setWantedPosition(behind.getX(), f.getY(), behind.getZ(), 1.0D);
            f.setYRot(yaw + 90.0F);
        });
        helper.runAfterDelay(20L, () -> {
            if (turned[0] >= 175.0F) {
                helper.fail("20 tick で " + turned[0] + "° 回った(毎秒 140° なら 140° 以下のはず)");
            }
        });
        helper.runAfterDelay(45L, () -> {
            if (turned[0] < 170.0F) {
                helper.fail("真後ろの相手へ回りきっていない(45 tick で " + turned[0] + "°)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 回る速さに上限があるので、回避は向きを変えずに跳ぶ(2026-09-24): 右(−X)に居る相手の一撃なら左(+X)へ跳ぶ。跳んだ瞬間に向きは変わらない。
     * 台帳は種ごと・ワールドに 1 つなので、回避のもう 1 本のテストと同じ別のバッチで走らせる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 30, batch = "falcimnes_adaptation")
    public static void dodgeHopsSidewaysWithoutTurning(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String species = Adaptation.speciesId(ModEntities.FALCIMNES.get());
        FalcimnesEntity f = place(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        // 本体は (3.5, 2, 1.5) で +Z を向いている。右は −X。
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5D, 2.0D, 1.5D)));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        DamageSource sword = helper.getLevel().damageSources().playerAttack(player);
        try {
            for (int i = 0; i < 15; i++) {
                Adaptation.recordKill(server, ModEntities.FALCIMNES.get(), sword);
            }
            if (f.hurt(sword, 10.0F)) {
                helper.fail("剣の適応値 30% なのにかわしていない");
                return;
            }
            if (f.getAction() != FalcimnesEntity.ACT_DODGE_L) {
                helper.fail("右の相手から左へ跳んでいない(動作 " + f.getAction() + ")");
                return;
            }
            net.minecraft.world.phys.Vec3 v = f.getDeltaMovement();
            if (v.x < 0.8D || Math.abs(v.z) > 1.0E-3D) {
                helper.fail("左(+X)へまっすぐ跳んでいない: " + v);
                return;
            }
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(f.getYRot())) > 1.0E-3F) {
                helper.fail("跳んだ瞬間に向きが変わった: " + f.getYRot());
                return;
            }
            helper.succeed();
        } finally {
            AdaptationData.get(server).forget(species);
        }
    }

    /**
     * 掴みの終わり(突き出した鎌を引き戻している間)の最初の 9 tick は、適応した武器でもかわさない(2026-09-24、仮)。
     * ここから回避へ補間すると鎌が下顎を貫いて地面へ潜るため(アニメの検算の割り込みの掃き出しで出た)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 30, batch = "falcimnes_adaptation")
    public static void noDodgeRightAfterTheGrab(GameTestHelper helper) {
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
            f.forceAction(FalcimnesEntity.ACT_RECOVER);
            if (!f.hurt(sword, 10.0F)) {
                helper.fail("掴みの終わりの直後なのにかわした(動作 " + f.getAction() + ")");
                return;
            }
            if (f.isDodging()) {
                helper.fail("掴みの終わりの直後に回避へ移った");
                return;
            }
            helper.succeed();
        } finally {
            AdaptationData.get(server).forget(species);
        }
    }

    private FalcimnesGameTests() {
    }

    // ------------------------------------------------------------------ 11-05 の作り直し(層 2 の部品・飛ぶ斬撃・地走りの光の帯)

    /**
     * 通常技の選び方(層 2 の {@code ActionPicker})。直前の技は点数に recency を掛ける —— recency 0 なら直前の技は選ばれず、
     * クールダウンの間・間合いの外の技も選ばれない。乱数に頼らない形で確かめる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10)
    public static void pickerLowersTheLastMoveAndRespectsRangeAndCooldown(GameTestHelper helper) {
        var a = new com.yayuy.overgrownvegetation.entity.action.ActionDef("a", 5, 0.0D, 4.0D, 1.0D, 0.0D, 1.0D, 0.0D, null);
        var b = new com.yayuy.overgrownvegetation.entity.action.ActionDef("b", 0, 0.0D, 4.0D, 1.0D, 0.0D, 1.0D, 0.0D, null);
        var far = new com.yayuy.overgrownvegetation.entity.action.ActionDef("far", 0, 6.0D, 16.0D, 100.0D, 1.0D, 1.0D, 0.0D, null);
        var picker = new com.yayuy.overgrownvegetation.entity.action.ActionPicker();
        RandomSource r = RandomSource.create(5);
        java.util.List<com.yayuy.overgrownvegetation.entity.action.ActionDef> defs = java.util.List.of(a, b, far);
        picker.used(b);
        for (int i = 0; i < 50; i++) {
            var p = picker.pick(defs, 3.0D, 1.0D, 0.0D, d -> true, r);
            if (p == null || !p.id().equals("a")) {
                helper.fail("直前の技 b(recency 0)か間合いの外の far を選んだ: " + (p == null ? "null" : p.id()));
                return;
            }
        }
        picker.used(a);   // a はクールダウン 5、直前の技も a。b は直前でなくなった。
        var p = picker.pick(defs, 3.0D, 1.0D, 0.0D, d -> true, r);
        if (p == null || !p.id().equals("b")) {
            helper.fail("クールダウン中の a か直前の a を選んだ: " + (p == null ? "null" : p.id()));
            return;
        }
        var q = picker.pick(defs, 10.0D, 1.0D, 0.0D, d -> true, r);
        if (q == null || !q.id().equals("far")) {
            helper.fail("10 ブロックの相手に遠い技を選ばない: " + (q == null ? "null" : q.id()));
            return;
        }
        helper.succeed();
    }

    /**
     * 横薙ぎの段(前触れ → 実行 → 隙)の長さはアニメと対(前触れ 0.7 秒 = ユーザー指定、隙は教科書 15 章の 13 tick 以上)。
     * 飛ぶ斬撃は前触れ 20 tick・隙 14 tick。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10)
    public static void movePhasesKeepTheAgreedLengths(GameTestHelper helper) {
        var s = com.yayuy.overgrownvegetation.entity.action.ActionTiming.ofSeconds(4, 0.7, 0.2, 0.7);
        var w = com.yayuy.overgrownvegetation.entity.action.ActionTiming.ofSeconds(4, 1.0, 0.3, 0.7);
        if (s.windup() != 14 || s.recover() < 13 || w.windup() != 20 || w.recover() != 14) {
            helper.fail("技の段の長さ: 横薙ぎ " + s + " / 飛ぶ斬撃 " + w);
            return;
        }
        if (s.phaseAt(17) != com.yayuy.overgrownvegetation.entity.action.ActionPhase.WINDUP
                || s.phaseAt(18) != com.yayuy.overgrownvegetation.entity.action.ActionPhase.EXECUTE
                || s.phaseAt(22) != com.yayuy.overgrownvegetation.entity.action.ActionPhase.RECOVER
                || s.phaseAt(36) != com.yayuy.overgrownvegetation.entity.action.ActionPhase.DONE) {
            helper.fail("段の境目がずれた");
            return;
        }
        helper.succeed();
    }

    /** 飛ぶ斬撃は前触れの後(放つ tick)に出て、前に並んだ 2 体のうち最初の 1 体だけに当たって消える。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void waveHitsOnlyTheFirstInLine(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        f.setNoAi(true);
        Pig front = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 3));
        Pig back = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 5));
        front.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        front.setHealth(100.0F);
        back.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        back.setHealth(100.0F);
        var server = helper.getLevel();
        Vec3 start = helper.absoluteVec(new Vec3(3.5D, 2.4D, 1.6D));
        com.yayuy.overgrownvegetation.entity.FalcimnesWaveEntity.shoot(server, f, start, new Vec3(0, 0, 1),
                1.0D, 16.0D, 10.0F, 3.0F, 0.5F);
        helper.runAfterDelay(10L, () -> {
            if (front.getHealth() >= 100.0F) {
                helper.fail("前の豚に当たっていない");
                return;
            }
            if (back.getHealth() < 100.0F) {
                helper.fail("前の豚に当たった後も飛んで、後ろの豚にも当たった");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 地走りの光の帯: 筋の上の相手には帯が立った時にダメージと上への打ち上げ、筋から横へ外れた相手には当たらない。
     * 刺した Entity をテスト場の端から直に出す(本体は 3 ブロックの幅で 7×7 に入らない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void riftBandHitsOnTheLineAndLifts(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        f.setNoAi(true);
        Pig onLine = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 5));
        Pig aside = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(6, 2, 5));
        for (Pig p : new Pig[]{onLine, aside}) {
            p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
            p.setHealth(100.0F);
        }
        Vec3 start = helper.absoluteVec(new Vec3(3.5D, 2.0D, 1.5D));
        var rift = com.yayuy.overgrownvegetation.entity.FalcimnesRiftEntity.spawn(helper.getLevel(), f, start, 0.0F, 5.0D, 1.5D,
                1.2D, 12, 8, 3.0D, 14.0F, 0.6D);
        boolean[] lifted = {false};
        helper.onEachTick(() -> {
            if (onLine.getDeltaMovement().y > 0.3D) lifted[0] = true;
        });
        long bandAt = rift.bandStart();
        helper.runAfterDelay(bandAt - 2L, () -> {
            if (onLine.getHealth() < 100.0F) helper.fail("帯が立つ前に当たった");
        });
        helper.runAfterDelay(bandAt + 4L, () -> {
            if (onLine.getHealth() >= 100.0F) {
                helper.fail("筋の上の豚に帯が当たっていない");
                return;
            }
            if (!lifted[0]) {
                helper.fail("帯に当たった豚が打ち上がっていない");
                return;
            }
            if (aside.getHealth() < 100.0F) {
                helper.fail("筋から横へ 3 ブロック外れた豚にも当たった");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 地走りの円く噴き出す形(11-05 夜、近くの相手): 刺した所から半径の中の相手には噴き出した時に当たり、外の相手には当たらない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void riftBurstHitsInsideTheCircle(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        f.setNoAi(true);
        Pig inside = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(4, 2, 3));
        Pig outside = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(6, 2, 6));
        for (Pig p : new Pig[]{inside, outside}) {
            p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
            p.setHealth(100.0F);
        }
        Vec3 start = helper.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        var rift = com.yayuy.overgrownvegetation.entity.FalcimnesRiftEntity.spawnBurst(helper.getLevel(), f, start, 0.0F, 2.5D,
                1.2D, 12, 8, 3.0D, 14.0F, 0.6D);
        long bandAt = rift.bandStart();
        helper.runAfterDelay(bandAt - 2L, () -> {
            if (inside.getHealth() < 100.0F) helper.fail("噴き出す前に当たった");
        });
        helper.runAfterDelay(bandAt + 4L, () -> {
            if (inside.getHealth() >= 100.0F) {
                helper.fail("円の中の豚に当たっていない");
                return;
            }
            if (outside.getHealth() < 100.0F) {
                helper.fail("円の外の豚にも当たった");
                return;
            }
            helper.succeed();
        });
    }

    /** 飛ぶ斬撃の動作は、放つ tick に斬撃を 1 つ出す(前触れの間は出さない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void waveActionReleasesAtTheReleaseTick(GameTestHelper helper) {
        FalcimnesEntity f = placeForGrab(helper);
        f.setNoAi(true);
        int[] firstSeen = {-1};
        helper.runAfterDelay(1L, () -> f.forceAction(FalcimnesEntity.ACT_WAVE_R));
        helper.onEachTick(() -> {
            if (firstSeen[0] < 0 && !helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.FalcimnesWaveEntity.class,
                    f.getBoundingBox().inflate(12.0D)).isEmpty()) {
                firstSeen[0] = f.getActionTicks();
            }
        });
        helper.runAfterDelay(50L, () -> {
            // 放つのは動作の 26 tick 目(補間 4 + アニメの 1.1 秒)。
            if (firstSeen[0] != 26) {
                helper.fail("斬撃が出た tick が 26 でない: " + firstSeen[0]);
                return;
            }
            helper.succeed();
        });
    }
}
