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
 * 大規模レイドから仮想の群れを出す口(2026-11-18 大群の軽量化 A。ユーザー決定: 大規模レイドの G2・G3 の一部を、拠点から遠く(読み込み範囲の外)に
 * 仮想の群れで出して歩かせる)。{@code LargeRaids.step} が予算の確かめの後に {@link #maybeSend} を呼び、{@code LargeRaids.end} が
 * {@link VirtualHordes#retreat} を呼ぶ(G1 の作成のセッション了承 11-18)。
 *
 * <ul>
 *     <li>{@code virtualHorde.raidIntervalTicks} に 1 回、割合 {@code raidShare} で群れを 1 つ出す。頭数は {@code raidSwarmMin}〜{@code raidSwarmMax}
 *     (仮想の上限 {@code virtualCap} まで)。1 体ずつ今の G2・G3 と同じ値段を払う(払えなければそこまで)。値段は出した時に払い、本物化では払わない。</li>
 *     <li>置く所: 拠点から {@code raidSpawnMin}〜{@code raidSpawnMax} ブロック、角度は大規模レイドの湧かせる所と同じ決め方(森の方角か弱い方角)。</li>
 *     <li>仮想は大規模レイドの maxAlive に数えない。本物になった分は印 {@link LargeRaids#ATTACKER_TAG} が付くので数える。本物化は
 *     maxAlive に空きがある分だけ({@link #room})。空きが無い間、群れは待つ。</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
public final class VirtualRaids {

    /** 確かめ用: 出した群れ・頭数。 */
    public static long sentSwarms, sentHeads;
    private static long lastSend = -1_000_000_000L; // Long.MIN_VALUE だと now − lastSend が桁あふれして負になり、二度と出さなかった

    private VirtualRaids() {
    }

    /** サーバーを止めたら時刻を戻す(1 人用でほかのワールドへ持ち越さない。11-18 見直し ③2)。 */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        lastSend = -1_000_000_000L;
    }

    /**
     * 群れのうち、その襲撃の同時の上限で今いくつ本物にしてよいか。大規模レイドの群れは maxAlive − 今の攻め手、ほかは上限なし。
     */
    public static int room(ServerLevel level, VirtualHordes.Swarm s, it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap counted) {
        if (!LargeRaids.ATTACKER_TAG.equals(s.extraTag)) return Integer.MAX_VALUE;
        BaseMemory.Base b = BaseMemory.get(level.getServer()).byId(s.baseId);
        if (b == null) return 0;
        // 同じ回の群れどうしは数えを使い回す(呼ぶ側が出した分を足す。11-18 見直し ③6)
        if (!counted.containsKey(s.baseId)) counted.put(s.baseId, attackers(level, b)[0]);
        return Math.max(0, LargeRaids.maxAlive() - counted.get(s.baseId));
    }

    public static int room(ServerLevel level, VirtualHordes.Swarm s) {
        if (!LargeRaids.ATTACKER_TAG.equals(s.extraTag)) return Integer.MAX_VALUE;
        BaseMemory.Base b = BaseMemory.get(level.getServer()).byId(s.baseId);
        if (b == null) return 0;
        return Math.max(0, LargeRaids.maxAlive() - attackers(level, b)[0]);
    }

    /**
     * 大規模レイドの攻め手の数 {全部, うち仮想の群れから本物になった分}。拠点のまわりの広めの箱で数える(拠点のまわりの数え方 {@code LargeRaids.alive} は
     * 半径の中だけなので、64 ブロック先で本物になった分を数えず、上限 60 に 62 体まで出た。11-18)。毎回数え直す(同じ tick に足した分も数える)。
     */
    public static int[] attackers(ServerLevel level) {
        return attackers(level, null);
    }

    /**
     * 拠点 b のまわり(拠点の数える半径 + 本物にする距離 + 32 ブロック)の攻め手。遠くではぐれた・前の襲撃の生き残りが枠を取り続けないように
     * (11-18 G1 の作成のセッションの指摘)。b が null なら世界全体(表示用)。
     */
    public static int[] attackers(ServerLevel level, @javax.annotation.Nullable BaseMemory.Base b) {
        int all = 0, virt = 0;
        double r = b == null ? 0.0D : com.yayuy.overgrownvegetation.data.DesignData.director().largeRaid.countRadius + DesignData.perf().virtualMaterializeBlocks + 32.0D;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (!(e instanceof net.minecraft.world.entity.Mob m) || !m.isAlive() || !m.getTags().contains(LargeRaids.ATTACKER_TAG)) continue;
            if (b != null && (Math.abs(m.getX() - b.centerX()) > r || Math.abs(m.getZ() - b.centerZ()) > r)) continue;
            all++;
            if (m.getTags().contains(VirtualHordes.TAG)) virt++;
        }
        return new int[]{all, virt};
    }

    /**
     * 大規模レイドが近くに本物を出してよい数(11-18 ユーザー決定 案 B): maxAlive のうち {@code raidReserveShare}(半分【仮】)は仮想の群れのために空けておき、
     * 近くに出す本物(仮想の群れから来ていない攻め手)は残りの枠の中だけ。全体でも maxAlive は超えない。仮想の群れを切っているときは枠を空けない。
     */
    public static int nearRoom(ServerLevel level, BaseMemory.Base b) {
        int max = LargeRaids.maxAlive();
        int[] a = attackers(level, b);
        int total = Math.max(0, max - a[0]);
        if (!VirtualHordes.enabled()) return total;
        int nearCap = (int) Math.floor(max * (1.0D - DesignData.perf().virtualRaidReserveShare));
        return Math.max(0, Math.min(total, nearCap - (a[0] - a[1])));
    }

    /** 大規模レイドの本番の 1 回ぶん(呼ぶ側で予算・ピースフルの門を通った後)。出した頭数を返す(本物を出した数ではない)。 */
    public static int maybeSend(ServerLevel level, BaseMemory.Base b, RandomSource random) {
        if (!VirtualHordes.enabled()) return 0;
        PerfTable t = DesignData.perf();
        long now = level.getGameTime();
        if (now < lastSend) lastSend = -1_000_000_000L; // 時刻の若いワールドへ移った(11-18 見直し ③2。追いつくまで一度も出なかった)
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
                    DirectorGates.speciesStrength(level, type));
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
