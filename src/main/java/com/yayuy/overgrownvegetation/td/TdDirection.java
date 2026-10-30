package com.yayuy.overgrownvegetation.td;

import net.minecraft.network.chat.Component;

/**
 * TD の侵攻が<b>攻めてくる向き</b>(2026-09-26 のユーザー指定「いつも全周囲からではなく、北や西など一方向から攻めてくる場合も。
 * 過繁茂したバイオームがある方向からになりやすく。全周囲 4 割・特定の方向 6 割。コマンドで向きを指定できるように
 * (例: /ov td start north)」)。{@link #AROUND} = 全方位、ほかは 8 方位。
 *
 * <p>向き(ラジアン)は TD のほかの計算と同じくターゲットから見た {@code atan2(dz, dx)}: 東 0、南 π/2(+Z)、西 π、北 −π/2(−Z)。
 * 名前({@link #key})はコマンドの引数と保存に使う。
 */
public enum TdDirection {
    AROUND("around", "全方位", Double.NaN),
    EAST("east", "東", 0.0D),
    SOUTHEAST("southeast", "南東", Math.PI * 0.25D),
    SOUTH("south", "南", Math.PI * 0.5D),
    SOUTHWEST("southwest", "南西", Math.PI * 0.75D),
    WEST("west", "西", Math.PI),
    NORTHWEST("northwest", "北西", -Math.PI * 0.75D),
    NORTH("north", "北", -Math.PI * 0.5D),
    NORTHEAST("northeast", "北東", -Math.PI * 0.25D);

    /** 8 方位を向きの順(東から +Z 回り = 45 度ずつ)に。添え字 i の向き = i × π/4。 */
    static final TdDirection[] COMPASS = {EAST, SOUTHEAST, SOUTH, SOUTHWEST, WEST, NORTHWEST, NORTH, NORTHEAST};

    private final String key;
    private final String japanese;
    private final double bearing;

    TdDirection(String key, String japanese, double bearing) {
        this.key = key;
        this.japanese = japanese;
        this.bearing = bearing;
    }

    /** コマンドの引数・保存の名前(英小文字)。 */
    public String key() {
        return key;
    }

    /** 開発用の出力({@code /ov td}・ログ)に使う日本語の名前。 */
    public String japanese() {
        return japanese;
    }

    /** ターゲットから見た向き(ラジアン)。{@link #AROUND} は NaN。 */
    public double bearing() {
        return bearing;
    }

    public boolean around() {
        return this == AROUND;
    }

    /** プレイヤーに見せる名前(翻訳キー {@code td.overgrown_vegetation.direction.<key>})。 */
    public Component label() {
        return Component.translatable("td.overgrown_vegetation.direction." + key);
    }

    /** 名前から(知らない名前・空なら {@link #AROUND})。 */
    public static TdDirection byKey(String key) {
        for (TdDirection d : values()) {
            if (d.key.equals(key)) {
                return d;
            }
        }
        return AROUND;
    }

    /** 向き(ラジアン)がいちばん近い 8 方位の添え字({@link #COMPASS})。 */
    static int sectorOf(double bearing) {
        long i = Math.round(bearing / (Math.PI * 0.25D));
        return (int) Math.floorMod(i, 8L);
    }
}
