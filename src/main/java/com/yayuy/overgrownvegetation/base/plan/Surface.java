package com.yayuy.overgrownvegetation.base.plan;

/**
 * 地面の仕上げ(2026-10-01、10-02 に浄化圏の形に合わせて入れ替え)。数が大きいほど上に塗る(道が砂利の上を通る、など)。
 * 基地の中の地面は砂利(前線基地の定番。木が生えない)、道はアスファルト(ユーザー指定の道のブロック)。
 */
public enum Surface {
    /** 壁の外の地面(手を入れない)。 */
    NATURAL(States.AIR),
    /** 基地の中の既定の地面。 */
    GRAVEL("minecraft:gravel"),
    /** 外周の内側の巡回路と、建物の列のあいだの路地(踏み固めた土)。 */
    PATH("minecraft:coarse_dirt"),
    /** 倉庫・工廠の前の荷さばき場・整備場(コンクリートの前庭)。 */
    APRON("minecraft:light_gray_concrete"),
    /** 閲兵場と、除去塔の広場。 */
    PLAZA("minecraft:smooth_stone"),
    /** 閲兵場の芝(広場の上に塗る)。 */
    LAWN("minecraft:grass_block[snowy=false]"),
    /** 駐車場・車両の屋根の下。 */
    PARKING("overgrown_vegetation:asphalt"),
    /** 道(大通り・浄化圏の通り・つなぎの道)。 */
    ROAD("overgrown_vegetation:asphalt"),
    /** 道の中央線。 */
    ROAD_LINE("minecraft:yellow_concrete");

    /** 地面の一番上に置くブロック。 */
    public final String top;

    Surface(String top) {
        this.top = top;
    }
}
