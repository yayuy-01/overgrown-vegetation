package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.entity.ErebomnesCavern;
import com.yayuy.overgrownvegetation.entity.ErebomnesEntity;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * エレボムネス(グループ5、巨大な洞窟の空中を泳ぐアノマロカリス類、2026-10-01)のテスト。守りたいのは採用の案(ユーザー改変)の芯:
 * <ul>
 *     <li><b>すくい上げるように掴み、噛みついて継続ダメージ</b>(掴む・噛む・時間で放す・振りほどき・大きな一撃で放す・払いは掴まない)。</li>
 *     <li><b>空中をゆっくり泳ぐ</b>(重力なしで落ちない)。</li>
 *     <li><b>巨大な洞窟の中だけ</b>(狭い所には湧かない・洞窟から空の見える所へ出る行き先を選ばない)。</li>
 *     <li>掴んでいる途中で保存して読み込んでも壊れない。頭の部位を殴っても体力は共通で、同じ攻撃は 1 回だけ。</li>
 * </ul>
 * 掴みのテストは実寸(体格 2)で、テスト場の南の端(z ≒ 1.8)に北を背にして置き、的のハスク(日光で燃えない)を正面の grab_point に立たせる
 * (体の箱 z 0.0〜3.5・頭の部位 z 4.0〜6.2 がテスト場 7×7 に収まる)。本体は NoAI で動かさない(動作は aiStep で進む)。
 * 時刻は {@link ErebomnesEntity} の定数から計算する(数値を変えてもテストを直さなくてよいように)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ErebomnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ErebomnesGameTests.class);
        }

        private Registration() {
        }
    }

    /**
     * 自分たちだけのバッチ。ほかのバッチの射程の長い設備(機関砲台は 32 ブロック)が隣のテスト場のこの Mob を撃つと、
     * 抱えている間の被弾で振りほどき・大きな一撃が起きて時刻のテストが揺れる(メモリ gametest-arena-layout)。
     * 時刻を見るテストは本体も無敵にしてある(振りほどきのテストだけ殴られる)。
     */
    private static final String BATCH = "erebomnes";
    /** 実寸の体格(mobs.json の scale。描画倍率 2 = NOTES の値)。 */
    private static final double SCALE = 2.0D;
    /** 払い・掴み・時間切れなどで「もう終わっているはず」を見るまでの余裕(tick。テストとエンティティの tick の前後のずれを吸う)。 */
    private static final int SLACK = 6;

    private record Setup(ErebomnesEntity mob, Husk husk) {
    }

    /**
     * 本体を 1 体置く(世界に足す前に体格と NoAI を決める —— 足した瞬間の SpawnFit の押し出しを NoAI で避け、体格 1 の箱で判定させない)。
     * 体格は個体差の記録({@code SIZE_VARIANCE})にも入れる —— 入れないと、世界に入った瞬間に {@code MobSizeVariance} が
     * mobs.json の体格(2)で上書きする(体格 1 のテストが実寸で走ってしまう)。
     *
     * @param absPos 足元(ワールド座標)
     * @param yaw    向き(度。0 = +Z)
     */
    private static ErebomnesEntity spawnMob(GameTestHelper helper, Vec3 absPos, float yaw, double scale, boolean noAi) {
        ServerLevel level = helper.getLevel();
        ErebomnesEntity mob = ModEntities.EREBOMNES.get().create(level);
        if (mob == null) {
            throw new IllegalStateException("エレボムネスを作れない");
        }
        mob.getAttribute(Attributes.SCALE).setBaseValue(scale);
        mob.setData(ModAttachments.SIZE_VARIANCE, (float) scale);
        // 箱も今の体格に合わせておく。最初の tick に SCALE の反映で箱が大きくなると、バニラの fudgePositionAfterSizeChange が
        // 箱の中心を保つように原点を (2.1 − 1.05) / 2 = 0.525 下げる(抱えたハスクの足が床に 0.525 埋まって落ちた。2026-10-01)。
        // 実体のコンストラクタも既定の体格(2)で箱を作り直すようにした(点検係)が、テストが体格を変えたときのためにここでも合わせる。
        // 世界に足す前(firstTick の間)なら押し下げない。
        mob.refreshDimensions();
        mob.setNoAi(noAi);
        mob.setPersistenceRequired();
        mob.moveTo(absPos.x, absPos.y, absPos.z, yaw, 0.0F);
        level.addFreshEntity(mob);
        return mob;
    }

    /**
     * ハスクを床(y=2)に立たせ、その胸が grab_point に来る位置に本体(実寸・NoAI・+Z 向き)を置く。
     *
     * @param invulnerableHusk ハスクを無敵に(噛みで倒れて離れるのと、時間切れ・振りほどきを区別する)
     * @param invulnerableMob  本体を無敵に(よそからの被弾で振りほどきが起きないように。振りほどきのテストでは false)
     */
    private static Setup grabSetup(GameTestHelper helper, boolean invulnerableHusk, boolean invulnerableMob) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 4.8D));
        husk.setInvulnerable(invulnerableHusk);
        Vec3 origin = ErebomnesEntity.originForPrey(husk.position(), husk.getBbHeight(), 0.0F, SCALE);
        ErebomnesEntity mob = spawnMob(helper, origin, 0.0F, SCALE, true);
        mob.setInvulnerable(invulnerableMob);
        return new Setup(mob, husk);
    }

    /** 抱え終わる(bite_hold に入る)tick。すくいの当たりで抱え、scoop の終わりから噛み始める。 */
    private static final int HOLD_START = ErebomnesEntity.SCOOP_TICKS;

    // ------------------------------------------------------------------ 掴み・噛み

    /** 掴み: 当たり(0.9 秒)でハスクを抱え、bite_hold に入ってから噛む(噛む前は無傷)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = BATCH)
    public static void scoopGrabsHuskThenBites(GameTestHelper helper) {
        Setup s = grabSetup(helper, false, true);
        s.mob().forceScoop(s.husk(), true);
        helper.runAfterDelay(ErebomnesEntity.SCOOP_HIT_TICK - 4, () -> {
            helper.assertTrue(s.husk().getVehicle() == null, "当たりの前に掴んでいる");
        });
        helper.runAfterDelay(ErebomnesEntity.SCOOP_HIT_TICK + 4, () -> {
            helper.assertTrue(s.husk().getVehicle() == s.mob(), "すくいの当たりでハスクを抱えていない");
            helper.assertTrue(s.mob().isHoldingPrey(), "抱えている状態になっていない");
            helper.assertTrue(s.husk().getHealth() >= s.husk().getMaxHealth(), "噛む前に傷ついている(掴みは払いの打撃を入れない)");
            // 抱えた獲物の足は床に埋まらない(NOTES「足元を床から 1.2 以上離す」の芯)
            helper.assertTrue(s.husk().getY() >= helper.absoluteVec(new Vec3(0.0D, 2.0D, 0.0D)).y - 0.05D,
                    "抱えたハスクの足が床に埋まっている: " + s.husk().getY());
        });
        helper.runAfterDelay(HOLD_START + 2, () -> {
            helper.assertTrue(s.mob().getAction() == ErebomnesEntity.ACT_HOLD, "bite_hold に入っていない(動作 " + s.mob().getAction() + ")");
        });
        helper.runAfterDelay(HOLD_START + ErebomnesEntity.BITE_TICK_A + SLACK, () -> {
            helper.assertTrue(s.husk().getHealth() < s.husk().getMaxHealth(), "噛んでいない(1 回目の噛み 0.42 秒を過ぎても無傷)");
            helper.assertTrue(s.husk().getVehicle() == s.mob(), "噛んだら離してしまった");
            helper.succeed();
        });
    }

    /** 払い(通常攻撃): 当たりで打つが掴まない。scoop の終わりから release で静止へ戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = BATCH)
    public static void rakeHitsWithoutHolding(GameTestHelper helper) {
        Setup s = grabSetup(helper, false, true);
        s.mob().forceScoop(s.husk(), false);
        helper.runAfterDelay(ErebomnesEntity.SCOOP_HIT_TICK + 4, () -> {
            helper.assertTrue(s.husk().getHealth() < s.husk().getMaxHealth(), "払いが当たっていない");
            helper.assertTrue(s.husk().getVehicle() == null, "払いなのに掴んだ");
            helper.assertFalse(s.mob().isHoldingPrey(), "払いなのに抱えている");
        });
        helper.runAfterDelay(ErebomnesEntity.SCOOP_TICKS + 3, () -> {
            helper.assertTrue(s.mob().getAction() == ErebomnesEntity.ACT_RELEASE, "払いの後に release へ移っていない(動作 " + s.mob().getAction() + ")");
        });
        helper.runAfterDelay(ErebomnesEntity.SCOOP_TICKS + ErebomnesEntity.RELEASE_TICKS + SLACK, () -> {
            helper.assertTrue(s.mob().getAction() == ErebomnesEntity.ACT_NONE, "release が終わっていない(動作 " + s.mob().getAction() + ")");
            helper.succeed();
        });
    }

    /** 時間切れ: 抱えて {@link ErebomnesEntity#HOLD_MAX_TICKS} で離す(ハスクは無敵にして、噛みで倒れて離れるのと区別する)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260, batch = BATCH)
    public static void holdEndsOnTimeLimit(GameTestHelper helper) {
        Setup s = grabSetup(helper, true, true);
        s.mob().forceScoop(s.husk(), true);
        int releaseAt = HOLD_START + ErebomnesEntity.HOLD_MAX_TICKS;
        int dropAt = releaseAt + ErebomnesEntity.TRANSITION_TICKS + ErebomnesEntity.RELEASE_DROP_TICK;
        helper.runAfterDelay(releaseAt - SLACK, () -> {
            helper.assertTrue(s.husk().getVehicle() == s.mob(), "時間切れの前に離した(抱えた tick " + s.mob().getHoldTicks() + ")");
        });
        helper.runAfterDelay(dropAt + SLACK, () -> {
            helper.assertTrue(s.husk().getVehicle() == null, "時間切れで離していない(動作 " + s.mob().getAction() + ")");
            helper.assertFalse(s.mob().isHoldingPrey(), "離したのに抱えている状態のまま");
            helper.succeed();
        });
    }

    /**
     * 振りほどき: 抱えている間に削られた量が {@link ErebomnesEntity#BREAK_FREE_DAMAGE} に届いたら離す。一撃は「大きな一撃」の 6 割
     * (一撃では離さない)で 2 tick おきに殴り、実際に減った体力を足していく(適応などで減りが変わっても、届く前には離さない・届けば離す)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140, batch = BATCH)
    public static void breaksFreeByAccumulatedDamage(GameTestHelper helper) {
        Setup s = grabSetup(helper, true, false);
        s.mob().forceScoop(s.husk(), true);
        float hit = ErebomnesEntity.BIG_HIT_DAMAGE * 0.6F;
        int maxHits = (int) Math.ceil(ErebomnesEntity.BREAK_FREE_DAMAGE / hit) * 2;
        int first = HOLD_START + 10;
        float[] lost = new float[1];
        int[] releasedAt = {-1};
        for (int i = 0; i < maxHits; i++) {
            int at = first + i * 2;
            helper.runAfterDelay(at, () -> {
                if (releasedAt[0] >= 0) {
                    return;
                }
                helper.assertTrue(s.mob().getAction() == ErebomnesEntity.ACT_HOLD, "振りほどきの量に届く前に離した(削った量 " + lost[0] + ")");
                float before = s.mob().getHealth();
                s.mob().invulnerableTime = 0;
                s.mob().hurt(helper.getLevel().damageSources().magic(), hit);
                lost[0] += before - s.mob().getHealth();
                if (s.mob().getAction() == ErebomnesEntity.ACT_RELEASE) {
                    releasedAt[0] = at;
                    helper.assertTrue(lost[0] >= ErebomnesEntity.BREAK_FREE_DAMAGE - 1.0E-3F, "振りほどきの量に届く前に離した: " + lost[0]);
                }
            });
        }
        int lastHit = first + (maxHits - 1) * 2;
        helper.runAfterDelay(lastHit + ErebomnesEntity.TRANSITION_TICKS + ErebomnesEntity.RELEASE_DROP_TICK + SLACK, () -> {
            helper.assertTrue(releasedAt[0] >= 0, "振りほどきの量に届いても離さない(削った量 " + lost[0] + ")");
            helper.assertTrue(s.husk().getVehicle() == null, "離し始めたのにハスクを降ろしていない");
            helper.succeed();
        });
    }

    /** 大きく殴られる: 抱えている間に一撃で {@link ErebomnesEntity#BIG_HIT_DAMAGE} 以上削られたら、その一撃で離し始める。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = BATCH)
    public static void bigHitMakesItLetGo(GameTestHelper helper) {
        Setup s = grabSetup(helper, true, false);
        s.mob().forceScoop(s.husk(), true);
        int at = HOLD_START + 10;
        helper.runAfterDelay(at, () -> {
            helper.assertTrue(s.mob().isHoldingPrey(), "殴る前に抱えていない");
            float before = s.mob().getHealth();
            s.mob().invulnerableTime = 0;
            s.mob().hurt(helper.getLevel().damageSources().magic(), ErebomnesEntity.BIG_HIT_DAMAGE * 1.5F);
            float lost = before - s.mob().getHealth();
            helper.assertTrue(lost >= ErebomnesEntity.BIG_HIT_DAMAGE, "テストの一撃が大きな一撃にならない(減った量 " + lost + ")");
            helper.assertTrue(s.mob().getAction() == ErebomnesEntity.ACT_RELEASE, "大きな一撃で離し始めない(動作 " + s.mob().getAction() + ")");
        });
        helper.runAfterDelay(at + ErebomnesEntity.TRANSITION_TICKS + ErebomnesEntity.RELEASE_DROP_TICK + SLACK, () -> {
            helper.assertTrue(s.husk().getVehicle() == null, "大きな一撃で離していない");
            helper.succeed();
        });
    }

    /**
     * 掴んでいる途中で保存して読み込み直す(チャンクのアンロード・プレイヤーのログアウトと同じ形: 乗客ごと NBT にして作り直す)。
     * 読み込んだ個体はハスクを抱えたままで、時間切れは読み込み前の続きから数える(やり直さない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 280, batch = BATCH)
    public static void holdSurvivesSaveAndLoad(GameTestHelper helper) {
        Setup s = grabSetup(helper, true, true);
        s.mob().forceScoop(s.husk(), true);
        int saveAt = HOLD_START + 20;
        ErebomnesEntity[] again = new ErebomnesEntity[1];
        int[] heldBefore = new int[1];
        helper.runAfterDelay(saveAt, () -> {
            helper.assertTrue(s.mob().isHoldingPrey() && s.husk().getVehicle() == s.mob(), "保存の前に抱えていない");
            CompoundTag tag = new CompoundTag();
            helper.assertTrue(s.mob().saveAsPassenger(tag), "保存できない");
            heldBefore[0] = s.mob().getHoldTicks();
            s.mob().discard();
            s.husk().discard();
            ServerLevel level = helper.getLevel();
            Entity loaded = EntityType.loadEntityRecursive(tag, level, e -> e);
            helper.assertTrue(loaded instanceof ErebomnesEntity, "読み込めない");
            helper.assertTrue(level.tryAddFreshEntityWithPassengers(loaded), "読み込んだ個体を世界に足せない");
            again[0] = (ErebomnesEntity) loaded;
        });
        helper.runAfterDelay(saveAt + 5, () -> {
            ErebomnesEntity m = again[0];
            helper.assertTrue(m.getFirstPassenger() instanceof Husk, "読み込んだらハスクを抱えていない");
            helper.assertTrue(m.isHoldingPrey(), "読み込んだら抱えている状態が消えた");
            helper.assertTrue(m.getAction() == ErebomnesEntity.ACT_HOLD, "読み込んだら bite_hold でない(動作 " + m.getAction() + ")");
            helper.assertTrue(m.getHoldTicks() >= heldBefore[0], "抱えた時間がやり直しになった(" + heldBefore[0] + " → " + m.getHoldTicks() + ")");
        });
        // 読み込み前の続きから時間切れ(保存の時点で抱えて約 20 tick)。やり直していたら(読み込み + HOLD_MAX_TICKS まで離さない)
        // ここではまだ抱えている。予定はテストの頭で全部入れる(予定を実行中に足すと GameTestInfo の表を回している最中に書き換わる)。
        int dropAt = saveAt + (ErebomnesEntity.HOLD_MAX_TICKS - (saveAt - HOLD_START))
                + ErebomnesEntity.TRANSITION_TICKS + ErebomnesEntity.RELEASE_DROP_TICK;
        helper.runAfterDelay(dropAt + SLACK, () -> {
            ErebomnesEntity m = again[0];
            helper.assertTrue(m.getFirstPassenger() == null,
                    "読み込んだ後、時間切れで離していない(保存時 " + heldBefore[0] + " tick、今 " + m.getHoldTicks() + " tick)");
            helper.succeed();
        });
    }

    /**
     * 抱えている間は獲物を床から浮かせる(すくい上げ。NOTES「抱えている間、Mob の足元は床から 1.2 以上離す」)。浮かせるのは AI の泳ぎなので
     * AI ありの実寸で、標的を選ぶ Goal は外す(隣のテスト場の Mob を選ばない)。テスト場の天井(相対 y=8 のバリア)まで、持ち上げた体の上に 1.7 余る。
     * 模擬(この Mob の寄せの式)では 40 tick で獲物の足が床から 0.9 に落ち着く(寄る点の 0.25 手前で止まる)。抱えている間、足は床に埋まらない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140, batch = BATCH)
    public static void holdLiftsPreyOffFloor(GameTestHelper helper) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 4.8D));
        husk.setInvulnerable(true);
        Vec3 origin = ErebomnesEntity.originForPrey(husk.position(), husk.getBbHeight(), 0.0F, SCALE);
        ErebomnesEntity mob = spawnMob(helper, origin, 0.0F, SCALE, false);
        mob.setInvulnerable(true);
        mob.targetSelector.removeAllGoals(g -> true);
        mob.forceScoop(husk, true);
        double floor = helper.absoluteVec(new Vec3(0.0D, 2.0D, 0.0D)).y;
        double[] lowestFeet = {Double.MAX_VALUE};
        helper.onEachTick(() -> {
            if (husk.getVehicle() == mob) {
                lowestFeet[0] = Math.min(lowestFeet[0], husk.getY());
            }
        });
        helper.runAfterDelay(HOLD_START + 60, () -> {
            helper.assertTrue(husk.getVehicle() == mob && mob.isHoldingPrey(), "抱えていない(動作 " + mob.getAction() + ")");
            double lift = husk.getY() - floor;
            helper.assertTrue(lift >= ErebomnesEntity.HOLD_PREY_LIFT * SCALE * 0.6D, "獲物を床から浮かせていない(足と床の差 " + lift + ")");
            helper.assertTrue(mob.getY() - floor >= 1.2D, "抱えている間の足元が床から 1.2 離れていない: " + (mob.getY() - floor));
            helper.assertTrue(lowestFeet[0] >= floor - 0.05D, "抱えている間に獲物の足が床に埋まった: " + (lowestFeet[0] - floor));
            helper.succeed();
        });
    }

    /**
     * 抱えたまま倒れる: その場で獲物を降ろし(死体に吊ったままにしない)、倒れるアニメ(death)に入って、
     * {@link ErebomnesEntity#DEATH_TICKS} の間は消えずに残る(アニメを見せてから消える)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160, batch = BATCH)
    public static void deathDropsPreyAndLingers(GameTestHelper helper) {
        Setup s = grabSetup(helper, true, false);
        s.mob().forceScoop(s.husk(), true);
        int at = HOLD_START + 10;
        helper.runAfterDelay(at, () -> {
            helper.assertTrue(s.mob().isHoldingPrey(), "倒す前に抱えていない");
            s.mob().kill();
            helper.assertTrue(s.mob().isDeadOrDying(), "倒れていない");
            helper.assertTrue(s.husk().getVehicle() == null, "倒れたのに獲物を降ろしていない");
            helper.assertFalse(s.mob().isVehicle(), "倒れたのに乗客が残っている");
            helper.assertTrue(s.mob().getAction() == ErebomnesEntity.ACT_DEATH, "倒れるアニメ(death)に入っていない(動作 " + s.mob().getAction() + ")");
        });
        helper.runAfterDelay(at + ErebomnesEntity.DEATH_TICKS - SLACK, () -> {
            helper.assertFalse(s.mob().isRemoved(), "倒れるアニメ(" + ErebomnesEntity.DEATH_TICKS + " tick)の前に消えた");
        });
        helper.runAfterDelay(at + ErebomnesEntity.DEATH_TICKS + SLACK, () -> {
            helper.assertTrue(s.mob().isRemoved(), "倒れるアニメの後も消えない");
            helper.succeed();
        });
    }

    /**
     * 横取りしない・抱えられない相手: 別の個体が抱えている獲物は抱えない(乗っている相手は抱えない)。抱えている最中に 2 体目は抱えない。
     * 大きすぎる相手(ラヴェジャー 幅 1.95 > 1.6)は抱えない。素のハスクは抱えられる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20, batch = BATCH)
    public static void cannotSnatchHeldPreyOrCarryBigOnes(GameTestHelper helper) {
        Setup s = grabSetup(helper, true, true);
        helper.assertTrue(s.mob().grab(s.husk()), "ハスクを抱えられない");
        helper.assertTrue(s.husk().getVehicle() == s.mob(), "抱えたのに乗っていない");
        ServerLevel level = helper.getLevel();
        ErebomnesEntity other = ModEntities.EREBOMNES.get().create(level);
        Husk second = EntityType.HUSK.create(level);
        Ravager ravager = EntityType.RAVAGER.create(level);
        helper.assertTrue(other != null && second != null && ravager != null, "比べる相手を作れない");
        Vec3 at = s.husk().position();
        other.moveTo(s.mob().getX(), s.mob().getY(), s.mob().getZ(), 0.0F, 0.0F);
        second.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        ravager.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        try {
            helper.assertFalse(other.grab(s.husk()), "別の個体が抱えている獲物を横取りした");
            helper.assertTrue(s.husk().getVehicle() == s.mob(), "横取りで獲物が移った");
            helper.assertFalse(s.mob().grab(second), "抱えている最中に 2 体目を抱えた");
            helper.assertTrue(ErebomnesEntity.canCarry(second, SCALE), "素のハスクを抱えられる大きさと見ていない");
            helper.assertFalse(ErebomnesEntity.canCarry(ravager, SCALE), "ラヴェジャー(幅 1.95)を抱えられると見た");
            helper.assertFalse(ErebomnesEntity.canCarry(s.husk(), SCALE), "乗っている相手を抱えられると見た");
        } finally {
            other.discard();
            second.discard();
            ravager.discard();
        }
        helper.succeed();
    }

    /**
     * 洞窟の外へ連れ出されない・重い経路探索をしない: フィロセンスの誘導(地上のプレイヤーへ向かわせる)を受けない。
     * 航法は経路を引かず「もう着いている」空の経路を返す —— 標的を選ぶ Goal の「届かない相手」の見限りは null でなければ通り、
     * よそからの {@code moveTo} では動き出さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20, batch = BATCH)
    public static void ignoresGuidanceAndNeverPathfinds(GameTestHelper helper) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(1.5D, 2.0D, 5.5D));
        husk.setNoAi(true);
        ErebomnesEntity mob = spawnMob(helper, helper.absoluteVec(new Vec3(3.5D, 4.0D, 1.5D)), 0.0F, 1.0D, true);
        helper.assertFalse(mob.addEffect(new MobEffectInstance(ModMobEffects.PHYLLOSENS_GUIDANCE, 200)), "フィロセンスの誘導を受けた");
        helper.assertFalse(mob.hasEffect(ModMobEffects.PHYLLOSENS_GUIDANCE), "フィロセンスの誘導が付いている");
        Path path = mob.getNavigation().createPath(husk, 0);
        helper.assertTrue(path != null, "標的への経路が null(標的を選ぶ Goal が『届かない相手』として見限る)");
        helper.assertTrue(path.isDone(), "経路を引いた(空の経路でない: " + path.getNodeCount() + " ノード)");
        helper.assertFalse(mob.getNavigation().moveTo(husk, 1.0D), "よそからの moveTo で動き出した");
        helper.assertTrue(mob.getNavigation().isDone(), "航法が止まっていない");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 泳ぎ・当たり判定

    /**
     * 空中をゆっくり泳ぐ: 重力が無く、AI が動いていても(行き先も標的も無ければ)宙に留まって落ちない。G5 であること。
     * 体格 1(テスト場で床・天井・壁の押し返しが掛からない大きさ)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = BATCH)
    public static void floatsWithoutFalling(GameTestHelper helper) {
        Vec3 start = helper.absoluteVec(new Vec3(3.5D, 4.0D, 3.5D));
        ErebomnesEntity mob = spawnMob(helper, start, 0.0F, 1.0D, false);
        mob.removeFreeWill();
        mob.targetSelector.removeAllGoals(g -> true);
        helper.assertTrue(Adaptation.isAdaptive(mob.getType()), "group_5 に入っていない(適応が効かない)");
        helper.assertTrue(mob.isNoGravity(), "重力がある(空中を泳ぐ種)");
        helper.runAfterDelay(60, () -> {
            double dy = mob.getY() - start.y;
            helper.assertTrue(Math.abs(dy) < 0.25D, "宙に留まっていない(高さの変化 " + dy + ")");
            helper.assertFalse(mob.onGround(), "床に着いている");
            helper.succeed();
        });
    }

    /**
     * 頭の部位: 本体の箱より前にあり、殴ると本体の体力が減る(体力は共通)。同じ tick の同じダメージ源が頭と胴の両方に来ても 1 回だけ
     * (爆発・なぎ払い)。次の tick の攻撃はまた通る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40, batch = BATCH)
    public static void headPartSharesHealthAndCountsOnce(GameTestHelper helper) {
        ErebomnesEntity mob = spawnMob(helper, helper.absoluteVec(new Vec3(3.5D, 3.0D, 1.5D)), 0.0F, 1.0D, true);
        DamageSource magic = helper.getLevel().damageSources().magic();
        float[] afterFirst = new float[2];
        helper.runAfterDelay(3, () -> {
            AABB head = mob.getHead().getBoundingBox();
            AABB body = mob.getBoundingBox();
            helper.assertTrue(head.getCenter().z > body.maxZ, "頭の部位が本体の箱より前に無い(頭 " + head + " / 胴 " + body + ")");
            float start = mob.getHealth();
            mob.getHead().hurt(magic, 6.0F);
            float once = start - mob.getHealth();
            helper.assertTrue(once > 0.0F, "頭を殴っても本体の体力が減らない");
            mob.invulnerableTime = 0;
            mob.hurt(magic, 6.0F);
            helper.assertTrue(Math.abs(start - mob.getHealth() - once) < 0.01F, "同じ tick の同じダメージ源が頭と胴で 2 回通った: " + (start - mob.getHealth()));
            afterFirst[0] = mob.getHealth();
            afterFirst[1] = once;
        });
        helper.runAfterDelay(5, () -> {
            mob.invulnerableTime = 0;
            mob.hurt(magic, 6.0F);
            float second = afterFirst[0] - mob.getHealth();
            helper.assertTrue(Math.abs(second - afterFirst[1]) < 0.01F, "次の tick の攻撃が通らない: " + second);
            helper.succeed();
        });
    }

    /**
     * 見えた相手だけを狩る(標的を選ぶ Goal はこの MOD の決まりで壁越しにも相手を選ぶが、すくいに寄るのは見えてから)。
     * テスト場を石の壁で仕切り、向こう側のハスクを標的にしても狩りに入らない(標的は見失いの 5 秒までは持ったまま)。
     * 壁を取り払うと、見えて狩りに入る。体格 1・AI あり(見えるかは AI の目で 10 tick ごとに測る)・Goal は外す(動かさない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = BATCH)
    public static void huntsOnlyTargetsItHasSeen(GameTestHelper helper) {
        setWall(helper, Blocks.STONE);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 5.5D));
        husk.setNoAi(true);
        husk.setInvulnerable(true);
        ErebomnesEntity mob = spawnMob(helper, helper.absoluteVec(new Vec3(3.5D, 4.0D, 1.5D)), 0.0F, 1.0D, false);
        mob.removeFreeWill();
        mob.targetSelector.removeAllGoals(g -> true);
        mob.setTarget(husk);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(mob.getTarget() == husk, "壁の向こうの標的を見失いの 5 秒より前に外した");
            helper.assertFalse(mob.wantsToHunt(), "壁の向こうの見えない相手を狩ろうとしている");
            setWall(helper, Blocks.AIR);
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(mob.getTarget() == husk, "壁を取り払ったら標的を外していた");
            helper.assertTrue(mob.wantsToHunt(), "壁を取り払って見えるようになっても狩りに入らない");
            helper.succeed();
        });
    }

    /**
     * 狩りの通し(AI あり): 見えた標的の胸が grab_point に来る点へ泳いで寄り、止まってすくう(交戦の始めなので払い)。
     * 追う速さで寄る点へ来ても止まり切れる(制動 {@code BRAKE})ので、当たりの輪から外れない —— 制動が加速と同じ 6% だと、
     * 当たりの時点で寄る点を行き過ぎていた。体格 1(寄る点まで約 2.6 ブロック)。標的を選ぶ Goal は外す(隣のテスト場の Mob を選ばない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void approachesAndRakesSeenTarget(GameTestHelper helper) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 5.0D));
        husk.setNoAi(true);
        ErebomnesEntity mob = spawnMob(helper, helper.absoluteVec(new Vec3(3.5D, 4.0D, 1.0D)), 0.0F, 1.0D, false);
        mob.targetSelector.removeAllGoals(g -> true);
        mob.setTarget(husk);
        helper.succeedWhen(() -> {
            Vec3 off = mob.grabPointWorld().subtract(husk.position().add(0.0D, husk.getBbHeight() * ErebomnesEntity.PREY_CHEST_RATIO, 0.0D));
            helper.assertTrue(husk.getHealth() < husk.getMaxHealth(), "寄ってすくっていない(動作 " + mob.getAction()
                    + "、狩り " + mob.wantsToHunt() + "、grab_point と胸のずれ " + off + ")");
            helper.assertTrue(husk.getVehicle() == null, "交戦の始めなのに掴んだ(払いのはず)");
            // 当たった直後(すくいの後は止まっている)の grab_point が胸の近く = 寄る点で止まり切れている(制動が弱いと 0.7 ほど行き過ぎる)
            helper.assertTrue(off.horizontalDistance() < 0.5D, "寄る点を行き過ぎてすくった(grab_point と胸の水平のずれ " + off.horizontalDistance() + ")");
        });
    }

    /** テスト場を z = 4 で仕切る壁(床の上 y 2〜6 の全幅)。 */
    private static void setWall(GameTestHelper helper, net.minecraft.world.level.block.Block block) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 6; y++) {
                helper.setBlock(x, y, 4, block);
            }
        }
    }

    // ------------------------------------------------------------------ 洞窟だけ

    /** 仮想の地形(条件に合うマスが空気、ほかは石)。 */
    private record Virtual(Predicate<BlockPos> open) implements BlockGetter {
        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.open.test(pos) ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    /**
     * 大きな空洞だけを通す(関数を直接)。広間(25×25×高さ 12)は通り、空中の高さに置く。背の高い広間(高さ 40)も通り、置いた高さから
     * 漂う行き先の床まで届く({@link ErebomnesEntity#WANDER_FLOOR_SCAN})。細い坑道(幅 3・高さ 4)・峡谷(幅 5・高さ 25)・
     * 低い広間(高さ 6)・床の無い所は通らない。テスト場(7×7)の床も通らず、関門そのもの({@code acceptOvergrownSpawn})も湧かせない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20, batch = BATCH)
    public static void onlyBigCavernsPass(GameTestHelper helper) {
        double h = ModEntities.EREBOMNES.get().getDimensions().height() * SCALE;
        BlockPos origin = BlockPos.ZERO;

        ErebomnesCavern.Result hall = ErebomnesCavern.measure(
                new Virtual(p -> Math.abs(p.getX()) <= 12 && Math.abs(p.getZ()) <= 12 && p.getY() >= 0 && p.getY() < 12), origin, SCALE, h);
        helper.assertTrue(hall.ok(), "大きな広間が通らない: " + hall.reason());
        helper.assertTrue(hall.placeY() >= ErebomnesCavern.HOVER_MIN * SCALE - 1.0E-6D, "床から持ち上げていない: " + hall.placeY());
        helper.assertTrue(hall.placeY() + h <= 12 - ErebomnesCavern.CEILING_MARGIN * SCALE + 1.0E-6D, "天井に近すぎる: " + hall.placeY());

        ErebomnesCavern.Result tunnel = ErebomnesCavern.measure(
                new Virtual(p -> Math.abs(p.getX()) <= 1 && p.getY() >= 0 && p.getY() < 4), origin, SCALE, h);
        helper.assertFalse(tunnel.ok(), "細い坑道が通った");

        ErebomnesCavern.Result ravine = ErebomnesCavern.measure(
                new Virtual(p -> Math.abs(p.getX()) <= 2 && p.getY() >= 0 && p.getY() < 25), origin, SCALE, h);
        helper.assertFalse(ravine.ok(), "峡谷(幅 5)が通った");

        // 背の高い広間(高さ 40。天井の探索の上限 32 を超える)も通り、置いた高さから漂う行き先の床を探す深さに床が入る
        // (以前は 12 段しか読まず、高い空洞に湧いた個体は行き先を一度も選べずに宙で止まっていた)。
        ErebomnesCavern.Result tallHall = ErebomnesCavern.measure(
                new Virtual(p -> Math.abs(p.getX()) <= 12 && Math.abs(p.getZ()) <= 12 && p.getY() >= 0 && p.getY() < 40), origin, SCALE, h);
        helper.assertTrue(tallHall.ok(), "背の高い広間が通らない: " + tallHall.reason());
        int midAboveFloor = Mth.floor(tallHall.placeY() + h * 0.5D) - tallHall.floorY();
        helper.assertTrue(midAboveFloor <= ErebomnesEntity.WANDER_FLOOR_SCAN,
                "高い空洞に置いた体の真ん中(床から " + midAboveFloor + ")から、漂う行き先の床を探す深さ(" + ErebomnesEntity.WANDER_FLOOR_SCAN + ")に床が入らない");

        ErebomnesCavern.Result lowHall = ErebomnesCavern.measure(
                new Virtual(p -> Math.abs(p.getX()) <= 12 && Math.abs(p.getZ()) <= 12 && p.getY() >= 0 && p.getY() < 6), origin, SCALE, h);
        helper.assertFalse(lowHall.ok(), "天井の低い広間が通った");

        ErebomnesCavern.Result noFloor = ErebomnesCavern.measure(new Virtual(p -> true), origin, SCALE, h);
        helper.assertFalse(noFloor.ok(), "床の無い所が通った");

        ServerLevel level = helper.getLevel();
        BlockPos arenaFloor = helper.absolutePos(new BlockPos(3, 2, 3));
        ErebomnesCavern.Result arena = ErebomnesCavern.measure(ErebomnesCavern.loadedOnly(level), arenaFloor, SCALE, h);
        helper.assertFalse(arena.ok(), "テスト場(7×7)が大きな空洞として通った");

        ErebomnesEntity probe = ModEntities.EREBOMNES.get().create(level);
        helper.assertTrue(probe != null, "エレボムネスを作れない");
        probe.moveTo(arenaFloor.getX() + 0.5D, arenaFloor.getY(), arenaFloor.getZ() + 0.5D, 0.0F, 0.0F);
        Vec3 before = probe.position();
        boolean accepted = probe.acceptOvergrownSpawn(level);
        probe.discard();
        helper.assertFalse(accepted, "空の見える狭いテスト場に湧いてしまう");
        helper.assertTrue(probe.position().equals(before), "湧かないのに位置を動かした");
        helper.succeed();
    }

    /**
     * 空の見える所へ出ない(行き先の判定の関数)。テスト場の北半分に石の屋根を掛け、空の光が落ちるのを待ってから:
     * 屋根の下(洞窟)から屋根の無い所(空の下)への行き先は選ばない。屋根の下どうし・空の下から屋根の下へは選べる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void neverHeadsFromCaveIntoOpenSky(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 3; z++) {
                helper.setBlock(x, 6, z, Blocks.STONE);
            }
        }
        ServerLevel level = helper.getLevel();
        Vec3 roofed = helper.absoluteVec(new Vec3(3.5D, 3.0D, 1.5D));
        Vec3 roofed2 = helper.absoluteVec(new Vec3(5.5D, 3.0D, 2.5D));
        Vec3 open = helper.absoluteVec(new Vec3(3.5D, 3.0D, 5.5D));
        helper.succeedWhen(() -> {
            helper.assertFalse(ErebomnesEntity.exposedToSky(level, roofed), "屋根の下がまだ空に開いている(光の更新待ち)");
            helper.assertFalse(ErebomnesEntity.exposedToSky(level, roofed2), "屋根の下がまだ空に開いている(光の更新待ち)");
            helper.assertTrue(ErebomnesEntity.exposedToSky(level, open), "屋根の無い所が空に開いていない");
            helper.assertFalse(ErebomnesEntity.destinationAllowed(level, roofed, open), "洞窟から空の見える所への行き先を選べてしまう");
            helper.assertTrue(ErebomnesEntity.destinationAllowed(level, roofed, roofed2), "洞窟の中どうしの行き先を選べない");
            helper.assertTrue(ErebomnesEntity.destinationAllowed(level, open, roofed), "空の下から洞窟へ戻れない");
        });
    }
}
