package com.yayuy.overgrownvegetation.survival.room;

import com.yayuy.overgrownvegetation.data.RoomSpec;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * 部屋をたどる(2026-11-13 段 2a、決定 12)。立っているマスから空気が流れるマスを幅優先でたどり、
 * 外(空が開けた所・バリア)へ出ずに尽きれば部屋。上限({@link RoomSpec#maxCells()}・{@link RoomSpec#maxSpan()})を超えたら部屋ではない。
 *
 * <p>ふつうは最初のすき間で止める(速い)。{@code full} = すき間を全部集める(開発用のコマンドと、後の「すき間の数で漏れる」案 (b) の口)。
 * 世界の読み方は {@link World} を差し替えられる(GameTest はテスト場より大きな部屋を素の Java の格子で試す)。
 */
public final class RoomFinder {

    /** 世界の読み口。 */
    public interface World {
        /** マスの印({@link AirCells})。読めない所は {@link AirCells#UNLOADED}。 */
        byte cell(int x, int y, int z);

        /** そのマスの上が空まで開けているか(外)。 */
        boolean openSky(int x, int y, int z);
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};
    private static final int[] AXIS = {0, 0, 1, 1, 2, 2};
    /** 開発用のすき間の一覧の上限。 */
    public static final int MAX_GAPS = 32;

    private RoomFinder() {
    }

    /**
     * 調べ始めるマス: 足元(またはそのまま)が何もふさがない空気ならそこ、足元がハーフ・床板などなら 1 つ上、どちらも駄目なら入れるほう。
     * 足元を渡す呼び手(暴露量)と頭を渡す呼び手(画面の霞)が同じ部屋に着くように。入れなければ null。
     */
    public static BlockPos start(World w, BlockPos pos) {
        byte here = w.cell(pos.getX(), pos.getY(), pos.getZ());
        if (here == AirCells.UNLOADED) return null;
        if ((here & AirCells.SOLID) == 0) return pos;
        BlockPos up = pos.above();
        byte above = w.cell(up.getX(), up.getY(), up.getZ());
        if (above != AirCells.UNLOADED && (above & AirCells.SOLID) != AirCells.SOLID) return up;
        if ((here & AirCells.SOLID) != AirCells.SOLID) return pos;
        return null;
    }

    /** {@code start} から部屋をたどる。 */
    public static RoomScan scan(World w, BlockPos start, RoomSpec spec, boolean full) {
        int sx = start.getX(), sy = start.getY(), sz = start.getZ();
        byte s0 = w.cell(sx, sy, sz);
        if (s0 == AirCells.UNLOADED) return RoomScan.simple(RoomScan.Status.UNLOADED, start);
        if ((s0 & AirCells.SOLID) == AirCells.SOLID) return RoomScan.simple(RoomScan.Status.SOLID, start);
        if ((s0 & AirCells.OUTSIDE) != 0 || w.openSky(sx, sy, sz)) return RoomScan.simple(RoomScan.Status.OPEN, start);

        LongOpenHashSet cells = new LongOpenHashSet(256);
        LongOpenHashSet outside = full ? new LongOpenHashSet() : null;
        LongLinkedOpenHashSet gaps = new LongLinkedOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long s = BlockPos.asLong(sx, sy, sz);
        cells.add(s);
        queue.enqueue(s);
        int minX = sx, maxX = sx, minY = sy, maxY = sy, minZ = sz, maxZ = sz;
        int built = (s0 & AirCells.COLLIDES) != 0 && (s0 & AirCells.NATURAL) == 0 ? 1 : 0;
        int boundary = 0;
        long escape = Long.MIN_VALUE;
        RoomScan.Status status = null;

        search:
        while (!queue.isEmpty()) {
            long c = queue.dequeueLong();
            int cx = BlockPos.getX(c), cy = BlockPos.getY(c), cz = BlockPos.getZ(c);
            byte cc = w.cell(cx, cy, cz);
            for (int d = 0; d < 6; d++) {
                int nx = cx + DX[d], ny = cy + DY[d], nz = cz + DZ[d];
                long n = BlockPos.asLong(nx, ny, nz);
                if (cells.contains(n) || (outside != null && outside.contains(n))) continue;
                int axis = AXIS[d];
                if (AirCells.blocks(cc, axis)) {
                    // 自分のマスのブロック(板ガラス・ドア・ハーフ)がこの向きをふさぐ: 境界はこのマス自身(入るときに数えた)
                    boundary++;
                    continue;
                }
                byte nc = w.cell(nx, ny, nz);
                if (nc == AirCells.UNLOADED) {
                    status = RoomScan.Status.UNLOADED;
                    break search;
                }
                if ((nc & AirCells.OUTSIDE) == 0 && AirCells.blocks(nc, axis)) {
                    boundary++;
                    if ((nc & AirCells.COLLIDES) != 0 && (nc & AirCells.NATURAL) == 0) built++;
                    continue;
                }
                if ((nc & AirCells.OUTSIDE) != 0 || w.openSky(nx, ny, nz)) {
                    // 外へ出た: c がすき間の内側
                    if (escape == Long.MIN_VALUE) escape = n;
                    if (gaps.size() < MAX_GAPS) gaps.add(c);
                    if (outside == null) {
                        status = RoomScan.Status.OPEN;
                        break search;
                    }
                    outside.add(n);
                    continue;
                }
                cells.add(n);
                if ((nc & AirCells.COLLIDES) != 0 && (nc & AirCells.NATURAL) == 0) built++;
                minX = Math.min(minX, nx);
                maxX = Math.max(maxX, nx);
                minY = Math.min(minY, ny);
                maxY = Math.max(maxY, ny);
                minZ = Math.min(minZ, nz);
                maxZ = Math.max(maxZ, nz);
                if (cells.size() > spec.maxCells()
                        || maxX - minX >= spec.maxSpan() || maxY - minY >= spec.maxSpan() || maxZ - minZ >= spec.maxSpan()) {
                    status = RoomScan.Status.TOO_BIG;
                    break search;
                }
                queue.enqueue(n);
            }
        }
        if (status == null) {
            if (!gaps.isEmpty()) status = RoomScan.Status.OPEN;
            else if (spec.requireBuiltWall() && built == 0) status = RoomScan.Status.NATURAL;
            else status = RoomScan.Status.ROOM;
        }
        return new RoomScan(status, start, cells, new LongArrayList(gaps), escape, built, boundary,
                minX, minY, minZ, maxX, maxY, maxZ);
    }
}
