package com.yayuy.overgrownvegetation.survival.room;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

/**
 * 1 回たどった結果(2026-11-13 段 2a)。覚え({@link RoomCache})に入り、中や境界のブロックが変われば捨てられる。
 * ドアを開け閉めするたびに作り直されるので、部屋に積もる状態(段 3c の汚れ)はここに持たせず、{@link #id()} をキーに {@link RoomAir} で持つ。
 *
 * @param cells    たどった空気のマス(部屋なら部屋の中全部。部屋でなければ途中まで)
 * @param gaps     すき間の内側のマス(外へ出た 1 つ手前。ふつうは最初の 1 つ、full なら最大 {@link RoomFinder#MAX_GAPS})
 * @param escape   最初に出た外のマス(無ければ Long.MIN_VALUE)
 * @param built    境界(と中)の「人の手のブロック」の数
 * @param boundary ふさいだ面の数
 */
public record RoomScan(Status status, BlockPos start, LongSet cells, LongList gaps, long escape, int built, int boundary,
                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public enum Status {
        /** 閉じた部屋。 */
        ROOM("閉じた部屋"),
        /** すき間があって外(空・バリア)へ抜ける。 */
        OPEN("外へ抜ける"),
        /** 上限を超えた(大きな空間・洞窟の続き)。 */
        TOO_BIG("大きすぎる"),
        /** 閉じているが境界が自然のブロックだけ(洞窟の袋)。 */
        NATURAL("自然の空洞"),
        /** 読み込まれていないチャンクに当たった。 */
        UNLOADED("読み込まれていない所に当たった"),
        /** 立っている所がブロックの中。 */
        SOLID("ブロックの中");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    static RoomScan simple(Status status, BlockPos start) {
        int x = start.getX(), y = start.getY(), z = start.getZ();
        return new RoomScan(status, start, new LongOpenHashSet(), new LongArrayList(), Long.MIN_VALUE, 0, 0, x, y, z, x, y, z);
    }

    public boolean isRoom() {
        return status == Status.ROOM;
    }

    public int volume() {
        return cells.size();
    }

    /**
     * 部屋を見分ける印: 中のマスのうち座標がいちばん小さいもの(下 → 北 → 西)。中の形がその角で変わらない限り、ドアを開け閉めしても同じ。
     * 部屋でなければ Long.MIN_VALUE。
     */
    public long id() {
        if (!isRoom()) return Long.MIN_VALUE;
        long best = Long.MIN_VALUE;
        int by = Integer.MAX_VALUE, bz = Integer.MAX_VALUE, bx = Integer.MAX_VALUE;
        for (LongIterator it = cells.iterator(); it.hasNext(); ) {
            long c = it.nextLong();
            int y = BlockPos.getY(c), z = BlockPos.getZ(c), x = BlockPos.getX(c);
            if (y < by || (y == by && (z < bz || (z == bz && x < bx)))) {
                by = y;
                bz = z;
                bx = x;
                best = c;
            }
        }
        return best;
    }
}
