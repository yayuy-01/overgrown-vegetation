package com.yayuy.overgrownvegetation.ally;

import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 同盟軍の兵士(2026-10-01、仮)。人間の軍隊 —— 別の陣営だがプレイヤーとは敵対しない(ユーザー指定)。
 * 見た目はスティーブに軍服(兵種ごとのスキン、{@link SoldierKind})、手に HK416。
 *
 * <p>狙う相手: 過繁茂の Mob(グループ 1〜10、{@link MobGroup})と、バニラの敵対 Mob(エンダーマン・ゾンビピグリンは除く)。
 * プレイヤーと兵士は狙わない(撃たれても撃ち返さない)。銃は線で当てる({@link SoldierGun})。砲兵は離れた相手へ迫撃砲も撃つ。
 *
 * <p>守る基地({@link #siteId})があれば、その中心から基地の大きさの範囲に留まり、範囲の外へ出たら戻る。
 * MONSTER 区分・{@code Enemy}・グループのタグには入れない(TD の片付けやピースフルの敵の判定に巻き込まれないため、メモリ「同盟軍と壁付きの基地」)。
 */
public class SoldierEntity extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> AIMING = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);

    private final SoldierKind kind;
    /** 守る基地の番号(0 = 無し)。 */
    private int siteId;
    private int mortarCooldown;
    /** 飛んでいる迫撃砲の弾(着弾点と、着弾までの tick)。 */
    private final List<Shell> shells = new ArrayList<>();

    private record Shell(Vec3 at, int[] ticks) {
    }

    public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level, SoldierKind kind) {
        super(type, level);
        this.kind = kind;
        this.xpReward = 0;
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
    }

    public static AttributeSupplier.Builder attributes(SoldierKind kind) {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, kind.maxHealth)
                .add(Attributes.ARMOR, kind.armor)
                .add(Attributes.MOVEMENT_SPEED, kind.speed)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0);
    }

    public SoldierKind kind() {
        return kind;
    }

    public int siteId() {
        return siteId;
    }

    /** 守る基地を決める(中心と、基地の大きさの範囲に留める)。 */
    public void guard(int siteId, BlockPos center, int radius) {
        this.siteId = siteId;
        restrictTo(center, radius);
        setPersistenceRequired();
    }

    public boolean isAiming() {
        return entityData.get(AIMING);
    }

    void setAiming(boolean aiming) {
        entityData.set(AIMING, aiming);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AIMING, false);
    }

    /**
     * 工兵の種類か({@link #kind} はコンストラクタの中の {@code registerGoals}・{@code createNavigation} ではまだ入っていないので、種類で見る)。
     */
    private boolean engineerType() {
        return getType() == AllyEntities.type(SoldierKind.ENGINEER);
    }

    /** 工兵は柵の門を開けて通る経路探し(整備で外周の外まで出る。順 2 の着手順 4)。 */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return engineerType() ? new EngineerNavigation(this, level) : super.createNavigation(level);
    }

    /**
     * 工兵ははしごを整備の登り方(重力を切って座標で動かす。{@code EngineerWorkGoal})でだけ上り下りし、バニラのはしごの物理
     * (はしごのマスで横にぶつかると上がる)は使わない。仕事の合間にはしごのマスへ寄ったまま倉庫へ歩き出すと、塔の壁に当たって
     * 上の端まで運ばれ、降りる経路が無いので仕事をあきらめていた(2026-10-18、GameTest の揺れから。Claude 仮決め)。
     */
    @Override
    public boolean onClimbable() {
        return !engineerType() && super.onClimbable();
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        if (engineerType()) { // 整備(順 2 の着手順 4、2026-10-04): 敵が近い間は撃つ方が先(仕事の側で止まる)
            goalSelector.addGoal(1, new com.yayuy.overgrownvegetation.base.build.EngineerWorkGoal(this));
            goalSelector.addGoal(3, new OpenGateGoal(this));
        }
        goalSelector.addGoal(2, new SoldierShootGoal(this));
        goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.9));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, SoldierEntity.class, Player.class).setAlertOthers());
        targetSelector.addGoal(2, new NearestFirstTargetGoal<>(this, Mob.class, 10, true, false, SoldierEntity::isFoe)); // 近い順に確かめる(選ぶ相手は同じ、2026-10-10)
        // 汚染シダ・汚染水草も除く(ユーザー指定 2026-10-02)。動く敵がいない間だけ(敵が見えたら 2 番が奪う)
        targetSelector.addGoal(3, new NearestFirstTargetGoal<>(this, BlightFernEntity.class, 10, true, false, e -> e.isAlive()));
    }

    /**
     * 兵士が先に狙う相手か: 過繁茂の Mob(グループ 1〜10)と、バニラの敵対 Mob(怒らせなければ襲ってこないエンダーマン・ゾンビピグリンは除く)。
     * 動かない汚染シダ・汚染水草はここに入れず、動く敵がいない間に別の Goal で狙う({@link #isTarget})。
     */
    public static boolean isFoe(LivingEntity e) {
        if (e instanceof SoldierEntity || e instanceof Player || !e.isAlive()) {
            return false;
        }
        if (e instanceof BlightFernEntity) {
            return false;
        }
        if (MobGroup.of(e) != null) {
            return true;
        }
        return e instanceof Enemy && !(e instanceof EnderMan) && !(e instanceof ZombifiedPiglin);
    }

    /** 見通し: 金網・鉄格子・柵・葉は見通せる({@link SoldierSight})。標的選び(Sensing)もこれを通る。 */
    @Override
    public boolean hasLineOfSight(net.minecraft.world.entity.Entity entity) {
        if (entity.level() != level()) {
            return false;
        }
        Vec3 from = new Vec3(getX(), getEyeY(), getZ());
        Vec3 to = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        if (to.distanceTo(from) > 128.0) {
            return false;
        }
        return SoldierSight.clip(level(), from, to, this).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** 撃ってよい相手か(先に狙う相手 + 汚染シダ・汚染水草)。迫撃砲の傷もこれで選ぶ。 */
    public static boolean isTarget(LivingEntity e) {
        return isFoe(e) || e instanceof BlightFernEntity && e.isAlive();
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof Player) && !(target instanceof SoldierEntity) && super.canAttack(target);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target instanceof Player || target instanceof SoldierEntity) {
            return;
        }
        super.setTarget(target);
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(AllyItems.HK416.get()));
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        setPersistenceRequired();
        return out;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSq) {
        return false;
    }

    // ---------- 迫撃砲(砲兵) ----------

    /** 迫撃砲を撃てるなら撃つ(着弾は {@link SoldierKind#MORTAR_FLIGHT} tick 後、少しぶれる)。撃ったら真。 */
    boolean tryMortar(LivingEntity target) {
        if (kind != SoldierKind.ARTILLERY || mortarCooldown > 0 || !(level() instanceof ServerLevel level)) {
            return false;
        }
        double d = distanceTo(target);
        if (d < SoldierKind.MORTAR_MIN || d > SoldierKind.MORTAR_MAX) {
            return false;
        }
        double spread = d * 0.06;
        Vec3 at = target.position().add(getRandom().nextGaussian() * spread, 0, getRandom().nextGaussian() * spread);
        shells.add(new Shell(at, new int[]{SoldierKind.MORTAR_FLIGHT}));
        mortarCooldown = SoldierKind.MORTAR_COOLDOWN;
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 2.0F, 0.6F);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.6, getZ(), 6, 0.2, 0.3, 0.2, 0.02);
        return true;
    }

    private void tickShells(ServerLevel level) {
        if (mortarCooldown > 0) {
            mortarCooldown--;
        }
        for (int i = shells.size() - 1; i >= 0; i--) {
            Shell s = shells.get(i);
            if (--s.ticks()[0] > 0) {
                continue;
            }
            shells.remove(i);
            Vec3 at = s.at();
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 12, 1.0, 0.5, 1.0, 0.05);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 1.1F);
            double r = SoldierKind.MORTAR_RADIUS;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(r),
                    SoldierEntity::isTarget)) {
                double d = e.position().distanceTo(at);
                if (d > r) {
                    continue;
                }
                e.invulnerableTime = 0;
                e.hurt(level.damageSources().explosion(this, this), (float) (SoldierKind.MORTAR_DAMAGE * (1.0 - 0.6 * d / r)));
            }
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level() instanceof ServerLevel level) {
            tickShells(level);
        }
    }

    // ---------- 保存 ----------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Site", siteId);
        if (hasRestriction()) {
            BlockPos c = getRestrictCenter();
            tag.putIntArray("Home", new int[]{c.getX(), c.getY(), c.getZ(), (int) getRestrictRadius()});
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        siteId = tag.getInt("Site");
        int[] h = tag.getIntArray("Home");
        if (h.length == 4) {
            restrictTo(new BlockPos(h[0], h[1], h[2]), h[3]);
        }
    }
}
