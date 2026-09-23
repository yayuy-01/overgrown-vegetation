package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
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
 * グループ3(原生消費種)の陸生捕食 Mob 共通の土台 — ペトロラコサウルス・イアンタサウルス・
 * ヒロノムスなど、原始的な小型爬虫類/単弓類。
 *
 * <p>索敵対象は3つ:
 * <ol>
 *     <li>捕食対象 — {@code config/overgrown_vegetation/predation.json}({@link Predation})に
 *     載っている被食者を一方的に狩る({@link OvergrownHunterTargetGoal} + {@link MeleeAttackGoal})。</li>
 *     <li>侵入者 — 縄張りに入ってきたプレイヤー・バニラの敵対 Mob({@link Predation#isIntruder})にも
 *     先制攻撃する。被食者(グループ2)や他の過繁茂 Mob・村人・動物は襲わない。</li>
 *     <li>反撃 — 殴られたら相手に反撃する({@link OvergrownHurtByTargetGoal})。</li>
 * </ol>
 *
 * <p>倒すたびに攻撃力・体力が {@code preyKillBonusPerKill} ずつ上昇する({@link PredatorMob}、
 * 捕食数は NBT {@code "PreyKills"} に保存)。{@link ParasitizedMob} 実装で「胞子感染」に一切かからず、
 * 他の過繁茂 Mob からも標的にされない。
 */
public abstract class AbstractOvergrownPredatorEntity extends PathfinderMob
        implements GeoEntity, ParasitizedMob, PredatorMob {

    /** 通常(idle / walk を移動状態で切替)。 */
    public static final int STATE_IDLE = 0;
    /** 種族固有の特殊動作中(イアンタサウルスの「日光浴」など)。 */
    public static final int STATE_SPECIAL = 1;

    protected static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(AbstractOvergrownPredatorEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private int preyKillCount;

    protected AbstractOvergrownPredatorEntity(EntityType<? extends AbstractOvergrownPredatorEntity> type, Level level) {
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
        registerSpeciesGoals();
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        // predation.json に載っている被食者を狩る。視線を無視する索敵(茂った過繁茂バイオームでも
        // 相手を見失わない。詳細は OvergrownHunterTargetGoal 参照)。
        // 水中・水面にいる相手は狙わない(OvergrownLandTargetGoal / Predation#isLandTarget)。
        this.targetSelector.addGoal(2, new OvergrownLandTargetGoal(this, Predation::isPredatorOf));
        // 縄張りに入ってきた侵入者(プレイヤー・バニラの敵対 Mob)にも先制攻撃する。
        this.targetSelector.addGoal(3, new OvergrownLandTargetGoal(this, Predation::isIntruder));
    }

    /** 種族固有のゴール(優先度 1〜3 目安)。 */
    protected void registerSpeciesGoals() {
    }

    // --- 捕食数(PredatorMob) ---

    @Override
    public int getPreyKillCount() {
        return this.preyKillCount;
    }

    @Override
    public void setPreyKillCount(int count) {
        this.preyKillCount = count;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PreyKills", this.preyKillCount);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.preyKillCount = tag.getInt("PreyKills");
        PredatorMob.applyPreyKillBonus(this, this.preyKillCount);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
