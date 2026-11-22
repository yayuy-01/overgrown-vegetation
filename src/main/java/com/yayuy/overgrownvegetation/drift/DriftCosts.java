package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestEconomy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * 漂流する擬態 Mob の払い(2026-11-20)。森の予算(司令塔の作り直し ① の {@link ForestDirector#tryPay})から払う。
 *
 * <p>値段は司令塔の値段の表({@code director.json} の economy)の行:
 * <ul>
 *   <li>1 体を流す = {@code driftCost} × 苗床の倍率({@link ForestEconomy#priceFactor})。</li>
 *   <li>乗せて運ぶ = 1 体を流す分 + 乗せる Mob それぞれの値段({@link ForestDirector#spawnCost})× {@code driftPassengerFactor}。
 *       島を拠点にすると、襲撃の予算がこの分かさむ(ユーザー 11-20)。</li>
 * </ul>
 * 払った額は個体に書く({@link ForestEconomy#markPaid})—— 森の中で倒されたら一部が戻る・沖で沈められたら戻らない(① の決まりのまま)。
 * 司令塔が止まっている({@code director.enabled} が false)ときは払わずに通す。
 *
 * <p>{@link #payer} はテストで差し替えられる口(払った額を数える・断る)。
 */
public final class DriftCosts {

    private DriftCosts() {
    }

    /** 払い手。払えたら true(払えなければ流さない)。理由は {@code "drift_launch"}・{@code "drift_ferry"}。 */
    @FunctionalInterface
    public interface Payer {
        boolean pay(ServerLevel level, double amount, String reason);
    }

    /** 払った額の合計(確かめ用)。 */
    public static double paidTotal;

    /** 森の予算から払う。 */
    public static final Payer FOREST = (level, amount, reason) -> {
        if (!DesignData.director().enabled) return true;
        if (!ForestDirector.get(level).tryPay(level, amount)) return false;
        paidTotal += amount;
        return true;
    };

    /** 今の払い手。テストは差し替えて戻す。 */
    public static volatile Payer payer = FOREST;

    /** 1 体を流す値段。 */
    public static double launchPrice() {
        return ForestEconomy.table().driftCost * ForestEconomy.priceFactor();
    }

    /** 乗せる分の値段(乗せる Mob それぞれの値段 × driftPassengerFactor)。 */
    public static double riderPrice(List<? extends Entity> riders) {
        double sum = 0.0D;
        for (Entity e : riders) sum += riderPrice(e.getType());
        return sum;
    }

    public static double riderPrice(EntityType<?> type) {
        return ForestDirector.spawnCost(type) * ForestEconomy.table().driftPassengerFactor;
    }

    /** 乗せて運ぶ値段: 1 体を流す分 + 乗せる分。 */
    public static double ferryPrice(List<? extends Entity> riders) {
        return launchPrice() + riderPrice(riders);
    }

    static boolean pay(ServerLevel level, double amount, String reason) {
        if (amount <= 0) return true;
        return payer.pay(level, amount, reason);
    }
}
