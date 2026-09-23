package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 「メガネウラ」が獲物を仕留めたあと、近くの過繁茂した木の枝の<b>上</b>に降りて食事と休息をとるゴール。
 *
 * <p>ステノデクティアの {@link PerchOnBranchGoal}(枝の<b>下</b>にぶら下がる)とは別物で、
 * こちらは {@link ModTags#DRAGONFLY_PERCH} のブロックの<b>天面</b>に着地する。流れは3段階:
 *
 * <ol>
 *     <li><b>移動</b> — 枝の上の空きマスへ飛ぶ。{@link #TRAVEL_TIMEOUT} tick 以内に着けなければ諦める。</li>
 *     <li><b>食事</b>({@link OvergrownFlyerEntity#STATE_FEED}) — 着地して {@code relax}(羽を下ろす)を
 *     再生し、そのまま {@code chew}(咀嚼)をループする。{@link #MIN_CHEW_TICKS}〜{@link #MAX_CHEW_TICKS} tick。</li>
 *     <li><b>休息</b>({@link OvergrownFlyerEntity#STATE_REST}) — {@code relax2} をループしたまま、
 *     着地からの合計が {@link #MIN_REST_TICKS}〜{@link #MAX_REST_TICKS} tick(10〜30秒)になるまで動かない。</li>
 * </ol>
 *
 * <p>入るきっかけは {@link MeganeuraEntity#wantsRest()} — 獲物を倒したときに確率で立つフラグ。
 * 途中で攻撃されたら中断して飛び立つ。
 */
public class MeganeuraRestGoal extends Goal {

    /** 止まり木を探す水平半径。 */
    private static final int SEARCH_RADIUS = 8;
    /** 止まり木を探す上方向の範囲(木の枝は自分より上にあることが多い)。 */
    private static final int SEARCH_UP = 10;
    /** 止まり木を探す下方向の範囲。 */
    private static final int SEARCH_DOWN = 3;
    /** 止まり木まで飛ぶのに使える時間。これを超えたら諦める(経路が塞がっている場合の保険)。 */
    private static final int TRAVEL_TIMEOUT = 200;
    /** 咀嚼(食事)の長さ。 */
    private static final int MIN_CHEW_TICKS = 60;
    private static final int MAX_CHEW_TICKS = 100;
    /** 着地してから飛び立つまでの合計時間(食事の時間を含む)。10秒〜30秒。 */
    private static final int MIN_REST_TICKS = 200;
    private static final int MAX_REST_TICKS = 600;

    private final MeganeuraEntity mob;
    private final double approachSpeed;

    /** 止まり木そのもの(この上に乗る)。 */
    private BlockPos branchPos;
    /** 実際に静止する座標(止まり木の天面の中央)。 */
    private Vec3 restSpot;
    private int travelTicksLeft;
    private int chewTicksLeft;
    private int restTicksLeft;
    private boolean settled;

    public MeganeuraRestGoal(MeganeuraEntity mob, double approachSpeed) {
        this.mob = mob;
        this.approachSpeed = approachSpeed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.mob.wantsRest()) {
            return false;
        }
        // 攻撃を受けている最中はのんびり食事どころではない。この機会は流す。
        if (this.mob.getLastHurtByMob() != null) {
            this.mob.setWantsRest(false);
            this.mob.setPreyDisplay(null);
            return false;
        }
        this.branchPos = findBranch();
        if (this.branchPos == null) {
            // 近くに止まれる枝がなければ休息はしない(次に狩ったときに再挑戦)。
            this.mob.setWantsRest(false);
            this.mob.setPreyDisplay(null);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.branchPos == null || this.mob.getLastHurtByMob() != null) {
            return false;
        }
        if (!isPerchableTop(this.branchPos)) {
            // 枝が壊された/塞がれた。
            return false;
        }
        return this.settled ? this.restTicksLeft > 0 : this.travelTicksLeft > 0;
    }

    @Override
    public void start() {
        this.mob.setWantsRest(false);
        this.settled = false;
        this.travelTicksLeft = TRAVEL_TIMEOUT;
        this.chewTicksLeft = MIN_CHEW_TICKS + this.mob.getRandom().nextInt(MAX_CHEW_TICKS - MIN_CHEW_TICKS + 1);
        this.restTicksLeft = MIN_REST_TICKS + this.mob.getRandom().nextInt(MAX_REST_TICKS - MIN_REST_TICKS + 1);
        this.restSpot = spotAbove(this.branchPos);
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.mob.getNavigation().stop();
        this.mob.getMoveControl().setWantedPosition(this.restSpot.x, this.restSpot.y, this.restSpot.z, this.approachSpeed);
    }

    @Override
    public void stop() {
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.mob.setNoGravity(false);
        this.mob.getNavigation().stop();
        this.settled = false;
        this.branchPos = null;
        this.restSpot = null;
        // 途中で中断された場合の取りこぼし防止(正常に食べ終わったときは既に消えている)。
        this.mob.setPreyDisplay(null);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.restSpot == null) {
            return;
        }
        if (!this.settled) {
            this.travelTicksLeft--;
            this.mob.getLookControl().setLookAt(this.restSpot.x, this.restSpot.y, this.restSpot.z);
            if (this.mob.position().distanceToSqr(this.restSpot) < 0.5D) {
                settle();
            } else {
                this.mob.getMoveControl().setWantedPosition(
                        this.restSpot.x, this.restSpot.y, this.restSpot.z, this.approachSpeed);
            }
            return;
        }

        // 枝の天面に貼り付けたまま固定する(飛行 Mob なので放っておくと流されるため)。
        this.mob.setDeltaMovement(Vec3.ZERO);
        this.mob.setPos(this.restSpot.x, this.restSpot.y, this.restSpot.z);

        if (this.chewTicksLeft > 0) {
            this.chewTicksLeft--;
            if (this.chewTicksLeft == 0) {
                // 食べ終わったら口の中の獲物を消して、relax2 のループ(休息)へ。
                this.mob.setPreyDisplay(null);
                this.mob.setActionState(OvergrownFlyerEntity.STATE_REST);
            }
        }
        this.restTicksLeft--;
    }

    /** 枝に着地して食事({@code relax} → {@code chew})を始める。 */
    private void settle() {
        this.settled = true;
        this.mob.getNavigation().stop();
        this.mob.setNoGravity(true);
        this.mob.setPos(this.restSpot.x, this.restSpot.y, this.restSpot.z);
        this.mob.setDeltaMovement(Vec3.ZERO);
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FEED);
        // 食べている間は次の獲物を追わない(このゴールが狩りゴールより優先なので追跡は始まらない)。
        this.mob.setTarget(null);
    }

    /** 近くの止まり木(天面に乗れる {@link ModTags#DRAGONFLY_PERCH} ブロック)のうち一番近いものを返す。 */
    private BlockPos findBranch() {
        BlockPos origin = this.mob.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 17×14×17 ≒ 4000 マス × 最大 4 読み。チャンクキャッシュ付きの読み(結果は Level 経由と同じ)。
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_DOWN; dy <= SEARCH_UP; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!isPerchableTop(reader, x, y, z)) {
                        continue;
                    }
                    double d = origin.distToLowCornerSqr(x, y, z);
                    if (d < bestSq) {
                        bestSq = d;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /** {@code pos} が「上に乗れる露出した枝」か(止まり木で、真上が空いていて、休息時の当たり判定が収まる)。 */
    private boolean isPerchableTop(BlockPos pos) {
        if (!this.mob.level().getBlockState(pos).is(ModTags.DRAGONFLY_PERCH)) {
            return false;
        }
        BlockPos above = pos.above();
        if (this.mob.level().getBlockState(above).blocksMotion()
                || !this.mob.level().getFluidState(above).isEmpty()) {
            return false;
        }
        // 頭上にもう1マス余裕がある「露出した枝」だけを選ぶ。葉の内側だと体が枝葉に
        // 埋もれて見えるうえ、飛び立つときにも引っかかる。
        if (this.mob.level().getBlockState(pos.above(2)).blocksMotion()) {
            return false;
        }
        // 休息時の(縮めた)当たり判定がそこに本当に収まるか。体格の大きい個体が地面や
        // 隣のブロックに埋まってしまうのを防ぐ、最後の砦。
        return this.mob.level().noCollision(this.mob, this.mob.getRestingBoundingBox(spotAbove(pos)));
    }

    /** {@link #isPerchableTop(BlockPos)} と同じ判定を、走査用のキャッシュ付き読みで行う。 */
    private boolean isPerchableTop(ChunkCachedReader reader, int x, int y, int z) {
        if (!reader.getBlockState(x, y, z).is(ModTags.DRAGONFLY_PERCH)) {
            return false;
        }
        if (reader.getBlockState(x, y + 1, z).blocksMotion()
                || !reader.getFluidState(x, y + 1, z).isEmpty()) {
            return false;
        }
        if (reader.getBlockState(x, y + 2, z).blocksMotion()) {
            return false;
        }
        return this.mob.level().noCollision(this.mob, this.mob.getRestingBoundingBox(spotAbove(new BlockPos(x, y, z))));
    }

    /** {@code branch} の天面の中央 —— 実際に静止する座標。 */
    private static Vec3 spotAbove(BlockPos branch) {
        return new Vec3(branch.getX() + 0.5D, branch.getY() + 1.0D, branch.getZ() + 0.5D);
    }
}
