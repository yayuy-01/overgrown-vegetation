package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * ヴェロフィルの<b>ふだんの飛び回り</b>。地表から {@code velophyllCruiseAltitude}(既定 28)
 * ブロックの高さを保って流す。
 *
 * <p>行き先は<b>必ず遠くに置く</b>(最短 40 ブロック)。{@link VelophyllFlightMoveControl} は
 * 1 tick に {@code velophyllTurnRate} 度しか曲がれないので、近い点を次々に置くと
 * 「曲がりきる前に着いてしまう → また曲がる」でふらついて見える。遠くに置いて、
 * ゆっくり向きを合わせながら長い直線を飛ばせるのが飛行機らしい見え方になる。
 *
 * <p>行き先はいまの進行方向から<b>大きく外れすぎない角度</b>で選ぶ(真後ろを選ぶと
 * 大回りの U ターンに丸ごと費やしてしまう)。
 *
 * <h2>空の群れの先頭(群れ v2、2026-09-25)</h2>
 * ヴェロフィルは<b>ヴェロフィルだけの空の群れ</b>を作る(ユーザー指定 2026-09-25。地上の群れとは別)。
 * 群れの<b>先頭</b>(司令塔が選んだ先導役)はこの Goal で飛び、行き先は<b>司令塔が決める</b>:
 * 狩りなら群れの標的の上空(標的が先頭の手の届く 64 ブロックに入れば司令塔が {@code setTarget} し、爆撃・噛みつきの Goal が
 * 代わる)、暇なら {@link Swarm#destination}(過繁茂バイオームの外へ向かう点・外ではぶらつく先)。行き先の高さだけはここで
 * 巡航高度に直す(司令塔の行き先は先頭のいまの高さのままなので)。{@value #LEAD_REFRESH} 回(= 10 tick)ごとに置き直す。
 * 着いたかどうかは司令塔が決める(この Goal では選び直さない。空の群れは行き先を通り過ぎても着いたとみなす —
 * {@code SwarmController#airReached})。群れが汚染を広げている間に飛んでいる先頭は、汚染の中心の上空を回って待つ。
 *
 * <p>先頭は<b>巡航より少し遅く</b>飛ぶ({@value #LEADER_SPEED_FRACTION} 倍。仮決め 2026-09-25)。後続は速さで前後のずれを
 * 直す({@link SwarmAirFollowGoal})ので、先頭が巡航いっぱいで飛ぶと遅れた後続が二度と追いつけない。
 * さらに、標的を追っていない・地上にいない後続が {@value #LAGGARD_DISTANCE} ブロックより離れている間は
 * {@value #LEADER_WAIT_SPEED_FRACTION} 倍まで落として待つ(ユーザー指定「先導役は飛び出さず遅れを待つ」の空の版。
 * 待つのは地上と同じ {@code waitCapTicks}(300)まで。数値は仮決め 2026-09-25)。
 *
 * <p>後続はこの Goal を使わない({@link SwarmAirFollowGoal})。群れに入っていても先頭がまだ決まっていない間(湧いた直後・
 * 組み直した直後)は、いまの向きのまままっすぐ飛ぶ(仮決め 2026-09-25。{@link #canUse} の説明)。群れに居ない個体は
 * 今までどおり好きに流す。旧い「地上の群れの重心から {@code velophyllPackLeash} の内側だけを飛ぶ」
 * (2026-09-15)は群れ v2 で廃止した(空の群れは地上の群れと別に動く)。
 *
 * <h2>読み込まれていない列を見ない(2026-09-25)</h2>
 * 巡航高度を決める地表の高さは、<b>読み込まれていないチャンクだと {@code Level#getHeight} が最低の高さ(-64)を返す</b>。
 * 旧い実装は 40〜90 ブロック先の列をそのまま読んでいたので、シミュレーション距離の縁では「地下 36 ブロック」を行き先にして
 * 地面へ突っ込むことがあった。{@link #canopyAt} は {@code getChunkNow} で読み込み済みの列だけを見る。
 */
public class VelophyllWanderGoal extends Goal {

    /** 行き先を選ぶ水平距離(ブロック)。近い点は置かない。 */
    private static final double MIN_RANGE = 40.0D;
    private static final double MAX_RANGE = 90.0D;
    /** いまの進行方向から左右どこまで曲げた先を選んでよいか(度)。 */
    private static final float MAX_HEADING_CHANGE = 100.0F;
    /** 行き先を選び直すまで粘る上限(tick)。 */
    private static final int MAX_TICKS = 600;
    /** 行き先に着いたとみなす水平距離(ブロック)。群れに居ない個体だけ(群れの先頭の到着は司令塔が決める)。 */
    private static final double ARRIVE_DISTANCE = 12.0D;
    /**
     * 群れの先頭が行き先と速さを置き直す間隔(この Goal の tick 数)。この Goal は毎 tick 更新ではないので
     * 2 game tick に 1 回動く = 10 game tick(司令塔が考える間隔と同じ)。
     */
    private static final int LEAD_REFRESH = 5;
    /** 先頭の速さの指示の期限(game tick)。置き直す間隔(10)より長く、止まったあとは早めに切れる長さ。 */
    private static final int LEAD_PACE_TTL = 30;
    /** 先頭の速さ(巡航に対する割合)。後続が追いつく余裕。仮決め 2026-09-25。 */
    static final double LEADER_SPEED_FRACTION = 0.85D;
    /** 遅れた後続を待つ間の先頭の速さ(巡航に対する割合、最低速度より上)。仮決め 2026-09-25。 */
    static final double LEADER_WAIT_SPEED_FRACTION = 0.6D;
    /** 先頭からこれより離れた後続を「遅れている」とみなす(ブロック、3D)。仮決め 2026-09-25。 */
    static final double LAGGARD_DISTANCE = 48.0D;

    private final VelophyllEntity mob;
    private Vec3 destination;
    private int ticks;
    /** 空の群れの先頭として飛んでいるか(canUse で決める)。 */
    private boolean leading;
    /** 巡航高度のばらつき(ブロック、-5〜+5)。先頭は行き先を置き直しても変えない(置き直すたびに上下へ揺れないように)。 */
    private int altitudeJitter;
    /** 遅れた後続を待っている game tick(待つ上限 {@code waitCapTicks} まで。全員追いつけば 0)。 */
    private int waitTicks;

    public VelophyllWanderGoal(VelophyllEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isGrounded() || this.mob.hasAttackTarget()) {
            return false;
        }
        // 飛び立つモーションの間は始めない(終わったら本体が滑空へ戻す)。地上の胞子撒きが終わった
        // 同じ tick にここが始まると、start() の滑空で飛び立ちを上書きして、飛び立つモーションが一度も見えなかった。
        if (this.mob.getActionState() == VelophyllEntity.STATE_TAKEOFF) {
            return false;
        }
        this.altitudeJitter = this.mob.getRandom().nextInt(11) - 5;
        Swarm swarm = SwarmManager.get(this.mob);
        if (swarm != null && swarm.isVanguard(this.mob)) {
            // 空の群れの先頭。行き先は司令塔から(まだ決まっていなければ、決まるまで好きに流す)。
            this.leading = true;
            Vec3 lead = leadDestination(swarm);
            this.destination = lead != null ? lead : pickDestination(MAX_HEADING_CHANGE);
            return this.destination != null;
        }
        this.leading = false;
        if (swarm != null && swarm.vanguard() != null) {
            // 後続は SwarmAirFollowGoal が飛ばす。
            return false;
        }
        if (swarm != null) {
            // 群れに入っていて先頭がまだ決まっていない(湧いた直後・先頭が倒れて組み直す間。司令塔が次に考えるまでの 10 tick ほど)。
            // 好きな向きへ流さず、いまの向きのまま同じ高さでまっすぐ飛ぶ(仮決め 2026-09-25)。
            // 理由(GameTest airFollowersStayNearTheLeader が 7 回に 1 回ほど落ちて分かった): この機体は目一杯曲がっている tick は
            // 加速せず最低速度へ落ちる(VelophyllFlightMoveControl)。先頭が決まるまでに後続だけが 30 度ほど曲がると、その間の加速を
            // 失ったまま、先頭と同じ加速(0.03/tick)で巡航へ上げていくので、速さの差が先頭の頭打ち(巡航の 0.85 倍)まで 50 tick ほど
            // 縮まらず、後ろへ 25 ブロック離れた。「後続は自分でうろつかない・編成は湧いた時点で揃っている」(ユーザー指定 2026-09-25)
            // にも合う。前が読み込まれていなければ今までどおり左右へ振った先を選ぶ。
            this.altitudeJitter = 0;
            this.destination = pickDestination(0.0F);
            if (this.destination == null) {
                this.destination = pickDestination(MAX_HEADING_CHANGE);
            }
            return this.destination != null;
        }
        this.destination = pickDestination(MAX_HEADING_CHANGE);
        return this.destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.destination == null || this.mob.isGrounded() || this.mob.hasAttackTarget()) {
            return false;
        }
        Swarm swarm = SwarmManager.get(this.mob);
        if (this.leading) {
            // 先頭から降ろされたら止まる(後続の Goal へ)。行き先は tick で司令塔から置き直す。
            return swarm != null && swarm.isVanguard(this.mob);
        }
        if (swarm != null && swarm.vanguard() != null) {
            // 群れの先頭が決まった(後続になった / 先頭に選ばれた)。先頭として canUse からやり直す。
            return false;
        }
        return this.ticks < MAX_TICKS && horizontalDistanceTo(this.destination) > ARRIVE_DISTANCE;
    }

    @Override
    public void start() {
        this.ticks = 0;
        this.waitTicks = 0;
        this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        this.mob.setMoveTargetPoint(this.destination);
        if (this.leading) {
            Swarm swarm = SwarmManager.get(this.mob);
            if (swarm != null) {
                applyLeaderPace(swarm);
            }
        }
    }

    @Override
    public void stop() {
        this.destination = null;
        if (this.leading) {
            this.mob.flightControl().clearFormationSpeed();
        }
        this.leading = false;
    }

    @Override
    public void tick() {
        this.ticks++;
        if (!this.leading || this.ticks % LEAD_REFRESH != 0) {
            return;
        }
        // Swarm は持ち続けない(合流・組み直しで別の物になる)。毎回引き直す。
        Swarm swarm = SwarmManager.get(this.mob);
        if (swarm == null || !swarm.isVanguard(this.mob)) {
            return;
        }
        Vec3 next = leadDestination(swarm);
        if (next == null && horizontalDistanceTo(this.destination) <= ARRIVE_DISTANCE) {
            // 司令塔の行き先がまだ無いまま、仮の行き先に着いた。次の仮の行き先へ(その場を小さく回らないように)。
            next = pickDestination(MAX_HEADING_CHANGE);
        }
        if (next != null) {
            this.destination = next;
            this.mob.setMoveTargetPoint(next);
        }
        applyLeaderPace(swarm);
    }

    /**
     * 群れの先頭の行き先(巡航高度に直したもの)。汚染を広げている間は汚染の中心の上空を回る点({@link #orbitAround})、
     * それ以外は司令塔の {@link Swarm#leadGoal}(狩り → 標的、暇 → 行き先)、それも無ければ {@link Swarm#destination}。
     * どれも無ければ null。
     *
     * <p>高さは「行き先の列」と「自分の真下の列」の高いほうの天蓋 + 巡航高度 —— 行き先が遠い(外へ向かう点は 192 ブロック先まで)
     * ので、行き先の列だけを見ると途中の丘や巨大樹へ突っ込む。10 tick ごとに置き直すので、越えたら下がる。
     */
    @Nullable
    private Vec3 leadDestination(Swarm swarm) {
        BlockPos centre = swarm.spreadCentre();
        if (centre != null) {
            // 汚染を広げている間、飛んでいる先頭(降り終えた・降りられなかった)は汚染の中心の上空を回って待つ
            // (後続が地上の先頭のまわりを回るのと同じ回り方)。はじめは暇なときの行き先へ飛んで行き、撒き終えた後続を連れて
            // 群れを散らしていた(レビュー指摘 2026-09-25)。
            return orbitAround(centre);
        }
        Vec3 goal = swarm.leadGoal();
        if (goal == null) {
            goal = swarm.destination();
        }
        if (goal == null) {
            return null;
        }
        Integer atGoal = canopyAt(this.mob.level(), goal.x, goal.z);
        Integer here = canopyAt(this.mob.level(), this.mob.getX(), this.mob.getZ());
        double base;
        if (atGoal != null && here != null) {
            base = Math.max(atGoal, here);
        } else if (atGoal != null || here != null) {
            base = atGoal != null ? atGoal : here;
        } else {
            return new Vec3(goal.x, this.mob.getY(), goal.z);
        }
        int cruise = OvergrownVegetationConfig.COMMON.velophyllCruiseAltitude.get();
        double y = Math.min(base + cruise + this.altitudeJitter, this.mob.level().getMaxBuildHeight() - 3);
        return new Vec3(goal.x, y, goal.z);
    }

    /**
     * {@code centre} の上空を回る点。半径は爆撃の待ちと同じ {@code velophyllCircleRadius}、高さは中心の列の天蓋 + 巡航高度。
     * 円周上の {@value #ORBIT_LEAD_DEGREES} 度先を指す({@link SwarmAirFollowGoal} の地上の先頭のまわりを回る点と同じ考え方)。
     */
    private Vec3 orbitAround(BlockPos centre) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double radius = cfg.velophyllCircleRadius.get();
        double cx = centre.getX() + 0.5D;
        double cz = centre.getZ() + 0.5D;
        double here = Math.atan2(this.mob.getZ() - cz, this.mob.getX() - cx);
        double direction = (this.mob.getId() & 1) == 0 ? 1.0D : -1.0D;
        double ahead = here + direction * Math.toRadians(ORBIT_LEAD_DEGREES);
        Integer canopy = canopyAt(this.mob.level(), cx, cz);
        double base = canopy != null ? canopy : centre.getY();
        double y = Math.min(base + cfg.velophyllCruiseAltitude.get() + this.altitudeJitter,
                this.mob.level().getMaxBuildHeight() - 3);
        return new Vec3(cx + Math.cos(ahead) * radius, y, cz + Math.sin(ahead) * radius);
    }

    /** 汚染の中心の上空を回るとき、円周上のどれだけ先を指すか(度)。{@link SwarmAirFollowGoal} と同じ。 */
    private static final double ORBIT_LEAD_DEGREES = 55.0D;

    /**
     * 先頭の速さを決める(上の「空の群れの先頭」参照)。後続が 1 体もいなければ指示を消して巡航で飛ぶ。
     * 待つ数えは置き直すたび(10 tick)に進め、遅れがいなくなったときだけ 0 に戻す(地上の先導役と同じ)。
     */
    private void applyLeaderPace(Swarm swarm) {
        VelophyllFlightMoveControl flight = this.mob.flightControl();
        boolean anyFollower = false;
        boolean lagging = false;
        double lagSq = LAGGARD_DISTANCE * LAGGARD_DISTANCE;
        for (Mob member : swarm.activeMembers()) {
            if (member == this.mob) {
                continue;
            }
            anyFollower = true;
            // 標的を追っている・地上にいる後続は待たない(待っても来ない)。
            if (swarm.handedTarget(member) != null
                    || (member instanceof VelophyllEntity velophyll && velophyll.isGrounded())) {
                continue;
            }
            if (member.distanceToSqr(this.mob) > lagSq) {
                lagging = true;
            }
        }
        if (!anyFollower) {
            flight.clearFormationSpeed();
            this.waitTicks = 0;
            return;
        }
        boolean wait = false;
        if (lagging) {
            this.waitTicks += LEAD_REFRESH * 2;
            wait = this.waitTicks <= DesignData.swarm().waitCapTicks();
        } else {
            this.waitTicks = 0;
        }
        double cruise = OvergrownVegetationConfig.COMMON.velophyllCruiseSpeed.get();
        flight.setFormationSpeed(cruise * (wait ? LEADER_WAIT_SPEED_FRACTION : LEADER_SPEED_FRACTION), LEAD_PACE_TTL);
    }

    private double horizontalDistanceTo(Vec3 point) {
        return Math.hypot(this.mob.getX() - point.x, this.mob.getZ() - point.z);
    }

    /**
     * 巡航高度にある、遠くて・進行方向から外れすぎない点(群れに居ない個体の飛び回り)。
     * 8 回選んで 1 つも使えなければ(読み込まれていない列・高すぎる)null。
     *
     * @param maxHeadingChange いまの進行方向から左右どこまで曲げてよいか(度)。0 ならまっすぐ前(距離だけ選ぶ)
     */
    @Nullable
    private Vec3 pickDestination(float maxHeadingChange) {
        // yRot は「+Z が 0 度」。進行方向のコンパス角へ直してから左右へ振る。
        float heading = this.mob.getYRot() + 90.0F;
        for (int attempt = 0; attempt < 8; attempt++) {
            float offset = (this.mob.getRandom().nextFloat() * 2.0F - 1.0F) * maxHeadingChange;
            double angle = (heading + offset) * Mth.DEG_TO_RAD;
            double distance = Mth.lerp(this.mob.getRandom().nextDouble(), MIN_RANGE, MAX_RANGE);
            double x = this.mob.getX() + Math.cos(angle) * distance;
            double z = this.mob.getZ() + Math.sin(angle) * distance;
            Vec3 destination = atCruiseAltitude(x, z);
            if (destination != null) {
                return destination;
            }
        }
        return null;
    }

    /**
     * その水平位置の<b>巡航高度</b>。高さの基準は<b>葉を含む</b> MOTION_BLOCKING —
     * 地面基準(NO_LEAVES)にすると、巨大樹や森の上では巡航高度が枝の中に入ってしまう
     * (2026-09-14「木につっかえる」)。少しばらけさせるのは、目標より上から入ると
     * 降りながら進む区間(= 滑空の姿勢)ができるため。読み込まれていない列は null(上の説明)。
     */
    @Nullable
    private Vec3 atCruiseAltitude(double x, double z) {
        Integer canopy = canopyAt(this.mob.level(), x, z);
        if (canopy == null) {
            return null;
        }
        int cruise = OvergrownVegetationConfig.COMMON.velophyllCruiseAltitude.get();
        double y = canopy + cruise + this.altitudeJitter;
        return y >= this.mob.level().getMaxBuildHeight() - 2 ? null : new Vec3(x, y, z);
    }

    /**
     * その列の<b>葉を含む</b>地表の高さ({@code Level#getHeight(MOTION_BLOCKING)} と同じ値 = いちばん上のブロックの 1 つ上)。
     * サーバーでは読み込み済みのチャンク({@code getChunkNow})だけを見て、読み込まれていなければ null
     * ({@code Level#getHeight} は読み込まれていない列で最低の高さを返し、巡航高度が地下になる)。
     */
    @Nullable
    static Integer canopyAt(Level level, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            if (chunk == null) {
                return null;
            }
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
    }
}
