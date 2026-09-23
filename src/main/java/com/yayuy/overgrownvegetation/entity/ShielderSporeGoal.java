package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * 「過繁茂したスカトゥリウム」が<b>その場に座り、周囲を汚染する</b>(ユーザー要望 2026-09-15)。
 *
 * <p>グループ4 に移った以上、ほかの拡大種と同じ「汚染を自分の足で運び、腰を落ち着けた場所を汚す」
 * 役割を持つ。汚し方は共通の {@link InvaderSporeField}(近い順に 1 マスずつ・胞子除去装置の中では止まる)で、
 * アルボラのうずくまり({@link InvaderSporeCrouchGoal})とまったく同じ仕掛け。違いは 2 つだけ:
 * <ul>
 *     <li>スカトゥリウムは {@link AbstractOvergrownInvaderEntity} を継承していない
 *     (行動もステータスも維持したまま群れに入れるため独立している)ので、Goal も別に立ててある。</li>
 *     <li>座っている間は<b>周りの Mob へ胞子感染も配る</b>
 *     ({@code invaderSporeInfectRadius}、アルボラのうずくまりと同じ範囲)。</li>
 * </ul>
 *
 * <h2>いつ座るか</h2>
 * <b>襲う相手が居ないとき</b>だけ。加えて
 * <ul>
 *     <li>接地していて水に入っていない</li>
 *     <li>前回から {@code invaderSporeIntervalTicks}(±25%)たっている</li>
 * </ul>
 * を満たしたとき。立つのは<b>相手を見つけたか、届く範囲を汚し尽くしたか</b>のどちらか
 * ——「汚す物が多い場所ほど長く座っている」ことになる。
 */
public class ShielderSporeGoal extends Goal {

    /** 粒子を出す間隔(tick)。 */
    private static final int PARTICLE_INTERVAL = 4;
    /** 1 回に出す粒の数。 */
    private static final int PARTICLES = 6;
    /** 胞子感染を配り直す間隔(tick)。 */
    private static final int INFECT_INTERVAL = 40;

    private final OvergrownShielderEntity mob;

    private InvaderSporeField field;
    private int nextAllowedTick;
    private int particleTimer;
    private int infectTimer;

    public ShielderSporeGoal(OvergrownShielderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isBusy() || this.mob.tickCount < this.nextAllowedTick) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            return false;
        }
        // 既に汚染された地面の上では座らない（アルボラのうずくまりと同じ足切り）。
        // これが無いと、汚染し尽くした縄張りの真ん中で座り直しを繰り返して、
        // 群れの「外へ出ていく」歩き（InvaderAdvanceGoal）がいつまでも始まらない。
        return this.mob.onGround() && !this.mob.isInWater() && !onCorruptedGround();
    }

    /** 足元が既に汚染ブロックか。 */
    private boolean onCorruptedGround() {
        return this.mob.level().getBlockState(this.mob.blockPosition().below()).is(ModTags.CORRUPTED);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.getActionState() != OvergrownShielderEntity.STATE_SIT) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            // 横取りされた。立ち上がって相手をする。
            return false;
        }
        return this.field != null && !this.field.isFinished();
    }

    @Override
    public void start() {
        this.mob.setActionState(OvergrownShielderEntity.STATE_SIT);
        this.mob.getNavigation().stop();
        this.field = new InvaderSporeField(this.mob.blockPosition(), this.mob.sporeCorruptionRadius(),
                InvaderSporeField.defaultInterval(), this.mob.getRandom());
        this.particleTimer = 0;
        this.infectTimer = 0;
        this.mob.playSound(SoundEvents.SCULK_CATALYST_BLOOM, 1.0F, 0.6F);
    }

    @Override
    public void stop() {
        this.field = null;
        if (this.mob.getActionState() == OvergrownShielderEntity.STATE_SIT) {
            this.mob.setActionState(OvergrownShielderEntity.STATE_NONE);
        }
        // 実際は ±25% ばらつかせて、群れ全員が同時に立ち座りしないようにする。
        int base = OvergrownVegetationConfig.COMMON.invaderSporeIntervalTicks.get();
        int jitter = Math.max(1, base / 4);
        this.nextAllowedTick = this.mob.tickCount + base - jitter
                + this.mob.getRandom().nextInt(jitter * 2 + 1);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // 座っている間は一歩も動かない。
        this.mob.getNavigation().stop();
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.field != null) {
            this.field.spread(level, this.mob.blockPosition());
        }
        if (--this.particleTimer <= 0) {
            this.particleTimer = PARTICLE_INTERVAL;
            spawnSpores(level);
        }
        if (--this.infectTimer <= 0) {
            this.infectTimer = INFECT_INTERVAL;
            infectNearby(level);
        }
    }

    private void spawnSpores(ServerLevel level) {
        double radius = Math.max(1.0D, this.mob.getBbWidth());
        level.sendParticles(ModParticles.FINE_SPORE.get(),
                this.mob.getX(), this.mob.getY() + this.mob.getBbHeight() * 0.8D, this.mob.getZ(),
                PARTICLES, radius, 0.4D, radius, 0.01D);
    }

    /** 周りの Mob に「胞子感染1」を付ける。プレイヤーとグループ1〜10 は対象外。 */
    private void infectNearby(ServerLevel level) {
        double radius = OvergrownVegetationConfig.COMMON.invaderSporeInfectRadius.get();
        if (radius <= 0.0D) {
            return;
        }
        AABB box = this.mob.getBoundingBox().inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (AbstractOvergrownInvaderEntity.isSporeVictim(this.mob, entity)) {
                SporeInfectionEffects.infect(entity, 0);
            }
        }
    }
}
