package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corruption.FilmTrails;
import java.util.EnumSet;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

/**
 * 水面の膜に残った筋をたどる(2026-11-13、ユーザー「筋を見て追ってくる Mob」)。どの Mob に持たせるかは未定なので、まだどこにも付けていない。
 * <p>
 * 標的のいない間、半径 {@code radius} の中で一番新しい跡の点({@link FilmTrails})へ泳いで・歩いていき、着いたらそれより新しい点を探して
 * 続ける(筋の新しい方へたどる = 通ったものが行った先へ)。より新しい点が見つからなければ終わる。自分の跡はたどらない。
 * 逃げる側の手: 膜の無い水・陸へ出る、筋が閉じる(約 1 分)のを待つ。数値は仮決め。
 */
public class FollowFilmTrailGoal extends Goal {

    static final int SEARCH_INTERVAL = 10;
    static final double REACHED = 1.5;
    static final int MAX_LEG_TICKS = 300;

    private final Mob mob;
    private final double speed;
    private final double radius;
    private final Predicate<FilmTrails.Point> filter;
    @Nullable
    private FilmTrails.Point goal;
    private long lastTime = Long.MIN_VALUE;
    private int cooldown;
    private int leg;

    /** {@code filter}: たどる跡(例: プレイヤーの跡だけ)。 */
    public FollowFilmTrailGoal(Mob mob, double speed, double radius, Predicate<FilmTrails.Point> filter) {
        this.mob = mob;
        this.speed = speed;
        this.radius = radius;
        this.filter = filter;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.getTarget() != null || !(this.mob.level() instanceof ServerLevel)) {
            return false;
        }
        if (--this.cooldown > 0) {
            return false;
        }
        this.cooldown = SEARCH_INTERVAL;
        // 前にたどり終えた筋が残っていても、それより新しい点が無ければ動かない
        this.goal = next(Long.MIN_VALUE);
        return this.goal != null && this.goal.time() > this.lastTime;
    }

    @Override
    public boolean canContinueToUse() {
        return this.goal != null && this.mob.getTarget() == null;
    }

    @Override
    public void start() {
        this.leg = 0;
        moveTo(this.goal);
    }

    @Override
    public void stop() {
        this.goal = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        FilmTrails.Point g = this.goal;
        if (g == null) {
            return;
        }
        this.leg++;
        boolean reached = this.mob.position().distanceToSqr(g.x(), this.mob.getY(), g.z()) < REACHED * REACHED;
        if (reached || this.leg > MAX_LEG_TICKS) {
            this.lastTime = Math.max(this.lastTime, g.time());
            this.goal = next(this.lastTime);
            if (this.goal != null) {
                this.leg = 0;
                moveTo(this.goal);
            }
        } else if (this.mob.getNavigation().isDone() && this.leg % 10 == 0) {
            // 経路が切れた・まだ引けなかった(出てすぐで地面に着いていない等): 引き直す
            moveTo(g);
        }
    }

    /** 今いる所の近くで、時刻 {@code after} より新しい一番新しい点。 */
    @Nullable
    private FilmTrails.Point next(long after) {
        ServerLevel level = (ServerLevel) this.mob.level();
        int self = this.mob.getId();
        return FilmTrails.freshestNear(level, this.mob.position(), this.radius, FilmTrails.LIFETIME,
                p -> p.owner() != self && p.time() > after && this.filter.test(p));
    }

    private void moveTo(@Nullable FilmTrails.Point p) {
        if (p != null) {
            this.mob.getNavigation().moveTo(p.x(), p.y() - 1.0, p.z(), this.speed);
        }
    }

    /** 今たどっている点(開発用)。 */
    @Nullable
    public FilmTrails.Point goal() {
        return this.goal;
    }
}
