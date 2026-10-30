package com.yayuy.overgrownvegetation.base.plan;

/**
 * 地面を塗る長方形(端を含む、基地の局所座標)。重なったら {@link Surface} の順が後のものが勝つ。2026-10-01。
 */
public record Area(Surface surface, int x0, int z0, int x1, int z1) {

    public static Area of(Surface surface, int xa, int za, int xb, int zb) {
        return new Area(surface, Math.min(xa, xb), Math.min(za, zb), Math.max(xa, xb), Math.max(za, zb));
    }

    public boolean contains(int x, int z) {
        return x >= x0 && x <= x1 && z >= z0 && z <= z1;
    }
}
