package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CaveGrowth;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 「エレボムネス」— グループ5(適応種)の、巨大な洞窟の空中を泳ぐアノマロカリス類(全長 8.6 ブロック、2026-10-01 実装)。
 *
 * <h2>ユーザーが決めたこと</h2>
 * <ul>
 *     <li>モチーフ: アノマロカリス類。モデル・テクスチャ・アニメは低キューブ版({@code tools/g5-models/out/erebomnes/}、2026-09-30、ユーザーが見本を見て OK)。</li>
 *     <li>採用の案(ユーザー改変): <b>巨大な洞窟の中だけに棲み、空中をゆっくり泳ぐ。洞窟内のプレイヤーをすくい上げるように掴み、噛みついて継続ダメージ</b>。
 *     複数エンティティ(別の Mob)は使わない見込み。</li>
 *     <li>G5 共通: 特殊攻撃は交戦直後に出さず、通常攻撃に時々混ぜる({@link SpecialPacing})。</li>
 * </ul>
 * それ以外(流れの細部・数値・どれを通常攻撃と数えるか・頭の部位)は<b>すべて仮決め</b>(実装係、2026-10-01)。
 *
 * <h2>動き</h2>
 * 重力なしで、速度だけで泳ぐ({@link SwimControl})。経路探索は使わない(実効の幅 3.5 は経路探索の上限 3 を超える)。
 * 泳ぐ向きは体の向き(旋回は毎秒 {@value #MAX_TURN}×20 度まで)で、横へは少ししか滑れない —— 長い体で弧を描いて回る。
 * 加速はゆっくり({@link #ACCEL})、減速は鰭で強く({@link #BRAKE})—— 追う速さのまま寄る点へ来ても止まり切れて、すくいが行き過ぎない。
 * 床・天井・壁へは 2 tick ごとに数点だけ読んで押し返す({@link #computeAvoidance})。水・溶岩のマスも壁として避ける。
 * 寄る点のすぐ近く({@link #CLOSE_IN_RANGE})とすくいの最中は押し返さない(壁を背にした相手の手前で止まらないように)。
 * <b>空の見える所へは出ない</b>: 行き先・獲物へ寄る点が空の光 15 の所なら選ばない({@link #destinationAllowed})。
 * 洞窟から外へ押し出されたら、最後にいた洞窟の点へ戻る。空の見える所に居る獲物は追わない(10 秒で諦めて標的を外す)。
 * 標的はこの MOD の決まりで壁越しにも選ばれるが、狩るのは<b>見えた相手だけ</b>({@link #wantsToHunt})。見えない間は漂い続け、
 * 5 秒見えなければ標的を外す(壁の向こうの相手の前で止まって待たない)。
 *
 * <h2>攻撃(scoop → bite_hold → release)</h2>
 * 標的の胸が {@code grab_point}(頭の前下、NOTES の「抱えた姿勢」前 3.03・高さ 0.11)に来る位置へ寄って、scoop を流す。
 * 当たりは scoop の 0.90 秒(補間 4 tick 込みで {@link #SCOOP_HIT_TICK})。付属肢の先の輪の中(grab_point のまわり)に胸がある相手に当たる。
 * <ul>
 *     <li><b>通常攻撃 = すくい払い</b>(仮決め): 掴まずに付属肢で払い上げる(攻撃力ぶん + 上へ放る)。scoop の終わりから release で静止へ戻る。</li>
 *     <li><b>特殊攻撃 = すくい上げて抱える</b>: 当たった相手を乗客として抱え({@link #getPassengerAttachmentPoint}、ファルキムネスの掴みと同じ作り)、
 *     scoop の終わりから bite_hold のループへ。1 周 2 秒で 0.42・1.42 秒に噛む(攻撃力 × {@value #BITE_DAMAGE_RATIO})。
 *     抱えている間は獲物の足を床から {@link #HOLD_PREY_LIFT} × 体格(1 ブロック)浮かせる(NOTES「足元を床から 1.2 以上」を満たす)。
 *     離すのは: 時間切れ({@value #HOLD_MAX_TICKS} tick)・振りほどき(抱えている間に削られた量が {@value #BREAK_FREE_DAMAGE} 以上)・
 *     大きく殴られる(一撃で {@value #BIG_HIT_DAMAGE} 以上)・獲物の死・自分の死。release の 0.2 秒に前へ放る。</li>
 * </ul>
 * 同じアニメで始まるので、見た目では払いか掴みか分からない(溜めの 0.62 秒の間に横へ・後ろへ逃げれば両方かわせる)。
 * どちらにするかは scoop を始めるときに {@link SpecialPacing#roll} で決める(交戦 6 秒 + 払い 2 回までは掴まない)。
 *
 * <h2>当たり判定</h2>
 * 本体は体格 2 で幅 3.5 × 高さ 2.1。頭(中心から前 2.2〜4.4)は箱の外なので、頭に部位 {@link ErebomnesHead} を 1 つ足した(体力は共通)。
 * 同じ tick の同じダメージ源は 1 回にまとめる(爆発が頭と胴の両方に当たっても 1 回。ティタニカと同じ)。
 *
 * <h2>湧き</h2>
 * 地下の大きな空洞だけ({@link #acceptOvergrownSpawn} → {@link ErebomnesCavern})。空洞の床の上に置かれたら、空洞の広さを測って空中へ持ち上げる。
 */
public class ErebomnesEntity extends Monster implements GeoEntity, ParasitizedMob, OvergrownSpawnCheck {

    // ------------------------------------------------------------------ 動作

    public static final int ACT_NONE = 0;
    /** すくい(払い・掴みの両方。アニメ scoop 1.8 秒)。 */
    public static final int ACT_SCOOP = 1;
    /** 抱えて噛む(bite_hold のループ)。 */
    public static final int ACT_HOLD = 2;
    /** 離す・払い終わりの戻り(release 1.2 秒)。 */
    public static final int ACT_RELEASE = 3;
    public static final int ACT_DEATH = 4;

    /** 動作を切り替えるときの補間(tick)。GeckoLib は補間の後にアニメを 0 秒目から流すので、当たりの tick は補間ぶん後ろ(ほかの G5 と同じ 4)。 */
    public static final int TRANSITION_TICKS = 4;
    /** scoop の振りの始まり(0.62 秒)。ここまでは標的を向き、少しだけ位置を合わせる。ここからは向きも位置も変えない。 */
    public static final int SCOOP_COMMIT_TICK = TRANSITION_TICKS + 12;
    /** scoop の当たり(0.90 秒。NOTES)。 */
    public static final int SCOOP_HIT_TICK = TRANSITION_TICKS + 18;
    /** scoop の終わり(1.8 秒)。終わりの姿勢 = bite_hold・release の 0 秒目なので、次は補間 0 でつなぐ。 */
    public static final int SCOOP_TICKS = TRANSITION_TICKS + 36;
    /** bite_hold の 1 周(2.0 秒)。 */
    public static final int HOLD_LOOP_TICKS = 40;
    /** 噛む時刻(bite_hold の 0.42 秒・1.42 秒。scoop の終わりから補間 0 でつなぐので補間は足さない)。 */
    public static final int BITE_TICK_A = 8;
    public static final int BITE_TICK_B = 28;
    /** 抱えていられる長さ(6 秒 = 噛み 6 回、仮)。 */
    public static final int HOLD_MAX_TICKS = 120;
    /** release で獲物を離す時刻(0.2 秒。これに release へ入る補間を足す)。 */
    public static final int RELEASE_DROP_TICK = 4;
    /** release の長さ(1.2 秒。これに補間を足す)。 */
    public static final int RELEASE_TICKS = 24;
    /** 倒れるアニメ(3.0 秒)+ 補間 + 少しの余韻。 */
    public static final int DEATH_TICKS = TRANSITION_TICKS + 60 + 10;
    /** 死んでからこの tick で目の光を消す(death の 2.3 秒 = 床に横たわった所。NOTES。補間ぶん後ろ)。 */
    public static final int DEATH_GLOW_OFF_TICKS = TRANSITION_TICKS + 46;

    /** 噛み 1 回 = 攻撃力 × これ(攻撃力 10 で 5、仮)。すくい払いは攻撃力そのまま。 */
    public static final float BITE_DAMAGE_RATIO = 0.5F;
    /** 抱えている間に削られた量(防具の後の実際の減り)がこれ以上で離す = 振りほどき(仮)。 */
    public static final float BREAK_FREE_DAMAGE = 14.0F;
    /** 抱えている間に一撃でこれ以上削られたら離す = 大きく殴られる(仮。ダイヤの剣の会心・ネザライトの斧の溜めで届く)。 */
    public static final float BIG_HIT_DAMAGE = 8.0F;
    /** 払い(外れも)の後、次のすくいまで(仮)。 */
    static final int COOLDOWN_AFTER_RAKE = 30;
    /** 抱えて時間切れ・獲物が死んだ後(仮)。 */
    static final int COOLDOWN_AFTER_HOLD = 60;
    /** 振りほどかれた・大きく殴られた後(仮)。 */
    static final int COOLDOWN_SHAKEN = 100;

    // ------------------------------------------------------------------ 形(体格 1 のブロック = NOTES の値 ÷ 描画倍率 2。使うときは体格を掛ける)

    /** 抱えた姿勢の {@code grab_point}(獲物の胸)。前 3.03・高さ 0.11(体格 2)。bite_hold の間は動かない(NOTES)。 */
    public static final double GRAB_FORWARD = 3.03D / 2.0D;
    public static final double GRAB_UP = 0.11D / 2.0D;
    /** 獲物の胸 = 足から背の高さ × これ(プレイヤーの 1.25 / 1.8。NOTES の人の箱)。 */
    public static final double PREY_CHEST_RATIO = 1.25D / 1.8D;
    /** 頭の部位: 中心が前 3.3、下の端が 0.5、幅 2.2 × 高さ 1.6(体格 2)。口・柄の目・巻いた付属肢を覆う。 */
    static final double HEAD_FORWARD = 1.65D;
    static final double HEAD_BOTTOM = 0.25D;
    static final float HEAD_WIDTH = 1.1F;
    static final float HEAD_HEIGHT = 0.8F;
    /** 頭の先(付属肢の先)まで。前 4.8(体格 2)。前の壁を見る距離。 */
    static final double HEAD_REACH = 2.4D;
    /** すくいの当たり: grab_point から横 ±1.4・縦 ±1.2(体格 2)に胸がある相手(仮)。 */
    static final double SCOOP_ZONE_H = 0.7D;
    static final double SCOOP_ZONE_V = 0.6D;
    /** 抱えられる大きさ(幅 1.6・高さ 2.5 まで、体格 2。仮)。 */
    static final double CARRY_MAX_WIDTH = 0.8D;
    static final double CARRY_MAX_HEIGHT = 1.25D;
    /** 抱えている間、獲物の足を床からこれだけ浮かせる(1 ブロック、体格 2。仮)。 */
    public static final double HOLD_PREY_LIFT = 0.5D;
    /** 床・天井・横の壁から離れる距離(体格 2 で 床 2(狩りの間は 0.8)・天井 1・横 1.2。仮)。 */
    static final double FLOOR_KEEP = 1.0D;
    static final double FLOOR_KEEP_HUNT = 0.4D;
    static final double CEILING_KEEP = 0.5D;
    static final double WALL_KEEP = 0.6D;
    /** 漂う行き先: 今の位置から 6〜16 ブロック先、床から 2〜5 ブロック上(体格 2。仮)。 */
    static final double WANDER_MIN = 3.0D;
    static final double WANDER_MAX = 8.0D;
    static final double CRUISE_MIN = 1.0D;
    static final double CRUISE_MAX = 2.5D;
    /**
     * 漂う行き先の列で床を探して下へ読む段数(体の真ん中の高さから)。湧きの関門は高い空洞(天井の探索の上限 32)だと
     * 床から 12.8 の高さに置く(真ん中は床から 13.85)ので、それより深く読む。以前の 12 では高い空洞に湧いた個体が
     * 行き先を一度も選べず、標的が来るまで宙で止まったままだった(点検係、2026-10-01)。
     */
    public static final int WANDER_FLOOR_SCAN = 24;
    /** すくいを始める: 寄る点からこれ以内(体格 2 で 1 ブロック)で、標的が正面 ±{@value #ATTACK_FACE_TOLERANCE} 度。 */
    static final double ATTACK_POS_TOLERANCE = 0.5D;
    static final float ATTACK_FACE_TOLERANCE = 25.0F;
    /** 寄る点からこれ以内(体格 2 で 10 ブロック)なら標的を向く(遠くでは泳ぐ向き)。 */
    static final double FACE_TARGET_RANGE = 5.0D;
    /**
     * 寄る点からこれ以内(体格 2 で 4 ブロック)では、床・天井・壁の押し返しを切る(仮)。寄る点は体が入ることを確かめてあり
     * ({@link #spotUsable})、壁にぶつかっても {@code move} が止めるだけ。押し返しが残ると、壁を背にした相手・壁際の相手の手前で
     * 押し返しと寄る速さ(距離 × 0.1)が釣り合って 1.2 ブロック手前で止まり、すくいの間合い({@link #ATTACK_POS_TOLERANCE})に入れなかった。
     */
    static final double CLOSE_IN_RANGE = 2.0D;
    /** すくい払いで放る速さ(上・前、ブロック/tick)。 */
    static final double RAKE_LIFT = 0.5D;
    static final double RAKE_PUSH = 0.2D;

    // ------------------------------------------------------------------ 泳ぎ(仮)

    /** 速度倍率。最高速度 = 飛ぶ速さ(0.3)× 倍率 × 0.5 ブロック/tick(漂う 0.15 = 秒 3、追う 0.24 = 秒 4.8、抱えて 0.075)。 */
    static final double CRUISE = 1.0D;
    static final double CHASE = 1.6D;
    static final double HOLD_SPEED = 0.5D;
    static final double WINDUP_TRACK = 0.3D;
    /** 速度の寄せ(1 tick にこの割合だけ狙いの速度へ。重い体)。 */
    static final double ACCEL = 0.06D;
    /**
     * 減速・向きを変えるときの寄せ(鰭で制動する。仮)。加速と同じ 6% だと、追う速さで寄る点へ来た体が止まり切れず、
     * すくいの当たり(0.9 秒)の時点で寄る点を 1.4 ブロック(体格 2)行き過ぎ、止まった相手でも当たりの輪(横 ±1.7)の端に掛かるだけだった
     * (歩いて離れる相手には外れる。この Mob の式を写した模擬で確認、2026-10-01)。20% なら行き過ぎは 0.05 ブロックほど。
     */
    static final double BRAKE = 0.2D;
    /** 体の向きの横へ滑れる速さ(体格 2 で 0.04 ブロック/tick)。 */
    static final double LATERAL_CAP = 0.02D;
    /** 後ずさりの速さの上限(体格 2 で 0.05 ブロック/tick = 秒 1。鰭を逆に打つ。獲物の真上に来てしまったときに下がれるだけ)。 */
    static final double BACK_CAP = 0.025D;
    /** 床・天井・壁の押し返しの強さ(ブロック/tick)。 */
    static final double AVOID_GAIN = 0.12D;
    /** 旋回の上限(度/tick = 毎秒 60 度)。全長 8.6 の体なので遅く。 */
    static final float MAX_TURN = 3.0F;
    /** 標的が見えないまま、これだけ経ったら諦める。 */
    static final int LOST_SIGHT_GIVE_UP = 100;
    /** 標的が空の見える所に居続けたら、これだけ経って諦める。 */
    static final int OUT_OF_CAVE_GIVE_UP = 200;
    /** 見えないまま諦めた相手を選び直さない間(tick。2026-10-09、仮決め)。 */
    static final int IGNORE_LOST_TICKS = 200;
    /** クライアント: 泳ぎのアニメに切り替える 1 tick の移動量(ブロック)と、待機へ戻す量。 */
    static final double SWIM_ANIM_ON = 0.06D;
    static final double SWIM_ANIM_OFF = 0.03D;

    /** 向きの希望の強さ(強いものが勝つ)。 */
    static final int FACE_MOVE = 0;
    static final int FACE_HUNT = 1;
    static final int FACE_ACTION = 2;

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(ErebomnesEntity.class, EntityDataSerializers.INT);
    /** 同じ動作を出し直したことをクライアントへ知らせる番号。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(ErebomnesEntity.class, EntityDataSerializers.INT);
    /** 今の動作に入るときの補間(tick)。scoop の終わりからは 0、それ以外は 4。 */
    private static final EntityDataAccessor<Integer> DATA_TRANSITION =
            SynchedEntityData.defineId(ErebomnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation SCOOP = RawAnimation.begin().thenPlayAndHold("scoop");
    private static final RawAnimation HOLD = RawAnimation.begin().thenLoop("bite_hold");
    private static final RawAnimation RELEASE = RawAnimation.begin().thenPlayAndHold("release");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final ErebomnesHead head;
    private final PartEntity<?>[] parts;
    private final SpecialPacing pacing = SpecialPacing.standard();

    // --- サーバー: 動作
    private int actionTicks;
    private int attackCooldown = 20;
    /** 今のすくいが掴みか(false = 払い)。 */
    private boolean grabMode;
    /** 獲物を抱えているか(捕まえてから離すまで)。 */
    private boolean holding;
    /** 自分で獲物を離すときだけ立てる({@link Events#onMount} が降車を止めないように)。 */
    private boolean releasing;
    private int holdTicks;
    private float holdDamage;
    /** 読み込み直した直後、乗客(獲物)が付き直すのを待つ tick。 */
    private int loadGrace;
    private int releaseTransition = TRANSITION_TICKS;
    private int releaseCooldown = COOLDOWN_AFTER_RAKE;
    /** 抱えている間の行き先(4 tick ごとに測り直す)。 */
    @Nullable
    private Vec3 holdWant;

    // --- サーバー: 泳ぎ
    private Vec3 swimVel = Vec3.ZERO;
    private Vec3 lastSetVel = Vec3.ZERO;
    private Vec3 avoidCache = Vec3.ZERO;
    private float wantedYaw = Float.NaN;
    private int wantedYawPriority = -1;
    /** 体の上端が空に開いているか(10 tick ごと)。 */
    private boolean exposed;
    /** 最後に居た、空に開いていない点(洞窟へ戻る先)。 */
    @Nullable
    private Vec3 lastCavePos;
    private int outOfCaveTicks;
    /** 最後に見えた標的と、その tick(狩りは見えた相手だけ。{@link #wantsToHunt})。 */
    @Nullable
    private LivingEntity seenTarget;
    private int seenTargetAt = -100000;
    /** 標的が見えないまま続いている tick(10 tick ごとに数える)。 */
    private int unseenTicks;
    /**
     * 見えないまま諦めた相手と、選び直さない期限(tickCount)。標的選びは壁越しにいちばん近い相手を選ぶので、外してもすぐ同じ相手
     * (壁の向こう・上下の別の洞窟)を選び直し、見える相手へ向かえないことがある({@link #canAttack}。2026-10-09)。
     */
    @Nullable
    private java.util.UUID ignoredTarget;
    private int ignoredUntil;
    /** この tick、寄る点のすぐ近くまで来ている(押し返しを切る。狩りの Goal が毎 tick 決める)。 */
    private boolean closingIn;

    // --- サーバー: 同じ tick の同じダメージ源を 1 回にまとめる
    @Nullable
    private DamageSource lastHitSource;
    private long lastHitTick = Long.MIN_VALUE;

    // --- クライアント
    private int clientSeq;
    private boolean clientSwimming;

    public ErebomnesEntity(EntityType<? extends ErebomnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("erebomnes", 50);
        this.head = new ErebomnesHead(this, HEAD_WIDTH, HEAD_HEIGHT);
        this.parts = new PartEntity<?>[] {this.head};
        // 部位の id を本体の次の番号にする(エンダードラゴンと同じ。クライアントは本体の id + 1 を頭とみなす)
        this.setId(ENTITY_COUNTER.getAndAdd(this.parts.length + 1) + 1);
        this.moveControl = new SwimControl(this);
        this.lookControl = new FixedLookControl(this);
        this.setNoGravity(true);
        // 当たり判定の箱を最初から体格込みにする(点検係、2026-10-01)。作った直後の箱は素の大きさ(1.75 × 1.05)で、
        // 最初の tick に SCALE の反映で大きくなると、バニラの fudgePositionAfterSizeChange が箱の中心を保つように
        // 原点を (2.1 − 1.05) / 2 = 0.525 下げていた —— 湧きの関門が決めた高さ・保存から読み込んだ位置・抱えた獲物ごと、
        // 湧くたび・チャンクを読み込むたびに沈む。ここで大きくしておけば最初の tick は大きさが変わらず、押し下げは起きない
        // (世界に入る前なので firstTick の間。個体差は MobSizeVariance が参加のときに測り直す)。
        this.refreshDimensions();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "erebomnes");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_TRANSITION, TRANSITION_TICKS);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(2, new ScoopAttackGoal(this));
        this.goalSelector.addGoal(6, new CaveWanderGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        ParasitizedTargetGoal.installTargeting(this, 2);
    }

    /**
     * 経路探索は使わない(幅 3.5 は上限を超える。泳ぎは {@link SwimControl} が速度だけで決める)。よそが moveTo を呼んでも地面へ吸い寄せないよう、飛ぶ種の航法にしておく。
     *
     * <p><b>経路は引かず、「もう着いている」空の経路を返す</b>(点検係、2026-10-01)。標的を選ぶ {@link ParasitizedTargetGoal} は
     * 1 秒ごとに標的までの経路を引いて「届かない相手」を見限るが、この体(経路の枡 4 × 3 × 4)で飛ぶ経路を引くと、
     * 半径 48 の範囲で 512 ノードを探す重い探索が狩りの間ずっと 1 秒に 1 回走っていた。飛ぶ航法は部分経路を返すので
     * 元から「届かない」にはならず、見限りの答えは変わらない(見えない相手は {@link #tickCaveSense} が 5 秒で外す)。
     * 空の経路は {@code moveTo} が「着いている」として何もしないので、よそから差し込まれる歩く Goal に動かされることもない。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level) {
            @Nullable
            @Override
            protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
                return targets.isEmpty() ? null : new Path(List.of(), targets.iterator().next(), false);
            }
        };
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(false);
        return navigation;
    }

    /** 体は常に {@code yRot} の向き(サーバーでもクライアントでも)。 */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                Mob mob = ErebomnesEntity.this;
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
            }
        };
    }

    /**
     * フィロセンスの誘導({@code PHYLLOSENS_GUIDANCE})を受けない(点検係の仮決め、2026-10-01。ユーザー未確認)。
     * 誘導はグループの Mob 全種に差し込まれる {@code GuidedHuntGoal} が「視線持ちのプレイヤー」へ距離を問わず向かわせるもので、
     * 地上のプレイヤーへ向かうと「巨大な洞窟の中だけに棲む・空の見える所へは出ない」(採用の案)とけんかする
     * (洞窟の口で、外へ向かう誘導と {@link #shouldReturnToCave} の戻りが入れ替わり続ける)。
     */
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return !effect.is(ModMobEffects.PHYLLOSENS_GUIDANCE) && super.canBeAffected(effect);
    }

    // ------------------------------------------------------------------ 部位

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.parts;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    public ErebomnesHead getHead() {
        return this.head;
    }

    /**
     * 頭の部位を置く。サーバー・クライアントの両方で毎 tick、同期している位置・向き・体格だけから同じ式で出す。
     * {@code setPos} は x・z が箱の中心、y が箱の下の端。
     */
    void positionHead() {
        float s = this.getScale();
        this.head.setSize(HEAD_WIDTH * s, HEAD_HEIGHT * s);
        Vec3 f = forward();
        double x = this.getX() + f.x * HEAD_FORWARD * s;
        double y = this.getY() + HEAD_BOTTOM * s;
        double z = this.getZ() + f.z * HEAD_FORWARD * s;
        this.head.xo = this.head.getX();
        this.head.yo = this.head.getY();
        this.head.zo = this.head.getZ();
        this.head.xOld = this.head.getX();
        this.head.yOld = this.head.getY();
        this.head.zOld = this.head.getZ();
        this.head.setPos(x, y, z);
    }

    /** 頭の部位への被弾(体力は共通)。同じ tick の同じダメージ源は {@link #hurt} が 1 回にまとめる。 */
    public boolean hurtFromPart(DamageSource source, float amount) {
        return this.hurt(source, amount);
    }

    @Override
    public void tick() {
        super.tick();
        positionHead();
    }

    // ------------------------------------------------------------------ 状態

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public boolean isActing() {
        return getAction() != ACT_NONE;
    }

    /** 獲物を抱えているか(捕まえてから離すまで。サーバー)。 */
    public boolean isHoldingPrey() {
        return this.holding && !this.releasing;
    }

    /** bite_hold を始めてからの tick(GameTest 用。サーバー)。 */
    public int getHoldTicks() {
        return this.holdTicks;
    }

    /** 大技の出し方の勘定(GameTest 用。書き換えない)。 */
    public SpecialPacing getSpecialPacing() {
        return this.pacing;
    }

    private void startAction(int action, int transition) {
        this.entityData.set(DATA_TRANSITION, transition);
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.actionTicks = 0;
    }

    private void endAction(int cooldown) {
        this.entityData.set(DATA_TRANSITION, TRANSITION_TICKS);
        this.entityData.set(DATA_ACTION, ACT_NONE);
        this.actionTicks = 0;
        this.attackCooldown = cooldown;
        this.grabMode = false;
    }

    // ------------------------------------------------------------------ 毎 tick

    /**
     * 動作は {@code aiStep} で進める({@code customServerAiStep} ではなく)。NoAI の個体でも動作は進むので、
     * GameTest が AI を止めたまま {@link #forceScoop} で確かめられる(泳ぎは AI が要る)。
     */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide() || this.isDeadOrDying()) {
            return;
        }
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        // 交戦の勘定(標的が替わってもやり直さない。SpecialPacing)
        this.pacing.tick(this.getTarget());
        int phase = this.tickCount + this.getId();
        if (phase % 10 == 0) {
            tickCaveSense();
        }
        if (phase % 2 == 0) {
            this.avoidCache = this.isNoAi() ? Vec3.ZERO : computeAvoidance();
        }
        // 抱えていないのに乗っているもの(/ride など)は降ろす。読み込み直した獲物は holding で抱えている扱い。
        if (this.isVehicle() && !this.holding) {
            dropPrey(false);
        }
        if (isActing()) {
            this.actionTicks++;
            tickAction();
        }
        // 旋回は最後 —— この tick に出た向きの希望(Goal・泳ぎ・動作)を全部見てから回す。
        tickTurn();
    }

    private void tickAction() {
        switch (getAction()) {
            case ACT_SCOOP -> tickScoop();
            case ACT_HOLD -> tickHold();
            case ACT_RELEASE -> tickRelease();
            default -> {
            }
        }
    }

    /**
     * 空に開いているか・洞窟の点・標的が見えるか・見えない / 空の下の標的を諦めるか(10 tick ごと)。
     * 標的を選ぶ Goal はこの MOD の決まりで壁越しにも相手を見つける({@link ParasitizedTargetGoal})が、
     * 狩り({@link ScoopAttackGoal})は<b>見えた相手だけ</b>。見えないまま {@value #LOST_SIGHT_GIVE_UP} tick 経ったら標的を外す
     * (その間は漂い続ける。壁の向こうの相手の前で止まって待たない)。
     */
    private void tickCaveSense() {
        this.exposed = exposedToSky(this.level(), this.position().add(0.0D, this.getBbHeight(), 0.0D));
        if (!this.exposed) {
            this.lastCavePos = this.position();
        }
        LivingEntity t = this.getTarget();
        if (t != null && t.isAlive() && !this.isNoAi()) {
            if (this.getSensing().hasLineOfSight(t)) {
                noteSighting(t);
                this.unseenTicks = 0;
            } else {
                this.unseenTicks += 10;
                if (this.unseenTicks >= LOST_SIGHT_GIVE_UP && !isActing()) {
                    this.setTarget(null);
                    this.unseenTicks = 0;
                    this.outOfCaveTicks = 0;
                    // しばらく選び直さない(すぐ同じ相手を選び直して、見える相手へ向かえなくなるのを防ぐ)
                    this.ignoredTarget = t.getUUID();
                    this.ignoredUntil = this.tickCount + IGNORE_LOST_TICKS;
                    return;
                }
            }
        } else {
            this.unseenTicks = 0;
        }
        if (t != null && t.isAlive() && !mayHunt(t)) {
            this.outOfCaveTicks += 10;
            if (this.outOfCaveTicks >= OUT_OF_CAVE_GIVE_UP && !isActing()) {
                this.setTarget(null);
                this.outOfCaveTicks = 0;
            }
        } else {
            this.outOfCaveTicks = 0;
        }
    }

    // ------------------------------------------------------------------ すくい

    /** テスト用: target を見えないまま諦めたことにする({@link #tickCaveSense} の諦めと同じ。しばらく選び直さない)。 */
    public void giveUpUnseenForTests(LivingEntity target) {
        this.ignoredTarget = target.getUUID();
        this.ignoredUntil = this.tickCount + IGNORE_LOST_TICKS;
    }

    /** テスト・デバッグ用: 標的を決めて、すぐすくいを始める({@code grab} で掴みか払いか。溜めや間合いの条件は見ない)。 */
    public void forceScoop(LivingEntity target, boolean grab) {
        this.setTarget(target);
        this.attackCooldown = 0;
        startScoop(grab);
    }

    /** すくいを始める。大技の勘定もここで数える(掴み = 特殊攻撃、払い = 通常攻撃。当たったかは問わない)。 */
    void startScoop(boolean grab) {
        this.grabMode = grab;
        if (grab) {
            this.pacing.onSpecial();
        } else {
            this.pacing.onNormalAttack();
        }
        startAction(ACT_SCOOP, TRANSITION_TICKS);
        halt();
        playSound(SoundEvents.PHANTOM_SWOOP, 1.4F, 0.55F);
    }

    private void tickScoop() {
        LivingEntity t = this.getTarget();
        int k = this.actionTicks;
        if (k < SCOOP_COMMIT_TICK) {
            // 溜め: 標的を向き、寄る点へ少しだけ合わせる(止まっている相手には当たる。歩いて逃げればかわせる)
            if (t != null && t.isAlive()) {
                requestYaw(yawTo(t), FACE_ACTION);
                Vec3 spot = attackSpotFor(t);
                this.moveControl.setWantedPosition(spot.x, spot.y, spot.z, WINDUP_TRACK);
            }
        } else {
            halt();
        }
        if (k == SCOOP_HIT_TICK) {
            strike(t);
        }
        if (k >= SCOOP_TICKS) {
            if (this.holding) {
                this.holdTicks = 0;
                startAction(ACT_HOLD, 0);
            } else {
                startRelease(false);
            }
        }
    }

    /** すくいの当たり(0.90 秒)。掴みなら抱えられる相手を 1 体抱える。抱えなかったら輪の中の相手を払い上げる。 */
    private void strike(@Nullable LivingEntity target) {
        List<LivingEntity> caught = foesInScoop(target);
        if (this.grabMode) {
            for (LivingEntity e : caught) {
                if (canCarry(e, this.getScale()) && grab(e)) {
                    playSound(SoundEvents.EVOKER_FANGS_ATTACK, 1.3F, 0.6F);
                    return;
                }
            }
        }
        playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.4F, 0.55F);
        Vec3 f = forward();
        int n = 0;
        for (LivingEntity e : caught) {
            if (n++ >= 3) {
                break;
            }
            if (this.doHurtTarget(e)) {
                e.setDeltaMovement(e.getDeltaMovement().add(f.x * RAKE_PUSH, RAKE_LIFT, f.z * RAKE_PUSH));
                e.hurtMarked = true;
            }
        }
    }

    /**
     * {@code prey} を抱える(乗客にする)。抱えられない相手なら false。すくいの当たりで呼ぶ。
     * プレイヤーを毎 tick テレポートさせず、乗客の位置({@link #getPassengerAttachmentPoint})でバニラに同期させる(ファルキムネスと同じ)。
     */
    public boolean grab(LivingEntity prey) {
        if (this.holding || !canCarry(prey, this.getScale()) || !prey.startRiding(this, true)) {
            return false;
        }
        this.holding = true;
        this.releasing = false;
        this.holdDamage = 0.0F;
        this.holdTicks = 0;
        this.holdWant = null;
        return true;
    }

    private void tickHold() {
        Entity p = this.getFirstPassenger();
        if (!(p instanceof LivingEntity prey) || !prey.isAlive() || !stillPrey(prey)) {
            if (p == null && this.loadGrace > 0) {
                this.loadGrace--;
                return;
            }
            startRelease(false);
            if (p != null) {
                // 殺した・クリエイティブに切り替えた。死体を口の前に吊ったまま泳がない(離すアニメを待たずに降ろす)
                dropPrey(false);
            }
            return;
        }
        this.loadGrace = 0;
        this.holdTicks++;
        int phase = this.actionTicks % HOLD_LOOP_TICKS;
        if (phase == BITE_TICK_A || phase == BITE_TICK_B) {
            bite(prey);
            if (!prey.isAlive()) {
                return;
            }
        }
        if (this.holdTicks >= HOLD_MAX_TICKS) {
            startRelease(false);
            return;
        }
        if (!this.isNoAi()) {
            steerWhileHolding(prey);
        }
    }

    /**
     * 噛む(bite_hold の 0.42・1.42 秒)。ダメージ源の位置は獲物の頭の真上(口が頭の上 0.17 まで降りる。NOTES)にする ——
     * 盾は水平の向きで防ぐかを決めるので、真上からの噛みは抱えられたまま振り向いて盾を構えても防げない。防具は効く(仮決め)。
     */
    private void bite(LivingEntity prey) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * BITE_DAMAGE_RATIO;
        Vec3 mouth = new Vec3(prey.getX(), prey.getY() + prey.getBbHeight() + 0.2D, prey.getZ());
        DamageSource source = new DamageSource(this.damageSources().mobAttack(this).typeHolder(), this, this, mouth);
        prey.invulnerableTime = 0;
        prey.hurt(source, damage);
        playSound(SoundEvents.EVOKER_FANGS_ATTACK, 1.2F, 0.7F + this.random.nextFloat() * 0.1F);
        if (this.level() instanceof ServerLevel server) {
            Vec3 at = prey.position().add(0.0D, prey.getBbHeight() * 0.8D, 0.0D);
            server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, at.x, at.y, at.z, 3, 0.2D, 0.2D, 0.2D, 0.1D);
        }
    }

    /**
     * 抱えている間の泳ぎ: 獲物の足を床から {@link #HOLD_PREY_LIFT} × 体格まで浮かせ(すくい上げ)、
     * 獲物の体が壁・天井に食い込んだら頭を引く。4 tick ごとに獲物の下の列(6 マスまで)と獲物の箱だけを見る。
     */
    private void steerWhileHolding(LivingEntity prey) {
        double s = this.getScale();
        if (this.holdWant == null || (this.tickCount + this.getId()) % 4 == 0) {
            Level lv = this.level();
            Vec3 want = this.position();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int feetY = Mth.floor(prey.getY());
            for (int d = 0; d <= 6; d++) {
                if (!ErebomnesCavern.isOpen(lv, m.set(prey.getX(), feetY - d - 1, prey.getZ()))) {
                    double floorTop = feetY - d;
                    double dy = floorTop + HOLD_PREY_LIFT * s - prey.getY();
                    want = want.add(0.0D, Mth.clamp(dy, -1.0D, 1.0D), 0.0D);
                    break;
                }
            }
            AABB box = prey.getBoundingBox();
            if (!lv.noCollision(prey, box)) {
                AABB lower = box.setMaxY(box.minY + box.getYsize() * 0.5D);
                double up = !lv.noCollision(prey, lower) ? 0.5D : -0.5D;
                want = want.subtract(forward().scale(0.75D * s)).add(0.0D, up, 0.0D);
            }
            this.holdWant = want;
        }
        this.moveControl.setWantedPosition(this.holdWant.x, this.holdWant.y, this.holdWant.z, HOLD_SPEED);
    }

    /** 離し始める(release)。払い終わり・外れも同じアニメで静止へ戻る。 */
    private void startRelease(boolean shaken) {
        int act = getAction();
        if (act == ACT_RELEASE || act == ACT_DEATH) {
            return;
        }
        boolean fromScoopEnd = act == ACT_SCOOP && this.actionTicks >= SCOOP_TICKS;
        this.releaseTransition = fromScoopEnd ? 0 : TRANSITION_TICKS;
        this.releaseCooldown = shaken ? COOLDOWN_SHAKEN : this.holding ? COOLDOWN_AFTER_HOLD : COOLDOWN_AFTER_RAKE;
        startAction(ACT_RELEASE, this.releaseTransition);
        halt();
    }

    private void tickRelease() {
        halt();
        int k = this.actionTicks;
        if (this.holding && k >= this.releaseTransition + RELEASE_DROP_TICK) {
            dropPrey(true);
        }
        if (k >= this.releaseTransition + RELEASE_TICKS) {
            endAction(this.releaseCooldown);
        }
    }

    /** 獲物を降ろす。{@code toss} なら前へ放る(release の付属肢を開く動き)。 */
    private void dropPrey(boolean toss) {
        Entity prey = this.getFirstPassenger();
        this.releasing = true;
        this.ejectPassengers();
        this.releasing = false;
        this.holding = false;
        this.holdDamage = 0.0F;
        this.holdTicks = 0;
        this.loadGrace = 0;
        this.holdWant = null;
        if (toss && prey != null && prey.isAlive()) {
            Vec3 f = forward();
            prey.setDeltaMovement(f.x * 0.25D, 0.05D, f.z * 0.25D);
            prey.hurtMarked = true;
        }
    }

    /** 抱えていてよい相手のままか(クリエイティブ・観戦者に切り替えたプレイヤーは離す)。 */
    private static boolean stillPrey(LivingEntity prey) {
        return !(prey instanceof Player p) || (!p.isCreative() && !p.isSpectator());
    }

    // ------------------------------------------------------------------ 判定

    /** 抱えた姿勢の grab_point(獲物の胸)のワールド位置。サーバー・クライアント共通の式。 */
    public Vec3 grabPointWorld() {
        double s = this.getScale();
        return this.position().add(forward().scale(GRAB_FORWARD * s)).add(0.0D, GRAB_UP * s, 0.0D);
    }

    /**
     * 足元が {@code preyFeet}・背の高さ {@code preyHeight} の相手の胸を grab_point に合わせるときの、この Mob の足元(原点)。
     * 向き {@code yaw}(度)・体格 {@code scale}。GameTest と {@link #attackSpotFor} が使う。
     */
    public static Vec3 originForPrey(Vec3 preyFeet, double preyHeight, float yaw, double scale) {
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        return new Vec3(preyFeet.x - f.x * GRAB_FORWARD * scale,
                preyFeet.y + preyHeight * PREY_CHEST_RATIO - GRAB_UP * scale,
                preyFeet.z - f.z * GRAB_FORWARD * scale);
    }

    /** 標的をすくえる位置(標的の胸が grab_point に来る原点)。向きは自分から標的へ(真上・真下に近いときは今の向き)。 */
    Vec3 attackSpotFor(LivingEntity t) {
        double dx = t.getX() - this.getX();
        double dz = t.getZ() - this.getZ();
        float yaw = dx * dx + dz * dz < 0.25D ? this.getYRot() : yawTo(t);
        return originForPrey(t.position(), t.getBbHeight(), yaw, this.getScale());
    }

    /** 寄る点が使えるか: 洞窟の中から空の見える所へ出ない・体が入る。5 tick ごとに ScoopAttackGoal が呼ぶ。 */
    boolean spotUsable(Vec3 spot) {
        Vec3 top = spot.add(0.0D, this.getBbHeight(), 0.0D);
        if (!destinationAllowed(this.level(), this.position().add(0.0D, this.getBbHeight(), 0.0D), top)) {
            return false;
        }
        AABB box = this.getDimensions(this.getPose()).makeBoundingBox(spot).deflate(0.05D);
        return this.level().noCollision(this, box);
    }

    /** 追ってよい相手か: 相手が空の見える所に居ない(自分が空の下に迷い出ているときは誰でも)。 */
    boolean mayHunt(LivingEntity t) {
        return this.exposed || !exposedToSky(this.level(), t.getEyePosition());
    }

    /**
     * 標的に選べる相手か(標的選び {@code ParasitizedTargetGoal} の {@code TargetingConditions} と、標的を持ち続けるかの判定がこれを見る)。
     * 狩れない相手 —— 空の見える所の相手({@link #mayHunt})と、見えないまま諦めたばかりの相手 —— は選ばない(2026-10-09)。
     * 標的選びは壁越しにいちばん近い相手を選ぶので、狩れない相手を選んでは 5〜10 秒で外す、を繰り返して、近くの見える相手
     * (村人・動物・プレイヤー)へ向かわないことがあった。索敵箱を上下に広げた(10-09)ので、薄い天井の上の地表の相手も入るようになった分の手当てでもある。
     */
    @Override
    public boolean canAttack(LivingEntity target) {
        if (!super.canAttack(target) || !mayHunt(target)) {
            return false;
        }
        return this.ignoredTarget == null || this.tickCount >= this.ignoredUntil || !this.ignoredTarget.equals(target.getUUID());
    }

    /** 標的が見えた(狩りの Goal が毎 tick・{@link #tickCaveSense} が 10 tick ごとに呼ぶ)。 */
    void noteSighting(LivingEntity t) {
        this.seenTarget = t;
        this.seenTargetAt = this.tickCount;
    }

    /** その相手を最近({@value #LOST_SIGHT_GIVE_UP} tick 以内に)見たか。 */
    boolean seenRecently(LivingEntity t) {
        return t == this.seenTarget && this.tickCount - this.seenTargetAt <= LOST_SIGHT_GIVE_UP;
    }

    /**
     * 狩りをするか: 生きた標的が居て、空の見える所に居らず、最近見えた。そうでなければ(壁の向こうの相手・空の下の相手)漂う
     * ({@link CaveWanderGoal})。「洞窟の中の相手を見つけて」すくう(採用の案)の「見つける」をここで決める(仮決め)。
     */
    public boolean wantsToHunt() {
        LivingEntity t = this.getTarget();
        return t != null && t.isAlive() && mayHunt(t) && seenRecently(t);
    }

    /** すくいの輪(grab_point のまわり)に胸がある、襲ってよい相手。狙っている相手を先頭に、近い順。 */
    private List<LivingEntity> foesInScoop(@Nullable LivingEntity target) {
        double s = this.getScale();
        Vec3 g = grabPointWorld();
        double hw = SCOOP_ZONE_H * s;
        double hv = SCOOP_ZONE_V * s;
        AABB search = new AABB(g.x - hw - 1.0D, g.y - hv - 3.0D, g.z - hw - 1.0D, g.x + hw + 1.0D, g.y + hv + 1.0D, g.z + hw + 1.0D);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, search, this::isFoe)) {
            double chestY = e.getY() + e.getBbHeight() * PREY_CHEST_RATIO;
            double r = e.getBbWidth() * 0.5D;
            if (Math.abs(e.getX() - g.x) <= hw + r && Math.abs(e.getZ() - g.z) <= hw + r && Math.abs(chestY - g.y) <= hv) {
                out.add(e);
            }
        }
        out.sort((a, b) -> {
            if (a == target) return -1;
            if (b == target) return 1;
            return Double.compare(a.distanceToSqr(g), b.distanceToSqr(g));
        });
        return out;
    }

    /** 襲ってよい相手か(自分・乗客・防具立て・汚染側の仲間・クリエイティブ/観戦者は除く。狙っている相手は仲間でも)。 */
    private boolean isFoe(LivingEntity e) {
        if (e == this || !e.isAlive() || e.getVehicle() == this || e instanceof ArmorStand) {
            return false;
        }
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        if (e == this.getTarget()) {
            return true;
        }
        return MobGroup.of(e) == null;
    }

    /**
     * 抱えられる相手か(ボス・大きすぎる・何かに乗っている/乗られている相手は抱えない。ファルキムネスの canCarry と同じ考え)。
     * 大きさは体格に比例(体格 2 で幅 1.6・高さ 2.5 まで)。
     */
    public static boolean canCarry(LivingEntity e, double scale) {
        return !e.getType().is(Tags.EntityTypes.BOSSES)
                && e.getBbWidth() <= CARRY_MAX_WIDTH * scale && e.getBbHeight() <= CARRY_MAX_HEIGHT * scale
                && !e.isVehicle() && !e.isPassenger();
    }

    // ------------------------------------------------------------------ 空と洞窟

    /** その点が空に開いているか(空の光が 15 = 真上に空が見える)。光で見るので、屋根を掛けた直後は光の更新を待つ。 */
    public static boolean exposedToSky(Level level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        return level.isLoaded(pos) && level.canSeeSky(pos);
    }

    /**
     * 行き先に選んでよいか(「空の見える所へは出ない」)。空に開いていない所から空に開いた所へは出ない。
     * 既に空の下に居る(卵で地上に出された・押し出された)ときは縛らない。{@code from}・{@code to} は体の上端の点。
     */
    public static boolean destinationAllowed(Level level, Vec3 from, Vec3 to) {
        return !exposedToSky(level, to) || exposedToSky(level, from);
    }

    /**
     * 自然湧きの関門(湧かせ役が床の上に置いた後・世界に足す前)。空に開いた所・地表付近・狭い洞窟には湧かない。
     * 大きな空洞なら、床から持ち上げて空中({@link ErebomnesCavern.Result#placeY})に置く。
     * 体格(SCALE)はまだ当たり判定に掛かっていないので、大きさは {@link #getDimensions}(体格込み)で見る。
     */
    @Override
    public boolean acceptOvergrownSpawn(ServerLevel level) {
        BlockPos start = this.blockPosition();
        if (!level.isLoaded(start) || level.canSeeSky(start)) {
            return false;
        }
        int margin = OvergrownVegetationConfig.COMMON.caveDepthMargin.get();
        if (!CaveGrowth.isCave(level, start, margin)) {
            return false;
        }
        EntityDimensions dims = this.getDimensions(this.getPose());
        ErebomnesCavern.Result r = ErebomnesCavern.measure(ErebomnesCavern.loadedOnly(level), start, this.getScale(), dims.height());
        if (!r.ok()) {
            return false;
        }
        Vec3 at = new Vec3(start.getX() + 0.5D, r.placeY(), start.getZ() + 0.5D);
        if (exposedToSky(level, at.add(0.0D, dims.height(), 0.0D)) || !level.noCollision(dims.makeBoundingBox(at))) {
            return false;
        }
        this.moveTo(at.x, at.y, at.z, this.random.nextFloat() * 360.0F, 0.0F);
        this.lastCavePos = at;
        return true;
    }

    /** 漂う行き先を選ぶ(数秒に 1 回。8 回まで試す)。空の見える所・体の入らない所・まっすぐ行けない所は選ばない。 */
    @Nullable
    Vec3 pickWanderSpot() {
        Level lv = this.level();
        double s = this.getScale();
        double h = this.getBbHeight();
        Vec3 here = this.position();
        Vec3 hereTop = here.add(0.0D, h, 0.0D);
        EntityDimensions dims = this.getDimensions(this.getPose());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int startY = Mth.floor(here.y + h * 0.5D);
        for (int i = 0; i < 8; i++) {
            double ang = this.random.nextDouble() * Math.PI * 2.0D;
            double dist = (WANDER_MIN + this.random.nextDouble() * (WANDER_MAX - WANDER_MIN)) * s;
            double x = here.x + Math.cos(ang) * dist;
            double z = here.z + Math.sin(ang) * dist;
            if (!lv.isLoaded(m.set(x, startY, z)) || !ErebomnesCavern.isOpen(lv, m)) {
                continue;
            }
            int floorTop = Integer.MIN_VALUE;
            for (int d = 0; d <= WANDER_FLOOR_SCAN; d++) {
                if (!ErebomnesCavern.isOpen(lv, m.set(x, startY - d - 1, z))) {
                    floorTop = startY - d;
                    break;
                }
            }
            if (floorTop == Integer.MIN_VALUE) {
                continue;
            }
            int ceiling = floorTop + 16;
            for (int yy = floorTop; yy < floorTop + 16; yy++) {
                if (!ErebomnesCavern.isOpen(lv, m.set(x, yy, z))) {
                    ceiling = yy;
                    break;
                }
            }
            double lo = floorTop + CRUISE_MIN * s;
            double hi = ceiling - h - CEILING_KEEP * s;
            if (hi < lo) {
                continue;
            }
            double y = Math.min(hi, floorTop + (CRUISE_MIN + this.random.nextDouble() * (CRUISE_MAX - CRUISE_MIN)) * s);
            Vec3 p = new Vec3(x, y, z);
            if (!destinationAllowed(lv, hereTop, p.add(0.0D, h, 0.0D))) {
                continue;
            }
            if (!lv.noCollision(this, dims.makeBoundingBox(p).inflate(0.3D))) {
                continue;
            }
            Vec3 c0 = here.add(0.0D, h * 0.5D, 0.0D);
            Vec3 c1 = p.add(0.0D, h * 0.5D, 0.0D);
            if (lv.clip(new ClipContext(c0, c1, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this)).getType() != HitResult.Type.MISS) {
                continue;
            }
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ 泳ぎ

    /** 最高速度(ブロック/tick)= 飛ぶ速さ × 倍率 × 0.5。 */
    double maxSpeed(double modifier) {
        return this.getAttributeValue(Attributes.FLYING_SPEED) * modifier * 0.5D;
    }

    /** その場で止まる(速度はゆっくり 0 へ)。 */
    void halt() {
        if (this.moveControl instanceof SwimControl swim) {
            swim.halt();
        }
    }

    /** よそから加わった速度(ノックバック・爆発・壁にぶつかって止まった分)を泳ぎの速度に取り込む。 */
    private void absorbImpulse() {
        Vec3 diff = this.getDeltaMovement().subtract(this.lastSetVel);
        if (diff.lengthSqr() > 0.02D * 0.02D) {
            this.swimVel = this.swimVel.add(diff);
        }
    }

    /**
     * 狙いの速度 {@code want} へ泳ぐ。前へは体の向きにしか進まず(横は {@link #LATERAL_CAP} × 体格まで、後ろは {@link #BACK_CAP} まで)、
     * 向きは動作していなければ狙いの方へ回す。速度は途中の値をフィールドに持って少しずつ寄せる
     * (バニラが 0.003 未満の速度を毎 tick 0 にするので、getDeltaMovement から寄せると動き出せない)。
     */
    void steer(Vec3 want, double max) {
        double s = this.getScale();
        Vec3 wh = new Vec3(want.x, 0.0D, want.z);
        if (wh.lengthSqr() > 0.02D * 0.02D && getAction() == ACT_NONE) {
            requestYaw((float) (Mth.atan2(wh.z, wh.x) * Mth.RAD_TO_DEG) - 90.0F, FACE_MOVE);
        }
        Vec3 f = forward();
        double dot = wh.dot(f);
        double along = Mth.clamp(dot, -BACK_CAP * s, max);
        Vec3 lat = wh.subtract(f.scale(dot));
        double latCap = LATERAL_CAP * s;
        if (lat.lengthSqr() > latCap * latCap) {
            lat = lat.normalize().scale(latCap);
        }
        double vy = Mth.clamp(want.y, -max * 0.7D, max * 0.7D);
        Vec3 target = f.scale(along).add(lat).add(0.0D, vy, 0.0D);
        Vec3 diff = target.subtract(this.swimVel);
        // 今の速度を打ち消す向きの寄せ(減速・向きを変える)は強く、加速はゆっくり(BRAKE)
        this.swimVel = this.swimVel.add(diff.scale(diff.dot(this.swimVel) < 0.0D ? BRAKE : ACCEL));
        this.setDeltaMovement(this.swimVel);
        this.lastSetVel = this.swimVel;
    }

    /**
     * 床・天井・壁から離れる押し返し(2 tick ごと、数点だけ読む)。床は足元の下、天井は体の上、前は体の前の端と頭の先、横は体の両脇。
     * 狩り・抱えている間は床へ近づいてよい(床の上に立つ相手の胸は足元の 1.2 下に来ないとすくえない)。
     */
    private Vec3 computeAvoidance() {
        Level lv = this.level();
        double s = this.getScale();
        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        double h = this.getBbHeight();
        double halfW = this.getBbWidth() * 0.5D;
        double midY = y + h * 0.5D;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double px = 0.0D;
        double py = 0.0D;
        double pz = 0.0D;
        // 床
        double floorKeep = (isActing() || wantsToHunt()) ? FLOOR_KEEP_HUNT * s : FLOOR_KEEP * s;
        for (double d = 0.5D; d <= floorKeep + 1.0E-6D; d += 0.5D) {
            if (!ErebomnesCavern.isOpen(lv, m.set(x, y - d, z))) {
                py += AVOID_GAIN * (floorKeep + 0.5D - d) / (floorKeep + 0.5D);
                break;
            }
        }
        // 天井
        double ceilKeep = CEILING_KEEP * s;
        for (double d = 0.25D; d <= ceilKeep + 1.0E-6D; d += 0.5D) {
            if (!ErebomnesCavern.isOpen(lv, m.set(x, y + h + d, z))) {
                py -= AVOID_GAIN * (ceilKeep + 0.5D - d) / (ceilKeep + 0.5D);
                break;
            }
        }
        // 前(泳いでいる向き。止まっていれば体の向き)
        Vec3 dir = new Vec3(this.swimVel.x, 0.0D, this.swimVel.z);
        dir = dir.lengthSqr() > 0.03D * 0.03D ? dir.normalize() : forward();
        double near = halfW + 0.5D;
        double far = HEAD_REACH * s + 0.3D;
        if (!ErebomnesCavern.isOpen(lv, m.set(x + dir.x * near, midY, z + dir.z * near))) {
            px -= dir.x * AVOID_GAIN * 1.5D;
            pz -= dir.z * AVOID_GAIN * 1.5D;
        } else if (!ErebomnesCavern.isOpen(lv, m.set(x + dir.x * far, midY, z + dir.z * far))) {
            px -= dir.x * AVOID_GAIN;
            pz -= dir.z * AVOID_GAIN;
        }
        // 横
        Vec3 f = forward();
        double side = halfW + WALL_KEEP * s;
        for (int sign = -1; sign <= 1; sign += 2) {
            double rx = -f.z * sign;
            double rz = f.x * sign;
            if (!ErebomnesCavern.isOpen(lv, m.set(x + rx * side, midY, z + rz * side))) {
                px -= rx * AVOID_GAIN;
                pz -= rz * AVOID_GAIN;
            }
        }
        return new Vec3(px, py, pz);
    }

    /** 洞窟から空の下へ押し出されていたら、最後に居た洞窟の点へ戻るか。 */
    boolean shouldReturnToCave() {
        return this.exposed && this.lastCavePos != null && !this.holding && !isActing()
                && this.lastCavePos.distanceToSqr(this.position()) > 1.0D;
    }

    /** 重力なしで、速度だけで動く。死んだら重力を戻して床まで落とす(death は「足元 = 床」に横たわる形)。 */
    @Override
    public void travel(Vec3 travelVector) {
        if (!this.isNoGravity()) {
            super.travel(travelVector);
            return;
        }
        // isControlledByLocalInstance の囲みは必須(無いとクライアントでガクガク震える。アクムネスと同じ)
        if (this.isControlledByLocalInstance()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
        }
        this.calculateEntityAnimation(false);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // ------------------------------------------------------------------ 向き

    void requestYaw(float yaw, int priority) {
        if (priority >= this.wantedYawPriority) {
            this.wantedYaw = yaw;
            this.wantedYawPriority = priority;
        }
    }

    private void tickTurn() {
        if (!Float.isNaN(this.wantedYaw)) {
            this.setYRot(Mth.approachDegrees(this.getYRot(), this.wantedYaw, MAX_TURN));
        }
        this.wantedYaw = Float.NaN;
        this.wantedYawPriority = -1;
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
    }

    float yawTo(Entity target) {
        return (float) (Mth.atan2(target.getZ() - this.getZ(), target.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** 標的の方向の、正面からのずれ(度)。 */
    float offFront(Entity target) {
        return Mth.wrapDegrees(yawTo(target) - this.getYRot());
    }

    /** 体の向いている方向(水平の単位ベクトル)。 */
    Vec3 forward() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    // ------------------------------------------------------------------ 被弾・死

    /**
     * 同じ tick に同じダメージ源が 2 回来たら 2 回目を捨てる(爆発・なぎ払いが頭の部位と胴の両方に当たる。ティタニカと同じ)。
     * 抱えている間の被弾は振りほどきに数える。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide()) {
            long now = this.level().getGameTime();
            if (now == this.lastHitTick && source == this.lastHitSource) {
                return false;
            }
            this.lastHitTick = now;
            this.lastHitSource = source;
        }
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide() && this.isAlive() && isHoldingPrey()) {
            float lost = Math.max(0.0F, before - this.getHealth());
            this.holdDamage += lost;
            if (lost >= BIG_HIT_DAMAGE || this.holdDamage >= BREAK_FREE_DAMAGE) {
                startRelease(true);
            }
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        // 2 回目の die(死んだ後にまた呼ばれた)で倒れるアニメを最初からやり直さない
        if (!this.level().isClientSide() && this.dead && getAction() != ACT_DEATH) {
            if (this.holding || this.isVehicle()) {
                dropPrey(false);
            }
            startAction(ACT_DEATH, TRANSITION_TICKS);
            this.setNoGravity(false);
            this.swimVel = Vec3.ZERO;
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** 倒れるアニメ(3 秒)の間は消さない。バニラの横倒しは描画で切ってある({@code ErebomnesRenderer})。 */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= DEATH_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 乗客(抱えた獲物)

    /**
     * 獲物の胸を抱えた姿勢の grab_point(前 3.03・高さ 0.11、体格 2)に置く。bite_hold の間 grab_point は動かないので(NOTES)、
     * アニメの骨を読まずにこの固定の値で足りる(サーバー・クライアントで同じ式。NOTES の推奨)。
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        double s = this.getScale();
        double up = GRAB_UP * s - passenger.getBbHeight() * PREY_CHEST_RATIO + passenger.getVehicleAttachmentPoint(this).y;
        return new Vec3(0.0D, up, GRAB_FORWARD * s).yRot(-this.getYRot() * Mth.DEG_TO_RAD);
    }

    /** 放した獲物は抱えられていた所に降ろす(既定は乗り物の箱の上 = 背中の上に乗ってしまう)。壁に食い込んでいれば体の下の方へ寄せる。 */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Vec3 at = passenger.position();
        AABB box = passenger.getBoundingBox();
        if (this.level().noCollision(passenger, box)) {
            return at;
        }
        Vec3 under = new Vec3(this.getX(), at.y, this.getZ());
        for (double t = 0.25D; t <= 1.0D + 1.0E-6D; t += 0.25D) {
            Vec3 p = at.lerp(under, t);
            if (this.level().noCollision(passenger, box.move(p.subtract(at)))) {
                return p;
            }
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    /** 抱えた獲物に操縦させない。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    /** 抱えられたプレイヤーがこちら(胴)を殴れるように。頭の部位は乗り物ではないので元から殴れる。 */
    @Override
    public boolean canRiderInteract() {
        return true;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    // ------------------------------------------------------------------ 音(仮: バニラの流用)

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ELDER_GUARDIAN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ELDER_GUARDIAN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ELDER_GUARDIAN_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.55F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }

    @Override
    protected float getSoundVolume() {
        return 1.5F;
    }

    // ------------------------------------------------------------------ 描画まわり

    /**
     * 頭・付属肢・尾扇が当たり判定の外へ長く出るので、描画の打ち切りは広めに(NOTES: 全アニメの外接は前 5.64・後ろ 4.20・
     * 上 2.73・下 −0.72、体格 2)。これが無いと、見上げたときに頭ごと消える。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double s = this.getScale();
        return super.getBoundingBoxForCulling().inflate(2.0D * s, 0.45D * s, 2.0D * s);
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 抱えている途中なら、抱えていることと経過・削られた量を保存する(乗客の獲物は乗り物と一緒に保存され、読み込みで付き直す。
     * プレイヤーがログアウトしたときは、バニラがこの Mob をプレイヤーのデータへ入れて一緒に戻す)。動作の途中(すくい・離し)は保存しない。
     */
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("ErebCooldown", this.attackCooldown);
        // 離し始めていたら(release の 0.2 秒より前)抱えていることは残さない —— 読み込むと乗客は付き直すが、抱えていない乗客として
        // 次の tick に降ろす(以前は bite_hold からやり直し、振りほどいた後でも時間切れまで抱え直していた)。
        if (this.holding && getAction() != ACT_RELEASE && this.getFirstPassenger() != null) {
            tag.putBoolean("ErebHolding", true);
            tag.putInt("ErebHoldTicks", this.holdTicks);
            tag.putFloat("ErebHoldDamage", this.holdDamage);
        }
        if (this.lastCavePos != null) {
            tag.putDouble("ErebCaveX", this.lastCavePos.x);
            tag.putDouble("ErebCaveY", this.lastCavePos.y);
            tag.putDouble("ErebCaveZ", this.lastCavePos.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.attackCooldown = tag.getInt("ErebCooldown");
        this.setNoGravity(true);
        if (tag.contains("ErebCaveY")) {
            this.lastCavePos = new Vec3(tag.getDouble("ErebCaveX"), tag.getDouble("ErebCaveY"), tag.getDouble("ErebCaveZ"));
        }
        if (tag.getBoolean("ErebHolding")) {
            this.holding = true;
            this.holdTicks = tag.getInt("ErebHoldTicks");
            this.holdDamage = tag.getFloat("ErebHoldDamage");
            this.loadGrace = 10;
            startAction(ACT_HOLD, TRANSITION_TICKS);
            // 読み込み直した bite_hold は補間 4 tick の後に 0 秒目から流れるので、噛む時刻(1 周の 8・28 tick)もその分だけ後ろへ
            // (scoop の終わりからつなぐときは補間 0 なのでずらさない)。
            this.actionTicks = -TRANSITION_TICKS;
        }
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * 層は 1 つ({@code main})。泳ぐ種で足が滑る問題が無く、攻撃のアニメは鰭まで全部動かすので、2 層に分けると
     * 攻撃の層が止まった瞬間に泳ぎの層の姿勢へ飛ぶ(補間が掛からない)。1 層なら切り替えはいつも補間される。
     * scoop の終わり → bite_hold・release は同じ姿勢なので補間 0(NOTES。飛び 0.000)。当たりの tick はこの補間の値で数えてある。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, this::mainState));
    }

    private PlayState mainState(AnimationState<ErebomnesEntity> state) {
        AnimationController<ErebomnesEntity> c = state.getController();
        int act = getAction();
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientSeq) {
            this.clientSeq = seq;
            if (act != ACT_NONE) {
                c.forceAnimationReset();
            }
        }
        if (this.isDeadOrDying() || act == ACT_DEATH) {
            c.transitionLength(TRANSITION_TICKS);
            return state.setAndContinue(DEATH);
        }
        int transition = this.entityData.get(DATA_TRANSITION);
        switch (act) {
            case ACT_SCOOP -> {
                c.transitionLength(transition);
                return state.setAndContinue(SCOOP);
            }
            case ACT_HOLD -> {
                c.transitionLength(transition);
                return state.setAndContinue(HOLD);
            }
            case ACT_RELEASE -> {
                c.transitionLength(transition);
                return state.setAndContinue(RELEASE);
            }
            default -> {
            }
        }
        c.transitionLength(TRANSITION_TICKS);
        // 泳ぎ: この tick に動いた量(クライアントの速度は当てにならないので位置の差。メモリ geckolib-ismoving-broken)。
        // 速さで再生速度は変えない(位相が巻き戻る)。遅いときは idle に切り替える(NOTES の勧め)。
        double moved = Math.sqrt(Mth.square(this.getX() - this.xo) + Mth.square(this.getY() - this.yo) + Mth.square(this.getZ() - this.zo));
        if (moved > SWIM_ANIM_ON) {
            this.clientSwimming = true;
        } else if (moved < SWIM_ANIM_OFF) {
            this.clientSwimming = false;
        }
        return state.setAndContinue(this.clientSwimming ? SWIM : IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ================================================================== 制御

    /**
     * 泳ぎの制御(サーバー、AI のある個体)。行き先へ向かう速度に、床・天井・壁の押し返しを足して {@link #steer} へ渡す。
     * 洞窟から空の下へ押し出されていたら、行き先を最後に居た洞窟の点に差し替える。
     */
    static final class SwimControl extends MoveControl {
        SwimControl(Mob mob) {
            super(mob);
        }

        void halt() {
            this.operation = Operation.WAIT;
        }

        @Override
        public void tick() {
            ErebomnesEntity e = (ErebomnesEntity) this.mob;
            e.absorbImpulse();
            if (e.shouldReturnToCave()) {
                Vec3 c = e.lastCavePos;
                this.setWantedPosition(c.x, c.y, c.z, CRUISE);
            }
            double max = e.maxSpeed(this.operation == Operation.MOVE_TO ? this.speedModifier : CRUISE);
            Vec3 want = Vec3.ZERO;
            if (this.operation == Operation.MOVE_TO) {
                Vec3 d = new Vec3(this.wantedX - e.getX(), this.wantedY - e.getY(), this.wantedZ - e.getZ());
                double dist = d.length();
                if (dist < 0.25D || this.speedModifier <= 0.0D) {
                    this.operation = Operation.WAIT;
                } else {
                    double speed = Math.min(max, dist * 0.1D);
                    want = d.scale(speed / dist);
                }
            }
            // 寄る点の近く・すくいの最中は押し返しを切る(CLOSE_IN_RANGE)
            boolean noAvoid = e.closingIn || e.getAction() == ACT_SCOOP;
            e.steer(noAvoid ? want : want.add(e.avoidCache), max);
        }
    }

    /** 頭だけ回すバニラの見回しを止める(向きは {@link #tickTurn} だけが決める)。 */
    static final class FixedLookControl extends LookControl {
        FixedLookControl(Mob mob) {
            super(mob);
        }

        @Override
        public void tick() {
        }
    }

    /**
     * 狩りの Goal(見えた標的が居る間。{@link #wantsToHunt})。標的の胸が grab_point に来る点へ泳いで寄り、近くでは標的を向く。着いて向きが合い、
     * 冷めていればすくう(掴みか払いかは {@link SpecialPacing#roll})。動作の最中は何もしない({@link #tickAction} が動かす)。
     * 寄る点が空の見える所・体の入らない所なら、その場で待つ(狭い横穴へ入らない)。見えないまま {@value #LOST_SIGHT_GIVE_UP} tick で降りる
     * (標的を外すのは {@link #tickCaveSense})。
     */
    static final class ScoopAttackGoal extends Goal {
        private final ErebomnesEntity mob;
        private int spotCheck;
        private boolean spotOk = true;

        ScoopAttackGoal(ErebomnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.mob.wantsToHunt();
        }

        @Override
        public boolean canContinueToUse() {
            return this.mob.isActing() || canUse();
        }

        @Override
        public void start() {
            this.spotCheck = 0;
            this.mob.setAggressive(true);
        }

        @Override
        public void stop() {
            this.mob.setAggressive(false);
            this.mob.closingIn = false;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            this.mob.closingIn = false;
            if (this.mob.isActing()) {
                return;
            }
            LivingEntity t = this.mob.getTarget();
            if (t == null) {
                return;
            }
            if (this.mob.getSensing().hasLineOfSight(t)) {
                this.mob.noteSighting(t);
            }
            double s = this.mob.getScale();
            Vec3 spot = this.mob.attackSpotFor(t);
            if (--this.spotCheck <= 0) {
                this.spotCheck = 5;
                this.spotOk = this.mob.spotUsable(spot);
            }
            if (this.spotOk) {
                this.mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, CHASE);
            } else {
                this.mob.halt();
            }
            double d = this.mob.position().distanceTo(spot);
            this.mob.closingIn = this.spotOk && d < CLOSE_IN_RANGE * s;
            double hx = t.getX() - this.mob.getX();
            double hz = t.getZ() - this.mob.getZ();
            // 真上・真下に近いと向きが定まらないので向き直らない(寄る点は今の向きの後ろ = 後ずさりで離れる)
            if (d < FACE_TARGET_RANGE * s && hx * hx + hz * hz > 1.0D) {
                this.mob.requestYaw(this.mob.yawTo(t), FACE_HUNT);
            }
            if (this.mob.attackCooldown <= 0 && this.spotOk && d < ATTACK_POS_TOLERANCE * s
                    && Math.abs(this.mob.offFront(t)) < ATTACK_FACE_TOLERANCE) {
                boolean grab = this.mob.pacing.roll(this.mob.getRandom()) && canCarry(t, s);
                this.mob.startScoop(grab);
            }
        }
    }

    /** 狩りをしていない間(標的が居ない・見えない・空の下に居る): 空洞の中をゆっくり漂う(数秒に 1 回、行き先を選び直す)。 */
    static final class CaveWanderGoal extends Goal {
        private final ErebomnesEntity mob;
        @Nullable
        private Vec3 dest;
        private int ticks;

        CaveWanderGoal(ErebomnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (this.mob.wantsToHunt() || this.mob.isActing() || this.mob.getRandom().nextInt(60) != 0) {
                return false;
            }
            this.dest = this.mob.pickWanderSpot();
            return this.dest != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.dest != null && !this.mob.wantsToHunt() && !this.mob.isActing() && this.ticks < 300
                    && this.mob.position().distanceToSqr(this.dest) > 1.0D;
        }

        @Override
        public void start() {
            this.ticks = 0;
        }

        @Override
        public void stop() {
            this.dest = null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            this.ticks++;
            if (this.dest != null) {
                this.mob.getMoveControl().setWantedPosition(this.dest.x, this.dest.y, this.dest.z, CRUISE);
            }
        }
    }

    // ------------------------------------------------------------------ 抱えられた獲物は降りられない・壁で窒息しない

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        /**
         * 抱えている獲物は自分では降りられない(スニーク・/ride dismount)。降車はサーバーでしか起きない。
         * 本体が消える(アンロード・倒れて消える)・獲物が死んでいる・自分で離す({@code releasing})ときは止めない。
         * 抱えられたプレイヤーのログアウトも止めない —— バニラ({@code PlayerList#remove})はここで降ろしてから、乗り物(この Mob)を
         * 世界から外してプレイヤーのデータに入れ、次のログインで抱えた状態のまま戻す(抱えていることは {@link #addAdditionalSaveData} が保存)。
         * 止めると、プレイヤーが乗ったまま乗り物と一緒に外されてしまう。
         */
        @SubscribeEvent
        public static void onMount(EntityMountEvent event) {
            Entity rider = event.getEntityMounting();
            if (event.isDismounting() && !event.getLevel().isClientSide()
                    && event.getEntityBeingMounted() instanceof ErebomnesEntity e
                    && e.isAlive() && !e.isRemoved() && e.isHoldingPrey()
                    && rider.isAlive() && !rider.isRemoved()
                    && !(rider instanceof ServerPlayer sp && sp.hasDisconnected())) {
                event.setCanceled(true);
            }
        }

        /**
         * 抱えられている獲物は壁の中で窒息しない(頭が壁・天井に近いと獲物の目が壁に入る。ファルキムネスの既知の問題の対策)。
         * 抱えている側も獲物が食い込んだら頭を引く({@link #steerWhileHolding})。
         */
        @SubscribeEvent
        public static void onIncomingDamage(LivingIncomingDamageEvent event) {
            if (event.getSource().is(DamageTypes.IN_WALL) && event.getEntity().getVehicle() instanceof ErebomnesEntity) {
                event.setCanceled(true);
            }
        }

        private Events() {
        }
    }
}
