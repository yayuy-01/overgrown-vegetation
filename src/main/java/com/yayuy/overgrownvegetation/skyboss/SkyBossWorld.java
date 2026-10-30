package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.sky.CloudField;

/**
 * 空の巨大生物が雲・風について知ることの<b>取り次ぎ</b>(雲のコード {@code sky/} は別のセッションが作っているので、そちらの名前が変わっても直すのはここだけ)。
 * サーバーとクライアントで同じ答えを出す純粋な関数だけを使う。
 *
 * <h2>空気の座標</h2>
 * ボスの頭の道は<b>空気の座標</b>(雲の場の座標 = 世界の座標 − 風が運んだ距離)で引く。体は空気の中を通った道をなぞり、全体が風で流される
 * (横風では体が風上へ向いたまま斜めに進み、雲に潜っていれば同じ雲と一緒に流れる)。世界の座標へは今の時刻の風のずれを足すだけ。
 */
public final class SkyBossWorld {

    /** 世界の風(ブロック / tick)。t = gameTime + partialTick。 */
    public static double windX(double t) {
        return CloudField.windX(t);
    }

    public static double windZ(double t) {
        return CloudField.windZ(t);
    }

    /** 空気の座標 → 世界の座標(時刻 t)。 */
    public static double worldX(double airX, double t) {
        return airX + CloudField.offsetX(t);
    }

    public static double worldZ(double airZ, double t) {
        return airZ + CloudField.offsetZ(t);
    }

    /** 世界の座標 → 空気の座標(時刻 t)。 */
    public static double airX(double worldX, double t) {
        return worldX - CloudField.offsetX(t);
    }

    public static double airZ(double worldZ, double t) {
        return worldZ - CloudField.offsetZ(t);
    }

    /**
     * 空気の座標 (ax, az) から {@code range} の中の巨大な積乱雲(サーバー。10-19)。雲の場の座標は空気の座標と同じなので、雲は止まっている
     * (育ち・崩れで大きさが変わるだけ)。塔の形の巨大な雲だけ(入道雲・橋・ふつうの積乱雲は除く)。
     */
    public static java.util.List<SkyBossFeeding.Storm> giantStorms(net.minecraft.world.level.Level level, double ax, double az, double range) {
        float coverage = com.yayuy.overgrownvegetation.sky.CloudSky.coverage(level, 1.0F);
        java.util.List<SkyBossFeeding.Storm> out = new java.util.ArrayList<>();
        for (CloudField.Storm s : com.yayuy.overgrownvegetation.sky.CloudSky.storms(level, 1.0F, coverage, ax - range, az - range, ax + range, az + range)) {
            if (!s.giant() || s.kind() != 0 || s.congestus()) {
                continue;
            }
            out.add(new SkyBossFeeding.Storm(s.id(), s.x(), s.z(), s.bodyRadius(), s.maturity()));
        }
        return out;
    }

    /**
     * 世界の点 (x, z) の雲の海の {底, 天辺}(y。雲の作業の {@code CloudSky.columnAt} の雲の海の部分。10-19 夕方)。無ければ null。
     * {@code storms} は {@link #stormsNear} で 1 回引いたもの(毎回引き直すと重い)。
     */
    public static double[] sea(net.minecraft.world.level.Level level, double x, double z, java.util.List<CloudField.Storm> storms) {
        com.yayuy.overgrownvegetation.sky.CloudSky.CloudColumn c = com.yayuy.overgrownvegetation.sky.CloudSky.columnAt(level, x, z, 1.0F, storms);
        if (c == null || c.seaBottom() < 0 || c.seaTop() <= c.seaBottom()) {
            return null;
        }
        return new double[]{c.seaBottom(), c.seaTop()};
    }

    public static java.util.List<CloudField.Storm> stormsNear(net.minecraft.world.level.Level level, double x, double z, double range) {
        return com.yayuy.overgrownvegetation.sky.CloudSky.stormsNear(level, x, z, 1.0F, range);
    }

    /**
     * 雷を (fx, fy, fz) から (tx, ty, tz) へ 1 本(雲の作業の {@code CloudLightning.strikeBetween}。触れたら即死)。戻り値 = 太い雷が届くまでの tick。
     * 見える距離はボスバーの距離。
     */
    public static int strike(net.minecraft.server.level.ServerLevel level, double fx, double fy, double fz, double tx, double ty, double tz) {
        return com.yayuy.overgrownvegetation.sky.CloudLightning.strikeBetween(level, new net.minecraft.world.phys.Vec3(fx, fy, fz),
                new net.minecraft.world.phys.Vec3(tx, ty, tz), true, SkyBossTuning.BOSS_BAR_RANGE);
    }

    private SkyBossWorld() {
    }
}
