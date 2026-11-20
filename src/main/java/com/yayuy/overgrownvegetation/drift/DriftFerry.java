package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 寄生種を乗せて運ぶ(島の拠点への襲撃、2026-11-20)。
 *
 * <p>司令塔が「拠点が海を越えないと届かない所にある」と分かったときに呼ぶ口。払いは森の予算(司令塔の作り直し ①)。
 * 送り込み・襲撃の出し方({@code director/RaidSpawns} など)からのつなぎは drift-mimic-impl-report.md を見る。
 *
 * <p>使い方: {@link #route} で出す水の列と着く岸の列を決め、{@link #ferry} に乗せる Mob を渡す。1 体に {@link DriftTable.Ferry#seats} まで乗り、
 * 乗りきらない分は返す(呼ぶ側がもう 1 体流す)。払いは {@link DriftCosts#ferryPrice}(1 体を流す分 + 乗せる Mob の値段 × 司令塔の表の driftPassengerFactor)。
 */
public final class DriftFerry {

    private DriftFerry() {
    }

    /** 出す水の列と着く岸の列(どちらも y は水面の段)。 */
    public record Route(BlockPos launch, BlockPos landing) {
    }

    /**
     * 陸の {@code from} から拠点 {@code to} へ、海を越える道があるか。まっすぐの線をたどり、深い水の最初の列を出す所、
     * その先の最初の陸の列を着く所にする。水が {@link DriftTable.Launch#minOpenWater} 列以上続かない(歩いて行ける)・
     * 読み込まれていない列に当たった、なら null。{@code wy} は水面の段。
     */
    @Nullable
    public static Route route(ServerLevel level, BlockPos from, BlockPos to, int wy) {
        return route(level, from, to, wy, DesignData.drift().drift.minDepth);
    }

    /** {@link #route} の深さを渡す版。出す所は、線の上で最初に {@code minDepth} 以上の深さになる水の列(浅瀬からは出さない)。 */
    @Nullable
    public static Route route(ServerLevel level, BlockPos from, BlockPos to, int wy, int minDepth) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ(), l = Math.hypot(dx, dz);
        if (l < 1) return null;
        dx /= l;
        dz /= l;
        BlockPos launch = null, landing = null, deep = null;
        int water = 0, run = 0;
        for (int r = 0; r <= (int) l; r++) {
            int x = Mth.floor(from.getX() + 0.5D + dx * r), z = Mth.floor(from.getZ() + 0.5D + dz * r);
            DriftShore.Col c = DriftShore.column(level, x, z, wy);
            if (c == DriftShore.Col.UNKNOWN) return null;
            if (c == DriftShore.Col.WATER) {
                if (launch == null) launch = new BlockPos(x, wy, z);
                if (deep == null && DriftShore.depth(level, x, z, wy, minDepth) >= minDepth) deep = new BlockPos(x, wy, z);
                run++;
                water = Math.max(water, run);
            } else {
                run = 0;
                if (deep != null && landing == null) landing = new BlockPos(x, wy, z); // 深い水を渡った先の最初の陸
            }
        }
        if (launch == null || deep == null || water < DesignData.drift().launch.minOpenWater) return null;
        if (landing == null) landing = new BlockPos(to.getX(), wy, to.getZ());
        return new Route(deep, landing);
    }

    /**
     * {@code route} で 1 体流し、{@code riders} を乗せて岸へ運ぶ。払えなければ null(誰も乗せない)。乗れなかった Mob は {@code riders} に残る
     * (乗った Mob はリストから外す)。
     */
    @Nullable
    public static DriftMimicEntity ferry(ServerLevel level, Route route, List<? extends Mob> riders) {
        int seats = Math.max(0, DesignData.drift().ferry.seats);
        int n = Math.min(seats, riders.size());
        double price = DriftCosts.ferryPrice(riders.subList(0, n));
        if (!DriftCosts.pay(level, price, "drift_ferry")) return null;
        DriftMimicEntity e = DriftLaunches.spawnAt(level, route.launch());
        if (e == null) return null;
        com.yayuy.overgrownvegetation.director.ForestEconomy.markPaid(e, DriftCosts.launchPrice()); // 乗せた Mob の分は乗せた Mob の払いのまま(呼ぶ側)
        e.ferryTo(route.landing());
        for (int i = 0; i < n; i++) {
            Mob m = riders.remove(0);
            m.setPersistenceRequired();
            m.startRiding(e, true);
        }
        DriftStats.ferried++;
        return e;
    }
}
