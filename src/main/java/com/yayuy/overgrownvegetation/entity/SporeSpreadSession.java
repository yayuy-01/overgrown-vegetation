package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner;
import com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 「その場に腰を落ち着けて周りを汚す」1 回ぶん(2026-09-25、群れ v2 で {@link InvaderSporeCrouchGoal} から切り出した)。
 *
 * <p>個体のうずくまり({@link InvaderSporeCrouchGoal})・スカトゥリウムの座り込み({@link ShielderSporeGoal})と、
 * 群れが止まって全員で汚す({@link SwarmSpreadGoal}、ユーザー指定 2026-09-25「暇なら止まって全員で」)が
 * <b>同じ場・同じ間隔・同じ見た目</b>で汚すための共通の中身。切り出す前と中身は同じ:
 * <ul>
 *     <li>汚し方は {@link InvaderSporeField}(近い順に 1 マスずつ・胞子除去装置の中では止まる)。</li>
 *     <li>動かない(うずくまりは経路・入力・水平速度まで潰す。座り込みは経路だけ —— 速度は
 *     {@link OvergrownShielderEntity#travel} が {@code STATE_SIT} で潰す)。</li>
 *     <li>粒子と「胞子感染1」(うずくまり 20 tick ごと / 座り込み 40 tick ごと)。</li>
 *     <li><b>汚し尽くして終わったときだけ</b>、うずくまりは確率で汚染シダ・過繁茂 Mob を 1 つ増やす
 *     (相手を見つけて中断したときは何も残さない)。座り込みは増やさない(切り出す前から)。</li>
 * </ul>
 * 行動状態(うずくまり {@code STATE_SPORE} / 座り {@code STATE_SIT})を立てる・戻すのは呼ぶ側。
 * 群れの一員の間は 1 体ごとの間隔({@code invaderSporeIntervalTicks})を見ない(いつ汚すかは司令塔が決める)。
 */
public final class SporeSpreadSession {

    /** 見た目と汚し方の型。 */
    public enum Style {
        /** グループ4 の共通のうずくまり(くつろいだ姿勢。ユーザー指示 2026-09-14)。 */
        CROUCH,
        /** 過繁茂したスカトゥリウムの座り込み(ユーザー要望 2026-09-15)。 */
        SIT
    }

    /** 「過繁茂 Mob を増やす」判定で、周囲のグループ Mob を数える半径(ブロック)。 */
    private static final double NEARBY_RADIUS = 24.0D;
    /** 粒子を出す間隔(tick)。 */
    private static final int PARTICLE_INTERVAL = 4;
    /** 胞子感染を配る間隔(tick)。うずくまり / 座り込み。 */
    private static final int CROUCH_INFECT_INTERVAL = 20;
    private static final int SIT_INFECT_INTERVAL = 40;
    /** 座り込みの粒の数。 */
    private static final int SIT_PARTICLES = 6;

    private final Mob mob;
    private final Style style;
    private final InvaderSporeField field;
    private int ticks;

    /** 汚染の場を足元に作る({@code radius} は種の汚す半径)。 */
    public SporeSpreadSession(Mob mob, int radius, Style style) {
        this.mob = mob;
        this.style = style;
        this.field = new InvaderSporeField(mob.blockPosition(), radius, InvaderSporeField.defaultInterval(), mob.getRandom());
    }

    /** 始めの音。 */
    public void begin(ServerLevel level) {
        if (this.style == Style.CROUCH) {
            level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 0.7F, 1.3F);
        } else {
            this.mob.playSound(SoundEvents.SCULK_CATALYST_BLOOM, 1.0F, 0.6F);
        }
    }

    /** 1 tick ぶん。動かず、粒子を撒き、周りに感染を配り、場を 1 歩進める。 */
    public void tick(ServerLevel level) {
        this.ticks++;
        this.mob.getNavigation().stop();
        if (this.style == Style.CROUCH) {
            // 一歩も動かない。経路を止めるだけでは MoveControl が押し続けるので、速度と入力も潰す。
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
            if (this.ticks % PARTICLE_INTERVAL == 0) {
                crouchParticles(level);
            }
            if (this.ticks % CROUCH_INFECT_INTERVAL == 0) {
                infectNearby(level);
            }
        } else {
            // 切り出す前の座り込みは 1 tick 目から出していた(数えが 0 から減る作り)。同じ拍にする。
            if ((this.ticks - 1) % PARTICLE_INTERVAL == 0) {
                sitParticles(level);
            }
            if ((this.ticks - 1) % SIT_INFECT_INTERVAL == 0) {
                infectNearby(level);
            }
        }
        this.field.spread(level, this.mob.blockPosition());
    }

    /** 届く範囲を汚し尽くしたか。 */
    public boolean isExhausted() {
        return this.field.isFinished();
    }

    /**
     * 終える。汚し尽くして終わったときだけ、うずくまりは汚染シダ・過繁茂 Mob を確率で増やす
     * (「拡大種が汚染シダを増やす」「過繁茂 Mob を増やす」経路)。
     */
    public void finish(ServerLevel level) {
        if (this.style == Style.CROUCH && isExhausted()) {
            trySpawnFern(level);
            trySpawnOvergrownMob(level);
        }
    }

    /** 立っている地面が既に汚染ブロックか(既に汚れている場所では腰を落ち着けない)。 */
    public static boolean onCorruptedGround(Mob mob) {
        return mob.level().getBlockState(mob.blockPosition().below()).is(ModTags.CORRUPTED);
    }

    // --- 群れの汚染(SPREAD)の動作 ---

    /** アルボラなど、グループ4 の共通のうずくまりで群れの汚染に加わる動作。 */
    public static SwarmSpreadGoal.Action crouchAction(AbstractOvergrownInvaderEntity mob) {
        return new SwarmSpreadGoal.Action() {
            private SporeSpreadSession session;

            @Override
            public boolean shouldSkip() {
                // 個体のうずくまりと同じ足切り(既に汚れている場所では腰を落ち着けない)。汚し尽くした扱いの芽吹きも起きない。
                return mob.onGround() && onCorruptedGround(mob);
            }

            @Override
            public boolean canStart() {
                return mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_IDLE
                        && mob.onGround() && !mob.isInWater();
            }

            @Override
            public void start(ServerLevel level) {
                this.session = new SporeSpreadSession(mob, mob.sporeCorruptionRadius(), Style.CROUCH);
                mob.getNavigation().stop();
                mob.setActionState(AbstractOvergrownInvaderEntity.STATE_SPORE);
                this.session.begin(level);
            }

            @Override
            public void tick(ServerLevel level) {
                this.session.tick(level);
            }

            @Override
            public boolean isFinished() {
                return this.session == null || this.session.isExhausted() || mob.isInWater();
            }

            @Override
            public void stop(ServerLevel level, boolean completed) {
                if (mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_SPORE) {
                    mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
                }
                if (completed && this.session != null) {
                    this.session.finish(level);
                }
                this.session = null;
            }
        };
    }

    /** 過繁茂したスカトゥリウムの座り込みで群れの汚染に加わる動作。 */
    public static SwarmSpreadGoal.Action sitAction(OvergrownShielderEntity mob) {
        return new SwarmSpreadGoal.Action() {
            private SporeSpreadSession session;

            @Override
            public boolean shouldSkip() {
                // 個体の座り込みと同じ足切り(既に汚れている場所では座らない)。
                return mob.onGround() && onCorruptedGround(mob);
            }

            @Override
            public boolean canStart() {
                return !mob.isBusy() && mob.onGround() && !mob.isInWater();
            }

            @Override
            public void start(ServerLevel level) {
                mob.setActionState(OvergrownShielderEntity.STATE_SIT);
                mob.getNavigation().stop();
                this.session = new SporeSpreadSession(mob, mob.sporeCorruptionRadius(), Style.SIT);
                this.session.begin(level);
            }

            @Override
            public void tick(ServerLevel level) {
                this.session.tick(level);
            }

            @Override
            public boolean isFinished() {
                return this.session == null || this.session.isExhausted()
                        || mob.getActionState() != OvergrownShielderEntity.STATE_SIT;
            }

            @Override
            public void stop(ServerLevel level, boolean completed) {
                if (mob.getActionState() == OvergrownShielderEntity.STATE_SIT) {
                    mob.setActionState(OvergrownShielderEntity.STATE_NONE);
                }
                if (completed && this.session != null) {
                    this.session.finish(level);
                }
                this.session = null;
            }
        };
    }

    // --- 中身(切り出す前の InvaderSporeCrouchGoal / ShielderSporeGoal と同じ) ---

    private void crouchParticles(ServerLevel level) {
        double x = this.mob.getX();
        double y = this.mob.getY() + 0.3D;
        double z = this.mob.getZ();
        level.sendParticles(ModParticles.FINE_SPORE.get(), x, y, z, 24, 0.9D, 0.25D, 0.9D, 0.01D);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 4, 1.2D, 0.4D, 1.2D, 0.0D);
    }

    private void sitParticles(ServerLevel level) {
        double radius = Math.max(1.0D, this.mob.getBbWidth());
        level.sendParticles(ModParticles.FINE_SPORE.get(),
                this.mob.getX(), this.mob.getY() + this.mob.getBbHeight() * 0.8D, this.mob.getZ(),
                SIT_PARTICLES, radius, 0.4D, radius, 0.01D);
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
                SporeInfectionEffects.plantAttack(entity, 0);
            }
        }
    }

    /** 終わりぎわの汚染シダ。地表に置く(埋まらないように {@link Heightmap} で高さを取り直す)。 */
    private void trySpawnFern(ServerLevel level) {
        double chance = OvergrownVegetationConfig.COMMON.invaderFernSpawnChance.get();
        if (chance <= 0.0D || this.mob.getRandom().nextDouble() >= chance) {
            return;
        }
        BlockPos at = this.mob.blockPosition();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos spot = new BlockPos(at.getX(), surface, at.getZ());
        if (Math.abs(surface - at.getY()) > 4) {
            // 洞窟の中など、地表が遠すぎるところでは芽吹かせない。
            return;
        }
        BlightFernPlacement.spawn(level, spot, MobSpawnType.MOB_SUMMONED);
    }

    /**
     * 終わりぎわに過繁茂 Mob を 1 体だけ増やす({@code invaderMobSpawnChance})。今のフェーズで過繁茂バイオームに湧ける種から、
     * グループ4(自己増殖の歯止め)と水生の種を除いて選ぶ。周囲 {@value #NEARBY_RADIUS} ブロックに既にグループ所属の Mob が
     * {@code invaderMobSpawnNearbyCap} 体以上いるときは増やさない。
     */
    private void trySpawnOvergrownMob(ServerLevel level) {
        double chance = OvergrownVegetationConfig.COMMON.invaderMobSpawnChance.get();
        if (chance <= 0.0D || this.mob.getRandom().nextDouble() >= chance) {
            return;
        }
        int cap = OvergrownVegetationConfig.COMMON.invaderMobSpawnNearbyCap.get();
        AABB near = this.mob.getBoundingBox().inflate(NEARBY_RADIUS);
        int existing = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, near)) {
            if (MobGroup.of(entity.getType()) != null && ++existing >= cap) {
                return;
            }
        }

        List<EntityType<?>> pool = new ArrayList<>();
        for (EntityType<?> type : OvergrownBiomeSpawner.spawnPoolFor(PhaseData.get(level).getPhase())) {
            if (MobGroup.of(type) == MobGroup.GROUP_4 || OvergrownSpawnProfile.isAquatic(type)) {
                continue;
            }
            pool.add(type);
        }
        if (pool.isEmpty()) {
            return;
        }

        EntityType<?> chosen = pool.get(this.mob.getRandom().nextInt(pool.size()));
        BlockPos at = this.mob.blockPosition();
        int x = at.getX() + this.mob.getRandom().nextInt(5) - 2;
        int z = at.getZ() + this.mob.getRandom().nextInt(5) - 2;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(surface - at.getY()) > 4) {
            return;
        }
        BlockPos spot = new BlockPos(x, surface, z);
        // 体がまるごと入る場所にだけ湧かせる(幅 1 を超える種が地面や壁へ埋まらないように)。
        if (!SpawnFit.fits(level, chosen, spot)) {
            return;
        }
        Entity spawned = chosen.create(level, null, spot, MobSpawnType.MOB_SUMMONED, false, false);
        if (spawned == null) {
            return;
        }
        // 種ごとの最後の関門(2026-10-01。地下の種はここで岩の中へ潜る・空洞が無ければ断る、など)。
        if (!OvergrownSpawnCheck.accept(spawned, level)) {
            spawned.discard();
            return;
        }
        level.addFreshEntity(spawned);
    }
}
