package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * グループ2(原生生産種)の陸生 Mob 共通の土台。
 *
 * <p>他の多くの過繁茂 Mob と違い、<b>他の Mob を一切攻撃しない</b> — 索敵/近接攻撃ゴールを持たず、
 * {@link HurtByTargetGoal} も積まない。殴られたら {@link PanicGoal} で逃げ、対応するグループ3の
 * 捕食者({@link Predation})が近づいたら {@link CritterAvoidPredatorGoal} で距離を取る。
 *
 * <p>{@link ParasitizedMob} を実装しているので「胞子感染」に一切かからず、他の過繁茂 Mob からも
 * 標的にされない({@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal})。
 */
public abstract class AbstractOvergrownCritterEntity extends PathfinderMob implements GeoEntity, ParasitizedMob {

    /** 通常(idle / walk を移動状態で切替)。 */
    public static final int STATE_IDLE = 0;
    /** 種族固有の特殊動作中(プロトファスマの「鳴き」など)。 */
    public static final int STATE_SPECIAL = 1;
    /** 茂みの中でうずくまってやり過ごし中(プロトファスマの {@link ProtophasmaHideInBushGoal})。 */
    public static final int STATE_HIDE = 2;

    protected static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(AbstractOvergrownCritterEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected AbstractOvergrownCritterEntity(EntityType<? extends AbstractOvergrownCritterEntity> type, Level level) {
        super(type, level);
        // 葉は当たり判定が 1 ブロックまるごとある。malus 0(= 通れる扱い)にすると葉の中へ突っ込む
        // 経路が引かれ、実際には入れずに詰まる。バニラ既定どおり通らせない(2026-09-14)。
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
    }

    /**
     * 幅 1 ブロック以上の Mob ではバニラが省略してしまう「段差の上に体が入るか」の確認を、
     * 自前の {@link OvergrownNodeEvaluator} で必ず行うようにする(登れない段差に挑み続けない)。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new OvergrownNodeEvaluator();
                this.nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_IDLE);
    }

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.5D));
        this.goalSelector.addGoal(2, new CritterAvoidPredatorGoal(this));
        registerSpeciesGoals();
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    /** 種族固有のゴール(優先度 3〜6 目安)。 */
    protected void registerSpeciesGoals() {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
