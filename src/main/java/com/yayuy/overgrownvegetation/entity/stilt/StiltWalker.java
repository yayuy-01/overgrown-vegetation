package com.yayuy.overgrownvegetation.entity.stilt;

import com.yayuy.overgrownvegetation.entity.forest.CoarseDetour;
import com.yayuy.overgrownvegetation.entity.forest.ForestMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 竹馬型の歩き(2026-11-10、移動の型 ③。種によらない。1 体目はフィロセンス)。<b>サーバーで</b>胴を経路に沿って運び、足を 1 本ずつ置く。
 *
 * <h2>エンティティの位置 = 胴</h2>
 * エンティティの当たり判定は胴の箱(1.5 × 1.5)で、空中に浮いている(重力なし)。足はここが決めた<b>ワールドの点</b>で、
 * 描画は胴からそこへ脚を IK で伸ばす。理由(11-10 Claude):
 * <ul>
 *   <li>地面に置くと、当たり判定を 7 ブロックの柱にするしかなく、胴の高さの葉・枝で止まる(かがんでくぐれない)。</li>
 *   <li>段 2 の「胴を約 2.5 まで下ろして前へ倒す」は、ここの目標の高さを変えるだけで済む。</li>
 *   <li>段 3 の脚 4 本の部位(PartEntity)は、胴の付け根と {@link #foot} を結ぶ線に置ける(サーバーが足の位置を持っている)。</li>
 * </ul>
 *
 * <h2>1 tick の流れ</h2>
 * <ol>
 *   <li>経路({@link StiltPlanner})の次の節へ向かう速さと、節の胴の高さ。速さは森 ↔ 開けた所({@link ForestMap} の幹までの距離)。</li>
 *   <li><b>支え</b>: 着いている足のどれかが、動いた後の付け根から届かなくなるなら、胴を止める(足場が無いと止まって探す)。</li>
 *   <li>胴を動かす({@code Mob#move}。ブロックには当たる)。向きは進む向きへ {@link StiltParams#maxTurn} 度ずつ。</li>
 *   <li>宙の足が無ければ、置き場から一番ずれた脚を 1 本選び、置き場のまわりの足場を探して踏み替えを始める。</li>
 *   <li>宙の足が下りたら、そこにいる人・Mob を押しのける(ダメージ無し。決定 11-10)。</li>
 * </ol>
 * 足の位置と踏み替えはクライアントへ {@link #syncTag} で送る(変わるのは踏み替えの始めだけ)。宙の弧はクライアントが描く。
 */
public final class StiltWalker {

    /** 持ち主(Mob)が実装する。 */
    public interface Holder {
        StiltWalker stiltWalker();

        /** 足の位置と踏み替えをクライアントへ送る(SynchedEntityData の CompoundTag に入れる)。 */
        void sendGait(CompoundTag tag);
    }

    final Mob mob;
    final Holder holder;
    public final StiltParams p;
    private final int n;

    // ---- 足(サーバーの正本)
    private final Vec3[] foot;
    private boolean ready;
    private int swingLeg = -1, swingT, swingLen, seq, lastLeg = -1;
    private Vec3 swingTo;

    // ---- 経路
    @Nullable
    private List<StiltPlanner.Node> path;
    private int pathIdx;
    @Nullable
    private Vec3 goal;
    private double speedMod = 1.0D;
    private boolean reaches;
    private long nextPlan;
    private int stall, gaitWait, replans;
    private double bestGap = Double.MAX_VALUE;
    /** 続きから探している途中の探索(無ければ null)と、届かないと覚えた目標を探し直してよい時刻。 */
    @Nullable
    private StiltPlanner.Search search;
    private long unreachUntil;
    private double targetB = Double.NaN;
    private double open = 1.0D;
    private Vec3 vel = Vec3.ZERO;
    /** 確かめ用: 最後の tick の目標の速さ・支えで縮めた倍率。 */
    private double lastWant, lastScale;
    /** 確かめ用: 最後の足場探しで落とした理由の数(足場なし・下りる頃に届かない・今届かない・ほかの足と近い)と脚。 */
    private int whyNo, whyLand, whyNow, whyCrowd, whyLeg = -1;

    /** 確かめ用の 1 行(navto の途中経過)。 */
    public String debug() {
        StringBuilder feet = new StringBuilder();
        Vec3 pos = this.mob.position();
        for (int i = 0; i < n; i++) {
            if (this.foot[i] == null) continue;
            Vec3 f = this.foot[i], hp = hip(i, pos, this.mob.getYRot());
            feet.append(String.format(java.util.Locale.ROOT, " 足%d(%+.1f,%+.1f,%+.1f%s)", i, f.x - pos.x, f.y - pos.y, f.z - pos.z,
                    reachable(hp, f, 0.0D) ? "" : " 届かない"));
        }
        return String.format(java.util.Locale.ROOT, "速さ %.3f 支え %.2f 速さ実 %.3f 宙 %d 待ち %d 詰まり %d 探し直し %d 節 %d/%d 胴の目標 %.2f%s",
                this.lastWant, this.lastScale, Math.hypot(this.vel.x, this.vel.z), this.swingLeg, this.gaitWait, this.stall, this.replans,
                this.pathIdx, this.path == null ? -1 : this.path.size(), this.targetB, feet)
                + String.format(java.util.Locale.ROOT, " 探した脚%d なし%d 下り%d 今%d 近い%d", this.whyLeg, this.whyNo, this.whyLand, this.whyNow, this.whyCrowd);
    }

    /** 計測用: 歩きの 1 tick の時間(ns)の合計・回数・足場探しの回数。 */
    public static volatile long tickNanos, ticks, footSearches;
    /** 計測用: サーバーの 1 tick の全個体の合計の最大・1 体の 1 tick の最大・足の部分の最大(ns)。 */
    public static volatile long maxTickSum, maxOneNanos, maxFeetNanos;
    private static long sumTick = Long.MIN_VALUE, tickSum;

    /** 計測用: 今の tick の内訳(経路の時間・回数・打ち切りの回数・足・森の地図・歩いた個体の数)と、合計が最大だった tick の内訳。 */
    private static long curPlan, curPlans, curCapped, curFeet, curForest, curWalkers, curStand, curGc, curMove;
    /** 確かめ用(テストクライアントだけ): 最悪の tick の中で GC が止めた時間を測る。 */
    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");

    private static long gcMillis() {
        long s = 0L;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) s += Math.max(0L, b.getCollectionTime());
        return s;
    }
    public static volatile String worstTick = "-";
    /** 計測用: 経路を探した回数・打ち切った回数・届かない目標を覚えていて探さなかった回数。 */
    public static volatile long plans, cappedPlans, skippedPlans;

    public static void resetStats() {
        maxTickSum = maxOneNanos = maxFeetNanos = 0L;
        tickSum = 0L;
        curPlan = curPlans = curCapped = curFeet = curForest = curWalkers = curStand = curGc = curMove = 0L;
        worstTick = "-";
        plans = cappedPlans = skippedPlans = 0L;
    }
    /** 確かめ用: 最後の経路の答え(navto の返事)。 */
    @Nullable
    public StiltPlanner.Plan lastPlan;

    public StiltWalker(Mob mob, Holder holder, StiltParams p) {
        this.mob = mob;
        this.holder = holder;
        this.p = p;
        this.n = p.homeLeft.length;
        this.foot = new Vec3[n];
    }

    // ------------------------------------------------------------------ 外から

    /** (x, y, z) へ歩く。speed は速さの倍率(1 = 森 0.12 ↔ 開けた所 0.28)。 */
    public void goTo(double x, double y, double z, double speed) {
        this.goal = new Vec3(x, y, z);
        this.search = null;
        this.unreachUntil = 0L;
        this.speedMod = speed;
        this.path = null;
        this.pathIdx = 0;
        this.replans = 0;
        this.stall = 0;
        this.gaitWait = 0;
        this.nextPlan = 0L;
        this.bestGap = Double.MAX_VALUE;
    }

    public void stop() {
        this.goal = null;
        this.path = null;
        this.search = null;
    }

    /** 歩いている(目標がある)か。 */
    public boolean moving() {
        return this.goal != null;
    }

    public boolean reaches() {
        return this.reaches;
    }

    @Nullable
    public List<StiltPlanner.Node> path() {
        return this.path;
    }

    /** 足 i の今の位置(宙の足は下りる所)。サーバー。 */
    public Vec3 foot(int i) {
        return this.foot[i];
    }

    public int swingLeg() {
        return this.swingLeg;
    }

    public boolean ready() {
        return this.ready;
    }

    /** 胴の中心(ワールド)。 */
    public Vec3 center() {
        return this.mob.position().add(0.0D, p.bodyHalf, 0.0D);
    }

    /** 脚 i の付け根(ワールド)。 */
    public Vec3 hip(int i, Vec3 bodyPos, float yawDeg) {
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double l = p.hipLeft[i], f = p.hipFwd[i];
        return new Vec3(bodyPos.x + lx * l + fx * f, bodyPos.y + p.bodyHalf + p.hipUp, bodyPos.z + lz * l + fz * f);
    }

    // ------------------------------------------------------------------ tick

    /** サーバーの tick(Mob#customServerAiStep から)。 */
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel level)) return;
        long t0 = System.nanoTime();
        long gc0 = DEV ? gcMillis() : 0L;
        if (!this.ready) {
            long su = System.nanoTime();
            standUp(level);
            curStand += System.nanoTime() - su;
            this.ready = true;
            sync();
        }
        Vec3 pos = this.mob.position();
        float yaw = this.mob.getYRot();

        // 1. 経路と目標の速さ・高さ
        double dx = 0.0D, dz = 0.0D, want = 0.0D;
        if (this.goal != null) {
            if (this.path == null && level.getGameTime() >= this.nextPlan) {
                replan(level);
            }
            if (this.path != null) {
                while (this.pathIdx < this.path.size()) {
                    StiltPlanner.Node nd = this.path.get(this.pathIdx);
                    double ex = nd.cx() - pos.x, ez = nd.cz() - pos.z;
                    if (Math.abs(pos.y - nd.b()) < 0.6D) {
                        if (ex * ex + ez * ez < 0.3D * 0.3D) {
                            this.pathIdx++;
                            continue;
                        }
                        // 角を削って通り過ぎた: 次の節へ、この節より近い
                        if (this.pathIdx + 1 < this.path.size()) {
                            StiltPlanner.Node nx = this.path.get(this.pathIdx + 1);
                            double a = Math.hypot(nx.cx() - pos.x, nx.cz() - pos.z), b = Math.hypot(nx.cx() - nd.cx(), nx.cz() - nd.cz());
                            if (a < b && ex * ex + ez * ez < 1.0D) {
                                this.pathIdx++;
                                continue;
                            }
                        }
                    }
                    break;
                }
                if (this.pathIdx >= this.path.size()) {
                    double gx = this.goal.x - pos.x, gz = this.goal.z - pos.z;
                    if (this.reaches || gx * gx + gz * gz < 2.0D * 2.0D || this.replans >= p.unreachableTries) {
                        stop();
                    } else {
                        // 届かない経路の終わり(一番近づける所): 覚えた時間が過ぎるまで待ってから探し直す(道が開いていれば気づく)
                        this.path = null;
                        this.nextPlan = Math.max(level.getGameTime() + 20L, this.unreachUntil);
                        this.replans++;
                    }
                } else {
                    StiltPlanner.Node nd = this.path.get(this.pathIdx);
                    // ならし: 先の 4 節までで、間の節がまっすぐの線から 0.6 以内・高さが揃っている一番遠い節へ向かう(格子のジグザグで向きが揺れない)
                    StiltPlanner.Node aim = nd;
                    for (int k = this.pathIdx + 1; k < Math.min(this.path.size(), this.pathIdx + 5); k++) {
                        StiltPlanner.Node c = this.path.get(k);
                        boolean ok = Math.abs(c.b() - nd.b()) < 0.3D;
                        for (int j = this.pathIdx; ok && j < k; j++) {
                            StiltPlanner.Node m = this.path.get(j);
                            ok = Math.abs(m.b() - nd.b()) < 0.3D && offLine(pos.x, pos.z, c.cx(), c.cz(), m.cx(), m.cz()) < 0.6D;
                        }
                        if (!ok) break;
                        aim = c;
                    }
                    dx = aim.cx() - pos.x;
                    dz = aim.cz() - pos.z;
                    this.targetB = nd.b();
                    this.open = nd.open();
                    want = speed(level, nd);
                }
            }
        }
        // 2. 動き(高さを先に合わせる。くぐる前にかがむ・段を上る前に伸びる)
        double h = Math.sqrt(dx * dx + dz * dz);
        double vx = 0.0D, vz = 0.0D, vy = 0.0D;
        // 胴の目標の高さは、着いている足から脚が届く高さまで(段を上るとき、後ろ足が下にある間は低いまま前へ出る)
        double tb = this.targetB;
        if (!Double.isNaN(tb)) {
            double top = reachTop(pos, yaw) - 0.1D, bottom = feetFloor() + 0.05D;
            // 下限: 付け根が着いている足より下がらない(深さ 5 の池へ下りるとき、岸に残った後ろ足より胴が低くなって支えが切れた、11-10)
            tb = bottom > top ? pos.y : Mth.clamp(tb, bottom, top);
            vy = Mth.clamp(tb - pos.y, -p.maxVy, p.maxVy);
        }
        if (h > 1.0E-4D && want > 0.0D) {
            double off = Double.isNaN(tb) ? 0.0D : Math.abs(tb - pos.y);
            double k = off > 0.5D ? 0.15D : 1.0D;
            double s = Math.min(want * k, h);
            vx = dx / h * s;
            vz = dz / h * s;
        }
        float newYaw = yaw;
        if (h > 0.05D && want > 0.0D) {
            float to = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
            newYaw = yaw + Mth.clamp(Mth.wrapDegrees(to - yaw), (float) -p.maxTurn, (float) p.maxTurn);
            // 大きく向きを変える間は前へ出ない(脚が付いてこられない)
            float left = Math.abs(Mth.wrapDegrees(to - newYaw));
            if (left > 45.0F) {
                vx *= 0.2D;
                vz *= 0.2D;
            }
        }
        // 歩調: 着いている足のうち、進む向きへあとどれだけ届くか(余り)が一番少ないものに合わせて速さを落とす
        // (余りが尽きてから止まると「進む・止まる」の繰り返しになった、11-10)
        double hv = Math.hypot(vx, vz);
        if (hv > 1.0E-6D) {
            double slack = slack(pos, newYaw, vx / hv, vz / hv);
            double k = Mth.clamp((slack - 0.15D) / 1.4D, 0.0D, 1.0D);
            vx *= k;
            vz *= k;
        }
        // 支え: 着いている足が動いた後の付け根から届くか。届かなければ縮める
        double scale = 1.0D;
        for (int k = 0; k < 4 && scale > 0.0D; k++) {
            Vec3 np = pos.add(vx * scale, vy * scale, vz * scale);
            if (supported(np, newYaw)) break;
            scale = k < 2 ? scale * 0.5D : 0.0D;
        }
        if (scale == 0.0D && !supported(pos, newYaw)) {
            newYaw = yaw;   // 回るのも止める
        }
        if (scale < 1.0D && (vx != 0.0D || vz != 0.0D)) {
            this.gaitWait++;
        } else {
            this.gaitWait = 0;
        }
        this.lastWant = want;
        this.lastScale = scale;
        Vec3 move = new Vec3(vx * scale, vy * scale, vz * scale);
        Vec3 before = this.mob.position();
        if (move.lengthSqr() > 1.0E-8D) {
            long mv = System.nanoTime();
            this.mob.move(MoverType.SELF, move);
            curMove += System.nanoTime() - mv;
        }
        this.mob.setDeltaMovement(Vec3.ZERO);
        this.mob.setYRot(newYaw);
        this.mob.yBodyRot = newYaw;
        this.mob.yHeadRot = newYaw;
        Vec3 after = this.mob.position();
        this.vel = this.vel.scale(0.6D).add(after.subtract(before).scale(0.4D));

        // 詰まり: 進もうとして進めない
        if (this.goal != null && want > 0.0D) {
            double moved = Math.hypot(after.x - before.x, after.z - before.z);
            if (moved < 0.004D && Math.abs(after.y - before.y) < 0.004D) this.stall++;
            else this.stall = Math.max(0, this.stall - 2);
            if (this.stall > 60 || this.gaitWait > 160) {
                this.stall = 0;
                this.gaitWait = 0;
                this.replans++;
                this.path = null;
                this.search = null;
                this.nextPlan = level.getGameTime() + 10L;
                if (this.replans > 5) stop();
            }
        }

        // 3. 足
        long tf = System.nanoTime();
        tickFeet(level, after, newYaw);
        long now = System.nanoTime();
        long ns = now - t0;
        tickNanos += ns;
        ticks++;
        maxFeetNanos = Math.max(maxFeetNanos, now - tf);
        maxOneNanos = Math.max(maxOneNanos, ns);
        long gt = level.getGameTime();
        if (gt != sumTick) {
            if (tickSum > maxTickSum) {
                worstTick = String.format(java.util.Locale.ROOT, "合計 %.2f ms = 経路 %.2f ms(%d 回・打ち切り %d)+ 足 %.2f ms + 森の地図 %.2f ms + 立ち上がり %.2f ms + 胴の移動 %.2f ms + そのほか %.2f ms(%d 体)・この tick の中の GC %d ms",
                        tickSum / 1.0e6, curPlan / 1.0e6, curPlans, curCapped, curFeet / 1.0e6, curForest / 1.0e6, curStand / 1.0e6, curMove / 1.0e6,
                        (tickSum - curPlan - curFeet - curForest - curStand - curMove) / 1.0e6, curWalkers, curGc);
            }
            maxTickSum = Math.max(maxTickSum, tickSum);
            sumTick = gt;
            tickSum = 0L;
            curPlan = curPlans = curCapped = curFeet = curForest = curWalkers = curStand = curGc = curMove = 0L;
        }
        tickSum += ns;
        if (DEV) curGc += gcMillis() - gc0;
        curFeet += now - tf;
        curWalkers++;
    }

    /** 点 (px, pz) と、(ax, az)〜(bx, bz) の線分との距離。 */
    private static double offLine(double ax, double az, double bx, double bz, double px, double pz) {
        double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
        double t = l2 < 1.0E-9D ? 0.0D : Mth.clamp(((px - ax) * vx + (pz - az) * vz) / l2, 0.0D, 1.0D);
        return Math.hypot(ax + vx * t - px, az + vz * t - pz);
    }

    private double speed(ServerLevel level, StiltPlanner.Node nd) {
        long t0 = System.nanoTime();
        float c = ForestMap.of(level).clearance(Mth.floor(this.mob.getX()), Mth.floor(nd.ground()), Mth.floor(this.mob.getZ()));
        curForest += System.nanoTime() - t0;
        double o = Float.isNaN(c) ? nd.open() : Mth.clamp(c / p.openClearance, 0.0D, 1.0D);
        double attr = Mth.lerp(o, p.speedForest, p.speedOpen);
        return StiltParams.blocksPerTick(attr) * this.speedMod;
    }

    private void replan(ServerLevel level) {
        long now = level.getGameTime();
        // この tick に使える時間 = サーバー全体の予算 2 ms の残りから、歩き・足の分を引いたもの(11-11。前は 1 回 1.5 ms を予算の見積もりだけで通し、2 本重なった)
        long slice = Math.min(StiltPlanner.TIME_CAP, CoarseDetour.remaining(level) - p.planReserveNanos);
        if (slice < p.planMinSliceNanos) {
            this.nextPlan = now + 1L;
            return;
        }
        Vec3 pos = this.mob.position();
        int fx = Mth.floor(pos.x + 0.5D), fz = Mth.floor(pos.z + 0.5D);
        int gx = Mth.floor(this.goal.x + 0.5D), gz = Mth.floor(this.goal.z + 0.5D);
        boolean toApproach = false;
        if (this.search == null) {
            // 届かないと覚えた目標(近くから同じ目標へ来た個体の分も): 探さず、一番近づける所へ向かう / もう着いていれば待つ
            Unreachable.Entry u = Unreachable.get(level, gx, gz, fx, fz);
            int tx = gx, tz = gz;
            if (u != null) {
                skippedPlans++;
                this.unreachUntil = u.until();
                if (Math.abs(u.ax() - fx) <= 1 && Math.abs(u.az() - fz) <= 1) {
                    this.nextPlan = u.until();
                    return;
                }
                tx = u.ax();
                tz = u.az();
                toApproach = true;
            }
            long s0 = System.nanoTime();
            this.search = StiltPlanner.Search.start(level, p, fx, fz, groundRef(pos), tx, tz);
            long sn = System.nanoTime() - s0;   // 始めの節の調べも経路の時間に数える
            CoarseDetour.spend(level, sn);
            curPlan += sn;
            if (this.search == null) {
                this.replans++;
                this.nextPlan = now + 40L;
                if (this.replans > 5) stop();
                return;
            }
        } else {
            toApproach = this.search.tx != gx || this.search.tz != gz;
        }
        long t0 = System.nanoTime();
        StiltPlanner.Plan pl = this.search.run(slice);
        long ns = System.nanoTime() - t0;
        CoarseDetour.spend(level, ns);
        plans++;
        curPlans++;
        curPlan += ns;
        if (pl == null) {
            // 打ち切った: 続きは次の tick(開いた節を持ち越すので、やり直しにならない)
            cappedPlans++;
            curCapped++;
            this.nextPlan = now + 1L;
            return;
        }
        this.search = null;
        this.lastPlan = pl;
        if (!pl.reaches() && !toApproach) {
            // 届かない: 覚えて(近くの個体も使う)、しばらく探し直さない
            StiltPlanner.Node end = pl.nodes().isEmpty() ? null : pl.nodes().get(pl.nodes().size() - 1);
            int ax = end == null ? fx : end.x(), az = end == null ? fz : end.z();
            this.unreachUntil = now + p.unreachableTicks;
            Unreachable.put(level, gx, gz, fx, fz, ax, az, this.unreachUntil);
        }
        if (pl.nodes().isEmpty()) {
            this.replans++;
            this.nextPlan = Math.max(now + 20L, this.unreachUntil);
            if (this.replans > p.unreachableTries) stop();
            return;
        }
        // 届かない経路でも、終わりが前より目標に近づいていれば数え直す
        StiltPlanner.Node last = pl.nodes().get(pl.nodes().size() - 1);
        double d = Math.hypot(last.cx() - this.goal.x, last.cz() - this.goal.z);
        if (d < this.bestGap - 1.0D) {
            this.bestGap = d;
            this.replans = 0;
        }
        this.path = pl.nodes();
        this.pathIdx = 0;
        this.reaches = pl.reaches() && !toApproach;
        this.stall = 0;
    }

    /**
     * 届かなかった目標の覚え(11-11、サーバー全体)。鍵 = 目標の角、中身 = 探しを始めた角・一番近づける角・期限(近くから来た個体が使う)。
     * 群れが同じ逃げ場(崖の上・谷の向こう)を目指しても、出どころの区画ごとに 1 回探すだけで済む。ブロックの変化は追わない(期限で忘れる)。
     */
    static final class Unreachable {
        /** 一番近づける角 (ax, az)・探しを始めた角 (sx, sz)・期限。 */
        record Entry(int ax, int az, int sx, int sz, long until) {
        }

        /** 近くから来た = 探しを始めた角か一番近づける角から、この距離(列)以内【仮】。 */
        static final int NEAR = 24;
        private static final java.util.Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.List<Entry>>> ALL = new java.util.WeakHashMap<>();

        private static long key(int gx, int gz) {
            return ((long) gx << 32) ^ (gz & 0xffffffffL);
        }

        /**
         * 目標の角 (gx, gz) を、角 (fx, fz) の近くから届かないと覚えていれば、その覚え。前は鍵に出どころの 16 列の区画を入れていて、
         * 一番近づける所まで歩いた後は区画が変わり自分の覚えにも当たらなかった(11-11)。
         */
        @Nullable
        static synchronized Entry get(ServerLevel level, int gx, int gz, int fx, int fz) {
            var m = ALL.get(level);
            if (m == null) return null;
            java.util.List<Entry> l = m.get(key(gx, gz));
            if (l == null) return null;
            long now = level.getGameTime();
            for (Entry e : l) {
                if (e.until() <= now) continue;
                if (Math.max(Math.abs(e.sx() - fx), Math.abs(e.sz() - fz)) <= NEAR
                        || Math.max(Math.abs(e.ax() - fx), Math.abs(e.az() - fz)) <= NEAR) return e;
            }
            return null;
        }

        static synchronized void put(ServerLevel level, int gx, int gz, int fx, int fz, int ax, int az, long until) {
            var m = ALL.computeIfAbsent(level, x -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
            long now = level.getGameTime();
            if (m.size() > 256) {
                m.values().forEach(l -> l.removeIf(e -> e.until() <= now));
                m.values().removeIf(java.util.List::isEmpty);
            }
            java.util.List<Entry> l = m.computeIfAbsent(key(gx, gz), k -> new java.util.ArrayList<>());
            l.removeIf(e -> e.until() <= now || (e.sx() == fx && e.sz() == fz));
            l.add(new Entry(ax, az, fx, fz, until));
        }

        /** 試験用: 全部忘れる。 */
        static synchronized void clear() {
            ALL.clear();
        }
    }

    /** 試験用: 届かない目標の覚えを全部忘れる。 */
    public static void forgetUnreachable() {
        Unreachable.clear();
    }

    /** だいたいの地面の高さ(着いている足の平均。足がまだ無ければ胴の 5 下)。 */
    private double groundRef(Vec3 pos) {
        double s = 0.0D;
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (this.foot[i] != null) {
                s += this.foot[i].y;
                c++;
            }
        }
        return c > 0 ? s / c : pos.y - (p.preferHeight - p.bodyHalf);
    }

    /** 着いている足が全部届く、胴の箱の底の一番高い所(胴の今の横の位置で)。 */
    private double reachTop(Vec3 pos, float yaw) {
        double top = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (i == this.swingLeg || this.foot[i] == null) continue;
            Vec3 hp = hip(i, pos, yaw), f = this.foot[i];
            double h2 = (hp.x - f.x) * (hp.x - f.x) + (hp.z - f.z) * (hp.z - f.z);
            double v = Math.sqrt(Math.max(0.0D, p.legReach * p.legReach - h2));
            top = Math.min(top, f.y + p.ankleUp + v - (hp.y - pos.y));
        }
        return top;
    }

    /** 胴の箱の底の下限: 着いている足の一番高いものより、付け根が {@link StiltParams#minDrop} − 0.4 上にある。 */
    private double feetFloor() {
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (i == this.swingLeg || this.foot[i] == null) continue;
            hi = Math.max(hi, this.foot[i].y);
        }
        return hi == -Double.MAX_VALUE ? -Double.MAX_VALUE : hi + p.minDrop - 0.4D - p.bodyHalf - p.hipUp;
    }

    /** 着いている足のうち、胴が向き (ux, uz) へ進めるあと一番短い距離(ブロック、3 まで)。 */
    private double slack(Vec3 pos, float yaw, double ux, double uz) {
        double min = 3.0D;
        for (int i = 0; i < n; i++) {
            if (i == this.swingLeg || this.foot[i] == null) continue;
            double lo = 0.0D, hi = min;
            if (reachable(hip(i, pos.add(ux * hi, 0.0D, uz * hi), yaw), this.foot[i], 0.0D)) continue;
            if (!reachable(hip(i, pos, yaw), this.foot[i], 0.0D)) return 0.0D;
            for (int k = 0; k < 6; k++) {
                double mid = (lo + hi) * 0.5D;
                if (reachable(hip(i, pos.add(ux * mid, 0.0D, uz * mid), yaw), this.foot[i], 0.0D)) lo = mid;
                else hi = mid;
            }
            min = Math.min(min, lo);
        }
        return min;
    }

    /** 着いている足が全部、胴の位置 np・向き yaw の付け根から届くか(宙の足は数えない)。 */
    private boolean supported(Vec3 np, float yaw) {
        for (int i = 0; i < n; i++) {
            if (i == this.swingLeg || this.foot[i] == null) continue;
            if (!reachable(hip(i, np, yaw), this.foot[i], 0.0D)) return false;
        }
        return true;
    }

    /** 付け根 hip から置き場 f へ脚が届くか(足首までの距離。margin だけ控える)。 */
    boolean reachable(Vec3 hip, Vec3 f, double margin) {
        double drop = hip.y - f.y;
        if (drop < p.minDrop - 0.4D) return false;
        double dx = hip.x - f.x, dy = hip.y - (f.y + p.ankleUp), dz = hip.z - f.z;
        double r = p.legReach - margin;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    // ------------------------------------------------------------------ 足

    /** 湧いた・読み込まれた: 胴を立つ高さへ上げ、足を基準の置き場へそのまま置く。 */
    private void standUp(ServerLevel level) {
        Vec3 pos = this.mob.position();
        // 卵・コマンドは地面に置く(胴の箱の底 = 足元)。まわりの節の高さへ持ち上げる
        double ref = pos.y;
        StiltPlanner.Node nd = StiltPlanner.evaluate(level, p, Mth.floor(pos.x + 0.5D), Mth.floor(pos.z + 0.5D), ref);
        if (nd == null) nd = StiltPlanner.evaluate(level, p, Mth.floor(pos.x + 0.5D), Mth.floor(pos.z + 0.5D), ref - 4.0D);
        if (nd != null && nd.b() > pos.y - 2.0D) {
            this.mob.setPos(pos.x, nd.b(), pos.z);
            this.targetB = nd.b();
        } else {
            this.targetB = pos.y;
        }
        pos = this.mob.position();
        float yaw = this.mob.getYRot();
        for (int i = 0; i < n; i++) {
            Vec3 want = home(i, pos, yaw, Vec3.ZERO);
            Vec3 f = findFoot(level, i, want, pos, yaw, pos, true);
            this.foot[i] = f != null ? f : new Vec3(want.x, pos.y - (p.preferHeight - p.bodyHalf), want.z);
        }
        this.swingLeg = -1;
    }

    /** 脚 i の置きたい所(地面の高さは測らない)。v = 胴の速さ(先回り)。 */
    private Vec3 home(int i, Vec3 pos, float yawDeg, Vec3 v) {
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double l = p.homeLeft[i], f = p.homeFwd[i];
        double ax = v.x * p.lead, az = v.z * p.lead;
        double al = Math.sqrt(ax * ax + az * az);
        if (al > 2.2D) {
            ax *= 2.2D / al;
            az *= 2.2D / al;
        }
        return new Vec3(pos.x + lx * l + fx * f + ax, pos.y, pos.z + lz * l + fz * f + az);
    }

    private void tickFeet(ServerLevel level, Vec3 pos, float yaw) {
        // 宙の足を進める
        if (this.swingLeg >= 0) {
            this.swingT++;
            if (this.swingT >= this.swingLen) {
                this.foot[this.swingLeg] = this.swingTo;
                land(level, this.swingTo);
                this.swingLeg = -1;
            }
            return;
        }
        // 見ている人がいない(48 ブロック以内にプレイヤーがいない)個体は、置き場のまわりの内側 9 点だけ探す(重さのため。ファルキムネスの足の置き方と同じ考え)
        if (!nearPlayer(level)) {
            for (int i = 0; i < n; i++) {
                Vec3 w = home(i, pos, yaw, this.vel);
                if (Math.hypot(this.foot[i].x - w.x, this.foot[i].z - w.z) < p.stepAt && reachable(hip(i, pos, yaw), this.foot[i], 0.3D)) continue;
                // 内側の 9 点だけ探す(置き場の決まり = 届く・空いた所 は同じ)。踏み替えの弧はふつうに送る(遠くからも 7 ブロックの体は見える)
                Vec3 f = findFoot(level, i, w, pos, yaw, pos, true, 9);
                if (f == null) continue;
                startSwing(i, f);
                return;
            }
            return;
        }
        // ずれた脚から順に(1 tick に 2 本まで足場を探す。見つからない脚に詰まって、ほかの脚まで止めないように)
        double[] err = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 w = home(i, pos, yaw, this.vel);
            Vec3 f = this.foot[i];
            err[i] = Math.hypot(f.x - w.x, f.z - w.z);
            boolean out = !reachable(hip(i, pos, yaw), f, 0.0D);
            if (out) err[i] += 3.0D;   // もう届かない: 何より先に置き直す
            else if (!reachable(hip(i, pos, yaw), f, 0.5D)) err[i] += 1.0D;   // 届く限りに近い
            // いま下ろした脚は、よほどずれていなければ続けて上げない(置き場が無くて近くへ置いた脚が、同じ所で足踏みを続けた)。
            // 届かなくなった脚は別(家の降り口で、届かない前脚がこの決まりで置き直されずに固まった、11-10)
            if (i == this.lastLeg && !out && err[i] < p.stepAt * 3.0D) err[i] = 0.0D;
        }
        int pick = -1;
        Vec3 f = null;
        for (int tries = 0; tries < 2 && f == null; tries++) {
            pick = -1;
            double worst = p.stepAt;
            for (int i = 0; i < n; i++) {
                if (err[i] > worst) {
                    worst = err[i];
                    pick = i;
                }
            }
            if (pick < 0) return;
            // 足場を探す(下りる頃の胴の位置から届く所)
            int lenGuess = Mth.clamp((int) Math.round(3.0D + worst * 1.6D), p.swingMin, p.swingMax);
            Vec3 land = pos.add(this.vel.x * lenGuess, 0.0D, this.vel.z * lenGuess);   // 上下は足さない(上っている途中の速さで高すぎる足場を選んだ、11-10)
            f = findFoot(level, pick, home(pick, pos, yaw, this.vel), land, yaw, pos, false);
            footSearches++;
            err[pick] = 0.0D;
        }
        if (f == null) return;   // 足場が無い: 次の tick にまた探す(支えが足りなければ胴は止まる)
        startSwing(pick, f);
    }

    /** 脚 i の踏み替えを始める(f へ下りる)。長さは歩幅から。 */
    private void startSwing(int i, Vec3 f) {
        double stride = Math.hypot(f.x - this.foot[i].x, f.z - this.foot[i].z);
        this.swingLeg = i;
        this.lastLeg = i;
        this.swingT = 0;
        this.swingLen = Mth.clamp((int) Math.round(3.0D + stride * 1.6D), p.swingMin, p.swingMax);
        this.swingTo = f;
        this.seq = (this.seq + 1) & 0xFFFF;
        sync();
    }

    /** 足場を丁寧に探す、プレイヤーからの距離(ブロック)【仮】。ファルキムネスの足の置き方(48)と同じ。 */
    static final double NEAR = 48.0D;
    /** GameTest 用: 人がいなくても丁寧に探す。 */
    public static boolean alwaysNear;
    private long nearAt = -1000L;   // Long.MIN_VALUE にすると引き算があふれて「人がいない」のまま固まった(11-10)
    private boolean near;

    private boolean nearPlayer(ServerLevel level) {
        if (alwaysNear) return true;
        long t = level.getGameTime();
        if (t - this.nearAt >= 20L) {
            this.nearAt = t;
            this.near = false;
            for (net.minecraft.world.entity.player.Player pl : level.players()) {
                if (pl.distanceToSqr(this.mob) <= NEAR * NEAR) {
                    this.near = true;
                    break;
                }
            }
        }
        return this.near;
    }

    /** 置き場のまわりを外へ探す順(ブロック)。 */
    private static final double[][] RING;

    static {
        double[] rs = {0.0D, 0.6D, 1.2D, 1.8D, 2.4D};
        java.util.ArrayList<double[]> l = new java.util.ArrayList<>();
        for (double r : rs) {
            int k = r == 0.0D ? 1 : 8;
            for (int a = 0; a < k; a++) {
                double ang = a * Math.PI * 2.0D / k + (r == 1.2D ? Math.PI / 8.0D : 0.0D);
                l.add(new double[]{Math.cos(ang) * r, Math.sin(ang) * r});
            }
        }
        RING = l.toArray(new double[0][]);
    }

    /**
     * 置きたい所 {@code want} のまわりで足場を探す。足先は列の中(縁から 0.15 内)の固い上面。下りる頃の胴 {@code land} と
     * 今の胴 {@code now} の両方の付け根から届き、ほかの足から 0.9 以上離れていること。一番近い所。
     */
    @Nullable
    private Vec3 findFoot(ServerLevel level, int leg, Vec3 want, Vec3 land, float yaw, Vec3 now, boolean any) {
        return findFoot(level, leg, want, land, yaw, now, any, RING.length);
    }

    /** {@code limit} = 探す点の数(内側から)。 */
    @Nullable
    private Vec3 findFoot(ServerLevel level, int leg, Vec3 want, Vec3 land, float yaw, Vec3 now, boolean any, int limit) {
        StiltColumns cols = StiltColumns.of(level);
        this.whyNo = this.whyLand = this.whyNow = this.whyCrowd = 0;
        this.whyLeg = leg;
        Vec3 hipLand = hip(leg, land, yaw), hipNow = hip(leg, now, yaw);
        double ref = hipLand.y - (p.preferHeight - p.bodyHalf + p.hipUp);
        Vec3 bestF = null;
        double bestS = Double.MAX_VALUE;
        for (int ri = 0; ri < limit; ri++) {
            double[] o = RING[ri];
            // 内側の輪で十分近い所が見つかったら外は探さない(点数は輪の半径 + 高さの差なので、外の輪はそれより悪い)
            if (bestF != null && bestS <= Math.hypot(o[0], o[1])) break;
            double x = want.x + o[0], z = want.z + o[1];
            int bx = Mth.floor(x), bz = Mth.floor(z);
            StiltColumns.Column c = cols.at(bx, bz, ref);
            if (!c.foothold(p.maxWaterDepth)) {
                this.whyNo++;
                continue;
            }
            x = Mth.clamp(x, bx + 0.15D, bx + 0.85D);
            z = Mth.clamp(z, bz + 0.15D, bz + 0.85D);
            Vec3 f = new Vec3(x, c.surface(), z);
            if (!reachable(hipLand, f, 0.15D)) {
                this.whyLand++;
                continue;
            }
            if (!any && !reachable(hipNow, f, -0.6D)) {
                this.whyNow++;
                continue;
            }
            boolean crowded = false;
            for (int j = 0; j < n; j++) {
                if (j == leg || this.foot[j] == null) continue;
                Vec3 o2 = j == this.swingLeg ? this.swingTo : this.foot[j];
                if (Math.hypot(o2.x - f.x, o2.z - f.z) < 0.9D) {
                    crowded = true;
                    break;
                }
            }
            if (crowded) {
                this.whyCrowd++;
                continue;
            }
            double s = Math.hypot(o[0], o[1]) + 0.25D * Math.abs(f.y - (hipLand.y - (p.preferHeight - p.bodyHalf + p.hipUp)));
            if (s < bestS) {
                bestS = s;
                bestF = f;
            }
        }
        return bestF;
    }

    /** 足が下りた: そこにいる人・Mob を押しのける(ダメージ無し)。 */
    private void land(ServerLevel level, Vec3 f) {
        AABB box = new AABB(f.x - 0.55D, f.y - 0.2D, f.z - 0.55D, f.x + 0.55D, f.y + 2.0D, f.z + 0.55D);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != this.mob && e.isAlive() && !e.isSpectator())) {
            double ex = e.getX() - f.x, ez = e.getZ() - f.z;
            double d = Math.sqrt(ex * ex + ez * ez);
            if (d < 1.0E-3D) {
                double a = this.mob.getRandom().nextDouble() * Math.PI * 2.0D;
                ex = Math.cos(a);
                ez = Math.sin(a);
                d = 1.0D;
            }
            e.push(ex / d * p.footPush, 0.15D, ez / d * p.footPush);
            if (e instanceof ServerPlayer sp) sp.hurtMarked = true;
        }
    }

    // ------------------------------------------------------------------ 送る / 受ける

    private void sync() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (int i = 0; i < n; i++) {
            Vec3 f = i == this.swingLeg ? this.swingTo : this.foot[i];
            l.add(DoubleTag.valueOf(f.x));
            l.add(DoubleTag.valueOf(f.y));
            l.add(DoubleTag.valueOf(f.z));
        }
        t.put("f", l);
        t.putInt("sw", this.swingLeg);
        t.putInt("len", this.swingLen);
        t.putInt("seq", this.seq);
        this.holder.sendGait(t);
    }

    /** クライアント側の足(受けた値と、宙の弧)。 */
    public static final class Client {
        private final Vec3[] from, to;
        private final int[] start, len;
        private int lastSeq = -1;
        private boolean ready;
        private final double lift;

        public Client(int legs, double lift) {
            this.from = new Vec3[legs];
            this.to = new Vec3[legs];
            this.start = new int[legs];
            this.len = new int[legs];
            this.lift = lift;
        }

        public boolean ready() {
            return this.ready;
        }

        /** 受けた(tick = クライアントの tickCount)。 */
        public void accept(CompoundTag t, int tick, float pt) {
            ListTag l = t.getList("f", Tag.TAG_DOUBLE);
            int legs = Math.min(this.to.length, l.size() / 3);
            int sw = t.getInt("sw"), sq = t.getInt("seq");
            for (int i = 0; i < legs; i++) {
                Vec3 v = new Vec3(l.getDouble(i * 3), l.getDouble(i * 3 + 1), l.getDouble(i * 3 + 2));
                if (!this.ready || this.to[i] == null) {
                    this.from[i] = v;
                    this.to[i] = v;
                    this.len[i] = 0;
                    continue;
                }
                if (i == sw && sq != this.lastSeq) {
                    this.from[i] = foot(i, tick, pt);
                    this.to[i] = v;
                    this.start[i] = tick;
                    this.len[i] = Math.max(1, t.getInt("len"));
                } else if (this.to[i].distanceToSqr(v) > 1.0E-6D && i != sw) {
                    // 宙でない脚が動いた(置き直し・読み込み): そのまま
                    this.from[i] = v;
                    this.to[i] = v;
                    this.len[i] = 0;
                }
            }
            this.lastSeq = sq;
            this.ready = true;
        }

        /** 足 i(ワールド)。 */
        public Vec3 foot(int i, int tick, float pt) {
            if (this.len[i] <= 0) return this.to[i];
            double t = (tick - this.start[i] + pt) / this.len[i];
            if (t >= 1.0D) return this.to[i];
            if (t <= 0.0D) return this.from[i];
            Vec3 a = this.from[i], b = this.to[i];
            double s = t * t * (3.0D - 2.0D * t);
            double base = Mth.lerp(s, a.y, b.y);
            double top = Math.max(a.y, b.y) + this.lift;
            return new Vec3(Mth.lerp(s, a.x, b.x), base + (top - base) * Math.sin(Math.PI * t), Mth.lerp(s, a.z, b.z));
        }

        /** 足 i が宙にあるか。 */
        public boolean swinging(int i, int tick) {
            return this.len[i] > 0 && tick - this.start[i] < this.len[i];
        }
    }
}
