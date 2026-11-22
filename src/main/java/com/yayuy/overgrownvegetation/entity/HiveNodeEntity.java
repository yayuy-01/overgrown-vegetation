package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModSounds;
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
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.List;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;

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
 *       設定 2 つごと撤去した。今後はグループ7(侵略級)の出現条件として作り直す予定)。</li>
 * </ul>
 *
 * <p>動かない(AI ゴールを一切持たない)。見た目は GeckoLib 経由で、
 * {@code geo/hive_node.geo.json} / {@code animations/hive_node.animation.json} /
 * {@code textures/entity/hive_node.png}(+ 発光用 {@code hive_node_glowmask.png})を差し替えれば
 * このクラスを触らずに作り直せる。現状のモデルは参考画像を元にした<b>仮モデル</b>
 * (球体のみ。2026-10-23 に巣の蔦 hive_vine を削除し、ノードは小部屋に据わって殴って倒す。体力 1000・防御 20)。
 */
public class HiveNodeEntity extends PathfinderMob
        implements GeoEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

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

    public HiveNodeEntity(EntityType<? extends HiveNodeEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "hive_node");
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_RITUAL_YAW, Float.NaN);
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
        return InteractionResult.PASS;
    }

    @Override
    protected void registerGoals() {
        // 広間に据わったまま動かないので AI ゴールは持たせない。
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
     * 当たり判定を見た目どおりの球(既定 3×3)に固定する。{@code PortAttributes.SCALE.get()} は
     * {@code LivingEntity#getDimensions} の中で当たり判定にも掛かってしまうので、
     * {@link BlightFernEntity#getDefaultDimensions} と同じ手で打ち消す。
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // 【1.20.1】1.20.1 は getDimensions が体格を掛けるので、上書きして掛けない形にする(本流は getDefaultDimensions で 1/体格 を掛けて打ち消す)
        return EntityDimensions.scalable(MobStats.width("hive_node", 3.0F), MobStats.height("hive_node", 3.0F));
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
    }

    /**
     * 1 回の鼓動。胞子を吹き出して音を鳴らすだけで、<b>周囲には何の影響も与えない</b>。
     *
     * <p>2026-09-19 にユーザー指定で、周囲の Mob へ再生能力・攻撃力上昇を配る処理を撤去した
     * ({@code hiveNodeBuffRadius} / {@code hiveNodeBuffDurationTicks} の 2 つの設定ごと削除)。
     * ハイヴノードは今後<b>グループ7(侵略級)の出現条件</b>として作り直す予定で、それまでは
     * 「巨大樹の中に居る、殴って倒せる置物」として振る舞う(倒すとその巨大樹の胞子の生産が止まる)。
     */
    private void pulse(ServerLevel level) {
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                40, 1.6D, 1.6D, 1.6D, 0.02D);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                ModSounds.HIVE_NODE_PULSE.get(), this.getSoundSource(), 2.0F, 1.0F);
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
        this.setNoGravity(true);
        reapplyBaseStats();
        int[] rc = tag.getIntArray("ritualCenter");
        ritualCenter = rc.length == 3 ? new BlockPos(rc[0], rc[1], rc[2]) : null;
        this.entityData.set(DATA_RITUAL_YAW, tag.contains("ritualYaw") ? tag.getFloat("ritualYaw") : Float.NaN);
        lockFacing();
    }

    /**
     * 2026-10-23 E1: 体力・防御は mobs.json の値(既定 体力 1000・防御 20)。属性の基本値は個体ごとにセーブされるので
     * (memory mob-attributes-saved-per-entity)、読み込むたびに今の設計値へ置き直す(前の 体力 10000 の個体も倒せるように)。
     */
    private void reapplyBaseStats() {
        AttributeSupplier fresh = createAttributes().build();
        for (var attr : java.util.List.of(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)) {
            var inst = this.getAttribute(attr);
            if (inst != null && fresh.hasAttribute(attr)) inst.setBaseValue(fresh.getBaseValue(attr));
        }
        if (this.getHealth() > this.getMaxHealth()) this.setHealth(this.getMaxHealth());
    }

    /**
     * 倒された = 核が壊された。強制感染ゾーンの登録を外し、その巨大樹の胞子の生産を止める(2026-10-23 E2、再生なし)。
     */
    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel) {
            // 見直し ④(11-16): 先に巨大樹を止め、それからノードの記録を外す(順番を入れ替えた)
            PhaseData.get(serverLevel).killGiantTreeNear(this.blockPosition(),
                    com.yayuy.overgrownvegetation.data.DesignData.sporeField().giantTreeKillRadius());
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

    /** 広間に据わっているので押されない・ノックバックも受けない。 */
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
        return ModSounds.HIVE_NODE_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.HIVE_NODE_DEATH.get();
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
     * 壁に埋まっても窒息しない・押し潰しと落下も無視する(倒せるのはプレイヤー・Mob の攻撃だけ)。
     * 2026-10-23 E1: 前の「99% カット・蔦を 7 割切り落として落とすと即死」はやめ、ふつうに殴って倒せる(体力 1000・防御 20)。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.CRAMMING) || source.is(DamageTypes.FALL)) {
            return false;
        }
        return super.hurt(source, amount);
    }
}
