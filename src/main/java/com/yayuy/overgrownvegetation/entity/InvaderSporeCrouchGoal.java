package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * グループ4(拡大種)が<b>汚れていない土地でその場にうずくまり、周囲へ胞子をばらまいて汚染する</b> Goal
 * (ユーザー要望 2026-09-13、条件と汚染の仕方は 2026-09-14 に見直し)。
 *
 * <p>拡大種は汚染を「運ぶ」役なので、運んだ先で立ち止まって種を落とす動作がいる。発動条件は
 * <ul>
 *     <li><b>襲う相手が見つかっていない</b></li>
 *     <li><b>いま立っている地面が汚染ブロックではない</b>({@link ModTags#CORRUPTED}) —
 *     既に汚れている場所で腰を落ち着けても意味がないので、汚れていない土地でだけ行う
 *     (ユーザー指示 2026-09-14。それまでは「過繁茂バイオームの外なら」という条件だった)</li>
 *     <li>接地している・水に入っていない</li>
 *     <li>前回から {@code invaderSporeIntervalTicks} tick 以上たっている</li>
 * </ul>
 *
 * <p>始まったら<b>一歩も動かず、汚染を広げることだけに専念する</b>(ユーザー指示 2026-09-14)。
 * 終わるのは次の 2 つだけ:
 * <ul>
 *     <li><b>襲う相手を見つけた</b>(横取りされたら中断して襲いに行く)</li>
 *     <li><b>届く範囲を汚し尽くした</b> — 半径 {@link AbstractOvergrownInvaderEntity#sporeCorruptionRadius()} の中に
 *     汚染できるブロックが 1 つも残っていない状態({@link InvaderSporeField#isFinished()})</li>
 * </ul>
 * 時間制限は無いので、汚す物が多い場所ほど長く居座る。
 *
 * <p>うずくまっているあいだ:
 * <ul>
 *     <li>{@link ModParticles#FINE_SPORE 細かい胞子}と胞子の花の粒子を撒き続ける</li>
 *     <li>{@code invaderSporeInfectRadius} 以内の「グループ1〜10 でもプレイヤーでもない」生き物へ
 *     {@link SporeInfectionEffects#infect 胞子感染1} を付ける(1 秒ごとに判定)</li>
 *     <li><b>周囲 {@link AbstractOvergrownInvaderEntity#sporeCorruptionRadius()} を内側から順に汚染していく</b> —
 *     {@code invaderSporeCorruptionIntervalTicks} tick に 1 マスずつ({@link InvaderSporeField})。
 *     <b>グループ4 が汚染を広げるのはこの最中だけ</b>で、歩いているだけでは何も汚さない</li>
 * </ul>
 * そして<b>汚し尽くして終わったときだけ、{@code invaderFernSpawnChance} の確率で汚染シダが
 * 1 本芽吹く</b>({@link BlightFernPlacement}) — これが「汚染シダを増やす」経路。芽吹いたシダは
 * そこから本格的な侵食を始め、やがてそのチャンクを過繁茂バイオームへ変える。
 * 相手を見つけて中断したときは芽吹かない。
 *
 * <h2>見た目の方針(グループ4 共通)</h2>
 * <p>{@code spore} アニメーションは<b>力んでいる姿ではなく、くつろいで休んでいる姿</b>にする
 * (ユーザー指示 2026-09-14)。腹や尾を大きく脈動させる「踏ん張り」に見える動きは避け、
 * 周期を長く(8 秒程度)・振幅を小さく取り、上下の脈動ではなく左右のゆったりした揺れで見せる。
 * <b>これから追加するグループ4 の mob もすべて同じ方向で作ること。</b>
 *
 * <h2>群れ(v2、2026-09-25)</h2>
 * <p>群れの一員は<b>この Goal を使わない</b>(1 体ずつしゃがまない)。いつ汚すかは司令塔が決め、群れごと止まって
 * 全員で汚す({@link SwarmSpreadGoal}、ユーザー指定 2026-09-25「暇なら止まって全員で」)。汚し方・見た目・芽吹きは
 * {@link SporeSpreadSession} に切り出して、この Goal と群れの汚染とで同じものを使う。
 */
public class InvaderSporeCrouchGoal extends Goal {

    private final AbstractOvergrownInvaderEntity mob;

    private int nextTime;
    /** 汚染の広がり方・粒子・感染・芽吹き(群れの汚染と共通)。 */
    private SporeSpreadSession session;

    public InvaderSporeCrouchGoal(AbstractOvergrownInvaderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
        // 湧いた直後に全員そろってうずくまらないよう、初回だけばらけさせる。
        this.nextTime = mob.tickCount + mob.getRandom().nextInt(600);
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextTime) {
            return false;
        }
        // 群れの一員は群れごと止まって汚す(SwarmSpreadGoal)。
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
            return false;
        }
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            return false;
        }
        if (!this.mob.onGround() || this.mob.isInWater()) {
            return false;
        }
        // 既に汚染された地面の上ではやらない(汚れていない土地でだけ腰を落ち着ける)。
        return !SporeSpreadSession.onCorruptedGround(this.mob);
    }

    /** 終わるのは「相手を見つけた」か「汚し尽くした」ときだけ。時間では終わらない。 */
    @Override
    public boolean canContinueToUse() {
        return (this.session == null || !this.session.isExhausted())
                && (this.mob.getTarget() == null || !this.mob.getTarget().isAlive())
                && !this.mob.isInWater();
    }

    @Override
    public void start() {
        this.session = new SporeSpreadSession(this.mob, this.mob.sporeCorruptionRadius(), SporeSpreadSession.Style.CROUCH);
        this.mob.getNavigation().stop();
        this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_SPORE);
        if (this.mob.level() instanceof ServerLevel level) {
            this.session.begin(level);
        }
    }

    @Override
    public void stop() {
        int interval = OvergrownVegetationConfig.COMMON.invaderSporeIntervalTicks.get();
        // 次回までの間隔は ±25% ばらつかせる(群れがそろって同時にしゃがまないように)。
        this.nextTime = this.mob.tickCount + interval
                + this.mob.getRandom().nextInt(Math.max(1, interval / 2)) - interval / 4;
        if (this.mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_SPORE) {
            this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
        // 芽吹くのは「汚し尽くして終わった」ときだけ(SporeSpreadSession#finish が見る)。相手を見つけて中断したときは何も残さない。
        if (this.session != null && this.mob.level() instanceof ServerLevel level) {
            this.session.finish(level);
        }
        this.session = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.session == null) {
            return;
        }
        if (this.mob.level() instanceof ServerLevel level) {
            this.session.tick(level);
        }
    }
}
