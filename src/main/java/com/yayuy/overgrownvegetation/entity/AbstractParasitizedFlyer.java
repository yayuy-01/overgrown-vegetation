package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;

/**
 * ネザーの空を漂う媒介種(寄生されたガスト・ブレイズ、2026-10-09)の共通部分。
 *
 * <h2>ユーザー指定(10-09)</h2>
 * <ul>
 *     <li>ガスト・ブレイズの寄生体は<b>死体を使った生き物</b>で、<b>胞子嚢で無理やり浮かされている</b>(胞子嚢はペディキュラが手本)。</li>
 *     <li>死体ブロックは作らない。元の Mob が胞子感染で死んだ<b>その瞬間に寄生された見た目になり</b>
 *     ({@link com.yayuy.overgrownvegetation.corpse.NetherReanimation})、<b>15 秒は何もせず死んだ場所で漂い</b>、
 *     <b>目を覚ますようにして</b>活動を始める({@link #DORMANT_TICKS}・{@link #WAKE_TICKS})。</li>
 *     <li>攻撃は火の玉ではなく<b>胞子爆弾</b>({@link VelophyllBombEntity} をその種の {@code mobs.json} の {@code bomb} の数値で撃つ)。
 *     ダメージは元の攻撃と同じくらい、<b>燃える代わりに毒</b>が乗る。</li>
 *     <li>全種燃える(耐火を持たない)。</li>
 * </ul>
 *
 * <p>動き方はバニラのガストに倣う: 行き先へ向けて数 tick おきに速度を足す({@link FloatMoveControl})、行き先はふらふら選び直す
 * ({@link FloatAroundGoal}。標的がいればそのまわりの撃ちやすい所)。索敵は G1 共通の {@link ParasitizedTargetGoal}(種族を問わず近くの生き物)。
 *
 * <p>眠っている間(dormant・wake)は {@link #isNoAi} を立てて AI を止める。AI を止めた Mob はバニラでは動かない
 * ({@code FlyingMob#travel} は {@code isControlledByLocalInstance} を見る)ので、漂いは {@link #driftWhileDormant} が自分で少しずつ動かす。
 */
public abstract class AbstractParasitizedFlyer extends FlyingMob implements Enemy, ParasitizedMob, GeoEntity {

    /** 死んだ場所で漂う長さ(15 秒、ユーザー指定)。 */
    public static final int DORMANT_TICKS = 300;
    /** 目覚めのアニメ wake(2.0 秒)+ 切り替えの補間 6 tick。この間も AI は止めたまま。 */
    public static final int WAKE_TICKS = 46;
    private static final int WAKE_TRANSITION = 6;

    protected static final RawAnimation DORMANT_ANIM = RawAnimation.begin().thenLoop("dormant");
    protected static final RawAnimation WAKE_ANIM = RawAnimation.begin().thenPlayAndHold("wake");
    protected static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    protected static final RawAnimation CHARGE_ANIM = RawAnimation.begin().thenPlayAndHold("charge");
    protected static final RawAnimation SPIT_ANIM = RawAnimation.begin().thenPlay("spit");

