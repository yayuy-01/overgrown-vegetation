package com.yayuy.overgrownvegetation.nest;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 巣の<b>道しるべの木</b>を辿るための計算(2026-09-20 に作り直し)。
 *
 * <h2>なぜ道しるべが要るか</h2>
 * <p>アルボラの経路探索は壁のぼり用({@code WallClimberNavigation})で、
 * <b>目的地までの経路が見つからないとそのまま直進する</b>([[wallclimber-nav-hidden-beeline]])。
 * 巣の奥から地上のプレイヤーを直に指すと経路が出ず、坑道の壁を登って天井に張り付いたまま
 * 暴れ続ける。
 *
 * <h2>なぜ「木」なのか(1 本の線では駄目だった)</h2>
 * <p>最初の版は<b>幹に沿った 1 本の線</b>だけを道しるべにしていた。枝や側室に居る個体は
 * そこから遠く、「12 マス先の中継点」を直線で置いて歩かせていたので、
 * <b>中継点が岩の中に落ちる</b>。経路が出ないので結局直進 —— 天井に張り付く元の症状がそのまま残った
 * (2026-09-20 のユーザー報告「巣の構造を理解できておらず、出入り口を使おうとしない」)。
 *
 * <p>いまは<b>幹・枝・側室への通路・部屋の中心</b>のすべてに道しるべを落とし、
 * 1 つ 1 つが「坑口へ 1 歩戻る先」({@code parent})を持つ木にしてある。
 * どこに居ても、いちばん近い道しるべから<b>親をたぐるだけで坑口に出られる</b>。
 * 逆にたどれば任意の部屋へ入れる。1 区間は 3 マスほどなので経路探索がまず失敗しない。
 *
 * <p>根(添字 0)が坑口。{@code parents[0] == -1}。
 */
public final class NestRoute {

    /** 道しるべに「着いた」とみなす距離。 */
    public static final double ARRIVE = 2.5D;
    /** 着いたとみなす高さの差。坑道は斜めなので水平より緩く見る。 */
    public static final double ARRIVE_Y = 3.0D;

    /** {@code pos} にいちばん近い道しるべの添字。道しるべが無ければ -1。 */
    public static int nearestIndex(List<BlockPos> route, BlockPos pos) {
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double sq = route.get(i).distSqr(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        return best;
    }

    /**
     * {@code pos} が道しるべのどれかから {@code radius} 以内にあるか
     * (= だいたい坑道の中に居る)。
     */
    public static boolean onRoute(List<BlockPos> route, BlockPos pos, double radius) {
        double rr = radius * radius;
        for (BlockPos way : route) {
            if (way.distSqr(pos) <= rr) {
                return true;
            }
        }
        return false;
    }

    /** {@code from} から根(坑口)までの添字。先頭が {@code from}、末尾が 0。 */
    public static IntList toRoot(int[] parents, int from) {
        IntArrayList out = new IntArrayList();
        int at = from;
        int guard = 0;
        while (at >= 0 && at < parents.length && guard++ < parents.length + 1) {
            out.add(at);
            at = parents[at];
        }
        return out;
    }

    /**
     * {@code from} から {@code to} まで木の上を歩く道(添字の並び)。先頭は {@code from} の<b>次</b>、
     * 末尾が {@code to}。同じ場所なら空。
     *
     * <p>木なので「両方から根まで辿って、共通の先祖で折り返す」だけで最短になる。
     */
    public static IntList between(int[] parents, int from, int to) {
        IntArrayList out = new IntArrayList();
        if (from < 0 || to < 0 || from >= parents.length || to >= parents.length) {
            return out;
        }
        IntList up = toRoot(parents, from);
        IntList down = toRoot(parents, to);
        // 共通の先祖を探す(down 側にも現れる、いちばん手前の up 側の点)
        int meetUp = -1;
        int meetDown = -1;
        outer:
        for (int i = 0; i < up.size(); i++) {
            for (int j = 0; j < down.size(); j++) {
                if (up.getInt(i) == down.getInt(j)) {
                    meetUp = i;
                    meetDown = j;
                    break outer;
                }
            }
        }
        if (meetUp < 0) {
            return out;                                // 別の木(壊れた記録)。歩かせない
        }
        for (int i = 1; i <= meetUp; i++) {
            out.add(up.getInt(i));                     // 登る(坑口へ)
        }
        for (int j = meetDown - 1; j >= 0; j--) {
            out.add(down.getInt(j));                   // 下る(目的地へ)
        }
        return out;
    }

    /**
     * {@code mob} が {@code way} に着いたか。坑道は斜めなので、上下は水平より緩く見る。
     */
    public static boolean reached(net.minecraft.world.entity.Entity mob, BlockPos way) {
        double dx = mob.getX() - (way.getX() + 0.5D);
        double dy = mob.getY() - way.getY();
        double dz = mob.getZ() - (way.getZ() + 0.5D);
        return dx * dx + dz * dz <= ARRIVE * ARRIVE && Math.abs(dy) <= ARRIVE_Y;
    }

    private NestRoute() {
    }
}
