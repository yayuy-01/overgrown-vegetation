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

    /**
     * すき間のある建物の中か(2026-11-13 決定 21): たどって外へ抜けたが、境界か中に人の手のブロック(自然の壁のタグ以外)があった。
     * この中の濃度は外と同じ(×1)。自然の窪地・洞窟の口(人の手のブロックが無い)は今までの地点の補正(×1.3 など)のまま。
     */
    public static boolean leakyBuilding(RoomScan scan) {
        return scan != null && scan.status() == RoomScan.Status.OPEN && scan.built() > 0;
    }

    /**
     * 閉じた部屋の中のゆっくりたまる暴露量(%秒 / 秒。2026-11-13 決定 22、ユーザー指定): 外の値(部屋の倍率の前)が過繁茂の下支え
     * {@code reading.floorOvergrown} 以上なら {@code room.seepPerSecond}(過繁茂の部屋にこもり続けて約 2 日で感染 1)、それ以外は 0。
     * 森の中の小屋は一時の避難所で、住むには換気の機械(段 2c)が要る、という段差。
     */
    public static double seepPerSecond(double outsidePercent, SporeFieldTable t) {
        return outsidePercent >= t.floorFor(2) ? t.room().seepPerSecond() : 0.0D;
    }

    /** 部屋に積もった汚れの上乗せ(%)。段 3c まで 0。 */
    public static double extraPercent(ServerLevel level, RoomScan room) {
        return 0.0D;
    }

    /** 汚れた物を持ったプレイヤーが閉じた部屋にいると、部屋を汚す(段 3c で中身を入れる)。 */
    public static void contaminateFrom(ServerLevel level, net.minecraft.world.entity.player.Player player, double points, double seconds) {
    }

    /** 開発用の説明(立っている所の部屋の汚れ。段 3c で中身を入れる)。 */
    public static String describe(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return "部屋の汚れ: (段 3c)";
    }

    /** 部屋を汚す(段 3c の口。今は何もしない)。 */
    public static void contaminate(ServerLevel level, RoomScan room, double percent) {
    }
}
