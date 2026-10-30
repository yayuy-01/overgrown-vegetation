package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
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
    /** 萌芽体のモデル(2026-10-23、tools/g5-models/mobs/Hougatai): 歩き・根付く・胞子嚢の脈(別のコントローラ)。 */
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ROOT_ANIM = RawAnimation.begin().thenPlayAndHold("root");
    private static final RawAnimation SACS_ANIM = RawAnimation.begin().thenLoop("sacs");
    /** walk の足が滑らない速さ(ブロック/tick。生成器 HougataiAnims が出す値)。 */
    private static final double WALK_ANIM_REF = 0.0434D;
    /** 根付くアニメ(root、1.5 秒)の長さ。この間は止まって根を下ろし、終わったら根付いた姿のブロックに替わる。 */
    static final int ROOT_TICKS = 30;

    /** 根を下ろしている最中か(クライアントの root アニメのため同期)。 */
    private static final EntityDataAccessor<Boolean> DATA_ROOTING =
            SynchedEntityData.defineId(BlightFernEntity.class, EntityDataSerializers.BOOLEAN);

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
        // 水を避けない: 沈んで水底を歩き、水の中でも根付く(2026-10-24 追記 18【決定】。前は浮いて岸へ戻っていた)
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 0.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER, 0.0F);
        applyStepHeight();
    }

    /** 段差 1 ブロックは歩いて登る(穴につっかえないように。2026-10-24 追記 18【決定】)。セーブの属性で戻らないよう読み込みの後にも。 */
    private void applyStepHeight() {
        net.minecraft.world.entity.ai.attributes.AttributeInstance step = this.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) step.setBaseValue(1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SPREAD_FINISHED, false);
        builder.define(DATA_ROOTING, false);
    }

    public boolean isRooting() {
        return this.entityData.get(DATA_ROOTING);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, state -> {
            state.getController().setAnimationSpeed(1.0D);
            if (isRooting()) {
                return state.setAndContinue(ROOT_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
                state.getController().setAnimationSpeed(MobAnimation.walkSpeedFor(this, WALK_ANIM_REF));
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
        // 胞子嚢がしぼむ瞬間の小さな噴き出しは画面の演出と一緒に 2026-10-30 に parked/ へ(BlightFernRootedPuffs)
        controllers.add(new AnimationController<>(this, "sacs", 0, state -> state.setAndContinue(SACS_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createAttributes("blight_fern");
    }

    /**
     * ステータス設計データのキーから属性を作る共通ビルダ。
     */
    public static AttributeSupplier.Builder createAttributes(String designId) {
        return MobStats.apply(Mob.createMobAttributes(), designId);
    }

    @Override
    protected void registerGoals() {
        // 胞子の場(2026-10-23 第 2 段 F): 萌芽体は根を脚にして湿った所へ歩き、根付く。移動速度 0 の個体は動かない
        // 水に落ちても浮かない(浮かせる goal を持たないので沈む)。水底を歩いて根付く(2026-10-24 追記 18)
        this.goalSelector.addGoal(1, new MoistStrollGoal(this));
    }

    /** 水の中では息を減らさない(萌芽体が水に落ちて溺れていた。汚染水草は 10-23 に削除して萌芽体に統一)。 */
    @Override
    protected int decreaseAirSupply(int currentAir) {
        return currentAir;
    }

    /**
     * 根を下ろせる足場か: 固い天面の上で、足元も床も溶岩でない。水の中(水底)も含む(2026-10-24 追記 18: 前は乾いた足場だけ)。
     */
    static boolean footing(Level level, BlockPos feet) {
        BlockPos below = feet.below();
        net.minecraft.world.level.material.FluidState here = level.getFluidState(feet);
        return (here.isEmpty() || here.is(net.minecraft.tags.FluidTags.WATER))
                && !level.getFluidState(below).is(net.minecraft.tags.FluidTags.LAVA)
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** 水底の床(足元が水で、下が固い天面)か。 */
    static boolean seabed(Level level, BlockPos feet) {
        return level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER) && footing(level, feet);
    }

    /** 水に隣接する(周り 1 ブロックの足の高さか一段下に水がある)乾いた足場か。 */
    static boolean dryShore(Level level, BlockPos feet) {
        if (!footing(level, feet) || !level.getFluidState(feet).isEmpty()) return false;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL)
            for (int dy = -1; dy <= 0; dy++) {
                c.set(feet.getX() + d.getStepX(), feet.getY() + dy, feet.getZ() + d.getStepZ());
                if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.WATER)) return true; // 溶岩の岸は湿った所ではない
            }
        return false;
    }

    /** いちばん近い足場(水底も含む。半径 r・上下 3)。無ければ null。 */
    static BlockPos nearestFooting(Level level, BlockPos from, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -3; dy <= 3; dy++) {
                    c.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    if (!level.getBlockState(c).canBeReplaced() || !footing(level, c)) continue;
                    double d = c.distSqr(from);
                    if (d < bestD) {
                        bestD = d;
                        best = c.immutable();
                    }
                }
        return best;
    }

    /** 根付くまでの残り(tick)。-1 = まだ決めていない。 */
    private int rootIn = -1;

    /** 胞子の場で動くか(オーバーワールドで場が有効)。そうでなければ今までの「その場で汚染を広げて消える」。 */
    private boolean fieldMode() {
        // 2026-10-23 F からネザーでも(相談セッションの答え: 萌芽体もネザーで出す)
        return com.yayuy.overgrownvegetation.spore.field.SporeSprouts.fieldLevel(this.level())
                && com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.enabled();
    }

    /** 湿った所(水辺・雨・夜・湿った土地)か。湿っていれば早く根付く。 */
    static double moisture(Level level, BlockPos pos) {
        if (level.dimension() == Level.NETHER) {
            // ネザー: 湿った所の代わりに「溶岩から離れた床」(周り 4 ブロック・上下 2 に溶岩が無い)。溶岩が近いほど低い
            int lava = 0;
            BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++)
                    for (int dy = -2; dy <= 1; dy++) {
                        c.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) lava++;
                    }
            return lava == 0 ? 1.0D : Math.max(0.0D, 0.6D - lava * 0.05D);
        }
        double m = 0;
        if (level.isRainingAt(pos.above())) m += 1;
        long tod = level.getDayTime() % 24000L;
        if (tod >= 13000 && tod < 23000) m += 0.5;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 0; dy++) {
                    c.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.WATER)) {
                        m += 0.4;
                    }
                }
        // 水の中(水底)はいちばん湿った所(2026-10-24 追記 18)
        if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) m = Math.max(m, 3.0D);
        return m;
    }

    /** 歩き回る先を、近くの数点のうちいちばん湿った所にする。 */
    static final class MoistStrollGoal extends net.minecraft.world.entity.ai.goal.RandomStrollGoal {
        MoistStrollGoal(PathfinderMob mob) {
            super(mob, 1.0D, 40);
        }

        @Override
        public boolean canUse() {
            // 湿った所に着いたらもう歩かない(そこで根付くのを待つ。10-23: 池に着いてもまた離れていた)
            return this.mob instanceof BlightFernEntity fern && fern.fieldMode() && !fern.isRooting()
                    && this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0
                    && !(this.mob.onGround() && moisture(this.mob.level(), this.mob.blockPosition()) >= 0.8D)
                    && super.canUse();
        }

        @Override
        protected Vec3 getPosition() {
            // 近くの数点から、いちばん湿った足場を選ぶ。水底も足場(2026-10-24 追記 18: 前は LandRandomPos で水を避け、乾いた足場だけ)。
            // 各点は列を上から下へたどって、置ける(空気か水の)足場の最初の 1 つ
            Level level = this.mob.level();
            BlockPos from = this.mob.blockPosition();
            BlockPos best = null;
            double bestScore = -1;
            BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 10; i++) {
                int x = from.getX() + this.mob.getRandom().nextInt(21) - 10, z = from.getZ() + this.mob.getRandom().nextInt(21) - 10;
                BlockPos p = null;
                for (int y = from.getY() + 4; y >= from.getY() - 6; y--) {
                    c.set(x, y, z);
                    if (level.getBlockState(c).canBeReplaced() && footing(level, c)) {
                        p = c.immutable();
                        break;
                    }
                }
                if (p == null) continue;
                double s = moisture(level, p) + (seabed(level, p) ? 1.5D : dryShore(level, p) ? 1.0D : 0.0D)
                        + this.mob.getRandom().nextDouble() * 0.2;
                if (s > bestScore) {
                    bestScore = s;
                    best = p;
                }
            }
            // 行き先を選んだ(道が見つからず歩き出せなくても「行き先がある」。穴の底からは道が無いことが多い)
            if (best != null && this.mob instanceof BlightFernEntity fern) fern.stuckWanting = true;
            return best == null ? null : Vec3.atBottomCenterOf(best);
        }
    }

    /**
     * 描画の箱(画面の外の判定)。萌芽体の当たり判定は 1 × 2 だが見た目は背丈 3.65・巻きと根の脚で横に約 1 ブロック広がるので、
     * 当たり判定のままだと頭だけ見えているときに丸ごと描かれなくなる(2026-10-23)。
     */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().inflate(0.9D, 0.0D, 0.9D).expandTowards(0.0D, 1.8D, 0.0D);
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

        if (this.level() instanceof ServerLevel serverLevel && fieldMode()) {
            if (!this.isDeadOrDying()) tickRooting(serverLevel);
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
     * 胞子の場の萌芽体(2026-10-23 第 2 段 F): 歩き回ってから根付く。根付くまでの長さは sprouts.wanderTicksMin〜Max(仮決め)、
     * 湿った所では 3 倍早く減る。根付いたら足元に根付いた姿(blight_fern_rooted)を置き、ふわっと弾けて消える
     * (そこから汚染の根が伸び、胞子を噴く)。
     */
    private void tickRooting(ServerLevel level) {
        if (rootSpot != null) {
            tickRootDown(level);
            return;
        }
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        if (rootIn < 0) {
            rootIn = t.wanderMin() + this.random.nextInt(Math.max(1, t.wanderMax() - t.wanderMin() + 1));
        }
        if (this.tickCount % 20 != 0) return;
        // 穴につっかえた(行き先があるのに位置が変わらない)まま stuckRootTicks たったら、その場で根付く(2026-10-24 追記 18【決定】)
        // (バニラのナビは動けない道を数秒で捨て、穴の底からは道そのものが無いので、「行き先を選んだ・持った」ことは動くまで覚えておく)
        if (!this.getNavigation().isDone()) stuckWanting = true;
        if (stuckFrom != null && this.position().distanceToSqr(stuckFrom) < 0.25D * 0.25D) {
            if (stuckWanting) stuckTicks += 20;
            if (stuckTicks >= t.stuckRootTicks()) rootIn = 0;
        } else {
            stuckTicks = 0;
            stuckWanting = false;
        }
        stuckFrom = this.position();
        double m = moisture(level, this.blockPosition());
        rootIn = Math.max(0, rootIn - (m >= 0.8D ? 60 : 20)); // 0 で止める(負にすると「まだ決めていない」と取り違えて歩き直していた。10-24)
        if (rootIn > 0 || !this.onGround()) return; // 水底でも根付く(2026-10-24 追記 18)
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) return;
        // 真下が空気(崖の縁に体の端で乗っている)なら、周り 1 ブロックの固い足場へ根を下ろす。
        // それも無ければ少し歩き直し、3 回だめなら半径 4 のいちばん近い足場に根付く(無限にやり直さない)。
        BlockPos at = rootFailures >= 3 ? nearestFooting(level, this.blockPosition(), 4)
                : footing(level, this.blockPosition()) ? this.blockPosition()
                : nearestFooting(level, this.blockPosition(), 1);
        BlockPos spot = at == null ? null : com.yayuy.overgrownvegetation.spore.field.SporeSprouts.rootedSpot(level, at);
        if (spot != null) {
            // 2026-10-23 萌芽体のモデル: その場で止まり、root アニメ(1.5 秒)を流してからブロックに替わる
            rootSpot = spot;
            rootTicks = 0;
            this.getNavigation().stop();
            this.entityData.set(DATA_ROOTING, true);
        } else {
            rootFailures++;
            rootIn = 100; // 根付けない場所(足元が柔らかい等): もう少し歩く
        }
    }

    /** 根を下ろしている間: 根付く場所の真ん中へ寄り、{@link #ROOT_TICKS} の後に根付いた姿のブロックへ替わる(向きを渡して見た目をつなぐ)。 */
    private void tickRootDown(ServerLevel level) {
        rootTicks++;
        this.getNavigation().stop();
        double tx = rootSpot.getX() + 0.5D, tz = rootSpot.getZ() + 0.5D;
        if (rootTicks <= 10) {
            this.setPos(this.getX() + (tx - this.getX()) * 0.3D, this.getY(), this.getZ() + (tz - this.getZ()) * 0.3D);
        }
        if (rootTicks < ROOT_TICKS) return;
        if (com.yayuy.overgrownvegetation.spore.field.SporeSprouts.placeRootedAt(level, rootSpot, this.yBodyRot, true)) {
            spawnRootPoof(level);
            this.discard();
        } else {
            rootSpot = null;
            this.entityData.set(DATA_ROOTING, false);
            rootFailures++;
            rootIn = 100;
        }
    }

    /** つっかえ判定: 1 秒前の位置と、位置が変わらないまま行き先を持っていた長さ(保存しない)。 */
    private Vec3 stuckFrom;
    private int stuckTicks;
    private boolean stuckWanting;

    /** テスト用: 根付きの途中の様子。 */
    public String rootDebugForTests() {
        return "rootIn " + rootIn + "・失敗 " + rootFailures + "・つっかえ " + stuckTicks + "・足場 " + footing(this.level(), this.blockPosition())
                + "・位置 " + this.blockPosition().toShortString() + "・ナビ " + (this.getNavigation().isDone() ? "なし" : "あり");
    }

    /** テスト用: 歩き回る時間を飛ばして、足が着いたらすぐ根付かせる。 */
    public void rootSoonForTests() {
        rootIn = 0;
    }

    /** 根付く場所(決めてから root アニメを流している間だけ。保存しない)。 */
    private BlockPos rootSpot;
    private int rootTicks;

    /** ブロックに替わる瞬間の小さな胞子(見た目のつなぎ目を隠す程度。消える演出 {@link #spawnFinishPoof} より控えめ)。 */
    private void spawnRootPoof(ServerLevel level) {
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                this.getX(), this.getY() + 0.4D, this.getZ(), 16, 0.6D, 0.3D, 0.6D, 0.01D);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ROOTED_DIRT_PLACE, this.getSoundSource(), 1.0F, 0.8F);
    }

    /** 根付こうとして足場が無かった回数(保存しない。3 回で近くの足場へ落とす)。 */
    private int rootFailures;

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
        // 水の中では泡(2026-10-24 追記 18)
        this.level().addParticle(this.isInWater() ? ParticleTypes.BUBBLE : ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0.0D, this.isInWater() ? 0.05D : -0.01D, 0.0D);
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
        // 行き先がある(ナビが動いている・歩く入力がある)あいだは飛ばさない(10-23: 萌芽体が 1 歩目で止まっていた)
        if (!this.level().isClientSide()
                && OvergrownVegetationConfig.COMMON.blightFernDormantPhysics.get()
                && this.getNavigation().isDone()
                && travelVector.lengthSqr() < 1.0E-7D
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
     * 水中でも成り立つ(沈みきって底に乗っていれば速度は僅かな負の値に落ち着く)。
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
        tag.putInt("RootIn", rootIn);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        applyStepHeight();
        spreader.load(tag);
        if (tag.contains("RootIn")) rootIn = tag.getInt("RootIn");
        // 10-23 より前の萌芽体は体格 2.5 が保存されている(属性は個体ごとに保存)。今の既定(1)へ戻す —— でないと 9 ブロックの背丈で描かれる
        var scale = this.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier(com.yayuy.overgrownvegetation.registry.ModEntities.BLIGHT_FERN.get()).getBaseValue(Attributes.SCALE));
        }
    }
}
