package com.yayuy.overgrownvegetation.base.plan;

/**
 * 設計図で使うブロック状態の文字列と、向き付きの状態を作る道具(2026-10-01)。
 * 色・材料はすべて仮決め(作者が建物を描き直すまでの下地)。
 */
public final class Pal {

    public static final String AIR = States.AIR;
    public static final String KEEP = Blueprint.KEEP;

    // ---- この MOD の建材(id は改名しない) ----
    public static final String RC = "overgrown_vegetation:reinforced_concrete";
    public static final String RC_WALL = "overgrown_vegetation:reinforced_concrete_wall";
    public static final String ASPHALT = "overgrown_vegetation:asphalt";
    public static final String SANDBAGS = "overgrown_vegetation:sandbags";
    public static final String CHAIN_LINK = "overgrown_vegetation:chain_link_fence";
    public static final String STEEL = "overgrown_vegetation:steel_plate";
    /** 有刺鉄線(序盤の防衛設備のブロック。当たり判定なし、踏み込むと遅くなり傷つく)。 */
    public static final String BARBED_WIRE = "overgrown_vegetation:barbed_wire";
    public static final String JAMMING_LAMP = "overgrown_vegetation:jamming_lamp[covered=false]";
    /** 管理端末(管理棟の作戦室。基地のレベルを上げる)。 */
    public static final String TERMINAL = "overgrown_vegetation:base_terminal[facing=south]";

    // ---- バニラ ----
    public static final String LGC = "minecraft:light_gray_concrete";
    public static final String GC = "minecraft:gray_concrete";
    public static final String WC = "minecraft:white_concrete";
    public static final String YC = "minecraft:yellow_concrete";
    public static final String BLACK_C = "minecraft:black_concrete";
    public static final String GREEN_C = "minecraft:green_concrete";
    public static final String SMOOTH = "minecraft:smooth_stone";
    public static final String POLISHED_ANDESITE = "minecraft:polished_andesite";
    public static final String BIRCH = "minecraft:birch_planks";
    public static final String SPRUCE = "minecraft:spruce_planks";
    public static final String SPRUCE_LOG = "minecraft:spruce_log[axis=y]";
    public static final String FABRIC = "minecraft:white_terracotta";
    public static final String GLASS = "minecraft:gray_stained_glass_pane";
    public static final String CLEAR_GLASS = "minecraft:glass_pane";
    public static final String IRON_BARS = "minecraft:iron_bars";
    public static final String IRON_BLOCK = "minecraft:iron_block";
    public static final String DIRT = "minecraft:dirt";
    public static final String COARSE_DIRT = "minecraft:coarse_dirt";
    public static final String GRASS = "minecraft:grass_block[snowy=false]";
    public static final String GRAVEL = "minecraft:gravel";
    public static final String BARREL = "minecraft:barrel[facing=up,open=false]";
    public static final String CRAFTING = "minecraft:crafting_table";
    public static final String SMITHING = "minecraft:smithing_table";
    public static final String CARTOGRAPHY = "minecraft:cartography_table";
    public static final String BOOKSHELF = "minecraft:bookshelf";
    public static final String LANTERN = "minecraft:lantern[hanging=false,waterlogged=false]";
    public static final String LANTERN_HANGING = "minecraft:lantern[hanging=true,waterlogged=false]";
    public static final String LIGHTNING_ROD = "minecraft:lightning_rod[facing=up,powered=false,waterlogged=false]";
    public static final String CHAIN_Y = "minecraft:chain[axis=y,waterlogged=false]";
    public static final String SEA_LANTERN = "minecraft:sea_lantern";
    public static final String HAY = "minecraft:hay_block[axis=y]";
    public static final String WATER_CAULDRON = "minecraft:cauldron";

    public static String stairs(String id, Facing f, boolean top) {
        return id + "[facing=" + f.serialized() + ",half=" + (top ? "top" : "bottom") + ",shape=straight,waterlogged=false]";
    }

    public static String slab(String id, String type) {
        return id + "[type=" + type + ",waterlogged=false]";
    }

    /** 扉。{@code inward} は建物の中へ向かう向き(閉じた戸板は外側の縁に来る)。 */
    public static String door(String id, Facing inward, boolean upper, boolean rightHinge) {
        return id + "[facing=" + inward.serialized() + ",half=" + (upper ? "upper" : "lower") + ",hinge="
                + (rightHinge ? "right" : "left") + ",open=false,powered=false]";
    }

    public static String trapdoor(String id, Facing f, boolean top, boolean open) {
        return id + "[facing=" + f.serialized() + ",half=" + (top ? "top" : "bottom") + ",open=" + open
                + ",powered=false,waterlogged=false]";
    }

    /** ベッド。頭は {@code head} の向き。 */
    public static String bed(String color, Facing head, boolean headPart) {
        return "minecraft:" + color + "_bed[facing=" + head.serialized() + ",occupied=false,part=" + (headPart ? "head" : "foot") + "]";
    }

    public static String chest(Facing f) {
        return "minecraft:chest[facing=" + f.serialized() + ",type=single,waterlogged=false]";
    }

    public static String crate(Facing f) {
        return "overgrown_vegetation:ammo_crate[facing=" + f.serialized() + "]";
    }

    /** 機関砲台(序盤の防衛設備。{@code facing} = 最初に砲身を向ける方位)。置いたあと弾を入れる({@code base.build.BasePlacer})。 */
    public static String gunTurret(Facing f) {
        return "overgrown_vegetation:gun_turret[facing=" + f.serialized() + ",mode=dual,overgrown=false]";
    }

    /** 胞子除去装置(2 マスの高さ)。置いたあと燃料とフィルターを入れる({@code base.build.BasePlacer})。 */
    public static String sporePurifier(Facing f) {
        return "overgrown_vegetation:spore_purifier[facing=" + f.serialized() + ",lit=false]";
    }

    /**
     * 除染機(装備の除染と胞子フィルターの洗浄。2026-10-04 の整備で倉庫に置く —— ユーザー指定「使用済みのフィルターは除染機で使い回す」
     * 「今の除染機を使う」)。
     */
    public static String decontaminator(Facing f) {
        return "overgrown_vegetation:decontaminator[facing=" + f.serialized() + ",lit=false]";
    }

    public static String floodlight(Facing f) {
        return "overgrown_vegetation:floodlight[facing=" + f.serialized() + "]";
    }

    public static String facing(String id, Facing f) {
        return id + "[facing=" + f.serialized() + "]";
    }

    public static String log(String id, String axis) {
        return id + "[axis=" + axis + "]";
    }

    public static String fenceGate(String id, Facing f) {
        return id + "[facing=" + f.serialized() + ",in_wall=false,open=false,powered=false]";
    }

    public static String wallBanner(String color, Facing f) {
        return "minecraft:" + color + "_wall_banner[facing=" + f.serialized() + "]";
    }

    private Pal() {
    }
}
