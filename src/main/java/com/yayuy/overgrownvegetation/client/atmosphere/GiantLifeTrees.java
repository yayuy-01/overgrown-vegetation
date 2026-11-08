package com.yayuy.overgrownvegetation.client.atmosphere;

import java.util.ArrayList;
import java.util.List;

/**
 * 巨大樹のまわりの生き物と音(11-06 改善案 ⑤)が使う、{@link GiantTrees} の一覧の小さな手伝い(形を写し終えた木だけ・近い順)。
 */
public final class GiantLifeTrees {

    private GiantLifeTrees() {
    }

    /** (x, z) から水平 maxDistance 以内の、形を写し終えた巨大樹を近い順に最大 max 本。 */
    public static List<GiantTrees.Info> nearest(double x, double z, double maxDistance, int max) {
        List<GiantTrees.Info> out = new ArrayList<>();
        for (GiantTrees.Info t : GiantTrees.all()) {
            if (t.scanned() && distSqr(t, x, z) <= maxDistance * maxDistance) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Double.compare(distSqr(a, x, z), distSqr(b, x, z)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public static double distSqr(GiantTrees.Info t, double x, double z) {
        double dx = x - t.axisX();
        double dz = z - t.axisZ();
        return dx * dx + dz * dz;
    }

    /** 木ごとに決まった数(根元の位置から)。 */
    public static long key(GiantTrees.Info t) {
        return ((long) t.originX() << 32) ^ (t.originZ() & 0xFFFFFFFFL);
    }
}
