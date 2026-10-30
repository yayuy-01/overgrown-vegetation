package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmAttacker;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSiegeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * TD の<b>護衛</b>(G5〜G9 の個体。群れに入らない種)がターゲットブロックを攻める Goal(2026-09-25、ユーザー指定
 * 「TD で G4〜9 も湧くように。TD が起きたフェーズで湧く mob のみ」)。
 *
 * <p>護衛は群れに入らず自分の AI で動く(G5 は対人特化 —— プレイヤー・防衛側を見つければ自分の狩りの Goal が先に動く)。
 * この Goal は<b>標的がいない間だけ</b>ターゲットブロックへ歩き、手が届けば {@value #STRIKE_INTERVAL_TICKS} tick ごとに攻撃力ぶん
 * 耐久を削る({@link TdSiege#strikeIndividual})。進めなければ、ターゲットへの線にある壁を<b>自分の体の大きさの穴</b>で崩す
 * ({@link TdSiege#digHole} / {@link TdSiege#strikeWall}。ユーザー指定 2026-09-25「TD 時のみ、すべての mob がブロックを破壊できるように。
 * アルボラ以外はアルボラよりも 5 倍の時間がかかるが、体格の大きな mob は自身が通れるサイズの大きな穴をあける」。それまでは壁を壊さなかった)。
 *
 * <p>入れ方: 侵攻の印を持つ個体(群れに入らない種と、地上の群れの種)が読み込まれたとき、{@code TdTicker#onJoin} が優先度
 * {@value #PRIORITY} で差し込む(Goal は保存されないので、読み込まれるたびに入れ直す。同じ個体へ 2 つは入れない)。優先度は G5 の狩り(1)・
 * G4 の近接(4)より後ろ、散歩(6〜7)より前。<b>群れの一員の間は動かない</b>(群れの所属がある間)。付いて来られずに群れから外れた一員
 * ({@code SwarmPaths#noteRecoveryOutcome}、2026-09-25)が自分でターゲットへ向かうのに使う。侵攻が終われば印が外れて止まる。
 *
 * <p>経路は {@value #REPATH_TICKS} tick ごと(個体ごとにずらす)にだけ引き直す(護衛は群れより少ない。仮決め 2026-09-25)。
 * 遠ければターゲットへの直線上 {@value #WAYPOINT_STEP} ブロック先の地面(木を掘り抜いた高さ)へ。引き直すたびにターゲットへ
 * {@value #PROGRESS} ブロック以上近づけていなければ、次の行き先を左右へ 45 度・90 度と振って回り道を探す。ターゲットを見るのは
 * {@value #LOOK_RANGE} ブロックより近いときだけ。
 *
 * <p>2026-09-25(ユーザー報告「TD 中にターゲットへ行かず、湧いた場所でずっとくるくるしてスタック」の調べ): はじめは遠くても
 * ターゲットそのものへ {@code moveTo} し、毎 tick ターゲットを見ていた。森では体の太い護衛(幅 3 近い)の部分経路が 0 ブロック先で
 * 終わり続け、ファルキムネス・スクティムネスの視線(体ごと回る)がターゲットの方へ体を回し続けて、その場で足踏みしながら回っていた。
 */
public final class TdAssaultGoal extends Goal {

    /** 差し込む優先度。 */
    public static final int PRIORITY = 5;
    /** 叩く間隔(tick)。群れの前衛と同じ。 */
    static final int STRIKE_INTERVAL_TICKS = 20;
    /** 経路を引き直す間隔(tick)。仮決め 2026-09-25。 */
    private static final int REPATH_TICKS = 60;
    /** 歩く速さの倍率。 */
    private static final double SPEED = 1.0D;
    /** これより遠ければ、ターゲットへの直線上のこの距離先を行き先にする(ブロック、水平)。群れの区間と同じ 24。仮決め 2026-09-25。 */
    private static final double WAYPOINT_STEP = 24.0D;
    /** ターゲットを見るのはこれより近いときだけ(ブロック、水平)。仮決め 2026-09-25。 */
    private static final double LOOK_RANGE = 12.0D;
    /** 引き直すたびにターゲットへこれだけ近づけていなければ「進めていない」(ブロック)。仮決め 2026-09-25。 */
    private static final double PROGRESS = 2.0D;
    /** 進めていない回数ごとに行き先を振る角度(度)。0 → 右 45 → 左 45 → 右 90 → 左 90 → ……。 */
    private static final float[] DETOURS = {0.0F, 45.0F, -45.0F, 90.0F, -90.0F};
    /** 崩す塊へ寄っても手が届かないまま、これだけ経ったら崩すのをやめる(tick)。仮決め 2026-09-25。 */
    private static final int DIG_REACH_TIMEOUT = 100;
    /** 崩す塊がこれより近ければ(水平、ブロック)、経路を引かずにまっすぐ寄る。 */
    private static final double DIG_STEER_DISTANCE = 5.0D;
    /** 止まってからこれ以内に動き出したら、進み具合と崩しかけの穴を続きから使う(tick)。仮決め 2026-09-25。 */
    private static final int RESUME_TICKS = 200;

    private final PathfinderMob mob;
    private int repathAt;
    private int strikeCooldown;
    /** 前に引き直したときのターゲットまでの水平距離。 */
    private double lastDistance = Double.MAX_VALUE;
    /** 続けて進めていない回数(行き先を振る)。 */
    private int stalls;
    /** いま崩している穴の断面の塊({@link TdSiege#digHole})。空なら崩していない。 */
    private final List<BlockPos> dig = new ArrayList<>();
    /** 崩す塊に手が届かないまま経った tick。 */
    private int digReachTicks;
    /** 次に壁を叩いてよいまでの tick。 */
    private int digCooldown;
    /** 最後に止まった tickCount({@link #RESUME_TICKS} 以内に動き出したら続きから)。 */
    private int stoppedAt = Integer.MIN_VALUE / 2;

    public TdAssaultGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** まだ入っていなければ {@code mob} に差し込む。 */
    public static void install(PathfinderMob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof TdAssaultGoal) {
                return;
            }
        }
        mob.goalSelector.addGoal(PRIORITY, new TdAssaultGoal(mob));
    }

    @Override
    public boolean canUse() {
        return target() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target() != null;
    }

    /** 攻める塊。標的がいる・群れの一員・侵攻中でない・この侵攻の印が無いなら null。 */
    @Nullable
    private BlockPos target() {
        LivingEntity enemy = this.mob.getTarget();
        if (enemy != null && enemy.isAlive()) {
            return null;
        }
        if (this.mob.getExistingData(ModAttachments.SWARM).map(m -> !m.isNone()).orElse(false)) {
            return null; // 群れにいる間は群れの Goal が動かす
        }
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        String eventId = TdEvents.activeEventId(level);
        BlockPos target = TdEvents.activeTarget(level);
        if (eventId == null || target == null || !TdEvents.isSiegeLive(level, target)
                || !EventMobs.has(this.mob, EventMobs.Kind.TD, eventId)) {
            return null;
        }
        return target;
    }

    @Override
    public void start() {
        this.repathAt = 0;
        this.strikeCooldown = Math.floorMod(this.mob.getId(), STRIKE_INTERVAL_TICKS);
        if (this.mob.tickCount - this.stoppedAt > RESUME_TICKS) {
            // 久しぶりに動き出した。進み具合と崩しかけの穴は忘れる。短い割り込み(G5 の待機の仕草・盾の上げ下げ)のあとは続きから
            // (2026-09-25: 毎回忘れていたので、壁の前で待機の仕草が割り込むたびに数え直し、いつまでも崩し始めなかった)。
            this.lastDistance = Double.MAX_VALUE;
            this.stalls = 0;
            this.dig.clear();
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.stoppedAt = this.mob.tickCount;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos target = target();
        if (target == null) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        double dx = centre.x - this.mob.getX();
        double dz = centre.z - this.mob.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        boolean reach = Swarm.canReachBlock(this.mob, target);
        if (reach || distance <= LOOK_RANGE) {
            this.mob.getLookControl().setLookAt(centre.x, centre.y, centre.z, 30.0F, 30.0F);
        }
        if (reach) {
            if (!this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().stop();
            }
            if (--this.strikeCooldown > 0) {
                return;
            }
            this.strikeCooldown = STRIKE_INTERVAL_TICKS;
            if (TdSiege.strikeIndividual(this.mob, target)) {
                this.mob.swing(InteractionHand.MAIN_HAND);
                if (this.mob instanceof SwarmAttacker attacker) {
                    attacker.playAttackAnimation();
                }
            }
            return;
        }
        if (tickDig()) {
            return;
        }
        // 間隔だけで引き直す。はじめは「経路が終わったら」でも引き直していたが、壁の前で途切れた経路(拠点を攻めるときのふつうの形)は
        // 終わったまま同じ経路が返り(バニラの moveTo は同じ経路なら入れ替えない)、毎 tick 探索が走っていた(レビュー指摘 2026-09-25)。
        if (this.mob.tickCount >= this.repathAt) {
            this.repathAt = this.mob.tickCount + REPATH_TICKS + Math.floorMod(this.mob.getId(), 20);
            if (distance > this.lastDistance - PROGRESS) {
                this.stalls++;
            } else {
                this.stalls = 0;
            }
            this.lastDistance = distance;
            if (this.stalls > 0 && (crossBridge(target) || startDig(target))) {
                return;
            }
            Vec3 dest = destination(centre, target, dx, dz, distance);
            this.mob.getNavigation().moveTo(dest.x, dest.y, dest.z, SPEED);
        }
    }

    /**
     * 進めていないとき、近くの橋(崖・溝に架けた・架けている途中の橋。{@link TdBridges#crossingFor})を使う。架けている途中なら
     * 始まりで待ち、できていれば降り口へ向かう。近くに無ければ前の崖・溝に橋を頼む(架けるのは近くの群れの外のアルボラ)。
     * 2026-09-25 のユーザー報告「崖などがあるとアルボラ以外が立ち往生する。G5 なども通れる橋を」。崖を横へ掘っても上には出られない。
     *
     * @return 橋を使っている(壁は崩さない)
     */
    private boolean crossBridge(BlockPos target) {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }
        TdBridges.Crossing crossing = TdBridges.crossingFor(level, this.mob, target, level.getGameTime());
        if (crossing == null) {
            return false;
        }
        Vec3 at = Vec3.atBottomCenterOf(crossing.ready() ? crossing.landing() : crossing.start());
        if (!crossing.ready() && this.mob.distanceToSqr(at) <= 9.0D) {
            this.mob.getNavigation().stop(); // 橋の始まりで待つ
        } else {
            this.mob.getNavigation().moveTo(at.x, at.y, at.z, SPEED);
        }
        return true;
    }

    /**
     * 進めていないとき、ターゲットへの線にある壁を自分の体の大きさの穴で崩し始める({@link TdSiege#digHole}。ユーザー指定 2026-09-25
     * 「TD 時のみ、すべての mob がブロックを破壊できる。体格の大きな mob は自身が通れるサイズの大きな穴をあける」)。
     *
     * @return 崩し始めた
     */
    private boolean startDig(BlockPos target) {
        TdSiege.Hole hole = TdSiege.digHole(this.mob, target);
        if (hole == null || hole.cells().isEmpty()) {
            return false;
        }
        this.dig.clear();
        this.dig.addAll(hole.cells());
        this.digReachTicks = 0;
        this.mob.getNavigation().stop();
        return true;
    }

    /**
     * 穴を崩している間の 1 tick。手の届くいちばん近い塊を {@link SwarmSiegeHooks#STRIKE_INTERVAL_TICKS} tick に 1 回叩き
     * ({@link TdSiege#strikeWall}。アルボラ以外はアルボラの 5 倍の時間)、届かなければ寄る。{@value #DIG_REACH_TIMEOUT} tick 寄っても
     * 届かなければやめる。崩し終えたらすぐ引き直す。
     *
     * @return 崩している(この tick はほかに何もしない)
     */
    private boolean tickDig() {
        if (this.dig.isEmpty()) {
            return false;
        }
        this.dig.removeIf(pos -> !TdSiege.isWall(this.mob, pos));
        if (this.dig.isEmpty()) {
            // 崩し終えた(または誰かが崩した)。すぐ引き直す。
            this.repathAt = 0;
            this.stalls = 0;
            this.lastDistance = Double.MAX_VALUE;
            return false;
        }
        BlockPos cell = null;
        boolean inReach = false;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : this.dig) {
            boolean reach = Swarm.canReachBlock(this.mob, pos);
            double d = this.mob.distanceToSqr(Vec3.atCenterOf(pos));
            if ((reach && !inReach) || (reach == inReach && d < bestSq)) {
                cell = pos;
                inReach = reach;
                bestSq = d;
            }
        }
        Vec3 centre = Vec3.atCenterOf(cell);
        this.mob.getLookControl().setLookAt(centre.x, centre.y, centre.z, 30.0F, 30.0F);
        if (!inReach) {
            if (++this.digReachTicks > DIG_REACH_TIMEOUT) {
                this.dig.clear(); // 届かない(穴の底・崖の上)。引き直しに戻る(次に進めなければまた探す)
                return false;
            }
            double hx = centre.x - this.mob.getX();
            double hz = centre.z - this.mob.getZ();
            if (hx * hx + hz * hz <= DIG_STEER_DISTANCE * DIG_STEER_DISTANCE) {
                // 近ければ塊へまっすぐ寄る(壁に体が当たれば手が届く)。経路は塞がれたマスへは引けず、手前の足場で止まったまま
                // 届かないことがある(2026-09-25 の GameTest escortDigsThroughWall)。
                if (!this.mob.getNavigation().isDone()) {
                    this.mob.getNavigation().stop();
                }
                this.mob.getMoveControl().setWantedPosition(centre.x, this.mob.getY(), centre.z, SPEED);
            } else if (this.mob.getNavigation().isDone() || this.digReachTicks % 20 == 0) {
                this.mob.getNavigation().moveTo(centre.x, cell.getY(), centre.z, SPEED);
            }
            return true;
        }
        this.digReachTicks = 0;
        if (!this.mob.getNavigation().isDone()) {
            this.mob.getNavigation().stop();
        }
        if (--this.digCooldown > 0) {
            return true;
        }
        this.digCooldown = SwarmSiegeHooks.STRIKE_INTERVAL_TICKS;
        TdSiege.WallStrike result = TdSiege.strikeWall(this.mob, cell);
        if (result != TdSiege.WallStrike.INVALID) {
            this.mob.swing(InteractionHand.MAIN_HAND);
            if (this.mob instanceof SwarmAttacker attacker) {
                attacker.playAttackAnimation();
            }
        }
        if (result != TdSiege.WallStrike.DAMAGED) {
            this.dig.remove(cell);
        }
        return true;
    }

    /**
     * 次の行き先。進めていて近ければターゲット。遠い・進めていないときは、ターゲットへの向きを {@link #DETOURS} で振った直線上
     * {@value #WAYPOINT_STEP} ブロック先(近ければその距離)の、木を掘り抜いた地面(読み込まれていなければ今の高さ)。
     */
    private Vec3 destination(Vec3 centre, BlockPos target, double dx, double dz, double distance) {
        float turn = this.stalls <= 0 ? 0.0F : DETOURS[this.stalls % DETOURS.length];
        if (turn == 0.0F && distance <= WAYPOINT_STEP) {
            return new Vec3(centre.x, target.getY(), centre.z);
        }
        if (distance < 1.0E-3D) {
            return new Vec3(centre.x, target.getY(), centre.z);
        }
        double rad = Math.toRadians(turn);
        double ux = dx / distance;
        double uz = dz / distance;
        double rx = ux * Math.cos(rad) - uz * Math.sin(rad);
        double rz = ux * Math.sin(rad) + uz * Math.cos(rad);
        double step = Math.min(WAYPOINT_STEP, Math.max(distance, 8.0D));
        double x = this.mob.getX() + rx * step;
        double z = this.mob.getZ() + rz * step;
        double y = this.mob.getY();
        if (this.mob.level() instanceof ServerLevel level) {
            // 水たまりの底を狙わない(手前の乾いた地面へ戻す。2026-09-25、湿地で水へ入り直し続けていた)。
            BlockPos ground = TreeCanopyGroundFinder.findDrySurfaceToward(level, Mth.floor(x), Mth.floor(z),
                    this.mob.getX(), this.mob.getZ(), 8);
            if (ground != null) {
                if (ground.getX() != Mth.floor(x) || ground.getZ() != Mth.floor(z)) {
                    x = ground.getX() + 0.5D;
                    z = ground.getZ() + 0.5D;
                }
                y = ground.getY();
            }
        }
        return new Vec3(x, y, z);
    }

    /** 調べもの用(GameTest の失敗の文言): 進めていない回数・崩している塊・次に引き直す tick。 */
    public String debugState() {
        return "stalls=" + this.stalls + " dig=" + this.dig + " repathAt=" + this.repathAt + " reachTicks=" + this.digReachTicks;
    }

    /** 1 回の削りの量 = 攻撃力の属性(持たない種は 0)× ワールドの難易度「敵の強さ」(2026-09-25)。 */
    static float strikeAmount(Mob mob) {
        return com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.scaleEnemyStrike(mob,
                mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                        ? (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE) : 0.0F);
    }
}
