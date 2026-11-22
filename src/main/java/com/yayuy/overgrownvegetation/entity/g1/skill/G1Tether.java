package com.yayuy.overgrownvegetation.entity.g1.skill;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 絡めて引き寄せる(2026-11-12、能力の第 1 回。共通の部品 —— ゾンビの肩の蔦 (c) のほか、後の回の種(クモの糸など)も使える)。
 * 巻き付いている間、標的を遅くし、持ち手の方へ引く。
 *
 * <ul>
 *   <li>引く: 毎 tick、標的の速度へ持ち手の方への {@code pull} ブロック/tick を足す({@link #CLOSE_ENOUGH} まで近づいたら引かない)。
 *   プレイヤーは速度をクライアントが持つので {@code hurtMarked} で送る。</li>
 *   <li>遅くする: 鈍足(強さ {@code slowAmplifier})をかけ直す。</li>
 *   <li>切れる: 長さ {@code maxTicks}・標的が持ち手を殴った({@link #onHolderHurtBy}。仕様「蔦を殴る(1 撃で切れる)」)・
 *   走って振りほどいた(持ち手から {@code breakDistance} より離れた)・どちらかが死んだ。</li>
 *   <li>見た目: 持ち手の肩から標的まで、苔の粒を点線のように 2 tick おきに出す(蔦を引き伸ばすアニメは無いので、粒で結ぶ)。</li>
 * </ul>
 */
public final class G1Tether {

    /** ここまで近づいたら引かない(ブロック)。【仮】 */
    static final double CLOSE_ENOUGH = 1.6D;

    private final Mob holder;
    private final LivingEntity target;
    private final int maxTicks;
    private final double pull;
    private final int slowAmplifier;
    private final double breakDistance;
    private int ticks;
    private boolean cut;

    public G1Tether(Mob holder, LivingEntity target, int maxTicks, double pull, int slowAmplifier, double breakDistance) {
        this.holder = holder;
        this.target = target;
        this.maxTicks = maxTicks;
        this.pull = pull;
        this.slowAmplifier = slowAmplifier;
        this.breakDistance = breakDistance;
        holder.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.CAVE_VINES_PLACE, SoundSource.HOSTILE, 1.0F, 0.7F);
    }

    public LivingEntity target() {
        return target;
    }

    public boolean isCut() {
        return cut;
    }

    /** @return まだ巻き付いているか */
    public boolean tick() {
        if (cut) return false;
        ticks++;
        double dist = holder.distanceTo(target);
        if (ticks > maxTicks || !holder.isAlive() || !target.isAlive() || target.isRemoved() || dist > breakDistance) {
            cut(false);
            return false;
        }
        if (slowAmplifier >= 0) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6, slowAmplifier, false, false), holder);
        }
        if (dist > CLOSE_ENOUGH) {
            Vec3 to = holder.position().subtract(target.position()).multiply(1.0D, 0.0D, 1.0D).normalize().scale(pull);
            Vec3 v = target.getDeltaMovement();
            target.setDeltaMovement(v.x * 0.6D + to.x, v.y, v.z * 0.6D + to.z);
            target.hurtMarked = true;
        }
        if (ticks % 2 == 0 && holder.level() instanceof ServerLevel level) {
            Vec3 a = holder.position().add(0.0D, holder.getBbHeight() * 0.8D, 0.0D);
            Vec3 b = target.position().add(0.0D, target.getBbHeight() * 0.3D, 0.0D);
            int n = Math.max(3, (int) (a.distanceTo(b) * 3.0D));
            BlockParticleOption vine = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.VINE.defaultBlockState());
            for (int i = 1; i < n; i++) {
                Vec3 p = a.lerp(b, i / (double) n);
                level.sendParticles(vine, p.x, p.y, p.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
        return true;
    }

    /** 持ち手が殴られた: 巻かれている相手からなら蔦が切れる。 */
    public void onHolderHurtBy(LivingEntity attacker) {
        if (attacker == target) {
            cut(true);
        }
    }

    public void cut(boolean struck) {
        if (cut) return;
        cut = true;
        if (struck) {
            holder.level().playSound(null, holder.getX(), holder.getY() + 1.0D, holder.getZ(), SoundEvents.CAVE_VINES_BREAK, SoundSource.HOSTILE, 1.0F, 0.8F);
        }
    }
}
