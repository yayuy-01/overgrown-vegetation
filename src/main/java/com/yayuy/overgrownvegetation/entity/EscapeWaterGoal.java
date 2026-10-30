package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.Trail;
import com.yayuy.overgrownvegetation.td.TdEvents;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;

/**
 * 水に落ちた陸の Mob が<b>いちばん近い陸へ急いで上がる</b> Goal(ユーザー要望 2026-09-17)。
 * 差し込みは {@link EscapeWaterInjector}(グループ1〜10 の陸生 Mob 全部)。
 *
 * <p>狙いは「水ぐされ({@link com.yayuy.overgrownvegetation.effect.WaterRotTracker})で溶けるまで
 * 水の中をうろうろしない」こと。発症まで 30 秒あるので、まっすぐ岸へ向かえばまず助かる。
 *
 * <h2>経路探索は使わない</h2>
 * 陸生の過繁茂 Mob は {@code PathType.WATER} の malus が -1(= 水に入らない)なので、
 * <b>水の中からでは経路が引けない</b> —— 出発点が「入ってはいけない場所」になってしまう。
 * そこでこの Goal は経路を引かず、{@link net.minecraft.world.entity.ai.control.MoveControl} に
 * 毎 tick 行き先を渡して直接泳がせる(経路探索は {@link #start()} で止める)。
 * 浮き上がりはバニラの {@link net.minecraft.world.entity.ai.goal.FloatGoal}(優先度 0・JUMP)が
 * やるので、こちらは MOVE と LOOK しか取らない。
 *
 * <h2>どの岸を選ぶか</h2>
 * 半径 {@value #SEARCH_RADIUS} の中の「足場があって水に浸かっていない」マスのうち<b>いちばん近いもの</b>。
 * ただし<b>陸にいる</b>相手を追跡中の場合、相手から遠ざかる岸には {@value #BACKWARD_PENALTY} の下駄を
 * 履かせるので、前方に上がれる岸があるかぎりそちらを選ぶ。これが無いと、川を渡っている最中に手前の岸へ
 * 戻り→追跡で水へ入り直し→また戻り、と汀で往復してしまう。陸の標的が無いときは、群れの向かう先・TD のターゲットを「前」にする
 * (2026-09-25)。上がれずにあきらめた岸は {@value #FAILED_SHORE_TICKS} tick 選ばない。岸の段は {@link WaterLedgeHop} が跳ね上げる。
 *
 * <p>相手が<b>水の中</b>にいる場合はこの下駄を履かせない —— 相手を追って川の奥へ上陸地点を探しに
 * 行ってしまうため。そもそも遠距離攻撃を持たない種は水中の相手を狙わない
 * ({@link Predation#isLandTarget}、2026-09-17)ので、これは反撃({@link OvergrownHurtByTargetGoal})で
 * 水中の相手を標的にしている場合の保険。
 *
 * <p>地形の走査は {@value #RESCAN_INTERVAL} tick に 1 回だけ({@link ChunkCachedReader} 経由)。
 * 見つからなかったときは {@value #FAILED_SCAN_INTERVAL} tick 空ける(大海原のど真ん中で毎 tick
 * 8000 マスを舐めないため)。{@value #MAX_TICKS} tick 泳いでも上がれなければ一度あきらめて、
 * ほかの Goal に譲る(壁に向かって泳ぎ続けて詰むのを避ける)。
 */
public class EscapeWaterGoal extends Goal {

