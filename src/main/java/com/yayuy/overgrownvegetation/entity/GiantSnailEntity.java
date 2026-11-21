package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * 陸生カタツムリ {@code giant_snail}(獲物の環境生物・原生生産種。名前から「巨大」は外したが id はそのまま。11-16 ユーザー)。
 * 2026-11-16 環境生物の作り直しの段 1: 「小さな地這いの虫」の型({@link CrawlerCritter})で這うだけ。行動は後の段。
 * 形は 11-16 夜に「A 丸い殻」へ直した(全長 1.34・幅 0.69・高さ 0.89。殻は普通の樹皮 = 矢や弾は弾かない)。正本 Blockbench の giant_snail.bbmodel。
 *
 * <p>脚が無いので足の滑りは無い。walk(2 秒)は頭と殻が前後するだけ。這う速さに合わせた walk の基準はモデル制作と合わせる【仮 {@link #WALK_REF}】。
 * 速さ【仮・Claude 11-17】: うろつき 約 0.018 ブロック/tick(移動の速さ 0.05 はそのまま、掛け率 1.85。下の罠のため 0.011 は出せない)。走って逃げない(急ぎも同じ速さ)。
 *
 * <p><b>殻に引っ込む</b>(11-16 夜 ユーザー): 叩かれるか、敵(植物側以外の生き物・サバイバルのプレイヤー)が {@link #RETRACT_NEAR} ブロック以内に来たら、
 * 殻に引っ込んで止まる(アニメ retract → retract_hold を繰り返し → 出るときは retract_out)。{@link #RETRACT_TICKS} tick【仮・約 5 秒】じっとしてから、頭を出してまた這う。
 * 引っ込んでいる間は受ける傷が {@link #RETRACT_DAMAGE} 倍【仮】。矢や弾は弾かない。頭を出している 28 tick(retract_out)も止まったまま。アニメは 11-16 にモデル制作(g2g3-bug-walk)が作った。
 */
public class GiantSnailEntity extends CrawlerCritter {

    public static final String ID = "giant_snail";
    /** walk を 1 倍で流す地面の速さ【仮】(脚が無いので滑りとは関係ない。頭と殻の前後がゆっくり見える速さ)。 */
    public static final double WALK_REF = 0.011D;
    static final float TURN_PER_TICK = 4.0F;
    /**
     * 移動の速さ 0.05 に掛ける【仮】。走って逃げない = 急ぎも同じ。
     * 罠(11-17 測った): バニラは 1 tick ごとに 0.003 未満の速度の成分を 0 にするので、遅すぎると勢いが続かず 1 tick 分の加速(× 1.4 で 0.005)に張り付く。
     * × 1.6 は斜めに進むと成分が境の下に落ちて 0.006 と 0.014 に分かれた。斜めでも勢いが続く × 1.85 にした(式 2.16 × 速さ² で 約 0.018。× 2.1 は 0.024 で式どおり)。
     */
    static final double STROLL_SPEED = 1.85D, RUSH_SPEED = STROLL_SPEED;

    // --- 殻に引っ込む(11-16 夜 ユーザー。数値は仮) ---
    /** 引っ込んでじっとしている長さ(tick)・受ける傷の倍率・敵に気付く距離(ブロック)・敵を見る間隔(tick)。 */
    public static final int RETRACT_TICKS = 100;
    public static final float RETRACT_DAMAGE = 0.5F;
    public static final double RETRACT_NEAR = 3.0D;
    static final int LOOK_EVERY = 10;

    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_RETRACTED =
            net.minecraft.network.syncher.SynchedEntityData.defineId(GiantSnailEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private static final software.bernie.geckolib.animation.RawAnimation RETRACT =
            software.bernie.geckolib.animation.RawAnimation.begin().thenPlay("retract").thenLoop("retract_hold");
    private static final software.bernie.geckolib.animation.RawAnimation EMERGE =
            software.bernie.geckolib.animation.RawAnimation.begin().thenPlay("retract_out");
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_EMERGING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(GiantSnailEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    /** 頭を出すアニメ retract_out の長さ(tick)。出ている途中は止まったまま(這い出さない)。 */
    public static final int EMERGE_TICKS = 28;
    private int emergeLeft;

    /** 引っ込んでいる残り(サーバー)。 */
    private int retractLeft;

    public GiantSnailEntity(EntityType<? extends GiantSnailEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 1);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_RETRACTED, false);
        builder.define(DATA_EMERGING, false);
    }

    /** 頭を出している途中か(両側。retract_out の 28 tick)。 */
    public boolean emerging() {
        return this.entityData.get(DATA_EMERGING);
    }

    /** 殻に引っ込んでいるか(両側)。 */
    public boolean retracted() {
        return this.entityData.get(DATA_RETRACTED);
    }

    /** 殻に引っ込む(サーバー)。引っ込んでいる間にまた叩かれたら、じっとしている長さを延ばす。 */
    public void retract() {
        this.retractLeft = RETRACT_TICKS;
        this.emergeLeft = 0;
        this.entityData.set(DATA_RETRACTED, true);
        this.entityData.set(DATA_EMERGING, false);
        this.getNavigation().stop();
    }

    /** 引っ込んでいる間はうろつかない(群れ・うろつきの Goal が止まる)。 */
    @Override
    public boolean isSteered() {
        return retracted() || emerging() || super.isSteered();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.retractLeft > 0) {
            this.getNavigation().stop();
            this.setZza(0.0F);
            if (--this.retractLeft == 0) {   // 頭を出す(retract_out の間は止まったまま)
                this.entityData.set(DATA_RETRACTED, false);
                this.entityData.set(DATA_EMERGING, true);
                this.emergeLeft = EMERGE_TICKS;
            }
            return;
        }
        if (this.emergeLeft > 0) {
            this.getNavigation().stop();
            this.setZza(0.0F);
            if (--this.emergeLeft == 0) this.entityData.set(DATA_EMERGING, false);   // また這う
            if (this.emergeLeft > 0) return;
        }
        if ((this.tickCount + this.getId()) % LOOK_EVERY == 0 && enemyNear()) retract();
    }

    /** 敵が近くにいるか: サバイバルのプレイヤー・植物側以外の生き物(防具立てを除く)が {@link #RETRACT_NEAR} 以内。 */
    private boolean enemyNear() {
        double r2 = RETRACT_NEAR * RETRACT_NEAR;
        for (net.minecraft.world.entity.player.Player p : this.level().players()) {
            if (!p.isCreative() && !p.isSpectator() && p.distanceToSqr(this) <= r2) return true;
        }
        return !this.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, this.getBoundingBox().inflate(RETRACT_NEAR),
                e -> e != this && e.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player)
                        && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)
                        && !com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(e)
                        && e.distanceToSqr(this) <= r2).isEmpty();
    }

    /** 引っ込んでいる間は受ける傷が半分。叩かれたら(相手のある傷なら)引っ込む。 */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!this.level().isClientSide() && retracted()) amount *= RETRACT_DAMAGE;
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide() && this.isAlive() && source.getEntity() != null) retract();
        return hurt;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("OvRetract", this.retractLeft);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.retractLeft = tag.getInt("OvRetract");
        this.entityData.set(DATA_RETRACTED, this.retractLeft > 0);
    }

    /** 殻の層: 引っ込んでいる間は retract → retract_hold、出るときは retract_out で、歩き・待機の頭と殻を上書きする(後に登録 = 同じ骨は後が勝つ)。 */
    @Override
    public void registerControllers(software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new software.bernie.geckolib.animation.AnimationController<>(this, "shell", 4,
                s -> retracted() ? s.setAndContinue(RETRACT) : emerging() ? s.setAndContinue(EMERGE) : software.bernie.geckolib.animation.PlayState.STOP));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    /** 0.4〜2.5 倍(這う速さ 0.0044〜0.0275 を覆う)。 */
    @Override
    protected double[] walkRange() {
        return new double[]{0.4D, 2.5D};
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;   // ゆっくり向きを変える
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    public double rushSpeed() {
        return RUSH_SPEED;
    }

    @Override
    protected double bodyHalfLength() {
        return 0.6D;
    }

    /** 長く這って長く止まる【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{80, 200};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{60, 200};
    }
}
