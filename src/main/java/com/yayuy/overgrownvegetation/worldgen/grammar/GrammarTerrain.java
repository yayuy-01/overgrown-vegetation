package com.yayuy.overgrownvegetation.worldgen.grammar;

/**
 * 地形クエリ。SPEC §5。エンジンはこの抽象しか触らない。
 *
 * <ul>
 *   <li>ゲーム内: {@code LevelTerrain} が {@code WorldGenLevel} をラップ。</li>
 *   <li>ブラウザ / テスト: {@link #preset(String)} が {@code src/terrain.js} と同じ数式のプリセット。</li>
 * </ul>
 */
public interface GrammarTerrain {

    /** その柱の一番上の「固体」表面の y。 */
    int groundY(int x, int z);

    default boolean isSolid(int x, int y, int z) {
        return y <= groundY(x, z);
    }

    default boolean isReplaceable(int x, int y, int z) {
        return y > groundY(x, z);
    }

    GrammarTerrain FLAT = (x, z) -> 0;

    /** {@code src/terrain.js} の presets と一致(round は fdlibm 系にせず素の四捨五入で可)。 */
    static GrammarTerrain preset(String name) {
        switch (name == null ? "flat" : name) {
            case "hill":
                return (x, z) -> (int) Math.round(3 * StrictMath.sin(x / 7.0) + 2 * StrictMath.cos(z / 5.0));
            case "slope":
                return (x, z) -> (int) Math.round(x / 4.0);
            case "cliff":
                return (x, z) -> x < 0 ? 0 : 7;
            case "flat":
            default:
                return FLAT;
        }
    }
}
