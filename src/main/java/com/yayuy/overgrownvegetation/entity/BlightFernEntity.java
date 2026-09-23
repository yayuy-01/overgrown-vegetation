package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.corruption.BlightFernSelfSpread;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 巨大シダの寄生植物。動かないが、周囲を球状に侵食していく。
 *
 * <p>見た目は GeckoLib 経由で読み込む。Blockbench で作ったモデルに差し替えたいときは
 * {@code assets/overgrown_vegetation/geo/blight_fern.geo.json}・{@code animations/blight_fern.animation.json}・
 * {@code textures/entity/blight_fern.png} を上書きするだけでよく、このクラスの変更は不要
 * （アニメーション名を変える場合は下の {@code IDLE_ANIM} だけ合わせる）。
 */
public class BlightFernEntity extends PathfinderMob implements GeoEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    /**
     * 侵食が完了したか。クライアントは漂う胞子パーティクル({@link #spawnAmbientParticles})を
     * 止めるためだけにこの値を見るので、サーバーから同期する必要がある({@code spreader} 自体は
     * NBT でしか保存/同期されない)。
     */
    private static final EntityDataAccessor<Boolean> DATA_SPREAD_FINISHED =
            SynchedEntityData.defineId(BlightFernEntity.class, EntityDataSerializers.BOOLEAN);

    private final CorruptionSpreader spreader = new CorruptionSpreader();
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public BlightFernEntity(EntityType<? extends BlightFernEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SPREAD_FINISHED, false);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createAttributes("blight_fern");
    }

    /**
     * 汚染シダ / 汚染水草でステータス設計データのキーを分けるための共通ビルダ。
     * ({@code blight_water_grass} は汚染シダとまったく同じ既定値だが、別 id として編集できる。)
     */
    public static AttributeSupplier.Builder createAttributes(String designId) {
        return MobStats.apply(Mob.createMobAttributes(), designId);
    }

    @Override
    protected void registerGoals() {
        // 動かない植物なので AI ゴールは持たせない
    }

    /**
     * 当たり判定を、湧いた場所の 1 ブロック分に固定する。
     *
     * <p>{@code Attributes.SCALE}(2.5倍)は {@code LivingEntity#getDimensions}(final、
     * オーバーライド不可)の中で {@code getDefaultDimensions(pose).scale(getScale())} として
     * 当たり判定にもそのまま掛かってしまい、見た目どおりの巨大な判定(約3.5×6.5ブロック)になって
     * 周囲の木に埋まってしまう。ここで返す基準サイズをあらかじめ {@code 1 / getScale()} 倍しておくことで、
     * 呼び出し元が掛けてくる {@code Attributes.SCALE} を打ち消し、最終的な当たり判定を常に
     * 1 ブロック(1.0×1.0)に固定する。GeckoLib の描画スケールは {@code Attributes.SCALE} を
     * 別途参照して見た目の大きさを保つので、このオーバーライドは見た目には影響しない。
     */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        float scale = this.getScale();
        float inverse = scale > 0.0F ? 1.0F / scale : 1.0F;
        return EntityDimensions.scalable(inverse, inverse);
    }

    public CorruptionSpreader getSpreader() {
        return spreader;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide()) {
            spawnAmbientParticles();
            return;
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.isDeadOrDying()) {
                // 死んだ(死亡アニメーション中も含む)Mob が始めた侵食はそこで止める。
                // 実際にはエンティティが消滅すれば tick() 自体呼ばれなくなるが、
                // 死亡モーション中の数 tick の間も新たに広げさせないための明示的なガード。
                return;
            }
            boolean finished = spreader.isFinished();
            if (this.entityData.get(DATA_SPREAD_FINISHED) != finished) {
                // クライアント側で胞子パーティクルを止める/再開するために同期する。
                this.entityData.set(DATA_SPREAD_FINISHED, finished);
            }
            if (finished) {
                // 侵食し終えたら役目を終えたので、その場でパッと消える(要望)。
                // 触手/claimed などの作業メモリは CorruptionSpreader.tick が完了時に
                // freeTendrilMemory() で既に手放しているので、あとはエンティティ自体を
                // discard() すれば残るものは何も無い。
                // 消える前に、汚染し終えた範囲の外周付近へ次世代の汚染シダを湧かせて
                // 汚染域を外へ広げていく(blightFernFinishSpawn* 設定。0 で無効)。
                BlightFernSelfSpread.spawnNextGeneration(serverLevel, this.blockPosition(),
                        OvergrownVegetationConfig.COMMON.corruptionRadius.get(), spreader.getCorruptedCount());
                spawnFinishPoof(serverLevel);
                this.discard();
                return;
            }

            // 隣接伝播の足がかりとして、直下のブロックを最初に必ず汚染しておく
            spreader.seedGround(serverLevel, this.blockPosition());

            int interval = OvergrownVegetationConfig.COMMON.tickInterval.get();
            // Mob ごとに UUID から決まる位相をずらして、同じ tick に多数の Mob の
            // 侵食サイクルが重ならないようにする(広がり方・速度は変わらない)。
            int phase = Math.floorMod(this.getUUID().hashCode(), interval);
            if ((this.tickCount + phase) % interval == 0) {
                int before = spreader.getCorruptedCount();
                spreader.tick(serverLevel, this.blockPosition());
                CorruptionCounter.record(serverLevel, spreader.getCorruptedCount() - before);
            }
        }
    }

    /**
     * 侵食完了で消える瞬間の小さな演出(胞子がふわっと弾けて苔が崩れる音)。サーバー側から
     * 全クライアントへパーティクル/音を送る。
     */
    private void spawnFinishPoof(ServerLevel level) {
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                this.getX(), this.getY() + 1.2D, this.getZ(), 60, 1.2D, 1.4D, 1.2D, 0.02D);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.MOSS_BREAK, this.getSoundSource(), 1.0F, 0.8F);
    }

    private void spawnAmbientParticles() {
        if (this.entityData.get(DATA_SPREAD_FINISHED)) {
            // 侵食が終わったら胞子をばらまく演出も止める(要望)。
            return;
        }
        if (this.random.nextInt(6) != 0) {
            return;
        }
        double x = this.getX() + (this.random.nextDouble() - 0.5D) * 1.6D;
        double y = this.getY() + this.random.nextDouble() * 2.4D;
        double z = this.getZ() + (this.random.nextDouble() - 0.5D) * 1.6D;
        this.level().addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0.0D, -0.01D, 0.0D);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /**
     * 休眠物理(2026-09-11、負荷対策)。着地して静止している間は、{@code LivingEntity#travel} が毎 tick 行う
     * 重力・摩擦・衝突判定({@code Entity#move})を飛ばす。動かない植物なので、この計算は「足元に立ったまま」を
     * 毎 tick 再確認しているだけで、結果は変わらない。汚染シダが数百体いるときの tick コストの大半がこれ。
     *
     * <p>飛ばすのは {@link #isSettled()} が true の tick だけ。足元のブロックが消えた・押された・水流に乗った・
     * 浮遊効果を受けた tick は通常どおり {@code super.travel} が走り、以前と同じ物理に戻る。
     * {@code Entity#move} が接地中に毎 tick 行っていた副作用(足元ブロックの {@code stepOn}(マグマ等)と
     * {@code entityInside} 判定(焚き火・サボテン等))はここで同じように呼ぶ。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (!this.level().isClientSide()
                && OvergrownVegetationConfig.COMMON.blightFernDormantPhysics.get()
                && isSettled()) {
            BlockPos onPos = this.getOnPosLegacy();
            BlockState onState = this.level().getBlockState(onPos);
            onState.getBlock().stepOn(this.level(), onPos, onState, this);
            this.tryCheckInsideBlocks();
            this.calculateEntityAnimation(false);
            return;
        }
        super.travel(travelVector);
    }

    /**
     * 「足元の完全なブロックの上に、速度ゼロで乗っている」か。
     * 水中(汚染水草)でも成り立つ(沈みきって底に乗っていれば速度は僅かな負の値に落ち着く)。
     */
    private boolean isSettled() {
        if (!this.onGround() || this.isNoGravity() || this.isPassenger() || this.hasEffect(MobEffects.LEVITATION)) {
            return false;
        }
        Vec3 motion = this.getDeltaMovement();
        // 静止中の縦速度は、重力を掛けた直後の -0.0784(陸)/ -0.005(水中)程度。それより速ければ本当に動いている。
        if (Math.abs(motion.x) > 1.0E-7D || Math.abs(motion.z) > 1.0E-7D || motion.y > 0.0D || motion.y < -0.1D) {
            return false;
        }
        BlockPos onPos = this.getOnPosLegacy();
        BlockState onState = this.level().getBlockState(onPos);
        if (!onState.isFaceSturdy(this.level(), onPos, Direction.UP)) {
            // 足元が消えた / 低いブロック(雪の層など)に変わった → 通常の物理で落とす。
            return false;
        }
        // 足がブロックの天面にぴったり乗っていること(浮いていたら通常処理で着地させる)。
        return Math.abs(this.getY() - (onPos.getY() + 1.0D)) < 1.0E-3D;
    }

    /**
     * 窒息ダメージを受けない。汚染シダは自分の広げた汚染ブロックや湿地の地形書き換え(棚田の盛り土など)に
     * 埋まることがあり、そのたびに窒息で死んでいた(2026-09-10 の要望)。
     * {@code LivingEntity#baseTick} は {@code isInWall()} が true のときだけ窒息ダメージを与えるので、
     * これを常に false にすれば済む。
     */
    @Override
    public boolean isInWall() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
        // 押し合わない
    }

    @Override
    protected void pushEntities() {
        // 周囲のエンティティを押しのけない
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.MOSS_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.MOSS_BREAK;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        spreader.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        spreader.load(tag);
    }
}
