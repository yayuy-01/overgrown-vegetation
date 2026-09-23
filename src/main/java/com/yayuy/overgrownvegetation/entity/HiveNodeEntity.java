package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「ハイヴノード」({@code hive_node})。巨大樹の真下の「巣の球」
 * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature} の {@code buildHiveChamber})の中心の
 * 小部屋に 1 体だけ据わっている、緑に脈打つ球体の mob。
 *
 * <h2>役割</h2>
 * <ul>
 *   <li><b>儀式の入口</b>(2026-09-18、仮) — 右クリックすると隣の「儀式の広間」({@link #ritualCenter})で
 *       寄生植物装備を受け取る演出({@link HiveRitualEntity})が始まる。ノードは常に広間の方
 *       ({@link #facingYaw}、巨大樹の生成時に {@link #setRitual} で決まる)を向いていて回らない。</li>
 *   <li><b>旧「古代樹の核」の代替</b> — 生きている間、周囲 {@code level3.giantTreeCoreRadius}
 *       (既定 60 ブロック)が「胞子感染2 のプレイヤーが胞子感染3 になる」ゾーンになる。
 *       最初の tick で {@link PhaseData#recordHiveNode} に登録し、倒されると
 *       {@link PhaseData#removeHiveNode} で登録が外れてゾーンが消える(核ブロックを壊すのと同じ扱い)。</li>
 *   <li><b>鼓動</b> — {@code hiveNodePulseIntervalTicks}(既定 80 = 4秒)ごとに 1 回脈打ち、
 *       胞子のパーティクルと低い鼓動音を出す。<b>周囲には何の影響も与えない</b>
 *       (2026-09-19 にユーザー指定で、周囲の mob へ再生能力・攻撃力上昇を配る処理を
 *       設定 2 つごと撤去した。今後はグループ9(侵略級)の出現条件として作り直す予定)。</li>
 * </ul>
 *
 * <p>動かない(AI ゴールを一切持たない)。見た目は GeckoLib 経由で、
 * {@code geo/hive_node.geo.json} / {@code animations/hive_node.animation.json} /
 * {@code textures/entity/hive_node.png}(+ 発光用 {@code hive_node_glowmask.png})を差し替えれば
 * このクラスを触らずに作り直せる。現状のモデルは参考画像を元にした<b>仮モデル</b>
 * (球体のみ —— 吊っている蔦は実際のブロックとして広間に置かれる)。
 */
public class HiveNodeEntity extends PathfinderMob
        implements GeoEntity, com.yayuy.overgrownvegetation.phase.ParasitizedMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    /** 蔦を切られて落下したか。落ちると 99% のダメージカットが外れて倒せるようになる。 */
    private static final EntityDataAccessor<Boolean> DATA_FALLEN =
            SynchedEntityData.defineId(HiveNodeEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * 儀式のときプレイヤーがノードを見る向き(Minecraft の yaw、度)。ノード自身はその逆(+180°)を向く。
     * NaN = 未設定(スポーンエッグで出したノードなど)。向きの固定に両側で使うので同期する。
     */
    private static final EntityDataAccessor<Float> DATA_RITUAL_YAW =
            SynchedEntityData.defineId(HiveNodeEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 儀式の広間の床の中心(空気の一番下のマス)。null = 未設定(儀式はプレイヤーの今いる場所で行う)。 */
    @Nullable
    private BlockPos ritualCenter;

    /** {@link PhaseData} への登録済みか(毎 tick 登録し直さないためのフラグ)。 */
    private boolean registered;

    /** 湧いたときに張られた蔦の本数。生き残りの割合を出すための分母。 */
    private int vineTotal;

    public HiveNodeEntity(EntityType<? extends HiveNodeEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "hive_node");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FALLEN, false);
        builder.define(DATA_RITUAL_YAW, Float.NaN);
    }

    /** 張られた蔦の本数を覚える({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue} が湧かせた直後に呼ぶ)。 */
    public void setVineTotal(int total) {
        this.vineTotal = total;
    }

    /**
     * 儀式の広間の位置と向きを覚える({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue} が
     * 湧かせた直後に呼ぶ)。以後ノードは広間の方({@code ritualYaw + 180°})を向いたまま回らない。
     *
     * @param center    広間の床の中心(空気の一番下のマス)
     * @param ritualYaw 広間からノードを見る向き(度)= 儀式中のプレイヤーの向き
     */
    public void setRitual(BlockPos center, float ritualYaw) {
        this.ritualCenter = center.immutable();
        this.entityData.set(DATA_RITUAL_YAW, ritualYaw);
        lockFacing();
    }

    @Nullable
    public BlockPos ritualCenter() {
        return ritualCenter;
    }

    /** 儀式中のプレイヤーの向き(度)。未設定なら NaN。 */
    public float ritualYaw() {
        return this.entityData.get(DATA_RITUAL_YAW);
    }

    /** ノード自身が向く方角(度)= 広間の方。未設定なら NaN。 */
    public float facingYaw() {
        float r = ritualYaw();
        return Float.isNaN(r) ? Float.NaN : Mth.wrapDegrees(r + 180.0F);
    }

    /** 頭も体も {@link #facingYaw} に固定する(AI も攻撃も視線もノードを回さない)。 */
    private void lockFacing() {
        float f = facingYaw();
        if (Float.isNaN(f)) {
            return;
        }
        this.setYRot(f);
        this.yRotO = f;
        this.setYBodyRot(f);
        this.yBodyRotO = f;
        this.setYHeadRot(f);
        this.yHeadRotO = f;
        this.setXRot(0.0F);
        this.xRotO = 0.0F;
    }

    /** 右クリック = 儀式を始める(仮実装。鍵アイテムの条件はまだ無い)。 */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || this.isDeadOrDying()) {
            return InteractionResult.PASS;
        }
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && this.level() instanceof ServerLevel serverLevel
                && HiveRitualEntity.start(serverLevel, this, serverPlayer)) {
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** 蔦を切られて落ちたか。 */
    public boolean hasFallen() {
        return this.entityData.get(DATA_FALLEN);
    }

    @Override
    protected void registerGoals() {
        // 蔦で吊られたまま動かないので AI ゴールは持たせない。
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /**
     * 当たり判定を見た目どおりの球(既定 3×3)に固定する。{@code Attributes.SCALE} は
     * {@code LivingEntity#getDimensions} の中で当たり判定にも掛かってしまうので、
     * {@link BlightFernEntity#getDefaultDimensions} と同じ手で打ち消す。
     */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        float scale = this.getScale();
        float inverse = scale > 0.0F ? 1.0F / scale : 1.0F;
        return EntityDimensions.scalable(MobStats.width("hive_node", 3.0F) * inverse,
                MobStats.height("hive_node", 3.0F) * inverse);
    }

    /** 光源ブロックを置く位置 = 体の中心のマス(足元の 1 つ上)。 */
    private BlockPos lightPos() {
        return this.blockPosition().above();
    }

    /**
     * ノードを明るさ 15 の光源にする。エンティティは光を出せないので、体の中心のマス(見た目の球の内側)に
     * バニラの透明な光源ブロック({@code minecraft:light[level=15]})を置いて代用する。倒されたら消す。
     */
    private void keepLight(ServerLevel level) {
        BlockPos pos = lightPos();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
        }
    }

    private void removeLight(ServerLevel level) {
        BlockPos pos = lightPos();
        if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.LIGHT)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** 胞子感染3 の強制ゾーンの中心として登録する。 */
    private void registerAsCore(ServerLevel level) {
        if (!registered) {
            PhaseData.get(level).recordHiveNode(this.blockPosition());
            registered = true;
        }
    }

    @Override
    public void tick() {
        super.tick();
        lockFacing();
        if (this.level().isClientSide()) {
            spawnAmbientParticles();
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel) || this.isDeadOrDying()) {
            return;
        }
        // 湧かせ方によらず(巨大樹の生成でも、コマンド・スポーンエッグでも)核として振る舞えるよう、
        // 最初の tick で必ず登録しておく。
        registerAsCore(serverLevel);
        if (this.tickCount % 20 == 1) {
            keepLight(serverLevel);
        }

        int interval = Math.max(1, OvergrownVegetationConfig.COMMON.hiveNodePulseIntervalTicks.get());
        if (this.tickCount % interval == 0) {
            pulse(serverLevel);
        }
        if (!hasFallen() && this.tickCount % 20 == 0) {
            checkVines(serverLevel);
        }
        if (hasFallen() && this.onGround()) {
            // 落ちて地面(広間の床)に当たった —— その時点で潰れて即死する。
            crash(serverLevel);
        }
    }

    /**
     * 吊っている蔦({@link HiveVineEntity})の生き残りを数え、{@code hiveNodeFallThreshold}
     * (既定 0.7 = 7 割)を超えて切られていたら落下させる。落下すると 99% のダメージカットが
     * 外れる({@link #hurt})ので、そこで初めて倒せるようになる。
     */
    private void checkVines(ServerLevel level) {
        if (vineTotal <= 0) {
            return;
        }
        double reach = OvergrownVegetationConfig.COMMON.giantTreeHiveChamberRadius.get() + 48.0D;
        List<HiveVineEntity> vines = level.getEntitiesOfClass(HiveVineEntity.class,
                this.getBoundingBox().inflate(reach),
                v -> v.isAlive() && this.getUUID().equals(v.getNodeId()));
        double cutRatio = 1.0D - (double) vines.size() / vineTotal;
        if (cutRatio >= OvergrownVegetationConfig.COMMON.hiveNodeFallThreshold.get()) {
            fall(level, vines);
        }
    }

    /** 支えを失って落下する。残った蔦は引きちぎれる。 */
    private void fall(ServerLevel level, List<HiveVineEntity> remaining) {
        this.entityData.set(DATA_FALLEN, true);
        this.setNoGravity(false);
        for (HiveVineEntity vine : remaining) {
            vine.snap(level);
        }
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.SCULK_CATALYST_BREAK, this.getSoundSource(), 2.0F, 0.5F);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                120, 2.0D, 2.0D, 2.0D, 0.05D);
    }

    /**
     * 落下して床に激突した —— 即死する。蔦を切り落として落とすことが、このノードの本来の倒し方。
     * {@link #hurt} の 99% カットを通さずに直接 0 にするので、カットを 100% にしていても確実に死ぬ。
     */
    private void crash(ServerLevel level) {
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                this.getX(), this.getY() + this.getBbHeight() * 0.4D, this.getZ(),
                200, 2.4D, 1.2D, 2.4D, 0.08D);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.SCULK_SHRIEKER_BREAK, this.getSoundSource(), 3.0F, 0.5F);
        this.setHealth(0.0F);
        this.die(level.damageSources().fellOutOfWorld());
    }

    /**
     * 1 回の鼓動。胞子を吹き出して音を鳴らすだけで、<b>周囲には何の影響も与えない</b>。
     *
     * <p>2026-09-19 にユーザー指定で、周囲の Mob へ再生能力・攻撃力上昇を配る処理を撤去した
     * ({@code hiveNodeBuffRadius} / {@code hiveNodeBuffDurationTicks} の 2 つの設定ごと削除)。
     * ハイヴノードは今後<b>グループ9(侵略級)の出現条件</b>として作り直す予定で、それまでは
     * 「巨大樹の中に居る、蔦を切って落とすと倒せる置物」として振る舞う。
     *
     * <p>アルボラの巣の最奥に置く<b>巣核</b>(湧いた瞬間に恒久的な強化を焼き付ける)とは別物。
     */
    private void pulse(ServerLevel level) {
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                40, 1.6D, 1.6D, 1.6D, 0.02D);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.WARDEN_HEARTBEAT, this.getSoundSource(), 2.0F, 0.6F);
    }

    /** 緑に光っている雰囲気を出すための、球の周りに漂うパーティクル(クライアント側だけ)。 */
    private void spawnAmbientParticles() {
        if (this.random.nextInt(3) != 0) {
            return;
        }
        double r = this.getBbWidth() * 0.6D;
        double x = this.getX() + (this.random.nextDouble() - 0.5D) * r * 2.0D;
        double y = this.getY() + this.getBbHeight() * (0.2D + this.random.nextDouble() * 0.8D);
        double z = this.getZ() + (this.random.nextDouble() - 0.5D) * r * 2.0D;
        this.level().addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0.0D, -0.005D, 0.0D);
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("vineTotal", vineTotal);
        tag.putBoolean("fallen", hasFallen());
        if (ritualCenter != null) {
            tag.putIntArray("ritualCenter", new int[] {ritualCenter.getX(), ritualCenter.getY(), ritualCenter.getZ()});
        }
        if (!Float.isNaN(ritualYaw())) {
            tag.putFloat("ritualYaw", ritualYaw());
        }
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        vineTotal = tag.getInt("vineTotal");
        this.entityData.set(DATA_FALLEN, tag.getBoolean("fallen"));
        this.setNoGravity(!hasFallen());
        int[] rc = tag.getIntArray("ritualCenter");
        ritualCenter = rc.length == 3 ? new BlockPos(rc[0], rc[1], rc[2]) : null;
        this.entityData.set(DATA_RITUAL_YAW, tag.contains("ritualYaw") ? tag.getFloat("ritualYaw") : Float.NaN);
        lockFacing();
    }

    /** 倒された = 核が壊された。強制感染ゾーンの登録を外す。 */
    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel) {
            PhaseData.get(serverLevel).removeHiveNode(this.blockPosition());
            removeLight(serverLevel);
        }
        super.die(source);
    }

    /** コマンドなどで消されたときも光源ブロックを残さない(チャンクのアンロードでは残す = 次に読んだとき同じ場所)。 */
    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && this.level() instanceof ServerLevel serverLevel) {
            removeLight(serverLevel);
        }
        super.remove(reason);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** 吊られているので押されない・ノックバックも受けない。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
        // 何もしない(周りの mob に押されて広間の中を漂わないように)。
    }

    @Override
    public void push(double x, double y, double z) {
        // ノックバックも受けない。
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SLIME_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SCULK_SHRIEKER_BREAK;
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return false;
    }

    /** 自分からは何もしない(完全に無抵抗)。 */
    @Override
    public boolean canAttack(LivingEntity target) {
        return false;
    }

    /**
     * 蔦に埋まっていても窒息しない。ハイヴノードを吊る蔦({@code buildHiveVines})は球の表面から
     * 伸びるので、目の位置が蔦のブロックと重なって窒息ダメージが入り続けることがある。
     * 落下・押し潰しも同様に無視する(倒せるのはプレイヤー/mob の攻撃だけ)。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.CRAMMING) || source.is(DamageTypes.FALL)) {
            return false;
        }
        // すべての攻撃を 99%(hiveNodeDamageReduction)カットする。体力 10000 と合わせて
        // 「殴って倒すのは事実上不可能」だが、理屈のうえでは倒せる状態のまま。
        // 本来の倒し方は蔦を 7 割切り落として落下させること(落ちた瞬間に即死する。{ #crash})。
        double cut = OvergrownVegetationConfig.COMMON.hiveNodeDamageReduction.get();
        float reduced = (float) (amount * (1.0D - Mth.clamp(cut, 0.0D, 1.0D)));
        if (reduced <= 0.0F && amount > 0.0F) {
            // 完全に 0 にすると無敵時間すら発生しないので、最低でも 1 ダメージの 1/100 は通す。
            reduced = 0.01F;
        }
        return super.hurt(source, reduced);
    }
}
