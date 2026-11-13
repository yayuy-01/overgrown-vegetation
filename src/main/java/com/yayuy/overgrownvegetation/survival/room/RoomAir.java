package com.yayuy.overgrownvegetation.survival.room;

import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import net.minecraft.server.level.ServerLevel;

/**
 * 部屋の中の空気(2026-11-13 段 2a)。部屋の中の濃度 = 外の値 × {@link #factor} + {@link #extraPercent}。
 *
 * <ul>
 *     <li><b>段 3c</b>(2026-11-14。汚れた装備・覆われた○○を持って閉じた部屋にいると部屋が汚れる): {@link #contaminateFrom}・{@link #extraPercent}。
 *     汚れは覚え({@link RoomScan})にも部屋の印にも持たせず、置いた場所ごとの量で {@link RoomDirt}(次元ごとの保存データ)に持つ
 *     (ドアでつながれば混ざって薄まり、分かれれば置いた側に残る)。</li>
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

    /**
     * 部屋に積もった汚れの上乗せ(%。2026-11-14 段 3c)= 部屋の中のマスにある汚れの量の和 ÷ 部屋の広さ(上限 {@code dirt.roomMaxPercent})。
     * 汚れは {@link RoomDirt}(置いた場所ごと)。
     */
    public static double extraPercent(ServerLevel level, RoomScan room) {
        if (room == null || !room.isRoom()) return 0.0D;
        RoomDirt d = RoomDirt.peek(level);
        return d == null || d.isEmpty() ? 0.0D : d.percentIn(room);
    }

    /**
     * {@code pos} のまわりに部屋の汚れがあるか(2026-11-14 段 3c)。外の値が 0 の所では部屋を調べないので、汚れのある所だけはその前に調べさせる
     * ({@code SporeConcentration#reading})。汚した部屋の箱(中の空気の外接箱)の中だけを見る(安い。基地の下の洞窟の Mob の読みでは部屋を調べない)。
     */
    public static boolean dirtNear(ServerLevel level, net.minecraft.core.BlockPos pos) {
        RoomDirt d = RoomDirt.peek(level);
        return d != null && d.anyNear(pos, 1);
    }

    /**
     * 汚れた物を持ったプレイヤーが閉じた部屋にいると、部屋を汚す(2026-11-14 段 3c、決定 32 の 2): 持っている汚れの点
     * ({@code GearDirt#carriedPoints})× {@code dirt.roomPerPoint} × 秒 × 基準の広さ ÷ 部屋の広さ だけ、立っている所に積もる(広い部屋ほど薄まる)。
     */
    public static void contaminateFrom(ServerLevel level, net.minecraft.world.entity.player.Player player, double points, double seconds) {
        if (points <= 0.0D || seconds <= 0.0D) return;
        net.minecraft.core.BlockPos pos = player.blockPosition();
        RoomScan room = RoomCache.roomAt(level, pos);
        if (room == null) return;
        com.yayuy.overgrownvegetation.data.DirtSpec s = RoomDirt.spec();
        double percent = points * s.roomPerPoint() * seconds * s.roomRefVolume() / Math.max(1, room.volume());
        long cell = room.cells().contains(pos.asLong()) ? pos.asLong() : room.start().asLong();
        RoomDirt.get(level).add(room, cell, percent);
    }

    /** 部屋を {@code percent} だけ汚す(部屋の始まりのマスに積む)。 */
    public static void contaminate(ServerLevel level, RoomScan room, double percent) {
        if (room == null || !room.isRoom()) return;
        RoomDirt.get(level).add(room, room.start().asLong(), percent);
    }

    /** 開発用の説明(立っている所の部屋の汚れ)。 */
    public static String describe(ServerLevel level, net.minecraft.core.BlockPos pos) {
        RoomScan room = RoomCache.roomAt(level, pos);
        if (room == null) return "閉じた部屋の外";
        return String.format("部屋 %d マスの汚れ %.2f%%%s", room.volume(), extraPercent(level, room),
                RoomDirt.ventCovers(level, pos) ? "(換気機の範囲 = 速く抜ける)" : "");
    }
}
