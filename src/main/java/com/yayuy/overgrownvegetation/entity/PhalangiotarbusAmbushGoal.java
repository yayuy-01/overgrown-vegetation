package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * ファランギオターブスの「擬態(待ち伏せ)」ゴール。
 *
 * <p>擬態条件({@link PhalangiotarbusEntity#isMimicryEnvironment()})を満たし、直近で
 * 攻撃を受けていない({@link PhalangiotarbusEntity#isInCombatCooldown()} が false)ときだけ動く。
 * 獲物を狩り終えたら、擬態環境にいる限りまた原木へ登って隠れ直す:
 * <ol>
 *     <li><b>SEEK</b>: 最寄りの原木({@link PhalangiotarbusEntity#findPerchLog()})の隣へ移動する。</li>
 *     <li><b>CLIMB</b>: 原木の側面をよじ登る({@code climb}、体は樹皮と平行に90度傾く)。</li>
 *     <li><b>CLING</b>: 数ブロック登ったら原木の面のちょうど中央に位置を固定してじっと張り付く
 *         ({@code cling})。周囲 {@link #POUNCE_DETECT_RADIUS} 以内を獲物が通ると POUNCE へ。</li>
 *     <li><b>POUNCE</b>: 壁を蹴って獲物めがけて跳ぶ({@code pounce})。着地時に獲物へ
 *         触れていれば取り付き、外したら短いクールダウンを置いて SEEK へ戻る。</li>
 * </ol>
 * <b>擬態は必ず原木の側面で行う。</b>原木の側面に取り付けなかった場合は地上でうずくまったりせず、
 * 少し間を置いて({@link #SEEK_RETRY_COOLDOWN})また原木を探し直す。
 */
public class PhalangiotarbusAmbushGoal extends Goal {

    /** 擬態成立の判定と、張り付き先の原木を探す半径(ブロック)。 */
    public static final int PERCH_SEARCH_RADIUS = 6;
    private static final double POUNCE_DETECT_RADIUS = 5.0D;
    private static final double POUNCE_MAX_RANGE = 6.0D;
    private static final double POUNCE_SPEED = 0.62D;
    private static final double POUNCE_LIFT = 0.42D;
    private static final int POUNCE_MAX_TICKS = 30;
    private static final int POUNCE_COOLDOWN = 60;
    private static final double PERCH_REACHED_DIST = 2.6D;
    /** よじ登る高さ(ブロック)の下限・上限。 */
    private static final int CLIMB_MIN = 2;
    private static final int CLIMB_MAX = 5;
    /** 登りの上昇速度(ブロック/tick)。 */
    private static final double CLIMB_RISE = 0.14D;
    private static final int CLIMB_TIMEOUT = 80;
    /** 張り付く場所に要求する縦の余裕(原木・前方空間ともにこのブロック数ぶん)。 */
    private static final int CLING_CLEARANCE = 3;
    /** CLING 地点を上下に探すときの最大走査ブロック数。 */
    private static final int CLING_SCAN_RANGE = 6;
    /** SEEK でここまでに原木の側面へ取り付けなければ一旦ゴールを畳んで仕切り直す。 */
    private static final int SEEK_TIMEOUT = 120;
    /** 上記の仕切り直し後、再び擬態を試みるまでの待ち時間。 */
    private static final int SEEK_RETRY_COOLDOWN = 80;

    private static final int PHASE_SEEK = 0;
    private static final int PHASE_CLIMB = 1;
    private static final int PHASE_CLING = 2;
    private static final int PHASE_POUNCE = 3;

    private final PhalangiotarbusEntity mob;

    private int phase;
    private BlockPos perch;
    private int repathCooldown;
    private int phaseTicks;
    private double climbStartY;
    private double climbTargetY;
    private int wallLostTicks;
    private int nextPounceTime;
    private int nextTryTime;
    private LivingEntity pounceVictim;
    private Vec3 clingAnchor;

    public PhalangiotarbusAmbushGoal(PhalangiotarbusEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (mob.isInCombatCooldown() || mob.getLatchTarget() != null || mob.tickCount < nextTryTime) {
            return false;
        }
        if (!mob.isMimicryEnvironment()) {
            return false;
        }
        this.perch = mob.findPerchLog();
        return this.perch != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getLatchTarget() != null || !mob.isAlive()) {
            return false;
        }
        if (phase == PHASE_POUNCE) {
            return true;
        }
        // 攻撃を受けたら擬態を中断して普通に戦う(クールダウン後にまた隠れ直す)。
        if (mob.isInCombatCooldown()) {
            return false;
        }
        // restartSeek() で仕切り直しの待ちに入ったら一度ゴールを畳む。
        if (mob.tickCount < nextTryTime) {
            return false;
        }
        return mob.isMimicryEnvironment();
    }

    @Override
    public void start() {
        setPhase(PHASE_SEEK);
        repathCooldown = 0;
        wallLostTicks = 0;
        pounceVictim = null;
        clingAnchor = null;
        if (perch == null) {
            perch = mob.findPerchLog();
        }
    }

    @Override
    public void stop() {
        mob.setWallLocked(false);
        mob.setNoGravity(false);
        if (mob.getActionState() != PhalangiotarbusEntity.STATE_LATCH) {
            mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
        }
        mob.getNavigation().stop();
        phase = PHASE_SEEK;
        perch = null;
        pounceVictim = null;
        clingAnchor = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void setPhase(int p) {
        this.phase = p;
        this.phaseTicks = 0;
    }

    /** 原木の側面に取り付けなかった。少し間を置いて仕切り直す(地上擬態はしない)。 */
    private void restartSeek() {
        mob.setWallLocked(false);
        mob.setNoGravity(false);
        mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
        mob.getNavigation().stop();
        nextTryTime = mob.tickCount + SEEK_RETRY_COOLDOWN;
        perch = null;
        setPhase(PHASE_SEEK);
    }

    @Override
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case PHASE_SEEK -> tickSeek();
            case PHASE_CLIMB -> tickClimb();
            case PHASE_CLING -> tickCling();
            case PHASE_POUNCE -> tickPounce();
            default -> { }
        }
    }

    // --- SEEK ---

    private void tickSeek() {
        mob.setWallLocked(false);
        mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
        if (perch == null) {
            perch = mob.findPerchLog();
            if (perch == null) {
                restartSeek();
                return;
            }
        }

        // 既に原木の側面に接していれば登り始める。
        if (mob.isAgainstAnyPerchWall(true)) {
            beginClimb();
            return;
        }

        if (--repathCooldown <= 0) {
            repathCooldown = 10;
            mob.getNavigation().moveTo(perch.getX() + 0.5D, perch.getY(), perch.getZ() + 0.5D, 1.0D);
        }

        if (phaseTicks > SEEK_TIMEOUT) {
            // どうしても側面に取り付けない。地上でうずくまらず、間を置いて探し直す。
            restartSeek();
        }
    }

    // --- CLIMB ---

    private void beginClimb() {
        setPhase(PHASE_CLIMB);
        wallLostTicks = 0;
        mob.getNavigation().stop();
        mob.setWallLocked(true);
        mob.setNoGravity(true);
        mob.setActionState(PhalangiotarbusEntity.STATE_CLIMB);
        int height = CLIMB_MIN + mob.getRandom().nextInt(CLIMB_MAX - CLIMB_MIN + 1);
        this.climbStartY = mob.getY();
        // 原木の柱の上端より上へは登らない(空中に張り付かないため)。
        double columnTop = perchColumnTopY(mob.getClimbFacing());
        this.climbTargetY = Math.min(mob.getY() + height, columnTop - CLING_CLEARANCE);
        mob.playSound(ModSounds.PHALANGIOTARBUS_STEP.get(), 0.25F, 0.8F);
    }

    private void tickClimb() {
        Direction facing = mob.getClimbFacing();
        if (!mob.isAgainstAnyPerchWall(true)) {
            // 一瞬壁を見失った。少し降りて取り付き直す。数tick続いたら仕切り直し。
            wallLostTicks++;
            mob.setDeltaMovement(0.0D, -0.12D, 0.0D);
            if (wallLostTicks > 12) {
                beginCling();
            }
            return;
        }
        wallLostTicks = 0;

        // 十分登ったら、縦に CLING_CLEARANCE マスの余裕がある地点で止まって張り付く
        // (葉に突っ込んで空中で張り付かないように)。
        if (mob.getY() - climbStartY >= CLIMB_MIN && isValidClingHere()) {
            beginCling();
            return;
        }

        BlockPos wall = wallBlock(facing);
        // 壁へ押し付けつつ、幹の中心線からずれていれば横方向にも寄せて上昇する
        // (角や隣のブロックに乗り上げて浮かないように)。向きは aiStep が壁方向へ固定している。
        double tx = 0.0D;
        double tz = 0.0D;
        if (wall != null) {
            if (facing.getAxis() == Direction.Axis.X) {
                tz = Mth.clamp((wall.getZ() + 0.5D) - mob.getZ(), -0.1D, 0.1D);
            } else {
                tx = Mth.clamp((wall.getX() + 0.5D) - mob.getX(), -0.1D, 0.1D);
            }
        }
        mob.setDeltaMovement(facing.getStepX() * 0.1D + tx, CLIMB_RISE, facing.getStepZ() * 0.1D + tz);
        mob.hasImpulse = true;

        if (mob.getY() >= climbTargetY
                || phaseTicks > CLIMB_TIMEOUT
                || (mob.horizontalCollision && mob.getDeltaMovement().y < 0.02D && phaseTicks > 10)) {
            beginCling();
        }
    }

    // --- CLING ---

    private void beginCling() {
        Direction f = mob.getClimbFacing();
        int fx = Mth.floor(mob.getX());
        int fz = Mth.floor(mob.getZ());
        int fy = findClingFeetY(f, fx, fz, Mth.floor(mob.getY()));
        if (fy == Integer.MIN_VALUE) {
            // 縦に CLING_CLEARANCE マスの余裕がある原木の側面が近くに無い。空中に張り付かず仕切り直す。
            restartSeek();
            return;
        }
        setPhase(PHASE_CLING);
        mob.getNavigation().stop();
        mob.setWallLocked(true);
        mob.setNoGravity(true);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setActionState(PhalangiotarbusEntity.STATE_CLING);
        snapToWallFace(f, fx, fz, fy);
        this.clingAnchor = mob.position();
    }

    /**
     * 張り付く原木の面のちょうど中央へ位置を吸着させる。幹の中心線に横方向を合わせ、
     * 高さは検証済みの {@code feetY}、面からの距離は当たり判定の半分ぶん空ける
     * (描画側でさらに樹皮へ寄せる)。
     */
    private void snapToWallFace(Direction f, int fx, int fz, int feetY) {
        BlockPos wall = new BlockPos(fx, feetY, fz).relative(f);
        double dist = 0.5D + mob.getBbWidth() * 0.5D - 0.02D;
        double ax = wall.getX() + 0.5D;
        double az = wall.getZ() + 0.5D;
        if (f.getAxis() == Direction.Axis.X) {
            ax -= f.getStepX() * dist;
        } else {
            az -= f.getStepZ() * dist;
        }
        mob.setPos(ax, feetY, az);
        mob.setDeltaMovement(Vec3.ZERO);
        float yaw = f.toYRot();
        mob.setYRot(yaw);
        mob.yRotO = yaw;
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
    }

    /**
     * 現在地が「縦に {@link #CLING_CLEARANCE} マスの原木＋前方の空間」を満たす張り付き地点か。
     */
    private boolean isValidClingHere() {
        return isValidClingSpot(mob.getClimbFacing(),
                Mth.floor(mob.getX()), Mth.floor(mob.getY()), Mth.floor(mob.getZ()));
    }

    /**
     * {@code preferY} 付近(上下 {@link #CLING_SCAN_RANGE} ブロック)で、条件を満たす足元 Y を探す。
     * まず preferY 以下を近い順に、無ければ上を探す。見つからなければ {@link Integer#MIN_VALUE}。
     */
    private int findClingFeetY(Direction f, int fx, int fz, int preferY) {
        for (int dy = 0; dy <= CLING_SCAN_RANGE; dy++) {
            if (isValidClingSpot(f, fx, preferY - dy, fz)) {
                return preferY - dy;
            }
            if (dy > 0 && isValidClingSpot(f, fx, preferY + dy, fz)) {
                return preferY + dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * {@code (fx, feetY, fz)} を足元として、{@code facing} 側に原木が縦 {@link #CLING_CLEARANCE}
     * マス連続し、かつ前方(その列自身)が同じ高さぶん体を収められる空間になっているか。
     */
    private boolean isValidClingSpot(Direction facing, int fx, int feetY, int fz) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < CLING_CLEARANCE; k++) {
            // 背面: 原木
            p.set(fx, feetY + k, fz).move(facing);
            if (!mob.level().getBlockState(p).is(ModTags.PHALANGIOTARBUS_PERCH)) {
                return false;
            }
            // 前方(自分の列): 体が収まる空間
            p.set(fx, feetY + k, fz);
            if (mob.level().getBlockState(p).blocksMotion()
                    || !mob.level().getFluidState(p).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** {@code facing} 方向の隣(高さ ±1 も見る)にある原木ブロック。無ければ {@code null}。 */
    private BlockPos wallBlock(Direction facing) {
        BlockPos base = mob.blockPosition();
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos p = base.above(dy).relative(facing);
            if (mob.level().getBlockState(p).is(ModTags.PHALANGIOTARBUS_PERCH)) {
                return p;
            }
        }
        return null;
    }

    /** {@code facing} 方向の原木の柱の上端 Y(その上がもう原木でなくなる高さ)。 */
    private double perchColumnTopY(Direction facing) {
        BlockPos wall = wallBlock(facing);
        if (wall == null) {
            return mob.getY();
        }
        BlockPos.MutableBlockPos p = wall.mutable();
        int top = wall.getY();
        for (int i = 0; i < 40; i++) {
            p.move(Direction.UP);
            if (!mob.level().getBlockState(p).is(ModTags.PHALANGIOTARBUS_PERCH)) {
                break;
            }
            top = p.getY();
        }
        return top + 1.0D;
    }

    private void tickCling() {
        // 原木の定位置にピン留め。
        if (clingAnchor == null) {
            clingAnchor = mob.position();
        }
        mob.getNavigation().stop();
        mob.setPos(clingAnchor.x, clingAnchor.y, clingAnchor.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setNoGravity(true);

        if (mob.tickCount < nextPounceTime) {
            return;
        }
        LivingEntity victim = nearestHuntableInRange();
        if (victim != null) {
            beginPounce(victim);
        }
    }

    private LivingEntity nearestHuntableInRange() {
        AABB box = mob.getBoundingBox().inflate(POUNCE_DETECT_RADIUS);
        List<LivingEntity> candidates = mob.level().getEntitiesOfClass(LivingEntity.class, box, mob::isHuntable);
        LivingEntity best = null;
        double bestSq = POUNCE_DETECT_RADIUS * POUNCE_DETECT_RADIUS;
        for (LivingEntity e : candidates) {
            double d = mob.distanceToSqr(e);
            // 視線は Sensing 経由(2026-09-24 軽量化、報告 1725 の (2))。同じ tick の中で同じ相手への答えを覚えるだけで、中身は同じ。
            if (d < bestSq && mob.getSensing().hasLineOfSight(e)) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    // --- POUNCE ---

    private void beginPounce(LivingEntity victim) {
        double dx = victim.getX() - mob.getX();
        double dz = victim.getZ() - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > POUNCE_MAX_RANGE) {
            return;
        }
        setPhase(PHASE_POUNCE);
        pounceVictim = victim;

        mob.setActionState(PhalangiotarbusEntity.STATE_POUNCE);
        mob.getNavigation().stop();
        // 幹から跳ぶのは踏み切りの姿勢が画面に出る tick(PhalangiotarbusEntity.POUNCE_LAUNCH_TICK)。
        // それまでは幹に張り付いたまま構える(tickPounce)。
    }

    /** 幹を蹴って相手へ跳び出す。構えている間に相手が動くので、向きはここで測り直す。 */
    private void launch(LivingEntity victim) {
        double dx = victim.getX() - mob.getX();
        double dz = victim.getZ() - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        mob.setWallLocked(false);
        mob.setNoGravity(false);

        Vec3 toVictim = dist > 1.0E-4D ? new Vec3(dx / dist, 0.0D, dz / dist) : mob.getLookAngle();
        // 壁を蹴る成分を少し足す。
        Direction wall = mob.getClimbFacing();
        double kickX = -wall.getStepX() * 0.25D;
        double kickZ = -wall.getStepZ() * 0.25D;
        mob.setDeltaMovement(toVictim.x * POUNCE_SPEED + kickX, POUNCE_LIFT, toVictim.z * POUNCE_SPEED + kickZ);
        mob.hasImpulse = true;

        float yaw = yawTo(dx, dz);
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.playSound(ModSounds.PHALANGIOTARBUS_POUNCE.get(), 0.5F, 1.0F);
        if (mob.level() instanceof ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.SPORE_BLOSSOM_AIR,
                    mob.getX(), mob.getY() + 0.3D, mob.getZ(), 8, 0.3D, 0.2D, 0.3D, 0.01D);
        }
    }

    private void tickPounce() {
        LivingEntity victim = pounceVictim;
        if (phaseTicks <= PhalangiotarbusEntity.POUNCE_LAUNCH_TICK && (victim == null || !victim.isAlive())) {
            // 跳ぶ前に相手がいなくなった。張り付きに戻る。
            pounceVictim = null;
            mob.setActionState(PhalangiotarbusEntity.STATE_CLING);
            setPhase(PHASE_CLING);
            return;
        }
        if (phaseTicks < PhalangiotarbusEntity.POUNCE_LAUNCH_TICK) {
            // 踏み切りの構え。幹の定位置に留める(tickCling と同じ)。
            if (clingAnchor != null) {
                mob.setPos(clingAnchor.x, clingAnchor.y, clingAnchor.z);
            }
            mob.setDeltaMovement(Vec3.ZERO);
            mob.getLookControl().setLookAt(victim, 30.0F, 30.0F);
            return;
        }
        if (phaseTicks == PhalangiotarbusEntity.POUNCE_LAUNCH_TICK) {
            launch(victim);
        }
        int flight = phaseTicks - PhalangiotarbusEntity.POUNCE_LAUNCH_TICK;     // 跳んだ tick が 0

        if (victim != null && victim.isAlive()) {
            double dx = victim.getX() - mob.getX();
            double dz = victim.getZ() - mob.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-4D) {
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(v.x + (dx / len) * 0.04D, v.y, v.z + (dz / len) * 0.04D);
            }
            mob.getLookControl().setLookAt(victim, 30.0F, 30.0F);

            if (mob.getBoundingBox().inflate(0.4D).intersects(victim.getBoundingBox()) && mob.isHuntable(victim)) {
                attachTo(victim);
                return;
            }
        }

        boolean landed = mob.onGround() && flight > 2;
        if (landed || flight > POUNCE_MAX_TICKS || victim == null || !victim.isAlive()) {
            nextPounceTime = mob.tickCount + POUNCE_COOLDOWN;
            pounceVictim = null;
            mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
            perch = mob.findPerchLog();
            setPhase(PHASE_SEEK);
        }
    }

    private void attachTo(LivingEntity victim) {
        boolean hit = mob.doHurtTarget(victim);
        if (!hit) {
            victim.hurt(mob.damageSources().mobAttack(mob),
                    (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
        }
        mob.setLatchTarget(victim);
        mob.setTarget(victim);
        mob.setActionState(PhalangiotarbusEntity.STATE_LATCH);
        mob.playSound(ModSounds.PHALANGIOTARBUS_LATCH.get(), 0.5F, 0.9F);
        pounceVictim = null;
    }

    private static float yawTo(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
    }
}
