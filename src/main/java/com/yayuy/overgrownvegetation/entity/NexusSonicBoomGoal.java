package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * {@code vine4} の遠距離攻撃。本体は回転させず、{@code vine4} の先端(houkou ボーン)から
 * ウォーデンと同じ衝撃波を放つ:
 * <ol>
 *   <li><b>溜め</b> {@code raidNexusSonicChargeTicks}(既定34)tick。この間 {@code vine4} 自体を
 *       {@link OvergrownNexusEntity#solveBoomAim}経由でゆっくり(イージングをかけて)対象へ
 *       向け続け、溜めが終わる = ちょうど向き終わったタイミングで発射する({@link #updateAim}）。
 *       {@code WARDEN_SONIC_CHARGE}。</li>
 *   <li><b>発射</b>: {@code houkou} の位置(概算)からウォーデンの式そのまま
 *       (直線状の {@code SONIC_BOOM} パーティクル + {@code WARDEN_SONIC_BOOM} + {@code sonicBoom}
 *       ダメージ + 強ノックバック)。発射方向の狭い角度内の対象({@link OvergrownNexusEntity#isHostileTarget}）
 *       全員に当たる。ただし盾で正面から防いだ相手はダメージ・ノックバックとも無効
 *       ({@link #isShieldBlocked}）。</li>
 *   <li><b>反動</b>(既定20tick、モデル側が vine4 先端をわずかに引く)。</li>
 *   <li><b>復帰</b>(既定15tick、狙い(ヨー・ピッチ)を発射時点の値から0へなめらかに戻す
 *       {@link #updateReturn}）— 反動が終わった瞬間に狙いだけ急に0へ戻ってしまうと
 *       「カクッ」と不自然に見えるため、専用のイージング区間を設けてある。</li>
 *   <li>{@code raidNexusSonicCooldownTicks}(既定300=15秒)tick のクールダウン後、再度狙う。</li>
 * </ol>
 * {@code raidNexusSonicMinRange}(既定8)より近い相手には発動しない(近距離は薙ぎ払いに任せる)。
 */
public class NexusSonicBoomGoal extends Goal {

    private static final int RECOIL_TICKS = 20;
    private static final int RETURN_TICKS = 15;
    /** 発射方向からこの角度(度)以内の対象に当たる。 */
    private static final double CONE_DEGREES = 16.0;

    private final OvergrownNexusEntity nexus;
    private LivingEntity target;
    private int chargeTotal;
    private int chargeLeft;
    private int recoilLeft;
    private int returnLeft;
    private float returnFromYaw;
    private float returnFromPitch;
    private int nextTime;

    public NexusSonicBoomGoal(OvergrownNexusEntity nexus) {
        this.nexus = nexus;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (nexus.isBoomBusy() || nexus.tickCount < nextTime) {
            return false;
        }
        LivingEntity t = findTarget();
        if (t == null) {
            return false;
        }
        this.target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return nexus.isAlive() && nexus.isBoomBusy();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        chargeTotal = Math.max(1, OvergrownVegetationConfig.COMMON.raidNexusSonicChargeTicks.get());
        chargeLeft = chargeTotal;
        nexus.setBoomState(OvergrownNexusEntity.BOOM_CHARGE);
        nexus.setBoomProgress(0.0F);
        nexus.playSound(SoundEvents.WARDEN_SONIC_CHARGE, 3.0F, 1.0F);
    }

    @Override
    public void stop() {
        nexus.setBoomState(OvergrownNexusEntity.BOOM_IDLE);
        nexus.setBoomProgress(0.0F);
        nexus.setBoomAimYaw(0.0F);
        nexus.setBoomAimPitch(0.0F);
        this.target = null;
    }

    @Override
    public void tick() {
        switch (nexus.getBoomState()) {
            case OvergrownNexusEntity.BOOM_CHARGE -> {
                if (target == null || !nexus.isHostileTarget(target)) {
                    endToIdle(OvergrownVegetationConfig.COMMON.raidNexusSonicCooldownTicks.get() / 2);
                    return;
                }
                double minRange = OvergrownVegetationConfig.COMMON.raidNexusSonicMinRange.get();
                if (nexus.distanceToSqr(target) < minRange * minRange) {
                    // 溜め中に近づかれすぎた。近距離は薙ぎ払いに任せて衝撃波は中断する。
                    endToIdle(OvergrownVegetationConfig.COMMON.raidNexusSonicCooldownTicks.get() / 2);
                    return;
                }
                updateAim();
                nexus.setBoomProgress(1.0F - (float) chargeLeft / chargeTotal);
                if (--chargeLeft <= 0) {
                    fireBoom();
                    nexus.setBoomState(OvergrownNexusEntity.BOOM_RECOIL);
                    nexus.setBoomProgress(0.0F);
                    recoilLeft = RECOIL_TICKS;
                }
            }
            case OvergrownNexusEntity.BOOM_RECOIL -> {
                nexus.setBoomProgress(1.0F - (float) recoilLeft / RECOIL_TICKS);
                if (--recoilLeft <= 0) {
                    returnFromYaw = nexus.getBoomAimYaw();
                    returnFromPitch = nexus.getBoomAimPitch();
                    nexus.setBoomState(OvergrownNexusEntity.BOOM_RETURN);
                    nexus.setBoomProgress(0.0F);
                    returnLeft = RETURN_TICKS;
                }
            }
            case OvergrownNexusEntity.BOOM_RETURN -> {
                updateReturn();
                if (--returnLeft <= 0) {
                    endToIdle(OvergrownVegetationConfig.COMMON.raidNexusSonicCooldownTicks.get());
                }
            }
            default -> { }
        }
    }

    /**
     * {@code vine4} を溜め中の対象へ向けるための追加ヨー角・ピッチ角を
     * {@link OvergrownNexusEntity#solveBoomAim} で探して同期する。対象が動けば追従する。
     *
     * <p>いきなり全角度を適用すると「ガクッ」と一瞬で振り向くように見えるため、溜めの経過
     * (0〜1)に {@link #smoothstep} をかけたイージング済みの割合だけを実際の角度に掛ける。
     * 溜めが終わる(=発射する)ちょうどそのタイミングで割合がほぼ1になり、houkou が対象を
     * 向き終わった直後に発射される。
     */
    private void updateAim() {
        float[] solved = nexus.solveBoomAim(target.getEyePosition());
        float progress = Mth.clamp(1.0F - (float) chargeLeft / chargeTotal, 0.0F, 1.0F);
        float eased = smoothstep(progress);
        nexus.setBoomAimYaw(solved[0] * eased);
        nexus.setBoomAimPitch(solved[1] * eased);
    }

    private static float smoothstep(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /**
     * 反動が終わった直後の狙い({@link #returnFromYaw}/{@link #returnFromPitch}、発射時点の値)から
     * 0(レストポーズ)へ、{@link #updateAim} と同じ {@link #smoothstep} イージングでなめらかに戻す。
     */
    private void updateReturn() {
        float progress = Mth.clamp(1.0F - (float) returnLeft / RETURN_TICKS, 0.0F, 1.0F);
        float remaining = 1.0F - smoothstep(progress);
        nexus.setBoomAimYaw(returnFromYaw * remaining);
        nexus.setBoomAimPitch(returnFromPitch * remaining);
    }

    private void fireBoom() {
        if (!(nexus.level() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        Vec3 muzzle = nexus.houkouWorldPosition();
        Vec3 aim = target.getEyePosition().subtract(muzzle);
        if (aim.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 dir = aim.normalize();
        int steps = Mth.floor(aim.length()) + 7;
        for (int i = 1; i < steps; i++) {
            Vec3 at = muzzle.add(dir.scale(i));
            level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.WARDEN_SONIC_BOOM,
                net.minecraft.sounds.SoundSource.HOSTILE, 3.0F, 1.0F);

        double range = cfg.raidNexusSonicRange.get();
        float damage = (float) (double) cfg.raidNexusSonicDamage.get();
        double coneCos = Math.cos(Math.toRadians(CONE_DEGREES));
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class,
                nexus.getBoundingBox().inflate(range), nexus::isHostileTarget);
        for (LivingEntity victim : victims) {
            Vec3 toV = victim.getEyePosition().subtract(muzzle);
            if (toV.length() > range || toV.normalize().dot(dir) < coneCos) {
                continue;
            }
            if (isShieldBlocked(victim, dir)) {
                // 盾で正面から防がれた: ウォーデン本来の(盾を無視する)挙動とは違い、
                // このボスの衝撃波はダメージ・ノックバックとも完全に無効化する。
                level.playSound(null, victim.blockPosition(), SoundEvents.SHIELD_BLOCK,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F);
                continue;
            }
            if (victim.hurt(level.damageSources().sonicBoom(nexus), damage)) {
                double kbRes = victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
                double h = 2.5D * (1.0D - kbRes);
                double v = 0.5D * (1.0D - kbRes);
                victim.push(dir.x * h, dir.y * v + 0.35D, dir.z * h);
                victim.hurtMarked = true;
            }
        }
    }

    /**
     * バニラの近接盾ブロックと同じ判定(盾を構えていて、攻撃が飛んでくる水平方向を正面から
     * 見ている)。{@code sonicBoom} ダメージタイプ自体はバニラの盾無効化タグに含まれ本来は
     * 盾で防げないが、このボス戦では「盾で防ぐとダメージを無効化できる」仕様にするため、
     * ここで独自にブロック判定を行い、成立していたら {@link #fireBoom} 側でダメージ処理自体を
     * スキップする。
     */
    private boolean isShieldBlocked(LivingEntity victim, Vec3 travelDir) {
        if (!victim.isBlocking()) {
            return false;
        }
        Vec3 look = victim.getViewVector(1.0F);
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z).normalize();
        Vec3 flatDir = new Vec3(travelDir.x, 0.0D, travelDir.z).normalize();
        return flatDir.dot(flatLook) < 0.0D;
    }

    private void endToIdle(int cooldown) {
        nexus.setBoomState(OvergrownNexusEntity.BOOM_IDLE);
        nexus.setBoomProgress(0.0F);
        // BOOM_RETURN を経ずにここへ来る経路(溜め中の中断)向けの保険。BOOM_RETURN 経由なら
        // updateReturn がここに来る時点で既に 0 まで戻し終えている。
        nexus.setBoomAimYaw(0.0F);
        nexus.setBoomAimPitch(0.0F);
        nextTime = nexus.tickCount + Math.max(1, cooldown);
    }

    /** {@code raidNexusSonicMinRange}〜{@code raidNexusSonicRange} の間にいる最も近い対象。 */
    private LivingEntity findTarget() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double maxRange = cfg.raidNexusSonicRange.get();
        double minRange = cfg.raidNexusSonicMinRange.get();
        double minRangeSq = minRange * minRange;
        double maxRangeSq = maxRange * maxRange;

        LivingEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (LivingEntity candidate : nexus.level().getEntitiesOfClass(LivingEntity.class,
                nexus.getBoundingBox().inflate(maxRange), nexus::isHostileTarget)) {
            double distSq = nexus.distanceToSqr(candidate);
            if (distSq < minRangeSq || distSq > maxRangeSq) {
                continue;
            }
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
