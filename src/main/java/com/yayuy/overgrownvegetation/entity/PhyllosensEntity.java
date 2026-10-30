package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;


/**
 * 「フィロセンス」({@code phyllosens}) — グループ4(拡大種)の索敵役。
 *
 * <p>長い首の先に花を持つ多脚の植物型。戦うためではなく<b>見つけるため</b>にいる Mob で、
 * プレイヤーの居場所を周りの味方へ配るのが本業。
 *
 * <h2>2 つの印(この Mob の本体)</h2>
 * <p>仕組みはすべて {@link PhyllosensInfluence} の 2 つの効果に集約されている。
 * ワールド側にメモ帳を持たず、<b>効果そのものが共有状態</b>になっているのが肝
 * (ユーザー要望 2026-09-14、索敵システム作り直し)。
 * <ul>
 *     <li><b>視線</b>({@code phyllosens_watch}、「フィロセンスの視線 / Phyllosens' Gaze」)を
 *     <b>プレイヤー</b>に付ける。</li>
 *     <li><b>誘導</b>({@code phyllosens_guidance}、「フィロセンスの誘導」)を
 *     <b>味方(グループ1〜10)</b>に付ける。</li>
 * </ul>
 * <p>誘導を受けた Mob は {@link GuidedHuntGoal} で、<b>距離も視界も壁も問わず</b>
 * 視線持ちのプレイヤーへ向かう —— ただし<b>一体ずつ突っ込まず、近くの味方と合流してから</b>。
 * どちらの印も {@code phyllosensWatchTicks} / {@code phyllosensGuidanceTicks}
 * (既定 1200 = 1 分)で切れるので、離れて 1 分しのげば追跡は解ける。
 *
 * <h2>ふだん(半径 {@code phyllosensAuraRange}、既定 32)</h2>
 * <p>ただ立っているだけで、{@code phyllosensAuraIntervalTicks} ごとに周囲 32 ブロックの
 * プレイヤーに視線を、味方に誘導を配り続ける。<b>近くに居るだけで狩り出される</b>。
 *
 * <h2>索敵(半径が跳ね上がる)</h2>
 * <ul>
 *     <li><b>32 ブロック以内にプレイヤーが {@code phyllosensQuietTicks}(既定 1200 = 1 分)
 *     居なかったら</b>索敵に入る({@link PhyllosensScoutGoal})。
 *     <b>「プレイヤーが居ない」だけを見る</b> — 牛やコウモリは関係ない。</li>
 *     <li>入ったら {@code phyllosensScoutDurationTicks}(既定 600 = 30 秒)その場で撒き続け、
 *     首を上げ花を開き、胞子を撒きながら足元を汚染する({@link InvaderSporeField})。</li>
 *     <li>そのあいだ配る範囲が跳ね上がる —— 視線は
 *     <b>{@code phyllosensScoutWatchRange}(既定 512)</b>のプレイヤーへ、誘導は
 *     <b>{@code phyllosensScoutGuidanceRange}(既定 256)</b>の味方へ。</li>
 * </ul>
 *
 * <p><b>距離の現実</b>: Mob はシミュレーション距離の外では動かないので、実際に歩いてくるのは
 * 視線持ちのプレイヤーの周囲(既定 128 ブロック)に居る Mob だけ。それより遠い個体は印を持ったまま
 * 凍って待ち、プレイヤーが近づいた瞬間に動き出す。誘導を 256 ブロックへ配るのはこの予備軍のため。
 *
 * <h2>ビーム</h2>
 * <p><b>索敵中以外に</b>相手を見つけたときは、ティタニカの精密ビームと同じ形の光線で撃つ
 * ({@link PhyllosensBeamGoal})。ティタニカのものより細く・当たり判定も狭い。
 * 頭を相手へ向けて {@code phyllosensBeamChargeTicks}(既定 60 = 3 秒)溜め<b>花を大きく開き</b>、
 * そこで<b>狙点を固定</b>して {@code phyllosensBeamLockTicks}(既定 6 = 0.3 秒)後に発射。
 * 撃った瞬間は反動で首がのけぞる({@code beam_fire})。
 * ダメージ {@code phyllosensBeamDamage}(既定 20)、射程 {@code phyllosensBeamRange}
 * (<b>既定 80</b> — 2026-09-14 に 40 から倍。{@code mobs.json} の {@code followRange} も 80 に揃えてある)。
 * <b>溜めに入ったらその場から一歩も動かない</b>({@link #isRooted()}、ユーザー要望 2026-09-14) —
 * 射程の外にいる間だけ歩いて近づく。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code run} / {@code scout} / {@code beam_charge} / {@code beam_fire}。
 * {@code assets/overgrown_vegetation/animations/phyllosens.animation.json}。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class PhyllosensEntity extends AbstractOvergrownInvaderEntity {

    /** 索敵中(首を上げ、花を開いている)。 */
    public static final int STATE_SCOUT = 4;
    /** ビームの溜め〜発射中。 */
    public static final int STATE_BEAM = 5;

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN_ANIM = RawAnimation.begin().thenLoop("run");
    // 索敵・ビームは「姿勢を作って、そこで止まる」動き。ループさせると 5 秒ごとに首を下ろして
    // 上げ直してしまうので、最後のコマで固まる再生にする(json 側も hold_on_last_frame)。
    private static final RawAnimation SCOUT_ANIM = RawAnimation.begin().thenPlayAndHold("scout");
    private static final RawAnimation BEAM_CHARGE_ANIM = RawAnimation.begin().thenPlayAndHold("beam_charge");
    private static final RawAnimation BEAM_FIRE_ANIM = RawAnimation.begin().thenPlayAndHold("beam_fire");

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * 溜め → 発射だけは補間 0: {@code beam_fire} の 0 秒目は {@code beam_charge} の最後のコマと全部の骨で同じ姿勢なので
     * 寄せる必要がない。補間を挟んでいたころ(2026-09-23 まで)は、のけぞりが撃ってから 6 tick 遅れて始まり、
     * 光線が消えかけてから首が跳ねていた。
     */
    static final int TRANSITION_TICKS = 6;

    // --- ビームの同期データ(クライアントの描画用) ---

    /** 0 = 撃っていない / 1 = 溜め / 2 = 狙点固定 / 3 = 照射。 */
    public static final byte BEAM_NONE = 0;
    public static final byte BEAM_CHARGE = 1;
    public static final byte BEAM_LOCKED = 2;
    public static final byte BEAM_FIRE = 3;

    private static final EntityDataAccessor<Byte> DATA_BEAM_PHASE =
            SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_BEAM_X =
            SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BEAM_Y =
            SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BEAM_Z =
            SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.FLOAT);

    /** 半径 {@code phyllosensAuraRange} にプレイヤーが居ない状態が続いている tick 数。 */
    private int playerFreeTicks;
    /**
     * ビーム中でも<b>射程へ近づいている最中</b>だけは動いてよい。{@link PhyllosensBeamGoal} が立てる。
     * サーバー側だけの印で、クライアントは {@code STATE_BEAM} を常に「根を張っている」と見る
     * (見た目の押し合いにしか使わないので、そのずれは問題にならない)。
     */
    private boolean beamApproaching;
    /**
     * <b>頭の先端が実際にあるワールド座標</b>。<b>クライアント専用</b>で、
     * {@code client/PhyllosensRenderer} が描画のたびに焼かれた骨の行列から読んで入れる。
     * ビームはここから出る({@link #beamOrigin()})。
     */
    @Nullable
    private Vec3 headTipPosition;
    /** 次に印を配り直すまでの残り tick(毎 tick 走査すると重いので間引く)。 */
    private int auraTimer;

    // --- 向きの変え方(2026-09-14: 「大きいモデルなので、その場でぐるぐるしないように」) ---

    /** 立ち止まっているとき、体が 1 tick に回れる角度(度)。バニラは 10 tick で全部回る。 */
    private static final float BODY_TURN_STEP = 3.0F;

    /** 歩いているとき、進行方向を 1 tick に変えられる角度(度)。バニラは 90。 */
    private static final float MOVE_TURN_STEP = 12.0F;
    /** 首が 1 tick に回れる角度(度)。バニラは 10。 */
    private static final int HEAD_ROT_SPEED = 4;
    /** 首を体からどこまでひねれるか(度)。バニラは 75。 */
    private static final int MAX_HEAD_YAW = 50;

    // --- ビームの出どころ(サーバー用の概算。クライアントは焼かれた骨を使う) ---

    /**
     * 撃つときの姿勢での<b>頭の先端の高さ</b>(素のモデルでの足元からのブロック数)。
     * {@code beam_charge} の最後のコマ(首が合計 36 度持ち上がった形)で骨の鎖をたどった値。
     * {@code scale} は掛かっていないので、使うときに {@code getScale()} を掛けること。
     */
    private static final double BEAM_HEAD_HEIGHT = 4.41D;
    /** 同じく<b>前方向への出っ張り</b>(素のモデルでのブロック数)。 */
    private static final double BEAM_HEAD_FORWARD = 2.62D;

    public PhyllosensEntity(EntityType<? extends PhyllosensEntity> type, Level level) {
        super(type, level);
        // 歩きながらの方向転換も、この体の大きさだと 1 tick 90 度では速すぎる。
        this.moveControl = new SlowTurnMoveControl(this, MOVE_TURN_STEP);
        this.xpReward = MobStats.xpReward("phyllosens", 12);
    }

    /**
     * <b>その場でぐるぐる回らない</b>(ユーザー指摘 2026-09-14)。
     *
     * <p>バニラは立ち止まっている Mob の体を 10 tick で頭の向きへ揃えるので、
     * 見回すゴールが別方向を選ぶたびに体ごと独楽のように回ってしまう。この体の大きさでは
     * 目に余るので、{@link SlowBodyRotationControl} で <b>1 tick {@value #BODY_TURN_STEP} 度</b>
     * まで(＝一回転に約 6 秒)に抑える。
     */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new SlowBodyRotationControl(this, BODY_TURN_STEP);
    }

    /** 首もゆっくり。バニラの 10 度/tick は、この長さの首だと鞭のように見える。 */
    @Override
    public int getHeadRotSpeed() {
        return HEAD_ROT_SPEED;
    }

    /** 首のひねりの上限。ここを越えたぶんは体が(ゆっくり)追う。 */
    @Override
    public int getMaxHeadYRot() {
        return MAX_HEAD_YAW;
    }

    /**
     * <b>きょろきょろ見回さない</b>。回転を遅くしてもこのゴールが生きていると、
     * 1〜2 秒おきに選ばれる別方向へ延々とゆっくり回り続けることになる。見張り役の植物なので、
     * 見るものがあるとき(近くのプレイヤー・索敵・ビーム)だけ首を動かす。
     */
    @Override
    protected boolean useRandomLookAroundGoal() {
        return false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "phyllosens");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BEAM_PHASE, BEAM_NONE);
        builder.define(DATA_BEAM_X, 0.0F);
        builder.define(DATA_BEAM_Y, 0.0F);
        builder.define(DATA_BEAM_Z, 0.0F);
    }

    @Override
    protected void registerSpeciesGoals() {
        // ビームは索敵より先。索敵中(STATE_SCOUT)は canUse() が false を返すので撃たない。
        this.goalSelector.addGoal(2, new PhyllosensBeamGoal(this));
        this.goalSelector.addGoal(3, new PhyllosensScoutGoal(this));
    }

    /** 索敵行動が「その場に留まって汚染する」役目を兼ねているので、アルボラのうずくまりは使わない。 */
    @Override
    protected boolean useSporeCrouchGoal() {
        return false;
    }

    /** 遠距離で戦う。殴りに行くと撃つ間合いが保てない。 */
    @Override
    protected boolean useMeleeAttackGoal() {
        return false;
    }

    /*
     * 群れの中の立ち位置(ユーザー指定 2026-09-15「フィロセンスは後方」)は、群れの司令塔が役割(RANGED_SCOUT = いちばん後ろの列)で
     * 決める(swarm/SwarmRole の並び順)。旧い群れの「重心からの距離の倍率」「陣形の後ろ寄せ」は 2026-09-25 に消えた。
     */

    /**
     * 群れが止まって全員で汚すとき({@link com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal})は<b>索敵する</b>
     * (個体の索敵と同じ中身 {@link PhyllosensScoutAction}。根を張るのはこのときだけ期待どおり。群れ v2、2026-09-25)。
     * 群れの一員は、群れが歩いている間には索敵しない({@link PhyllosensScoutGoal} が一員では動かない)。
     * 索敵で配った視線は、群れが汚染を広げ終えてから拾う(SPREAD の間は司令塔が視線持ちを新しく標的にしない。
     * 仮決め 2026-09-25、{@code swarm/SwarmTargeting#think} の b)。
     */
    @Override
    protected com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal.Action createSpreadAction() {
        return new PhyllosensScoutAction(this);
    }

    /** 索敵中に広げる汚染の半径。アルボラより広い(ユーザー要望 2026-09-14)。 */
    @Override
    public int sporeCorruptionRadius() {
        return OvergrownVegetationConfig.COMMON.phyllosensCorruptionRadius.get();
    }

    /**
     * <b>索敵中は微動だにしない</b>(ユーザー指示 2026-09-14)。
     *
     * <p>{@link PhyllosensScoutGoal} は毎 tick 経路と入力と水平速度を潰しているが、それだけでは
     * 足りない —— 周りを味方(アルボラの群れなど)が通ると
     * {@code Mob#pushEntities} の押し合いでじりじり横へずらされてしまう。押される側にならないよう、
     * 索敵中だけ当たり判定の押し合いから外す。
     *
     * <p>攻撃は普通に当たる({@code isPushable} は押し合いだけの判定で、被弾には関係しない)。
     */
    @Override
    public boolean isPushable() {
        return !isRooted() && super.isPushable();
    }

    /**
     * <b>一歩も動かない状態か</b>。索敵中と、<b>ビームの溜め〜発射中</b>
     * (ユーザー要望 2026-09-14:「チャージ中は動かないように」)。
     *
     * <p>ビームでも射程の外から歩いて近づいている間({@link #beamApproaching})は普通に動く。
     */
    public boolean isRooted() {
        int state = getActionState();
        return state == STATE_SCOUT || (state == STATE_BEAM && !this.beamApproaching);
    }

    /** {@link PhyllosensBeamGoal} から。射程の外にいて歩いて近づいている間だけ true。 */
    public void setBeamApproaching(boolean approaching) {
        this.beamApproaching = approaching;
    }

    /**
     * <b>遠くても描く</b>(ユーザー要望 2026-09-14:「遠くから見て索敵をしているのが分かるように」)。
     *
     * <p>バニラの既定は当たり判定の大きさから上限を決めていて({@code 辺の平均 × 64})、
     * この Mob だと 230 ブロックほどで消える。索敵中の「首を上げ花を開いた姿」は遠景の合図なので、
     * クライアントが持っているうちは描かせる。実際の上限は
     * {@code clientTrackingRange} とプレイヤーの描画距離のほうで頭打ちになる。
     *
     * <p>アニメーションを止める側({@code MobRenderLod})でもこの Mob は対象外にしてある —
     * 描けても姿勢がレストポーズに戻ってしまっては意味がないため。
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 512.0D * 512.0D;
    }

    /**
     * <b>根を張っている間の移動を最後の関門で断つ</b>(ユーザー指示 2026-09-14)。
     * 対象は{@link #isRooted() 索敵中とビームの溜め〜発射中}。
     *
     * <p>{@link PhyllosensScoutGoal} / {@link PhyllosensBeamGoal} 側でも
     * 経路・入力・水平速度を毎 tick 潰しているが、そこは
     * {@code serverAiStep} の中 —— そのあとに動く経路(ノックバックの残り、押し合いの跳ね返り、
     * 他所から差し込まれた速度)は素通りしてしまう。<b>自力移動も外力も必ずここを通る</b>ので、
     * 根を張っている間は入力を捨て、{@code super.travel} の前後で水平速度を 0 に潰す。
     *
     * <p>縦(重力・落下)はそのまま残す — 足場が崩れたら普通に落ちてほしいため。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (!isRooted()) {
            super.travel(travelVector);
            return;
        }
        setZza(0.0F);
        setXxa(0.0F);
        setSpeed(0.0F);
        Vec3 before = getDeltaMovement();
        setDeltaMovement(0.0D, before.y, 0.0D);
        super.travel(Vec3.ZERO);
        Vec3 after = getDeltaMovement();
        setDeltaMovement(0.0D, after.y, 0.0D);
    }

    /**
     * 索敵中は押し合いに参加しない。{@link #isPushable()} で「押される側」からは外れるが、
     * <b>自分から押しに行く側</b>({@code LivingEntity#pushEntities})は別勘定で、
     * 押した反動で自分も動いてしまう。根を張っている間はどちらもしない。
     */
    @Override
    protected void pushEntities() {
        if (isRooted()) {
            return;
        }
        super.pushEntities();
    }

    // --- ふだんの印配り と 索敵に入る条件 ---

    /**
     * いま索敵を始めてよいか。条件は<b>ユーザー指定のひとつだけ</b>(2026-09-14) —
     * <b>半径 {@code phyllosensAuraRange}(既定 32)にプレイヤーが
     * {@code phyllosensQuietTicks}(既定 1200 = 1 分)居なかった</b>こと。
     *
     * <p>見るのは<b>プレイヤーだけ</b>。以前は「グループ1〜10 以外の生き物すべて」を数えていて、
     * 牛 1 頭・洞窟のコウモリ 1 匹で永久に索敵に入れなかった(2026-09-14 に判明)。
     */
    public boolean canBeginScout() {
        return this.playerFreeTicks >= OvergrownVegetationConfig.COMMON.phyllosensQuietTicks.get();
    }

    /**
     * 索敵が終わった。数え直しからやり直す(＝また 1 分プレイヤーが来なければ次の索敵に入る)。
     * <b>途中で中断された場合も同じ</b> — そうしないと、殴られて中断 → すぐ再開、を繰り返して
     * 立ち止まり続けることになる。
     */
    public void onScoutFinished() {
        this.playerFreeTicks = 0;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        tickAura();
    }

    /**
     * ふだんの印配りと、プレイヤー不在の計測。{@code phyllosensAuraIntervalTicks} ごとに
     * まとめてやる(毎 tick 走査すると重いため)。
     *
     * <p>やることは 2 つ:
     * <ul>
     *     <li>半径 {@code phyllosensAuraRange} のプレイヤーへ<b>視線</b>、味方へ<b>誘導</b>を配る。</li>
     *     <li>その半径にプレイヤーが 1 人も居なければ {@link #playerFreeTicks} を進める。
     *     1 人でも居れば 0 に戻す。</li>
     * </ul>
     * 索敵中はここでは配らない —— {@link PhyllosensScoutGoal} 側がずっと広い範囲へ配るので、
     * 二重に走らせる意味がない。
     */
    private void tickAura() {
        int interval = Math.max(1, OvergrownVegetationConfig.COMMON.phyllosensAuraIntervalTicks.get());
        if (this.auraTimer == 0) {
            // 2026-09-24 軽量化(監査 867): 初回だけ個体ごとにずらす。auraTimer は保存されず 0 から始まるので、
            // 同じ tick に湧いた・読み込まれた個体がそろって同じ tick に走査していた。
            // 0 になるのは初回だけ(配ったあとは interval に戻り、1 以上で次の回を迎える)。
            // ずれるのは初回の配布が最大 interval - 1 tick 遅れるだけで、間隔は同じ。
            this.auraTimer = 1 + Math.floorMod(this.getId(), interval);
        }
        if (--this.auraTimer > 0) {
            return;
        }
        this.auraTimer = interval;
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        // 獲物(グループ1〜3)を探して群れの狩り手へ渡す役(ユーザー要望 2026-09-15、旧 InvaderPreyIntel)は、
        // 2026-09-25 から群れの司令塔の標的探し(先導役から半径 48 の箱、40 tick に 1 回)に入った。
        // 旧い配り方は間引きの数え違いで 3 秒のはずが 60 秒おきになっていた(それもこれで消える)。

        double range = OvergrownVegetationConfig.COMMON.phyllosensAuraRange.get();
        int marked = PhyllosensInfluence.markPlayers(level, this, range);
        if (getActionState() != STATE_SCOUT) {
            PhyllosensInfluence.guideAllies(level, this, range);
        }
        if (marked > 0) {
            this.playerFreeTicks = 0;
        } else {
            this.playerFreeTicks += interval;
        }
    }

    /** 半径 {@code phyllosensAuraRange} にプレイヤーが居ない状態が続いている tick 数。 */
    public int getPlayerFreeTicks() {
        return this.playerFreeTicks;
    }

    // --- ビームの状態(サーバーが書き、クライアントが読む) ---

    public byte getBeamPhase() {
        return this.entityData.get(DATA_BEAM_PHASE);
    }

    public void setBeamPhase(byte phase) {
        this.entityData.set(DATA_BEAM_PHASE, phase);
    }

    /** ビームの終点(ワールド座標)。 */
    public Vec3 getBeamTarget() {
        return new Vec3(this.entityData.get(DATA_BEAM_X), this.entityData.get(DATA_BEAM_Y),
                this.entityData.get(DATA_BEAM_Z));
    }

    public void setBeamTarget(Vec3 target) {
        this.entityData.set(DATA_BEAM_X, (float) target.x);
        this.entityData.set(DATA_BEAM_Y, (float) target.y);
        this.entityData.set(DATA_BEAM_Z, (float) target.z);
    }

    /**
     * <b>ビームの出どころ = 頭の先端</b>(ユーザー要望 2026-09-14:
     * 「ビームが出る場所が首の少し後ろになっているので、しっかり頭の先端から出るように」)。
     *
     * <p>クライアントでは {@code client/PhyllosensRenderer} が焼かれた骨の行列から読んだ
     * <b>実際の頭の先端</b>を使う({@link #setHeadTipPosition})。首は
     * {@code neck → bone3 → bone4 → bone5 → head} と長いうえ、溜めの 3 秒間ずっと
     * 持ち上がり続けるので、計算では追いつかない。
     *
     * <p><b>サーバー側には焼かれた骨が無い</b>ので、{@code beam_charge} の<b>撃つときの姿勢</b>
     * (首が合計 36 度持ち上がった形)で骨の鎖をたどって求めた定数を使う。撃つのはその姿勢に
     * なってからなので、判定の線と見た目はほぼ重なる。
     */
    public Vec3 beamOrigin() {
        Vec3 tip = this.headTipPosition;
        if (tip != null) {
            return tip;
        }
        // Minecraft の yaw は 0 で +Z 向きなので、前方向は (-sin, 0, cos)。
        double scale = this.getScale();
        float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
        return new Vec3(
                this.getX() - Mth.sin(yaw) * BEAM_HEAD_FORWARD * scale,
                this.getY() + BEAM_HEAD_HEIGHT * scale,
                this.getZ() + Mth.cos(yaw) * BEAM_HEAD_FORWARD * scale);
    }

    /** {@code client/PhyllosensRenderer} が描画のたびに、焼かれた頭の先端の位置を入れてくる。 */
    public void setHeadTipPosition(Vec3 pos) {
        this.headTipPosition = pos;
    }

    /**
     * 自力で相手を掴んだときは、そのプレイヤーにも視線を付ける
     * (32 ブロックの輪の外から矢で撃たれた、など「見られていないのに絡んできた」相手用)。
     */
    @Override
    public void setTarget(LivingEntity target) {
        super.setTarget(target);
        if (target instanceof net.minecraft.world.entity.player.Player player
                && this.level() instanceof ServerLevel level
                && PhyllosensInfluence.isWatchable(player)) {
            PhyllosensInfluence.markPlayers(level, this, this.distanceTo(player) + 1.0D);
            this.playerFreeTicks = 0;
        }
    }

    // --- アニメーション ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            boolean fire = getActionState() == STATE_BEAM && getBeamPhase() == BEAM_FIRE;
            // 発射の反動は撃った瞬間に始める(溜めの最後のコマと発射の 0 秒目は同じ姿勢)。
            state.getController().transitionLength(fire ? 0 : TRANSITION_TICKS);
            switch (getActionState()) {
                case STATE_SCOUT -> {
                    return state.setAndContinue(SCOUT_ANIM);
                }
                case STATE_BEAM -> {
                    return state.setAndContinue(getBeamPhase() == BEAM_FIRE ? BEAM_FIRE_ANIM : BEAM_CHARGE_ANIM);
                }
                default -> {
                }
            }
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(
                        MobAnimation.isFasterThan(state, 0.10D) ? RUN_ANIM : WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    // --- 音 ---

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SCULK_CLICKING;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SCULK_BLOCK_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SCULK_SHRIEKER_BREAK;
    }

    @Override
    protected float getSoundVolume() {
        return 0.7F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SCULK_BLOCK_STEP, 0.2F, 0.7F);
    }

    /** 索敵役なので目が良い。{@code FOLLOW_RANGE} は {@code mobs.json} 側で 56 に揃えてある。 */
    public double sightRange() {
        return this.getAttributeValue(Attributes.FOLLOW_RANGE);
    }
}
