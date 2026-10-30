package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * <b>「フィロセンスの誘導」を受けた Mob が、「視線」を持つプレイヤーへ向かう</b> Goal
 * (ユーザー要望 2026-09-14)。グループ1〜10 のすべての {@link PathfinderMob} に仕込まれる
 * ({@link GuidedHuntInjector})。
 *
 * <p>条件は 2 つだけ —— <b>自分に誘導が付いている</b>ことと、
 * <b>同じワールドに視線持ちのプレイヤーが居る</b>こと。
 * 距離・視界・壁はいっさい問わない({@link PhyllosensInfluence#nearestWatchedPlayer})。
 * これが「フィロセンスに教われば、どこに居ても襲いに来る」の本体。
 *
 * <h2>なぜ「索敵 Goal」ではなく「移動 Goal」なのか</h2>
 * <p>300 ブロック先のプレイヤーに {@code mob.setTarget()} をしても、バニラの
 * {@code TargetGoal#canContinueToUse} が「{@code FOLLOW_RANGE} より遠い標的」を次の tick で
 * 外してしまうので狙いは一瞬で消える。そこでこの Goal は<b>自分で歩かせる</b>役に徹し、
 * {@link #ENGAGE_DISTANCE} まで近づいたところで {@code setTarget} を呼んで
 * 通常の戦闘 Goal へ引き継ぐ。
 *
 * <p><b>視線持ちのプレイヤーが最優先</b>(ユーザー選択 2026-09-14)。優先度を通常の追跡より
 * 上に入れてあるので、村人や動物を追っている最中でもそちらへ切り替える。
 * 視線持ちが居なければこの Goal は動かず、今までどおりの行動に戻る。
 *
 * <h2>合流のしかた(ユーザー要望)</h2>
 * <ol>
 *     <li>周囲 {@link #RALLY_SCAN} ブロックの味方(グループ1〜10)を数える。</li>
 *     <li>味方が {@code phyllosensRallyMinAllies} 体以上いれば<b>そのままプレイヤーへ向かう</b>。</li>
 *     <li>足りなければ<b>近くの味方の重心へ先に歩く</b>(＝集まる)。重心まで
 *     {@link #RALLY_TIGHTNESS} ブロック以内なら、もう集まっているとみなして向かう。</li>
 *     <li>周りに味方が 1 体も居なければ待っても無駄なので単独で向かう。</li>
 *     <li>{@code phyllosensRallyMaxTicks} 待っても集まらなければ諦めて向かう。</li>
 * </ol>
 */
public class GuidedHuntGoal extends Goal {

    /** 味方を数える半径(ブロック)。 */
    private static final double RALLY_SCAN = 32.0D;
    /** 味方の重心までこの距離まで寄っていれば「もう集まっている」とみなす。 */
    private static final double RALLY_TIGHTNESS = 10.0D;
    /** ここまで近づいたら合流を待たずに標的として掴む(通常の戦闘 Goal へ引き継ぐ)。 */
    private static final double ENGAGE_DISTANCE = 20.0D;
    /**
     * 経路を引き直す間隔。相手は動くので追従し続ける。単位は {@link #tick()} の呼び出し回数 —— この Goal は毎 tick 更新を
     * 求めないので tick() は 2 game tick に 1 回、実時間では約 40 game tick。個体ごとに ±2 ずらして使う(2026-09-24、報告 299)。
     */
    private static final int REPATH_INTERVAL = 20;
    /** 経路探索が届く距離の目安。これより遠いときは「その方向のこのくらい先」を目指す。 */
    private static final double WAYPOINT_STEP = 24.0D;

    private final PathfinderMob mob;
    private final double speedModifier;

    private Player quarry;
    /**
     * 合流を待った長さ。単位は {@link #tick()} の呼び出し回数(= 2 game tick)なので、{@code phyllosensRallyMaxTicks}
     * の 200 は実時間では約 400 game tick(コメントだけの補足 2026-09-24、報告 299。値は変えていない)。
     */
    private int rallyTicks;
    private int repathTimer;

    public GuidedHuntGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!PhyllosensInfluence.isGuided(this.mob)) {
            return false;
        }
        // G4 の群れの一員は動かない(群れ v2、2026-09-25)。視線持ちのプレイヤーは群れの司令塔が「距離を問わず優先」で追う
        // (ユーザー指定「視線持ちのプレイヤー優先」はそちらで守る)。1 体ずつ歩き出すと群れの隊列が崩れる。
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        if (busyWithOwnAction()) {
            return false;
        }
        this.quarry = PhyllosensInfluence.nearestWatchedPlayer(level, this.mob);
        if (this.quarry == null) {
            return false;
        }
        // 標的を掴めない種(スポロフィル)は、寄り切ったらそこで打ち切る。掴めない相手を
        // 目指し続けると、{@link #ENGAGE_DISTANCE} の内と外で発動と終了を毎 tick 繰り返して
        // そのたびに経路探索が走る。
        if (!canEngage() && this.mob.distanceTo(this.quarry) <= ENGAGE_DISTANCE) {
            this.quarry = null;
            return false;
        }
        return true;
    }

    /**
     * 近づき切ったあと {@code setTarget} で通常の戦闘 Goal へ渡せる種か。
     * 攻撃手段を持たない種({@link AbstractOvergrownInvaderEntity#useTargetGoals()} が false)は
     * 渡す先が無く、渡すと<b>相手を抱えたまま動けなくなる</b>。
     */
    private boolean canEngage() {
        return !(this.mob instanceof AbstractOvergrownInvaderEntity invader) || invader.useTargetGoals();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.quarry == null || !PhyllosensInfluence.isWatchable(this.quarry)) {
            return false;
        }
        if (!PhyllosensInfluence.isWatched(this.quarry) || !PhyllosensInfluence.isGuided(this.mob)) {
            // 誘導か視線が切れた = もう知らない。今までどおりの行動へ戻る。
            return false;
        }
        // 途中で群れに入った(召喚・残党の合流など)。群れの司令塔に任せる。
        return !busyWithOwnAction() && !SwarmMember.isInSwarm(this.mob);
    }

    /**
     * 拡大種が飛びつき・うずくまり・索敵・ビームの最中か。その間は割り込まない
     * (途中で経路を奪うと、そのモーションが破綻する)。
     */
    private boolean busyWithOwnAction() {
        return this.mob instanceof AbstractOvergrownInvaderEntity invader
                && invader.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE;
    }

    @Override
    public void start() {
        this.rallyTicks = 0;
        // 最初の引き直しを個体ごとに 1〜4 回目の tick() へ散らす(2026-09-24 軽量化、報告 299)。
        // 誘導は PhyllosensInfluence#guideAllies が範囲の味方へ同じ tick に配るので、0 のままだと
        // 全員が同じ tick に経路探索と味方の走査を始め、その後も同じ位相で揃い続けていた。遅れは最大 8 game tick。
        this.repathTimer = 1 + Math.floorMod(this.mob.getId(), 4);
    }

    @Override
    public void stop() {
        this.quarry = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.quarry == null) {
            return;
        }
        this.rallyTicks++;
        this.mob.getLookControl().setLookAt(this.quarry, 20.0F, 20.0F);

        if (this.mob.distanceTo(this.quarry) <= ENGAGE_DISTANCE) {
            // 目の前まで来た。ここからは通常の戦闘 Goal の仕事(戦えない種はここで降りるだけ)。
            if (canEngage()) {
                this.mob.setTarget(this.quarry);
            }
            this.quarry = null;
            return;
        }

        if (--this.repathTimer <= 0) {
            // 間隔も個体ごとに ±2 ずらし、揃った位相が自然にほどけるようにする(2026-09-24 軽量化、報告 299)。
            this.repathTimer = REPATH_INTERVAL - 2 + Math.floorMod(this.mob.getId(), 5);
            // 行き先を決めるのは経路を引き直すこの tick だけ。以前はここより手前で毎 tick
            // 味方を数えていたが、その結果は経路を引くときにしか使わないので無駄だった
            // ({@link #rallyDestination} の javadoc 参照)。
            Vec3 rally = rallyDestination();
            moveToward(rally != null ? rally : this.quarry.position());
        }
    }

    /**
     * {@code destination} へ歩き出す。遠すぎる目的地には経路が引けないので、
     * <b>その方向 {@value #WAYPOINT_STEP} ブロック先</b>を刻んで目指す
     * (着くたびに引き直すので、結果として何百ブロックでも歩いていく)。
     */
    private void moveToward(Vec3 destination) {
        Vec3 here = this.mob.position();
        Vec3 delta = destination.subtract(here);
        double distance = delta.length();
        Vec3 goal = destination;
        if (distance > WAYPOINT_STEP) {
            goal = here.add(delta.scale(WAYPOINT_STEP / distance));
        }
        // 中継点へ引けなければ目的地へ引き直す。ただし目的地が近くて中継点 = 目的地のときは、同じ tick・同じ位置から
        // まったく同じ探索をもう 1 回するだけなので呼ばない(2026-09-24 軽量化、報告 792)。バニラ系のナビでは
        // 2 回目の結果もナビの状態も 1 回目と同じ。拡大種のナビ(InvaderNavigation)だけは失敗を数えるので呼び続ける
        // —— そちらはナビの中の控えが同じ探索を省いて、失敗だけ同じように数える。
        if (!this.mob.getNavigation().moveTo(goal.x, goal.y, goal.z, this.speedModifier)
                && (goal != destination || this.mob instanceof AbstractOvergrownInvaderEntity)) {
            // そちらへは経路が引けなかった。次の引き直しでまた試す(地形が変わることもある)。
            this.mob.getNavigation().moveTo(destination.x, destination.y, destination.z, this.speedModifier);
        }
    }

    /**
     * まだ合流を待つべきなら<b>集合地点(近くの味方の重心)</b>、そのままプレイヤーへ向かって
     * よければ {@code null}。
     *
     * <h2>1 tick に 1 回も走らせない(負荷対策 2026-09-16)</h2>
     * <p>周囲 {@value #RALLY_SCAN} ブロックの箱でエンティティを集める走査は安くない。
     * 以前はこれを「待つべきか」と「重心はどこか」で<b>別々に</b>呼んでいて、
     * 合流中は 1 体につき<b>毎 tick 2 回</b>走っていた —— 誘導は
     * {@link PhyllosensInfluence#guideAllies} が半径 256 ブロックの味方<b>全員</b>に配るので、
     * 群れが動き出した瞬間に「頭数 × 毎 tick 2 回」の走査がサーバーに乗る。
     *
     * <p>いまは走査 1 回で頭数と重心を同時に求め、しかも<b>経路を引き直す tick
     * ({@link #tick()} の {@value #REPATH_INTERVAL} 回前後に 1 回 = 実時間で約 40 game tick)にしか呼ばない</b>。行き先はそもそも
     * その tick でしか使わないので、合流の挙動は以前とまったく同じまま、走査の回数だけが
     * 20〜40 分の 1 になる。
     */
    @Nullable
    private Vec3 rallyDestination() {
        int maxWait = OvergrownVegetationConfig.COMMON.phyllosensRallyMaxTicks.get();
        if (this.rallyTicks > maxWait) {
            return null;
        }
        int needed = OvergrownVegetationConfig.COMMON.phyllosensRallyMinAllies.get();
        if (needed <= 0) {
            return null;
        }

        AABB box = this.mob.getBoundingBox().inflate(RALLY_SCAN);
        // 味方だけを、必要数に届いた時点で打ち切って集める(2026-09-24 軽量化、報告 781)。
        // 条件は元の getEntitiesOfClass(観戦者を除く)+ 下の足切りと同じで、並びも同じ。届いたら null を返すだけなので
        // 数え切らなくてよい。届かなければ打ち切りは起きず全員が入るので、重心も元と同じ順で足した同じ値になる。
        List<LivingEntity> allies = new ArrayList<>();
        this.mob.level().getEntities(EntityTypeTest.forClass(LivingEntity.class), box,
                entity -> EntitySelector.NO_SPECTATORS.test(entity)
                        && entity != this.mob && entity.isAlive() && MobGroup.of(entity) != null,
                allies, needed);
        int count = 0;
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (LivingEntity entity : allies) {
            count++;
            x += entity.getX();
            y += entity.getY();
            z += entity.getZ();
        }
        // 周りに味方が 1 体も居ないなら、待っても集まらないので単独で向かう。
        // 足りているならもう待つ必要が無い。
        if (count == 0 || count >= needed) {
            return null;
        }

        Vec3 centroid = new Vec3(x / count, y / count, z / count);
        // 重心のすぐそばまで寄っていれば、もう集まっているとみなしてプレイヤーへ向かう。
        return this.mob.position().distanceTo(centroid) > RALLY_TIGHTNESS ? centroid : null;
    }
}