    /**
     * 眠りの段階(クライアント向け)。サーバーが数える正確な残り {@link #dormantLeft} を、段階が変わるときだけ送る
     * (漂い中は眠り始めの値・目覚め中は {@link #WAKE_TICKS}・起きたら 0)。クライアントは段階しか見ないので見た目は同じ。
     * 前は残りを毎 tick 送っていた(漂い 15 秒の間ずっと 1 tick 1 回の通信。2026-10-10、0.1.9 の前の軽量化)。
     */
    /** 眠りの残り tick(漂う 300 + 目覚め {@link #WAKE_TICKS})。0 なら起きている。 */
    private static final EntityDataAccessor<Integer> DORMANT =
            SynchedEntityData.defineId(AbstractParasitizedFlyer.class, EntityDataSerializers.INT);
    /** 撃つ前のため(アニメ charge)。 */
    private static final EntityDataAccessor<Boolean> CHARGING =
            SynchedEntityData.defineId(AbstractParasitizedFlyer.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 漂う向き(ラジアン)。眠っている間だけ使う。 */
    private float driftHeading = Float.NaN;
    /** 眠りの正確な残り tick(サーバーだけが数える。{@link #DORMANT} は段階だけ)。 */
    private int dormantLeft;

    protected AbstractParasitizedFlyer(EntityType<? extends AbstractParasitizedFlyer> type, Level level) {
        super(type, level);
        this.moveControl = new FloatMoveControl(this);
        ParasitizedTargetGoal.installTargeting(this, 1);
    }

    // ------------------------------------------------------------------ 種ごとの値
    /** 爆弾の数値を引く {@code mobs.json} の id(自分の id)。 */
    protected abstract String statsId();

    /** 撃つ前のため(アニメ charge と同じ長さ。ガスト 20・ブレイズ 60 tick)。 */
    protected abstract int chargeTicks();

    /**
     * 待機からためへ移るアニメの補間(tick)。補間のぶんためのアニメは遅れて流れる(撃つ時はアニメの「長さ − 補間」秒)。
     * ブレイズは待機の棒の揺れが大きいので 8(10-09 夜)。
     */
    protected int chargeTransitionTicks() {
        return 4;
    }

    /** 爆弾を吐く口の位置(体の向きの前へ {@code forward}、足元から上へ {@code up}、ブロック)。 */
    protected abstract double mouthForward();

    protected abstract double mouthUp();

    /** 標的のまわりで漂う距離の範囲(ブロック)。 */
    protected abstract double keepMin();

    protected abstract double keepMax();

    /** 標的より上に浮く高さの範囲(ブロック)。 */
    protected abstract double hoverMin();

    protected abstract double hoverMax();

    /** これより近ければ殴る(距離の 2 乗。0 なら殴らない)。 */
    protected double meleeRangeSqr() {
        return 0.0D;
    }

    /** 自然に湧いたときに床から持ち上げる最大の高さ(ブロック)。 */
    protected abstract int spawnLift();

    /** 当たり判定より上・下へはみ出して描かれる量(ブロック)。胞子嚢は上、ガストの触手は下へはみ出す。 */
    protected abstract double drawnAbove();

    protected abstract double drawnBelow();

    /** 描画の間引きの箱を、はみ出して描かれる胞子嚢・触手のぶん広げる(当たり判定が画面の外でも胞子嚢が見えていれば描く)。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        AABB box = super.getBoundingBoxForCulling();
        return new AABB(box.minX, box.minY - drawnBelow(), box.minZ, box.maxX, box.maxY + drawnAbove(), box.maxZ);
    }

    /** 撃ったときの音。 */
    protected abstract SoundEvent shootSound();

    /** ためを始めたときの音(無ければ null)。 */
    @Nullable
    protected SoundEvent warnSound() {
        return null;
    }

    // ------------------------------------------------------------------ 眠り
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DORMANT, 0);
        builder.define(CHARGING, false);
    }

    /** 死んだ元の Mob から入れ替わったところ: 15 秒漂ってから目覚める。 */
    public void startDormant() {
        this.dormantLeft = DORMANT_TICKS + WAKE_TICKS;
        this.entityData.set(DORMANT, this.dormantLeft);
    }

    /** 漂っているか目覚めの途中か。 */
    public boolean isDormant() {
        return dormantTicks() > 0;
    }

    /** 目覚めの途中か(アニメ wake)。 */
    public boolean isWaking() {
        int d = dormantTicks();
        return d > 0 && d <= WAKE_TICKS;
    }

    /** 眠りの残り tick(サーバーは正確な値、クライアントは段階の値 —— 「漂い中 > WAKE_TICKS ≥ 目覚め中 > 0」の比べ方なら同じ答え)。 */
    public int dormantTicks() {
        return this.level().isClientSide() ? this.entityData.get(DORMANT) : this.dormantLeft;
    }

    public boolean isCharging() {
        return this.entityData.get(CHARGING);
    }

    void setCharging(boolean charging) {
        this.entityData.set(CHARGING, charging);
    }

    /** 眠っている間は AI を止める(Mob#isEffectiveAi が見る)。 */
    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isDormant();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            // 胞子嚢からこぼれる胞子(バニラのブレイズの煙の代わり)。短命版の粒。
            if (this.tickCount % 5 == 0) {
                this.level().addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.OVERGROWN_SPORE_SHORT.get(),
                        this.getRandomX(0.7D), this.getY() + this.getBbHeight() * (0.55D + this.random.nextDouble() * 0.5D),
                        this.getRandomZ(0.7D), 0.0D, -0.02D, 0.0D);
            }
            return;
        }
        {
            int d = this.dormantLeft;
            if (d > 0) {
                if (d > WAKE_TICKS && this.isAlive()) {
                    driftWhileDormant(d);
                }
                this.dormantLeft = d - 1;
                if (this.dormantLeft == WAKE_TICKS || this.dormantLeft == 0) {
                    this.entityData.set(DORMANT, this.dormantLeft); // 段階が変わるときだけ送る(目覚め始め・起きた)
                }
            }
        }
    }

    /**
     * 死んだ場所でゆっくり漂う(ユーザー指定「15 秒間は何もせず死んだ場所で漂うだけ」)。上下にふわふわ ±0.25、横へは 15 秒で 1.5 ブロックほど流れる。
     * AI を止めているので速度ではなく位置を直接動かす(バニラの 0.003 の切り捨ても通らない)。
     */
    private void driftWhileDormant(int remaining) {
        if (Float.isNaN(this.driftHeading)) {
            this.driftHeading = this.random.nextFloat() * Mth.TWO_PI;
        }
        this.driftHeading += (this.random.nextFloat() - 0.5F) * 0.08F;
        double t = (DORMANT_TICKS + WAKE_TICKS - remaining) / 20.0D;
        double bob = 0.25D * 2.0D * Math.PI / 4.0D * Math.cos(2.0D * Math.PI * t / 4.0D) / 20.0D;
        double side = 0.005D;
        this.move(MoverType.SELF, new Vec3(Mth.cos(this.driftHeading) * side, bob, Mth.sin(this.driftHeading) * side));
    }

    /** 眠っている間は鳴かない。 */
    @Override
    public int getAmbientSoundInterval() {
        return isDormant() ? Integer.MAX_VALUE / 2 : super.getAmbientSoundInterval();
    }

    @Override
    public void playAmbientSound() {
        if (!isDormant()) {
            super.playAmbientSound();
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Dormant", this.dormantLeft);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.dormantLeft = tag.getInt("Dormant");
        this.entityData.set(DORMANT, this.dormantLeft);
    }

    // ------------------------------------------------------------------ 湧き
    /** 自然に湧いたら床から少し浮かせる(ネザーの湧かせ役は床の上に置く。ネザーの天井は高さ地図で見られないので空の経路は使わない)。 */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
                                        @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        if (spawnType == MobSpawnType.NATURAL) {
            int lift = 1 + this.random.nextInt(Math.max(1, spawnLift()));
            for (int i = 0; i < lift; i++) {
                AABB next = this.getBoundingBox().move(0.0D, 1.0D, 0.0D);
                if (!level.noCollision(this, next)) {
                    break;
                }
                this.setPos(this.getX(), this.getY() + 1.0D, this.getZ());
            }
        }
        return data;
    }

    /**
     * 空の経路(地上の経路にしない)。動きは {@link FloatMoveControl} で経路は使わない。G1 共通の索敵が「たどり着けるか」を
     * 経路探索で測っていた頃の名残で、今は {@link #canStillEngage} で測る(経路探索は使わない)。
     */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        net.minecraft.world.entity.ai.navigation.FlyingPathNavigation navigation =
                new net.minecraft.world.entity.ai.navigation.FlyingPathNavigation(this, level) {
                    @Override
                    public boolean isStableDestination(net.minecraft.core.BlockPos pos) {
                        return true;
                    }
                };
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        return navigation;
    }

    // ------------------------------------------------------------------ 攻撃
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(5, new FloatAroundGoal(this));
        this.goalSelector.addGoal(7, new FaceGoal(this));
        this.goalSelector.addGoal(7, new SporeBombVolleyGoal(this));
    }

    /** 爆弾の数値(この種の {@code mobs.json} の {@code bomb})。 */
    MobStat.Bomb bomb() {
        return MobStats.bomb(statsId());
    }

    /** 爆弾を吐く口の位置。 */
    Vec3 mouthPosition() {
        float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
        double f = mouthForward();
        return new Vec3(this.getX() - Mth.sin(yaw) * f, this.getY() + mouthUp(), this.getZ() + Mth.cos(yaw) * f);
    }

    /** 1 発撃つ。 */
    void shootAt(LivingEntity target) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        MobStat.Bomb cfg = bomb();
        Vec3 origin = mouthPosition();
        Vec3 aim = new Vec3(target.getX(), target.getY(0.5D), target.getZ());
        Vec3 velocity = SporeBombAim.solve(aim.subtract(origin), cfg.launchSpeed());
        level.addFreshEntity(new VelophyllBombEntity(level, origin, velocity, this, (float) cfg.bombSize()).withStats(statsId()));
        this.playSound(shootSound(), 2.0F, 0.75F + this.random.nextFloat() * 0.1F);
        this.triggerAnim("attack", "spit");
    }

    /** 殴ったら毒を乗せる(ユーザー指定「攻撃には毒が乗る」)。長さ・強さは爆弾の表と同じ。 */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            MobStat.Bomb cfg = bomb();
            int ticks = (int) Math.round(cfg.poisonSeconds() * 20.0D);
            if (ticks > 0) {
                living.addEffect(new MobEffectInstance(MobEffects.POISON, ticks, cfg.poisonAmplifier()), this);
            }
        }
        return hit;
    }

    // ------------------------------------------------------------------ アニメ
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", WAKE_TRANSITION, state -> {
            int d = dormantTicks();
            if (d > WAKE_TICKS) {
                state.getController().transitionLength(0);
                return state.setAndContinue(DORMANT_ANIM);
            }
            if (d > 0) {
                state.getController().transitionLength(WAKE_TRANSITION);
                return state.setAndContinue(WAKE_ANIM);
            }
            boolean charging = isCharging();
            state.getController().transitionLength(charging ? chargeTransitionTicks() : 4);
            return state.setAndContinue(charging ? CHARGE_ANIM : IDLE_ANIM);
        }));
        // 吐き出しは上に重ねる層(同じ骨は後のコントローラが勝つ)。引き金は shootAt。
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("spit", SPIT_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ 動き
    /**
     * 標的 {@code target} をまだ追えるか(G1 共通の索敵 {@link ParasitizedTargetGoal} が「たどり着けない相手」を見失う判定に使う)。
     * 見通せる(撃てる)か、体の箱を標的の頭上までまっすぐ動かしてもぶつからない(飛んで行ける)なら追える。
     *
     * <p>2026-10-09: 以前は空の経路探索({@code FlyingPathNavigation#createPath})で測っていたが、ガスト(幅 4・索敵 64)は
     * 1 回で最大 1024 マスを体の大きさぶん調べ、屋内・洞窟の相手には毎回上限まで広げていた(性能記録でサーバーの 38%、
     * Mob の動きがかくかくした)。動きは経路を使わない({@link FloatMoveControl})ので、動きと同じ「まっすぐ」で測る(ユーザー指定)。
     */
    public boolean canStillEngage(LivingEntity target) {
        if (this.getSensing().hasLineOfSight(target)) {
            return true;
        }
        AABB box = this.getBoundingBox();
        Vec3 from = box.getCenter();
        Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() + this.getBbHeight() * 0.5D + 0.5D, target.getZ());
        Vec3 dir = to.subtract(from);
        double length = dir.length();
        if (length < 1.0D) {
            return true;
        }
        dir = dir.scale(1.0D / length);
        int steps = Math.min(Mth.ceil(length), 80);
        for (int i = 1; i <= steps; i++) {
            box = box.move(dir);
            if (!this.level().noCollision(this, box)) {
                return false;
            }
        }
        return true;
    }

    /** バニラのガストの動き方と同じ: 行き先へ向けて数 tick おきに速度を足す。行き先までの通り道が塞がっていれば止まる。 */
    static final class FloatMoveControl extends MoveControl {
        private final AbstractParasitizedFlyer flyer;
        private int floatDuration;

        FloatMoveControl(AbstractParasitizedFlyer flyer) {
            super(flyer);
            this.flyer = flyer;
        }

        @Override
        public void tick() {
            if (this.operation != Operation.MOVE_TO) {
                return;
            }
            if (this.floatDuration-- > 0) {
                return;
            }
            this.floatDuration += this.flyer.getRandom().nextInt(5) + 2;
            Vec3 to = new Vec3(this.wantedX - this.flyer.getX(), this.wantedY - this.flyer.getY(), this.wantedZ - this.flyer.getZ());
            double length = to.length();
            if (length < 0.5D) {
                this.operation = Operation.WAIT;
                return;
            }
            to = to.normalize();
            if (canReach(to, Mth.ceil(length))) {
                double accel = this.flyer.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                this.flyer.setDeltaMovement(this.flyer.getDeltaMovement().add(to.scale(accel)));
            } else {
                this.operation = Operation.WAIT;
            }
        }

        private boolean canReach(Vec3 direction, int steps) {
            AABB box = this.flyer.getBoundingBox();
            for (int i = 1; i < steps; i++) {
                box = box.move(direction);
                if (!this.flyer.level().noCollision(this.flyer, box)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 行き先を選び直す。標的がいれば、そのまわりの撃ちやすい距離・高さ。いなければ ±16 ブロックのどこか(バニラのガストと同じ)。 */
    static final class FloatAroundGoal extends Goal {
        private final AbstractParasitizedFlyer flyer;

        FloatAroundGoal(AbstractParasitizedFlyer flyer) {
            this.flyer = flyer;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            MoveControl control = this.flyer.getMoveControl();
            if (!control.hasWanted()) {
                return true;
            }
            double dx = control.getWantedX() - this.flyer.getX();
            double dy = control.getWantedY() - this.flyer.getY();
            double dz = control.getWantedZ() - this.flyer.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            return d < 1.0D || d > 3600.0D || this.flyer.getRandom().nextInt(40) == 0;
        }

        @Override
        public boolean canContinueToUse() {
            return false;
        }

        @Override
        public void start() {
            RandomSource random = this.flyer.getRandom();
            LivingEntity target = this.flyer.getTarget();
            double x;
            double y;
            double z;
            if (target != null && target.isAlive()) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double dist = Mth.lerp(random.nextDouble(), this.flyer.keepMin(), this.flyer.keepMax());
                x = target.getX() + Math.cos(angle) * dist;
                z = target.getZ() + Math.sin(angle) * dist;
                y = target.getY() + Mth.lerp(random.nextDouble(), this.flyer.hoverMin(), this.flyer.hoverMax());
            } else {
                x = this.flyer.getX() + (random.nextFloat() * 2.0F - 1.0F) * 16.0F;
                y = this.flyer.getY() + (random.nextFloat() * 2.0F - 1.0F) * 16.0F;
                z = this.flyer.getZ() + (random.nextFloat() * 2.0F - 1.0F) * 16.0F;
            }
            this.flyer.getMoveControl().setWantedPosition(x, y, z, 1.0D);
        }
    }

    /** 標的がいればそちらを、いなければ進む向きを向く(バニラのガストと同じ)。 */
    static final class FaceGoal extends Goal {
        private final AbstractParasitizedFlyer flyer;

        FaceGoal(AbstractParasitizedFlyer flyer) {
            this.flyer = flyer;
            this.setFlags(EnumSet.of(Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return true;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.flyer.getTarget();
            if (target == null) {
                Vec3 v = this.flyer.getDeltaMovement();
                if (v.horizontalDistanceSqr() > 1.0E-4D) {
                    this.flyer.setYRot(-((float) Mth.atan2(v.x, v.z)) * Mth.RAD_TO_DEG);
                    this.flyer.yBodyRot = this.flyer.getYRot();
                }
            } else {
                double dx = target.getX() - this.flyer.getX();
                double dz = target.getZ() - this.flyer.getZ();
                this.flyer.setYRot(-((float) Mth.atan2(dx, dz)) * Mth.RAD_TO_DEG);
                this.flyer.yBodyRot = this.flyer.getYRot();
            }
            this.flyer.yHeadRot = this.flyer.yBodyRot;
        }
    }

    /**
     * 撃つ: 標的が見えて射程({@code bomb.range})の内なら、ため({@link #chargeTicks})→ 1 斉射({@code bomb.perSalvo} 発を
     * {@code bomb.intervalTicks} おき)→ 休み({@code bomb.cooldownTicks})。見失うとためが戻っていく(バニラのガストと同じ)。
     * 近ければ殴る({@link #meleeRangeSqr}、ブレイズ)。標的は G1 共通の索敵({@link ParasitizedTargetGoal})が決めたものだけを使う。
     */
    static final class SporeBombVolleyGoal extends Goal {
        private final AbstractParasitizedFlyer flyer;
        private int charge;
        private int shotsLeft;
        private int shotTimer;
        private int cooldown;
        private int meleeTimer;

        SporeBombVolleyGoal(AbstractParasitizedFlyer flyer) {
            this.flyer = flyer;
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.flyer.getTarget();
            return target != null && target.isAlive() && this.flyer.canAttack(target);
        }

        @Override
        public void start() {
            this.charge = 0;
            this.shotsLeft = 0;
        }

        @Override
        public void stop() {
            this.charge = 0;
            this.shotsLeft = 0;
            this.flyer.setCharging(false);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.flyer.getTarget();
            if (target == null) {
                return;
            }
            MobStat.Bomb cfg = this.flyer.bomb();
            double distSqr = this.flyer.distanceToSqr(target);
            if (this.meleeTimer > 0) {
                this.meleeTimer--;
            }
            if (this.flyer.meleeRangeSqr() > 0.0D && distSqr < this.flyer.meleeRangeSqr() && this.meleeTimer <= 0) {
                this.meleeTimer = 20;
                this.flyer.doHurtTarget(target);
            }
            if (this.shotsLeft > 0) {
                if (--this.shotTimer <= 0) {
                    fire(target, cfg);
                }
                return;
            }
            if (this.cooldown > 0) {
                this.cooldown--;
                return;
            }
            boolean inRange = distSqr < cfg.range() * cfg.range();
            if (inRange && this.flyer.hasLineOfSight(target)) {
                if (this.charge == 0) {
                    this.flyer.setCharging(true);
                    SoundEvent warn = this.flyer.warnSound();
                    if (warn != null) {
                        this.flyer.playSound(warn, 2.0F, 0.75F);
                    }
                }
                if (++this.charge >= this.flyer.chargeTicks()) {
                    this.charge = 0;
                    this.shotsLeft = cfg.perSalvo();
                    fire(target, cfg);
                }
            } else if (this.charge > 0) {
                if (--this.charge == 0) {
                    this.flyer.setCharging(false);
                }
            }
        }

        private void fire(LivingEntity target, MobStat.Bomb cfg) {
            this.flyer.shootAt(target);
            this.shotsLeft--;
            this.shotTimer = cfg.intervalTicks();
            if (this.shotsLeft <= 0) {
                this.shotsLeft = 0;
                this.flyer.setCharging(false);
                this.cooldown = cfg.cooldownTicks();
            }
        }
    }
}