    /** 岸を探す水平半径(マス)。 */
    private static final int SEARCH_RADIUS = 10;
    /**
     * 岸を探す上方向/下方向の範囲(マス)。上は 2 まで —— 水に浮いたまま上がれるのは {@link WaterLedgeHop} の跳ね上げで届く岸だけ
     * (2026-09-25: 4 まで選んでいたので、上がれない高い岸へ 300 tick 押し付けては同じ岸を選び直していた)。
     */
    private static final int SEARCH_UP = 2;
    private static final int SEARCH_DOWN = 3;
    /** 上がれずにあきらめた岸(とそのまわり 1 マス)を選ばない間(tick)。仮決め 2026-09-25。 */
    private static final int FAILED_SHORE_TICKS = 600;
    /** 「急いで」上がる ── 移動速度の倍率。 */
    private static final double SPEED = 1.3D;
    /** 岸を探し直す間隔(tick)。 */
    private static final int RESCAN_INTERVAL = 20;
    /** 岸が見つからなかったときに次を探すまでの間隔(tick)。 */
    private static final int FAILED_SCAN_INTERVAL = 60;
    /** これだけ泳いでも上がれなければ一度あきらめる(tick)。 */
    private static final int MAX_TICKS = 300;
    /** あきらめたあとの待ち(tick)。 */
    private static final int GIVE_UP_COOLDOWN = 100;
    /** 追跡中の相手から遠ざかる岸に履かせる下駄(探索範囲内のどの距離より大きい値)。 */
    private static final double BACKWARD_PENALTY = 4096.0D;

    /**
     * 岸を探すマスのずらし(dx, dy, dz)を<b>距離の 2 乗の小さい順、同じなら元の走査順</b>に並べた表と、各マスの距離の 2 乗・
     * 元の走査順の番号({@link #findNearestLand} が同点を元どおりに決めるのに使う)。2026-09-24 軽量化(報告 770)。
     * 元の走査順は dx 昇順 → dz 昇順 → dy 降順。クラスを読み込むときに 1 回だけ作り、以後は読むだけ。
     */
    private static final int[] SCAN_DX;
    private static final int[] SCAN_DY;
    private static final int[] SCAN_DZ;
    private static final int[] SCAN_DIST_SQ;
    private static final int[] SCAN_RASTER;

