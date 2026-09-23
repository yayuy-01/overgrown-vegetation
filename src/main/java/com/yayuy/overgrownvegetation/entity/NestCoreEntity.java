package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.nest.NestBuffs;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * 「巣の核」({@code nest_core}、2026-09-20)。女王室のいちばん奥、女王の後ろに据わっている。
 *
 * <h2>役割(ユーザー指定 2026-09-19)</h2>
 * <ul>
 *     <li><b>世界全体の過繫茂 Mob を永続強化する</b>。どの能力かは巣ごとに 1 回だけ抽選され、
 *     <b>プレイヤーには見せない</b>。複数の巣で重複し、上限は無い({@link NestBuffs})。</li>
 *     <li><b>倒すと噴出孔が止まる</b>。胞子ノードを壊しに行かなくても、核を落とせばその巣の
 *     噴出孔は枯れる。強化も外れる(ただし<b>既に湧いた個体は戻らない</b>)。</li>
 * </ul>
 *
 * <p>{@link com.yayuy.overgrownvegetation.phase.ParasitizedMob} 実装なので「胞子感染」にかからない。
 * <b>これが無いと自分の巣の上で勝手に死ぬ</b> —— 濃度 100% の巣の中に居座るのが通常動作なので、
 * 30 秒で胞子感染1 が付き、その 60 秒後の満了で {@code entity.kill()} される
 * ({@code SporeInfectionExpiryHandler})。苗床・攻城戦ボスで 2 度起きた事故と同じもの(2026-09-20)。
 *
 * <p>動かない(AI ゴールを持たない)。見た目はハイヴノードと同じ作りで、
 * {@code geo/nest_core.geo.json} / {@code animations/nest_core.animation.json} /
 * {@code textures/entity/nest_core.png} を差し替えれば作り直せる。
 * <b>現状はハイヴノードを小さくして色を変えただけの仮モデル</b>。
 */
public class NestCoreEntity extends PathfinderMob
        implements GeoEntity, com.yayuy.overgrownvegetation.phase.ParasitizedMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** この核が上げる能力。null = まだ抽選していない(最初の tick で引く)。 */
    @Nullable
    private NestBuffs.Stat stat;
    /** この巣の胞子ノードの場所。倒されたときに一緒に枯らす。 */
    @Nullable
    private BlockPos sporeNode;
    private boolean registered;

    public NestCoreEntity(EntityType<? extends NestCoreEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setNoGravity(true);
        this.xpReward = MobStats.xpReward("nest_core", 40);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "nest_core");
    }

    /** 巣を掘り終えたときに胞子ノードの場所を渡す({@code nest/NestContents})。 */
    public void setSporeNode(BlockPos pos) {
        this.sporeNode = pos.immutable();
    }

    @Override
    protected void registerGoals() {
        // 動かない
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel serverLevel)) {
            if (level().isClientSide && random.nextInt(6) == 0) {
                level().addParticle(ParticleTypes.SPORE_BLOSSOM_AIR,
                        getX() + (random.nextDouble() - 0.5D), getY() + 0.6D, getZ() + (random.nextDouble() - 0.5D),
                        0.0D, 0.01D, 0.0D);
            }
            return;
        }
        if (!registered) {
            if (stat == null) {
                stat = NestBuffs.roll(random);
            }
            NestBuffs.add(serverLevel, getUUID(), stat);
            registered = true;
        }
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel serverLevel) {
            NestBuffs.remove(serverLevel, getUUID());
            // 核を落とせば噴出孔も止まる(胞子ノードを壊しに行かなくてよい)
            if (sporeNode != null && serverLevel.getBlockState(sporeNode).is(ModBlocks.SPORE_NODE.get())) {
                serverLevel.setBlockAndUpdate(sporeNode, Blocks.AIR.defaultBlockState());
            }
        }
        super.die(source);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (stat != null) {
            tag.putString("Stat", stat.name());
        }
        if (sporeNode != null) {
            tag.putLong("SporeNode", sporeNode.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Stat")) {
            try {
                stat = NestBuffs.Stat.valueOf(tag.getString("Stat"));
            } catch (IllegalArgumentException ignored) {
                stat = null;
            }
        }
        sporeNode = tag.contains("SporeNode") ? BlockPos.of(tag.getLong("SporeNode")) : null;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SLIME_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SLIME_DEATH;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, state -> state.setAndContinue(IDLE_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
