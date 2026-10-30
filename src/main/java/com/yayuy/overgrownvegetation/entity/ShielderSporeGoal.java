package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 「過繁茂したスカトゥリウム」が<b>その場に座り、周囲を汚染する</b>(ユーザー要望 2026-09-15)。
 *
 * <p>グループ4 に移った以上、ほかの拡大種と同じ「汚染を自分の足で運び、腰を落ち着けた場所を汚す」
 * 役割を持つ。汚し方は共通の {@link InvaderSporeField}(近い順に 1 マスずつ・胞子除去装置の中では止まる)で、
 * アルボラのうずくまり({@link InvaderSporeCrouchGoal})とまったく同じ仕掛け。違いは 2 つだけ:
 * <ul>
 *     <li>スカトゥリウムは {@link AbstractOvergrownInvaderEntity} を継承していない
 *     (行動もステータスも維持したまま群れに入れるため独立している)ので、Goal も別に立ててある。</li>
 *     <li>座っている間は<b>周りの Mob へ胞子感染も配る</b>
 *     ({@code invaderSporeInfectRadius}、アルボラのうずくまりと同じ範囲)。</li>
 * </ul>
 *
 * <h2>いつ座るか</h2>
 * <b>襲う相手が居ないとき</b>だけ。加えて
 * <ul>
 *     <li>接地していて水に入っていない</li>
 *     <li>前回から {@code invaderSporeIntervalTicks}(±25%)たっている</li>
 * </ul>
 * を満たしたとき。立つのは<b>相手を見つけたか、届く範囲を汚し尽くしたか</b>のどちらか
 * ——「汚す物が多い場所ほど長く座っている」ことになる。
 *
 * <h2>群れ(v2、2026-09-25)</h2>
 * 群れの一員は<b>この Goal を使わない</b>。いつ座るかは司令塔が決め、群れごと止まって全員で汚す
 * ({@link SwarmSpreadGoal}、ユーザー指定 2026-09-25)。座って汚す中身は {@link SporeSpreadSession}(型 {@code SIT})に
 * 切り出して、ここと群れの汚染とで同じものを使う。
 */
public class ShielderSporeGoal extends Goal {

    private final OvergrownShielderEntity mob;

    /** 座って汚す中身(粒子・感染・汚染の場。群れの汚染と共通)。 */
    private SporeSpreadSession session;
    private int nextAllowedTick;

    public ShielderSporeGoal(OvergrownShielderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isBusy() || this.mob.tickCount < this.nextAllowedTick) {
            return false;
        }
        // 群れの一員は群れごと止まって汚す(SwarmSpreadGoal)。
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            return false;
        }
        // 既に汚染された地面の上では座らない（アルボラのうずくまりと同じ足切り）。
        // これが無いと、汚染し尽くした縄張りの真ん中で座り直しを繰り返して、ほかの行動がいつまでも始まらない。
        return this.mob.onGround() && !this.mob.isInWater() && !SporeSpreadSession.onCorruptedGround(this.mob);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.getActionState() != OvergrownShielderEntity.STATE_SIT) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            // 横取りされた。立ち上がって相手をする。
            return false;
        }
        return this.session != null && !this.session.isExhausted();
    }

    @Override
    public void start() {
        this.mob.setActionState(OvergrownShielderEntity.STATE_SIT);
        this.mob.getNavigation().stop();
        this.session = new SporeSpreadSession(this.mob, this.mob.sporeCorruptionRadius(), SporeSpreadSession.Style.SIT);
        if (this.mob.level() instanceof ServerLevel level) {
            this.session.begin(level);
        }
    }

    @Override
    public void stop() {
        this.session = null;
        if (this.mob.getActionState() == OvergrownShielderEntity.STATE_SIT) {
            this.mob.setActionState(OvergrownShielderEntity.STATE_NONE);
        }
        // 実際は ±25% ばらつかせて、群れ全員が同時に立ち座りしないようにする。
        int base = OvergrownVegetationConfig.COMMON.invaderSporeIntervalTicks.get();
        int jitter = Math.max(1, base / 4);
        this.nextAllowedTick = this.mob.tickCount + base - jitter
                + this.mob.getRandom().nextInt(jitter * 2 + 1);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // 座っている間は一歩も動かない(SporeSpreadSession#tick が経路を止める。速度は travel が STATE_SIT で潰す)。
        if (this.session != null && this.mob.level() instanceof ServerLevel level) {
            this.session.tick(level);
        }
    }
}
