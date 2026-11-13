package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 地面に落ちた熟した莢(2026-11-13、能力の第 3 回。クリーパーの 1 段目の (c)「莢を落とす」。仕様 Vault「G1 クリーパー(1 段目)」3 節【決定 11-09】)。
 * 実体 1 つ(生き物ではない)。落ちて地面に転がり、{@link #fuse()} tick(仕様 10 秒【仮】)かけて脈打ちながら膨らみ、割れて小さく撒く
 * ({@link G1SporeBlast}: 汚染の半径 {@value #CORRUPT_RADIUS}・升の浮遊 +{@value #AIR_PERCENT}%・半径 {@value #DOSE_RADIUS} へ暴露量 +{@value #DOSE}。ダメージ無し)。
 *
 * <ul>
 *   <li><b>叩けば割れずに消える</b>【決定 11-09】: どんなダメージでも 1 回で潰れ、撒かない(湿った小さな音とかけらの粒だけ)。</li>
 *   <li>絵はクリーパーの背の莢の箱 1 つと同じ画素({@code client/G1SeedPodRenderer}。新しい絵は無い)。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数と、落とす側 {@link G1DropPodGoal})。
 */
public class G1SeedPod extends Entity {

    /** 割れるまで(tick)。仕様「10 秒で膨らんで割れる」。 */
    public static final int FUSE_TICKS = 200;
    /** 割れたときに覆う半径・輪郭の揺らぎ。仕様「莢の汚染の半径 1」。 */
    static final int CORRUPT_RADIUS = 1;
    static final double CORRUPT_NOISE = 0.3D;
    /** 割れたときの升の浮遊(%。仕様は球の式で +0.5 → 11-12「G1 だけ小さく」に合わせて % で)。 */
    static final double AIR_PERCENT = 5.0D;
    /** 割れたときの暴露量と届く半径。仕様「+150(半径 2)」。 */
    static final double DOSE = 150.0D;
    static final double DOSE_RADIUS = 2.0D;
    /** 脈打つ間隔(tick)。終わりに向けて速くなる: 残りがこの値を切るごとに次の段。描画も同じ表を使う。 */
    public static final int[] PULSE_PERIOD = {40, 26, 16, 10};

    private static final EntityDataAccessor<Integer> FUSE = SynchedEntityData.defineId(G1SeedPod.class, EntityDataSerializers.INT);

    public G1SeedPod(EntityType<? extends G1SeedPod> type, Level level) {
        super(type, level);
    }

    /** 莢を 1 つ落とす(サーバー)。{@code at} から {@code push} の速さで転がり出る。 */
    public static G1SeedPod drop(ServerLevel level, Vec3 at, Vec3 push) {
        G1SeedPod pod = new G1SeedPod(ModG1.SEED_POD.get(), level);
        pod.setPos(at);
        pod.setDeltaMovement(push);
        pod.setYRot(level.random.nextFloat() * 360.0F);
        level.addFreshEntity(pod);
        return pod;
    }

    /** その点のまわり {@code radius} ブロックの莢(落とす側が「近くに 3 つまで」を数える)。 */
    public static List<G1SeedPod> near(Level level, Vec3 at, double radius) {
        return level.getEntitiesOfClass(G1SeedPod.class, new AABB(at, at).inflate(radius), Entity::isAlive);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(FUSE, FUSE_TICKS);
    }

    public int fuse() {
        return this.entityData.get(FUSE);
    }

    /** 割れるまでの長さを変える(GameTest・確かめ用)。 */
    public void setFuse(int ticks) {
        this.entityData.set(FUSE, Math.max(1, ticks));
    }

    /** 脈の間隔(tick)。残りが少ないほど短い。 */
    public static int pulsePeriod(int age, int fuse) {
        float f = fuse <= 0 ? 1.0F : (float) age / fuse;
        return PULSE_PERIOD[Math.min(PULSE_PERIOD.length - 1, (int) (f * PULSE_PERIOD.length))];
    }

    @Override
    public void tick() {
        super.tick();
        // 落ちて転がる(両側で動かす。位置はサーバーが 10 tick ごとに送る)
        if (!isNoGravity()) {
            setDeltaMovement(getDeltaMovement().add(0.0D, -0.04D, 0.0D));
        }
        move(MoverType.SELF, getDeltaMovement());
        Vec3 v = getDeltaMovement();
        setDeltaMovement(onGround() ? new Vec3(v.x * 0.6D, 0.0D, v.z * 0.6D) : v.scale(0.98D));
        if (!(level() instanceof ServerLevel level)) return;
        if (tickCount == 4) {
            // 「ぽとり」
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.7F, 0.6F);
        }
        int fuse = fuse();
        if (tickCount >= fuse) {
            burst(level);
            return;
        }
        if (tickCount > fuse / 2 && tickCount % pulsePeriod(tickCount, fuse) == 0) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.35F, 0.8F + 0.5F * tickCount / fuse);
        }
    }

    private void burst(ServerLevel level) {
        Vec3 at = position();
        discard();
        // 覆う球の中心は莢の下のブロックに置く(莢のいる空気のマスを中心にすると、半径 1 では下の地面が縁になって半分以上は覆われない)
        G1SporeBlast.blast(level, null, at.add(0.0D, -0.5D, 0.0D), CORRUPT_RADIUS, CORRUPT_NOISE, AIR_PERCENT, DOSE, DOSE, DOSE_RADIUS, 0.0F, 0.0D);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.HOSTILE, 0.7F, 1.1F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 0.5F, 1.2F);
    }

    /** 叩かれた: 割れずに潰れて消える(撒かない)。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || isRemoved()) return false;
        if (level() instanceof ServerLevel level) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(ModBlocks.OVERGROWN_MOSS_LAYER.get())),
                    getX(), getY() + 0.2D, getZ(), 8, 0.12D, 0.1D, 0.12D, 0.05D);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.6F, 1.3F);
            discard();
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Fuse")) setFuse(tag.getInt("Fuse"));
        this.tickCount = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Fuse", fuse());
        tag.putInt("Age", tickCount);
    }
}
