package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * グループ4(拡大種)が<b>詰まったときに諦めて回り道する</b> Goal。
 *
 * <p>経路探索を直しても({@link OvergrownNodeEvaluator})、体の幅を枡目に丸めて扱う
 * バニラの都合上「経路上は通れるのに体がつかえる」場所は残る。そこで
 * {@link AbstractOvergrownInvaderEntity} 側が
 * <ul>
 *     <li>歩こうとしているのに 1 秒経っても進めていない、または</li>
 *     <li>頭上が塞がっていてジャンプが無駄になった</li>
 * </ul>
 * を検知したら、この Goal が最優先(優先度1)で割り込み、<b>横へ逸れる地点</b>へ歩き直す。
 * 追跡も前進もこの間は止まる(どちらも {@code MOVE} を使うため)。
 *
 * <p>同じ相手を追って何度も詰まったときに「その相手をしばらく諦める」のは
 * {@link AbstractOvergrownInvaderEntity#markStuck()} 側の仕事。
 */
public class InvaderUnstickGoal extends Goal {

    /** 回り道に使う最大時間。 */
    private static final int MAX_TICKS = 60;
    /** 立て直しが終わってから次に割り込めるようになるまで。 */
    private static final int RETRY_DELAY = 20;
    private static final int DETOUR_VERTICAL = 4;
    private static final double SPEED_MODIFIER = 1.0D;
    /**
     * 回り道を探す向き(詰まっている向きからの角度、度)。<b>この順に試す</b>。
     *
     * <p>真横(90 度)から始めるのは<b>壁伝いに抜けるのがいちばん近い</b>から。
     * 駄目なら斜め後ろ(135 度)、最後に真後ろ(180 度)。
     * 左右は個体ごとに毎回どちらかを先に引くので、同じ壁に集まった群れが一斉に同じ側へ寄らない。
     */
    private static final float[] DETOUR_ANGLES = {90.0F, 135.0F, 180.0F};
    /** 候補地を探す円錐の広さ(ラジアン、片側)。狭いほど狙った向きへ素直に出る。 */
    private static final double DETOUR_CONE = Math.PI / 4.0D;

    private final AbstractOvergrownInvaderEntity mob;

    private int ticks;
    private int nextAllowedTick;
    private Vec3 detour;

    public InvaderUnstickGoal(AbstractOvergrownInvaderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.mob.isStuck() || this.mob.tickCount < this.nextAllowedTick) {
            return false;
        }
        // 群れの後続は回り道しない(群れ v2、2026-09-25)。後続の立て直しは足跡のカーソルへの 1 本だけ
        // (SwarmFollowGoal が札の入れ物を通して引く)。本体の詰まり検知も後続では数えない。
        if (SwarmMember.isFollower(this.mob)) {
            return false;
        }
        if (this.mob.prefersDiggingWhenStuck()) {
            // 掘って抜けるほうが確実(アルボラ)。回り道は譲る。
            return false;
        }
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            // 胞子をばらまいている最中などは割り込まない(そもそも歩いていない)。
            return false;
        }
        this.detour = findDetour();
        if (this.detour == null) {
            // 逃げ道が無いなら印だけ消して間を置く(毎 tick 探し直さない)。
            this.mob.clearStuck();
            this.nextAllowedTick = this.mob.tickCount + RETRY_DELAY;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.ticks <= MAX_TICKS
                && !this.mob.getNavigation().isDone()
                && this.mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_IDLE
                && !SwarmMember.isFollower(this.mob);
    }

    @Override
    public void start() {
        this.mob.clearStuck();
        this.ticks = 0;
        this.mob.getNavigation().stop();
        this.mob.getNavigation().moveTo(this.detour.x, this.detour.y, this.detour.z, SPEED_MODIFIER);
    }

    @Override
    public void stop() {
        this.detour = null;
        this.mob.clearStuck();
        this.nextAllowedTick = this.mob.tickCount + RETRY_DELAY;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.ticks++;
    }

    /**
     * 回り道の行き先。<b>詰まっている向きを基準に、横 → 斜め後ろ → 真後ろ</b>の順に試す
     * (2026-09-17 に作り直し)。
     *
     * <p>それまでは「ランダムに左右どちらかへ 1 回試して、駄目なら適当なところへ」だった。
     * 壁に当たった向きを 1 度しか使わないので、<b>回り道が壁の方を向いていることが多く</b>、
     * 同じ場所で詰まり直していた。
     *
     * <p>左右は毎回どちらを先に引くか振る —— 固定すると、同じ壁に当たった群れが
     * <b>全員同じ側へ寄って団子になる</b>。
     */
    private Vec3 findDetour() {
        Vec3 blocked = blockedDirection();
        int radius = OvergrownVegetationConfig.COMMON.invaderUnstickRadius.get();
        double first = this.mob.getRandom().nextBoolean() ? 1.0D : -1.0D;
        for (float angle : DETOUR_ANGLES) {
            for (double side : new double[] {first, -first}) {
                Vec3 away = rotate(blocked, angle * side);
                Vec3 towards = this.mob.position().add(away.scale(radius));
                Vec3 pos = DefaultRandomPos.getPosTowards(this.mob, radius, DETOUR_VERTICAL, towards, DETOUR_CONE);
                if (pos != null) {
                    return pos;
                }
                if (angle >= 180.0F) {
                    // 真後ろは左右で同じ向き。2 回試しても意味がない。
                    break;
                }
            }
        }
        // どの向きも駄目。最後の手段でその辺へ。
        return LandRandomPos.getPos(this.mob, radius, DETOUR_VERTICAL);
    }

    /** 水平ベクトルを {@code degrees} 度回す。 */
    private static Vec3 rotate(Vec3 direction, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(direction.x * cos - direction.z * sin, 0.0D, direction.x * sin + direction.z * cos);
    }

    /** 詰まっている向き = いま進もうとしていた向き。 */
    private Vec3 blockedDirection() {
        Path path = this.mob.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            Vec3 next = path.getNextEntityPos(this.mob);
            Vec3 delta = new Vec3(next.x - this.mob.getX(), 0.0D, next.z - this.mob.getZ());
            if (delta.lengthSqr() > 1.0E-4D) {
                return delta.normalize();
            }
        }
        Vec3 look = this.mob.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        return flat.lengthSqr() > 1.0E-4D ? flat.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }
}
