package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import com.yayuy.overgrownvegetation.entity.g1.G1TargetGoal;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * クモの 1 段目の (b1)「壁登りと待ち伏せ」(2026-11-13、能力の第 4 回。仕様 Vault「G1 クモ(1 段目)」3 節【決定 11-09・数は仮】)。
 * 近くに獲物がいないとき、近くの壁・幹を登って高い所に張り付いて待ち、下に獲物が来ると<b>落ちて</b>噛む(昼も夜も【決定 11-09】)。
 * 壁を登って相手を追うこと自体は {@link DevelopedParasitized} の登り({@code onClimbable})と navigation がする。ここは「高い所で待つ」の Goal。
 *
 * <ol>
 *   <li><b>探す</b>: 冷却が明け、{@value #CALM_RADIUS} ブロック以内に獲物がいないとき、周り {@value #SEARCH_RADIUS} ブロックの
 *   「壁(当たりが立方体のブロックの柱)と、その手前の登れる空き」を探す。登る高さは {@value #MIN_CLIMB}〜{@value #MAX_CLIMB} ブロック(仕様「落ちる高さ 3〜8」)。</li>
 *   <li><b>歩く → 登る</b>: 壁の手前まで歩き、壁へ体を押し付けて登る(速さは {@link DevelopedParasitized#CLIMB_SPEED} = 歩きの半分【仮】)。</li>
 *   <li><b>張り付く</b>(アニメ hang_idle): 重力を切ってその場に留まる(保存しない。Goal が止まれば落ちる)。{@value #DUST_INTERVAL} tick ごとに
 *   <b>胞子の粉と葉くずが降り、カサカサと鳴る</b>(前触れ = 上を見れば分かる)。最長 {@value #MAX_HANG} tick。殴られたら落ちる。</li>
 *   <li><b>落ちる</b>: 下(水平 {@value #POUNCE_RANGE} ブロック以内・{@value #MIN_DROP} ブロック以上下)に見えている獲物が来ると、
 *   脚を縮めて {@value #WINDUP} tick 揺れ(drop_windup)、相手の今いる所へ落ちる(drop)。水平 {@value #DROP_RADIUS} より遠い相手へは、
 *   壁を蹴って跳ぶ(仕様「高い所から相手へ跳ぶこともある」。同じ前触れ)。行き先は落ち始めに決めるので、歩いて離れればよけられる。</li>
 *   <li><b>着地</b>: そばの獲物へ 3 段の 1 撃と同じ当て方(攻撃 4 + 暴露量 +300)+ 鈍足 {@value #LAND_SLOW_TICKS} tick。落下のダメージは受けない。
 *   着地の隙は leap_recover {@value #LAND_RECOVER} tick。</li>
 * </ol>
 * 数値はすべて【仮】。優先度は種の技と同じ 1(3 段の 1 撃より上)。
 */
public class G1PerchGoal extends Goal {

    /** 探す・待つ条件。 */
    static final double CALM_RADIUS = 8.0D;
    static final int SEARCH_RADIUS = 5;
    static final int SEARCH_INTERVAL = 40;
    public static final int MIN_CLIMB = 3;
    public static final int MAX_CLIMB = 8;
    /** 歩く・登るを諦めるまで(tick)。近づかれたら(この距離)待ち伏せをやめて戦う。 */
    static final int SEEK_TIMEOUT = 160;
    static final int CLIMB_TIMEOUT = 240;
    static final double ABORT_RADIUS = 4.0D;
    /** 張り付いている最長・粉の間隔・次に待ち伏せるまで(tick)。 */
    public static final int MAX_HANG = 600;
    static final int DUST_INTERVAL = 30;
    static final int COOLDOWN_MIN = 400, COOLDOWN_MAX = 800;
    /** 落ちる条件: 水平の距離・真下とみなす距離・高さの差。 */
    public static final double POUNCE_RANGE = 6.0D;
    public static final double DROP_RADIUS = 2.0D;
    static final double MIN_DROP = 2.0D;
    public static final int WINDUP = 10;
    static final int FALL_TIMEOUT = 80;
    /** 壁を蹴る水平の速さの上限(ブロック / tick)。 */
    static final double POUNCE_SPEED_MAX = 0.9D;
    /** 着地: 当たる距離(体の箱を広げる)・鈍足の長さ・隙。 */
    static final double LAND_REACH = 0.7D;
    public static final int LAND_SLOW_TICKS = 20;
    public static final int LAND_RECOVER = 16;

    /** 待ち伏せる場所: 立つマス・壁の向き・登る高さ。 */
    public record Spot(BlockPos stand, Direction toWall, int climb) {
    }

    private enum Phase { SEEK, CLIMB, HANG, WINDUP, FALL }

    private final DevelopedParasitized mob;
    private long readyAt;
    private long nextSearchAt;
    @Nullable
    private Spot spot;
    private Phase phase = Phase.SEEK;
    private int phaseTicks;
    private int hurtAtStart;
    private boolean done;
    @Nullable
    private LivingEntity prey;
    private Vec3 hangAt = Vec3.ZERO;
    private float wallYaw;

    public G1PerchGoal(DevelopedParasitized mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive() || mob.isEvolving() || mob.isStunned() || mob.isPassenger() || mob.action() != AbstractParasitizedMonster.ACTION_NONE) {
            return false;
        }
        Spot forced = mob.takeForcedPerch();
        if (forced != null) {
            spot = forced;
            return true;
        }
        long now = mob.level().getGameTime();
        if (now < readyAt || now < nextSearchAt || !mob.onGround()) return false;
        nextSearchAt = now + SEARCH_INTERVAL;
        if (!mob.calm(CALM_RADIUS)) return false;
        spot = findSpot();
        return spot != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && mob.isAlive();
    }

    /** 冷却を今から数え直す(確かめ用のコマンド)。 */
    public void resetCooldown() {
        readyAt = 0L;
        nextSearchAt = 0L;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        prey = null;
        hurtAtStart = mob.hurtCount();
        Spot s = spot;
        wallYaw = s.toWall().toYRot();
        hangAt = center(s, s.climb());
        enter(Phase.SEEK);
        mob.getNavigation().moveTo(hangAt.x, s.stand().getY(), hangAt.z, 1.0D);
    }

    @Override
    public void stop() {
        mob.setPerched(false);
        mob.getNavigation().stop();
        int a = mob.action();
        if (a == G1Move.HANG_IDLE.id() || a == G1Move.DROP_WINDUP.id() || a == G1Move.DROP.id()) {
            mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
        }
        readyAt = mob.level().getGameTime() + COOLDOWN_MIN + mob.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        spot = null;
    }

    private void enter(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    @Override
    public void tick() {
        Spot s = spot;
        if (s == null || !(mob.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        phaseTicks++;
        switch (phase) {
            case SEEK -> {
                double dx = hangAt.x - mob.getX(), dz = hangAt.z - mob.getZ();
                if (dx * dx + dz * dz < 0.8D * 0.8D) {
                    mob.getNavigation().stop();
                    enter(Phase.CLIMB);
                } else if (phaseTicks > SEEK_TIMEOUT || !mob.calm(ABORT_RADIUS)) {
                    done = true;
                } else if (mob.getNavigation().isDone()) {
                    // 経路が切れた・引けなかった: navigation の行き先を消して(残すと後から上書きされる)、まっすぐ寄る
                    mob.getNavigation().stop();
                    mob.getMoveControl().setWantedPosition(hangAt.x, mob.getY(), hangAt.z, 1.0D);
                }
            }
            case CLIMB -> {
                // 壁の真ん中へ体を押し付ける(当たっている間は登る = DevelopedParasitized の onClimbable)
                BlockPos wall = s.stand().relative(s.toWall());
                mob.getMoveControl().setWantedPosition(wall.getX() + 0.5D, mob.getY(), wall.getZ() + 0.5D, 1.0D);
                if (mob.getY() >= s.stand().getY() + s.climb() - 0.05D) {
                    mob.setPerched(true);
                    mob.setAction(G1Move.HANG_IDLE.id());
                    hangAt = new Vec3(mob.getX(), mob.getY(), mob.getZ());
                    enter(Phase.HANG);
                } else if (phaseTicks > CLIMB_TIMEOUT || mob.hurtCount() != hurtAtStart || !mob.calm(ABORT_RADIUS)) {
                    done = true;
                }
            }
            case HANG -> {
                hold();
                if (phaseTicks % DUST_INTERVAL == 1) {
                    dust(level, 6);
                    // カサカサ(上から聞こえる前触れ)
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 0.35F, 0.6F);
                }
                if (mob.hurtCount() != hurtAtStart) {
                    release(null); // 殴られた・撃たれた: 落ちる(「壁にいる間を撃ち落とす」)
                } else if (phaseTicks % 5 == 0 && (prey = preyBelow(level)) != null) {
                    mob.setTarget(prey);
                    mob.setAction(G1Move.DROP_WINDUP.id());
                    enter(Phase.WINDUP);
                } else if (phaseTicks > MAX_HANG) {
                    release(null);
                }
            }
            case WINDUP -> {
                hold();
                if (phaseTicks == 1 || phaseTicks == 6) {
                    // 脚を縮めて揺れる: 粉と葉くずが多めに降る
                    dust(level, 12);
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 0.8F, 1.4F);
                }
                if (phaseTicks >= WINDUP) {
                    release(prey);
                }
            }
            case FALL -> {
                mob.setYRot(wallYaw);
                if ((phaseTicks > 2 && (mob.onGround() || mob.isInWater())) || phaseTicks > FALL_TIMEOUT) {
                    land(level);
                }
            }
        }
    }

    /** 張り付いている間、その場に留めて壁を向かせる。 */
    private void hold() {
        mob.getNavigation().stop();
        mob.setDeltaMovement(Vec3.ZERO);
        if (mob.position().distanceToSqr(hangAt) > 0.01D) {
            mob.setPos(hangAt.x, hangAt.y, hangAt.z);
        }
        mob.setYRot(wallYaw);
        mob.setYBodyRot(wallYaw);
        mob.yHeadRot = wallYaw;
    }

    /** 壁から離れて落ちる。{@code at} がいれば、その今いる所へ着くように壁を蹴る。 */
    private void release(@Nullable LivingEntity at) {
        mob.setPerched(false);
        mob.noFallDamageFor(FALL_TIMEOUT + 20);
        mob.setAction(G1Move.DROP.id());
        Vec3 v = Vec3.ZERO;
        if (at != null && at.isAlive()) {
            double dx = at.getX() - mob.getX(), dz = at.getZ() - mob.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double fall = Math.max(1.0D, mob.getY() - at.getY());
            v = pounceVelocity(dx, dz, dist, fall);
        } else {
            // 壁から少し離れる(張り付いたまま擦れ落ちないように)
            Direction away = spot != null ? spot.toWall().getOpposite() : Direction.NORTH;
            v = new Vec3(away.getStepX() * 0.12D, 0.0D, away.getStepZ() * 0.12D);
        }
        mob.setDeltaMovement(v);
        mob.hasImpulse = true;
        enter(Phase.FALL);
    }

    /**
     * 高さ {@code fall} を落ちる間に水平へ {@code dist} 進む初速(純関数。GameTest が使う)。落ちる時間は重力 0.08・抵抗 0.98 を 1 tick ずつ進めて数え、
     * 空中の水平の抵抗 0.91 / tick ぶんを割り戻す。上限 {@value #POUNCE_SPEED_MAX}。
     */
    public static Vec3 pounceVelocity(double dx, double dz, double dist, double fall) {
        if (dist < 1.0E-3D) return Vec3.ZERO;
        int ticks = 0;
        double y = 0.0D, vy = 0.0D;
        while (y > -fall && ticks < 60) {
            vy = (vy - 0.08D) * 0.98D;
            y += vy;
            ticks++;
        }
        double reach = (1.0D - Math.pow(0.91D, ticks)) / 0.09D; // 初速 1 で進む距離
        double speed = Math.min(POUNCE_SPEED_MAX, dist / reach);
        return new Vec3(dx / dist * speed, 0.0D, dz / dist * speed);
    }

    private void land(ServerLevel level) {
        AABB box = mob.getBoundingBox().inflate(LAND_REACH, 0.25D, LAND_REACH);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!G1TargetGoal.isPrey(mob, e)) continue;
            if (mob.doHurtTarget(e)) {
                // 3 段の 1 撃と同じ当て方(当てたら暴露量)+ 鈍足 1 秒
                SporeExposureTracker.addDose(e, G1StrikeGoal.MOB_DOSE);
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, LAND_SLOW_TICKS, 0), mob);
            }
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOSTILE_SMALL_FALL, SoundSource.HOSTILE, 1.0F, 0.6F);
        BlockState below = level.getBlockState(BlockPos.containing(mob.getX(), mob.getY() - 0.2D, mob.getZ()));
        if (!below.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, below), mob.getX(), mob.getY() + 0.1D, mob.getZ(), 12, 0.5D, 0.05D, 0.5D, 0.05D);
        }
        mob.holdAction(G1Move.LEAP_RECOVER, LAND_RECOVER);
        done = true;
    }

    /** 上から降る胞子の粉と葉くず(巻きシダのかけら)。 */
    private void dust(ServerLevel level, int count) {
        level.sendParticles(ModParticles.SPORE_JET.get(), mob.getX(), mob.getY() - 0.1D, mob.getZ(), count, 0.4D, 0.1D, 0.4D, 0.0D);
        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, ModBlocks.OVERGROWN_CURLED_FERN.get().defaultBlockState()),
                mob.getX(), mob.getY() - 0.1D, mob.getZ(), Math.max(2, count / 3), 0.4D, 0.1D, 0.4D, 0.0D);
    }

    /** 下に来た獲物(見えていて、水平 {@value #POUNCE_RANGE} 以内・{@value #MIN_DROP} 以上下)のうち、いちばん近いもの。 */
    @Nullable
    private LivingEntity preyBelow(ServerLevel level) {
        AABB box = new AABB(mob.getX() - POUNCE_RANGE, mob.getY() - MAX_CLIMB - 2.0D, mob.getZ() - POUNCE_RANGE,
                mob.getX() + POUNCE_RANGE, mob.getY() - MIN_DROP + 2.0D, mob.getZ() + POUNCE_RANGE);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!G1TargetGoal.isPrey(mob, e) || mob.getY() - e.getY() < MIN_DROP) continue;
            double dx = e.getX() - mob.getX(), dz = e.getZ() - mob.getZ();
            double d = dx * dx + dz * dz;
            if (d > POUNCE_RANGE * POUNCE_RANGE || d >= bestD || !mob.getSensing().hasLineOfSight(e)) continue;
            best = e;
            bestD = d;
        }
        return best;
    }

    // ------------------------------------------------------------------ 場所を探す
    /** 張り付く体の中心(足元)。壁の面から体の幅の半分 + 少し離す。 */
    private Vec3 center(Spot s, int up) {
        BlockPos wall = s.stand().relative(s.toWall());
        double off = 0.5D + mob.getBbWidth() * 0.5D + 0.02D;
        return new Vec3(wall.getX() + 0.5D - s.toWall().getStepX() * off, s.stand().getY() + up, wall.getZ() + 0.5D - s.toWall().getStepZ() * off);
    }

    /** 周りの壁から、登れる所を 1 つ(近い順に見て最初に見つかったもの。高さは登れるだけ、上限まで)。 */
    @Nullable
    private Spot findSpot() {
        BlockPos feet = mob.blockPosition();
        Spot best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                double d = dx * dx + dz * dz;
                if (d >= bestD || d > SEARCH_RADIUS * SEARCH_RADIUS) continue;
                p.set(feet.getX() + dx, feet.getY(), feet.getZ() + dz);
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    int climb = climbable(mob, p, dir);
                    if (climb >= MIN_CLIMB) {
                        best = new Spot(p.immutable(), dir, Math.min(climb, MIN_CLIMB + mob.getRandom().nextInt(MAX_CLIMB - MIN_CLIMB + 1)));
                        bestD = d;
                        break;
                    }
                }
            }
        }
        return best;
    }

    /**
     * {@code stand} に立って {@code dir} の壁を何ブロック登れるか(0 = 登れない。上限 {@value #MAX_CLIMB})。
     * 壁 = 当たりが立方体のブロックの柱(登った高さの体の横まで)。手前は、体の箱が登る間どこにも当たらないこと。足元は立てること。
     */
    public static int climbable(DevelopedParasitized mob, BlockPos stand, Direction dir) {
        var level = mob.level();
        if (!level.isLoaded(stand)) return 0;
        BlockPos below = stand.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return 0;
        BlockPos wall = stand.relative(dir);
        double off = 0.5D + mob.getBbWidth() * 0.5D + 0.02D;
        double x = wall.getX() + 0.5D - dir.getStepX() * off, z = wall.getZ() + 0.5D - dir.getStepZ() * off;
        float w = mob.getBbWidth() * 0.5F, h = mob.getBbHeight();
        int climb = 0;
        for (int up = 0; up <= MAX_CLIMB + 1; up++) {
            BlockPos wp = wall.above(up);
            if (!level.getBlockState(wp).isCollisionShapeFullBlock(level, wp)) break;
            double y = stand.getY() + up;
            if (!level.noCollision(mob, new AABB(x - w, y + 0.01D, z - w, x + w, y + h, z + w))) break;
            climb = up;
        }
        // 張り付いた体の横(頭の高さ)まで壁がある高さ = climb − 1
        return Mth.clamp(climb - 1, 0, MAX_CLIMB);
    }
}
