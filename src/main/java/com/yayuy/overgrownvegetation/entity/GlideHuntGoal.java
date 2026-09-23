package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * コエルロサウラヴスの<b>登って滑空して狩る</b>ゴール(ユーザー指定 2026-09-21)。
 *
 * <ol>
 *   <li><b>SEEK</b> — 近くの幹({@link ModTags#CLIMBABLE_TRUNK})の足元へ歩く。</li>
 *   <li><b>CLIMB</b> — 幹に張り付いて登る。幹が途切れるか {@link #MAX_CLIMB} 上がるまで。</li>
 *   <li><b>PERCH</b> — 天蓋で一息つき、飛び出す向きを決める。</li>
 *   <li><b>GLIDE</b> — 滑空比 3:1 で降りながら、<b>下と前を見て獲物を探す</b>。</li>
 *   <li><b>DIVE</b> — 見つけたら体を下に向けて急降下し、体当たりで噛みつく。</li>
 * </ol>
 *
 * <p><b>滑空は片道</b>。地面に着いたらそこで終わり、また木を探すところからやり直す。
 * 獲物を見つけられなければ 100 ブロック近く流されることもある —— 幹が 31〜40 ブロックあり、
 * 高さ 1 につき 3 進むので。空振りして遠くに降り、とぼとぼ歩いて木に戻るのも含めてこの種の生態。
 *
 * <p>獲物の判定は {@link Predation#isPredatorOf} —— つまり {@code predation.json} の表に従う。
 * <b>プレイヤーは対象に入らない</b>(グループ2〜3 は環境生物、という方針)。
 */
public class GlideHuntGoal extends Goal {

    /** 幹を探す半径。 */
    private static final int TRUNK_SEARCH = 12;
    /** 1 本の幹を登れる高さの上限。 */
    private static final int MAX_CLIMB = 42;
    /** 登る速さ(ブロック/tick)。 */
    private static final double CLIMB_SPEED = 0.16;
    /** 天蓋で構えている時間。 */
    private static final int PERCH_TICKS = 30;
    /** 獲物を探す間隔(滑空中)。 */
    private static final int SCAN_INTERVAL = 5;
    /** 獲物を探す範囲(滑空中の自分を中心とした箱の半径)。 */
    private static final double SCAN_RANGE = 24.0;
    /** これより近づいたら噛みつきを試す。 */
    private static final double BITE_RANGE = 1.6;
    /** 1 回の滑空が終わってから次に木を探すまでの間。 */
    private static final int REST_TICKS = 100;
    /** 幹が見つからなかったときに探し直すまでの間。 */
    private static final int RETRY_TICKS = 200;
    /** 周りの地面よりこれだけ高ければ、幹を登らずその場から飛び出す。 */
    private static final int MIN_LAUNCH_HEIGHT = 12;
    /** 周りの高さを測る輪の半径と点の数。 */
    private static final int LAUNCH_SAMPLE_RADIUS = 6;
    private static final int LAUNCH_SAMPLES = 8;
    /**
     * 飛び出してから<b>この高さぶん降りるまでは着地とみなさない</b>。
     * 天蓋の上から飛び出すと、縁へ出るまで足が葉に着いたままになる ——
     * そこで終わりにすると、飛んでは即着地を繰り返して固まって見える。
     */
    private static final double LAUNCH_CLEARANCE = 2.0;
    /** それでも降りられなかったときに諦めるまでの時間。 */
    private static final int LAUNCH_GRACE = 80;

    private enum Phase { SEEK, CLIMB, PERCH, GLIDE, DIVE }

    private final CoelurosauravusEntity glider;
    private Phase phase = Phase.SEEK;
    private BlockPos trunk;
    private double launchY;
    private int timer;
    private int cooldown;
    private LivingEntity prey;
    /** 飛び出したときの高さ。ここから降りて初めて着地を数える。 */
    private double glideStartY;
    private int grace;

    public GlideHuntGoal(CoelurosauravusEntity glider) {
        this.glider = glider;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (!this.glider.onGround() || this.glider.isInWater()) {
            return false;
        }
        // 目の前に獲物が居るなら、わざわざ登らずその場で襲えばよい(通常の近接ゴールに任せる)。
        if (this.glider.getTarget() != null && this.glider.distanceToSqr(this.glider.getTarget()) < 36.0D) {
            return false;
        }
        // <b>すでに高いなら登らない</b>(ユーザー指定 2026-09-21)。
        // 葉の天蓋の上に降りたときなど、幹が見つからず立ち往生していた。
        if (highEnoughToLaunch()) {
            this.trunk = null;
            return true;
        }
        this.trunk = findTrunk();
        return this.trunk != null;
    }

    /**
     * <b>周りより十分高いか</b>。まわりの地面(葉を数えない地表)を輪の上で測り、
     * <b>いちばん低いところ</b>との差で見る。
     *
     * <p>真下だけを見ると足りない —— 幹のてっぺんに立っていると自分の列の地表が自分自身になり、
     * 高さ 0 と出る。逆に平地では輪のどこを見ても差が出ないので、誤って飛び出すこともない。
     */
    private boolean highEnoughToLaunch() {
        int lowest = Integer.MAX_VALUE;
        BlockPos at = this.glider.blockPosition();
        for (int i = 0; i < LAUNCH_SAMPLES; i++) {
            double angle = 2.0 * Math.PI * i / LAUNCH_SAMPLES;
            int x = at.getX() + (int) Math.round(Math.cos(angle) * LAUNCH_SAMPLE_RADIUS);
            int z = at.getZ() + (int) Math.round(Math.sin(angle) * LAUNCH_SAMPLE_RADIUS);
            if (!this.glider.level().isLoaded(new BlockPos(x, at.getY(), z))) {
                continue;
            }
            lowest = Math.min(lowest,
                    this.glider.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        }
        if (lowest == Integer.MAX_VALUE) {
            return false;
        }
        return this.glider.getY() - lowest >= MIN_LAUNCH_HEIGHT;
    }

    @Override
    public boolean canContinueToUse() {
        return this.phase != Phase.SEEK || this.trunk != null;
    }

    @Override
    public void start() {
        this.timer = 0;
        this.prey = null;
        if (this.trunk == null) {
            // 幹を経由せずその場から飛び出す。天蓋の上・崖の上・別の木の枝の上など。
            this.phase = Phase.PERCH;
            this.glider.setFlightState(CoelurosauravusEntity.STATE_PERCH);
            return;
        }
        this.phase = Phase.SEEK;
        this.glider.setFlightState(CoelurosauravusEntity.STATE_GROUND);
    }

    @Override
    public void stop() {
        this.glider.setFlightState(CoelurosauravusEntity.STATE_GROUND);
        this.glider.setNoGravity(false);
        this.glider.getNavigation().stop();
        this.trunk = null;
        this.prey = null;
        this.cooldown = REST_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (this.phase) {
            case SEEK -> tickSeek();
            case CLIMB -> tickClimb();
            case PERCH -> tickPerch();
            case GLIDE -> tickGlide(false);
            case DIVE -> tickGlide(true);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 足元へ歩く
    private void tickSeek() {
        if (this.trunk == null) {
            this.cooldown = RETRY_TICKS;
            this.phase = Phase.SEEK;
            return;
        }
        double distSq = this.glider.distanceToSqr(this.trunk.getX() + 0.5D, this.glider.getY(),
                this.trunk.getZ() + 0.5D);
        if (distSq <= 2.5D) {
            this.phase = Phase.CLIMB;
            this.launchY = this.glider.getY();
            this.glider.setFlightState(CoelurosauravusEntity.STATE_CLIMB);
            this.glider.getNavigation().stop();
            return;
        }
        if (this.glider.getNavigation().isDone()) {
            boolean ok = this.glider.getNavigation().moveTo(this.trunk.getX() + 0.5D,
                    this.trunk.getY(), this.trunk.getZ() + 0.5D, 1.0D);
            if (!ok) {
                this.trunk = null;   // 行けない幹は諦める
            }
        }
        if (++this.timer > 200) {
            this.trunk = null;
        }
    }

    // ------------------------------------------------------------------ 幹を登る
    private void tickClimb() {
        // 幹の中心へ体を寄せてから上がる。離れると落ちる。
        double cx = this.trunk.getX() + 0.5D;
        double cz = this.trunk.getZ() + 0.5D;
        this.glider.setNoGravity(true);
        this.glider.setDeltaMovement((cx - this.glider.getX()) * 0.2D, CLIMB_SPEED,
                (cz - this.glider.getZ()) * 0.2D);
        this.glider.hasImpulse = true;

        BlockPos head = BlockPos.containing(cx, this.glider.getY() + 1.5D, cz);
        boolean stillTrunk = this.glider.level().getBlockState(head).is(ModTags.CLIMBABLE_TRUNK);
        boolean tooHigh = this.glider.getY() - this.launchY >= MAX_CLIMB;
        if (!stillTrunk || tooHigh) {
            this.phase = Phase.PERCH;
            this.timer = 0;
            this.glider.setFlightState(CoelurosauravusEntity.STATE_PERCH);
            this.glider.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ------------------------------------------------------------------ 天蓋で構える
    private void tickPerch() {
        this.glider.setNoGravity(true);
        this.glider.setDeltaMovement(Vec3.ZERO);
        if (++this.timer == 1) {
            // <b>いちばん低い方へ</b>飛び出す。適当な向きだと天蓋の真ん中へ出てしまい、
            // 縁に着くまで葉の上を滑る羽目になる。
            this.glider.setYRot(headingToDrop());
        }
        if (this.timer >= PERCH_TICKS) {
            this.phase = Phase.GLIDE;
            this.timer = 0;
            this.glideStartY = this.glider.getY();
            this.grace = 0;
            this.glider.setNoGravity(false);
            this.glider.setFlightState(CoelurosauravusEntity.STATE_GLIDE);
        }
    }

    // ------------------------------------------------------------------ 滑空／急降下
    private void tickGlide(boolean diving) {
        this.grace++;
        // 飛び出した高さから十分降りて初めて「着地」とみなす。天蓋の縁へ出るまでは
        // 足が葉に着いたままなので、そこで終わりにすると飛んでは即着地を繰り返す。
        boolean cleared = this.glideStartY - this.glider.getY() >= LAUNCH_CLEARANCE;
        boolean grounded = this.glider.onGround() || this.glider.isInWater();
        if (grounded && (cleared || this.grace > LAUNCH_GRACE)) {
            this.phase = Phase.SEEK;
            this.trunk = null;
            this.glider.setFlightState(CoelurosauravusEntity.STATE_GROUND);
            this.cooldown = REST_TICKS;
            return;
        }
        if (diving) {
            if (this.prey == null || !this.prey.isAlive()) {
                backToGlide();
                return;
            }
            this.glider.glideTowards(this.prey.position(), true);
            this.glider.setFlightState(CoelurosauravusEntity.STATE_DIVE);
            if (this.glider.distanceToSqr(this.prey) <= BITE_RANGE * BITE_RANGE) {
                this.glider.setTarget(this.prey);
                this.glider.doHurtTarget(this.prey);
                backToGlide();
            }
            return;
        }

        this.glider.glideTowards(null, false);
        this.glider.setFlightState(CoelurosauravusEntity.STATE_GLIDE);
        if (++this.timer % SCAN_INTERVAL != 0) {
            return;
        }
        LivingEntity found = scanForPrey();
        if (found != null) {
            this.prey = found;
            this.phase = Phase.DIVE;
        }
    }

    private void backToGlide() {
        this.prey = null;
        this.phase = Phase.GLIDE;
        this.glider.setFlightState(CoelurosauravusEntity.STATE_GLIDE);
    }

    /**
     * 滑空しながら<b>下と前</b>を見る。真上や真後ろは見ない —— 滑空では戻れないので、
     * 見つけても無駄に旋回するだけになる。
     */
    private LivingEntity scanForPrey() {
        AABB box = this.glider.getBoundingBox().inflate(SCAN_RANGE, SCAN_RANGE * 0.5, SCAN_RANGE)
                .move(0.0D, -SCAN_RANGE * 0.35D, 0.0D);
        List<LivingEntity> near = this.glider.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && Predation.isPredatorOf(this.glider, e));
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity candidate : near) {
            if (candidate.getY() > this.glider.getY() - 1.0D) {
                continue;   // 自分より上は狙わない(降りるしかないので)
            }
            double sq = this.glider.distanceToSqr(candidate);
            if (sq < bestSq) {
                bestSq = sq;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 周りを見渡して<b>いちばん低い方角</b>(度)。飛び出す向きに使う。
     * 測る点は {@link #highEnoughToLaunch()} と同じ輪。
     */
    private float headingToDrop() {
        int lowest = Integer.MAX_VALUE;
        double bestAngle = this.glider.getRandom().nextDouble() * 2.0 * Math.PI;
        BlockPos at = this.glider.blockPosition();
        for (int i = 0; i < LAUNCH_SAMPLES; i++) {
            double angle = 2.0 * Math.PI * i / LAUNCH_SAMPLES;
            int x = at.getX() + (int) Math.round(Math.cos(angle) * LAUNCH_SAMPLE_RADIUS);
            int z = at.getZ() + (int) Math.round(Math.sin(angle) * LAUNCH_SAMPLE_RADIUS);
            if (!this.glider.level().isLoaded(new BlockPos(x, at.getY(), z))) {
                continue;
            }
            int ground = this.glider.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (ground < lowest) {
                lowest = ground;
                bestAngle = angle;
            }
        }
        double dx = Math.cos(bestAngle);
        double dz = Math.sin(bestAngle);
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** 近くでいちばん近い幹。登れる高さがあるものだけ。 */
    private BlockPos findTrunk() {
        BlockPos origin = this.glider.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -TRUNK_SEARCH; dx <= TRUNK_SEARCH; dx++) {
            for (int dz = -TRUNK_SEARCH; dz <= TRUNK_SEARCH; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!this.glider.level().getBlockState(cursor).is(ModTags.CLIMBABLE_TRUNK)) {
                        continue;
                    }
                    // 登る意味がない低い幹は捨てる(滑空の高さが稼げない)。
                    if (!this.glider.level().getBlockState(cursor.above(8)).is(ModTags.CLIMBABLE_TRUNK)) {
                        continue;
                    }
                    double sq = this.glider.distanceToSqr(cursor.getX() + 0.5D, cursor.getY(),
                            cursor.getZ() + 0.5D);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }
}
