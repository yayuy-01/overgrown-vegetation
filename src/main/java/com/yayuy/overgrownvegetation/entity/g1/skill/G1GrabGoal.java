package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * エンダーマンの 1 段目の (c-1)「根で掴んで連れて行く」(2026-11-13、能力の第 4 回。仕様 Vault「G1 エンダーマン(1 段目)」3 節【決定 11-09・数は仮】)。
 * 左腕の根の束がほどけて前へ垂れ(前触れ {@value #WINDUP} tick)、伸びて目標に絡み、{@value #HOLD} tick 抱えたあと<b>一緒に転移</b>する
 * (行き先は近くの汚染の濃い方へ {@value G1Blink#MIN}〜{@value G1Blink#MAX} ブロック。{@link G1Blink})。
 *
 * <ul>
 *   <li>使う条件: 見えている標的が {@value #RANGE} ブロック以内・乗り物に乗っていない・根の技の間が明けている
 *   ({@link DevelopedParasitized#rootSkillReady} = (c-2) を使った後は 4 秒待つ)。休み {@value #COOLDOWN} tick。</li>
 *   <li>段: grab_windup {@value #WINDUP}({@value #AIM_UNTIL} tick まで向きを追う)→ grab 3(2 tick 目に届いているか)→ grab_hold {@value #HOLD} → teleport_in 5。</li>
 *   <li>掴めるのは、止めた体の向きの前({@value #CONE_DEG}° 以内)・{@value #RANGE} + 0.5 ブロック以内。横へ出ればよけられる。<b>盾で弾ける</b>。
 *   外れたら grab の段で終わる。</li>
 *   <li>抱えている間、相手は腕の前へ引き寄せられて動けない。<b>相手がエンダーマンを殴って {@value #RELEASE_DAMAGE} ダメージ入れると放す</b>
 *   ({@link DevelopedParasitized#grabDamage})。相手が死ぬ・離れる({@value #BREAK_DISTANCE} ブロック)でも放す。</li>
 *   <li>ダメージは無い(連れて行くだけ。転移の両端で噴く胞子は浴びる)。</li>
 * </ul>
 */
public class G1GrabGoal extends G1TechniqueGoal {

    public static final double RANGE = 4.0D;
    public static final int WINDUP = 14;
    static final int AIM_UNTIL = 11;
    public static final int HOLD = 20;
    public static final int COOLDOWN = 300;
    public static final float RELEASE_DAMAGE = 6.0F;
    static final float CONE_DEG = 50.0F;
    static final double BREAK_DISTANCE = 6.0D;
    /** 抱えている間、相手を置く所(体の前のブロック)。 */
    static final double HOLD_AHEAD = 1.3D;
    /** (c-1) と (c-2) を続けて使わない間(tick)。仕様「片方を使ったら、もう片方は 4 秒待つ」。 */
    public static final int ROOT_LOCKOUT = 80;

    @Nullable
    private LivingEntity held;
    private boolean missed;
    private boolean released;

    public G1GrabGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.GRAB_WINDUP, G1Move.GRAB, G1Move.GRAB_HOLD, G1Move.TELEPORT_IN},
                new int[]{WINDUP, 3, HOLD, G1TeleportGoal.ARRIVE});
    }

    @Override
    protected boolean ready() {
        if (!mob.rootSkillReady() || target == null || !target.isAlive() || !mob.canAttack(target) || target.isPassenger()) return false;
        return mob.distanceTo(target) <= RANGE && mob.getSensing().hasLineOfSight(target);
    }

    @Override
    protected int aimUntil() {
        return AIM_UNTIL;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected boolean interrupted() {
        return missed || released;
    }

    @Override
    public void start() {
        super.start();
        held = null;
        missed = false;
        released = false;
        mob.resetGrabDamage(null);
    }

    @Override
    public void stop() {
        super.stop();
        held = null;
        mob.resetGrabDamage(null);
        mob.lockRootSkills(ROOT_LOCKOUT);
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && tickInStage == 2) {
            // 根の束がほどけて前へ垂れる
            level.playSound(null, mob.getX(), mob.getY() + 1.6D, mob.getZ(), SoundEvents.HANGING_ROOTS_BREAK, SoundSource.HOSTILE, 0.9F, 0.6F);
        } else if (stageIndex == 1 && tickInStage == 2) {
            if (reaches(target)) {
                held = target;
                mob.resetGrabDamage(held);
                level.playSound(null, held.getX(), held.getY(), held.getZ(), SoundEvents.CAVE_VINES_PLACE, SoundSource.HOSTILE, 1.0F, 0.6F);
            } else {
                missed = true;
            }
        } else if (stageIndex == 2) {
            if (held == null) {
                missed = true;
                return;
            }
            if (!held.isAlive() || held.isRemoved() || mob.distanceTo(held) > BREAK_DISTANCE || mob.grabDamage() >= RELEASE_DAMAGE) {
                // 放す(殴られた・相手が死んだ・離れた)
                level.playSound(null, mob.getX(), mob.getY() + 1.2D, mob.getZ(), SoundEvents.CAVE_VINES_BREAK, SoundSource.HOSTILE, 1.0F, 0.7F);
                released = true;
                return;
            }
            hold(level, held);
            if (tickInStage == HOLD) {
                carryAlong(level, held);
            }
        }
    }

    /** 止めた体の向きの前にいて届くか(盾で弾かれない)。 */
    private boolean reaches(@Nullable LivingEntity e) {
        if (e == null || !e.isAlive() || e.isPassenger() || mob.distanceTo(e) > RANGE + 0.5D || !mob.getSensing().hasLineOfSight(e)) return false;
        double dx = e.getX() - mob.getX(), dz = e.getZ() - mob.getZ();
        if (dx * dx + dz * dz > 0.25D) {
            float to = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            if (Math.abs(Mth.wrapDegrees(to - lockedYaw)) > CONE_DEG) return false;
        }
        return !e.isDamageSourceBlocked(mob.damageSources().mobAttack(mob));
    }

    /** 抱えている間: 相手を腕の前へ引き寄せて留める。根の粒で結ぶ。 */
    private void hold(ServerLevel level, LivingEntity e) {
        Vec3 face = Vec3.directionFromRotation(0.0F, lockedYaw);
        Vec3 at = mob.position().add(face.x * HOLD_AHEAD, 0.0D, face.z * HOLD_AHEAD);
        Vec3 to = at.subtract(e.position());
        e.setDeltaMovement(to.x * 0.5D, Math.max(-0.1D, Math.min(0.2D, to.y * 0.5D)), to.z * 0.5D);
        e.hurtMarked = true;
        e.fallDistance = 0.0F;
        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6, 3, false, false), mob);
        if (mob.tickCount % 2 == 0) {
            Vec3 a = mob.position().add(0.0D, mob.getBbHeight() * 0.55D, 0.0D);
            Vec3 b = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            int n = Math.max(3, (int) (a.distanceTo(b) * 3.0D));
            BlockParticleOption root = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.HANGING_ROOTS.defaultBlockState());
            for (int i = 1; i < n; i++) {
                Vec3 p = a.lerp(b, i / (double) n);
                level.sendParticles(root, p.x, p.y, p.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
    }

    /** 一緒に転移する(汚染の濃い方へ)。行き先が無ければその場で放す。 */
    private void carryAlong(ServerLevel level, LivingEntity e) {
        Vec3 to = G1Blink.pickAround(mob, mob.teleportMin(), mob.teleportMax(), ground -> G1Blink.corruptionAround(level, ground), 8);
        if (to == null) {
            released = true;
            return;
        }
        G1Blink.go(mob, to);
        G1Blink.bring(mob, e, to, lockedYaw);
        mob.markTeleportUsed(G1TeleportGoal.COOLDOWN);
        mob.countGrabCarried(e);
    }

    /** 今抱えている相手(GameTest・確かめ用)。 */
    @Nullable
    public LivingEntity held() {
        return held;
    }
}
