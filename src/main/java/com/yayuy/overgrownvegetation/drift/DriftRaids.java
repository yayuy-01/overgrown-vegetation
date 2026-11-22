package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 島の拠点への襲撃: 流れ木もどきに寄生種を乗せて運ぶ(2026-11-20。ユーザー決定「島に拠点を構えたら、この Mob に Mob を乗せて襲撃する」)。
 *
 * <p>司令塔の襲撃({@code director/LargeRaids}・{@code BaseRaids})は、拠点のまわりの見通せない陸の地面に攻め手を湧かせる。
 * 拠点が島にあるとそこが見つからず、今までは何も出せなかった。そのとき({@link #ferry})、次の順で運ぶ:
 * <ol>
 *   <li>拠点のまわりの輪(襲撃の湧かせる距離)の多くが水なら、島とみなす({@link #acrossWater})。</li>
 *   <li>輪の上の深い水(流れ木もどきの要る深さ)から拠点へ、深い水を渡る道({@link DriftFerry#route})を探す。</li>
 *   <li>攻め手を流れ木もどきの上に湧かせ(1 体に {@link DriftTable.Ferry#seats} まで)、岸へ運んで降ろす。降りた攻め手は拠点へ歩く。</li>
 * </ol>
 * 払い: 攻め手の値段は今までどおり呼ぶ側の値段。そこへ乗せる分 = 流れ木もどきの値段 + 乗せる攻め手の値段 × driftPassengerFactor
 * ({@link DriftCosts#ferryPrice})を足す。呼ぶ側の財布(PurseScope)の中で払うので、島の拠点ほど同じ予算で出せる数が減る
 * (ユーザー「島を拠点にすると襲撃の予算の使い道が増え、拠点が強くなる」)。
 */
public final class DriftRaids {

    private DriftRaids() {
    }

    /** 島とみなす、輪の上の水の割合【仮】。 */
    public static final double WATER_SHARE = 0.6D;
    /** 輪の上を見る点の数。 */
    static final int RING_SAMPLES = 24;

    /** 確かめ用: 輪の半径・水面の段・要る深さを差し替える(テスト場は狭く浅く、海面 63 ではない)。null で戻す。 */
    @Nullable
    public static int[] ringForTests;

    /** 確かめ用: 運んだ回数。 */
    public static int ferried;

    /** 確かめ用: 流した流れ木もどきを受け取る(池に合わせて体を縮めるなど)。 */
    @Nullable
    public static Consumer<DriftMimicEntity> onFerryForTests;

    /**
     * 拠点 (cx, cz) へ攻め手を乗せて運ぶ。運べなければ 0(島でない・深い海から渡る道が無い・予算が足りない)。
     *
     * @param max        運びたい攻め手の数(1 回に 1 体の流れ木もどき = 席の数まで)
     * @param riderPrice 攻め手 1 体の値段(呼ぶ側の決まり。乗せる分はこの外に足す)
     * @param spawner    攻め手を 1 体湧かせる(足元の位置・乱数。置けなければ null)
     * @param onSent     運ぶと決まった攻め手ごとに(印・歩かせる先)
     * @return 運んだ攻め手の数
     */
    public static int ferry(ServerLevel level, double cx, double cz, RandomSource random, int max, double riderPrice,
                            BiFunction<BlockPos, RandomSource, ? extends Mob> spawner, Consumer<Mob> onSent) {
        if (max <= 0 || !DesignData.drift().enabled) return 0;
        int[] ring = ring(level);
        if (!acrossWater(level, cx, cz, ring)) return 0;
        DriftFerry.Route route = findRoute(level, cx, cz, ring, random);
        if (route == null) return 0;
        ForestDirector d = ForestDirector.get(level);
        int n = Math.min(max, Math.max(0, DesignData.drift().ferry.seats));
        BlockPos feet = route.launch().above();
        List<Mob> riders = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Mob m = spawner.apply(feet, random);
            if (m != null) riders.add(m);
        }
        // 予算に収まるまで減らす(攻め手の値段 + 乗せる分)
        while (!riders.isEmpty() && d.available(level) + 1.0E-9D < riders.size() * riderPrice + DriftCosts.ferryPrice(riders)) {
            riders.remove(riders.size() - 1).discard();
        }
        if (riders.isEmpty()) return 0;
        List<Mob> sent = new ArrayList<>(riders);
        DriftMimicEntity e = DriftFerry.ferry(level, route, riders); // 乗せる分を払う
        if (e != null && onFerryForTests != null) onFerryForTests.accept(e);
        if (e == null) {
            for (Mob m : sent) m.discard();
            return 0;
        }
        for (Mob m : riders) m.discard(); // 席に乗りきらなかった分(ふつうは無い)
        sent.removeIf(Mob::isRemoved);
        d.tryPay(level, sent.size() * riderPrice);
        for (Mob m : sent) onSent.accept(m);
        ferried++;
        return sent.size();
    }

    /** 輪の半径(内・外)・水面の段・要る深さ。 */
    static int[] ring(ServerLevel level) {
        if (ringForTests != null) return ringForTests;
        var t = DesignData.director().largeRaid;
        return new int[]{(int) t.spawnMinBlocks, (int) t.spawnMaxBlocks, level.getSeaLevel() - 1, DesignData.drift().drift.minDepth};
    }

    /** 拠点のまわりの輪の多くが水か(読み込まれていない点は数えない)。 */
    public static boolean acrossWater(ServerLevel level, double cx, double cz, int[] ring) {
        int water = 0, known = 0;
        double r = (ring[0] + ring[1]) * 0.5D;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double a = 2.0D * Math.PI * i / RING_SAMPLES;
            DriftShore.Col c = DriftShore.column(level, Mth.floor(cx + Math.cos(a) * r), Mth.floor(cz + Math.sin(a) * r), ring[2]);
            if (c == DriftShore.Col.UNKNOWN) continue;
            known++;
            if (c == DriftShore.Col.WATER) water++;
        }
        return known >= RING_SAMPLES / 3 && water >= known * WATER_SHARE;
    }

    /** 輪の上の深い水から拠点へ渡る道(くじを数回)。 */
    @Nullable
    static DriftFerry.Route findRoute(ServerLevel level, double cx, double cz, int[] ring, RandomSource random) {
        BlockPos center = BlockPos.containing(cx, ring[2], cz);
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double r = ring[0] + random.nextDouble() * Math.max(0, ring[1] - ring[0]);
            int x = Mth.floor(cx + Math.cos(a) * r), z = Mth.floor(cz + Math.sin(a) * r);
            BlockPos from = new BlockPos(x, ring[2], z);
            if (!level.isPositionEntityTicking(from)) continue;
            DriftFerry.Route route = DriftFerry.route(level, from, center, ring[2], ring[3]);
            if (route != null) return route;
        }
        return null;
    }
}
