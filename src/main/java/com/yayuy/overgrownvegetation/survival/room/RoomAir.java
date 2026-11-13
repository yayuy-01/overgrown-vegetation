package com.yayuy.overgrownvegetation.survival.room;

import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import net.minecraft.server.level.ServerLevel;

/**
 * 部屋の中の空気(2026-11-13 段 2a)。部屋の中の濃度 = 外の値 × {@link #factor} + {@link #extraPercent}。
 *
 * <ul>
 *     <li><b>段 3c の口</b>(汚れた装備のまま閉じた部屋に入ると部屋が汚れる): {@link #contaminate} と {@link #extraPercent}。今は中身が空(0)。
 *     状態は覚え({@link RoomScan})に持たせない —— ドアの開け閉めで作り直されるため。{@link RoomScan#id()}(中のいちばん小さい座標)をキーに、
 *     次元ごとの SavedData に持つ想定。部屋がつながった・分かれたときの汚れの分け方は段 3c で決める。</li>
 *     <li><b>すき間の扱いの口</b>: 今は (a) すき間があれば部屋でなくなるだけ【仮】。(b) すき間の数・大きさで少しずつ漏れる案にするなら、
 *     {@link RoomCache} に full のたどり({@link RoomFinder#scan} の full = true)で数を持たせ、ここで部屋でない結果にも倍率を返す。</li>
 * </ul>
 */
public final class RoomAir {

    private RoomAir() {
    }

    /** 部屋の中の倍率。部屋でなければ 1(今は (a))。 */
    public static double factor(RoomScan room, SporeFieldTable t) {
        return room != null && room.isRoom() ? t.roomFactor() : 1.0D;
    }

    /** 部屋に積もった汚れの上乗せ(%)。段 3c まで 0。 */
    public static double extraPercent(ServerLevel level, RoomScan room) {
        return 0.0D;
    }

    /** 部屋を汚す(段 3c の口。今は何もしない)。 */
    public static void contaminate(ServerLevel level, RoomScan room, double percent) {
    }
}
