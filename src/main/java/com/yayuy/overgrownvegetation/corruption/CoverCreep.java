package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.CoverTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.function.Predicate;

/**
 * <b>這い上がる苔</b>(2026-10-31、案 H の 28。ユーザー案)の決まり。覆われたマスに接している、覆われていないブロックの露出面に、
 * 共有している辺に沿って覆いの絵の帯を描く(汚染は接触で移るので「次に覆われる所」が先に見える)。
 *
 * <ul>
 *     <li>下のマスが覆われていれば 4 側面の下辺、横のマスが覆われていればその辺に接する上面・下面・両側面の辺。上のマスは見ない(這い上がる、仮決め)</li>
 *     <li>帯が出るのは覆われたマスに<b>接して新しく置かれた</b>ブロックだけ(2026-11-01 ユーザー指定。記録は {@link CoverCreepData})。
 *     置いてから {@code cover.json} の {@code creepSeconds} 秒で段階 1 → {@link #STAGES} の絵({@code textures/block/cover/creep_<変種>_<段階>}、下の辺が接している辺)が伸びる</li>
 * </ul>
 * 描画はクライアント({@code client/CoverRenderer})、ここはサーバーの GameTest でも確かめられるように共通に置く({@link CoverLook} と同じ)。
 */
public final class CoverCreep {

    /** 隣を見る向き(上は見ない)。 */
    public static final Direction[] FROM = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private CoverCreep() {
    }

    /**
     * 覆われた隣の向きの印(ビット = {@link Direction#ordinal()})。{@code pos} 自身が覆われていれば 0。
     *
     * @param covered そのマスが覆われているか(描画では記録の写し、GameTest では記録)
     */
    public static int coveredSides(BlockPos pos, Predicate<BlockPos> covered) {
        return coveredSides(pos.getX(), pos.getY(), pos.getZ(), (x, y, z) -> covered.test(new BlockPos(x, y, z)));
    }

    /** {@link #coveredSides(BlockPos, Predicate)} の座標版(描画はセクションの中の座標で、BlockPos を作らずに引く)。 */
    public static int coveredSides(int x, int y, int z, Cells covered) {
        if (covered.covered(x, y, z)) {
            return 0;
        }
        int mask = 0;
        for (Direction d : FROM) {
            if (covered.covered(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
                mask |= 1 << d.ordinal();
            }
        }
        return mask;
    }

    /** マスが覆われているか。 */
    @FunctionalInterface
    public interface Cells {
        boolean covered(int x, int y, int z);
    }

    /** 向き {@code from} の隣が覆われているとき、面 {@code face} に帯を描くか(共有する面とその向かいの面には描かない)。 */
    public static boolean bandOnFace(Direction from, Direction face) {
        return face.getAxis() != from.getAxis();
    }

    /** 段階の数(絵 {@code textures/block/cover/creep_<変種>_<段階>} の段階 1〜8)。 */
    public static final int STAGES = 8;
    /** 絵の変種の数(1〜3)。 */
    public static final int VARIANTS = 3;

    /**
     * 置いてから {@code ageTicks} 経ったときの段階(1〜{@link #STAGES})。{@code creepSeconds} を {@link #STAGES} に分けて切り上げ(置いた直後は 1、creepSeconds で最終段)。
     */
    public static int stage(long ageTicks, CoverTable design) {
        if (design.creepSeconds() <= 0) {
            return STAGES;
        }
        double t = Math.max(0L, ageTicks) / (design.creepSeconds() * 20.0D);
        return Math.max(1, Math.min(STAGES, (int) Math.ceil(t * STAGES)));
    }

    /** マスの座標から絵の変種(1〜{@link #VARIANTS})。 */
    public static int variant(int x, int y, int z) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ z * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return 1 + Math.floorMod(h, VARIANTS);
    }
}
