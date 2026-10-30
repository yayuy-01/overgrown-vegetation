package com.yayuy.overgrownvegetation.base.plan;

/**
 * ブロックの形のおおまかな分類(2026-10-01)。マインクラフトのクラスを使わずに、塀・金網のつながりと
 * プレビューの面の間引きを決めるため({@link States} の説明を参照)。設計図で使うブロックが分かればよいので、名前で見分ける。
 */
public final class Shapes {

    /** 完全な立方体ではない(=隣の面を隠さない・塀や金網が「固い面」としてつながらない)名前の一部。 */
    private static final String[] NOT_FULL = {
            "_stairs", "_wall", "_fence", "_pane", "iron_bars", "chain_link_fence", "_door", "trapdoor",
            "lantern", "torch", "_bed", "chest", "anvil", "floodlight", "_lamp", "chain", "button", "lever",
            "carpet", "banner", "sign", "_rod", "grindstone", "leaves", "flower", "short_grass", "fern",
            "ladder", "rail", "pressure_plate", "candle", "flower_pot", "potted_", "_head", "skull", "scaffolding",
            "bell", "cake", "campfire", "hopper", "cauldron", "lectern", "brewing_stand", "enchanting_table",
            "barbed_wire", "cobweb", "vine", "snow", "dead_bush", "sapling", "stonecutter", "daylight_detector",
            "composter", "end_rod", "conduit", "bamboo", "sea_pickle", "pointed_dripstone", "glow_lichen",
            "gun_turret", "spore_purifier", "spore_diffuser", "spore_alarm", "azalea", "spore_blossom", "dripleaf",
    };

    /**
     * 空気か。草木払いの印 {@link BaseEmitter#SOFT_AIR} も空気に数える —— 数えないと「固い立方体」と見なされ、
     * 塀・金網・板ガラスが払った側へもつながって全部十字になる(2026-10-01 にユーザーが runClient で見つけた)。
     */
    public static boolean isAir(String state) {
        return state.isEmpty() || state.equals(BaseEmitter.SOFT_AIR)
                || States.id(state).equals(States.AIR) || States.id(state).endsWith(":cave_air");
    }

    /** 隣の面を隠す完全な立方体か(ハーフは上下とも埋まった double だけ)。 */
    public static boolean isFullCube(String state) {
        if (isAir(state)) {
            return false;
        }
        String id = States.id(state);
        if (id.endsWith("_slab")) {
            return "double".equals(States.prop(state, "type"));
        }
        for (String part : NOT_FULL) {
            if (id.contains(part)) {
                return false;
            }
        }
        return true;
    }

    /** 透けて見える立方体(ガラス・氷)。面を隠すが、隣のガラスどうしの面だけ消す。 */
    public static boolean isGlassLike(String state) {
        String id = States.id(state);
        return id.endsWith("glass") || id.endsWith("_ice") || id.equals("minecraft:ice");
    }

    /** 金網・鉄格子・板ガラスの仲間(互いにつながる)。 */
    public static boolean isBars(String state) {
        String id = States.id(state);
        return id.endsWith("_pane") || id.equals("minecraft:iron_bars") || id.endsWith(":chain_link_fence");
    }

    public static boolean isWall(String state) {
        return States.id(state).endsWith("_wall");
    }

    /** 木の柵(フェンスゲートは含まない)。 */
    public static boolean isFence(String state) {
        String id = States.id(state);
        return id.endsWith("_fence") && !id.endsWith("chain_link_fence");
    }

    public static boolean isFenceGate(String state) {
        return States.id(state).endsWith("_fence_gate");
    }

    private Shapes() {
    }
}
