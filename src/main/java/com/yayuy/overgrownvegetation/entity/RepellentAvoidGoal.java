package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmGoals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 忌避灯({@code block/RepellentLampBlock})の半径に入ったグループ1〜4 の Mob を外へ追い出す Goal(2026-09-19)。
 * {@link GuidedHuntInjector} が仕込む(ヴェロフィルは除く — あちらは灯りを爆撃しに来る側)。
 *
 * <p>優先度は最上位(1)なので、標的を追っている最中でも灯りの半径に足を踏み入れた瞬間に外へ向かう。
 * 半径の縁で「入る → 追い出される → また入る」を繰り返す = 灯りのそばには寄れない、が狙いの挙動。
 * 遠距離攻撃(フィロセンスのビーム・シールダーの迫撃)は半径の外から届くので、灯りは万能ではない。
 */
public class RepellentAvoidGoal extends Goal {

    private static final int REPATH_INTERVAL = 10;

    private final PathfinderMob mob;
    private final double speedModifier;

    private BlockPos lamp;
    private int repathTimer;
    /**
     * 前回の逃げ道が引けなかったか(経路が無い、または出発点から一歩も進めない部分経路)。
     * 2026-09-24 軽量化(監査 911 / 343): 前は経路が引けないと {@code isDone()} が真のままなので、
     * 次の goal tick(2 game tick 後)に必ず getPosAway と経路探索(侵攻種は予算 3 倍の A*)をやり直していた。
     * 失敗したときだけ {@link #failStreak} に応じて間隔を空ける。着いた・進めた場合は前と同じくすぐ引き直す。
     * 経路が無くても直進するナビの Mob(クモ系、{@link #beelinesWithoutPath})は常に false = 前と同じ。
     */
    private boolean lastMoveFailed;
    /** 続けて失敗した回数。引き直しまでの goal tick を 2, 4, 8, … と延ばし、{@link #REPATH_INTERVAL} で頭打ち。 */
    private int failStreak;

    public RepellentAvoidGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Nullable
    protected BlockPos lampNearby() {
        BlockPos lamp = RepellentLamps.nearestActive(this.mob.level(), this.mob.position(), DesignData.defense().lampRadius());
        if (lamp == null && com.yayuy.overgrownvegetation.defense.SunlitLanterns.affects(this.mob)) {
            // 灯した陽溜まりのランタンを腰に下げたプレイヤーも、忌避灯と同じく近寄らない所(2026-10-16、①。G1 と小さな敵だけ)
            lamp = com.yayuy.overgrownvegetation.defense.SunlitLanterns.nearestBearer(this.mob.level(), this.mob.position(),
                    com.yayuy.overgrownvegetation.defense.SunlitLanterns.RADIUS);
        }
        if (lamp == null && com.yayuy.overgrownvegetation.phase.MobGroup.of(this.mob) == com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_1) {
            // 受け流しの杭で囲った四角の中の G1 は、囲いの真ん中から外へ出ていく(2026-10-16、⑭「G1 も入らない」)
            com.yayuy.overgrownvegetation.defense.StakeWards.Ward ward =
                    com.yayuy.overgrownvegetation.defense.StakeWards.wardAt(this.mob.level(), this.mob.blockPosition());
            if (ward != null) {
                lamp = ward.center();
            }
        }
        return lamp;
    }

    @Override
    public boolean canUse() {
        if (this.mob.level().isClientSide()) {
            return false;
        }
        this.lamp = lampNearby();
        return this.lamp != null && !breachingPastLamps();
    }

    @Override
    public boolean canContinueToUse() {
        this.lamp = lampNearby();
        return this.lamp != null && !breachingPastLamps();
    }

    /**
     * TD の攻城で、この Mob の群れが灯りを覆いに行っている・灯りのそばの壁を壊している間は押し出さない
     * ({@link SwarmGoals#breachIgnoresLamps}、仮決め 2026-09-25)。押し出すと灯りにも壁にも手が届かず、ターゲットの横に
     * 灯りを 1 つ置くだけで拠点が無敵になる。灯りが近いときだけ群れを引く(ふだんは 1 回の台帳引きのまま)。
     */
    private boolean breachingPastLamps() {
        return SwarmGoals.breachIgnoresLamps(SwarmManager.get(this.mob));
    }

