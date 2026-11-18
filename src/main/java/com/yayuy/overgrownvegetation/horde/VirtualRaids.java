package com.yayuy.overgrownvegetation.horde;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PerfTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大規模レイドから仮想の群れを出す口(2026-11-18 大群の軽量化 A。ユーザー決定: 大規模レイドの G4・G5 の一部を、拠点から遠く(読み込み範囲の外)に
 * 仮想の群れで出して歩かせる)。{@code LargeRaids.step} が予算の確かめの後に {@link #maybeSend} を呼び、{@code LargeRaids.end} が
 * {@link VirtualHordes#retreat} を呼ぶ(G1 の作成のセッション了承 11-18)。
 *
 * <ul>
 *     <li>{@code virtualHorde.raidIntervalTicks} に 1 回、割合 {@code raidShare} で群れを 1 つ出す。頭数は {@code raidSwarmMin}〜{@code raidSwarmMax}
 *     (仮想の上限 {@code virtualCap} まで)。1 体ずつ今の G4・G5 と同じ値段を払う(払えなければそこまで)。値段は出した時に払い、本物化では払わない。</li>
 *     <li>置く所: 拠点から {@code raidSpawnMin}〜{@code raidSpawnMax} ブロック、角度は大規模レイドの湧かせる所と同じ決め方(森の方角か弱い方角)。</li>
 *     <li>仮想は大規模レイドの maxAlive に数えない。本物になった分は印 {@link LargeRaids#ATTACKER_TAG} が付くので数える。本物化は
 *     maxAlive に空きがある分だけ({@link #room})。空きが無い間、群れは待つ。</li>
 * </ul>
 */
public final class VirtualRaids {

    /** 確かめ用: 出した群れ・頭数。 */
    public static long sentSwarms, sentHeads;
    private static long lastSend = Long.MIN_VALUE;

    private VirtualRaids() {
    }

    /**
     * 群れのうち、その襲撃の同時の上限で今いくつ本物にしてよいか。大規模レイドの群れは maxAlive − 今の攻め手、ほかは上限なし。
     */
    public static int room(ServerLevel level, VirtualHordes.Swarm s) {
        if (!LargeRaids.ATTACKER_TAG.equals(s.extraTag)) return Integer.MAX_VALUE;
        BaseMemory.Base b = BaseMemory.get(level.getServer()).byId(s.baseId);
        return b == null ? 0 : Math.max(0, LargeRaids.maxAlive() - LargeRaids.alive(level, b));
    }

    /** 大規模レイドの本番の 1 回ぶん(呼ぶ側で予算・ピースフルの門を通った後)。出した頭数を返す(本物を出した数ではない)。 */
    public static int maybeSend(ServerLevel level, BaseMemory.Base b, RandomSource random) {
        if (!VirtualHordes.enabled()) return 0;
        PerfTable t = DesignData.perf();
        long now = level.getGameTime();
        if (now - lastSend < t.virtualRaidIntervalTicks) return 0;
        lastSend = now;
        if (random.nextDouble() >= t.virtualRaidShare) return 0;
        VirtualHordes h = VirtualHordes.get(level.getServer());
        int k = Math.min(h.room(), t.virtualRaidSwarmMin + random.nextInt(Math.max(1, t.virtualRaidSwarmMax - t.virtualRaidSwarmMin + 1)));
        if (k < t.virtualRaidSwarmMin) return 0;
        ForestDirector d = ForestDirector.get(level);
        Map<EntityType<?>, Integer> heads = new LinkedHashMap<>();
        int n = 0;
        for (int i = 0; i < k * 3 && n < k; i++) {
            EntityType<?> type = DirectorHooks.pickPlant(level, random, 0);
            if (type == null) break;
            if (!VirtualHordes.allowed(type)) continue; // 空の種などは仮想にしない(払わない)
            double price = ForestDirector.stagePrice(ForestDirector.spawnCost(type),
                    DirectorGates.strength(level, DirectorHooks.gateOf(DirectorHooks.groupOf(type))));
            if (!d.tryPay(level, price)) break;
            heads.merge(type, 1, Integer::sum);
            n++;
        }
        if (n == 0) return 0;
        double angle = LargeRaids.spotAngle(level, b, random) + Math.toRadians((random.nextDouble() * 2.0D - 1.0D) * DesignData.director().largeRaid.spawnSpreadDeg);
        double dist = t.virtualRaidSpawnMin + random.nextDouble() * (t.virtualRaidSpawnMax - t.virtualRaidSpawnMin);
        double x = b.centerX() + Math.cos(angle) * dist, z = b.centerZ() + Math.sin(angle) * dist;
        double y = VirtualHordes.surfaceY(level, x, z);
        VirtualHordes.Swarm s = h.spawn(x, Double.isNaN(y) ? 64.0D : y, z, b.id(), b.centerX(), b.centerZ(), LargeRaids.ATTACKER_TAG, heads);
        if (s == null) return 0;
        sentSwarms++;
        sentHeads += s.size();
        return s.size();
    }
}
