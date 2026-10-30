package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「リトプレウラ」— 岩に擬態するヤスデの仲間(グループ2・原生生産種)。
 *
 * <h2>脱皮して抜け殻を残す</h2>
 * <p>この種の肝。ときどき立ち止まって脱皮し、<b>その場に抜け殻ブロックを置いていく</b>
 * ({@link ModBlocks#LITHOPLEURA_HUSK})。抜け殻は<b>殺さずに採れる素材</b>で、
 * 研究の解析に回せる —— プレイヤーの利益が「殺すこと」だけに寄らないようにするための種
 * (ユーザー指定 2026-09-21)。
 *
 * <p>脱皮の見せ方はモデル側の {@code kara} フォルダが受け持つ。<b>普段は隠してある</b>
 * ({@code LithopleuraModel} が毎フレーム非表示にする)。脱皮中だけ出して、後ろへ
 * 抜けていくところをアニメーションで見せ、抜けきったところでブロックを置く。
 *
 * <h2>プレイヤーを襲わない</h2>
 * <p>{@link AbstractOvergrownCritterEntity} の既定どおり、索敵も近接攻撃も持たない。
 * 殴られたら逃げ、グループ3 の捕食者からは距離を取る。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code molt}。
 */
public class LithopleuraEntity extends AbstractOvergrownCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    // 1 回だけ流して最後のコマで止める。ループだと、脱皮の終わりの同期が遅れた瞬間に 0 秒目へ戻り、殻が体へ戻って見える。
    private static final RawAnimation MOLT_ANIM = RawAnimation.begin().thenPlayAndHold("molt");

    /** 脱皮中。{@code kara} を出して後ろへ抜けていく。 */
    public static final int STATE_MOLT = 10;

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、脱皮のアニメは脱皮を始めた tick より 4 tick 遅れて始まる。
     * 脱皮の tick(殻が割れる音・殻を置く・終わる)は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 4;

    /** 脱皮の所要時間(tick)。アニメ {@code molt}(4.5 秒)を最後まで見せる。 */
    public static final int MOLT_TICKS = TRANSITION_TICKS + 90;
    /**
     * 何 tick 目で抜け殻ブロックを置くか。アニメで殻が体から抜けきる 3.1 秒。
     * 2026-09-23 までは補間を数えておらず(62)、殻が抜けきる 0.2 秒前(まだ体に掛かっているうち)に置いていた。
     */
    private static final int MOLT_DROP_AT = TRANSITION_TICKS + 62;
    /** 殻が割れる音を鳴らす tick。アニメの 0 秒目(殻が浮き始める)。 */
    private static final int MOLT_CRACK_AT = TRANSITION_TICKS;
    /**
     * 脱皮するかを判定する間隔(tick)。既定 1 分(ユーザー指定 2026-09-21)。
     *
     * <p><b>固定間隔で脱皮させない</b> —— 「5〜9 分後」だと最短時間が読めてしまい、
     * 待てば必ず起きる作業になる。1 分ごとに {@link #MOLT_CHANCE} で抽選する形にすると、
     * <b>次がいつかは分からないまま、平均だけが決まる</b>。
     */
    private static final int MOLT_CHECK_INTERVAL = 1200;
    /** 1 回の判定で脱皮する確率。0.15 なら平均 6.7 分に 1 回。 */
    private static final double MOLT_CHANCE = 0.15;

    /**
     * 脱皮中か(0 = していない、1 = している)。描画側が {@code kara} を出すかの判定に使う。
     *
     * <p>2026-09-24 軽量化(監査 1714 / 1802): 以前は進み具合そのもの(1〜{@link #MOLT_TICKS})をここに入れて
     * 脱皮中は毎 tick 同期していたが、クライアントが見るのは「0 より大きいか」だけ。
     * 進み具合はサーバー側の {@link #moltProgress} へ移し、ここは始めたときに 1、終わったときに 0 だけを書く
     * (0 と 0 より大きいが切り替わる tick は以前と同じ)。
     */
    private static final EntityDataAccessor<Integer> DATA_MOLT =
            SynchedEntityData.defineId(LithopleuraEntity.class, EntityDataSerializers.INT);

    /**
     * 脱皮の経過 tick(0 = していない)。<b>サーバー側だけ</b>が進める。
     * 以前の DATA_MOLT と同じく保存はしない(読み込み直すと脱皮していない状態から始まる)。
     */
    private int moltProgress;

    /**
     * 次の判定まで(tick)。0 になった瞬間に抽選し、外れたらまた {@link #MOLT_CHECK_INTERVAL} 待つ。
     * 当たったら 0 のまま据え置き = {@link #readyToMolt()} が真になり、
     * 足場などの条件が整い次第そこで脱皮する。
     */
    private int moltCheck;

    public LithopleuraEntity(EntityType<? extends LithopleuraEntity> type, Level level) {
        super(type, level);
        // 初回だけ 1 分の中のどこかへ散らす。まとめて湧いた個体が<b>同じ tick で一斉に</b>
        // 抽選すると、揃って脱皮したり揃って外れたりする。
        this.moltCheck = 1 + this.random.nextInt(MOLT_CHECK_INTERVAL);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "lithopleura");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOLT, 0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // 逃走(2)より後、徘徊(6)より前。脱皮中は動かない。
        this.goalSelector.addGoal(3, new MoltGoal(this));
    }

    /** 脱皮の経過 tick。0 なら脱皮していない。<b>サーバー側だけ</b>の値(クライアントでは常に 0)。 */
    public int getMoltProgress() {
        return this.moltProgress;
    }

    /** 脱皮中か。{@code kara} を描くかどうかの判定(同期した値を見るのでクライアントでも使える)。 */
    public boolean isMolting() {
        return this.entityData.get(DATA_MOLT) > 0;
    }

    boolean readyToMolt() {
        return this.moltCheck <= 0 && this.onGround() && !this.isInWater() && !isMolting();
    }

    void beginMolt() {
        this.moltProgress = 1;
        this.entityData.set(DATA_MOLT, 1);
        setActionState(STATE_MOLT);
    }

    /** @return 脱皮が終わったか */
    boolean advanceMolt() {
        int at = getMoltProgress();
        if (at == MOLT_CRACK_AT) {
            playSound(SoundEvents.BAMBOO_BREAK, 0.5F, 1.4F);
        }
        if (at == MOLT_DROP_AT) {
            dropHusk();
        }
        if (at >= MOLT_TICKS) {
            endMolt();
            return true;
        }
        // 進み具合はサーバー側だけで数える(DATA_MOLT は 1 のまま = 毎 tick の同期をしない)。
        this.moltProgress = at + 1;
        if (at == 0) {
            // 脱皮していないのに進められた(以前は DATA_MOLT が 0 → 1 になって脱皮中になった)。同じにする。
            this.entityData.set(DATA_MOLT, 1);
        }
        return false;
    }

    void endMolt() {
        this.moltProgress = 0;
        this.entityData.set(DATA_MOLT, 0);
        setActionState(STATE_IDLE);
        this.moltCheck = MOLT_CHECK_INTERVAL;
    }

    /**
     * 抜け殻をその場へ置く。<b>体の真下ではなく少し後ろ</b> —— 脱皮は前へ這い出て殻を脱ぐ動きなので、
     * 自分が立っている場所に置くと殻の中から生えてきたように見える。
     *
     * <p>置けなければ<b>何もしない</b>(アイテムとして落とさない)。ここで拾えるアイテムを撒くと、
     * 「抜け殻を見つける」という体験が「地面に落ちている物を拾う」に置き換わってしまう。
     */
    private void dropHusk() {
        if (this.level().isClientSide()) {
            return;
        }
        Direction facing = Direction.fromYRot(this.getYRot());
        BlockPos behind = this.blockPosition().relative(facing.getOpposite());
        BlockPos at = placeableAt(behind) ? behind
                : (placeableAt(this.blockPosition()) ? this.blockPosition() : null);
        if (at == null) {
            return;
        }
        BlockState husk = ModBlocks.LITHOPLEURA_HUSK.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing);
        this.level().setBlock(at, husk, Block.UPDATE_ALL);
        this.level().playSound(null, at, SoundEvents.BAMBOO_BREAK, SoundSource.NEUTRAL, 0.7F, 0.9F);
    }

    private boolean placeableAt(BlockPos pos) {
        return this.level().getBlockState(pos).canBeReplaced()
                && this.level().getBlockState(pos.below()).isSolidRender(this.level(), pos.below());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() || isMolting() || this.moltCheck <= 0) {
            return;
        }
        this.moltCheck--;
        if (this.moltCheck <= 0 && this.random.nextDouble() >= MOLT_CHANCE) {
            this.moltCheck = MOLT_CHECK_INTERVAL;   // 外れ。もう 1 分待つ
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("MoltCheck", this.moltCheck);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("MoltCheck")) {
            this.moltCheck = tag.getInt("MoltCheck");
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction、2026-10-22)
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            if (isMolting()) {
                return state.setAndContinue(MOLT_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SPIDER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.6F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.25F, 0.7F);
    }
}