    static {
        int side = SEARCH_RADIUS * 2 + 1;
        int count = side * side * (SEARCH_UP + SEARCH_DOWN + 1);
        int[] dxs = new int[count];
        int[] dys = new int[count];
        int[] dzs = new int[count];
        int[] distSqs = new int[count];
        Integer[] order = new Integer[count];
        int n = 0;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = SEARCH_UP; dy >= -SEARCH_DOWN; dy--) {
                    dxs[n] = dx;
                    dys[n] = dy;
                    dzs[n] = dz;
                    distSqs[n] = dx * dx + dy * dy + dz * dz;
                    order[n] = n;
                    n++;
                }
            }
        }
        Arrays.sort(order, Comparator.<Integer>comparingInt(k -> distSqs[k]).thenComparingInt(k -> k));
        SCAN_DX = new int[count];
        SCAN_DY = new int[count];
        SCAN_DZ = new int[count];
        SCAN_DIST_SQ = new int[count];
        SCAN_RASTER = new int[count];
        for (int i = 0; i < count; i++) {
            int k = order[i];
            SCAN_DX[i] = dxs[k];
            SCAN_DY[i] = dys[k];
            SCAN_DZ[i] = dzs[k];
            SCAN_DIST_SQ[i] = distSqs[k];
            SCAN_RASTER[i] = k;
        }
    }

    private final PathfinderMob mob;
    private BlockPos shore;
    private int ticks;
    private int nextScanTick;
    /** 上がれずにあきらめた岸と、そこを選ばない tickCount。 */
    @Nullable
    private BlockPos failedShore;
    private int failedShoreUntil;

    public EscapeWaterGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.mob.isInWater() || this.mob.tickCount < this.nextScanTick) {
            return false;
        }
        // 群れが泳いで渡っている最中は岸へ引き返さない(群れ v2、2026-09-25)。
        if (crossingWithSwarm(this.mob)) {
            return false;
        }
        // 跳び越え({@link InvaderWaterLeapGoal})の最中に水面をかすめただけなら割り込まない。
        // 空中にいて、体が水に浸かっている高さも跳ねる閾値に届いていない = 通過中。
        if (!this.mob.onGround() && this.mob.getFluidHeight(FluidTags.WATER) < this.mob.getFluidJumpThreshold()) {
            return false;
        }
        // 動けない種(汚染シダなど)は探すだけ無駄。
        if (this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED) <= 0.0D) {
            return false;
        }
        this.nextScanTick = this.mob.tickCount + RESCAN_INTERVAL;
        this.shore = findNearestLand();
        if (this.shore == null) {
            this.nextScanTick = this.mob.tickCount + FAILED_SCAN_INTERVAL;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isInWater() && this.shore != null && this.ticks <= MAX_TICKS && !crossingWithSwarm(this.mob);
    }

    /**
     * 群れが<b>わざと水を渡っている</b>最中か(群れ v2、2026-09-25)。このあいだは岸へ引き返さない
     * —— 引き返すと、先導役が泳いで引いた足跡を後続がなぞれず、先導役自身も区間を毎回手前の岸で失う。
     * <ul>
     *     <li>後続: 足跡の次の点が SWIM(先導役が泳いだ)か LEAP(跳び越えた着地点。跳べない種・跳び損ねた個体は泳いで渡る)。</li>
     *     <li>先導役: 歩いている区間の今のノード・次のノードが水(狩り・攻城で水だけに塞がれたとき、群れは区間ごと泳いで渡る。
     *     swarm-api の仮決め 2026-09-25)。</li>
     * </ul>
     * 群れに居ない個体・渡っていない一員(足を滑らせて落ちた)は今までどおり岸へ上がる。
     */
    static boolean crossingWithSwarm(PathfinderMob mob) {
        Swarm swarm = SwarmManager.get(mob);
        if (swarm == null || swarm.kind() != Swarm.Kind.GROUND) {
            return false;
        }
        if (swarm.isFollower(mob)) {
            Trail.Kind next = swarm.followerNextKind(mob);
            return next == Trail.Kind.SWIM || next == Trail.Kind.LEAP;
        }
        if (!swarm.isVanguard(mob)) {
            return false;
        }
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        int next = path.getNextNodeIndex();
        for (int i = Math.max(0, next - 1); i <= Math.min(path.getNodeCount() - 1, next + 1); i++) {
            if (path.getNode(i).type == PathType.WATER) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        this.ticks = 0;
        // 水の中では経路が引けない(malus -1)。残っている経路は MoveControl を上書きするので捨てる。
        this.mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        if (this.ticks > MAX_TICKS) {
            this.nextScanTick = this.mob.tickCount + GIVE_UP_COOLDOWN;
            // 上がれなかった岸はしばらく選ばない(同じ岸へ押し付け直さない)。
            this.failedShore = this.shore;
            this.failedShoreUntil = this.mob.tickCount + FAILED_SHORE_TICKS;
        }
        this.shore = null;
        this.ticks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.ticks++;
        if (this.mob.tickCount >= this.nextScanTick) {
            this.nextScanTick = this.mob.tickCount + RESCAN_INTERVAL;
            BlockPos found = findNearestLand();
            if (found != null) {
                this.shore = found;
            }
        }
        double x = this.shore.getX() + 0.5D;
        double y = this.shore.getY();
        double z = this.shore.getZ() + 0.5D;
        this.mob.getLookControl().setLookAt(x, y + this.mob.getEyeHeight(), z);
        // MoveControl は 1 tick で WAIT に戻るので毎 tick 渡し直す。岸の段差は MoveControl では越えられない(浮いている間は段を
        // 登れず、ジャンプは泳ぎ上がるだけ)ので、押し付けたところを {@link WaterLedgeHop} が跳ね上げる。
        this.mob.getMoveControl().setWantedPosition(x, y, z, SPEED);
    }

    /** 半径 {@value #SEARCH_RADIUS} の中で上がれる陸のうち、いちばん「近い」もの。無ければ {@code null}。 */
    private BlockPos findNearestLand() {
        BlockPos origin = this.mob.blockPosition();
        LivingEntity target = this.mob.getTarget();
        if (target != null && AquaticOvergrownMob.inWaterOrAtSurface(target)) {
            // 水の中の相手に引っぱられると、岸ではなく川の奥を選んでしまう。
            target = null;
        }
        // 「前」: 陸の標的、無ければ群れの向かう先・TD のターゲット(2026-09-25: 標的の無い攻め手は来た岸へ戻っては群れの区間で
        // 水へ入り直していた)。前から遠ざかる岸には下駄を履かせる。
        Vec3 ahead = target != null ? target.position() : forwardPoint();
        double aheadDistSq = ahead == null ? 0.0D : ahead.distanceToSqr(this.mob.position());
        boolean avoidFailed = this.failedShore != null && this.mob.tickCount < this.failedShoreUntil;
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int bestRaster = Integer.MAX_VALUE;
        // 選ぶのは元のまま「点数(距離の 2 乗 + 下駄)がいちばん小さく、同点なら元の走査順(dx 昇順 → dz 昇順 → dy 降順)で
        // 先のマス」。マスを近い順(SCAN_*)に見るので、距離の 2 乗が今の最良を超えたら残りは全部選ばれず、そこで打ち切れる
        // (2026-09-24 軽量化、報告 770。元の走査順だと、近い岸が +x 側にあると遠い隅から読むので枝刈りが効かなかった)。
        // 同点の取り方は元の走査順の番号(SCAN_RASTER)で比べるので、選ばれる岸は以前とまったく同じ。
        // 後ろ向きの下駄付きの岸しか無いときは打ち切りが起きず、元どおり全部見る。
        for (int i = 0; i < SCAN_DIST_SQ.length; i++) {
            int distSq = SCAN_DIST_SQ[i];
            if (distSq > bestScore) {
                // 点数は距離の 2 乗以上。ここから先のマスはどれも今の最良より大きい。
                break;
            }
            int raster = SCAN_RASTER[i];
            if (distSq == bestScore && raster > bestRaster) {
                // 同点になれても、元の走査では今の最良より後ろ = 選ばれない。
                continue;
            }
            int x = origin.getX() + SCAN_DX[i];
            int y = origin.getY() + SCAN_DY[i];
            int z = origin.getZ() + SCAN_DZ[i];
            if (avoidFailed && Math.abs(x - this.failedShore.getX()) <= 1 && Math.abs(y - this.failedShore.getY()) <= 1
                    && Math.abs(z - this.failedShore.getZ()) <= 1) {
                continue;
            }
            if (!canStandOn(reader, x, y, z)) {
                continue;
            }
            double score = origin.distToLowCornerSqr(x, y, z);
            if (ahead != null && ahead.distanceToSqr(x + 0.5D, y, z + 0.5D) > aheadDistSq) {
                score += BACKWARD_PENALTY;
            }
            if (score < bestScore || (score == bestScore && raster < bestRaster)) {
                bestScore = score;
                bestRaster = raster;
                best = new BlockPos(x, y, z);
            }
        }
        return best;
    }

    /**
     * 陸の標的が無いときの「前」: 地上の群れの一員なら群れの向かう先(先導役の行き先)、TD の攻め手ならターゲット。どちらでもなければ null。
     */
    @Nullable
    private Vec3 forwardPoint() {
        Swarm swarm = SwarmManager.get(this.mob);
        if (swarm != null && swarm.kind() == Swarm.Kind.GROUND) {
            Vec3 goal = swarm.leadGoal();
            if (goal != null) {
                return goal;
            }
        }
        if (this.mob.level() instanceof ServerLevel level) {
            String eventId = TdEvents.activeEventId(level);
            BlockPos target = TdEvents.activeTarget(level);
            if (eventId != null && target != null && EventMobs.has(this.mob, EventMobs.Kind.TD, eventId)) {
                return Vec3.atCenterOf(target);
            }
        }
        return null;
    }

    /** そのマスに「水に浸からずに」立てるか(足場がある・体が入る)。 */
    private boolean canStandOn(ChunkCachedReader reader, int x, int y, int z) {
        if (!reader.getFluidState(x, y, z).isEmpty()) {
            // 水面/水中。ここへ上がっても水ぐされは止まらない。
            return false;
        }
        BlockState ground = reader.getBlockState(x, y - 1, z);
        if (!ground.blocksMotion()) {
            return false;
        }
        return !reader.getBlockState(x, y, z).blocksMotion()
                && !reader.getBlockState(x, y + 1, z).blocksMotion();
    }
}
