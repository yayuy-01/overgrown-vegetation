package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1DeathBurst;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1BowShotGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1CarryGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1DoorBashGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1DropPodGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffLooseGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1FuseBurstGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1GrazeGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1LayEggGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Latch;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1LeapGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1PerchGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Blink;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1GrabGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSlamGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TeleportGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1RootsilkBolt;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SpitGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkSprayGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Move;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1RootAnchorGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SleepGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SpewGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeGiftGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeMistGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporePuffGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TechniqueGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Tether;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1ThroatPuffGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1TossGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1VineLashGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1WalkingSource;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * G1 の 1 段目「発達した寄生された○○」(2026-11-12、第 1 段 → 同日 能力の第 1 回)。11 種とも 1 つのクラスで、種の値は {@link G1Developed}。
 * 段階 0 の作り({@link AbstractParasitizedMonster}: どの生き物も襲う・3 段の 1 撃・夜は明かりと生き物の方へ・歩きは速さ合わせ・日に焼けない)の上に、
 *
 * <ul>
 *   <li>(a) 育てた近接: 3 段の 1 撃(前触れ 12・実行 3・隙は種のアニメの長さ)。届く距離・重さの違いは次の段【仮】。
 *   チビゾンビは当たると取り付く({@link G1Latch})。</li>
 *   <li>(b)(c) 種の技(能力の第 1 回 = ゾンビ・チビゾンビ・村人・豚、第 2 回 = 牛・羊・鶏。技ごとのクラスは {@code entity/g1/skill/}、アニメの段は {@link G1Move}):
 *     <ul>
 *       <li>ゾンビ: 戸口を破る {@link G1DoorBashGoal}・殴られると胞子を噴いて仲間を呼ぶ({@link #callAllies})・肩の蔦で絡める {@link G1VineLashGoal}。</li>
 *       <li>チビゾンビ: 低い隙間をくぐり柵を越える(navigation が柵を越える経路を引く・段差 1.5・{@link #tickBabyMoves})・頭の袋が胞子を噴く {@link G1SporePuffGoal}。</li>
 *       <li>村人: 昼は胞子を配る {@link G1SporeGiftGoal}・夜は眠って嚢を開く {@link G1SleepGoal}(11-13 に「巣」から作り直し)・背の瘤の胞子の吹き出し {@link G1SporeMistGoal}。</li>
 *       <li>豚: 小さな G1 を背に乗せて運ぶ {@link G1CarryGoal}・放り出す {@link G1TossGoal}・喉袋の胞子噴き {@link G1ThroatPuffGoal}。</li>
 *       <li>牛(第 2 回 11-13): 胞子の乳 {@link G1MilkSprayGoal}(乳だまり {@link G1MilkPuddle})・歩く発生源 {@link G1WalkingSource}。</li>
 *       <li>羊(第 2 回): 草を食べて綿毛が育つ {@link G1GrazeGoal}・鋏で刈れる({@link #shear})・綿毛を風に飛ばす {@link G1FluffLooseGoal}・燃えやすい・
 *       爆ぜると残りの綿毛の一部が風下へ流れる({@link #DEATH_FLUFF_PERCENT_PER_STAGE}、11-13 ユーザー決定)。</li>
 *       <li>鶏(第 2 回): 汚染された卵を産む {@link G1LayEggGoal}・そ嚢の胞子を吐く {@link G1SpewGoal}。</li>
 *       <li>クリーパー(第 3 回 11-13): 胞子の爆ぜ {@link G1FuseBurstGoal}(地形を壊さない・死なない・莢は {@value #POD_REGROW_TICKS} tick で育ち直す)・
 *       莢を落とす {@link G1DropPodGoal}(落ちた莢 {@code G1SeedPod})。</li>
 *       <li>スケルトン(第 3 回): 植物の弓で胞子の塊を射る {@link G1BowShotGoal}(弾 {@link G1SporeBolt})・根を張って据え撃ち {@link G1RootAnchorGoal}。</li>
 *     </ul></li>
 *   <li>技と技の間は 10〜20 tick の乱数【仮・共通】({@link #techniqueReady})。種の技は 3 段の 1 撃より優先度が高いが、1 撃の途中には割り込まない。</li>
 *   <li>死に際: {@code death_burst}(震え → 胞子嚢だけ膨らむ)を流し、膨らみきった時刻に破裂して消える({@link G1DeathBurst}、11-11〜12 ユーザー決定)。
 *   死体は作らない(答え 4)。バニラの倒れ込み・赤い被弾色・白い煙は出さない(描画は {@code client/G1GeoRenderer}・破裂は G1DeathBurst)。</li>
 *   <li>グループは G1 のまま(答え 1)。自然には湧かない(司令塔ができるまでスポーンエッグとコマンドだけ、11-09)。</li>
 * </ul>
 * 声は段階 0 の種と同じ(元の Mob の声)【仮】。残りの 2 種(クモ・エンダーマン)の (b)(c) は第 4 回で同じ作りに足す。
 */
public class DevelopedParasitized extends AbstractParasitizedMonster {

    private static final RawAnimation DEATH_BURST = RawAnimation.begin().thenPlayAndHold("death_burst");
    private static final RawAnimation EVOLVE_IN = RawAnimation.begin().thenPlayAndHold("evolve_in");
    private static final RawAnimation CALL_BURST = RawAnimation.begin().thenPlay("call_burst");
    /** 村人の (b) 昼: 嚢をちぎって下手で投げる(1.1 秒・左腕と脇の嚢だけの重ね。10 tick 目に手を離す)。 */
    private static final RawAnimation GIFT = RawAnimation.begin().thenPlay("gift");
    /** チビゾンビ: 豚の背に四つ這いで張り付く(繰り返し)。 */
    private static final RawAnimation RIDE = RawAnimation.begin().thenLoop("ride");
    /** 段階 0 から変わる所を見せている(アニメ evolve_in)。今は {@code /ov perf g1 evolve} からだけ(進化の時計は司令塔と一緒に、11-12 了承)。 */
    public static final int ACTION_EVOLVE = 10;

    // ------------------------------------------------------------------ 【仮】の数値(2026-11-12 能力の第 1 回。設計データ・config には出していない)
    /** 技と技の間(tick)。共通の仕様書「10〜20 tick の乱数」。 */
    static final int TECHNIQUE_GAP_MIN = 10, TECHNIQUE_GAP_MAX = 20;
    /** 1 撃の後、種の技が次の 1 撃より何 tick 先に使えるようになるか(Goal の判定は 2 tick に 1 回なので 3)。 */
    static final int TECHNIQUE_HEAD_START = 3;
    /** ゾンビ (b-2): 煙の半径・長さ・暴露量 / 秒・呼ぶ範囲・冷却。仕様「半径 2・1 秒・+100 / 秒・16 ブロック・冷却 10 秒」。 */
    static final float CALL_CLOUD_RADIUS = 2.0F;
    static final int CALL_CLOUD_TICKS = 20;
    static final double CALL_DOSE_PER_SECOND = 100.0D;
    static final double CALL_RANGE = 16.0D;
    static final int CALL_COOLDOWN = 200;
    /** チビゾンビ (a): 取り付きの長さ・噛む間・噛む強さ・1 回の暴露量・鈍足(1 = II = −30%)。仕様「1 / 10 tick・最大 3 秒・−30%」。 */
    static final int LATCH_TICKS = 60;
    static final int LATCH_BITE_INTERVAL = 10;
    static final float LATCH_BITE_DAMAGE = 1.0F;
    static final double LATCH_BITE_DOSE = 50.0D;
    static final int LATCH_SLOW = 1;
    /** チビゾンビ (b): 越えられる段(ブロック、柵 1.5 = 【決定】)・屋根の下の鼓動(間隔 tick・1 回の浮遊 %・上限の回数)。仕様「+1% / 5 秒・最大 +5%」。 */
    public static final double BABY_STEP_HEIGHT = 1.5D;
    static final int ROOF_PULSE_TICKS = 100;
    static final double ROOF_PULSE_PERCENT = 1.0D;
    static final int ROOF_PULSE_MAX = 5;
    /** チビゾンビ (b): 柵を乗り越えるアニメ climb_over の長さ。 */
    static final int CLIMB_TICKS = 24;
    /** 放り出されたチビゾンビが着地して取り付ける距離(ブロック)。 */
    static final double TOSS_LATCH_REACH = 1.8D;
    /** 豚 (b): 乗せている間の速さの倍率(×1.2)。豚が死んだときの乗り手の転ぶ長さ(1 秒)。 */
    static final double CARRY_SPEED_BONUS = 0.2D;
    static final int RIDER_FALL_STUN = 20;
    static final double PIG_BACK_HEIGHT = 0.875D;
    /**
     * 豚 (a) 食らいつき(2026-11-13): 離さない長さ・暴露量を足す間と 1 回の量(= +100 / 秒)・鈍足(2 = III = −45%。仕様は −40% だが
     * 鈍足は 15% 刻みなので近い方)・引きずられ始めるすきま(ブロック)・口を離す隙(attack_recover 0.7 秒)・振りほどかれたときのよろけ。
     * 仕様「最大 40 tick【仮】・+100 暴露 / 秒【仮】・移動 −40%」。すきま・よろけの長さは仕様に数が無い【仮】。
     */
    public static final int BITE_HOLD_TICKS = 40;
    static final int BITE_DOSE_INTERVAL = 10;
    public static final double BITE_DOSE = 50.0D;
    static final int BITE_SLOW = 2;
    static final double BITE_DRAG_GAP = 0.3D;
    public static final int BITE_RECOVER_TICKS = 14;
    public static final int BITE_SHAKEN_STUN = 20;
    /** 村人 (b): 眠って嚢を開いている間の被弾の倍率。仕様「開いた嚢への攻撃は 2 倍【仮】」。 */
    static final float SLEEP_DAMAGE_MULTIPLIER = 2.0F;
    private static final ResourceLocation CARRY_SPEED_ID = OvergrownVegetation.id("g1_carry_speed");

    // ------------------------------------------------------------------ 【仮】の数値(2026-11-13 能力の第 2 回 = 牛・羊・鶏)
    /** 羊 (b): 綿毛の段の上限(仕様「綿毛は 3 段」。飛ばすと 1 段減る・草 1 回で 1 段戻る・刈ると 0 段)。 */
    public static final int FLUFF_MAX = 3;
    /** 羊 (b): 刈ったときに舞う胞子(周り {@value} ブロックへ暴露量 +{@link #SHEAR_DOSE})と、落ちる覆われた羊毛の数(1〜2)。 */
    static final double SHEAR_RADIUS = 2.0D;
    static final double SHEAR_DOSE = 300.0D;
    /** 羊: 燃えやすい【決定 11-09】。火のダメージの倍率。 */
    static final float SHEEP_FIRE_MULTIPLIER = 1.5F;
    /**
     * 羊: 爆ぜたとき、残っていた綿毛の一部が風下へ流れる【決定 11-13 ユーザー「羊が爆ぜたときに綿毛が風下に行く要素はアリだね」・数は仮】。
     * 流れは 1 本、着地点の升へ「残りの綿毛の段 × {@value}%」の降り積もり((c) の 1 回 15% の 3 分の 1 ずつ = 満杯でも 15%。
     * 倒すことが (c) の 2 分の冷却を打ち消さない大きさに)。
     */
    public static final double DEATH_FLUFF_PERCENT_PER_STAGE = 5.0D;
    /** 牛: 破裂の跡に残す乳だまり 1 つの半径(仕様【決定 11-09 牛 8】。長さは噴いた乳だまりと同じ 6 秒)。 */
    static final float DEATH_PUDDLE_RADIUS = 1.2F;

    // ------------------------------------------------------------------ 【仮】の数値(2026-11-13 能力の第 3 回 = クリーパー・スケルトン)
    /** クリーパー (b): 爆ぜた後、莢が育ち直すまで(tick)。仕様「60 秒」。 */
    public static final int POD_REGROW_TICKS = 1200;
    /** クリーパー (b): 爆ぜの解禁 = 交戦(標的が {@value #ENGAGE_RANGE} ブロック以内)がこの tick 続き、薙ぎ払いを 1 回振った後(仕様【案】「交戦 6 秒 + 薙ぎ払い 1 回」)。 */
    static final int FUSE_UNLOCK_ENGAGED = 120;
    static final double ENGAGE_RANGE = 16.0D;
    /** 交戦が切れたとみなすまで(tick)。 */
    static final int DISENGAGE_TICKS = 100;
    /** スケルトン (c): 据えている間、標的がいて技を出していないときの体の回り(度 / tick)。 */
    static final float ANCHORED_IDLE_TURN = G1BowShotGoal.ANCHORED_TURN;
    private static final ResourceLocation ANCHOR_KNOCKBACK_ID = OvergrownVegetation.id("g1_anchor_knockback");

    // ------------------------------------------------------------------ 【仮】の数値(2026-11-13 能力の第 4 回 = クモ・エンダーマン)
    /** クモ (b1): 壁を登る速さ(正味のブロック / tick)。仕様「歩きの 0.5 倍」= 歩きの足が滑らない速さ 0.124 の半分。バニラのクモは約 0.118。 */
    public static final double CLIMB_SPEED = 0.062D;
    /** クモ: 壁から離れても登りのアニメを続ける tick(当たりが 1 tick 途切れても絵が飛ばないように)。 */
    static final int CLIMB_GRACE = 4;
    /** エンダーマン (b): 土を拾う — 届く範囲(水平、ブロック)・試す回数・試す間隔(tick)・「近くに獲物がいない」の半径。 */
    static final int SOIL_REACH = 2;
    static final int SOIL_TRIES = 12;
    static final int SOIL_INTERVAL_MIN = 200, SOIL_INTERVAL_MAX = 400;
    static final double SOIL_CALM_RADIUS = 8.0D;
    /** エンダーマン (b): 置いた土のマスの升の浮遊の上げ幅(%)。仕様「そのマスが汚染のきっかけになる」。 */
    static final double SOIL_AIR_PERCENT = 1.0D;
    /** エンダーマン (b): 目が合ったと数える距離(ブロック)と、標的を見失ってからまた目を合わせられるまで(tick)。 */
    static final double GAZE_RANGE = 32.0D;
    static final int GAZE_RESET_TICKS = 100;
    /** エンダーマン (b): 被弾のきっかけが残る tick(休みの間に受けた 1 撃で、休みが明けてから渡らないように)。 */
    static final int HURT_TELEPORT_WINDOW = 40;
    /** エンダーマン (b): 土を抱えている間の腕の重ね(繰り返し)。 */
    private static final RawAnimation CARRY = RawAnimation.begin().thenLoop("carry");

    /** エンダーマンが抱えている汚染した土(バニラのエンダーマンと同じ持ち方。両腕の間に描くので同期する)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<java.util.Optional<BlockState>> CARRIED =
            net.minecraft.network.syncher.SynchedEntityData.defineId(DevelopedParasitized.class,
                    net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_BLOCK_STATE);

    /** クリーパーの背の莢の満ち具合(0 = 爆ぜた直後・100 = 満ちている)。莢とその子のシダの大きさに使うので同期する({@code client/G1DevelopedRig})。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> PODS = net.minecraft.network.syncher.SynchedEntityData.defineId(
            DevelopedParasitized.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    /** スケルトンが根を張って据えているか。据えたまま射る間も足の根を地面に刺したままに見せるので同期する。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> ANCHORED = net.minecraft.network.syncher.SynchedEntityData.defineId(
            DevelopedParasitized.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    /** 羊の綿毛の段(0〜{@link #FLUFF_MAX})。房の見た目に使うので同期する。羊のほかは使わない。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> FLUFF = net.minecraft.network.syncher.SynchedEntityData.defineId(
            DevelopedParasitized.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    /** evolve_in の残り(サーバーだけ)。流している間は AI を止める(無防備 = 倒す好機、答え 3 の案)。 */
    private int evolveLeft;

    @Nullable
    private G1Developed dev;

    // ------------------------------------------------------------------ 技の状態(サーバーだけ)
    private long techniqueReadyAt;
    private long strikeNotBefore;
    @Nullable
    private G1Latch latch;
    /** 取り付いている間の行動の段(チビゾンビ = LATCH、豚の食らいつき = BITE_HOLD)。 */
    private G1Move latchMove = G1Move.LATCH;
    @Nullable
    private G1Tether tether;
    private long callReadyAt;
    private boolean puffTrigger;
    private boolean mistTrigger;
    private boolean mistByHealthUsed;
    private long giftReadyAt;
    @Nullable
    private Boolean dayOverride;
    @Nullable
    private G1Move holdMove;
    private int holdLeft;
    private int hurtCount;
    private int roofTicks;
    private int roofPulses;
    private int climbLeft;
    @Nullable
    private LivingEntity tossedAt;
    private int tossedTicks;
    /** 牛 (c): 歩く発生源の状態。 */
    private final G1WalkingSource walkingSource = new G1WalkingSource();
    /** 鶏 (b): 次に卵を産める時刻(ゲームの tick。-1 = まだ決めていない)と、産んだ数。 */
    private long layReadyAt = -1L;
    private int eggsLaid;
    /** 羊 (c): 最後に放った綿毛の着地点(GameTest・確かめ用)。 */
    @Nullable
    private net.minecraft.world.phys.Vec3 lastFluffLanding;
    /** 「近くに獲物がいない」の半径の上書き(GameTest が、狭いテスト場で遠くの標的を置くために縮める)。 */
    @Nullable
    private Double calmRadiusOverride;
    /** クリーパー (b): 莢が育ち直すまでの残り(0 = 満ちている)。 */
    private int podRegrowLeft;
    /** クリーパー (c): 次に莢を落とせる時刻(-1 = まだ決めていない)。 */
    private long podDropReadyAt = -1L;
    /** クリーパー (b): 交戦している tick・交戦が切れている tick・交戦してから薙ぎ払いを振ったか・確かめ用に解禁を飛ばすか。 */
    private int engagedTicks;
    private int disengagedTicks;
    private boolean struckSinceEngage;
    private boolean fuseForced;
    /** クライアントだけ: 見せている莢の満ち具合(0〜1)と 1 tick 前の値(爆ぜのアニメからの受け渡しをならす)。 */
    private float podsShown = 1.0F, podsShownO = 1.0F;
    /** スケルトン (c): 据えている残り・次に据えられる時刻・据える距離の上書き(GameTest)。 */
    private int anchorLeft;
    private long anchorReadyAt;
    @Nullable
    private Double anchorMinRangeOverride;
    /** スケルトン (b): 射た弾の数と最後の弾(GameTest・確かめ用)。 */
    private int boltsShot;
    @Nullable
    private G1SporeBolt lastBolt;
    /** クモ (b1): 壁に当たっていて登れる・登りのアニメの残り・高い所に張り付いている(重力を切る。保存しない)・落下のダメージを受けない残り。 */
    private boolean climbing;
    private int climbGrace;
    private boolean perched;
    private int noFallTicks;
    /** クモ (b1): 待ち伏せの Goal(確かめ用のコマンドが冷却を飛ばす)と、GameTest が決めた待ち伏せの場所。構築子の中で埋まるので初期化子を付けない。 */
    private G1PerchGoal perchGoal;
    @Nullable
    private G1PerchGoal.Spot forcedPerch;
    /** クモ (c): 吐いた根糸の数と最後の弾(GameTest・確かめ用)。 */
    private int rootsilkShots;
    @Nullable
    private G1RootsilkBolt lastRootsilk;
    /** エンダーマン (b): 土を抱えてからの tick・次に土を拾ってみる時刻(-1 = まだ決めていない)・置いた数と所。 */
    private int carriedTicks;
    private long soilTryAt = -1L;
    private int soilPlanted;
    @Nullable
    private BlockPos lastPlanted;
    /** エンダーマン (b): 転移の休みが明ける時刻・被弾のきっかけ・目が合った相手・この出会いで目を合わせたか・標的がいない tick。 */
    private long teleportReadyAt;
    private long teleportTriggerUntil = -1L;
    @Nullable
    private LivingEntity gazer;
    private boolean gazeUsed;
    private int noTargetTicks;
    /** エンダーマン: GameTest 用の上書き(遠いとみなす距離・転移の距離)と、渡った回数・最後に出た所。 */
    @Nullable
    private Double farRangeOverride;
    @Nullable
    private double[] teleportRangeOverride;
    private int teleports;
    @Nullable
    private net.minecraft.world.phys.Vec3 lastTeleportFrom;
    /** エンダーマン (c): 根の技を使える時刻・抱えている相手とそこから受けたダメージ・数え(GameTest・確かめ用)。 */
    private long rootSkillNotBefore;
    @Nullable
    private LivingEntity grabVictim;
    private float grabDamage;
    private int grabsCarried;
    private int slams;
    private int lastSlamSpikes;
    private int lastSlamHits;
    /** 種の技の Goal。親の構築子の中(registerGoals)で埋まるので、初期化子を付けない(付けると構築子の後で空に戻る)。 */
    private List<G1TechniqueGoal> techniques;

    public DevelopedParasitized(EntityType<? extends DevelopedParasitized> type, Level level) {
        super(type, level);
    }

    /** 種の値(登録名から引く。親の構築子の中の Goal の登録からも呼ばれるので、型から遅れて引く)。 */
    public G1Developed dev() {
        if (dev == null) {
            dev = G1Developed.of(getType());
        }
        return dev;
    }

    public static AttributeSupplier.Builder attributes(G1Developed d) {
        AttributeSupplier.Builder b = Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, d.health)
                .add(Attributes.ATTACK_DAMAGE, d.attack)
                .add(Attributes.MOVEMENT_SPEED, d.speed)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
        if (d == G1Developed.BABY_ZOMBIE) {
            b.add(Attributes.STEP_HEIGHT, BABY_STEP_HEIGHT); // (b) 柵・塀を四つ足でよじ登る(1.5 ブロック【決定 11-09】)
        }
        return b;
    }

    @Override
    public G1Species species() {
        return dev().base();
    }

    /** 1 段目はクリーパーも殴る(薙ぎ払い 4、仕様書【決定】)。 */
    @Override
    public boolean attacks() {
        return true;
    }

    @Override
    public double walkRef() {
        return dev().walkRef;
    }

    @Override
    public int recoverTicks() {
        return dev().recover;
    }

    // ------------------------------------------------------------------ Goal・navigation
    @Override
    protected void registerGoals() {
        super.registerGoals();
        switch (dev()) {
            case ZOMBIE -> {
                this.goalSelector.addGoal(1, new G1DoorBashGoal(this));
                addTechnique(new G1VineLashGoal(this));
            }
            case BABY_ZOMBIE -> addTechnique(new G1SporePuffGoal(this));
            case VILLAGER -> {
                addTechnique(new G1SporeMistGoal(this));
                this.goalSelector.addGoal(1, new G1SleepGoal(this)); // (b) 夜: 眠って嚢を開く(追跡より優先。遠くに標的がいても眠る)
                this.goalSelector.addGoal(3, new G1SporeGiftGoal(this)); // (b) 昼: 胞子を配る
            }
            case PIG -> {
                addTechnique(new G1TossGoal(this));
                addTechnique(new G1ThroatPuffGoal(this));
                this.goalSelector.addGoal(4, new G1CarryGoal(this));
            }
            case COW -> addTechnique(new G1MilkSprayGoal(this)); // (b) 胞子の乳。(c) 歩く発生源は tick から
            case SHEEP -> {
                addTechnique(new G1FluffLooseGoal(this)); // (c) 綿毛を風に飛ばす
                addTechnique(new G1GrazeGoal(this)); // (b) 草を食べて綿毛が育つ(刈るのは mobInteract)
            }
            case CHICKEN -> {
                addTechnique(new G1SpewGoal(this)); // (c) そ嚢の胞子を吐く
                addTechnique(new G1LayEggGoal(this)); // (b) 汚染された卵を産む
            }
            case CREEPER -> {
                addTechnique(new G1FuseBurstGoal(this)); // (b) 胞子の爆ぜ
                addTechnique(new G1DropPodGoal(this)); // (c) 莢を落とす
            }
            case SKELETON -> {
                addTechnique(new G1RootAnchorGoal(this)); // (c) 根を張って据え撃ち(射るより先に確かめる)
                addTechnique(new G1BowShotGoal(this)); // (b) 胞子の塊を射る
            }
            case SPIDER -> {
                perchGoal = new G1PerchGoal(this);
                this.goalSelector.addGoal(1, perchGoal); // (b1) 壁を登って高い所で待ち伏せ(登って追うのは onClimbable と navigation)
                addTechnique(new G1LeapGoal(this)); // (b2) 跳びつき
                addTechnique(new G1SpitGoal(this)); // (c) 根糸を吐く
            }
            case ENDERMAN -> {
                addTechnique(new G1TeleportGoal(this, true)); // (b) 目が合ったら首を向けて、溜めて転移で近づく(1 回だけ)
                addTechnique(new G1RootSlamGoal(this)); // (c-2) 根が地面から突き上がる(汚染した地面の上の相手へ)
                addTechnique(new G1GrabGoal(this)); // (c-1) 根で掴んで連れて行く
                addTechnique(new G1TeleportGoal(this, false)); // (b) 転移(土を抱えている・被弾した・標的が遠い)
            }
            default -> {
            }
        }
    }

    private void addTechnique(G1TechniqueGoal goal) {
        if (techniques == null) techniques = new ArrayList<>();
        techniques.add(goal);
        this.goalSelector.addGoal(1, goal);
    }

    /**
     * ゾンビは扉を通る経路を引く(叩き壊すため)。チビゾンビは柵・塀を越える経路を引く(段差 1.5 と組)。
     * 構築子の中で呼ばれる(型は分かっている)。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        G1Developed d = G1Developed.of(getType());
        if (d == G1Developed.SPIDER) {
            return new com.yayuy.overgrownvegetation.entity.g1.G1ClimberNavigation(this, level); // (b1) 壁の上の相手へ登る
        }
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        if (d == G1Developed.ZOMBIE) {
            nav.setCanOpenDoors(true);
        }
        if (d == G1Developed.BABY_ZOMBIE) {
            nav.setCanWalkOverFences(true);
        }
        return nav;
    }

    /** 種の技の Goal(確かめ用のコマンド・GameTest が冷却を飛ばす)。 */
    public List<G1TechniqueGoal> techniques() {
        return techniques == null ? List.of() : techniques;
    }

    // ------------------------------------------------------------------ 技の口
    /** 技と技の間が明けているか。 */
    public boolean techniqueReady() {
        return level().getGameTime() >= techniqueReadyAt;
    }

    /** 技を使い終えた: 次の技まで 10〜20 tick 空ける。 */
    public void markTechniqueUsed() {
        techniqueReadyAt = level().getGameTime() + TECHNIQUE_GAP_MIN + getRandom().nextInt(TECHNIQUE_GAP_MAX - TECHNIQUE_GAP_MIN + 1);
        strikeNotBefore = techniqueReadyAt; // 技の直後に 1 撃を重ねない(技の合間 10〜20 tick)
    }

    /**
     * 1 撃が終わった: 種の技は、次の 1 撃の {@value #TECHNIQUE_HEAD_START} tick 前から使える(きっかけの立った技が 1 撃より先に確かめられるように。
     * 間はどちらも 10〜20 tick の乱数なので、別々に数えると殴り合いの最中はたいてい 1 撃が先に始まり、霧・袋が出ない)。
     */
    @Override
    public void onStrikeEnd(int gap) {
        techniqueReadyAt = level().getGameTime() + Math.max(1, gap - TECHNIQUE_HEAD_START);
        struckSinceEngage = true; // クリーパー: 薙ぎ払いを 1 回振った(爆ぜの解禁)
    }

    @Override
    public boolean strikeReady() {
        return level().getGameTime() >= strikeNotBefore;
    }

    /** 技が終わったときに戻る行動の段(取り付いていれば取り付き、そうでなければ空き)。 */
    public int restingAction() {
        if (latch != null) return latchMove.id();
        return anchored() ? G1Move.ANCHORED_IDLE.id() : ACTION_NONE; // スケルトン: 据えている間は据えた姿勢へ戻る
    }

    @Override
    public boolean busy() {
        G1Move m = G1Move.byId(action());
        // 潜る・乗り越える・壁を登る(クモ)は歩きの見た目なので、追うのは止めない
        boolean inMove = m != null && m != G1Move.SQUEEZE_UNDER && m != G1Move.CLIMB_OVER && m != G1Move.CLIMB;
        return super.busy() || latch != null || inMove || isEvolving() || holdsBowRange();
    }

    @Nullable
    public G1Latch latch() {
        return latch;
    }

    @Nullable
    public G1Tether tether() {
        return tether;
    }

    public void startTether(G1Tether t) {
        if (tether != null) tether.cut(false);
        tether = t;
    }

    /** チビゾンビ: 殴られた・撃たれたので袋を噴く(読んだら消える)。 */
    public boolean takePuffTrigger() {
        boolean b = puffTrigger;
        puffTrigger = false;
        return b;
    }

    /** 村人: 体力が半分を切ったので霧を吹く(読んだら消える)。 */
    public boolean takeMistTrigger() {
        boolean b = mistTrigger;
        mistTrigger = false;
        return b;
    }

    /** 村人の (b): 次に胞子を配れる時刻。 */
    public long giftReadyAt() {
        return giftReadyAt;
    }

    public void setGiftReadyAt(long t) {
        giftReadyAt = t;
    }

    /** 昼か(村人の (b) の昼と夜。GameTest・確かめ用に上書きできる)。 */
    public boolean isDayFor() {
        return dayOverride != null ? dayOverride : level().isDay();
    }

    public void setDayOverride(@Nullable Boolean day) {
        dayOverride = day;
    }

    /** 行動の段を {@code ticks} だけ流してから空きへ戻す(眠りから起き上がるなど、Goal が終わった後の段)。 */
    public void holdAction(G1Move move, int ticks) {
        holdMove = move;
        holdLeft = ticks;
        setAction(move.id());
    }

    /** 眠って嚢を開いているか(被弾 2 倍)。 */
    public boolean isSleepingOpen() {
        int a = action();
        return a == G1Move.SLEEP_WINDUP.id() || a == G1Move.SLEEP.id();
    }

    /** 体力が減るほど殴られた回数(村人の巣作りが「殴られたら止まる」に使う)。 */
    public int hurtCount() {
        return hurtCount;
    }

    /** 豚に放り出された(着地して標的が近ければ取り付く)。 */
    public void setTossedAt(LivingEntity target) {
        tossedAt = target;
        tossedTicks = 0;
    }

    /** 確かめ用: 種の技の冷却と技の間を飛ばし、きっかけを立てる(コマンド・GameTest)。 */
    public void forceSkillsReady() {
        techniqueReadyAt = 0L;
        callReadyAt = 0L;
        puffTrigger = dev() == G1Developed.BABY_ZOMBIE;
        mistTrigger = dev() == G1Developed.VILLAGER;
        giftReadyAt = 0L;
        layReadyAt = 0L;
        fuseForced = dev() == G1Developed.CREEPER; // 爆ぜの解禁(交戦 6 秒 + 薙ぎ払い 1 回)を飛ばす
        podDropReadyAt = 0L;
        podRegrowLeft = 0; // 空の莢もすぐ満たす
        anchorReadyAt = 0L;
        if (perchGoal != null) perchGoal.resetCooldown(); // クモ: 待ち伏せの冷却
        teleportReadyAt = 0L; // エンダーマン: 転移の休み・根の技の間・土を拾う時計・目を合わせた印
        rootSkillNotBefore = 0L;
        soilTryAt = 0L;
        gazeUsed = false;
        for (G1TechniqueGoal g : techniques()) g.resetCooldown();
    }

    // ------------------------------------------------------------------ 能力の第 4 回(クモ)の口
    /** クモ: 壁に当たっていて登れるか(サーバー。バニラのクモと同じく、横にぶつかっている間)。 */
    @Override
    public boolean onClimbable() {
        return dev() == G1Developed.SPIDER ? climbing : super.onClimbable();
    }

    /** クモ (b1): 高い所に張り付いている間は落ちない(保存しない。待ち伏せの Goal が止まれば落ちる)。 */
    @Override
    public boolean isNoGravity() {
        return perched || super.isNoGravity();
    }

    /**
     * クモ (b1): 登る速さをバニラ(正味 約 0.118 ブロック / tick)の半分ほど({@value #CLIMB_SPEED})に抑える。
     * バニラは壁に当たっている間この戻り値の縦を 0.2 にし、その後で重力を引いて 0.98 を掛ける。同じ式で正味が {@value #CLIMB_SPEED} になる値にする。
     */
    @Override
    public net.minecraft.world.phys.Vec3 handleRelativeFrictionAndCalculateMovement(net.minecraft.world.phys.Vec3 deltaMovement, float friction) {
        net.minecraft.world.phys.Vec3 v = super.handleRelativeFrictionAndCalculateMovement(deltaMovement, friction);
        if (climbing && !perched) {
            double rise = getGravity() + CLIMB_SPEED / 0.98D;
            if (v.y > rise) v = new net.minecraft.world.phys.Vec3(v.x, rise, v.z);
        }
        return v;
    }

    /** クモ: 待ち伏せから落ちる・跳びつく間は落下のダメージを受けない(仕様 (b1)「落下のダメージは受けない」)。 */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        if (noFallTicks > 0) return false;
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    /** クモ: 毒が効かない(段階 0・バニラのクモと同じ)。 */
    @Override
    public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance effect) {
        if (dev() == G1Developed.SPIDER && effect.is(net.minecraft.world.effect.MobEffects.POISON)) return false;
        return super.canBeAffected(effect);
    }

    /** クモ: クモの巣でも根糸の巣でも遅くならない(仕様 3 節「段階 0 から引き継ぐ」)。 */
    @Override
    public void makeStuckInBlock(BlockState state, net.minecraft.world.phys.Vec3 motionMultiplier) {
        if (dev() == G1Developed.SPIDER
                && (state.is(net.minecraft.world.level.block.Blocks.COBWEB) || state.is(com.yayuy.overgrownvegetation.registry.ModG1.ROOTSILK_WEB.get()))) {
            return;
        }
        super.makeStuckInBlock(state, motionMultiplier);
    }

    /** クモ (b1): 高い所に張り付いているか。 */
    public boolean perched() {
        return perched;
    }

    public void setPerched(boolean perched) {
        this.perched = perched;
        if (perched) {
            setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            this.fallDistance = 0.0F;
        }
    }

    /** クモ: 壁に当たって登っているか(サーバー)。 */
    public boolean climbingWall() {
        return climbing && !onGround();
    }

    /** クモ: これから {@code ticks} の間、落下のダメージを受けない。 */
    public void noFallDamageFor(int ticks) {
        noFallTicks = Math.max(noFallTicks, ticks);
        this.fallDistance = 0.0F;
    }

    /** クモ (b1): GameTest・確かめ用に、待ち伏せの場所を決める(次の判定で、条件と冷却を飛ばしてそこへ向かう)。 */
    public void forcePerch(G1PerchGoal.Spot spot) {
        forcedPerch = spot;
    }

    @Nullable
    public G1PerchGoal.Spot takeForcedPerch() {
        G1PerchGoal.Spot s = forcedPerch;
        forcedPerch = null;
        return s;
    }

    /** クモ (c): 根糸を吐いた(GameTest・確かめ用に数える)。 */
    public void countRootsilkShot(G1RootsilkBolt bolt) {
        rootsilkShots++;
        lastRootsilk = bolt;
    }

    public int rootsilkShots() {
        return rootsilkShots;
    }

    @Nullable
    public G1RootsilkBolt lastRootsilk() {
        return lastRootsilk;
    }

    private void tickSpider() {
        if (noFallTicks > 0) noFallTicks--;
        int a = action();
        // 壁に当たっている間は登れる(バニラのクモと同じ)。待ち伏せから落ちている間は、壁に擦っても登り直さない
        climbing = this.horizontalCollision && a != G1Move.DROP.id() && !perched;
        if (climbing && !onGround()) {
            climbGrace = CLIMB_GRACE;
            noFallTicks = Math.max(noFallTicks, 40); // 登っている途中で壁から離れて落ちても傷まない(標的を諦めて落ちるときなど)
        } else if (climbGrace > 0) {
            climbGrace = onGround() ? 0 : climbGrace - 1;
        }
        if (a == ACTION_NONE && climbGrace > 0) {
            setAction(G1Move.CLIMB.id());
        } else if (a == G1Move.CLIMB.id() && climbGrace <= 0) {
            setAction(ACTION_NONE);
        }
        if (action() == G1Move.CLIMB.id()) {
            faceWall();
        }
    }

    /** クモ: 登っている間、体を当たっている壁へ向ける(アニメ climb は胴を壁に平らに倒す)。今の向きにいちばん近い、当たっている側。 */
    private void faceWall() {
        float best = Float.NaN, bestDiff = 360.0F;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (level().noCollision(this, getBoundingBox().move(d.getStepX() * 0.1D, 0.0D, d.getStepZ() * 0.1D))) continue;
            float diff = Math.abs(net.minecraft.util.Mth.wrapDegrees(d.toYRot() - getYRot()));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = d.toYRot();
            }
        }
        if (!Float.isNaN(best)) {
            float yaw = net.minecraft.util.Mth.approachDegrees(getYRot(), best, 20.0F);
            setYRot(yaw);
            setYBodyRot(yaw);
            this.yHeadRot = yaw;
        }
    }

    // ------------------------------------------------------------------ 能力の第 3 回(クリーパー・スケルトン)の口
    /** クリーパー: 背の莢が満ちているか(サーバー)。空の間は爆ぜられず、莢も落とさない。 */
    public boolean podsFull() {
        return podRegrowLeft <= 0;
    }

    /** クリーパー: 莢の満ち具合(0〜100。同期)。 */
    public int pods() {
        return this.entityData.get(PODS);
    }

    /** クライアント: 見せる莢の満ち具合(0〜1)。 */
    public float podsShown(float partialTick) {
        return net.minecraft.util.Mth.lerp(partialTick, podsShownO, podsShown);
    }

    /** クリーパー (b): 爆ぜた。莢が空になり、{@value #POD_REGROW_TICKS} tick で育ち直す。交戦の数え(解禁)もやり直す。 */
    public void burstPods() {
        podRegrowLeft = POD_REGROW_TICKS;
        engagedTicks = 0;
        struckSinceEngage = false;
    }

    /** クリーパー: 莢が育ち直すまでの残りを決める(保存の読み込み・GameTest・確かめ用)。0 で満ちる。 */
    public void setPodRegrowLeft(int ticks) {
        podRegrowLeft = Math.max(0, ticks);
    }

    /** クリーパー (b): 爆ぜが解禁されているか(交戦 6 秒 + 薙ぎ払い 1 回の後【仮】。確かめ用のコマンドは飛ばす)。 */
    public boolean fuseUnlocked() {
        return fuseForced || (engagedTicks >= FUSE_UNLOCK_ENGAGED && struckSinceEngage);
    }

    /** クリーパー (c): 次に莢を落とせる時刻(-1 = まだ決めていない)。 */
    public long podDropReadyAt() {
        return podDropReadyAt;
    }

    public void setPodDropReadyAt(long t) {
        podDropReadyAt = t;
    }

    private void tickCreeper() {
        if (podRegrowLeft > 0) podRegrowLeft--;
        // 見た目の満ち具合。爆ぜのアニメの間は満ちたまま送る(しぼむのはアニメが見せる。終わってから 0 を送り、手続きの縮みに渡す)
        if (action() != G1Move.BURST.id()) {
            this.entityData.set(PODS, podRegrowLeft <= 0 ? 100 : (int) (100.0F * (1.0F - (float) podRegrowLeft / POD_REGROW_TICKS)));
        }
        if (podDropReadyAt < 0L) {
            podDropReadyAt = level().getGameTime() + G1DropPodGoal.FIRST_MIN + getRandom().nextInt(G1DropPodGoal.FIRST_MAX - G1DropPodGoal.FIRST_MIN + 1);
        }
        // 交戦の数え
        LivingEntity t = getTarget();
        if (t != null && t.isAlive() && distanceTo(t) <= ENGAGE_RANGE) {
            engagedTicks++;
            disengagedTicks = 0;
        } else if (++disengagedTicks > DISENGAGE_TICKS) {
            engagedTicks = 0;
            struckSinceEngage = false;
        }
    }

    /** スケルトン: 根を張って据えているか(同期)。 */
    public boolean anchored() {
        return this.entityData.get(ANCHORED);
    }

    /** スケルトン (c): 次に据えられるか(根を抜いてからの冷却)。 */
    public boolean anchorReady() {
        return level().getGameTime() >= anchorReadyAt;
    }

    /** スケルトン (c): 据える最小の距離(GameTest が狭いテスト場のために縮める)。 */
    public double anchorMinRange() {
        return anchorMinRangeOverride != null ? anchorMinRangeOverride : G1RootAnchorGoal.MIN_RANGE;
    }

    public void setAnchorMinRangeOverride(@Nullable Double range) {
        anchorMinRangeOverride = range;
    }

    /** スケルトン (c): 据わる(サーバー)。{@code ticks} の間、動かず押し返されない。 */
    public void startAnchor(int ticks) {
        anchorLeft = ticks;
        this.entityData.set(ANCHORED, true);
        AttributeInstance kb = getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null && !kb.hasModifier(ANCHOR_KNOCKBACK_ID)) {
            kb.addTransientModifier(new AttributeModifier(ANCHOR_KNOCKBACK_ID, 1.0D, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** スケルトン (c): 据えている残りを決める(GameTest・確かめ用)。 */
    public void setAnchorLeft(int ticks) {
        anchorLeft = ticks;
    }

    private void endAnchor() {
        this.entityData.set(ANCHORED, false);
        AttributeInstance kb = getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.removeModifier(ANCHOR_KNOCKBACK_ID);
        anchorReadyAt = level().getGameTime() + G1RootAnchorGoal.COOLDOWN;
        holdAction(G1Move.ROOT_RELEASE, G1RootAnchorGoal.RELEASE_TICKS);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 0.7F, 0.8F);
    }

    /**
     * スケルトン: 弓の間合い(3〜16 ブロックで見えている)に標的がいる間は、近づかずに立って射る(仕様「遠くでは陣取って撃つ・近づけば薙ぐ」)。
     * {@link #busy} に足すので、3 段の 1 撃は追わない。間合いの外・見えないときは追う。
     */
    private boolean holdsBowRange() {
        if (dev() != G1Developed.SKELETON || level().isClientSide()) return false;
        LivingEntity t = getTarget();
        return t != null && t.isAlive() && G1BowShotGoal.inBowRange(this, t);
    }

    private void tickSkeleton() {
        LivingEntity t = getTarget();
        if (anchored()) {
            // 据えている間は動かない(標的がいなくなってうろつきの Goal が走っても)
            getNavigation().stop();
            setDeltaMovement(0.0D, getDeltaMovement().y, 0.0D);
            if (action() == G1Move.ANCHORED_IDLE.id()) {
                if (--anchorLeft <= 0 || !isAlive()) {
                    endAnchor(); // 技(射る)の途中なら、終わって据えた姿勢へ戻ってから抜く
                } else if (t != null && t.isAlive()) {
                    turnToward(t, ANCHORED_IDLE_TURN);
                }
            } else {
                anchorLeft--;
            }
        } else if (action() == ACTION_NONE && t != null && t.isAlive() && G1BowShotGoal.inBowRange(this, t)) {
            // 弓の間合い: 立ち止まって相手を向く(次の射撃まで)
            getNavigation().stop();
            turnToward(t, 30.0F);
        }
    }

    private void turnToward(LivingEntity t, float rate) {
        float want = (float) (net.minecraft.util.Mth.atan2(t.getZ() - getZ(), t.getX() - getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90.0F;
        float yaw = net.minecraft.util.Mth.approachDegrees(getYRot(), want, rate);
        setYRot(yaw);
        setYBodyRot(yaw);
        this.yHeadRot = yaw;
    }

    /** スケルトン (b): 弾を射た(GameTest・確かめ用に数える)。 */
    public void countBoltShot(G1SporeBolt bolt) {
        boltsShot++;
        lastBolt = bolt;
    }

    public int boltsShot() {
        return boltsShot;
    }

    @Nullable
    public G1SporeBolt lastBolt() {
        return lastBolt;
    }

    // ------------------------------------------------------------------ 能力の第 4 回(エンダーマン)の口
    /** エンダーマン (b): 抱えている汚染した土(無ければ null。同期)。 */
    @Nullable
    public BlockState carried() {
        return this.entityData.get(CARRIED).orElse(null);
    }

    public void setCarried(@Nullable BlockState state) {
        this.entityData.set(CARRIED, java.util.Optional.ofNullable(state));
        carriedTicks = 0;
    }

    /** エンダーマン (b): 土を抱えてからの tick。 */
    public int carriedTicks() {
        return carriedTicks;
    }

    /** エンダーマン (b): 土を置きに渡れなかった(行き先が無い)。{@code ticks} 待ってからまた試す(溜めだけを繰り返さないように)。 */
    public void delayPlant(int ticks) {
        carriedTicks = G1TeleportGoal.CARRY_BEFORE_PLANT - ticks;
    }

    /** エンダーマン (b): 転移の休みが明けているか(転移・目を合わせる・掴みで共通の 6 秒)。 */
    public boolean teleportReady() {
        return level().getGameTime() >= teleportReadyAt;
    }

    /** エンダーマン (b): 転移を使った(今から {@code ticks} 休む)。 */
    public void markTeleportUsed(int ticks) {
        teleportReadyAt = level().getGameTime() + ticks;
    }

    /** エンダーマン (b): 被弾したので転移したい(読んでも消えない)。 */
    public boolean peekTeleportTrigger() {
        return level().getGameTime() <= teleportTriggerUntil;
    }

    public void takeTeleportTrigger() {
        teleportTriggerUntil = -1L;
    }

    /** エンダーマン (b): 目が合った相手(まだ転移していなければ)。 */
    @Nullable
    public LivingEntity gazer() {
        return gazer != null && gazer.isAlive() ? gazer : null;
    }

    /** 目が合った相手を受け取り、この出会いではもう繰り返さない(標的を見失うまで【決定 11-09: 1 回だけ】)。 */
    @Nullable
    public LivingEntity takeGazer() {
        LivingEntity g = gazer();
        gazer = null;
        gazeUsed = true;
        return g;
    }

    /** 目が合ったことにする(GameTest・確かめ用。この出会いでもう使っていれば何もしない)。 */
    public boolean gazeFrom(LivingEntity looker) {
        if (gazeUsed) return false;
        gazer = looker;
        return true;
    }

    /**
     * プレイヤーがこのエンダーマンを見ているか(バニラの {@code EnderMan#isLookingAtMe} と同じ判定: 視線の向きと自分への向きがほぼ同じで、見通せる)。
     * 創造・観戦と、くり抜かれたカボチャをかぶったプレイヤーは数えない。
     */
    public boolean lookedAtBy(net.minecraft.world.entity.player.Player player) {
        if (player.isCreative() || player.isSpectator() || !player.isAlive()) return false;
        if (player.getInventory().armor.get(3).is(net.minecraft.world.level.block.Blocks.CARVED_PUMPKIN.asItem())) return false;
        net.minecraft.world.phys.Vec3 view = player.getViewVector(1.0F).normalize();
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(getX() - player.getX(), getEyeY() - player.getEyeY(), getZ() - player.getZ());
        double dist = to.length();
        if (dist < 1.0E-4D) return false;
        return view.dot(to.normalize()) > 1.0D - 0.025D / dist && player.hasLineOfSight(this);
    }

    /** エンダーマン (b): 「標的が遠い」とみなす距離(GameTest が狭いテスト場のために縮める)。 */
    public double farRange() {
        return farRangeOverride != null ? farRangeOverride : G1TeleportGoal.FAR_RANGE;
    }

    public void setFarRangeOverride(@Nullable Double range) {
        farRangeOverride = range;
    }

    /** エンダーマン (b): 転移の距離(GameTest が場の外へ出ないように縮める)。 */
    public double teleportMin() {
        return teleportRangeOverride != null ? teleportRangeOverride[0] : G1Blink.MIN;
    }

    public double teleportMax() {
        return teleportRangeOverride != null ? teleportRangeOverride[1] : G1Blink.MAX;
    }

    public void setTeleportRangeOverride(double min, double max) {
        teleportRangeOverride = new double[]{min, max};
    }

    /** エンダーマン (b): 渡った(GameTest・確かめ用に数える)。 */
    public void countTeleport(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        teleports++;
        lastTeleportFrom = from;
    }

    public int teleports() {
        return teleports;
    }

    @Nullable
    public net.minecraft.world.phys.Vec3 lastTeleportFrom() {
        return lastTeleportFrom;
    }

    /** エンダーマン (c): 根の技((c-1) 掴み・(c-2) 叩きつけ)を使えるか(片方を使ったら、もう片方は 4 秒待つ)。 */
    public boolean rootSkillReady() {
        return level().getGameTime() >= rootSkillNotBefore;
    }

    public void lockRootSkills(int ticks) {
        rootSkillNotBefore = Math.max(rootSkillNotBefore, level().getGameTime() + ticks);
    }

    /** エンダーマン (c-1): 抱えている相手を決め、その相手から受けたダメージを数え直す(null で数えない)。 */
    public void resetGrabDamage(@Nullable LivingEntity victim) {
        grabVictim = victim;
        grabDamage = 0.0F;
    }

    /** エンダーマン (c-1): 抱えている相手から受けたダメージ(一定で放す)。 */
    public float grabDamage() {
        return grabDamage;
    }

    public void countGrabCarried(LivingEntity victim) {
        grabsCarried++;
    }

    /** エンダーマン (c-1): 相手を連れて転移した回数(GameTest・確かめ用)。 */
    public int grabsCarried() {
        return grabsCarried;
    }

    public void countSlam(int spikes, int hits) {
        slams++;
        lastSlamSpikes = spikes;
        lastSlamHits = hits;
    }

    /** エンダーマン (c-2): 叩きつけた回数・最後の根の数・当たった数(GameTest・確かめ用)。 */
    public int slams() {
        return slams;
    }

    public int lastSlamSpikes() {
        return lastSlamSpikes;
    }

    public int lastSlamHits() {
        return lastSlamHits;
    }

    /** エンダーマン (b): 置いた土の数と最後に置いた所(GameTest・確かめ用)。 */
    public int soilPlanted() {
        return soilPlanted;
    }

    @Nullable
    public BlockPos lastPlanted() {
        return lastPlanted;
    }

    /**
     * 抱えてよい汚染した土か(過繁茂の草ブロック・過繁茂の土・泥炭と、汚染した土の仲間。立方体で、中身の無いブロック)。
     * 仕様「拾うのは汚染したブロックだけ」【案】。覆いの記録で覆われただけのマス(元のブロックのまま)は拾わない。
     */
    public static boolean isSoil(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState s) {
        if (!s.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED) || s.hasBlockEntity() || !s.isCollisionShapeFullBlock(level, pos)) return false;
        return s.is(BlockTags.DIRT) || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_DIRT.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.PEAT.get());
    }

    /**
     * エンダーマン (b)「汚染した土を抱える」: 周り(水平 ±{@value #SOIL_REACH}・上下 ±1)から、上が空いている汚染した土を 1 つ拾う(元の「ブロックを持つ」)。
     * 拾ったマスは空気になる(バニラのエンダーマンと同じ【仮】)。汚染が止まっている・胞子除去装置の中では拾わない。足元のブロックは拾わない。
     */
    public boolean tryPickSoil() {
        if (carried() != null || !(level() instanceof ServerLevel level) || com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) return false;
        BlockPos feet = blockPosition();
        for (int i = 0; i < SOIL_TRIES; i++) {
            BlockPos p = feet.offset(getRandom().nextInt(SOIL_REACH * 2 + 1) - SOIL_REACH, getRandom().nextInt(3) - 1,
                    getRandom().nextInt(SOIL_REACH * 2 + 1) - SOIL_REACH);
            if (p.equals(feet.below()) || !level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (!isSoil(level, p, s)) continue;
            BlockPos above = p.above();
            if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) continue;
            if (com.yayuy.overgrownvegetation.spore.SporeDeviceData.inhibits(level, p, com.yayuy.overgrownvegetation.spore.SporeDeviceData.Inhibit.CORRUPTION)) continue;
            level.removeBlock(p, false);
            setCarried(s);
            level.playSound(null, p, s.getSoundType(level, p, this).getBreakSound(), SoundSource.HOSTILE, 0.8F, 0.7F);
            return true;
        }
        return false;
    }

    /**
     * エンダーマン (b)「転移で着いた所に置く」: 抱えた土を、体の前かそばの「空いていて下が固いマス」に置く。置いたマスは汚染のきっかけになる
     * (その場に下草が芽生える・升の浮遊 +{@value #SOIL_AIR_PERCENT}%【仮】。場所を移しただけなので、汚染の数(フェーズの点)には数えない)。置けなければ抱えたまま(次の転移でまた試す)。
     */
    public boolean plantSoil() {
        BlockState state = carried();
        if (state == null || !(level() instanceof ServerLevel level)) return false;
        carriedTicks = 0;
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) return false;
        net.minecraft.core.Direction front = net.minecraft.core.Direction.fromYRot(getYRot());
        BlockPos feet = blockPosition();
        BlockPos[] spots = {feet.relative(front), feet.relative(front.getClockWise()), feet.relative(front.getCounterClockWise()),
                feet.relative(front.getOpposite())};
        for (BlockPos p : spots) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos at = p.above(dy);
                BlockState old = level.getBlockState(at);
                BlockPos below = at.below();
                if (!(old.isAir() || old.canBeReplaced()) || !old.getFluidState().isEmpty()) continue;
                if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) continue;
                if (!level.getEntities(this, new net.minecraft.world.phys.AABB(at)).isEmpty()) continue;
                if (com.yayuy.overgrownvegetation.spore.SporeDeviceData.inhibits(level, at, com.yayuy.overgrownvegetation.spore.SporeDeviceData.Inhibit.CORRUPTION)) continue;
                level.setBlock(at, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
                setCarried(null);
                level.playSound(null, at, state.getSoundType(level, at, this).getPlaceSound(), SoundSource.HOSTILE, 0.9F, 0.7F);
                com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter.maybeConvert(level, new net.minecraft.world.level.ChunkPos(at));
                com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, at, state,
                        com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.growthChance.get());
                if (level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER) {
                    SporeFieldServer.emitPoint(level, at, SOIL_AIR_PERCENT / 100.0D * DesignData.sporeField().fullAt());
                }
                soilPlanted++;
                lastPlanted = at;
                return true;
            }
        }
        return false;
    }

    private void tickEnderman() {
        long now = level().getGameTime();
        if (carried() != null) {
            carriedTicks++;
        } else if (action() == ACTION_NONE) {
            // (b) 汚染した土を拾う: 近くに獲物がいないとき、ときどき
            if (soilTryAt < 0L) soilTryAt = now + SOIL_INTERVAL_MIN + getRandom().nextInt(SOIL_INTERVAL_MAX - SOIL_INTERVAL_MIN + 1);
            if (now >= soilTryAt) {
                soilTryAt = now + SOIL_INTERVAL_MIN + getRandom().nextInt(SOIL_INTERVAL_MAX - SOIL_INTERVAL_MIN + 1);
                if (calm(SOIL_CALM_RADIUS)) tryPickSoil();
            }
        }
        // (b) 目を合わせると: プレイヤーが見ていたら、1 回だけ溜めて転移で近づく
        // 転移の休みの間は見ない(休みの間に見られた分を覚えておくと、目をそらした後で渡ってしまう)
        if (!gazeUsed && gazer == null && this.tickCount % 4 == 0 && action() == ACTION_NONE && teleportReady()) {
            for (net.minecraft.world.entity.player.Player p : level().players()) {
                if (distanceToSqr(p) <= GAZE_RANGE * GAZE_RANGE && lookedAtBy(p)) {
                    gazer = p;
                    break;
                }
            }
        }
        // 標的を見失ってしばらくしたら、次にまた目が合ったときに繰り返せる
        if (getTarget() == null) {
            if (gazeUsed && ++noTargetTicks > GAZE_RESET_TICKS) {
                gazeUsed = false;
                noTargetTicks = 0;
            }
        } else {
            noTargetTicks = 0;
        }
    }

    // ------------------------------------------------------------------ 能力の第 2 回(牛・羊・鶏)の口
    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FLUFF, FLUFF_MAX);
        builder.define(PODS, 100);
        builder.define(ANCHORED, false);
        builder.define(CARRIED, java.util.Optional.empty());
    }

    /** 羊の綿毛の段(0〜{@link #FLUFF_MAX})。 */
    public int fluff() {
        return this.entityData.get(FLUFF);
    }

    public void setFluff(int fluff) {
        this.entityData.set(FLUFF, net.minecraft.util.Mth.clamp(fluff, 0, FLUFF_MAX));
    }

    /**
     * 落ち着いてする技(草を食べる・綿毛を放つ・卵を産む)の「近くに獲物がいない」。G1 は 35 ブロック以内の生き物を必ず標的にするので、
     * 「標的がいない」ではなく「{@code radius} ブロック以内に獲物がいない」で見る(村人の眠りと同じ考え)。
     */
    public boolean calm(double radius) {
        return !G1SleepGoal.preyNear(this, calmRadiusOverride != null ? calmRadiusOverride : radius);
    }

    public void setCalmRadiusOverride(@Nullable Double radius) {
        calmRadiusOverride = radius;
    }

    /** 牛 (c): 歩く発生源(放出した回数を GameTest が読む)。 */
    public G1WalkingSource walkingSource() {
        return walkingSource;
    }

    /** 鶏 (b): 次に卵を産める時刻。 */
    public long layReadyAt() {
        return layReadyAt;
    }

    public void setLayReadyAt(long t) {
        layReadyAt = t;
    }

    /** 鶏 (b): 次の産卵まで 3〜5 分【仮】空ける。 */
    public void scheduleNextLay() {
        layReadyAt = level().getGameTime() + G1LayEggGoal.INTERVAL_MIN + getRandom().nextInt(G1LayEggGoal.INTERVAL_MAX - G1LayEggGoal.INTERVAL_MIN + 1);
    }

    public void countEggLaid() {
        eggsLaid++;
    }

    /** 鶏 (b): 産んだ卵の数(GameTest・確かめ用)。 */
    public int eggsLaid() {
        return eggsLaid;
    }

    /** 羊 (c): 最後に放った綿毛の着地点(まだ放っていなければ null)。 */
    @Nullable
    public net.minecraft.world.phys.Vec3 lastFluffLanding() {
        return lastFluffLanding;
    }

    public void setLastFluffLanding(net.minecraft.world.phys.Vec3 to) {
        lastFluffLanding = to;
    }

    /**
     * 羊の (b)「鋏で刈れる」(仕様 Vault「G1 羊(1 段目)」3 節【決定 11-09: 段階 0 は刈れないが 1 段目は刈れる】)。
     * 綿毛が残っていれば刈れる: 綿毛が 0 段になり(育つまで (c) を使えない)、覆われた羊毛が 1〜2 落ち、刈った瞬間に胞子が舞う
     * (周り {@value #SHEAR_RADIUS} ブロックへ暴露量 +{@value #SHEAR_DOSE}【仮】。防護マスクで防げる)。
     */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
        if (dev() == G1Developed.SHEEP && isAlive() && fluff() > 0 && stack.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHEARS_HARVEST)) {
            if (level() instanceof ServerLevel) {
                shear();
                gameEvent(net.minecraft.world.level.gameevent.GameEvent.SHEAR, player);
                stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(level().isClientSide());
        }
        return super.mobInteract(player, hand);
    }

    /** 羊を刈る(サーバー)。綿毛が無ければ何もしない。 */
    public void shear() {
        if (!(level() instanceof ServerLevel level) || fluff() <= 0) return;
        setFluff(0);
        int wool = 1 + getRandom().nextInt(2);
        for (int i = 0; i < wool; i++) {
            net.minecraft.world.entity.item.ItemEntity drop = spawnAtLocation(new net.minecraft.world.item.ItemStack(ModItems.OVERGROWN_WOOL.get()), 1.0F);
            if (drop != null) {
                drop.setDeltaMovement(drop.getDeltaMovement().add((getRandom().nextFloat() - getRandom().nextFloat()) * 0.1F,
                        getRandom().nextFloat() * 0.05F, (getRandom().nextFloat() - getRandom().nextFloat()) * 0.1F));
            }
        }
        // 刈った瞬間に胞子が舞う
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(SHEAR_RADIUS))) {
            if (e != this && distanceTo(e) <= SHEAR_RADIUS + e.getBbWidth() * 0.5F && !SporeExposureTracker.isPlantSide(e)) {
                com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeDose.add(e, SHEAR_DOSE);
            }
        }
        level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), getX(), getY() + getBbHeight() * 0.7D, getZ(), 5, 0.5D, 0.3D, 0.5D, 0.01D);
        level.sendParticles(com.yayuy.overgrownvegetation.registry.ModG1.FLUFF.get(), getX(), getY() + getBbHeight() * 0.8D, getZ(), 14, 0.5D, 0.3D, 0.5D, 0.04D);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SHEEP_SHEAR, SoundSource.HOSTILE, 1.0F, 0.8F);
    }

    // ------------------------------------------------------------------ (a) 種ごとの 1 撃の違い(2026-11-13 ユーザー「入れよう」)
    // 間合い = 間合いの箱を広げる / 踏み込み = 遠くから振り出し、実行の段でまっすぐ前へ出る / 押し返し = 当たった後に足す。
    // 数は種の表 G1Developed の reach・knock・lunge(各種の仕様書の (a) 欄、【仮】)。
    /** 踏み込みで越えられる段(ブロック)。標的の足もとがこれより上下にずれていたら遠くからは振り出さない(歩いて寄る)。【仮】 */
    static final double LUNGE_STEP_MAX = 0.6D;
    /** 踏み込みの先の足もとを確かめる深さ(足の高さのマスから下へ数えるブロック数)。2 = 1 ブロックの段差までは降りる。それより深い穴・崖の先へは踏み込まない。【仮】 */
    static final int LUNGE_DROP_MAX = 2;
    /** 踏み込みの 1 tick を刻む数(届く所で止まり、相手を通り抜けないように)。 */
    private static final int LUNGE_SUBSTEPS = 4;

    /** 直前の 1 撃で踏み込んだ長さ(ブロック。テスト・調べ用)。 */
    private double lungeMoved;
    /** 直前に当たった 1 撃が相手に与えた速さ(押し返しを足した直後。テスト・調べ用)。 */
    @Nullable
    private net.minecraft.world.phys.Vec3 lastStrikePush;
    private int strikeHits;

    public double lungeMoved() {
        return lungeMoved;
    }

    @Nullable
    public net.minecraft.world.phys.Vec3 lastStrikePush() {
        return lastStrikePush;
    }

    /** 当たった 1 撃の数(盾で防がれた分は数えない)。 */
    public int strikeHits() {
        return strikeHits;
    }

    /** (a) 間合い: 種ごとに間合いの箱を水平に広げる(振り出す距離と当たりの判定の両方に効く)。 */
    @Override
    protected net.minecraft.world.phys.AABB getAttackBoundingBox() {
        net.minecraft.world.phys.AABB box = super.getAttackBoundingBox();
        double reach = dev().reach;
        return reach > 0.0D ? box.inflate(reach, 0.0D, reach) : box;
    }

    /**
     * (a) 踏み込み: 牛・羊は、踏み込みで届く所(間合い + 踏み込み)に相手がいれば振り出す。足もとの高さが合い、
     * そこまでの道が空いていて(壁・柵が無い)、穴・崖が無いときだけ。そうでなければ素の間合いまで歩いて寄る。
     */
    @Override
    public boolean strikeInRange(LivingEntity target) {
        if (isWithinMeleeAttackRange(target)) return true;
        double lunge = dev().lunge;
        if (lunge <= 0.0D) return false;
        if (!getAttackBoundingBox().inflate(lunge, 0.0D, lunge).intersects(target.getHitbox())) return false;
        if (Math.abs(target.getY() - getY()) > LUNGE_STEP_MAX) return false;
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-4D) return true;
        return lungeClear(dx / d, dz / d, Math.min(lunge, d));
    }

    /** (a) 牛・羊・鶏は、届く所では寄らずに次の 1 撃を待つ(踏み込み・長い間合いを 2 撃目からも見せる)。 */
    @Override
    public boolean strikeHoldsDistance() {
        return dev().holdsDistance();
    }

    /** 向き (ux, uz) へ {@code dist} ブロック、体が通れて足もとがあるか。 */
    private boolean lungeClear(double ux, double uz, double dist) {
        net.minecraft.world.phys.AABB body = getBoundingBox();
        // 低い段(LUNGE_STEP_MAX まで)は越えられるので、箱の下を切って確かめる
        net.minecraft.world.phys.AABB sweep = body.setMinY(body.minY + LUNGE_STEP_MAX).expandTowards(ux * dist, 0.0D, uz * dist);
        if (!level().noCollision(this, sweep)) return false;
        for (double at = 0.5D; at < dist + 0.5D; at += 0.5D) {
            double a = Math.min(at, dist);
            if (!groundAt(getX() + ux * a, getZ() + uz * a)) return false;
        }
        return true;
    }

    /** (x, z) の足もと(今の高さから {@value #LUNGE_DROP_MAX} ブロック下まで)に乗れるブロックがあるか。 */
    private boolean groundAt(double x, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = net.minecraft.util.Mth.floor(getY() + 0.2D);
        for (int i = 0; i <= LUNGE_DROP_MAX; i++) {
            pos.set(net.minecraft.util.Mth.floor(x), top - i, net.minecraft.util.Mth.floor(z));
            if (!level().getBlockState(pos).getCollisionShape(level(), pos).isEmpty()) return true;
        }
        return false;
    }

    /**
     * (a) 踏み込み: 実行の段({@value com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal#STRIKE} tick)で、止めた向きへまっすぐ
     * {@code lunge} ブロックまで出る(曲がらない = 横へ出ればよけられる)。相手が間合いに入った所で止まる。壁・柵には当たって止まり、
     * 穴・崖の手前で止まる。当たりの判定は、最後の tick の踏み込みの後。
     */
    @Override
    public void onStrikeTick(int tick, float yaw, @Nullable LivingEntity target) {
        double lunge = dev().lunge;
        if (lunge <= 0.0D) return;
        if (tick == 1) lungeMoved = 0.0D;
        float rad = yaw * net.minecraft.util.Mth.DEG_TO_RAD;
        double ux = -net.minecraft.util.Mth.sin(rad);
        double uz = net.minecraft.util.Mth.cos(rad);
        double sub = lunge / com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal.STRIKE / LUNGE_SUBSTEPS;
        for (int i = 0; i < LUNGE_SUBSTEPS; i++) {
            if (lungeMoved >= lunge - 1.0E-4D) return;
            if (target != null && isWithinMeleeAttackRange(target)) return; // 届く所まで来た(通り抜けない)
            if (!groundAt(getX() + ux * sub, getZ() + uz * sub)) return;
            double x0 = getX();
            double z0 = getZ();
            move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(ux * sub, 0.0D, uz * sub));
            double moved = Math.sqrt((getX() - x0) * (getX() - x0) + (getZ() - z0) * (getZ() - z0));
            lungeMoved += moved;
            if (moved < sub * 0.5D) return; // 壁・柵に当たった
        }
    }

    /**
     * (a) 押し返し: 1 撃が当たった相手だけを押す(跳びつき・待ち伏せの着地・取り付きには効かせない。盾で防がれたら呼ばれない)。
     * 強さの単位はバニラの属性 ATTACK_KNOCKBACK と同じ(当たりの素の押し返し 0.4 に、向いた方へ {@code knock × 0.5} を重ねる
     * = {@code Mob#doHurtTarget} と同じ式)。エンダーマンは横(体の右)へ、クモは手前へ引き寄せる(素の押し返しを打ち消して置き換える)。
     */
    private void applyStrikePush(LivingEntity target) {
        double knock = dev().knock;
        if (knock == 0.0D) {
            lastStrikePush = target.getDeltaMovement();
            return;
        }
        float rad = getYRot() * net.minecraft.util.Mth.DEG_TO_RAD;
        if (knock < 0.0D) {
            double dx = getX() - target.getX();
            double dz = getZ() - target.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 1.0E-4D) {
                double pull = -knock * 0.5D * Math.max(0.0D, 1.0D - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                net.minecraft.world.phys.Vec3 v = target.getDeltaMovement();
                target.setDeltaMovement(dx / d * pull, v.y, dz / d * pull);
                target.hasImpulse = true;
            }
        } else if (dev().knockSideways()) {
            // 体の右へ(knockback は渡した向きの逆へ押すので、左向きを渡す)
            target.knockback(knock * 0.5D, net.minecraft.util.Mth.cos(rad), net.minecraft.util.Mth.sin(rad));
        } else {
            target.knockback(knock * 0.5D, net.minecraft.util.Mth.sin(rad), -net.minecraft.util.Mth.cos(rad));
        }
        target.hurtMarked = true; // プレイヤーへ速さを送る
        lastStrikePush = target.getDeltaMovement();
    }

    // ------------------------------------------------------------------ チビゾンビの取り付き((a) が当たったら)
    @Override
    public void onStrikeHit(LivingEntity target) {
        strikeHits++;
        if (!target.isAlive()) return;
        applyStrikePush(target);
        if (dev() == G1Developed.BABY_ZOMBIE) {
            startLatch(target);
        } else if (dev() == G1Developed.PIG) {
            startBiteHold(target);
        }
    }

    public void startLatch(LivingEntity target) {
        if (latch != null) latch.release(false);
        latch = new G1Latch(this, target, LATCH_TICKS, LATCH_BITE_INTERVAL, LATCH_BITE_DAMAGE, LATCH_BITE_DOSE, LATCH_SLOW, true);
        latchMove = G1Move.LATCH;
        setAction(G1Move.LATCH.id());
    }

    /**
     * 豚の (a) 食らいつき(2026-11-13 ユーザー「入れようか」)。3 段の 1 撃が当たると、口を離さずにその場で噛み続ける
     * ({@link G1Latch} の follow でない形)。噛んでいる間は標的が遅くなり、暴露量が積まれる(傷は最初の 1 撃だけ)。
     * 標的が歩けば引きずられる(離さない)。標的に 1 回殴られると振りほどかれてよろける。しゃがんでも外れない。
     */
    public void startBiteHold(LivingEntity target) {
        if (latch != null) latch.release(false);
        latch = new G1Latch(this, target, BITE_HOLD_TICKS, BITE_DOSE_INTERVAL, 0.0F, BITE_DOSE, BITE_SLOW, false)
                .dragAlong(BITE_DRAG_GAP).noCrouchRelease();
        latchMove = G1Move.BITE_HOLD;
        setAction(G1Move.BITE_HOLD.id());
    }

    private void endLatch() {
        G1Latch ended = latch;
        latch = null;
        if (action() == latchMove.id()) setAction(ACTION_NONE);
        if (latchMove == G1Move.BITE_HOLD && ended != null && isAlive()) {
            // 口を離す = 1 撃の隙(attack_recover)。振りほどかれたときはよろけを足す。次の 1 撃・技は隙の後に 10〜20 tick 空ける
            // (1 撃の「次までの間」は噛んでいる間に数え終わっているので、ここで待たせないと離した途端にまた噛む)
            holdAction(G1Move.BITE_RECOVER, BITE_RECOVER_TICKS);
            if (ended.knocked()) stun(BITE_SHAKEN_STUN);
            markTechniqueUsed();
            techniqueReadyAt += BITE_RECOVER_TICKS;
            strikeNotBefore = techniqueReadyAt;
        }
    }

    // ------------------------------------------------------------------ evolve_in
    /** evolve_in を流し始める(サーバー)。長さはアニメの長さ(チビ・豚 2.5 秒、ほか 3 秒)。 */
    public void startEvolveIn() {
        evolveLeft = (dev() == G1Developed.BABY_ZOMBIE || dev() == G1Developed.PIG) ? 50 : 60;
        getNavigation().stop();
        setAction(ACTION_EVOLVE);
    }

    public boolean isEvolving() {
        return action() == ACTION_EVOLVE;
    }

    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isEvolving();
    }

    // ------------------------------------------------------------------ tick
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            if (faceYawTicks > 0) {
                faceYawTicks--;
                this.yBodyRot = getYRot(); // 立って投げる間、体をサーバーの向きに合わせる(下の EVENT_FACE_YAW)
            }
            if (action() == G1Move.BITE_HOLD.id()) {
                this.yBodyRot = getYRot(); // 豚の食らいつき: 噛んでいる間、長い胴を口の向き(標的の方)に合わせる(同じ理由)
            }
            // クリーパー: 見せている莢の満ち具合。縮むのは 5 tick かけて(爆ぜのアニメの終わりの姿からならす)、育つのはサーバーの値のまま
            podsShownO = podsShown;
            float pods = pods() / 100.0F;
            podsShown = pods < podsShown ? Math.max(pods, podsShown - 0.2F) : pods;
            // クモ: 壁に張り付いている間は、体をサーバーの向き(壁の方)に合わせる(止まっている Mob の体の向きはクライアントが決めるので)
            int a = action();
            if (a == G1Move.CLIMB.id() || a == G1Move.HANG_IDLE.id() || a == G1Move.DROP_WINDUP.id()) {
                this.yBodyRot = getYRot();
                this.yHeadRot = getYRot();
            }
            return;
        }
        if (evolveLeft > 0 && --evolveLeft == 0) {
            setAction(ACTION_NONE);
        }
        if (holdLeft > 0 && --holdLeft == 0 && holdMove != null && action() == holdMove.id()) {
            setAction(ACTION_NONE);
        }
        if (latch != null && !latch.tick()) {
            endLatch();
        }
        if (tether != null && !tether.tick()) {
            tether = null;
        }
        if (dev() == G1Developed.BABY_ZOMBIE) {
            tickBabyMoves();
        }
        if (dev() == G1Developed.COW) {
            walkingSource.tick(this); // (c) 歩く発生源
        }
        if (dev() == G1Developed.CHICKEN && layReadyAt < 0L) {
            scheduleNextLay(); // (b) 最初の産卵まで 3〜5 分
        }
        if (dev() == G1Developed.CREEPER) {
            tickCreeper();
        }
        if (dev() == G1Developed.SKELETON) {
            tickSkeleton();
        }
        if (dev() == G1Developed.SPIDER) {
            tickSpider();
        }
        if (dev() == G1Developed.ENDERMAN) {
            tickEnderman();
        }
    }

    /**
     * チビゾンビの (b) の見た目と屋根の下の鼓動。
     * <ul>
     *   <li>柵・塀に乗り上げた(足元が柵・塀・柵の扉、または 1 tick で 0.6 ブロックより高く上がった)→ climb_over を {@value #CLIMB_TICKS} tick。</li>
     *   <li>頭の上が塞がった低い隙間を進んでいる → squeeze_under(繰り返し)。</li>
     *   <li>屋根の下にいると {@value #ROOF_PULSE_TICKS} tick ごとに頭の袋を鼓動させ、その升の浮遊を +{@value #ROOF_PULSE_PERCENT}%(屋根の下に入るたび最大 {@value #ROOF_PULSE_MAX} 回)。</li>
     *   <li>豚に放り出されて着地したら、標的が近ければ取り付く。</li>
     * </ul>
     */
    private void tickBabyMoves() {
        ServerLevel level = (ServerLevel) level();
        int a = action();
        // 放り出された後の着地
        if (tossedAt != null) {
            tossedTicks++;
            if (onGround() && tossedTicks > 2) {
                if (tossedAt.isAlive() && distanceTo(tossedAt) <= TOSS_LATCH_REACH && latch == null && (a == ACTION_NONE || a == G1Move.SQUEEZE_UNDER.id())) {
                    startLatch(tossedAt);
                }
                tossedAt = null;
            } else if (tossedTicks > 60) {
                tossedAt = null;
            }
        }
        a = action();
        // 柵を越える
        if (climbLeft > 0) {
            climbLeft--;
            if (climbLeft == 0 && action() == G1Move.CLIMB_OVER.id()) setAction(ACTION_NONE);
        } else if (a == ACTION_NONE || a == G1Move.SQUEEZE_UNDER.id()) {
            BlockState below = level.getBlockState(BlockPos.containing(getX(), getY() - 0.2D, getZ()));
            boolean onFence = below.is(BlockTags.FENCES) || below.is(BlockTags.WALLS) || below.is(BlockTags.FENCE_GATES);
            if (onFence || getY() - yo > 0.6D) {
                climbLeft = CLIMB_TICKS;
                setAction(G1Move.CLIMB_OVER.id());
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 0.5F, 1.3F);
            }
        }
        // 低い隙間をくぐる
        a = action();
        BlockPos head = blockPosition().above();
        boolean lowCeiling = !level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        if (a == ACTION_NONE && lowCeiling && isWalkingNow()) {
            setAction(G1Move.SQUEEZE_UNDER.id());
        } else if (a == G1Move.SQUEEZE_UNDER.id() && (!lowCeiling || !isWalkingNow())) {
            setAction(ACTION_NONE);
        }
        // 屋根の下の鼓動
        if (SporeExposureTracker.underSky(level, this)) {
            roofTicks = 0;
            roofPulses = 0;
        } else if (++roofTicks % ROOF_PULSE_TICKS == 0 && roofPulses < ROOF_PULSE_MAX) {
            roofPulses++;
            SporeFieldServer.emitPoint(level, blockPosition(), ROOF_PULSE_PERCENT / 100.0D * DesignData.sporeField().fullAt());
            level.sendParticles(ModParticles.SPORE_JET.get(), getX(), getY() + 0.8D, getZ(), 6, 0.2D, 0.15D, 0.2D, 0.01D);
            level.playSound(null, getX(), getY() + 0.8D, getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.5F, 0.6F);
        }
    }

    // ------------------------------------------------------------------ 殴られたとき
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean wasAlive = isAlive();
        if (!level().isClientSide() && isSleepingOpen()) {
            amount *= SLEEP_DAMAGE_MULTIPLIER; // 村人の (b): 眠って開いた嚢への攻撃は 2 倍【仮】
        }
        if (!level().isClientSide() && dev() == G1Developed.SHEEP && source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
            amount *= SHEEP_FIRE_MULTIPLIER; // 羊: 綿毛は燃えやすい【決定 11-09・数は仮】
        }
        boolean hurt = super.hurt(source, amount);
        if (!hurt || level().isClientSide() || !wasAlive) return hurt;
        hurtCount++;
        LivingEntity attacker = source.getEntity() instanceof LivingEntity le ? le : null;
        if (attacker != null) {
            if (latch != null) {
                latch.onHolderHurtBy(attacker);
                if (latch.released()) endLatch();
            }
            if (tether != null) tether.onHolderHurtBy(attacker);
        }
        if (!isAlive()) return true;
        switch (dev()) {
            case ZOMBIE -> {
                // (b-2) 近接で殴られたときだけ(仕様【案】: 遠くから倒せば呼ばれない)
                if (attacker != null && source.getDirectEntity() == attacker && level().getGameTime() >= callReadyAt) {
                    callAllies(attacker);
                }
            }
            case BABY_ZOMBIE -> {
                if (attacker != null) puffTrigger = true;
            }
            case VILLAGER -> {
                if (!mistByHealthUsed && getHealth() < getMaxHealth() * 0.5F) {
                    mistByHealthUsed = true;
                    mistTrigger = true;
                }
            }
            case ENDERMAN -> {
                // (b) 被弾したら溜めて転移する(溜めの間は当たる = 矢も)。(c-1) 抱えている相手に殴られた分を数える(一定で放す)
                if (attacker != null) {
                    teleportTriggerUntil = level().getGameTime() + HURT_TELEPORT_WINDOW;
                    if (attacker == grabVictim) grabDamage += amount;
                }
            }
            default -> {
            }
        }
        return true;
    }

    /**
     * ゾンビの (b-2)「殴られると胞子を噴いて仲間を呼ぶ」(仕様 Vault「G1 ゾンビ(1 段目)」3 節【決定 11-09】)。
     * 左胸の袋が縮んで胞子を噴き(煙 半径 {@value #CALL_CLOUD_RADIUS}・{@value #CALL_CLOUD_TICKS} tick・+{@value #CALL_DOSE_PER_SECOND} / 秒)、
     * 周り {@value #CALL_RANGE} ブロックの G1 をその場へ向かわせる(新しく湧かせず、今いる G1 だけ。殴れる G1 は殴った相手を標的に、
     * 殴らない G1 はその場へ歩く)。アニメ call_burst は上半身だけの重ね(歩き・攻撃の上に)。冷却 {@value #CALL_COOLDOWN} tick。
     */
    public void callAllies(LivingEntity attacker) {
        if (!(level() instanceof ServerLevel level)) return;
        callReadyAt = level.getGameTime() + CALL_COOLDOWN;
        triggerAnim("overlay", "call_burst");
        G1SporeCloud.spawn(level, position().add(0.0D, 0.6D, 0.0D), CALL_CLOUD_RADIUS, CALL_CLOUD_TICKS, CALL_DOSE_PER_SECOND, -1, 0, false, this);
        level.playSound(null, getX(), getY() + 1.4D, getZ(), SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.5F);
        for (AbstractParasitizedMonster m : level.getEntitiesOfClass(AbstractParasitizedMonster.class, getBoundingBox().inflate(CALL_RANGE))) {
            if (m == this || !m.isAlive() || m.isRising() || distanceTo(m) > CALL_RANGE) continue;
            if (m.attacks() && m.canAttack(attacker) && attacker != m) {
                m.setTarget(attacker);
            } else {
                m.getNavigation().moveTo(getX(), getY(), getZ(), 1.0D);
            }
            m.getLookControl().setLookAt(this);
        }
    }

    // ------------------------------------------------------------------ 豚: 乗り手
    /**
     * 豚の背の乗り手の高さ = 豚の胴の上面(モデルの 14 単位 = {@value #PIG_BACK_HEIGHT} ブロック。当たり判定の天辺 1.0 より少し下)。
     * 乗り手の側はアニメ ride が root を 4.5 単位下げて、またがる・張り付く(11-13。それまでは乗る姿勢が無く、0.3 下げて立たせていた)。
     */
    @Override
    protected net.minecraft.world.phys.Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float partialTick) {
        net.minecraft.world.phys.Vec3 p = super.getPassengerAttachmentPoint(passenger, dimensions, partialTick);
        return dev() == G1Developed.PIG ? new net.minecraft.world.phys.Vec3(p.x, PIG_BACK_HEIGHT, p.z) : p;
    }

    /** 乗り手(小さな G1)に豚を操らせない(豚の AI が運ぶ)。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (!level().isClientSide() && dev() == G1Developed.PIG) {
            AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null && !speed.hasModifier(CARRY_SPEED_ID)) {
                speed.addTransientModifier(new AttributeModifier(CARRY_SPEED_ID, CARRY_SPEED_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && !isVehicle()) {
            AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(CARRY_SPEED_ID);
        }
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            if (latch != null) {
                latch.release(false);
                latch = null;
            }
            if (tether != null) {
                tether.cut(false);
                tether = null;
            }
            // 豚が倒れたら乗り手が落ちて 1 秒転ぶ(仕様の対処「豚を倒すと乗り手が落ちて 1 秒転ぶ(隙)」)
            for (Entity p : List.copyOf(getPassengers())) {
                p.stopRiding();
                if (p instanceof AbstractParasitizedMonster m) m.stun(RIDER_FALL_STUN);
            }
            // エンダーマン: 抱えていた土はその場に落ちる(仕様 6.2)。足元が空いていれば置く
            BlockState soil = carried();
            if (soil != null && level() instanceof ServerLevel level) {
                BlockPos at = blockPosition();
                BlockState old = level.getBlockState(at);
                if ((old.isAir() || old.canBeReplaced()) && old.getFluidState().isEmpty() && !com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
                    level.setBlock(at, soil, net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
                setCarried(null);
            }
        }
        super.die(source);
    }

    // ------------------------------------------------------------------ アニメ
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        // 上半身だけの重ね(ゾンビの call_burst = 胸と肩の袋・首・頭、村人の gift = 左腕と脇の嚢)。後に登録するので、流している間はその骨だけ勝つ
        // (記憶 geckolib-two-layer-controllers)。歩き・追跡の上に重なる
        // エンダーマンが土を抱えている間は、腕の重ね carry を繰り返す(技・1 撃の間は腕をそのアニメに譲る)
        controllers.add(new AnimationController<>(this, "overlay", 0, state ->
                carried() != null && action() == ACTION_NONE && !isDeadOrDying() ? state.setAndContinue(CARRY) : PlayState.STOP)
                .triggerableAnim("call_burst", CALL_BURST)
                .triggerableAnim("gift", GIFT));
    }

    @Nullable
    @Override
    protected RawAnimation actionAnimation(int action) {
        if (action == ACTION_EVOLVE) return EVOLVE_IN;
        G1Move m = G1Move.byId(action);
        return m != null ? m.raw() : super.actionAnimation(action);
    }

    @Override
    protected int actionTransition(int action) {
        G1Move m = G1Move.byId(action);
        return m != null ? m.blendIn : super.actionTransition(action);
    }

    @Nullable
    @Override
    protected RawAnimation ridingAnimation() {
        return dev() == G1Developed.BABY_ZOMBIE ? RIDE : null;
    }

    /** 村人の (b) 昼: 投げる動きを重ねで流す(サーバー。相手を決めた瞬間に呼ぶと、10 tick 後の投げと手を離す所が合う)。 */
    public void playGift() {
        triggerAnim("overlay", "gift");
        level().broadcastEntityEvent(this, EVENT_FACE_YAW);
    }

    /**
     * 立っている Mob の体の向きはクライアントが決める(頭から 75° 以内に寄せるだけで、サーバーの yBodyRot は届かない)。
     * 立ち止まって投げるときに体ごと相手を向かせるため、この合図から {@value #FACE_YAW_TICKS} tick の間、クライアントの体の向きを同期した yRot に合わせる。
     */
    private static final byte EVENT_FACE_YAW = 90;
    static final int FACE_YAW_TICKS = 14;
    /** クライアントだけ: 体の向きを yRot に合わせる残り。 */
    private int faceYawTicks;

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_FACE_YAW) {
            faceYawTicks = FACE_YAW_TICKS;
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Nullable
    @Override
    protected RawAnimation deathAnimation() {
        return DEATH_BURST;
    }

    /** 死んで death_burst の長さが経ったら破裂して消える(バニラの 20 tick の白い煙は出さない)。 */
    @Override
    protected void tickDeath() {
        boolean before = isRemoved();
        if (dev() == G1Developed.CREEPER && !podsFull()) {
            // クリーパー: 莢が空の間に倒されたら小さな破裂(仕様 5 節「半径 1」。体の大きさを半分として同じ式に通す = 汚染の半径 1・浮遊 +10%)【仮】
            this.deathTime++;
            if (this.deathTime >= Math.min(dev().burst, 19) && level() instanceof ServerLevel level && !isRemoved()) {
                G1DeathBurst.burst(level, position(), getBbWidth() * 0.5F, getBbHeight() * 0.5F);
                remove(Entity.RemovalReason.KILLED);
            }
            return;
        }
        G1DeathBurst.tickDeath(this, dev().burst);
        if (!before && isRemoved() && dev() == G1Developed.COW && level() instanceof ServerLevel level) {
            // 牛: 破裂の跡に乳だまりを 1 つ残す【決定 11-09 牛 8】
            G1MilkPuddle.spawn(level, position(), DEATH_PUDDLE_RADIUS, G1MilkSprayGoal.PUDDLE_TICKS);
        }
        if (!before && isRemoved() && dev() == G1Developed.SHEEP && level() instanceof ServerLevel level
                && fluff() > 0 && !isOnFire() && SporeExposureTracker.underSky(level, this)) {
            // 羊: 残っていた綿毛の一部が風下へ流れる【決定 11-13】。条件は (c) とそろえる = 刈ってから倒す・燃やして倒す・屋根の下で倒すと流れない
            setLastFluffLanding(com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffDrift.loose(level,
                    position().add(0.0D, getBbHeight() * 0.6D, 0.0D), fluff() * DEATH_FLUFF_PERCENT_PER_STAGE, getRandom()));
        }
    }

    // ------------------------------------------------------------------ 保存
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("MistByHealthUsed", mistByHealthUsed);
        if (dev() == G1Developed.SHEEP) {
            tag.putInt("Fluff", fluff());
        }
        if (dev() == G1Developed.CHICKEN && layReadyAt >= 0L) {
            tag.putLong("LayIn", Math.max(0L, layReadyAt - level().getGameTime())); // 残りの tick で持つ(世界の時計が変わっても狂わない)
        }
        if (dev() == G1Developed.CREEPER && podRegrowLeft > 0) {
            tag.putInt("PodRegrow", podRegrowLeft);
        }
        BlockState soil = carried();
        if (soil != null) {
            tag.put("CarriedSoil", net.minecraft.nbt.NbtUtils.writeBlockState(soil));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        mistByHealthUsed = tag.getBoolean("MistByHealthUsed");
        if (tag.contains("Fluff")) {
            setFluff(tag.getInt("Fluff"));
        }
        if (tag.contains("LayIn")) {
            layReadyAt = level().getGameTime() + tag.getLong("LayIn");
        }
        if (tag.contains("PodRegrow")) {
            setPodRegrowLeft(tag.getInt("PodRegrow"));
        }
        if (tag.contains("CarriedSoil", 10)) {
            BlockState soil = net.minecraft.nbt.NbtUtils.readBlockState(level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    tag.getCompound("CarriedSoil"));
            setCarried(soil.isAir() ? null : soil);
        }
    }

    // ------------------------------------------------------------------ 声
    @Override
    protected SoundEvent getAmbientSound() {
        return switch (dev()) {
            case ZOMBIE, BABY_ZOMBIE -> SoundEvents.ZOMBIE_AMBIENT;
            case VILLAGER -> SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
            case COW -> SoundEvents.COW_AMBIENT;
            case PIG -> SoundEvents.PIG_AMBIENT;
            case SHEEP -> SoundEvents.SHEEP_AMBIENT;
            case CHICKEN -> SoundEvents.CHICKEN_AMBIENT;
            case CREEPER -> null;
            case SKELETON -> SoundEvents.SKELETON_AMBIENT;
            case SPIDER -> SoundEvents.SPIDER_AMBIENT;
            case ENDERMAN -> SoundEvents.ENDERMAN_AMBIENT;
        };
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return switch (dev()) {
            case ZOMBIE, BABY_ZOMBIE -> SoundEvents.ZOMBIE_HURT;
            case VILLAGER -> SoundEvents.ZOMBIE_VILLAGER_HURT;
            case COW -> SoundEvents.COW_HURT;
            case PIG -> SoundEvents.PIG_HURT;
            case SHEEP -> SoundEvents.SHEEP_HURT;
            case CHICKEN -> SoundEvents.CHICKEN_HURT;
            case CREEPER -> SoundEvents.CREEPER_HURT;
            case SKELETON -> SoundEvents.SKELETON_HURT;
            case SPIDER -> SoundEvents.SPIDER_HURT;
            case ENDERMAN -> SoundEvents.ENDERMAN_HURT;
        };
    }

    /** 死に際の声は出さない(破裂の湿った音に置き換える = G1DeathBurst)。 */
    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return null;
    }
}
