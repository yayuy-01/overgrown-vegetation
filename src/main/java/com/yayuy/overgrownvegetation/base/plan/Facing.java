package com.yayuy.overgrownvegetation.base.plan;

/** 水平 4 方向(x = 東、z = 南)。マインクラフトの {@code Direction} を使わない版({@link States} の説明を参照)。 */
public enum Facing {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    public final int dx;
    public final int dz;

    Facing(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** 真上から見て時計回りに {@code turns} 回。 */
    public Facing rotateCw(int turns) {
        return values()[Math.floorMod(ordinal() + turns, 4)];
    }

    public Facing opposite() {
        return rotateCw(2);
    }

    public String serialized() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 南向き(設計図の正面)をこの向きへ回すのに要る時計回りの回数。南 0・西 1・北 2・東 3。
     */
    public int turnsFromSouth() {
        return Math.floorMod(ordinal() - SOUTH.ordinal(), 4);
    }

    public static Facing byName(String name) {
        for (Facing f : values()) {
            if (f.serialized().equals(name)) {
                return f;
            }
        }
        return null;
    }
}
