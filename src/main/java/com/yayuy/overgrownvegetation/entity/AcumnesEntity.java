package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 「アクムネス」— グループ5(適応種)の空の狙撃役(2026-09-28)。ヒメバチの姿で、体より長い産卵管を針として撃つ。
 *
 * <h2>ユーザーが決めたこと</h2>
 * <ul>
 *     <li>採用の案(2026-09-24): 上空から<b>照準線を 2 秒見せてから</b>、<b>防具を貫通する棘</b>を撃ち込む。
 *     <b>照準線は 2026-09-28 のユーザー指定で消した</b>(runClient で見て「概ね大丈夫でした。照準線は消せますか？」)。
 *     予兆は針を回して前へ向け止まって震えるアニメ(2 秒)と狙い始めの音だけ。撃つまでの時間と射線の判定はそのまま。
 *     潰す戦術 = 開けた場所からの一方的な遠距離。対処 = 遮蔽物に入って射線を切る。</li>
 *     <li>モデル・テクスチャはユーザーの低キューブ版(箱 79)。大きさは「ファルキムネスやスクティムネスと同じくらいのサイズ感」
 *     → 体格 1.8(ファルキムネスと同じ。全長 10.6・翅の幅 5.4・体 4.1 ブロック)。</li>
 *     <li>G5 は G4 より湧きにくく数が出ない(レア枠・1 体ずつ)。適応(武器への耐性)は group_5 タグで自動。</li>
 * </ul>
 * それ以外の数値(体力・攻撃・間合い・高度・時間・速さ)は<b>すべて仮</b>。アニメは Claude の生成器
 * ({@code tools/g5-models/mobs/AcumnesAnims.java})を低キューブ版に流し直したもの。手引きは {@code tools/g5-models/out/acumnes/NOTES.md}。
 *
 * <h2>戦い方</h2>
 * 標的の上空(足元から 6〜9 ブロック上、水平に 11〜17 ブロック離れた、射線の通る点)へ飛んで止まり、
 * 狙う(aim / aim_v、2 秒)→ 撃つ(fire)→ 装填(reload / reload_v)→ 2.5 秒休んで位置を取り直す、を繰り返す。
 * 狙い始めてから撃つまで 2.2 秒(補間 4 tick + aim の 2 秒)。
 * 射線(胸 → 標的)が {@value #LOS_GRACE} tick 続けて切れたら取りやめる。棘の飛ぶ向きは撃った瞬間の標的へ(先読みはしない)。
 *
 * <h2>特殊攻撃</h2>
 * 今は狙撃の 1 つだけ(G5 の「特殊攻撃は通常攻撃の合間に」= {@link SpecialPacing} は使っていない)。狙撃そのものに
 * 2 秒の予兆があるので、交戦の直後から撃つ。特殊攻撃を足すならここに。
 */
public class AcumnesEntity extends Monster implements GeoEntity, ParasitizedMob {

    // ------------------------------------------------------------------ 動作

    public static final int ACT_NONE = 0;
    /** 照準・横回し(下の空きが少ないとき)。 */
    public static final int ACT_AIM = 1;
    /** 照準・縦回し(下に {@link #AIM_V_CLEARANCE} ブロック以上の空きがあるとき。ふつうはこちら)。 */
    public static final int ACT_AIM_V = 2;
    public static final int ACT_FIRE = 3;
    public static final int ACT_RELOAD = 4;
    public static final int ACT_RELOAD_V = 5;
    public static final int ACT_CANCEL = 6;
    public static final int ACT_CANCEL_V = 7;
    public static final int ACT_DEATH = 8;
    /** 照準の後半・撃った直後の死(腹を巻いたまま横倒し)。 */
    public static final int ACT_DEATH_AIM = 9;

    /** 動作を切り替えるときの補間(tick)。GeckoLib はこの間は前の姿勢から寄せ、終わってから 0 秒目を流す(ほかの G5 と同じ 4)。 */
    public static final int TRANSITION_TICKS = 4;
    /** aim / aim_v の長さ(2.0 秒)+ 補間。この tick に撃つ(狙い始めてから 2.2 秒)。 */
    static final int AIM_TICKS = TRANSITION_TICKS + 40;
    /** fire は補間 0 で切り替える(aim の最後と fire の 0 秒目は同じ姿勢)。0.1 秒で棘が消える = 2 tick 目に弾を出す。 */
    static final int FIRE_SHOT_TICK = 2;
    static final int FIRE_TICKS = 16;
    static final int RELOAD_TICKS = TRANSITION_TICKS + 24;
    static final int CANCEL_TICKS = TRANSITION_TICKS + 19;
    /** 倒れるアニメ(1.5 秒)+ 補間 + 少しの余韻。 */
    static final int DEATH_TICKS = TRANSITION_TICKS + 30 + 6;
    /** 撃った(装填し終えた)後、次に狙い始めるまで(2.5 秒、仮)。 */
    static final int COOLDOWN_TICKS = 50;
    /** 取りやめの後(1.5 秒、仮)。 */
    static final int CANCEL_COOLDOWN_TICKS = 30;
    /** 射線がこれだけ続けて切れたら照準を取りやめる(0.3 秒、仮)。 */
    static final int LOS_GRACE = 6;

    // ------------------------------------------------------------------ 形(モデルの単位 1/16、体格 1 のとき。モデルから測った値)

    /** 胸(body の軸)の高さ。静止 22、照準の最後 28。 */
    static final double BODY_Y = 22.0D;
    static final double BODY_Y_AIM = 28.0D;
    /** 照準の最後の姿勢での針の付け根(needle_aim の軸)。原点の 9.42 上、0.63 後ろ。 */
    public static final double NEEDLE_PIVOT_Y = 9.42D;
    public static final double NEEDLE_PIVOT_BACK = 0.63D;
    /** 針の付け根から弾を出す点(muzzle)までの長さ。 */
    public static final double NEEDLE_LEN = 44.3D;
    /** 針を下へ向けられる上限(度)。75 度で返しが原点の 2.1 ブロック下(体格 1 のとき)。 */
    public static final float MAX_DEPRESSION = 75.0F;
    /** 縦回しに要る下の空き(ブロック、体格 1 のとき 2.5。体格を掛けて使う)。 */
    static final double AIM_V_CLEARANCE = 2.5D;

    // ------------------------------------------------------------------ 間合い(仮)

    static final double HOVER_MIN_R = 11.0D;
    static final double HOVER_MAX_R = 17.0D;
    static final double HOVER_MIN_H = 6.0D;
    static final double HOVER_MAX_H = 9.0D;
    /** 撃つ距離の範囲(ブロック)。 */
    static final double SHOOT_MIN = 5.0D;
    static final double SHOOT_MAX = 40.0D;
    /** 棘の速さ(ブロック/tick)。 */
    static final double SPINE_SPEED = 3.0D;
    /** 体の向きの変化の上限(度/tick = 毎秒 120 度)。針が 4 ブロック後ろへ伸びているので、速く回すと針先が振り回される。 */
    static final float MAX_TURN = 6.0F;

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.INT);
    /** 同じ動作を出し直したことをクライアントへ知らせる番号。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.INT);
    /** 動作を始めたゲーム時刻(クライアントがアニメの経過時間を出す)。 */
    private static final EntityDataAccessor<Integer> DATA_ACTION_TIME =
            SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.INT);
    /** 狙っている相手のエンティティ id(針の俯角に使う)。居なければ −1。 */
    private static final EntityDataAccessor<Integer> DATA_AIM_TARGET =
            SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("hover");
    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation AIM = RawAnimation.begin().thenPlay("aim").thenLoop("aim_hold");
    private static final RawAnimation AIM_V = RawAnimation.begin().thenPlay("aim_v").thenLoop("aim_hold");
    private static final RawAnimation FIRE = RawAnimation.begin().thenPlayAndHold("fire");
    private static final RawAnimation RELOAD = RawAnimation.begin().thenPlayAndHold("reload");
    private static final RawAnimation RELOAD_V = RawAnimation.begin().thenPlayAndHold("reload_v");
    private static final RawAnimation CANCEL = RawAnimation.begin().thenPlayAndHold("aim_cancel");
    private static final RawAnimation CANCEL_V = RawAnimation.begin().thenPlayAndHold("aim_cancel_v");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");
    private static final RawAnimation DEATH_AIM = RawAnimation.begin().thenPlayAndHold("death_aim");
    private static final RawAnimation WINGS = RawAnimation.begin().thenLoop("wings_buzz");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** サーバー: 今の動作の経過 tick。 */
    private int actionTicks;
    /** サーバー: 次に狙えるまでの tick。 */
    private int cooldown = 40;
    /** サーバー: 射線が切れ続けている tick。 */
    private int losLost;
    /** サーバー: 狙いの縦回しか(装填と取りやめも同じ回し方にそろえる)。 */
    private boolean aimVertical;
    /** サーバー: 向けたい向き(度)。NaN なら今の向きのまま。 */
    private float wantedYaw = Float.NaN;

    /** クライアント: 前の tick の針の俯角(補間用)。 */
    private float clientDepressionO;
    private float clientDepression;
    /** クライアント: 見えている動作の番号(同じ動作の出し直しでアニメを最初から流すため)。 */
    private int clientSeq;

    public AcumnesEntity(EntityType<? extends AcumnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("acumnes", 40);
        this.moveControl = new HoverMoveControl(this);
        this.lookControl = new FixedLookControl(this);
        this.setNoGravity(true);
        this.setPathfindingMalus(PathType.DANGER_FIRE, -1.0F);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 16.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "acumnes");
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level) {
            @Override
            public boolean isStableDestination(BlockPos pos) {
                return true;
            }

            /** 経路の点の高さをそのまま使う(床へ吸い寄せない。OvergrownFlyerEntity と同じ)。 */
            @Override
            protected double getGroundY(Vec3 vec) {
                return vec.y;
            }
        };
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(false);
        navigation.setCanPassDoors(true);
        return navigation;
    }

    /** 体は常に {@code yRot} の向き(サーバーでもクライアントでも)。バニラは動く向きへ体を回してしまう。 */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                Mob mob = AcumnesEntity.this;
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
            }
        };
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_ACTION_TIME, 0);
        builder.define(DATA_AIM_TARGET, -1);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new SnipeGoal(this));
        this.goalSelector.addGoal(6, new HoverWanderGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        ParasitizedTargetGoal.installTargeting(this, 2);
    }

    // ------------------------------------------------------------------ 状態

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public boolean isActing() {
        return getAction() != ACT_NONE;
    }

    /** クライアント: 今の動作を始めてからの経過(tick、partialTick 込み)。 */
    public float actionAge(float partialTick) {
        return (float) (this.level().getGameTime() - this.entityData.get(DATA_ACTION_TIME)) + partialTick;
    }

    @Nullable
    public Entity getAimTarget() {
        int id = this.entityData.get(DATA_AIM_TARGET);
        return id < 0 ? null : this.level().getEntity(id);
    }

    public boolean isAiming() {
        int a = getAction();
        return a == ACT_AIM || a == ACT_AIM_V;
    }

    private void startAction(int action) {
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.entityData.set(DATA_ACTION_TIME, (int) this.level().getGameTime());
        this.actionTicks = 0;
    }

    private void endAction(int cooldownTicks) {
        this.entityData.set(DATA_ACTION, ACT_NONE);
        this.entityData.set(DATA_AIM_TARGET, -1);
        this.actionTicks = 0;
        this.cooldown = cooldownTicks;
    }

    // ------------------------------------------------------------------ 毎 tick

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide()) {
            this.clientDepressionO = this.clientDepression;
            this.clientDepression = computeDepression();
            return;
        }
        if (this.isDeadOrDying()) {
            return;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        tickAction();
        tickTurn();
    }

    /** 動作の進行(サーバー)。 */
    private void tickAction() {
        int act = getAction();
        if (act == ACT_NONE) {
            return;
        }
        this.actionTicks++;
        LivingEntity target = getTarget();
        switch (act) {
            case ACT_AIM, ACT_AIM_V -> {
                holdPosition();
                if (target == null || !target.isAlive() || distanceTo(target) > SHOOT_MAX * 1.2D) {
                    cancelAim();
                    return;
                }
                faceTowards(target);
                this.losLost = hasShotLine(target) ? 0 : this.losLost + 1;
                if (this.losLost > LOS_GRACE) {
                    cancelAim();
                    return;
                }
                if (this.actionTicks >= AIM_TICKS) {
                    startAction(ACT_FIRE);
                    this.entityData.set(DATA_AIM_TARGET, target.getId());
                }
            }
            case ACT_FIRE -> {
                holdPosition();
                if (this.actionTicks == FIRE_SHOT_TICK && target != null && target.isAlive()) {
                    shoot(target);
                }
                if (this.actionTicks >= FIRE_TICKS) {
                    startAction(this.aimVertical && clearBelow() ? ACT_RELOAD_V : ACT_RELOAD);
                    this.entityData.set(DATA_AIM_TARGET, -1);
                }
            }
            case ACT_RELOAD, ACT_RELOAD_V -> {
                if (this.actionTicks >= RELOAD_TICKS) {
                    endAction(COOLDOWN_TICKS);
                }
            }
            case ACT_CANCEL, ACT_CANCEL_V -> {
                if (this.actionTicks >= CANCEL_TICKS) {
                    endAction(CANCEL_COOLDOWN_TICKS);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 照準の取りやめ。狙い始めて 0.5 秒(アニメの時刻)までは腹も針もまだ静止に近いので、そのまま待機へ戻す。
     * それより後は aim_cancel / aim_cancel_v で戻す(直接替えると、補間の間に針が体の真下を縦に振り抜ける。NOTES の 6)。
     */
    private void cancelAim() {
        float animT = (this.actionTicks - TRANSITION_TICKS) / 20.0F;
        this.entityData.set(DATA_AIM_TARGET, -1);
        if (animT < 0.5F) {
            endAction(CANCEL_COOLDOWN_TICKS);
        } else {
            startAction(getAction() == ACT_AIM_V ? ACT_CANCEL_V : ACT_CANCEL);
        }
    }

    /** テスト用: 標的を決めて、すぐ狙い始める(AI を止めた個体でも動作は {@link #aiStep} で進む)。 */
    public void forceAim(LivingEntity target) {
        this.setTarget(target);
        this.cooldown = 0;
        startAim(target);
    }

    /** 狙いを始める(サーバー、SnipeGoal から)。 */
    void startAim(LivingEntity target) {
        this.aimVertical = clearBelow();
        this.losLost = 0;
        startAction(this.aimVertical ? ACT_AIM_V : ACT_AIM);
        this.entityData.set(DATA_AIM_TARGET, target.getId());
        this.getNavigation().stop();
        // 照準の合図の音(ガーディアンの光線の溜めの音。仮 —— 専用の音を作るまで)
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GUARDIAN_ATTACK,
                SoundSource.HOSTILE, 2.0F, 1.3F);
    }

    /** 下に縦回しができるだけの空きがあるか(針が原点の 1.7 ブロック下まで振れる。体格を掛ける)。 */
    private boolean clearBelow() {
        double need = AIM_V_CLEARANCE * this.getScale();
        Vec3 from = this.position();
        BlockHitResult hit = this.level().clip(new ClipContext(from, from.add(0.0D, -need, 0.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** 胸から標的の胴までの射線が通っているか(針先ではなく胸から見る —— 針先は胴の 2 ブロック前で、壁の向こうへ出ることがある)。 */
    boolean hasShotLine(Entity target) {
        Vec3 from = chestPosition();
        Vec3 to = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        return this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    Vec3 chestPosition() {
        double up = (isAiming() || getAction() == ACT_FIRE ? BODY_Y_AIM : BODY_Y) * this.getScale() / 16.0D;
        return this.position().add(0.0D, up, 0.0D);
    }

    /** 照準の最後の姿勢での針の付け根のワールド位置(体の向きの前後に、付け根の後ろへのずれを入れる)。 */
    public Vec3 needlePivot(Vec3 origin, float yaw) {
        double s = this.getScale() / 16.0D;
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        return origin.add(0.0D, NEEDLE_PIVOT_Y * s, 0.0D).subtract(forward.scale(NEEDLE_PIVOT_BACK * s));
    }

    /** 撃つ(fire の 0.1 秒)。弾は針先から、撃った瞬間の標的の胴へ向けて出す。 */
    private void shoot(LivingEntity target) {
        double s = this.getScale() / 16.0D;
        Vec3 pivot = needlePivot(this.position(), this.getYRot());
        Vec3 aimAt = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        Vec3 dir = aimAt.subtract(pivot).normalize();
        Vec3 tip = pivot.add(dir.scale(NEEDLE_LEN * s));
        // 胸から針先までがブロックで塞がっていたら、塞がった所の手前から出す(壁の向こうから撃たない)
        BlockHitResult blocked = this.level().clip(new ClipContext(chestPosition(), tip,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (blocked.getType() != HitResult.Type.MISS) {
            tip = blocked.getLocation().subtract(dir.scale(0.2D));
        }
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        AcumnesSpine spine = new AcumnesSpine(this.level(), this, tip, dir.scale(SPINE_SPEED), damage);
        this.level().addFreshEntity(spine);
        this.level().playSound(null, tip.x, tip.y, tip.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.HOSTILE, 1.6F, 0.6F);
    }

    // ------------------------------------------------------------------ 向き・移動

    /** 向けたい向きを予約する(毎 tick の終わりに {@link #tickTurn} が上限つきで回す)。 */
    void faceTowards(Entity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        if (dx * dx + dz * dz > 1.0E-4D) {
            this.wantedYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        }
    }

    void faceDirection(Vec3 v) {
        if (v.horizontalDistanceSqr() > 1.0E-4D) {
            this.wantedYaw = (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG) - 90.0F;
        }
    }

    private void tickTurn() {
        if (!Float.isNaN(this.wantedYaw)) {
            float yaw = Mth.approachDegrees(this.getYRot(), this.wantedYaw, MAX_TURN);
            this.setYRot(yaw);
            this.wantedYaw = Float.NaN;
        }
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
    }

    /** 狙っている間はその場に留まる(移動の予約を消し、速さを落とす)。 */
    private void holdPosition() {
        this.getNavigation().stop();
        this.getMoveControl().setWantedPosition(this.getX(), this.getY(), this.getZ(), 0.0D);
        this.setDeltaMovement(this.getDeltaMovement().scale(0.6D));
    }

    /**
     * 空中では重力なしで、速度だけで動く({@link HoverMoveControl} が速度を寄せる)。死んだら重力を戻して地面まで落とす
     * (death・death_aim は最後の姿勢の最下点が地面の少し上に来るよう作ってある)。
     * <b>{@code isControlledByLocalInstance()} の囲みは必須</b>(ヴェロフィルと同じ。無いとクライアントでガクガク震える)。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (!this.isNoGravity()) {
            super.travel(travelVector);
            return;
        }
        if (this.isControlledByLocalInstance()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().scale(0.91D));
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

    // ------------------------------------------------------------------ 倒れる

    /**
     * 倒れ方を今の動作で選ぶ(NOTES の 6 を縮めたもの)。腹を巻いて針を前へ向けた姿勢(照準の後半・撃った直後・装填の前半・取りやめの前半)から
     * death(ほぼ静止の姿勢から始まる)へ替えると、補間の間に針が体の真下を振り抜けるので、そのときは death_aim(横倒し)。
     */
    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide()) {
            int act = getAction();
            float animT = (this.actionTicks - TRANSITION_TICKS) / 20.0F;
            boolean curled = switch (act) {
                case ACT_AIM, ACT_AIM_V -> animT >= 0.5F;
                case ACT_FIRE -> true;
                case ACT_RELOAD, ACT_RELOAD_V, ACT_CANCEL, ACT_CANCEL_V -> animT < 0.35F;
                default -> false;
            };
            startAction(curled ? ACT_DEATH_AIM : ACT_DEATH);
            this.entityData.set(DATA_AIM_TARGET, -1);
            this.setNoGravity(false);
            this.getNavigation().stop();
        }
        super.die(source);
    }

    /** 倒れるアニメの間は消さない。バニラの横倒しは描画で切ってある({@code AcumnesRenderer})。 */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= DEATH_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 音(仮: バニラの音を低く鳴らす。専用の音は未作成)

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.BEE_LOOP_AGGRESSIVE;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.BEE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.BEE_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.5F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }

    @Override
    protected float getSoundVolume() {
        return 1.6F;
    }

    // ------------------------------------------------------------------ 描画まわり

    /**
     * 針・翅・触角が当たり判定の外へ長く伸びるので、描画の打ち切りは広めに(NOTES: 体格 1 で水平 4.0・上下 2.5)。
     * これが無いと、見上げたときに自分へ向いた針先ごと消える。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double s = this.getScale();
        return super.getBoundingBoxForCulling().inflate(4.0D * s, 2.5D * s, 4.0D * s);
    }

    /** 上空から撃つ種なので遠くまで描く(遠くから狙われているのが見えるように)。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128.0D * 128.0D;
    }

    /**
     * クライアント: 針の俯角(度、下向きが +)。照準の 1.35 秒(針が真正面で止まる)から 0.15 秒で寄せ、fire の 0.35 秒から 0.3 秒で 0 へ戻す
     * (NOTES の 4。aim / aim_v の針の IK は俯角 0 の前提で焼いてあるので、それより前に入れると溜めの間に針が跳ね上がる)。
     */
    private float computeDepression() {
        int act = getAction();
        float w;
        if (act == ACT_AIM || act == ACT_AIM_V) {
            float animT = (actionAge(0.0F) - TRANSITION_TICKS) / 20.0F;
            w = smoothstep((animT - 1.35F) / 0.15F);
        } else if (act == ACT_FIRE) {
            float animT = actionAge(0.0F) / 20.0F;
            w = 1.0F - smoothstep((animT - 0.35F) / 0.3F);
        } else {
            return this.clientDepression * 0.6F;
        }
        Entity target = getAimTarget();
        if (target == null) {
            return this.clientDepression * w;
        }
        Vec3 pivot = needlePivot(this.position(), this.getYRot());
        Vec3 aimAt = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        double dy = pivot.y - aimAt.y;
        double h = Math.sqrt(Mth.square(aimAt.x - pivot.x) + Mth.square(aimAt.z - pivot.z));
        float dep = (float) (Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
        return Mth.clamp(dep, 0.0F, MAX_DEPRESSION) * w;
    }

    /** クライアント: 描画用の針の俯角(度)。 */
    public float getNeedleDepression(float partialTick) {
        return Mth.lerp(partialTick, this.clientDepressionO, this.clientDepression);
    }

    static float smoothstep(float u) {
        float x = Mth.clamp(u, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    // ------------------------------------------------------------------ 保存

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("AcumnesCooldown", this.cooldown);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.cooldown = tag.getInt("AcumnesCooldown");
        this.setNoGravity(true);
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * 層は 2 つ: {@code main}(動作・移動)と {@code wings}(羽ばたきの wings_buzz。後に登録 = 翅の buzz の骨はこちらが勝つ)。
     * 動作のアニメからは羽ばたきを抜いてある(生成時に分けた。animation.json を小さくするため)。death・death_aim は自分で羽ばたきを止めるので、
     * 死んだら wings の層を止める。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, this::mainState));
        controllers.add(new AnimationController<>(this, "wings", 0, this::wingsState));
    }

    private PlayState mainState(AnimationState<AcumnesEntity> state) {
        AnimationController<AcumnesEntity> c = state.getController();
        int act = getAction();
        // aim → fire だけ補間 0(fire の 0 秒目は照準の最後と同じ姿勢。補間すると棘が消える前に弾が飛んで見える)
        c.transitionLength(act == ACT_FIRE ? 0 : TRANSITION_TICKS);
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientSeq) {
            this.clientSeq = seq;
            if (act != ACT_NONE) {
                c.forceAnimationReset();
            }
        }
        if (this.isDeadOrDying()) {
            return state.setAndContinue(act == ACT_DEATH_AIM ? DEATH_AIM : DEATH);
        }
        switch (act) {
            case ACT_AIM:
                return state.setAndContinue(AIM);
            case ACT_AIM_V:
                return state.setAndContinue(AIM_V);
            case ACT_FIRE:
                return state.setAndContinue(FIRE);
            case ACT_RELOAD:
                return state.setAndContinue(RELOAD);
            case ACT_RELOAD_V:
                return state.setAndContinue(RELOAD_V);
            case ACT_CANCEL:
                return state.setAndContinue(CANCEL);
            case ACT_CANCEL_V:
                return state.setAndContinue(CANCEL_V);
            case ACT_DEATH_AIM:
                return state.setAndContinue(DEATH_AIM);
            case ACT_DEATH:
                return state.setAndContinue(DEATH);
            default:
                break;
        }
        // 移動: この tick に水平に進んだ量で fly / hover / idle を選ぶ(クライアントの速度は当てにならないので位置の差で見る)
        double moved = Math.sqrt(Mth.square(this.getX() - this.xo) + Mth.square(this.getZ() - this.zo));
        if (moved > 0.16D) {
            return state.setAndContinue(FLY);
        }
        return state.setAndContinue(this.isAggressive() || moved > 0.03D ? HOVER : IDLE);
    }

    private PlayState wingsState(AnimationState<AcumnesEntity> state) {
        if (this.isDeadOrDying()) {
            return PlayState.STOP;
        }
        return state.setAndContinue(WINGS);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ================================================================== 制御

    /**
     * 速度を行き先へ寄せるだけの移動(向きは変えない —— 標的を向いたまま横へ漂える)。バニラの FlyingMoveControl は
     * 進む向きへ 1 tick に 90 度まで体を回すので、長い針が振り回される。
     */
    static final class HoverMoveControl extends MoveControl {
        HoverMoveControl(Mob mob) {
            super(mob);
        }

        @Override
        public void tick() {
            if (this.operation != Operation.MOVE_TO) {
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().scale(0.8D));
                return;
            }
            Vec3 d = new Vec3(this.wantedX - this.mob.getX(), this.wantedY - this.mob.getY(), this.wantedZ - this.mob.getZ());
            double dist = d.length();
            if (dist < 0.4D || this.speedModifier <= 0.0D) {
                this.operation = Operation.WAIT;
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().scale(0.7D));
                return;
            }
            // 最高速度(ブロック/tick)= FLYING_SPEED × 速度倍率 × 0.5。近づくほど緩める
            double max = this.mob.getAttributeValue(Attributes.FLYING_SPEED) * this.speedModifier * 0.5D;
            double speed = Math.min(max, dist * 0.12D);
            Vec3 want = d.scale(speed / dist);
            Vec3 v = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(v.add(want.subtract(v).scale(0.15D)));
            if (this.mob instanceof AcumnesEntity a && !a.isActing() && a.getTarget() == null) {
                a.faceDirection(want);
            }
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
     * 狙撃の Goal(標的が居る間ずっと動く)。動作の最中は何もしない({@link #tickAction} が動かす)。
     * 動作していない間は、撃てる位置(標的の上空・射線が通る点)を選んで飛び、着いて冷めていれば狙い始める。
     */
    static final class SnipeGoal extends Goal {
        private final AcumnesEntity mob;
        @Nullable
        private Vec3 spot;
        private int repick;

        SnipeGoal(AcumnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = this.mob.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() || this.mob.isActing();
        }

        @Override
        public void start() {
            this.spot = null;
            this.repick = 0;
            this.mob.setAggressive(true);
        }

        @Override
        public void stop() {
            this.mob.setAggressive(false);
            this.mob.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = this.mob.getTarget();
            if (this.mob.isActing() || t == null) {
                return;
            }
            this.mob.faceTowards(t);
            if (--this.repick <= 0 || this.spot == null) {
                this.spot = pickSpot(t);
                this.repick = 60 + this.mob.getRandom().nextInt(40);
            }
            double toSpot = this.mob.position().distanceTo(this.spot);
            if (toSpot > 1.5D) {
                // 近ければ経路を使わず直接(細かい位置合わせ)、遠ければ経路で木を避ける
                if (toSpot < 6.0D) {
                    this.mob.getNavigation().stop();
                    this.mob.getMoveControl().setWantedPosition(this.spot.x, this.spot.y, this.spot.z, 1.0D);
                } else if (this.mob.getNavigation().isDone()) {
                    this.mob.getNavigation().moveTo(this.spot.x, this.spot.y, this.spot.z, 1.0D);
                }
            }
            double dist = this.mob.distanceTo(t);
            boolean inRange = dist >= SHOOT_MIN && dist <= SHOOT_MAX;
            if (this.mob.cooldown <= 0 && toSpot < 3.0D && inRange && this.mob.hasShotLine(t)) {
                this.mob.startAim(t);
                this.spot = null;
            }
        }

        /** 撃つ位置を選ぶ: 今いる方角を基準に、標的の上空の射線が通る空いた点。見つからなければ今の位置の少し上。 */
        private Vec3 pickSpot(LivingEntity t) {
            double base = Math.atan2(this.mob.getZ() - t.getZ(), this.mob.getX() - t.getX());
            for (int i = 0; i < 12; i++) {
                double ang = base + (i == 0 ? 0.0D : (this.mob.getRandom().nextDouble() - 0.5D) * Math.PI * (0.6D + i * 0.1D));
                double r = HOVER_MIN_R + this.mob.getRandom().nextDouble() * (HOVER_MAX_R - HOVER_MIN_R);
                double h = HOVER_MIN_H + this.mob.getRandom().nextDouble() * (HOVER_MAX_H - HOVER_MIN_H);
                Vec3 p = t.position().add(Math.cos(ang) * r, h, Math.sin(ang) * r);
                AABB box = this.mob.getBoundingBox().move(p.subtract(this.mob.position()));
                if (!this.mob.level().noCollision(this.mob, box)) {
                    continue;
                }
                Vec3 chest = p.add(0.0D, BODY_Y_AIM * this.mob.getScale() / 16.0D, 0.0D);
                Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
                if (this.mob.level().clip(new ClipContext(chest, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob))
                        .getType() == HitResult.Type.MISS) {
                    return p;
                }
            }
            return this.mob.position().add(0.0D, 2.0D, 0.0D);
        }
    }

    /** 標的が居ない間: 地面から 3〜8 ブロック上をゆっくり漂う。 */
    static final class HoverWanderGoal extends Goal {
        private final AcumnesEntity mob;

        HoverWanderGoal(AcumnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        /** 地面に近い(湧いた直後など)ときはすぐ浮き上がる —— 待機の姿では垂れた針先が足元の 0.5 ブロック下まで出る(体格 1.8)。 */
        @Override
        public boolean canUse() {
            return this.mob.getTarget() == null && !this.mob.isActing()
                    && this.mob.getNavigation().isDone() && (this.mob.getRandom().nextInt(80) == 0 || nearGround());
        }

        private boolean nearGround() {
            Vec3 from = this.mob.position();
            return this.mob.level().clip(new ClipContext(from, from.add(0.0D, -2.0D, 0.0D),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this.mob)).getType() != HitResult.Type.MISS;
        }

        @Override
        public boolean canContinueToUse() {
            return this.mob.getTarget() == null && !this.mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            for (int i = 0; i < 10; i++) {
                double x = this.mob.getX() + (this.mob.getRandom().nextDouble() - 0.5D) * 24.0D;
                double z = this.mob.getZ() + (this.mob.getRandom().nextDouble() - 0.5D) * 24.0D;
                BlockPos ground = this.mob.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(x, 0, z));
                double y = ground.getY() + 3.0D + this.mob.getRandom().nextDouble() * 5.0D;
                AABB box = this.mob.getBoundingBox().move(new Vec3(x, y, z).subtract(this.mob.position()));
                if (this.mob.level().noCollision(this.mob, box)) {
                    this.mob.getNavigation().moveTo(x, y, z, 0.6D);
                    return;
                }
            }
        }
    }
}
