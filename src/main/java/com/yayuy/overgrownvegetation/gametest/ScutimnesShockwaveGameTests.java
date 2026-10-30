package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ScutimnesShockwave;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * スクティムネスの<b>叩きつけの衝撃波</b>({@link ScutimnesShockwave})のテスト(2026-09-23)。
 * {@code gradlew.bat runGameTestServer} で実行(ヘッドレス)。テンプレートは {@code gametest_empty7}(7×7×7、床は相対 y=1、立つのは y=2)。
 *
 * <p>本体が無くても動くように、持ち主には AI を止めた豚を渡す({@link ScutimnesShockwave#spawn} は {@link LivingEntity} なら何でも受ける)。
 * 的も体力 100 の豚(防具 0・ノックバック耐性 0 で、当たった量がそのまま体力に出る)。守りたいのは仕様の数字:
 * <ul>
 *     <li>近い相手ほど強く当たる(ダメージ = 溜めた量 × {@link ScutimnesShockwave#falloff}、距離は体の縁まで)。</li>
 *     <li>6 ブロックの外・扇の外(横・後ろ)には当たらない。</li>
 *     <li>波が足元を通るときに 0.5 以上浮いていれば当たらない(0.4 / 0.6 で挟む)。地面に居る乗り物の上・ボートの上に立つ相手は当たる。</li>
 *     <li>1 体に 1 回だけ。持ち主と仲間(汚染側のグループ)には当たらない。</li>
 *     <li>溜めた量 0 なら当てずに押すだけ。</li>
 *     <li>専用のダメージの種類(盾で防げない・ノックバックは自前・難易度で増減しない)が登録されている。</li>
 *     <li>見た目の地表の探し方(草花・液体を飛ばす、木の葉を拾わない)。</li>
 * </ul>
 * 波は 12 tick で端まで届くので、判定は {@link #CHECK_AT} tick 目。テスト場は 7×7 なので扇(半径 6、幅 ±60°)は外へはみ出すが、
 * 当たりは相手の位置だけで決まるので、的はすべて床の上(箱の中)に置く(床の外へ置くと落ちて「浮いている」扱いになる)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ScutimnesShockwaveGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ScutimnesShockwaveGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    /**
     * 自分たちだけのバッチ。的は汚染側に属さない豚なので、同じ時刻に隣で走る他のテストの Mob に殴られると体力の勘定が狂う。
     * バッチの中は並列だが、隣のテスト場とは 5 ブロック以上空くので、半径 6 の扇が隣の的へ届く向きには置いていない。
     */
    private static final String BATCH = "scutimnes_shockwave";
    private static final long CHECK_AT = 30L;
    /**
     * 体力の比較のゆとり。的の豚は防具 0・ダメージの種類は難易度で増減しないので、当たった量がそのまま体力に出る。
     * 期待値は<b>仕様の数字から直接</b>書く(2026-09-24、レビューの指摘): 以前は {@link ScutimnesShockwave#falloff} で期待値を出していたので、
     * 減衰の定数や形を取り違えても期待値が一緒に動いてテストが通っていた。
     */
    private static final float EPS = 0.05F;

    // ------------------------------------------------------------------ 当たり

    /**
     * 減衰そのもの。仕様「起点から 1 ブロックまで 1.0、6 ブロックで 0.3 へ直線」(その外も 0.3。範囲の外は当たり自体を出さない)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void falloffMatchesSpec(GameTestHelper helper) {
        double[][] cases = {{0.0D, 1.0D}, {0.5D, 1.0D}, {1.0D, 1.0D}, {3.5D, 0.65D}, {6.0D, 0.3D}, {8.0D, 0.3D}};
        for (double[] c : cases) {
            float got = ScutimnesShockwave.falloff(c[0]);
            if (Math.abs(got - c[1]) > 1.0E-4D) {
                helper.fail("起点から " + c[0] + " ブロックの減衰が " + c[1] + " ではなく " + got);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 近い相手ほど強く当たる。数値も仕様どおり: 溜めた量 20、距離は体の縁まで(豚の幅 0.9)。
     * 近い豚は縁まで 1.353 → 20 × (1 − 0.7 × 0.353 / 5) = 19.01、遠い豚は縁まで 4.293 → 20 × (1 − 0.7 × 3.293 / 5) = 10.78。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void nearHitsHarderThanFar(GameTestHelper helper) {
        Pig owner = pig(helper, 0.5D, 0.5D);                 // 起点の真横(90°)= 扇の外
        Pig near = pig(helper, 2.5D, 2.0D);                  // 起点から 1.80(縁まで 1.35)
        Pig far = pig(helper, 5.0D, 5.0D);                   // 起点から 4.74(縁まで 4.29)
        wave(helper, owner, 3.5D, 0.5D, 0.0F, 20.0F);
        float wantNear = 19.012F;
        float wantFar = 10.778F;
        helper.runAfterDelay(CHECK_AT, () -> {
            float gotNear = lost(near);
            float gotFar = lost(far);
            if (Math.abs(gotNear - wantNear) > EPS || Math.abs(gotFar - wantFar) > EPS) {
                helper.fail("ダメージが式と合わない: 近い豚 " + gotNear + "(期待 " + wantNear + ")、遠い豚 " + gotFar
                        + "(期待 " + wantFar + ")");
                return;
            }
            if (!(gotNear > gotFar)) {
                helper.fail("近い豚のほうが強く当たっていない: 近い " + gotNear + " / 遠い " + gotFar);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 6 ブロックの外には当たらない。斜め(+X+Z、yaw −45°)へ向けて対角線を使う —— 7×7 の中で 6 ブロックより遠くへ的を置けるのは対角線だけ。
     * 向きを斜めにしてあるので、前の向きの符号を取り違えると手前の豚にも当たらなくなって落ちる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void nothingBeyondRange(GameTestHelper helper) {
        Pig owner = pig(helper, 6.5D, 4.0D);                 // 起点から 7.2 = 範囲の外(持ち主の除外はここでは見ない)
        Pig beyond = pig(helper, 5.3D, 5.3D);                // 起点から 7.07(縁まで 6.62)
        Pig inside = pig(helper, 2.6D, 2.6D);                // 起点から 3.25
        wave(helper, owner, 0.3D, 0.3D, -45.0F, 20.0F);
        helper.runAfterDelay(CHECK_AT, () -> {
            if (lost(beyond) > 0.0F) {
                helper.fail("6 ブロックの外の豚に当たった(" + lost(beyond) + ")");
                return;
            }
            if (lost(inside) <= 0.0F) {
                helper.fail("扇の中の 3 ブロック先の豚に当たっていない(向きの取り違え?)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 扇の縁は正面 ±60° + 体の幅のゆとり(asin(体の幅の半分 ÷ 距離))。起点から 3 ブロックの豚(ゆとり 8.6°)で、
     * 正面から 64° には当たり、反対側の 73° には当たらない —— 扇の開きを 50° や 70° に取り違えるとどちらかが崩れる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void fanEdgeIsSixtyDegreesPlusBodyWidth(GameTestHelper helper) {
        Pig owner = pig(helper, 3.5D, 6.5D);                 // 真正面の奥(持ち主は当たらない)
        Pig inside = pig(helper, 3.5D + 3.0D * Math.sin(Math.toRadians(64.0D)), 0.5D + 3.0D * Math.cos(Math.toRadians(64.0D)));
        Pig outside = pig(helper, 3.5D - 3.0D * Math.sin(Math.toRadians(73.0D)), 0.5D + 3.0D * Math.cos(Math.toRadians(73.0D)));
        wave(helper, owner, 3.5D, 0.5D, 0.0F, 20.0F);
        helper.runAfterDelay(CHECK_AT, () -> {
            if (lost(inside) <= 0.0F) {
                helper.fail("正面から 64°(縁 68.6° の内)の豚に当たっていない");
                return;
            }
            if (lost(outside) > 0.0F) {
                helper.fail("正面から 73°(縁 68.6° の外)の豚に当たった(" + lost(outside) + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 扇(正面 ±60°)の外 —— 真横と真後ろ —— には当たらない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void nothingOutsideFan(GameTestHelper helper) {
        Pig owner = pig(helper, 6.4D, 0.6D);                 // 後ろ斜め
        Pig left = pig(helper, 0.8D, 3.0D);                  // 正面から 79.5°(体の幅のゆとり 9.4° を足しても 69.4° > 60°)
        Pig right = pig(helper, 6.2D, 3.0D);                 // 反対側に同じ角度
        Pig behind = pig(helper, 3.5D, 0.6D);                // 真後ろ 1.9
        Pig front = pig(helper, 3.5D, 5.0D);                 // 真正面 2.5
        wave(helper, owner, 3.5D, 2.5D, 0.0F, 20.0F);
        helper.runAfterDelay(CHECK_AT, () -> {
            if (lost(left) > 0.0F || lost(right) > 0.0F) {
                helper.fail("扇の外(真横)の豚に当たった: 左 " + lost(left) + " / 右 " + lost(right));
                return;
            }
            if (lost(behind) > 0.0F) {
                helper.fail("真後ろの豚に当たった(" + lost(behind) + ")");
                return;
            }
            if (lost(front) <= 0.0F) {
                helper.fail("真正面の豚に当たっていない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 波が足元を通るときに地面から 0.5 以上浮いている相手には当たらない(仕様)。境目を両側から挟む: 床から 0.6 上に浮かせた豚には
     * 当たらず、0.4 上の豚には当たる。1.0 上の豚にも当たらず、地上の豚には当たる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void airborneTargetIsNotHit(GameTestHelper helper) {
        Pig owner = pig(helper, 0.5D, 0.5D);
        Pig high = floatingPig(helper, 2.0D, 1.0D, 4.5D);
        Pig above = floatingPig(helper, 2.3D, 0.6D, 3.0D);
        Pig below = floatingPig(helper, 4.7D, 0.4D, 3.0D);
        Pig grounded = pig(helper, 5.0D, 4.5D);
        wave(helper, owner, 3.5D, 0.5D, 0.0F, 20.0F);
        double floor = helper.absoluteVec(new Vec3(0.0D, 2.0D, 0.0D)).y;
        helper.runAfterDelay(CHECK_AT, () -> {
            if (above.getY() < floor + ScutimnesShockwave.AIRBORNE_GAP || high.getY() < floor + ScutimnesShockwave.AIRBORNE_GAP) {
                helper.fail("テストの前提が崩れた: 浮かせた豚が落ちている(y=" + above.getY() + " / " + high.getY() + ")");
                return;
            }
            if (lost(high) > 0.0F || lost(above) > 0.0F) {
                helper.fail("浮いている豚に当たった(1.0 上 " + lost(high) + " / 0.6 上 " + lost(above) + ")");
                return;
            }
            if (lost(below) <= 0.0F) {
                helper.fail("床から 0.4 上(0.5 未満)の豚に当たっていない");
                return;
            }
            if (lost(grounded) <= 0.0F) {
                helper.fail("地上の豚に当たっていない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 乗り物の上の相手は、いちばん下の乗り物が地面に居れば当たる。上に立てるエンティティ(ボート)の上に立つ相手も当たる
     * (2026-09-24、レビューの指摘。以前は足元のブロックしか見ず、馬に乗った相手・地面に置いたボートの上の相手は浮いている扱いで当たらなかった)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void ridersAndBoatStandersAreHit(GameTestHelper helper) {
        Pig owner = pig(helper, 0.5D, 0.5D);
        AbstractHorse horse = helper.spawnWithNoFreeWill(EntityType.HORSE, new Vec3(2.0D, 2.0D, 3.5D));
        // 馬にも波は当たる(汚染側ではない)。先に当たった馬が打ち上げられると、乗っている豚も宙に浮いて当たらなくなる
        // (それは正しい動き)ので、馬は押されず死なないようにしておく。
        horse.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        horse.setHealth(100.0F);
        horse.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        Pig rider = pig(helper, 2.0D, 5.5D);
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(5.2D, 2.0D, 3.5D));
        Pig onBoat = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(5.2D, 2.6D, 3.5D));
        tough(onBoat);
        helper.runAfterDelay(1L, () -> {
            if (!rider.startRiding(horse, true)) {
                helper.fail("テストの前提が崩れた: 豚を馬に乗せられない");
            }
        });
        helper.runAfterDelay(5L, () -> {
            double floor = helper.absoluteVec(new Vec3(0.0D, 2.0D, 0.0D)).y;
            if (rider.getVehicle() != horse || rider.getY() < floor + ScutimnesShockwave.AIRBORNE_GAP) {
                helper.fail("テストの前提が崩れた: 馬に乗った豚の足が地面から 0.5 以上上にない(y=" + rider.getY() + ")");
                return;
            }
            if (onBoat.isPassenger() || onBoat.getY() < floor + ScutimnesShockwave.AIRBORNE_GAP) {
                helper.fail("テストの前提が崩れた: 豚がボートの上に立っていない(y=" + onBoat.getY() + "、乗っている " + onBoat.isPassenger() + ")");
                return;
            }
            wave(helper, owner, 3.5D, 0.5D, 0.0F, 20.0F);
        });
        helper.runAfterDelay(5L + CHECK_AT, () -> {
            if (lost(rider) <= 0.0F || lost(onBoat) <= 0.0F) {
                helper.fail("地面に居る乗り物・ボートの上の豚に当たっていない: 馬に乗った豚 " + lost(rider) + " / ボートの上の豚 "
                        + lost(onBoat) + "(ボート " + boat.position() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 1 体に 1 回だけ。的はノックバック耐性 1(押されずにその場に残る)—— 輪の帯は 1 tick に 0.5 進むが幅は 1.0 あるので、
     * 動かない相手は 2 tick 続けて帯の中に居る。無敵時間を毎 tick 消しておき、二度当たれば体力に出るようにする。
     * 1 回ぶんは 30 × (1 − 0.7 × 1.05 / 5) = 25.59(縁まで 2.05)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void hitsEachTargetOnce(GameTestHelper helper) {
        Pig owner = pig(helper, 0.5D, 0.5D);
        Pig target = pig(helper, 3.5D, 3.0D);                // 起点から 2.5(縁まで 2.05)
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        wave(helper, owner, 3.5D, 0.5D, 0.0F, 30.0F);
        float want = 25.59F;
        for (long t = 1L; t < CHECK_AT; t++) {
            helper.runAfterDelay(t, () -> target.invulnerableTime = 0);
        }
        helper.runAfterDelay(CHECK_AT, () -> {
            float got = lost(target);
            if (Math.abs(got - want) > EPS) {
                helper.fail("1 回ぶんのダメージ " + want + " ではなく " + got + "(2 回当たると約 " + (want * 2.0F) + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 持ち主と仲間(汚染側のグループ。ここでは過繁茂した豚 = グループ1)には当たらない。扇の中に置いても。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void ownerAndAlliesAreSpared(GameTestHelper helper) {
        Pig owner = pig(helper, 3.5D, 2.5D);                 // 扇の真ん中、起点から 2
        Mob ally = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_PIG.get(), new Vec3(1.8D, 2.0D, 3.5D));
        Pig foe = pig(helper, 5.2D, 3.5D);
        wave(helper, owner, 3.5D, 0.5D, 0.0F, 20.0F);
        helper.runAfterDelay(CHECK_AT, () -> {
            if (lost(owner) > 0.0F) {
                helper.fail("持ち主に当たった(" + lost(owner) + ")");
                return;
            }
            if (lost(ally) > 0.0F) {
                helper.fail("仲間(過繁茂した豚)に当たった(" + lost(ally) + ")");
                return;
            }
            if (lost(foe) <= 0.0F) {
                helper.fail("同じ扇の中の、汚染側でない豚に当たっていない");
                return;
            }
            helper.succeed();
        });
    }

    /** 溜めた量 0 ならダメージは 0。ただし押し出しはする(外向きに動く)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void emptyWaveOnlyPushes(GameTestHelper helper) {
        Pig owner = pig(helper, 0.5D, 0.5D);
        Pig target = pig(helper, 3.5D, 2.0D);
        double startZ = target.getZ();
        wave(helper, owner, 3.5D, 0.5D, 0.0F, 0.0F);
        helper.runAfterDelay(CHECK_AT, () -> {
            if (lost(target) > 0.0F) {
                helper.fail("溜めた量 0 なのにダメージが出た(" + lost(target) + ")");
                return;
            }
            if (target.getZ() < startZ + 0.2D) {
                helper.fail("押し出されていない(z " + startZ + " → " + target.getZ() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 専用のダメージの種類が登録されていて、盾で防げず・バニラのノックバックが無く・難易度で増減しない。
     * json とタグは本体の担当の持ち場({@code data/overgrown_vegetation/damage_type/scutimnes_shockwave.json}、
     * {@code data/minecraft/tags/damage_type/bypasses_shield.json}・{@code no_knockback.json})。無ければ衝撃波は
     * 持ち主の近接攻撃に落ちて動きはするが、盾で全部受けられてしまう。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void ownDamageTypeIsRegistered(GameTestHelper helper) {
        Optional<Holder.Reference<DamageType>> type = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolder(ScutimnesShockwave.DAMAGE_TYPE);
        if (type.isEmpty()) {
            helper.fail("ダメージの種類 " + ScutimnesShockwave.DAMAGE_TYPE.location() + " が無い(json の置き忘れ)");
            return;
        }
        if (!type.get().is(DamageTypeTags.BYPASSES_SHIELD)) {
            helper.fail("衝撃波が盾で防げてしまう(#minecraft:bypasses_shield に入っていない)");
            return;
        }
        if (!type.get().is(DamageTypeTags.NO_KNOCKBACK)) {
            helper.fail("バニラのノックバックが衝撃波の押し出しに重なる(#minecraft:no_knockback に入っていない)");
            return;
        }
        if (type.get().value().scaling() != DamageScaling.NEVER) {
            helper.fail("衝撃波のダメージが難易度で増減する(scaling が never でない)");
            return;
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 見た目の地表

    /**
     * 持ち上げる地表の探し方。起点の下のブロックから近い順に見て「当たり判定があり、真上に無い」ものを取る:
     * 草花・水の下は床、1 段上がった石はその石、頭上の葉(±3 の中)は拾わない。
     * 列は扇(正面 ±60°、半径 6)の中にしか無い。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void surfaceSearchSkipsPlantsAndCanopy(GameTestHelper helper) {
        // ポピーは石の上では隣の更新で消える(土の上にしか咲けない)ので、下の床を草に替えておく。
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.GRASS_BLOCK);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.POPPY);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 4), Blocks.WATER);
        if (!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 2, 3))).is(Blocks.POPPY)) {
            helper.fail("テストの前提が崩れた: ポピーが置けていない");
            return;
        }
        Vec3 origin = helper.absoluteVec(new Vec3(3.5D, 2.0D, 0.5D));
        int baseY = Mth.floor(origin.y + 1.0E-3D) - 1;
        String[] problem = {null};
        check(helper, baseY, new BlockPos(3, 1, 3), new BlockPos(3, 1, 3), "ポピーの下", problem);
        check(helper, baseY, new BlockPos(4, 2, 3), new BlockPos(4, 2, 3), "1 段上がった石", problem);
        check(helper, baseY, new BlockPos(2, 1, 4), new BlockPos(2, 1, 4), "水の下", problem);
        if (problem[0] != null) {
            helper.fail(problem[0]);
            return;
        }

        List<ScutimnesShockwave.Column> columns =
                ScutimnesShockwave.buildColumns(helper.getLevel(), origin, 0.0F, ScutimnesShockwave.MAX_STORED);
        if (columns.isEmpty()) {
            helper.fail("扇の中の列が 1 つも無い");
            return;
        }
        boolean sawPoppyColumn = false;
        for (ScutimnesShockwave.Column c : columns) {
            double cx = c.pos().getX() + 0.5D - origin.x;
            double cz = c.pos().getZ() + 0.5D - origin.z;
            double d = Math.sqrt(cx * cx + cz * cz);
            boolean apex = c.pos().getX() == Mth.floor(origin.x) && c.pos().getZ() == Mth.floor(origin.z);
            if (!apex && (d > ScutimnesShockwave.RANGE + 1.0E-6D || cz / d < 0.5D - 1.0E-6D)) {
                helper.fail("扇の外に列がある: " + c.pos() + "(距離 " + d + "、正面からの cos " + (cz / d) + ")");
                return;
            }
            if (c.pos().equals(helper.absolutePos(new BlockPos(3, 1, 3)))) {
                sawPoppyColumn = true;
            }
        }
        if (!sawPoppyColumn) {
            helper.fail("正面 3 ブロックの列(ポピーの下の床)が扇に入っていない");
            return;
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 下ごしらえ

    private static void check(GameTestHelper helper, int baseY, BlockPos column, BlockPos want, String what,
                              String[] problem) {
        if (problem[0] != null) {
            return;
        }
        BlockPos abs = helper.absolutePos(column);
        BlockPos got = ScutimnesShockwave.findSurface(helper.getLevel(), abs.getX(), baseY, abs.getZ());
        BlockPos expected = helper.absolutePos(want);
        if (!expected.equals(got)) {
            problem[0] = what + "の地表が " + expected + " ではなく " + got;
        }
    }

    /** AI を止めた体力 100 の豚を床の上に置く。 */
    private static Pig pig(GameTestHelper helper, double x, double z) {
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(x, 2.0D, z));
        tough(pig);
        return pig;
    }

    private static void tough(Pig pig) {
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        pig.setHealth(100.0F);
    }

    /** 床から {@code height} 上に浮かせたまま(重力なし)の、体力 100 の豚。 */
    private static Pig floatingPig(GameTestHelper helper, double x, double height, double z) {
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(x, 2.0D + height, z));
        tough(pig);
        pig.setNoGravity(true);
        pig.setDeltaMovement(Vec3.ZERO);
        return pig;
    }

    private static void wave(GameTestHelper helper, LivingEntity owner, double x, double z, float yaw, float stored) {
        ScutimnesShockwave.spawn(helper.getLevel(), owner, helper.absoluteVec(new Vec3(x, 2.0D, z)), yaw, stored);
    }

    private static float lost(LivingEntity e) {
        return e.getMaxHealth() - e.getHealth();
    }

    private ScutimnesShockwaveGameTests() {
    }
}
