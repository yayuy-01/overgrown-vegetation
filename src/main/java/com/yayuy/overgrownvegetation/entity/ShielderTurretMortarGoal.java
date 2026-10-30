package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.GunTurrets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 過繫茂したスカトゥリウムが<b>機関砲台を迫撃砲で潰す</b> Goal(ユーザー要望 2026-09-21)。
 * {@link ShielderMortarGoal} の狙いを「いちばん近い、動いている機関砲台のブロック座標」に
 * 差し替えただけで、構え・斉射・クールタイムはまったく同じ
 * ({@code VelophyllLampBombGoal} が忌避灯に対してやっているのと同じ形)。
 *
 * <h2>なぜ砲台を優先するのか</h2>
 * 機関砲台の射程は 32 ブロック、迫撃砲の最大射程は 40 ブロック。<b>撃たれない位置から撃ち込める</b>ので、
 * 砲台を黙らせてから本隊が寄る、という手順が成立する。狙いに行く距離は {@code defense.json} の
 * {@code gunTurret.shielderDetectRange}(既定 40 = 迫撃砲の射程)。
 *
 * <p>弾が {@code gunTurret.corruptRadius} 以内で炸裂すると、<b>1 発ごとに {@code mortarDisableChance}
 * (既定 0.15、ユーザー指定: 2026-09-24 に 35% → 2026-09-25 に 15%)の確率で</b>砲台は胞子に覆われて止まり
 * ({@code VelophyllBombEntity} → {@link GunTurrets#corruptNear})、台帳から外れてこの Goal も終わる。
 * 外れれば砲台は動き続け、この Goal が残りの弾・次の斉射で撃ち続ける。
 * プレイヤーが右クリックで拭えば、また動き出して再び狙われる。
 *
 * <p>優先度は生き物への迫撃砲・突進と同じ 1 で、<b>先に登録してある</b>ので「砲台が見えているなら砲台が先」。
 * 相手が見えているかどうかで突進と迷うこともない({@link #prefersCharge} を常に false にしてある)——
 * 砲台には突進しても意味がないため。
 */
public class ShielderTurretMortarGoal extends ShielderMortarGoal {

    private final OvergrownShielderEntity shielder;
    @Nullable
    private BlockPos turret;

    public ShielderTurretMortarGoal(OvergrownShielderEntity mob) {
        super(mob);
        this.shielder = mob;
    }

    @Override
    protected boolean acquireTarget() {
        double range = DesignData.defense().turretShielderDetectRange();
        this.turret = GunTurrets.nearestWorking(this.shielder.level(), this.shielder.position(), range);
        return this.turret != null;
    }

    /** 砲台が覆われた(台帳から消えた)・壊された・別のブロックになったら終わり。 */
    @Override
    protected boolean targetStillValid() {
        return this.turret != null && GunTurrets.isWorking(this.shielder.level(), this.turret);
    }

    @Override
    protected void forgetTarget() {
        this.turret = null;
    }

    /** 砲台は動かないので見越さない。散らばり({@code shielderMortarScatter})も足さない —— 点を狙う。 */
    @Override
    protected Vec3 aimBase(int flightTicks) {
        return this.turret != null ? Vec3.atCenterOf(this.turret) : this.shielder.position();
    }

    /** 砲台に突進しても意味がないので、いつでも迫撃砲。 */
    @Override
    protected boolean prefersCharge() {
        return false;
    }

    @Override
    protected void lookAtTarget() {
        if (this.turret != null) {
            Vec3 center = Vec3.atCenterOf(this.turret);
            this.shielder.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 20.0F);
        }
    }
}
