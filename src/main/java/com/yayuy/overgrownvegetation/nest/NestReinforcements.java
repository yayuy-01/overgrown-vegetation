package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity;
import com.yayuy.overgrownvegetation.entity.NestRole;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 小規模レイドの<b>巣からの増援</b>(ユーザー指定 2026-09-20)。
 *
 * <p>一団が湧いた地点から {@code reinforceRange} 以内に巣があれば、その巣の住人を
 * {@code reinforceCount} 体まで送り出す。送り出された個体は:
 *
 * <ul>
 *     <li>{@link NestRole#RESIDENT} を外れ、巣の綱({@code guardLeashRadius})から解放される</li>
 *     <li>{@code leavingNest} が立ち、{@link com.yayuy.overgrownvegetation.entity.NestExitGoal}
 *     が<b>道しるべを 1 点ずつ辿って坑口まで歩かせる</b></li>
 *     <li>外へ出たところで狙いのプレイヤーが渡され、ふつうの狩りの Goal に引き継がれる</li>
 * </ul>
 *
 * <p>坑口まで道しるべで歩かせるのが肝心 —— 巣の奥から地上のプレイヤーを直に指すと
 * 経路が出ず、壁を登って天井に張り付いたまま暴れ続ける(2026-09-20 のユーザー報告)。
 */
public final class NestReinforcements {

    /** 巣の住人を探すとき、入口のまわりを見る箱の大きさ。 */
    private static final double NEST_BOX = 96.0D;

    /**
     * {@code site} の近くの巣から増援を送り出す。
     *
     * @return 送り出した数
     */
    public static int dispatch(ServerLevel level, BlockPos site, ServerPlayer target) {
        NestTable t = DesignData.nest();
        NestData.Nest nest = NestData.get(level).nearest(site);
        if (nest == null || nest.route().isEmpty()) {
            return 0;
        }
        double range = t.reinforceRange();
        if (nest.origin().distSqr(site) > range * range) {
            return 0;
        }
        List<AbstractOvergrownInvaderEntity> residents = residentsOf(level, nest);
        if (residents.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (AbstractOvergrownInvaderEntity mob : residents) {
            if (sent >= t.reinforceCount()) {
                break;
            }
            mob.setNestRole(NestRole.NONE);            // 綱を外す(巣の外の相手も狙えるように)
            mob.setLeavingNest(true);                  // 坑口まで道しるべを辿る
            mob.setData(ModAttachments.HUNT_TARGET, target.getUUID().toString());
            mob.setPersistenceRequired();
            sent++;
        }
        return sent;
    }

    /** その巣に居る住人(ロード済みのものだけ)。 */
    private static List<AbstractOvergrownInvaderEntity> residentsOf(ServerLevel level, NestData.Nest nest) {
        AABB box = new AABB(nest.origin()).inflate(NEST_BOX, NEST_BOX, NEST_BOX);
        List<AbstractOvergrownInvaderEntity> out = new ArrayList<>();
        for (AbstractOvergrownInvaderEntity mob :
                level.getEntitiesOfClass(AbstractOvergrownInvaderEntity.class, box, AbstractOvergrownInvaderEntity::isAlive)) {
            if (mob.nestRole() == NestRole.RESIDENT && nest.origin().equals(mob.nestHome())) {
                out.add(mob);
            }
        }
        return out;
    }

    private NestReinforcements() {
    }
}
