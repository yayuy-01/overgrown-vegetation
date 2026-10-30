package com.yayuy.overgrownvegetation.gametest;

import com.mojang.serialization.Lifecycle;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.EscapeWaterGoal;
import com.yayuy.overgrownvegetation.entity.ScutimnesEntity;
import com.yayuy.overgrownvegetation.entity.ScutimnesShockwave;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * スクティムネス(グループ5 の盾役)のテスト(2026-09-23)。
 *
 * <p>Goal は外し({@code spawnWithNoFreeWill})、標的を選ぶ Goal も外して標的は手で決める(並列に走る他のテストの Mob を
 * 狙わないように)。動作・盾・旋回は {@code aiStep} で進むので Goal が無くても進む。攻撃の選択は {@link ScutimnesEntity#combatTick}
 * を直接呼ぶ。守りたいのは、ユーザーが指定したところ:
 * <ul>
 *     <li>盾に当たった<b>近接は 50%</b>、<b>飛び道具は弾く</b>(正面 ±60° のみ。背面は全部通る)。盾に効くのは剣・状態異常・炎だけ
 *     (TaCZ の弾の防具貫通ぶん・機関砲台の弾も弾く。スプラッシュポーションは弾かずに効かせる)。</li>
 *     <li><b>ガードは 100% カットして溜め</b>(上限 60。無敵時間の決まりはバニラと同じ。弾いた矢は弓のエンチャント込み)、
 *     終わると<b>叩きつけの衝撃波に溜めた量が乗る</b>(出る tick・起点・向きも)。</li>
 *     <li><b>その場で速く回れない</b>(1 tick 1.5° まで。バニラの Goal・lookAt・向きの直書きでも)。</li>
 *     <li><b>盾は距離 10 で下ろし 15 を超えたら上げる</b>。<b>遠距離の標的なら距離に関係なく下ろす</b>。</li>
 *     <li><b>バッシュは装備の耐久を多く削る</b>。<b>背後の相手には尾の薙ぎ払い</b>。<b>交戦直後は特殊攻撃を出さない</b>。</li>
 * </ul>
 * 体格 2.88(2026-09-24 に 1.6 から 1.8 倍)のまま走らせる(当たり判定は幅 2.97 x 高さ 2.59 で 7×7 のテスト場に収まる。尾・脚・盾は判定の外なので関係ない)。
 * 適応の台帳には触らない(スクティムネスを倒すテストは無い)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ScutimnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ScutimnesGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";

    /** 足元 (3, 2, z) に +Z を向けて置く。原点は (3.5, 2, z + 0.5)。 */
    private static ScutimnesEntity place(GameTestHelper helper, int z) {
        ScutimnesEntity s = helper.spawnWithNoFreeWill(ModEntities.SCUTIMNES.get(), new BlockPos(3, 2, z));
        s.targetSelector.removeAllGoals(goal -> true);
        s.snapFacing(0.0F);
        return s;
    }

    /** 盾を下ろした状態で置き、正面 3 ブロックの(世界に居ない)プレイヤーを標的にする —— 標的が 10 以内なので盾は下ろしたまま。 */
    private static ScutimnesEntity placeShielded(GameTestHelper helper, int z, Player front) {
        ScutimnesEntity s = place(helper, z);
        s.forceShieldDown(true);
        s.setTarget(front);
        return s;
    }

    /** 世界に入れない偽のプレイヤーを、テスト場の相対座標に立たせる(殴った位置・距離の判定にだけ使う)。 */
    private static Player mockAt(GameTestHelper helper, double x, double y, double z) {
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.setPos(helper.absoluteVec(new Vec3(x, y, z)));
        return p;
    }

    /** 防御 20 を通ったあとに減る量(バニラと同じ式)。 */
    private static float afterArmor(LivingEntity e, DamageSource source, float amount) {
        return CombatRules.getDamageAfterAbsorb(e, amount, source, (float) e.getArmorValue(),
                (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    /** 正面の近接は 50%、背面は全部。盾を上げていれば正面も全部。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void frontMeleeIsHalvedBackIsFull(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 4.5D);
        Player back = mockAt(helper, 3.5D, 2.0D, -1.5D);
        ScutimnesEntity s = placeShielded(helper, 1, front);
        helper.runAfterDelay(2L, () -> {
            DamageSource fromFront = helper.getLevel().damageSources().playerAttack(front);
            DamageSource fromBack = helper.getLevel().damageSources().playerAttack(back);

            float before = s.getHealth();
            s.hurt(fromFront, 10.0F);
            float lost = before - s.getHealth();
            float want = afterArmor(s, fromFront, 5.0F);
            if (Math.abs(lost - want) > 0.01F) {
                helper.fail("正面の剣 10 は半分(防御後 " + want + ")のはずが " + lost);
                return;
            }

            s.invulnerableTime = 0;
            before = s.getHealth();
            s.hurt(fromBack, 10.0F);
            lost = before - s.getHealth();
            want = afterArmor(s, fromBack, 10.0F);
            if (Math.abs(lost - want) > 0.01F) {
                helper.fail("背面の剣 10 は全部(防御後 " + want + ")のはずが " + lost);
                return;
            }

            s.invulnerableTime = 0;
            s.forceShieldDown(false);
            before = s.getHealth();
            s.hurt(fromFront, 10.0F);
            lost = before - s.getHealth();
            want = afterArmor(s, fromFront, 10.0F);
            if (Math.abs(lost - want) > 0.01F) {
                helper.fail("盾を上げていれば正面も全部(防御後 " + want + ")のはずが " + lost);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 盾が効くのは下ろしきってから(shield_down の 0.45 秒 = 13 tick 目)。下ろしている途中の正面の一撃は全部通る。
     * 標的(正面 3)を渡すと最初の tick に下ろし始める。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 30)
    public static void shieldGuardsOnlyAfterSettling(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 4.5D);
        ScutimnesEntity s = place(helper, 1);
        s.setTarget(front);
        DamageSource fromFront = helper.getLevel().damageSources().playerAttack(front);
        helper.runAfterDelay(5L, () -> {
            if (s.getAction() != ScutimnesEntity.ACT_SHIELD_DOWN) {
                helper.fail("正面 3 ブロックの標的で盾を下ろし始めていない(動作 " + s.getAction() + ")");
                return;
            }
            float before = s.getHealth();
            s.hurt(fromFront, 10.0F);
            float lost = before - s.getHealth();
            if (Math.abs(lost - afterArmor(s, fromFront, 10.0F)) > 0.01F) {
                helper.fail("下ろしている途中なのに盾が効いている(減った量 " + lost + ")");
            }
        });
        helper.runAfterDelay(15L, () -> {
            s.invulnerableTime = 0;
            float before = s.getHealth();
            s.hurt(fromFront, 10.0F);
            float lost = before - s.getHealth();
            if (Math.abs(lost - afterArmor(s, fromFront, 5.0F)) > 0.01F) {
                helper.fail("盾が収まった後なのに半分になっていない(減った量 " + lost + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 正面から来た矢は弾かれて(跳ね返って)ダメージ 0。背面から来た矢は当たる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void frontArrowIsDeflected(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 5.5D);
        ScutimnesEntity s = placeShielded(helper, 2, front);
        Level level = helper.getLevel();
        Arrow[] arrows = new Arrow[2];
        helper.runAfterDelay(2L, () -> {
            // 当たり判定(体格込み)の前面は z = 2.5 + 1.44。その 2 ブロック先から撃ち込む。
            arrows[0] = new Arrow(EntityType.ARROW, level);
            arrows[0].setPos(helper.absoluteVec(new Vec3(3.5D, 2.7D, 6.2D)));
            arrows[0].setDeltaMovement(0.0D, 0.0D, -1.5D);
            level.addFreshEntity(arrows[0]);
        });
        helper.runAfterDelay(10L, () -> {
            if (s.getHealth() < s.getMaxHealth()) {
                helper.fail("正面の矢が当たっている(体力 " + s.getHealth() + ")");
                return;
            }
            if (arrows[0].getDeltaMovement().z <= 0.0D && arrows[0].getZ() < helper.absoluteVec(new Vec3(0.0D, 0.0D, 5.0D)).z) {
                helper.fail("正面の矢が跳ね返っていない");
                return;
            }
            arrows[1] = new Arrow(EntityType.ARROW, level);
            arrows[1].setPos(helper.absoluteVec(new Vec3(3.5D, 2.7D, 0.3D)));
            arrows[1].setDeltaMovement(0.0D, 0.0D, 1.5D);
            level.addFreshEntity(arrows[1]);
        });
        helper.runAfterDelay(18L, () -> {
            if (s.getHealth() >= s.getMaxHealth()) {
                helper.fail("背面から来た矢が当たっていない");
                return;
            }
            helper.succeed();
        });
    }

    /** 正面の爆発は 0。炎と魔法(状態異常)は盾と関係なく通る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void frontExplosionBlockedFireAndMagicGoThrough(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 4.5D);
        ScutimnesEntity s = placeShielded(helper, 1, front);
        helper.runAfterDelay(2L, () -> {
            Level level = helper.getLevel();
            // 爆心は原点の 2.5 ブロック前。爆発の出どころ(位置)を持たせるため、世界に入れない TNT を爆発の主にする。
            Vec3 c = helper.absoluteVec(new Vec3(3.5D, 2.5D, 4.0D));
            PrimedTnt tnt = new PrimedTnt(level, c.x, c.y, c.z, null);
            level.explode(tnt, c.x, c.y, c.z, 2.0F, Level.ExplosionInteraction.NONE);
            if (s.getHealth() < s.getMaxHealth()) {
                helper.fail("正面の爆発で減っている(体力 " + s.getHealth() + ")");
                return;
            }
            s.invulnerableTime = 0;
            s.hurt(level.damageSources().inFire(), 4.0F);
            if (s.getHealth() >= s.getMaxHealth()) {
                helper.fail("炎が盾で止まっている");
                return;
            }
            s.invulnerableTime = 0;
            float before = s.getHealth();
            s.hurt(level.damageSources().magic(), 3.0F);
            if (Math.abs((before - s.getHealth()) - 3.0F) > 0.01F) {
                helper.fail("魔法 3 がそのまま通っていない(減った量 " + (before - s.getHealth()) + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * ガード中は正面からの攻撃を 100% カットして溜める。背面は通って溜まらない。上限 60 に達したらその場で叩きつけへ。
     * 溜めにもバニラの無敵時間の決まりが掛かる(2026-09-24、レビューの指摘): 前の一撃から 10 tick の間に来た一撃は、
     * 前の一撃を超えたぶんしか溜まらない(バニラならそれしか通らないので「カットした分」もそれだけ)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void guardAbsorbsFrontAndCapsAtSixty(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 4.5D);
        Player back = mockAt(helper, 3.5D, 2.0D, -1.5D);
        ScutimnesEntity s = placeShielded(helper, 1, front);
        helper.runAfterDelay(1L, () -> s.forceAction(ScutimnesEntity.ACT_GUARD_START));
        helper.runAfterDelay(3L, () -> {
            DamageSource fromFront = helper.getLevel().damageSources().playerAttack(front);
            DamageSource fromBack = helper.getLevel().damageSources().playerAttack(back);
            float before = s.getHealth();
            s.hurt(fromBack, 10.0F);
            float lostBack = before - s.getHealth();
            if (Math.abs(lostBack - afterArmor(s, fromBack, 10.0F)) > 0.01F || s.getGuardStored() != 0.0F) {
                helper.fail("ガード中の背面は素通り・溜まらないはずが 減った " + lostBack + " / 溜め " + s.getGuardStored());
                return;
            }
            // 背面の 10 の無敵時間の中: 正面の 25 は 10 を超えた 15 だけ溜まり、続けて来た同じ 25 は溜まらない。
            before = s.getHealth();
            s.hurt(fromFront, 25.0F);
            s.hurt(fromFront, 25.0F);
            if (s.getHealth() != before || Math.abs(s.getGuardStored() - 15.0F) > 0.01F) {
                helper.fail("無敵時間の中の正面 25 × 2 は 0 で 15 だけ溜まるはずが 体力 " + (before - s.getHealth()) + " 減・溜め "
                        + s.getGuardStored());
                return;
            }
            // 無敵時間が明けた一撃は全部溜まる。
            s.invulnerableTime = 0;
            s.hurt(fromFront, 25.0F);
            s.invulnerableTime = 0;
            s.hurt(fromFront, 15.0F);
            if (s.getHealth() != before || Math.abs(s.getGuardStored() - 55.0F) > 0.01F) {
                helper.fail("無敵時間の明けた正面 25 + 15 で 55 溜まるはずが 体力 " + (before - s.getHealth()) + " 減・溜め "
                        + s.getGuardStored());
                return;
            }
            if (!s.isGuarding()) {
                helper.fail("上限の前にガードが終わっている(動作 " + s.getAction() + ")");
                return;
            }
            s.invulnerableTime = 0;
            s.hurt(fromFront, 25.0F);
            if (Math.abs(s.getGuardStored() - 60.0F) > 0.01F) {
                helper.fail("溜めは 60 で頭打ちのはずが " + s.getGuardStored());
                return;
            }
            if (s.getAction() != ScutimnesEntity.ACT_SLAM) {
                helper.fail("上限に達したのに叩きつけへ移っていない(動作 " + s.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * ガードは 5 秒(100 tick)で終わり、叩きつけの 1.10 秒に、溜めた量を乗せた衝撃波が出る。波そのもの(当たり・減衰・見た目)は
     * {@code ScutimnesShockwaveGameTests}。2026-09-24(レビューの指摘)から、波の<b>出る tick・起点・向き</b>も仕様の数字で縛る:
     * <ul>
     *     <li>叩きつけを始めた tick からちょうど 26 tick 目(補間 4 + slam の 1.10 秒 × 20)。</li>
     *     <li>起点は盾が地面を叩く点 = 原点の真正面 2.5535 ブロック({@code points.json} (b) の z −28.186 と原点 z −14 の差 14.186 px ×
     *     体格 2.88 ÷ 16)。</li>
     *     <li>扇の向きは本体の向き。向きの取り違え(符号・sin と cos)を拾うよう、斜め(yaw −45° = +X+Z)に置いて走らせる。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 160)
    public static void slamReleasesStoredDamageAsShockwave(GameTestHelper helper) {
        ScutimnesEntity s = helper.spawnWithNoFreeWill(ModEntities.SCUTIMNES.get(), new BlockPos(2, 2, 2));
        s.targetSelector.removeAllGoals(goal -> true);
        s.snapFacing(-45.0F);
        s.forceShieldDown(true);
        // 原点 (2.5, 2, 2.5)。標的は真正面(+X+Z)3 ブロック —— ガードの間も向きを変えない。
        double diag = 3.0D / Math.sqrt(2.0D);
        Player front = mockAt(helper, 2.5D + diag, 2.0D, 2.5D + diag);
        s.setTarget(front);
        int[] slamStart = {-1};
        boolean[] checked = {false};
        helper.runAfterDelay(1L, () -> {
            s.forceAction(ScutimnesEntity.ACT_GUARD_START);
            s.hurt(helper.getLevel().damageSources().playerAttack(front), 20.0F);
        });
        helper.runAfterDelay(95L, () -> {
            if (!s.isGuarding() || Math.abs(s.getGuardStored() - 20.0F) > 0.01F) {
                helper.fail("5 秒経つ前にガードが終わっている、または溜めが 20 でない(動作 " + s.getAction()
                        + "・溜め " + s.getGuardStored() + ")");
            }
        });
        helper.onEachTick(() -> {
            if (checked[0]) {
                return;
            }
            int tick = (int) helper.getTick();
            if (slamStart[0] < 0 && s.getAction() == ScutimnesEntity.ACT_SLAM) {
                slamStart[0] = tick;
            }
            List<ScutimnesShockwave> waves = waves(helper, s);
            if (waves.isEmpty()) {
                return;
            }
            checked[0] = true;
            if (slamStart[0] < 0) {
                helper.fail("叩きつけを始める前に衝撃波が出ている");
                return;
            }
            if (tick - slamStart[0] != 26) {
                helper.fail("衝撃波が叩きつけの " + (tick - slamStart[0]) + " tick 目に出た(26 tick 目 = 補間 4 + 1.10 秒のはず)");
                return;
            }
            ScutimnesShockwave wave = waves.get(0);
            float yaw = s.getYRot();
            if (Math.abs(Mth.wrapDegrees(yaw + 45.0F)) > 0.01F || Math.abs(Mth.wrapDegrees(wave.yawDegrees() - yaw)) > 0.01F) {
                helper.fail("扇の向き " + wave.yawDegrees() + " が本体の向き " + yaw + "(−45 のはず)と違う");
                return;
            }
            double ahead = 14.186D / 16.0D * 2.88D;
            Vec3 want = s.position().add(ahead / Math.sqrt(2.0D), 0.0D, ahead / Math.sqrt(2.0D));
            if (wave.position().distanceTo(want) > 0.01D) {
                helper.fail("衝撃波の起点 " + wave.position() + " が盾の叩いた点 " + want + "(原点の正面 2.5535)と違う");
                return;
            }
            if (Math.abs(s.getLastSlamStored() - 20.0F) > 0.01F || Math.abs(wave.storedDamage() - 20.0F) > 0.01F) {
                helper.fail("衝撃波に渡した量が 20 でない: " + s.getLastSlamStored() + " / " + wave.storedDamage());
                return;
            }
            if (s.getGuardStored() != 0.0F) {
                helper.fail("叩きつけた後も溜めが残っている: " + s.getGuardStored());
                return;
            }
            helper.succeed();
        });
    }

    private static List<ScutimnesShockwave> waves(GameTestHelper helper, ScutimnesEntity s) {
        return helper.getLevel().getEntitiesOfClass(ScutimnesShockwave.class, new AABB(s.blockPosition()).inflate(6.0D));
    }

    /**
     * 向きは 1 tick に 1.5° を超えて変わらない。真後ろの相手へ、バニラの移動(1 tick 90°)・{@code lookAt}(180°)・
     * 視線・向きの直書き(+90°)で毎 tick 急かしても同じ。ちゃんと相手の方へは回っていく。体の向きは常に yRot と同じ。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void turnIsLimitedToOnePointFiveDegreesPerTick(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 3);
        Player behind = mockAt(helper, 3.5D, 2.0D, -1.5D);
        float[] last = {Float.NaN};
        float[] turned = {0.0F};
        helper.onEachTick(() -> {
            float yaw = s.getYRot();
            if (!Float.isNaN(last[0])) {
                float step = Mth.wrapDegrees(yaw - last[0]);
                if (Math.abs(step) > ScutimnesEntity.MAX_TURN_PER_TICK + 1.0E-3F) {
                    helper.fail("1 tick に " + step + "° 回った");
                    return;
                }
                turned[0] += Math.abs(step);
            }
            if (Math.abs(Mth.wrapDegrees(s.yBodyRot - yaw)) > 1.0E-3F || Math.abs(Mth.wrapDegrees(s.yHeadRot - yaw)) > 1.0E-3F) {
                helper.fail("体・頭の向きが yRot とずれている(体 " + s.yBodyRot + " 頭 " + s.yHeadRot + " yRot " + yaw + ")");
                return;
            }
            last[0] = yaw;
            // よそからの急かし(次の tick の終わりに旋回の制限で上書きされるはず)。
            s.lookAt(behind, 180.0F, 90.0F);
            s.getLookControl().setLookAt(behind);
            s.getMoveControl().setWantedPosition(behind.getX(), s.getY(), behind.getZ(), 1.0D);
            s.setYRot(yaw + 90.0F);
        });
        helper.runAfterDelay(45L, () -> {
            if (turned[0] < 20.0F) {
                helper.fail("真後ろの相手へ回っていない(45 tick で " + turned[0] + "°)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 盾を下ろすのは距離 10 以下、上げるのは 15 を超えたとき(その間はそのまま)。遠距離の標的(弓を持っている)なら 20 ブロック先でも下ろす。
     * 上げ下げの動作(0.6 秒 + 補間 = 16 tick)を挟む。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void shieldHysteresisAndRangedTarget(GameTestHelper helper) {
        // 判定の式そのもの。
        if (ScutimnesEntity.wantShieldDown(false, true, 12.0D, false)
                || !ScutimnesEntity.wantShieldDown(false, true, 10.0D, false)
                || !ScutimnesEntity.wantShieldDown(true, true, 14.9D, false)
                || !ScutimnesEntity.wantShieldDown(true, true, 15.0D, false)
                || ScutimnesEntity.wantShieldDown(true, true, 15.1D, false)
                || !ScutimnesEntity.wantShieldDown(false, true, 20.0D, true)
                || ScutimnesEntity.wantShieldDown(true, false, 3.0D, false)) {
            helper.fail("盾の上げ下げの判定(10 で下ろす・15 超で上げる・遠距離なら下ろす・標的なしで上げる)が違う");
            return;
        }
        ScutimnesEntity s = place(helper, 3);
        Player target = mockAt(helper, 3.5D, 2.0D, 23.5D);
        target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        s.setTarget(target);
        helper.runAfterDelay(3L, () -> {
            if (s.getAction() != ScutimnesEntity.ACT_SHIELD_DOWN) {
                helper.fail("弓を持った 20 ブロック先の標的で盾を下ろし始めていない(動作 " + s.getAction() + ")");
            }
        });
        helper.runAfterDelay(20L, () -> {
            if (!s.isShieldDown() || s.isActing()) {
                helper.fail("盾を下ろしきっていない(動作 " + s.getAction() + ")");
                return;
            }
            // 弓を手放す → 20 ブロック先はもう遠距離の標的ではないので上げる。
            target.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        });
        helper.runAfterDelay(23L, () -> {
            if (s.getAction() != ScutimnesEntity.ACT_SHIELD_UP || s.isShieldDown()) {
                helper.fail("弓を手放した 20 ブロック先の標的で盾を上げ始めていない(動作 " + s.getAction() + ")");
            }
        });
        helper.runAfterDelay(42L, () -> {
            if (s.isShieldDown() || s.isActing()) {
                helper.fail("盾を上げきっていない(動作 " + s.getAction() + ")");
                return;
            }
            target.setPos(helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D + 12.0D)));
        });
        helper.runAfterDelay(46L, () -> {
            if (s.isShieldDown() || s.isActing()) {
                helper.fail("距離 12(10〜15)で上げた盾を下ろしている(動作 " + s.getAction() + ")");
                return;
            }
            target.setPos(helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D + 9.5D)));
        });
        helper.runAfterDelay(50L, () -> {
            if (s.getAction() != ScutimnesEntity.ACT_SHIELD_DOWN) {
                helper.fail("距離 9.5 で盾を下ろし始めていない(動作 " + s.getAction() + ")");
            }
        });
        helper.runAfterDelay(68L, () -> {
            if (!s.isShieldDown() || s.isActing()) {
                helper.fail("距離 9.5 で盾を下ろしきっていない(動作 " + s.getAction() + ")");
                return;
            }
            target.setPos(helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D + 14.0D)));
        });
        helper.runAfterDelay(72L, () -> {
            if (!s.isShieldDown() || s.isActing()) {
                helper.fail("距離 14(10〜15)で下ろした盾を上げている(動作 " + s.getAction() + ")");
                return;
            }
            target.setPos(helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D + 15.5D)));
        });
        helper.runAfterDelay(76L, () -> {
            if (s.getAction() != ScutimnesEntity.ACT_SHIELD_UP) {
                helper.fail("距離 15.5 で盾を上げ始めていない(動作 " + s.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * バッシュは突き出しの頂点(補間 4 + 0.60 秒 = 16 tick 目)に当たり、防具 4 部位それぞれの耐久を追加で削る
     * (通常ぶん max(1, 8 ÷ 4) = 2 の 2 倍 = 4。1.21.1 の Mob の防具は通常ぶんが 0 なので、ハスクにはこの 4 だけが入る)。
     * 的はゾンビではなくハスク —— ゾンビは昼の日光で毎 tick 約 4% の確率で兜に 0〜1 の減りが乗り(兜が無ければ燃える)、
     * head だけ 5・6 になってたまに落ちた(2026-09-24、10 回中 4 回)。ハスクは日に焼けない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void bashWearsArmorHeavily(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 1);
        s.forceShieldDown(true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 3));
        husk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        husk.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        husk.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        s.setTarget(husk);
        helper.runAfterDelay(1L, () -> s.forceAction(ScutimnesEntity.ACT_BASH_L));
        // forceAction の次の tick が動作の 1 tick 目 → 当たるのは 1 + 16 = 17 tick 目。
        helper.runAfterDelay(15L, () -> {
            if (husk.getHealth() < husk.getMaxHealth()) {
                helper.fail("盾を突き出す前に当たっている");
            }
        });
        helper.runAfterDelay(20L, () -> {
            if (husk.getHealth() >= husk.getMaxHealth()) {
                helper.fail("正面 2 ブロックのハスクに当たっていない");
                return;
            }
            int want = ScutimnesEntity.bashArmorWear(8.0F);
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                int wear = husk.getItemBySlot(slot).getDamageValue();
                if (wear != want) {
                    helper.fail(slot.getName() + " の耐久の減りが " + want + " のはずが " + wear);
                    return;
                }
            }
            // プレイヤーの防具: バニラが削る通常ぶん(難易度を掛けた後のダメージから)の 2 倍を足す = 合計 3 倍(どの難易度でも。
            // 2026-09-24 まではノーマルの値で固定していた)。世界に入れない偽のプレイヤーをバッシュと同じダメージで殴って通常ぶんを測る。
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            DamageSource bashSource = helper.getLevel().damageSources().mobAttack(s);
            player.hurt(bashSource, 8.0F);
            int vanilla = player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
            int extra = ScutimnesEntity.bashArmorWear(ScutimnesEntity.armorSeenDamage(bashSource, player, 8.0F));
            if (vanilla <= 0 || extra != 2 * vanilla) {
                helper.fail("プレイヤーの防具の追加の削り " + extra + " が、バニラの通常ぶん " + vanilla + " の 2 倍でない(難易度 "
                        + helper.getLevel().getDifficulty() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 背後(正面から 100° 以上)の相手には尾の薙ぎ払いを選び、振りの中央(補間 4 + 0.70 秒 = 18 tick 目)に後ろ側の相手だけに当てる。
     * 背後の的はハスク(ゾンビだと日光で燃えて尾より先に体力が減り、10 回中 5 回落ちた。2026-09-24)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void tailSweepsFoesBehind(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 4);
        s.forceShieldDown(true);
        Husk behind = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 1));
        Pig ahead = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 6));
        s.setTarget(behind);
        helper.runAfterDelay(1L, () -> {
            s.combatTick(behind);
            int act = s.getAction();
            if (act != ScutimnesEntity.ACT_TAIL_L && act != ScutimnesEntity.ACT_TAIL_R) {
                helper.fail("真後ろ 3 ブロックの標的に尾の薙ぎ払いを選んでいない(動作 " + act + ")");
            }
        });
        helper.runAfterDelay(16L, () -> {
            if (behind.getHealth() < behind.getMaxHealth()) {
                helper.fail("尾が真後ろを通る前に当たっている(" + behind.getLastDamageSource() + ")");
            }
        });
        helper.runAfterDelay(22L, () -> {
            if (behind.getHealth() >= behind.getMaxHealth()) {
                helper.fail("背後のハスクに尾が当たっていない");
                return;
            }
            if (ahead.getHealth() < ahead.getMaxHealth()) {
                helper.fail("正面の豚まで尾で払っている");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 交戦直後は特殊攻撃(ガード)を出さない(ユーザー指定「最初から特殊攻撃を使わず、通常攻撃を使いながら時々織り交ぜる」)。
     * ガードの条件(クールタイム明け・盾を下ろしきっている・正面から殴られ続けている)は最初から揃えてあり、
     * 間合いの内の相手にはバッシュ(通常攻撃)を出し続ける。暖機 6 秒と通常攻撃 2 回の後は、いずれガードに入る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 700)
    public static void noSpecialRightAfterEngaging(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 1);
        s.forceShieldDown(true);
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 3));
        // 殴る役は世界に入れない偽のプレイヤー。本体が少し動いても正面に居続けるよう、ずっと先(テスト場の外)に立たせる。
        Player front = mockAt(helper, 3.5D, 2.0D, 12.0D);
        s.setTarget(golem);
        DamageSource fromFront = helper.getLevel().damageSources().playerAttack(front);
        int[] bashes = {0};
        int[] lastAction = {ScutimnesEntity.ACT_NONE};
        helper.onEachTick(() -> {
            golem.setHealth(golem.getMaxHealth());
            if (helper.getTick() % 10 == 0) {
                s.invulnerableTime = 0;
                s.hurt(fromFront, 1.0F);
            }
            s.combatTick(golem);
            int act = s.getAction();
            if (act != lastAction[0] && (act == ScutimnesEntity.ACT_BASH_L || act == ScutimnesEntity.ACT_BASH_R)) {
                bashes[0]++;
            }
            lastAction[0] = act;
            if (s.isGuarding()) {
                if (helper.getTick() < 115L) {
                    helper.fail("交戦から " + helper.getTick() + " tick でガード(特殊攻撃)を出した");
                    return;
                }
                if (bashes[0] < 2) {
                    helper.fail("バッシュ(通常攻撃)を " + bashes[0] + " 回しか出していないのにガードを出した");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /**
     * バッシュを振り始める間合い(2026-09-24、統合で足した)。突き出した盾の前面は原点の 3.28 ブロック前(体格 2.88、
     * {@code tools/scutimnes-anim/points.json} の実測)で、当たり判定の前面(1.49)より 1.8 ブロック前へ出る。振り始めは届く距離(盾の前面 + 相手の半幅 + ゆとり 0.6 = 4.18)の 85% = 3.55。
     * 正面 3.0 の相手には振り始め、判定のゆとりを足しても届かない相手(4.3)には振らずに寄る。
     * 体格 1.6 のときは 2.2 と 2.9 で見ていた。仕様書の仮置き(盾の前面 z −36)のままの式だと、当たり判定に触れている相手にも振らなかった。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void bashStartsOnlyWithinReach(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 1);
        s.forceShieldDown(true);
        // 原点は (3.5, 2, 1.5)、正面は +Z。偽のプレイヤーは世界に入れない(距離と向きの判定にだけ使う)。
        Player near = mockAt(helper, 3.5D, 2.0D, 1.5D + 3.0D);
        Player far = mockAt(helper, 3.5D, 2.0D, 1.5D + 4.3D);
        // 標的はすぐに渡す(標的なしの tick があると盾を上げ始めてしまう)。
        s.setTarget(far);
        helper.runAfterDelay(1L, () -> {
            s.combatTick(far);
            if (s.isActing()) {
                helper.fail("原点から 4.3 の相手(届かない)にバッシュを振った(動作 " + s.getAction() + ")");
                return;
            }
            s.setTarget(near);
            s.combatTick(near);
            int act = s.getAction();
            if (act != ScutimnesEntity.ACT_BASH_L && act != ScutimnesEntity.ACT_BASH_R) {
                helper.fail("正面の相手(原点から 3.0)にバッシュを振っていない(動作 " + act + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 尾は、アニメの尾先が実際に通る弧(真後ろから払う側へ 62°、{@code points.json} の実測)に体が掛かる相手にだけ振る
     * (2026-09-24、統合で足した)。背後(正面から 100° 以上)でも、弧の外(真後ろから 75°、6.5 ブロック先)の相手には振らず、
     * 同じ距離の真後ろから 50° の相手には、その側へ払う {@code tail_l} を振る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void tailOnlyForTargetsInsideSweep(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 3);
        s.forceShieldDown(true);
        // 原点は (3.5, 2, 3.5)、向き 0 = 正面 +Z、真後ろ −Z、左 +X。真後ろから左へ θ・距離 r の点。
        Player outside = mockAt(helper, 3.5D + 6.5D * Math.sin(Math.toRadians(75.0D)), 2.0D,
                3.5D - 6.5D * Math.cos(Math.toRadians(75.0D)));
        Player inside = mockAt(helper, 3.5D + 6.5D * Math.sin(Math.toRadians(50.0D)), 2.0D,
                3.5D - 6.5D * Math.cos(Math.toRadians(50.0D)));
        // 標的はすぐに渡す(標的なしの tick があると盾を上げ始めてしまう)。
        s.setTarget(outside);
        helper.runAfterDelay(1L, () -> {
            s.combatTick(outside);
            if (s.isActing()) {
                helper.fail("尾の届かない真後ろから 75° の相手に振った(動作 " + s.getAction() + ")");
                return;
            }
            s.setTarget(inside);
            s.combatTick(inside);
            if (s.getAction() != ScutimnesEntity.ACT_TAIL_L) {
                helper.fail("左後ろ 50° の相手に左へ払う尾(tail_l)を振っていない(動作 " + s.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * TaCZ(銃 mod)の弾の「防具貫通ぶん」も、正面の盾で弾き、ガード中は溜まる(2026-09-24、レビューの指摘)。TaCZ は 1 発を 2 回の hurt に
     * 分けて当て、2 回目の種類 {@code tacz:bullet_ignore_armor} を {@code #minecraft:bypasses_armor}(→ バニラの {@code #bypasses_shield})に
     * 入れているので、以前はこの 2 回目が盾を素通りしていた。開発環境に TaCZ は無いので、同じ名前・同じタグのダメージの種類を
     * <b>テストの中だけの登録簿</b>で作って当てる(世界の登録簿には触らない)。弾のエンティティの代わりに矢(世界には入れない)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void taczArmorPiercingShareIsDeflectedToo(GameTestHelper helper) {
        Player shooter = mockAt(helper, 3.5D, 2.0D, 6.5D);
        Player behind = mockAt(helper, 3.5D, 2.0D, -3.5D);
        ScutimnesEntity s = placeShielded(helper, 1, shooter);
        helper.runAfterDelay(2L, () -> {
            Level level = helper.getLevel();
            MappedRegistry<DamageType> registry = new MappedRegistry<>(Registries.DAMAGE_TYPE, Lifecycle.experimental());
            Holder.Reference<DamageType> pierce = registry.register(ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath("tacz", "bullet_ignore_armor")), new DamageType("bullet", 0.1F), RegistrationInfo.BUILT_IN);
            registry.bindTags(Map.of(DamageTypeTags.BYPASSES_ARMOR, List.<Holder<DamageType>>of(pierce),
                    DamageTypeTags.BYPASSES_SHIELD, List.<Holder<DamageType>>of(pierce)));
            Arrow fromFront = new Arrow(EntityType.ARROW, level);
            fromFront.setPos(helper.absoluteVec(new Vec3(3.5D, 2.7D, 4.5D)));
            fromFront.setDeltaMovement(0.0D, 0.0D, -3.0D);
            DamageSource front = new DamageSource(pierce, fromFront, shooter);
            if (!front.is(DamageTypeTags.BYPASSES_SHIELD)) {
                helper.fail("テストの前提が崩れた: 仮の TaCZ のダメージが #bypasses_shield に入っていない");
                return;
            }
            float before = s.getHealth();
            s.hurt(front, 10.0F);
            if (s.getHealth() != before) {
                helper.fail("正面から来た TaCZ の防具貫通ぶんが盾を抜けた(減った量 " + (before - s.getHealth()) + ")");
                return;
            }
            s.forceAction(ScutimnesEntity.ACT_GUARD_START);
            s.hurt(front, 10.0F);
            if (s.getHealth() != before || Math.abs(s.getGuardStored() - 10.0F) > 0.01F) {
                helper.fail("ガード中の TaCZ の防具貫通ぶんが溜まっていない(溜め " + s.getGuardStored() + "、減った量 "
                        + (before - s.getHealth()) + ")");
                return;
            }
            // 背面から撃てば盾は無い。防具も貫く(防御 20 でも 10 がそのまま)。
            Arrow fromBehind = new Arrow(EntityType.ARROW, level);
            fromBehind.setPos(helper.absoluteVec(new Vec3(3.5D, 2.7D, -1.5D)));
            fromBehind.setDeltaMovement(0.0D, 0.0D, 3.0D);
            s.invulnerableTime = 0;
            s.hurt(new DamageSource(pierce, fromBehind, behind), 10.0F);
            if (Math.abs((before - s.getHealth()) - 10.0F) > 0.01F) {
                helper.fail("背面から来た TaCZ の防具貫通ぶんが 10 通っていない(減った量 " + (before - s.getHealth()) + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * この MOD の機関砲台の弾も飛び道具として正面の盾で弾く(撃った点 = 砲耳で正面を判定する。2026-09-24、レビューの指摘)。
     * 以前は弾に出どころも位置も無いので、正面からでも満額入っていた。背面から撃てば通る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void autocannonShotsFromFrontAreDeflected(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 4.5D);
        ScutimnesEntity s = placeShielded(helper, 1, front);
        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            float before = s.getHealth();
            s.hurt(ModDamageTypes.autocannon(level, helper.absoluteVec(new Vec3(3.5D, 3.2D, 9.5D))), 15.0F);
            if (s.getHealth() != before) {
                helper.fail("正面から撃った機関砲台の弾が盾を抜けた(減った量 " + (before - s.getHealth()) + ")");
                return;
            }
            DamageSource fromBehind = ModDamageTypes.autocannon(level, helper.absoluteVec(new Vec3(3.5D, 3.2D, -6.5D)));
            s.hurt(fromBehind, 15.0F);
            float lost = before - s.getHealth();
            if (Math.abs(lost - afterArmor(s, fromBehind, 15.0F)) > 0.01F) {
                helper.fail("背面から撃った機関砲台の弾が通っていない(減った量 " + lost + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 標的が居ないときに機関砲台に撃たれている間は、砲台へ向けて盾を展開する(ユーザー指定 2026-09-24)。後ろ(少し右寄り)の砲耳から
     * 5 tick ごとに撃ち、盾を下ろすこと・体が砲台へ向き直ること(1 tick 1.5° なので 180° 近く回るのに 6 秒ほど)・撃つのをやめて
     * 3 秒 + 上げる動作の後に盾を上げることを見る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 360)
    public static void facesTurretWithShieldWhenNoTarget(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 3);
        Vec3 trunnion = helper.absoluteVec(new Vec3(5.5D, 3.2D, -9.5D));
        float wantYaw = (float) (Mth.atan2(trunnion.z - s.getZ(), trunnion.x - s.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        helper.onEachTick(() -> {
            // 200 tick まで撃ち続ける(5 tick に 1 発、機関砲台と同じ間隔)。
            if (helper.getTick() < 200 && helper.getTick() % 5 == 0 && s.isAlive()) {
                s.hurt(ModDamageTypes.autocannon(helper.getLevel(), trunnion), 1.0F);
            }
        });
        helper.runAfterDelay(30L, () -> {
            if (!s.isShieldDown()) {
                helper.fail("標的なしで機関砲台に撃たれているのに盾を下ろしていない");
            }
        });
        helper.runAfterDelay(195L, () -> {
            if (!s.isShieldDown()) {
                helper.fail("撃たれ続けている間に盾を上げた");
                return;
            }
            float off = Math.abs(Mth.wrapDegrees(s.getYRot() - wantYaw));
            if (off > 5.0F) {
                helper.fail("砲台へ向き直っていない(向き " + s.getYRot() + "、砲台の向き " + wantYaw + "、ずれ " + off + "°)");
            }
        });
        // 最後の 1 発は 195 tick。覚えている 60 tick が切れてから盾を上げる動作(16 tick)を終えるまで待つ。
        helper.runAfterDelay(300L, () -> {
            if (s.isShieldDown() || s.isActing()) {
                helper.fail("撃たれなくなってから盾を上げていない(下ろしている " + s.isShieldDown() + "、動作 " + s.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 正面へ投げた負傷のスプラッシュポーションは跳ね返らず、盾の面で割れて効く(状態異常は盾に関係なく通る —— ユーザー指定
     * 「盾に効くのは剣による攻撃・状態異常・炎だけ」。2026-09-24、レビューの指摘)。ウィンドチャージも跳ね返さずに当たらせる
     * (バニラのブリーズと同じ。撃ってから 5 tick は跳ね返せず体をすり抜けていた)。矢は跳ね返す。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void frontSplashPotionIsNotDeflected(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 5.5D);
        ScutimnesEntity s = placeShielded(helper, 2, front);
        Level level = helper.getLevel();
        helper.runAfterDelay(2L, () -> {
            // 当たる瞬間の判定だけ先に(世界には入れない)。
            Vec3 ahead = helper.absoluteVec(new Vec3(3.5D, 2.7D, 4.2D));
            ThrownPotion probe = new ThrownPotion(level, ahead.x, ahead.y, ahead.z);
            probe.setDeltaMovement(0.0D, 0.0D, -1.0D);
            WindCharge wind = new WindCharge(level, ahead.x, ahead.y, ahead.z, new Vec3(0.0D, 0.0D, -1.0D));
            if (s.deflection(probe) != ProjectileDeflection.NONE || s.deflection(wind) != ProjectileDeflection.NONE) {
                helper.fail("正面から来たポーション・ウィンドチャージを跳ね返そうとしている");
                return;
            }
            Arrow arrow = new Arrow(EntityType.ARROW, level);
            arrow.setPos(ahead);
            arrow.setDeltaMovement(0.0D, 0.0D, -1.5D);
            if (s.deflection(arrow) != ProjectileDeflection.REVERSE) {
                helper.fail("正面から来た矢を跳ね返していない");
                return;
            }
            // 本物を投げる。当たり判定(体格込み)の前面は z = 2.5 + 1.44。その 2 ブロック先から。
            Vec3 from = helper.absoluteVec(new Vec3(3.5D, 2.7D, 6.2D));
            ThrownPotion potion = new ThrownPotion(level, from.x, from.y, from.z);
            potion.setItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING));
            potion.setDeltaMovement(0.0D, 0.0D, -1.0D);
            level.addFreshEntity(potion);
        });
        helper.runAfterDelay(14L, () -> {
            // 負傷 I の直撃は 6(魔法は防具を通る)。跳ね返れば手前の壁で割れて届かない(4 ブロックより遠い)。
            float lost = s.getMaxHealth() - s.getHealth();
            if (lost < 5.9F) {
                helper.fail("正面へ投げた負傷のポーションが効いていない(減った量 " + lost + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * ガード中に弾いた矢は、当たっていたら入ったはずの量を溜める —— 撃った弓の射撃ダメージ増加も入れる(2026-09-24、レビューの指摘)。
     * 射撃ダメージ増加 V(基本 2 + 2.5)の矢を速さ約 1.49 で正面から: ceil(1.49 × 4.5) = 7(以前は ceil(1.49 × 2) = 3 しか溜まらなかった)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void guardStoresEnchantedArrowDamage(GameTestHelper helper) {
        Player front = mockAt(helper, 3.5D, 2.0D, 5.5D);
        ScutimnesEntity s = placeShielded(helper, 2, front);
        Level level = helper.getLevel();
        helper.runAfterDelay(1L, () -> s.forceAction(ScutimnesEntity.ACT_GUARD_START));
        helper.runAfterDelay(2L, () -> {
            ItemStack bow = new ItemStack(Items.BOW);
            bow.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.POWER), 5);
            Vec3 from = helper.absoluteVec(new Vec3(3.5D, 2.7D, 6.2D));
            Arrow arrow = new Arrow(level, from.x, from.y, from.z, new ItemStack(Items.ARROW), bow);
            arrow.setDeltaMovement(0.0D, 0.0D, -1.5D);
            level.addFreshEntity(arrow);
        });
        helper.runAfterDelay(10L, () -> {
            if (s.getHealth() < s.getMaxHealth()) {
                helper.fail("ガード中の正面の矢が当たっている(体力 " + s.getHealth() + ")");
                return;
            }
            if (Math.abs(s.getGuardStored() - 7.0F) > 0.01F) {
                helper.fail("射撃ダメージ増加 V の矢で 7 溜まるはずが " + s.getGuardStored());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * バッシュは当たる高さの相手にだけ振る(2026-09-24、レビューの指摘)。突き出した盾の上端 + 1(体格 2.88 で 3.52)より高い段の上の相手には
     * 振らず(経路を探し直す)、それより低ければ振る。以前は振り始めの高さの条件が 4.0 まであり、2.4〜4.0 上の相手に空振りを繰り返していた。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void bashOnlyAtHeightsItCanHit(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 1);
        s.forceShieldDown(true);
        // 原点は (3.5, 2, 1.5)。正面 2 ブロック、足元より 4.5 / 3.0 上(世界に入れない偽のプレイヤー)。
        Player high = mockAt(helper, 3.5D, 6.5D, 3.5D);
        Player low = mockAt(helper, 3.5D, 5.0D, 3.5D);
        s.setTarget(high);
        helper.runAfterDelay(1L, () -> {
            s.combatTick(high);
            if (s.isActing()) {
                helper.fail("4.5 ブロック上(当たらない高さ)の相手にバッシュを振った(動作 " + s.getAction() + ")");
                return;
            }
            s.setTarget(low);
            s.combatTick(low);
            int act = s.getAction();
            if (act != ScutimnesEntity.ACT_BASH_L && act != ScutimnesEntity.ACT_BASH_R) {
                helper.fail("3 ブロック上(当たる高さ)の正面の相手にバッシュを振っていない(動作 " + act + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * バッシュの溜めの間は踏み込まず、向きも変えない(2026-09-24、レビューの指摘。アニメは 8 本の足を踏ん張ったまま体を後ろへためるので、
     * 本体を前へ動かしたり回したりすると接地した足が滑る)。正面から左へ 20°・2.2 ブロックの相手が溜めの間に 1 ブロック下がっても、
     * その場・その向きのまま振り終える。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void bashWindupHoldsStill(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 1);
        s.forceShieldDown(true);
        double side = 2.2D * Math.sin(Math.toRadians(20.0D));
        double ahead = 2.2D * Math.cos(Math.toRadians(20.0D));
        Player target = mockAt(helper, 3.5D + side, 2.0D, 1.5D + ahead);
        s.setTarget(target);
        Vec3[] start = {null};
        helper.runAfterDelay(1L, () -> {
            s.combatTick(target);
            if (s.getAction() != ScutimnesEntity.ACT_BASH_L) {
                helper.fail("左前 20° の相手に左の盾のバッシュを振っていない(動作 " + s.getAction() + ")");
                return;
            }
            start[0] = s.position();
        });
        helper.runAfterDelay(4L, () -> target.setPos(target.position().add(0.0D, 0.0D, 1.0D)));
        helper.runAfterDelay(26L, () -> {
            Vec3 now = s.position();
            double moved = Math.sqrt((now.x - start[0].x) * (now.x - start[0].x) + (now.z - start[0].z) * (now.z - start[0].z));
            if (moved > 0.01D) {
                helper.fail("バッシュの溜めの間に本体が " + moved + " ブロック動いた");
                return;
            }
            if (Math.abs(Mth.wrapDegrees(s.getYRot())) > 0.01F) {
                helper.fail("バッシュの溜めの間に向きを変えた(" + s.getYRot() + "°)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 水に浸かっても、浮く Goal({@link FloatGoal}、優先度 0・JUMP だけ)が追跡の Goal を止めない(2026-09-24、レビューの指摘)。以前は追跡の
     * Goal が JUMP を握っていて、水に入った瞬間に浮く Goal に止められ、水から出るまで追わず・殴らず・尾も振らなかった。
     * テスト場の床の上 2 段を水で満たし、標的(偽のプレイヤー)を渡して、浮く Goal が動いている間は追跡の Goal も動いていることを見る。
     * この MOD が全陸生種に差し込む岸へ泳ぐ Goal({@link EscapeWaterGoal}、優先度 0・MOVE と LOOK)は、岸が近ければ意図して追跡より先に動く
     * (水ぐされで溶ける前に上がる)ので外し、浮く Goal と追跡の Goal の取り合いだけを見る(岸が無い・あきらめた間に当たる)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void huntKeepsGoingInWater(GameTestHelper helper) {
        flood(helper);
        ScutimnesEntity s = helper.spawn(ModEntities.SCUTIMNES.get(), new BlockPos(3, 2, 3));
        s.targetSelector.removeAllGoals(goal -> true);
        onlyFloatAndHunt(s);
        Player front = mockAt(helper, 3.5D, 2.0D, 6.5D);
        s.setTarget(front);
        huntAndFloatTogether(helper, s);
    }

    /** 浮く Goal と追跡の Goal だけを残す(この MOD が差し込む岸へ泳ぐ Goal・ぶらつき・見回しなどを外す)。 */
    static void onlyFloatAndHunt(Mob mob) {
        mob.goalSelector.removeAllGoals(g -> !(g instanceof FloatGoal) && !g.getClass().getSimpleName().equals("HuntGoal"));
    }

    /**
     * 動作の途中(尾の薙ぎ)は、よその移動・視線の希望で向きを変えない(2026-09-24、レビューの指摘)。水に落ちて岸へ泳ぐ Goal
     * ({@link EscapeWaterGoal})のように追跡の Goal より先に動く Goal が真横へ動けと指図しても、尾を振り終えるまで向きはそのまま
     * (尾のアニメは足を踏ん張ったままなので、回ると足が滑る)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void actionsIgnoreOtherTurnWishes(GameTestHelper helper) {
        ScutimnesEntity s = place(helper, 3);
        s.forceShieldDown(true);
        Player side = mockAt(helper, 6.5D, 2.0D, 3.5D);
        helper.runAfterDelay(1L, () -> s.forceAction(ScutimnesEntity.ACT_TAIL_L));
        helper.onEachTick(() -> {
            if (s.getAction() == ScutimnesEntity.ACT_TAIL_L) {
                s.getMoveControl().setWantedPosition(side.getX(), s.getY(), side.getZ(), 1.0D);
                s.getLookControl().setLookAt(side);
                s.lookAt(side, 180.0F, 90.0F);
            }
        });
        helper.runAfterDelay(25L, () -> {
            if (s.getAction() != ScutimnesEntity.ACT_TAIL_L) {
                helper.fail("テストの前提が崩れた: 尾を振っている最中でない(動作 " + s.getAction() + ")");
                return;
            }
            if (Math.abs(Mth.wrapDegrees(s.getYRot())) > 0.01F) {
                helper.fail("尾を振っている途中に、よその希望で向きが変わった(" + s.getYRot() + "°)");
                return;
            }
            helper.succeed();
        });
    }

    /** テスト場の床の上 2 段(相対 y 2〜3)を水源で満たす。 */
    static void flood(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                for (int y = 2; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                }
            }
        }
    }

    /**
     * 水の中の {@code mob} を 40 tick 見て、浮く Goal が一度は動き、しかも浮く Goal が動いている間は必ず追跡の Goal も動いていることを確かめる
     * (水面で浮き沈みすると浮く Goal は付いたり消えたりするので、1 つの tick だけでは見ない)。
     */
    static void huntAndFloatTogether(GameTestHelper helper, Mob mob) {
        boolean[] sawFloat = {false};
        long[] conflictAt = {-1L};
        String[] conflictGoals = {""};
        helper.onEachTick(() -> {
            if (helper.getTick() < 3L) {
                return;
            }
            if (runningGoal(mob, "FloatGoal")) {
                sawFloat[0] = true;
                if (!runningGoal(mob, "HuntGoal") && conflictAt[0] < 0L) {
                    conflictAt[0] = helper.getTick();
                    StringBuilder sb = new StringBuilder();
                    mob.goalSelector.getAvailableGoals().forEach(g -> sb.append(g.getGoal().getClass().getSimpleName())
                            .append(g.isRunning() ? "(動)" : "").append(g.getFlags()).append(' '));
                    conflictGoals[0] = sb + " 標的 " + mob.getTarget();
                }
            }
        });
        helper.runAfterDelay(43L, () -> {
            if (!sawFloat[0]) {
                helper.fail("テストの前提が崩れた: 水の中で浮く Goal が一度も動いていない");
                return;
            }
            if (conflictAt[0] >= 0L) {
                helper.fail(conflictAt[0] + " tick 目に、水の中で追跡の Goal が止まっていた(浮く Goal に JUMP を取られた?): " + conflictGoals[0]);
                return;
            }
            helper.succeed();
        });
    }

    /** {@code mob} の Goal のうち、クラスの単純名が {@code simpleName} のものが動いているか(HuntGoal は entity パッケージの外から見えないので名前で)。 */
    static boolean runningGoal(Mob mob, String simpleName) {
        return mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.isRunning() && g.getGoal().getClass().getSimpleName().equals(simpleName));
    }

    private ScutimnesGameTests() {
    }
}
