package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmTable;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * <b>空の群れの後続</b>(ヴェロフィル。群れ v2、2026-09-25)。先頭(空の群れの先導役)の横に V 字に並んで飛ぶ。
 *
 * <p>ユーザー指定(2026-09-25): ヴェロフィルは<b>空の群れを別に</b>作る。1 匹が先導し、残りは付いていく
 * (後続は自分で標的を探さず、うろつかない)。
 *
 * <h2>なぜ「先頭の横の点」を行き先にしないのか(レビュー指摘)</h2>
 * {@link VelophyllFlightMoveControl} は常に機首方向へ推力を入れ、最低速度 0.6 より遅くならず、1 tick に
 * {@code velophyllTurnRate}(3 度)しか曲がれない。旋回半径は巡航でおよそ 25 ブロックあり、速さは「まっすぐ飛べているか」でしか
 * 変わらない。動いている先頭の横数ブロックの点を指すと、追い越して輪を描いて戻る……を繰り返すだけで隊形にならない。
 *
 * <h2>やっていること</h2>
 * <ol>
 *     <li><b>向き</b>: 行き先は「自分の隊形の位置 + 先頭の進む向き × {@code airLookahead}(40)」。旋回半径より遠い点なので、
 *     先頭と平行に飛びながら横のずれだけがゆっくり縮む(先読みの長い純追跡)。{@value #AIM_INTERVAL} tick ごとに置き直す。</li>
 *     <li><b>前後</b>: 速さで直す。目指す速さ = 先頭の速さ + {@code airAlongTrackGain}(0.05)× 進む向きに沿ったずれ
 *     (後ろにいれば速く、前に出すぎれば遅く)。{@link VelophyllFlightMoveControl#setFormationSpeed} が
 *     {@code [最低速度, 巡航]} に丸める。先頭は巡航より少し遅く飛ぶ({@link VelophyllWanderGoal})ので、遅れた後続も追いつける。</li>
 *     <li><b>隊形の位置</b>: 横は {@link Swarm#airLateralOffset}(右が +、番号 0 → 右 1 列目、1 → 左 1 列目 …)。
 *     後ろへは列ごとに {@code airLateralSpacing} ずつ下げて V 字にする(45 度。仮決め 2026-09-25 —— 群れの設計値に前後の
 *     間隔は無いので横の間隔を流用した)。高さは先頭と同じ(ただし行き先の木の天蓋より {@code velophyllCanopyClearance} 上)。</li>
 *     <li><b>先頭が地上にいる</b>(汚染を広げている群れで自分だけ先に飛び立った、など): 先頭の上空を巡航高度で回って待つ。</li>
 * </ol>
 *
 * <h2>ほかの Goal との関係(優先度)</h2>
 * 噛みつき(1)・爆撃(2)・地上の胞子まき(3。群れでは汚染を広げる間だけ)がこの Goal(4)より先。司令塔が標的を渡すと
 * ({@code setTarget})爆撃・噛みつきが MOVE を奪い、終われば戻ってくる。先頭の飛び回り({@link VelophyllWanderGoal}、6)とは
 * 役が入れ替わったら<b>自分で止まる</b>(canUse = canContinueToUse = 後続か。先頭に選ばれたらこちらが止まって飛び回りが動く)。
 * <b>Swarm を持ち続けない</b>(毎 tick {@link SwarmManager#get} で引き直す。合流・組み直しで別の物になる)。
 */
public class SwarmAirFollowGoal extends Goal {

    /** 行き先を置き直す間隔(tick)。仮決め 2026-09-25(設計「5 tick ごと」)。 */
    private static final int AIM_INTERVAL = 5;
    /** 速さの指示の期限(tick)。毎 tick 出し直すので、止まったあと少しで切れれば足りる。 */
    private static final int SPEED_TTL = 10;
    /** 地上にいる先頭の上空を回るとき、円周上のどれだけ先を指すか(度)。{@link VelophyllBombGoal} の旋回と同じ考え方。 */
    private static final double ORBIT_LEAD_DEGREES = 55.0D;

    private final VelophyllEntity mob;
    private int aimTimer;

    public SwarmAirFollowGoal(VelophyllEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // 飛び立つモーションの間は始めない(終わったら本体が滑空へ戻す。VelophyllWanderGoal と同じ)。
        if (this.mob.getActionState() == VelophyllEntity.STATE_TAKEOFF) {
            return false;
        }
        return shouldFollow();
    }

    @Override
    public boolean canContinueToUse() {
        return shouldFollow();
    }

    /** 空の群れの後続で、飛んでいて、動いている先頭がいる。 */
    private boolean shouldFollow() {
        if (this.mob.isGrounded()) {
            return false;
        }
        Swarm swarm = SwarmManager.get(this.mob);
        return swarm != null && swarm.kind() == Swarm.Kind.AIR && swarm.isFollower(this.mob)
                && leader(swarm) != null;
    }

    /** 群れの先頭(ヴェロフィル・生きている)。いなければ null。 */
    @Nullable
    private static VelophyllEntity leader(Swarm swarm) {
        Mob vanguard = swarm.vanguard();
        return vanguard instanceof VelophyllEntity v && v.isAlive() ? v : null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.aimTimer = 0;
        if (this.mob.getActionState() != VelophyllEntity.STATE_TAKEOFF) {
            this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        }
    }

    @Override
    public void stop() {
        this.mob.flightControl().clearFormationSpeed();
    }

    @Override
    public void tick() {
        Swarm swarm = SwarmManager.get(this.mob);
        VelophyllEntity leader = swarm != null ? leader(swarm) : null;
        if (leader == null) {
            return;
        }
        if (leader.isGrounded()) {
            this.mob.flightControl().clearFormationSpeed();
            if (--this.aimTimer <= 0) {
                this.aimTimer = AIM_INTERVAL;
                this.mob.setMoveTargetPoint(orbitPoint(leader));
            }
            return;
        }

        SwarmTable table = DesignData.swarm();
        // 先頭の進む向き(水平)。yRot は +Z が 0 度 → 向きは (-sin, cos)。右は (-z, x)(SwarmController の陣形と同じ)。
        float yaw = leader.getYRot() * Mth.DEG_TO_RAD;
        double hx = -Mth.sin(yaw);
        double hz = Mth.cos(yaw);
        double rx = -hz;
        double rz = hx;

        int index = swarm.airFormationIndex(this.mob);
        double lateral = swarm.airLateralOffset(this.mob);
        int rank = index >= 0 ? index / 2 + 1 : 1;
        double back = rank * table.airLateralSpacing();
        double slotX = leader.getX() + rx * lateral - hx * back;
        double slotZ = leader.getZ() + rz * lateral - hz * back;

        // 前後のずれは速さで直す(後ろにいれば +)。
        double alongError = (slotX - this.mob.getX()) * hx + (slotZ - this.mob.getZ()) * hz;
        double wanted = leader.flightControl().currentSpeed() + table.airAlongTrackGain() * alongError;
        this.mob.flightControl().setFormationSpeed(wanted, SPEED_TTL);

        if (--this.aimTimer > 0) {
            return;
        }
        this.aimTimer = AIM_INTERVAL;
        double lookahead = table.airLookahead();
        double aimX = slotX + hx * lookahead;
        double aimZ = slotZ + hz * lookahead;
        double aimY = leader.getY();
        // 横へずれた列の下だけ木や丘が高いことがある。その天蓋より上を飛ぶ(読み込まれていない列は見ない)。
        Integer canopy = VelophyllWanderGoal.canopyAt(this.mob.level(), aimX, aimZ);
        if (canopy != null) {
            aimY = Math.max(aimY, canopy + OvergrownVegetationConfig.COMMON.velophyllCanopyClearance.get());
        }
        this.mob.setMoveTargetPoint(new Vec3(aimX, aimY, aimZ));
    }

    /**
     * 地上にいる先頭の上空を回る点。半径は爆撃の待ちと同じ {@code velophyllCircleRadius}、高さは先頭の真上の巡航高度。
     * 円周上の {@value #ORBIT_LEAD_DEGREES} 度先を指す(真横の点を指すと円の内側へ切り込む)。回る向きは個体ごとに固定。
     */
    private Vec3 orbitPoint(VelophyllEntity leader) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double radius = cfg.velophyllCircleRadius.get();
        double here = Math.atan2(this.mob.getZ() - leader.getZ(), this.mob.getX() - leader.getX());
        double direction = (this.mob.getId() & 1) == 0 ? 1.0D : -1.0D;
        double ahead = here + direction * Math.toRadians(ORBIT_LEAD_DEGREES);
        double x = leader.getX() + Math.cos(ahead) * radius;
        double z = leader.getZ() + Math.sin(ahead) * radius;
        Integer canopy = VelophyllWanderGoal.canopyAt(this.mob.level(), leader.getX(), leader.getZ());
        double y = (canopy != null ? canopy : leader.getY()) + cfg.velophyllCruiseAltitude.get();
        return new Vec3(x, y, z);
    }
}
