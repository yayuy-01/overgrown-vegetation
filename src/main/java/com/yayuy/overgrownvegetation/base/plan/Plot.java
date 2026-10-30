package com.yayuy.overgrownvegetation.base.plan;

/**
 * 配置計画の 1 区画 = 建物 1 棟(2026-10-01)。座標は基地の中心が原点の局所座標(x = 東、z = 南。正門は南)。
 *
 * @param kind   建物の種類
 * @param zone   区画の役割
 * @param band   何番目の輪か(1 = 中心部。基地レベル {@code band} 以上で建つ)
 * @param x0     足元の西端(含む)
 * @param z0     足元の北端(含む)
 * @param x1     足元の東端(含む)
 * @param z1     足元の南端(含む)
 * @param facing 正面(入口)の向き
 * @param seed   この建物の細部を決める乱数の種(同じ計画なら毎回同じ)
 */
public record Plot(BuildingKind kind, Zone zone, int band, int x0, int z0, int x1, int z1, Facing facing, long seed) {

    public int sizeX() {
        return x1 - x0 + 1;
    }

    public int sizeZ() {
        return z1 - z0 + 1;
    }

    /** 正面に沿った幅(正面が南北なら x、東西なら z)。 */
    public int width() {
        return facing.dx == 0 ? sizeX() : sizeZ();
    }

    /** 奥行(正面から背面まで)。 */
    public int depth() {
        return facing.dx == 0 ? sizeZ() : sizeX();
    }

    public boolean contains(int x, int z) {
        return x >= x0 && x <= x1 && z >= z0 && z <= z1;
    }

    /** 足元どうしが {@code margin} マス以内に重なるか。 */
    public boolean overlaps(Plot o, int margin) {
        return x0 - margin <= o.x1 && o.x0 <= x1 + margin && z0 - margin <= o.z1 && o.z0 <= z1 + margin;
    }

    /** 足元の外側から (x, z) までのチェビシェフ距離(中なら 0)。 */
    public int distance(int x, int z) {
        int dx = Math.max(0, Math.max(x0 - x, x - x1));
        int dz = Math.max(0, Math.max(z0 - z, z - z1));
        return Math.max(dx, dz);
    }
}