    @Override
    public void start() {
        this.repathTimer = 0;
        this.lastMoveFailed = false;
        this.failStreak = 0;
        this.mob.setTarget(null); // 灯りのそばの相手は諦める(縁で睨み合わない)
    }

    @Override
    public void stop() {
        this.lamp = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.lamp == null) {
            return;
        }
        // 前回引けなかったときは、経路が無くて isDone() が真でも間隔を待つ(監査 911)。
        if (--this.repathTimer > 0 && (this.lastMoveFailed || !this.mob.getNavigation().isDone())) {
            return;
        }
        this.repathTimer = REPATH_INTERVAL;
        Vec3 lampPos = Vec3.atCenterOf(this.lamp);
        Vec3 away = DefaultRandomPos.getPosAway(this.mob, 16, 7, lampPos);
        if (away == null) {
            // ランダム探索が外れたら、灯りと反対方向へまっすぐ。
            Vec3 dir = this.mob.position().subtract(lampPos);
            double len = dir.horizontalDistance();
            if (len < 1.0E-3D) {
                dir = new Vec3(1.0D, 0.0D, 0.0D);
                len = 1.0D;
            }
            double reach = DesignData.defense().lampRadius() + 4.0D;
            away = lampPos.add(dir.x / len * reach, 0.0D, dir.z / len * reach);
        }
        PathNavigation navigation = this.mob.getNavigation();
        boolean moving = navigation.moveTo(away.x, away.y, away.z, this.speedModifier);
        Path path = navigation.getPath();
        // 出発点だけの部分経路(届かないうえに 1 ノード)は、moveTo が true でも一歩も動かない = 失敗と同じ。
        // ただし経路が無くても直進するナビ({@link #beelinesWithoutPath})は失敗にしない —— 実際には動いているので。
        this.lastMoveFailed = !beelinesWithoutPath(navigation)
                && (!moving || path == null || (!path.canReach() && path.getNodeCount() <= 1));
        if (this.lastMoveFailed) {
            this.failStreak = Math.min(this.failStreak + 1, 8);
            this.repathTimer = Math.min(REPATH_INTERVAL, 1 << this.failStreak);
        } else {
            this.failStreak = 0;
        }
    }

    /**
     * 経路が引けなくても<b>ナビ自身が直進させる</b> Mob か(2026-09-24 軽量化の見直し、監査 911 / 343 へのレビュー指摘)。
     *
     * <p>バニラの {@link WallClimberNavigation}(寄生グモ = バニラのクモのまま、ファランギオタルブス = その匿名の子)は
     * {@code moveTo(x, y, z)} の中の {@code createPath(BlockPos, int)} で行き先を覚え、経路が {@code null} でも
     * (出発点だけの部分経路を歩き終えても)その点へ {@code MoveControl} で<b>まっすぐ歩かせる</b>。
     * 前はこの直進先を goal tick ごと(2 game tick ごと)に getPosAway で引き直していたので、
     * 失敗扱いで間隔を空けると直進の向きの変わり方・着いた後の立ち止まり・灯りの外へ出る時刻が変わってしまう。
     * これらの Mob は失敗扱いにせず、前と同じく毎回引き直す(間引きはしない)。
     *
     * <p>侵攻種(アルボラ)の {@link InvaderNavigation} の Climber も {@code WallClimberNavigation} の子だが、
     * {@code tick()} でこの直進を潰してあるので、経路が無ければ本当に止まっている —— 間引いてよい。
     * 侵攻種は必ず {@code InvaderNavigation.create} のナビを使う({@link AbstractOvergrownInvaderEntity#createNavigation}、
     * 上書きしている子はいない)ので、Mob の側で見分ける。
     */
    private boolean beelinesWithoutPath(PathNavigation navigation) {
        return navigation instanceof WallClimberNavigation && !(this.mob instanceof AbstractOvergrownInvaderEntity);
    }
}
