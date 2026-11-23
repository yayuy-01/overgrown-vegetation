package com.yayuy.overgrownvegetation.horde;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import net.minecraft.server.level.ServerLevel;

/**
 * 大規模レイドの仮想の群れ(印 {@link LargeRaids#ATTACKER_TAG})の本物化の枠(2026-11-18 大群の軽量化 A)。
 * <p>2026-11-23 片付け E: 大規模レイドの本番から仮想の群れを出す口(maybeSend・nearRoom)は消した(11-21 から部隊は前線が出す)。
 * ここに残るのは、前の版の保存に残る群れが本物になる枠({@link #room})と、その攻め手の数え({@link #attackers})。
 */
public final class VirtualRaids {

    private VirtualRaids() {
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
}
