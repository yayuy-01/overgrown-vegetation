package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * フィロセンスの<b>索敵行動</b>(ユーザー要望 2026-09-14、作り直し版)。
 *
 * <h2>入る条件</h2>
 * <p>ひとつだけ —— <b>半径 {@code phyllosensAuraRange}(既定 32)にプレイヤーが
 * {@code phyllosensQuietTicks}(既定 1200 = 1 分)居なかったこと</b>
 * ({@link PhyllosensEntity#canBeginScout()})。見るのは<b>プレイヤーだけ</b>で、
 * 牛やコウモリは数に入らない。
 *
 * <h2>やること</h2>
 * <p>その場に留まって首を大きく上げ、花を開き({@code scout} アニメーション)、胞子を撒き散らして
 * 足元を汚染しながら、<b>ふだんよりずっと広い範囲へ 2 つの印を配る</b>:
 * <ul>
 *     <li><b>視線</b>({@code phyllosens_watch}) → 半径 {@code phyllosensScoutWatchRange}
 *     (既定 512)のプレイヤー全員。</li>
 *     <li><b>誘導</b>({@code phyllosens_guidance}) → 半径 {@code phyllosensScoutGuidanceRange}
 *     (既定 256)の味方(グループ1〜10)全員。</li>
 * </ul>
 * これを {@code phyllosensScoutDurationTicks}(既定 600 = 30 秒)続ける。
 * 配り直しは {@code phyllosensScanIntervalTicks} ごと —— 256 ブロックの走査は重いので、
 * <b>毎 tick はやらない</b>(読み込まれているチャンクぶんの全エンティティを舐めることになる)。
 *
 * <p>印を配るだけで、フィロセンス自身は誰も追わない。襲いに行くのは誘導を受けた味方
 * ({@link GuidedHuntGoal})で、<b>一体ずつ突っ込まず合流してから</b>向かう。
 *
 * <p>途中で終わるのは<b>殴られたとき</b>と<b>32 ブロックにプレイヤーが入ってきたとき</b>だけ。
 * 前者は棒立ちのまま殴られ続けないため、後者は「近くに居るならもう配る必要がない
 * (ふだんの輪が届いている)」ため。
 *
 * <h2>群れ(v2、2026-09-25)</h2>
 * 群れの一員のフィロセンスは<b>この Goal では索敵しない</b>。索敵するのは群れが止まって全員で汚すとき(SPREAD)だけで、
 * そのときは {@link SwarmSpreadGoal} が同じ中身({@link PhyllosensScoutAction})を回す —— 群れが歩いている最中に
 * 1 体だけ 30 秒根を張ると、先導役がそれを待ち続けるか置いていくかしかない。根を張るのは SPREAD の間だけ期待どおり。
 */
public class PhyllosensScoutGoal extends Goal {

    /** 殴られてから索敵に戻らない時間(tick)。 */
    private static final int HURT_LOCKOUT = 100;

    private final PhyllosensEntity mob;

    /** 索敵の中身(群れの汚染と共通)。 */
    private final PhyllosensScoutAction action;

    public PhyllosensScoutGoal(PhyllosensEntity mob) {
        this.mob = mob;
        this.action = new PhyllosensScoutAction(mob);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel)) {
            return false;
        }
        // 群れの一員は SPREAD の間だけ、SwarmSpreadGoal から索敵する。
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            return false;
        }
        if (!this.mob.onGround() || this.mob.isInWater()) {
            return false;
        }
        if (recentlyHurt()) {
            return false;
        }
        return this.mob.canBeginScout();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.isInWater() || recentlyHurt()) {
            return false;
        }
        // 近くまでプレイヤーが来たらやめる(ふだんの輪が届いているので配る意味がない)。
        if (this.mob.getPlayerFreeTicks() <= 0) {
            return false;
        }
        // 途中で群れに入った(召喚・残党の合流)。群れの側で止まる・歩くを決める。
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        return this.action.ticks() < OvergrownVegetationConfig.COMMON.phyllosensScoutDurationTicks.get();
    }

    /** 直近 {@value #HURT_LOCKOUT} tick 以内に殴られたか。殴られている間は棒立ちしない。 */
    private boolean recentlyHurt() {
        return this.mob.getLastHurtByMob() != null
                && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < HURT_LOCKOUT;
    }

    @Override
    public void start() {
        if (this.mob.level() instanceof ServerLevel level) {
            this.action.start(level);
        }
    }

    @Override
    public void stop() {
        if (this.mob.level() instanceof ServerLevel level) {
            this.action.stop(level, false);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.mob.level() instanceof ServerLevel level) {
            this.action.tick(level);
        }
    }
}
