package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * スケルトンの 1 段目の (c)「根を張って据え撃ち」(2026-11-13、能力の第 3 回。仕様 Vault「G1 スケルトン(1 段目)」3 節 A【決定 11-09・数は仮】)。
 * 足の根を地面へ突き刺して体を固定し、{@value #ANCHOR_TICKS} tick の間、射程・精度・弾速が良くなる({@link G1BowShotGoal})。
 * その間は動かない・押し返されない・振り向きが遅い(1 秒 120°【仮】。近接の距離まで詰めて回り込むのが対処)・薙がない。時間が来たら根を抜いて(20 tick)歩き出す。
 *
 * <ul>
 *   <li>使う条件: 据えていない・前に根を抜いてから {@value #COOLDOWN} tick・地面に立っている・標的が見えていて
 *   {@value #MIN_RANGE}〜{@link G1BowShotGoal#ANCHORED_RANGE} ブロック(仕様「10 ブロック以上離れて見えているときに据える」)。</li>
 *   <li>段: root_anchor 20(足元から根が潜り土が盛り上がる。終わりに据わる)。据えている間の姿勢 anchored_idle と根を抜く root_release は
 *   {@link DevelopedParasitized} が流す。</li>
 * </ul>
 */
public class G1RootAnchorGoal extends G1TechniqueGoal {

    public static final int ANCHOR_TICKS = 200;
    public static final int RELEASE_TICKS = 20;
    public static final int COOLDOWN = 300;
    public static final double MIN_RANGE = 10.0D;

    public G1RootAnchorGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.ROOT_ANCHOR}, new int[]{20});
    }

    @Override
    protected boolean ready() {
        if (mob.anchored() || !mob.anchorReady() || !mob.onGround()) return false;
        if (target == null || !target.isAlive() || !mob.canAttack(target) || !mob.getSensing().hasLineOfSight(target)) return false;
        double d = mob.distanceTo(target);
        return d >= mob.anchorMinRange() && d <= G1BowShotGoal.ANCHORED_RANGE;
    }

    @Override
    protected int aimUntil() {
        return 20;
    }

    @Override
    protected int cooldown() {
        return 0;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (tickInStage == 2 || tickInStage == 10) {
            // 根が地面へ潜り、土が盛り上がる
            BlockPos below = BlockPos.containing(mob.getX(), mob.getY() - 0.2D, mob.getZ());
            BlockState ground = level.getBlockState(below);
            if (!ground.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), mob.getX(), mob.getY() + 0.1D, mob.getZ(),
                        10, 0.3D, 0.05D, 0.3D, 0.05D);
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 0.8F, 0.6F);
        }
        if (tickInStage == 20) {
            mob.startAnchor(ANCHOR_TICKS); // 段が終わる前に据える(終わりの行動の段が anchored_idle になる)
        }
    }
}
