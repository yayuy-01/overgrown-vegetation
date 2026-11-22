package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染した地面から突き上がる根 1 本(2026-11-13、能力の第 4 回。エンダーマンの 1 段目の (c-2) の見た目)。
 * 当たり・ダメージは持たない(ダメージは {@link G1RootSlamGoal} が叩いた瞬間に入れる)。{@value #LIFE} tick で消える。保存しない。
 * 絵は今ある気根の先 {@code aerial_root_single_tip} を逆さに ×字の板で描く({@code client/G1RootSpikeRenderer}。新しい絵は作らない)。
 */
public class G1RootSpike extends Entity {

    /** 寿命(tick)。突き上がる 3・留まる・引っ込む 4。 */
    public static final int LIFE = 14;
    public static final int RISE = 3;
    public static final int SINK = 4;

    public G1RootSpike(EntityType<? extends G1RootSpike> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 地面のマス {@code ground} の上に 1 本出す(土のかけらの粒つき)。 */
    public static void spawn(ServerLevel level, BlockPos ground) {
        G1RootSpike spike = ModG1.ROOT_SPIKE.get().create(level);
        if (spike == null) return;
        double x = ground.getX() + 0.5D + (level.random.nextDouble() - 0.5D) * 0.3D;
        double z = ground.getZ() + 0.5D + (level.random.nextDouble() - 0.5D) * 0.3D;
        spike.setPos(x, ground.getY() + 1.0D, z);
        spike.setYRot(level.random.nextFloat() * 360.0F);
        level.addFreshEntity(spike);
        BlockState s = level.getBlockState(ground);
        if (!s.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), x, ground.getY() + 1.1D, z, 8, 0.15D, 0.1D, 0.15D, 0.08D);
        }
    }

    /** 突き出ている高さの割合(0〜1)。 */
    public float height(float partialTick) {
        float age = this.tickCount + partialTick;
        if (age < RISE) return age / RISE;
        if (age > LIFE - SINK) return Math.max(0.0F, (LIFE - age) / SINK);
        return 1.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && this.tickCount >= LIFE) {
            discard();
        }
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }
}
