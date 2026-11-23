package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.adaptation.AdaptationData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.SpecialPacing;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;

/**
 * ファルキムネス(グループ3)のテスト(2026-09-23)。
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
        f.getAttribute(PortAttributes.SCALE.get()).setBaseValue(1.0D / FalcimnesEntity.BAKE); // 11-05: 描画倍率 1.8 を geo に焼き込んだので、焼き込む前の体格 1.0 = SCALE 1 / 1.8
        return f;
    }

    private static FalcimnesEntity placeAt(GameTestHelper helper, int z) {
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, z));
        f.snapFacing(0.0F);
        f.setPersonality(0.5F, 0.5F, 0.5F);   // 段 3: 性格の真ん中 = 段 2 までと同じ振る舞い(湧いた個体は U 字の分布で振るので、テストは決めて置く)
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
        // 噛みつきは 14・34 tick 目(補間 4 tick + 0.5 秒、以後 1 秒ごと)。36 tick 目には 体力 − 噛み × 2 のはず(ダイヤ一式でも減らない)。
        // 11-18 夜: 噛みは設計値から引く(攻撃 16 → 25 と同じ比で 8 → 12.5)。2 回で死なないよう、ゾンビの体力を 60 にする
        float bite = (float) FalcimnesEntity.def("grab").get("bite", 8.0D);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0D);
        z.setHealth(60.0F);
        float want = 60.0F - bite * 2.0F;
        helper.runAfterDelay(36L, () -> {
            if (Math.abs(z.getHealth() - want) > 0.01F) {
                helper.fail("噛みつき 2 回で体力 " + want + " のはずが " + z.getHealth() + "(防具で減っている?)");
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
        // 11-18 夜: 技の傷を攻撃と同じ比で上げた(噛み 12.5 × 3 + 止め 46.9 + 胞子感染 20 > 100)。止めで死なないよう体力を 200 にする
        golem.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        golem.setHealth(200.0F);
        if (!f.grab(golem)) {
            helper.fail("アイアンゴーレムを掴めない");
            return;
        }
        var g = FalcimnesEntity.def("grab");
        int bites = (int) Math.round(g.get("bites", 3.0D));
        float afterBites = 200.0F - bites * (float) g.get("bite", 8.0D);
        // 止めへ移るのは掴んで 4 + 20 × 噛む回数 tick、噛むのはそこから 14 tick、放すのは 28 tick(FINISH_*)。
        long finishStart = 4L + 20L * bites;
        helper.runAfterDelay(finishStart - 2L, () -> {
            // 11-15 から噛まれた Mob は傷の暴露で感染し、暴露量が満ちると胞子感染の 20 を受ける(設計どおり)。満ちる tick は暴露量の判定の
            // 周期しだいで、止めの前後どちらにも来る(11-18: 200 本中 33 本が止めの直後に受けて落ちていた)。受けた回数ぶんを差し引いて見る
            float spore = OvAttach.hasData(golem, ModAttachments.SPORE_EXPOSURE)
                    ? (float) (DesignData.sporeField().mobExposureDamage() * OvAttach.getData(golem, ModAttachments.SPORE_EXPOSURE).reachedCount) : 0.0F;
            if (!golem.isAlive() || Math.abs(golem.getHealth() - (afterBites - spore)) > 0.01F) {
                helper.fail("止めの前に体力 " + afterBites + "(胞子感染 " + spore + " を引いて " + (afterBites - spore) + ")のはずが " + golem.getHealth());
            }
        });
        helper.runAfterDelay(finishStart + 20L, () -> {
            float want = afterBites - (float) g.damage();
            float spore = OvAttach.hasData(golem, ModAttachments.SPORE_EXPOSURE)
                    ? (float) (DesignData.sporeField().mobExposureDamage() * OvAttach.getData(golem, ModAttachments.SPORE_EXPOSURE).reachedCount) : 0.0F;
            want -= spore;
            if (!golem.isAlive() || Math.abs(golem.getHealth() - want) > 0.01F) {
                helper.fail("止めの後の体力 " + want + "(胞子感染 " + spore + " を引いた値)のはずが " + golem.getHealth() + "(即死のまま?)");
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
        f2.getAttribute(PortAttributes.SCALE.get()).setBaseValue(1.0D / FalcimnesEntity.BAKE); // 11-05: 描画倍率 1.8 を geo に焼き込んだので、焼き込む前の体格 1.0 = SCALE 1 / 1.8
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
     * AI を動かした本物の交戦で、最初の 5.5 秒は通常技(横薙ぎ・連撃・段 2 の小さな技)だけを出し、特殊技(地走りの光の帯)を出さない。
     * 変更前は交戦直後から連撃(毎 tick 1/12、最初の 3 秒はクールダウン)が出ていた。乱数に頼るのは「出さない」側だけなので、
     * このテストが偶然通ることはあっても、正しい実装で偶然落ちることは無い。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 130)
    public static void falcimnesOpensWithSlashesNotSpecials(GameTestHelper helper) {
        FalcimnesEntity f = helper.spawn(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, 1));
        f.snapFacing(0.0F);
        f.setPersonality(0.5F, 0.5F, 0.5F);
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
            } else if (FalcimnesEntity.isSmallMove(act)) {
                sawSlash[0] = true;   // 11-07 夜(段 2): 小さな通常技 5 つも近い間合いの通常技
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
        Player behind = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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
        Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
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

    // ------------------------------------------------------------------ 段 3(11-08): 個体の性格と連続技

    /** 性格のつまみは U 字の分布(両端の 0.1 以内に 3 割より多く入る)で、平均は真ん中あたり。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void personalityRollIsUShaped(GameTestHelper helper) {
        RandomSource r = RandomSource.create(20261108L);
        int n = 20000, ends = 0;
        double sum = 0.0D;
        for (int i = 0; i < n; i++) {
            float v = com.yayuy.overgrownvegetation.entity.FalcimnesPersonality.roll(r);
            if (v < 0.0F || v > 1.0F) {
                helper.fail("つまみが 0〜1 の外: " + v);
                return;
            }
            if (v < 0.1F || v > 0.9F) ends++;
            sum += v;
        }
        double endFrac = ends / (double) n, mean = sum / n;
        if (endFrac < 0.3D || Math.abs(mean - 0.5D) > 0.02D) {
            helper.fail("U 字になっていない: 両端 " + endFrac + " 平均 " + mean);
            return;
        }
        helper.succeed();
    }

    /** どのつまみも 0.5 なら、どの通常技の重みも変わらない(段 2 までと同じ選び方)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void neutralPersonalityKeepsWeights(GameTestHelper helper) {
        for (String id : new String[]{"sweep", "flurry", "grab", "wave", "kiriage", "tsukikaki", "kosa", "fumikomi", "kozuki"}) {
            double w = com.yayuy.overgrownvegetation.entity.FalcimnesPersonality.weight(FalcimnesEntity.def(id), 0.5F, 0.5F, 0.5F);
            if (Math.abs(w - 1.0D) > 1e-9) {
                helper.fail(id + " の重みが性格 0.5 で " + w);
                return;
            }
        }
        double cautious = com.yayuy.overgrownvegetation.entity.FalcimnesPersonality.weight(FalcimnesEntity.def("kozuki"), 1.0F, 0.5F, 0.5F);
        double bold = com.yayuy.overgrownvegetation.entity.FalcimnesPersonality.weight(FalcimnesEntity.def("kozuki"), 0.0F, 0.5F, 0.5F);
        if (!(cautious > 1.0D && bold < 1.0D)) {
            helper.fail("慎重さで小突きの重みが傾かない: " + cautious + " / " + bold);
            return;
        }
        helper.succeed();
    }

    /**
     * 連続技: 小突きが当たったら、隙の途中で(休めに戻らずに)当たったときの表の技(切り上げ・突き掻き)へ続け、2 段目からは続けない(最大 2 段)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140)
    public static void comboFollowsHitWithSecondStepOnly(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        Pig front = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 4));
        front.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        front.setHealth(100.0F);
        f.setTarget(front);
        f.setComboChanceForTest(1.0D);
        f.forceAction(FalcimnesEntity.ACT_KOZUKI_R);
        java.util.List<Integer> seq = new java.util.ArrayList<>();
        int[] prev = {FalcimnesEntity.ACT_KOZUKI_R};
        boolean[] secondWasStep2 = {false};
        seq.add(FalcimnesEntity.ACT_KOZUKI_R);
        helper.onEachTick(() -> {
            int act = f.getAction();
            if (act != prev[0]) {
                seq.add(act);
                if (FalcimnesEntity.isSmallMove(act) && f.comboStep() == 1) secondWasStep2[0] = true;
                prev[0] = act;
            }
        });
        helper.runAfterDelay(130L, () -> {
            // 期待: 小突き → (休めを挟まず)切り上げか突き掻き → 休め
            if (seq.size() < 3 || seq.get(2) != FalcimnesEntity.ACT_NONE) {
                helper.fail("動作の並びが 小突き → 2 段目 → 休め になっていない: " + seq);
                return;
            }
            int second = seq.get(1);
            boolean ok = second == FalcimnesEntity.ACT_KIRIAGE_R || second == FalcimnesEntity.ACT_KIRIAGE_L || second == FalcimnesEntity.ACT_TSUKIKAKI;
            if (!ok || !secondWasStep2[0]) {
                helper.fail("当たったあとの 2 段目が表(切り上げ・突き掻き)の技でない: " + seq);
                return;
            }
            if (seq.size() > 3) {
                helper.fail("3 段目へ続けた: " + seq);
                return;
            }
            if (front.getHealth() >= front.getMaxHealth()) {
                helper.fail("小突きが当たっていない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 慎重な個体(慎重さ 1)は、隙の無い相手(こちらを向いた鉄ゴーレム)には待つ間(8 秒)、自分からは返し技(小突き・突き掻き・切り上げ)しか始めない。
     * 連続技の 2 段目は数えない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 130)
    public static void cautiousOnlyCountersWhileWaiting(GameTestHelper helper) {
        FalcimnesEntity f = helper.spawn(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, 1));
        f.snapFacing(0.0F);
        f.setPersonality(1.0F, 0.5F, 0.5F);
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 4));
        golem.setYRot(180.0F);
        golem.setYHeadRot(180.0F);
        golem.yBodyRot = 180.0F;
        f.setTarget(golem);
        int[] prev = {FalcimnesEntity.ACT_NONE};
        int[] bad = {-1};
        helper.onEachTick(() -> {
            int act = f.getAction();
            if (prev[0] == FalcimnesEntity.ACT_NONE && act != FalcimnesEntity.ACT_NONE) {
                boolean counter = act == FalcimnesEntity.ACT_KOZUKI_R || act == FalcimnesEntity.ACT_KOZUKI_L || act == FalcimnesEntity.ACT_TSUKIKAKI
                        || act == FalcimnesEntity.ACT_KIRIAGE_R || act == FalcimnesEntity.ACT_KIRIAGE_L;
                boolean neutral = act == FalcimnesEntity.ACT_THREAT || act == FalcimnesEntity.ACT_DODGE
                        || act == FalcimnesEntity.ACT_DODGE_L || act == FalcimnesEntity.ACT_DODGE_R;
                if (!counter && !neutral && bad[0] < 0) bad[0] = act;
            }
            prev[0] = act;
        });
        helper.runAfterDelay(120L, () -> {
            if (bad[0] >= 0) {
                helper.fail("慎重な個体が待っている間に返し技でない技(動作 " + bad[0] + ")を自分から始めた");
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 森の中を動く型 ② の段 1(11-08): 細い芯と叩ける部位

    /**
     * 幹と幹の間の 2 マスの隙間を、経路を引いて抜けられる(前の 2.97 の箱では 3 マスの隙間が要った)。
     * テスト場を横切る高さ 4 の丸太の壁(z = 3)に、x = 3・4 の 2 マスだけ隙間を空け、奥(z = 5)へ歩かせる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void coreSlipsThroughTwoBlockGapBetweenTrunks(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            if (x == 3 || x == 4) continue;
            for (int y = 2; y <= 5; y++) {
                helper.setBlock(new BlockPos(x, y, 3), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            }
        }
        FalcimnesEntity f = placeAt(helper, 1);
        BlockPos goalRel = new BlockPos(4, 2, 5);
        Vec3 goal = helper.absoluteVec(new Vec3(4.0D, 2.0D, 5.5D));
        helper.runAfterDelay(2L, () -> {
            net.minecraft.world.level.pathfinder.Path path = f.getNavigation().createPath(helper.absolutePos(goalRel), 0);
            if (path == null || !path.canReach()) {
                helper.fail("2 マスの隙間の向こうへ経路が引けない(path " + (path == null ? "null" : "届かない") + ")");
                return;
            }
            f.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
        });
        helper.onEachTick(() -> {
            if (f.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.6D)).z) {
                helper.succeed();
            }
        });
    }

    /**
     * 叩ける部位: 本体の id の続き番号で 9 個あり、待機の頭は体の前 5.6 ブロック(体格 1)。部位を殴ると本体が減る。
     * 同じ tick に別の部位を同じ強さで殴っても、無敵時間で 2 回は減らない(1 撃を 2 つの部位が拾っても二重に入らない)。
     * 生き物の検索(LivingEntity)には部位は入らない(自分の鎌の当たり・飛ぶ斬撃が自分の部位を拾わない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hitPartsForwardDamageToBody(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        f.setInvulnerable(false);
        helper.runAfterDelay(2L, () -> {
            var parts = f.getParts();
            if (parts.length != 9) {
                helper.fail("部位が 9 個でない(軌道表が読めていない?): " + parts.length);
                return;
            }
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].getId() != f.getId() + 1 + i) {
                    helper.fail("部位 " + i + " の id が本体の続き番号でない");
                    return;
                }
                if (helper.getLevel().getEntityOrPart(parts[i].getId()) != parts[i]) {
                    helper.fail("部位 " + i + " を世界が id で引けない(NeoForge の部位の台帳に載っていない)");
                    return;
                }
            }
            var head = f.hitParts().part(0);
            double fwd = head.getZ() - f.getZ();   // 向き 0 = +Z が前
            if (Math.abs(fwd - 5.6D * f.getScale()) > 0.1D || Math.abs(head.getX() - f.getX()) > 0.3D) {   // 待機の揺れで頭は横へ 0.1 ほど動く
                helper.fail("頭の部位が体の前 5.6 にない: 前 " + fwd + " 横 " + (head.getX() - f.getX()) + " 向き " + f.getYRot() + " | " + f.partDebug());
                return;
            }
            var living = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, head.getBoundingBox().inflate(0.1D));
            if (living.stream().anyMatch(e -> e != f && !(e instanceof Player))) {
                helper.fail("生き物の検索に部位以外の何かが入った: " + living);
                return;
            }
            if (!helper.getLevel().getEntities(f, head.getBoundingBox()).contains(head)) {
                helper.fail("エンティティの検索に頭の部位が入らない(照準・飛び道具が当たらない)");
                return;
            }
            float before = f.getHealth();
            DamageSource src = helper.getLevel().damageSources().magic();
            boolean hit = head.hurt(src, 6.0F);
            float afterHead = f.getHealth();
            f.hitParts().part(f.hitParts().indexOf("abdomen_a")).hurt(src, 6.0F);   // 腹
            float afterBelly = f.getHealth();
            if (!hit || afterHead >= before) {
                helper.fail("頭の部位を殴っても本体が減らない: " + before + " → " + afterHead);
                return;
            }
            if (afterBelly < afterHead) {
                helper.fail("同じ tick に 2 つ目の部位を同じ強さで殴ったら二重に減った: " + afterHead + " → " + afterBelly);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 部位は技の構えに付いていく(サーバーはアニメを流さないので、アニメから作った軌道表を同期した動作と時刻で引く)。
     * 威嚇の 1.5 秒で頭の部位の底が 4 より上(待機は 2.2)、振りかざした左の刃の箱の底が 5 より上。
     * 横薙ぎ(右)の当たり(0.80 秒)で左の刃の箱が体の前 6 より先(待機は 1.2)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void hitPartsFollowMovePoses(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        int head = f.hitParts().indexOf("head"), tip = f.hitParts().indexOf("blade_l");
        double[] low = new double[1];
        helper.runAfterDelay(2L, () -> {
            low[0] = f.hitParts().part(head).getY() - f.getY();
            if (Math.abs(low[0] - 2.1D * f.getScale()) > 0.25D) {   // 待機の揺れで頭は上下に 0.1 ほど動く(箱の高さ 1.1)
                helper.fail("待機の頭の部位の底が 2.1 前後でない: " + low[0]);
                return;
            }
            f.forceAction(FalcimnesEntity.ACT_THREAT);
        });
        // 動作の切り替えの補間 4 tick + 1.5 秒(forceAction は次の aiStep で始まる)
        helper.runAfterDelay(2L + 1L + 4L + 30L, () -> {
            double high = f.hitParts().part(head).getY() - f.getY();
            double tipUp = f.hitParts().part(tip).getY() - f.getY();
            if (f.getAction() != FalcimnesEntity.ACT_THREAT) {
                helper.fail("威嚇が続いていない(動作 " + f.getAction() + ")");
                return;
            }
            if (high < 4.0D * f.getScale() || tipUp < 5.0D * f.getScale()) {
                helper.fail("威嚇の構えに部位が付いていかない: 頭の底 " + high + " 刃先の底 " + tipUp);
                return;
            }
            f.forceAction(FalcimnesEntity.ACT_ATTACK_R);
        });
        helper.runAfterDelay(37L + 1L + 4L + 16L, () -> {
            double fwd = f.hitParts().part(tip).getZ() - f.getZ();
            if (f.getAction() != FalcimnesEntity.ACT_ATTACK_R) {
                helper.fail("横薙ぎが続いていない(動作 " + f.getAction() + ")");
                return;
            }
            if (fwd < 6.0D * f.getScale()) {
                helper.fail("横薙ぎの当たりで刃の先の部位が前に出ていない: 前 " + fwd);
                return;
            }
            helper.succeed();
        });
    }

    /** 部位でも会心が出る(跳んで溜めきって殴ると 1.5 倍)。芯と同じ条件(11-08 ユーザー決定)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hitPartsTakeCriticalHits(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        Player p = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        helper.runAfterDelay(2L, () -> {
            var part = f.hitParts().part(f.hitParts().indexOf("abdomen_a"));
            float before = f.getHealth();
            swing(p, part, false);
            float plain = before - f.getHealth();
            f.invulnerableTime = 0;
            float mid = f.getHealth();
            swing(p, part, true);
            float crit = mid - f.getHealth();
            if (plain <= 0.0F) {
                helper.fail("部位を殴っても減らない");
                return;
            }
            if (crit < plain * 1.4F) {
                helper.fail("跳んで殴っても会心にならない: 普通 " + plain + " / 跳んで " + crit);
                return;
            }
            helper.succeed();
        });
    }

    /** 溜めきった一撃を部位へ(跳んでいれば落ちている途中)。 */
    private static void swing(Player p, net.minecraft.world.entity.Entity target, boolean jumping) {
        try {
            java.lang.reflect.Field fld = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
            fld.setAccessible(true);
            fld.setInt(p, 100);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        p.setOnGround(!jumping);
        p.fallDistance = jumping ? 1.0F : 0.0F;
        p.setSprinting(false);
        p.attack(target);
    }

    /** 火の矢・火属性: 部位に付けた火は本体が燃える(部位は tick しない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hitPartsPassFireToBody(GameTestHelper helper) {
        FalcimnesEntity f = place(helper);
        helper.runAfterDelay(2L, () -> {
            var part = f.hitParts().part(f.hitParts().indexOf("head"));
            part.setSecondsOnFire(4); // 1.20.1: igniteForSeconds は無い
            if (f.getRemainingFireTicks() < 60) {
                helper.fail("部位に付けた火が本体に移らない: " + f.getRemainingFireTicks());
                return;
            }
            if (part.getRemainingFireTicks() != f.getRemainingFireTicks()) {
                helper.fail("部位の火の残りが本体と違う");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 叩ける部位は性格の構えにも付いていく(11-08 夜、ユーザー「頭や首が下に下がらなかった」): 慎重な個体(首を垂らす・低い)の頭の部位は
     * 真ん中の個体より 0.6 以上低く、荒い個体(首を起こす・高い)は 0.6 以上高い。性格は同期されるので両側で同じ。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hitPartsFollowPersonalityPosture(GameTestHelper helper) {
        FalcimnesEntity mid = placeAt(helper, 1), low = placeAt(helper, 3), high = placeAt(helper, 5);
        low.setPersonality(1.0F, 0.0F, 0.5F);
        high.setPersonality(0.5F, 1.0F, 0.5F);
        helper.runAfterDelay(12L, () -> {
            int h = mid.hitParts().indexOf("head");
            double m = mid.hitParts().part(h).getY() - mid.getY(), l = low.hitParts().part(h).getY() - low.getY(),
                    u = high.hitParts().part(h).getY() - high.getY();
            if (l > m - 0.6D || u < m + 0.6D) {
                helper.fail(String.format(java.util.Locale.ROOT, "頭の部位が性格の構えに付いていかない: 慎重 %.2f / 真ん中 %.2f / 荒い %.2f", l, m, u));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 坂・段で胴が傾くと、叩ける部位も傾く(11-08 夜、ユーザー「坂で下がる頭の部位も付いていかせる」)。サーバーも描画と同じ足の置き方を動かす。
     * 2 段の台(後ろ足の下)から前が 2 段下がる所に立たせると、胴が前へ傾き、頭の部位は平らな所(待機の表の値)より 0.5 以上低い。
     * テスト場は脚(左右 2.4)の幅で 2 体並べられないので、平らな所の値は表から引く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void hitPartsTiltDownSlope(GameTestHelper helper) {
        // 幅いっぱい(x 0〜6)・z 0〜1 に 2 段の台。z 2 から先は床のまま(2 段下がる)
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 1; z++) {
                helper.setBlock(new BlockPos(x, 2, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 3, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        FalcimnesEntity slope = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 4, 1));
        slope.snapFacing(0.0F);
        slope.setPersonality(0.5F, 0.5F, 0.5F);
        slope.setNoAi(true);
        slope.terrainForced = true;   // テストのサーバーにはプレイヤーがいない(足の置き方は近くにプレイヤーがいる個体だけ)
        helper.runAfterDelay(40L, () -> {
            var track = com.yayuy.overgrownvegetation.entity.FalcimnesPartTrack.get();
            int h = slope.hitParts().indexOf("head");
            double[] idle = new double[track.count() * 3];
            track.sample("idle", 0.0D, idle);
            double level = (idle[h * 3 + 2] - slope.hitParts().part(h).getBbHeight() * 0.5D / slope.getScale()) * slope.getScale();
            double down = slope.hitParts().part(h).getY() - slope.getY();
            float[] tilt = slope.terrainTilt();
            OvergrownVegetation.LOGGER.info("TERRAIN_TILT 傾き {} 頭の部位の底 坂 {} 平ら {} / サーバーの足の置き方 {} 回 平均 {} µs", java.util.Arrays.toString(tilt), down, level,
                    FalcimnesEntity.terrainCalls, FalcimnesEntity.terrainNanos / 1000.0D / Math.max(1, FalcimnesEntity.terrainCalls));
            if (tilt[0] > -5.0F) {
                helper.fail("前が 2 段下がっているのに胴が前へ傾かない: " + java.util.Arrays.toString(tilt));
                return;
            }
            if (down > level - 0.5D) {
                helper.fail(String.format(java.util.Locale.ROOT, "胴が傾いても頭の部位が下がらない: 坂 %.2f / 平ら %.2f(傾き %s)",
                        down, level, java.util.Arrays.toString(tilt)));
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 森の中を動く型 ② の段 3(11-08 夜)

    /** 近くにプレイヤーがいない個体は足の置き方を計算せず、胴の傾きは平ら(0)のまま(11-08 夜、ユーザー「近くにプレイヤーがいる個体だけ計算」)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 50)
    public static void terrainOnlyNearPlayers(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 1; z++) {
                helper.setBlock(new BlockPos(x, 2, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 3, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 4, 1));
        f.snapFacing(0.0F);
        f.setPersonality(0.5F, 0.5F, 0.5F);
        f.setNoAi(true);
        helper.runAfterDelay(40L, () -> {
            float[] t = f.terrainTilt();
            if (t[0] != 0.0F || t[1] != 0.0F || t[2] != 0.0F) {
                helper.fail("近くにプレイヤーがいないのに胴を傾けた: " + java.util.Arrays.toString(t));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 体より狭い隙間(幹と幹の間 2 マス)に立つと、前を起こして細くなる構えになり、頭の部位が上がる(威嚇の起こしの 7 割、表で +1.3)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void squeezeInTwoBlockGap(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            if (x == 3 || x == 4) continue;
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, 3), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        }
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, 1));
        f.setPersonality(0.5F, 0.5F, 0.5F);
        f.setNoAi(true);
        f.terrainForced = true;   // テストのサーバーにはプレイヤーがいない(構えは近くにプレイヤーがいる個体だけ)
        Vec3 gap = helper.absoluteVec(new Vec3(4.0D, 2.0D, 3.5D));
        f.moveTo(gap.x, gap.y, gap.z, 0.0F, 0.0F);
        f.snapFacing(0.0F);
        int h = f.hitParts().indexOf("head");
        helper.runAfterDelay(40L, () -> {
            if (f.squeeze(1.0F) < 0.9F || f.duck(1.0F) > 0.1F) {
                helper.fail("2 マスの隙間で狭い所の構えにならない: 細く " + f.squeeze(1.0F) + " 首を下げる " + f.duck(1.0F));
                return;
            }
            var track = com.yayuy.overgrownvegetation.entity.FalcimnesPartTrack.get();
            double[] idle = new double[track.count() * 3];
            track.sample("idle", 0.0D, idle);
            double flat = (idle[h * 3 + 2] - f.hitParts().part(h).getBbHeight() * 0.5D / f.getScale()) * f.getScale();
            double up = f.hitParts().part(h).getY() - f.getY();
            if (up < flat + 0.8D) {
                helper.fail(String.format(java.util.Locale.ROOT, "前を起こしても頭の部位が上がらない: %.2f(待機 %.2f)", up, flat));
                return;
            }
            helper.succeed();
        });
    }

    /** 頭の上(前 3.5〜5.5、足元から 2 段上)に葉があると首を下げる構えになり、頭の部位が下がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 50)
    public static void duckUnderLowLeaves(GameTestHelper helper) {
        FalcimnesEntity f = placeAt(helper, 0);   // z 0.5 から +Z を向く: 頭の上を見る所は z 4.0〜6.0
        f.setNoAi(true);
        f.terrainForced = true;
        int h = f.hitParts().indexOf("head");
        double[] before = {Double.NaN};
        helper.runAfterDelay(5L, () -> {
            before[0] = f.hitParts().part(h).getY() - f.getY();
            for (int x = 2; x <= 4; x++) {
                for (int z = 4; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, 4, z), net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                }
            }
        });
        helper.runAfterDelay(30L, () -> {
            double ducked = f.hitParts().part(h).getY() - f.getY();
            if (f.duck(1.0F) < 0.9F) {
                helper.fail("頭の上に葉があるのに首を下げない: " + f.duck(1.0F));
                return;
            }
            if (ducked > before[0] - 0.8D) {
                helper.fail(String.format(java.util.Locale.ROOT, "首を下げても頭の部位が下がらない: %.2f → %.2f", before[0], ducked));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 片側に幹が 1 本あるだけでは狭い所の構えに入らない(11-08 夜、ユーザー「ブロックがあるだけで鎌を後ろに回したり戻したりしすぎ」)。
     * 体のすぐ左(x 2)に高さ 4 の幹を立て、右は開けておく。40 tick の間、構えの効きは 0 のまま。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void singleTrunkDoesNotSqueeze(GameTestHelper helper) {
        for (int z = 1; z <= 4; z++) {
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(5, y, z), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        }
        FalcimnesEntity f = placeAt(helper, 1);
        f.setNoAi(true);
        f.terrainForced = true;
        float[] max = {0.0F};
        helper.onEachTick(() -> max[0] = Math.max(max[0], f.squeeze(1.0F)));
        helper.runAfterDelay(45L, () -> {
            if (max[0] > 0.0F) {
                helper.fail("片側に幹があるだけで狭い所の構えに入った: " + max[0]);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 曲げは急な動きで飛ばない(11-08 夜、ユーザー「曲がったりするときに体の後ろ半分が変の方向に吹き飛ぶ」の再現の見張り)。
     * 歩かせながら、その場で 180 度向きを変え、4 ブロック瞬間移動させる。毎 tick、曲げの角が上限(70 度)を超えず、1 tick に 10 度より大きく
     * 変わらず、叩ける部位が芯から体の長さ(頭 6.2)より遠くへ離れないことを見る。
     * (描画の骨の値が毎フレーム積み重なって飛んでいた本当の原因は描画の中なので、GameTest では見られない。テストクライアントで見た)
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void bendSurvivesUTurnAndTeleport(GameTestHelper helper) {
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(1, 2, 1));
        f.snapFacing(0.0F);
        f.setPersonality(0.5F, 0.5F, 0.5F);
        Vec3 goal = helper.absoluteVec(new Vec3(5.0D, 2.0D, 5.5D));
        float[] prev = new float[8];
        String[] bad = {null};
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t > 2 && f.getNavigation().isDone() && t < 90) f.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
            if (t == 30) f.snapFacing(f.getYRot() + 180.0F);   // その場で 180 度
            if (t == 55) {
                Vec3 to = helper.absoluteVec(new Vec3(1.5D, 2.0D, 5.5D));
                f.teleportTo(to.x, to.y, to.z);   // 瞬間移動
            }
            float[] a = new float[8];
            f.bend().angles(1.0F, a);
            for (int k = 0; k < 8; k++) {
                if (Math.abs(a[k]) > 70.5F) bad[0] = "角が上限を超えた: " + java.util.Arrays.toString(a);
                if (t > 3 && Math.abs(a[k] - prev[k]) > 10.5F) bad[0] = "1 tick に大きく変わった: " + java.util.Arrays.toString(prev) + " → " + java.util.Arrays.toString(a);
            }
            System.arraycopy(a, 0, prev, 0, 8);
            for (var part : t < 2 ? new net.minecraftforge.entity.PartEntity<?>[0] : f.getParts()) {   // 部位は最初の aiStep で置かれる
                double dx = part.getX() - f.getX(), dz = part.getZ() - f.getZ();
                if (dx * dx + dz * dz > 6.5D * 6.5D * f.getScale() * f.getScale()) bad[0] = "部位が芯から離れた: " + Math.sqrt(dx * dx + dz * dz);
            }
            if (bad[0] != null) {
                helper.fail(bad[0] + "(tick " + t + ")");
                return;
            }
            if (t >= 110) helper.succeed();
        });
    }

    /**
     * この MOD の葉(木生シダの葉)の生け垣を、荒い個体は経路の上でも押し分けて近道し、慎重な個体は開いた所を回り込む
     * (11-08 夜、ユーザー「葉のタグを直す」。経路の評価器が MOD の葉を葉として見るようにした)。
     * z = 3 の x 0〜4 を高さ 4 の葉で塞ぎ、x 5〜6 の 2 マスだけ開ける。左下 (1.5, 1.5) から左上 (1.5, 5.5) への経路を見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void modLeavesPathByPersonality(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, 3), com.yayuy.overgrownvegetation.registry.ModBlocks.TREE_FERN_LEAVES.get().defaultBlockState());
        }
        FalcimnesEntity bold = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(1, 2, 1));
        FalcimnesEntity careful = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(1, 2, 1));
        bold.setPersonality(0.5F, 1.0F, 0.5F);
        careful.setPersonality(1.0F, 0.0F, 0.5F);
        BlockPos goal = helper.absolutePos(new BlockPos(1, 2, 5));
        BlockPos hedgeLeft = helper.absolutePos(new BlockPos(0, 2, 3));
        helper.runAfterDelay(2L, () -> {
            var pb = bold.getNavigation().createPath(goal, 0);
            var pc = careful.getNavigation().createPath(goal, 0);
            if (pb == null || !pb.canReach() || pc == null || !pc.canReach()) {
                helper.fail("経路が引けない: 荒い " + (pb == null ? "null" : pb.canReach()) + " / 慎重 " + (pc == null ? "null" : pc.canReach()));
                return;
            }
            // 生け垣の列(z = 3)を通るノードの x(足場の角。体は x 〜 x + 1)
            int boldX = Integer.MIN_VALUE, carefulX = Integer.MIN_VALUE;
            for (int i = 0; i < pb.getNodeCount(); i++) if (pb.getNode(i).z == hedgeLeft.getZ()) boldX = pb.getNode(i).x - hedgeLeft.getX();
            for (int i = 0; i < pc.getNodeCount(); i++) if (pc.getNode(i).z == hedgeLeft.getZ()) carefulX = pc.getNode(i).x - hedgeLeft.getX();
            if (boldX == Integer.MIN_VALUE || boldX > 3) {
                helper.fail("荒い個体が葉を押し分けて近道しない(生け垣の列を通る x = " + boldX + ")");
                return;
            }
            if (carefulX < 5) {
                helper.fail("慎重な個体が葉の中を通る経路を引いた(生け垣の列を通る x = " + carefulX + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 回り道が経路を探す範囲の外にあっても、慎重な個体は止まらずに回り込む(11-09、ユーザー「直す」)。普段の探索が届かないときだけ、
     * 粗い下調べで回り道を見つけ、その途中の目標まで普段の探索でつなぐ(11-09 夕、広げた探し直しの代わり)。テスト場に 25 マスの生け垣は
     * 置けないので、探す距離(FOLLOW_RANGE)を 3 に縮めて「普段の探索では回り道が範囲の外」を作る。生け垣は {@link #modLeavesPathByPersonality}
     * と同じ(x 0〜4 を葉、x 5〜6 が開いている)。経路の終わりへ動かしては引き直し、目標に届くまで続ける(歩く代わり)。どの経路も葉を通らない。
     * 最初に頼んだ直後にもう一度頼むと、間隔を空けるので下調べしない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void cautiousWidensSearchToGoAround(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, 3), com.yayuy.overgrownvegetation.registry.ModBlocks.TREE_FERN_LEAVES.get().defaultBlockState());
        }
        FalcimnesEntity careful = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(1, 2, 1));
        careful.setPersonality(1.0F, 0.0F, 0.5F);
        careful.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(3.0D);
        BlockPos goal = helper.absolutePos(new BlockPos(1, 2, 5));
        BlockPos hedgeLeft = helper.absolutePos(new BlockPos(0, 2, 3));
        FalcimnesEntity.detourTrace = true;
        long before = FalcimnesEntity.widePathFound;
        helper.runAfterDelay(2L, () -> aroundStep(helper, careful, goal, hedgeLeft, before, 0, 0));
    }

    /**
     * {@link #cautiousWidensSearchToGoAround} の 1 手順(1 tick に 1 つ)。回り道の予算(サーバー全体で 1 tick 2 ms)を同じ tick のほかのテストや
     * 前の手順(JIT 前で重い)が使い切っていたら、次の tick にやり直す(予算が効いている印なので失敗にしない)。
     */
    private static void aroundStep(GameTestHelper helper, FalcimnesEntity careful, BlockPos goal, BlockPos hedgeLeft, long before, int step, int waits) {
        if (step >= 12 || waits > 40) {
            helper.fail("12 回動かしても目標に届かない(位置 " + careful.blockPosition() + "、予算待ち " + waits + ")");
            return;
        }
        long deferred = com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.deferred;
        var p = careful.getNavigation().createPath(goal, 0);
        if (com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.deferred != deferred) {
            helper.runAfterDelay(1L, () -> aroundStep(helper, careful, goal, hedgeLeft, before, step, waits + 1));
            return;
        }
        if (p == null) {
            helper.fail("回り込む途中で経路が無くなった(" + step + " 回目、位置 " + careful.blockPosition() + ")");
            return;
        }
        for (int i = 0; i < p.getNodeCount(); i++) {
            var nd = p.getNode(i);
            if (nd.z == hedgeLeft.getZ() && nd.x - hedgeLeft.getX() < 5) {
                helper.fail("慎重な個体の経路が葉を通った(生け垣の列で x = " + (nd.x - hedgeLeft.getX()) + "、" + step + " 回目)");
                return;
            }
        }
        if (step == 0) {
            long calls = FalcimnesEntity.widePathCalls;
            careful.getNavigation().createPath(goal, 0);
            if (FalcimnesEntity.widePathCalls != calls) {
                helper.fail("続けて頼むと間隔を空けずにまた回り道を探した");
                return;
            }
        }
        if (p.canReach() && goal.equals(p.getTarget())) {
            if (FalcimnesEntity.widePathFound <= before) {
                helper.fail("普段の探索で届いた(テストの場面が「範囲の外」になっていない)");
                return;
            }
            FalcimnesEntity.detourTrace = false;
            helper.succeed();
            return;
        }
        var end = p.getEntityPosAtNode(careful, p.getNodeCount() - 1);
        if (end.distanceToSqr(careful.position()) < 0.25D) {
            helper.fail("回り道が見つからず進めない(" + step + " 回目、位置 " + careful.blockPosition() + "、届く " + p.canReach() + "、最後の回り道 "
                    + FalcimnesEntity.lastDetour + "、経路の終わり " + p.getEndNode().asBlockPos() + ")");
            return;
        }
        careful.moveTo(end.x, end.y, end.z);
        helper.runAfterDelay(1L, () -> aroundStep(helper, careful, goal, hedgeLeft, before, step + 1, waits));
    }

    /**
     * 荒い個体は足場の葉を押し分けない(11-08 夜遅く、本物の木生シダの森で葉の上の荒い個体が 10 ブロック落ちた)。
     * 床の上に MOD の葉を 1 段敷き、その下(床)との間に空気を 1 段空けて、葉の上に荒い個体を立たせる。40 tick たっても落ちない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void aggressiveStandsOnLeafFloor(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) helper.setBlock(new BlockPos(x, 3, z), com.yayuy.overgrownvegetation.registry.ModBlocks.TREE_FERN_LEAVES.get().defaultBlockState());
        }
        FalcimnesEntity bold = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 4, 3));
        bold.setPersonality(0.5F, 1.0F, 0.5F);
        double y0 = bold.getY();
        helper.runAfterDelay(40L, () -> {
            if (!bold.pushesThroughLeaves()) {
                helper.fail("荒い個体が葉を押し分ける個体になっていない");
                return;
            }
            if (bold.getY() < y0 - 0.5D) {
                helper.fail(String.format(java.util.Locale.ROOT, "葉の床を抜けて落ちた: %.2f → %.2f", y0, bold.getY()));
                return;
            }
            helper.succeed();
        });
    }

    /** 荒い個体は葉を押し分けて通る(葉の当たりが空になる)。慎重な個体には当たる。ブロックは壊さない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10)
    public static void aggressivePushesThroughLeaves(GameTestHelper helper) {
        BlockPos leaf = new BlockPos(3, 2, 5);
        helper.setBlock(leaf, net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        FalcimnesEntity bold = placeAt(helper, 1), careful = placeAt(helper, 1);
        bold.setPersonality(0.5F, 1.0F, 0.5F);
        careful.setPersonality(1.0F, 0.0F, 0.5F);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(helper.absolutePos(leaf)).deflate(0.1D);
        helper.runAfterDelay(1L, () -> {
            boolean boldFree = helper.getLevel().noCollision(bold, box), carefulFree = helper.getLevel().noCollision(careful, box);
            if (!boldFree || carefulFree) {
                helper.fail("葉の当たり: 荒い個体 " + (boldFree ? "通る" : "当たる") + " / 慎重な個体 " + (carefulFree ? "通る" : "当たる"));
                return;
            }
            if (!helper.getBlockState(leaf).is(net.minecraft.tags.BlockTags.LEAVES)) {
                helper.fail("葉が無くなった");
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 森の中を動く型 ② の段 2(11-08): 森の地図・体の曲げ

    /**
     * 森の地図の「一番近い幹までの距離」: 丸太の柱 (3, 3) の上は 0、隣は 0.5、2 つ隣は 1.5(列の中心どうし − 0.5)。
     * 区画は裏のスレッドで作るので、最初は「分からない」(NaN)で、少し待つと値が入る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void forestMapMeasuresTrunkDistance(GameTestHelper helper) {
        for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(3, y, 3), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        var map = com.yayuy.overgrownvegetation.entity.forest.ForestMap.of(helper.getLevel());
        BlockPos trunk = helper.absolutePos(new BlockPos(3, 2, 3));
        // 3 つのマスは別の区画にまたがることがあるので、どれも最初に頼み、値が入るまで待つ(裏のスレッドは他のテストの区画も作る)
        helper.onEachTick(() -> {
            if (helper.getTick() < 2) return;
            float on = map.clearance(trunk.getX(), trunk.getY(), trunk.getZ());
            float next = map.clearance(trunk.getX() + 1, trunk.getY(), trunk.getZ());
            float two = map.clearance(trunk.getX(), trunk.getY(), trunk.getZ() - 2);
            if (Float.isNaN(on) || Float.isNaN(next) || Float.isNaN(two)) {
                if (helper.getTick() > 110) helper.fail("森の地図の区画ができない(裏のスレッド)");   // 裏のスレッドはほかのテストの区画も作るので、混むと遅れる
                return;
            }
            if (on != 0.0F || Math.abs(next - 0.5F) > 0.01F || Math.abs(two - 1.5F) > 0.01F) {
                helper.fail("幹までの距離が違う: 上 " + on + " 隣 " + next + " 2 つ隣 " + two);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 体の曲げの形: 首を左へ曲げると頭・首の部位が左へ、腹を左へ曲げると腹の部位が左へ寄る。まっすぐなら部位は動かない。
     * (同じ計算をサーバーの当たりとクライアントの照準の両方が使う。)
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10)
    public static void bendMovesPartsSideways(GameTestHelper helper) {
        var track = com.yayuy.overgrownvegetation.entity.FalcimnesPartTrack.get();
        int n = track.count();
        double[] in = new double[n * 3], out = new double[n * 3];
        if (!track.sample("idle", 0.0D, in)) {
            helper.fail("待機の軌道表が無い");
            return;
        }
        var bend = new com.yayuy.overgrownvegetation.entity.FalcimnesBend();
        bend.clientTick(0L);
        bend.bendParts(in, out, track.names);
        if (!java.util.Arrays.equals(in, out)) {
            helper.fail("曲げ 0 で部位が動いた");
            return;
        }
        bend.clientTick(com.yayuy.overgrownvegetation.entity.FalcimnesBend.pack(new float[]{10, 20, 30, 40, 10, 20, 30, 40}));
        bend.bendParts(in, out, track.names);
        int head = java.util.Arrays.asList(track.names).indexOf("head"), tail = java.util.Arrays.asList(track.names).indexOf("abdomen_b");
        double headLeft = out[head * 3 + 1] - in[head * 3 + 1], tailLeft = out[tail * 3 + 1] - in[tail * 3 + 1];
        if (headLeft < 1.0D || tailLeft < 0.3D) {
            helper.fail("左へ曲げても部位が左へ寄らない: 頭 " + headLeft + " 腹 " + tailLeft);
            return;
        }
        // 骨の長さは変わらない(頭は胴の中心から 5.6 前後のまま。曲げた分だけ前へは縮む)
        double r = Math.hypot(out[head * 3], out[head * 3 + 1]);
        if (r > Math.hypot(in[head * 3], in[head * 3 + 1]) + 0.05D || out[head * 3] >= in[head * 3]) {
            helper.fail("曲げた頭の部位が伸びた・前へ出た: " + out[head * 3] + ", " + out[head * 3 + 1]);
            return;
        }
        helper.succeed();
    }

    /**
     * 歩いている間は首と頭が先の経路に沿って曲がり、頭の部位は曲げた向きの側へ寄る。着いて止まると曲げは 0 に戻る。
     * 丸太の壁(z = 3、x = 0〜3)を避けて、左下 (1.5, 1.5) から右上 (5, 5.5) へ回り込ませる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void walkingBendsAlongPathAndStraightens(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, 3), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        }
        FalcimnesEntity f = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(1, 2, 1));
        f.snapFacing(0.0F);
        f.setPersonality(0.5F, 0.5F, 0.5F);
        Vec3 goal = helper.absoluteVec(new Vec3(5.0D, 2.0D, 5.5D));
        float[] max = {0.0F};
        boolean[] sideOk = {true};
        int[] arrived = {-1};
        int head = f.hitParts().indexOf("head");
        helper.runAfterDelay(2L, () -> f.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D));
        helper.onEachTick(() -> {
            float[] a = new float[8];
            f.bend().angles(1.0F, a);
            float front = a[3];
            if (Math.abs(front) > Math.abs(max[0])) {
                max[0] = front;
                // 頭の部位が体のどちら側にあるか(左 = 向きの左)
                float yaw = f.getYRot() * net.minecraft.util.Mth.DEG_TO_RAD;
                var hp = f.hitParts().part(head);
                double left = (hp.getX() - f.getX()) * net.minecraft.util.Mth.cos(yaw) + (hp.getZ() - f.getZ()) * net.minecraft.util.Mth.sin(yaw);
                // 首が S 字のときは頭の位置と頭の向きの側が食い違ってよいので、首の 4 つが同じ側へ曲がっているときだけ見る
                boolean oneSide = Math.signum(a[0]) == Math.signum(a[3]) && Math.signum(a[1]) == Math.signum(a[3]) && Math.signum(a[2]) == Math.signum(a[3]);
                if (Math.abs(front) > 10.0F && oneSide && Math.signum(left) != Math.signum(front)) sideOk[0] = false;
            }
            if (arrived[0] < 0 && f.distanceToSqr(goal) < 4.0D) arrived[0] = (int) helper.getTick();   // 経路は目的地の手前 1〜2 ブロックで終わることがある
            // 標的のいない個体は待機の仕草を始めると経路を止めるので、着くまで引き直す
            if (arrived[0] < 0 && helper.getTick() > 2 && f.getNavigation().isDone()) f.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
            if (arrived[0] < 0 && helper.getTick() > 190) helper.fail(String.format(java.util.Locale.ROOT, "着かない: 位置 %.2f %.2f 動作 %d", f.getX() - goal.x, f.getZ() - goal.z, f.getAction()));
            if (arrived[0] >= 0 && helper.getTick() > arrived[0] + 30) {
                if (Math.abs(max[0]) < 5.0F) {
                    helper.fail("歩いている間に首が曲がらない(最大 " + max[0] + " 度)");
                } else if (!sideOk[0]) {
                    helper.fail("頭の部位が首を曲げた側と逆にある");
                } else if (f.bend().packed() != 0L) {
                    helper.fail("止まっても曲げが 0 に戻らない");
                } else {
                    OvergrownVegetation.LOGGER.info("FOREST_COST 地図の写し {} 回 平均 {} µs / 裏の計算 合計 {} µs / 経路の重み {} 回 平均 {} µs / 曲げ {} 回 平均 {} µs",
                            com.yayuy.overgrownvegetation.entity.forest.ForestMap.snapshotCount,
                            com.yayuy.overgrownvegetation.entity.forest.ForestMap.snapshotNanos / 1000.0D / Math.max(1, com.yayuy.overgrownvegetation.entity.forest.ForestMap.snapshotCount),
                            com.yayuy.overgrownvegetation.entity.forest.ForestMap.buildNanos / 1000.0D,
                            com.yayuy.overgrownvegetation.entity.OvergrownNodeEvaluator.longBodyCalls,
                            com.yayuy.overgrownvegetation.entity.OvergrownNodeEvaluator.longBodyNanos / 1000.0D / Math.max(1, com.yayuy.overgrownvegetation.entity.OvergrownNodeEvaluator.longBodyCalls),
                            com.yayuy.overgrownvegetation.entity.FalcimnesBend.serverCalls,
                            com.yayuy.overgrownvegetation.entity.FalcimnesBend.serverNanos / 1000.0D / Math.max(1, com.yayuy.overgrownvegetation.entity.FalcimnesBend.serverCalls));
                    helper.succeed();
                }
            }
        });
    }
}
