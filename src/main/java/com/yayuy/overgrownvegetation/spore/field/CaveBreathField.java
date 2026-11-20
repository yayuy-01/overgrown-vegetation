package com.yayuy.overgrownvegetation.spore.field;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

/**
 * 洞窟の呼吸(2026-11-20)から胞子の場(オーバーワールド)への口。場の計算は裏のスレッドなので、どれも {@link SporeFieldServer#INPUTS} に積み、
 * 次の更新の頭で当てはめる。仕組みの本体は {@code corruption.cave.CaveBreathing}。
 */
public final class CaveBreathField {
    private CaveBreathField() {
    }

    /** 口ごとの染み込みの倍率と、地脈の効き(地下の発芽のしきい値の割り算)を入れる。 */
    public static void publish(Long2FloatOpenHashMap soak, long leySeed, double leyNearBlocks, double leyGermMax) {
        SporeFieldServer.INPUTS.add(f -> {
            f.breathSoak = soak;
            f.leySeed = leySeed;
            f.leyNearBlocks = leyNearBlocks;
            f.leyGermMax = leyGermMax;
        });
    }

    /** 地下の浮遊を口のチャンクから奥の洞窟のチャンクへ移す。 */
    public static void spreadUnder(int fx, int fz, int tx, int tz, double frac) {
        SporeFieldServer.INPUTS.add(f -> f.moveUnder(fx, fz, tx, tz, frac));
    }

    /** 吐き出す口: その升の浮遊へ足す(胞子の場の発生源。風に乗って広がる)。 */
    public static void exhale(int cx, int cz, double amount) {
        SporeFieldServer.emitCell(cx, cz, amount);
    }

    /** 胞子が満ちたとみなす浮遊の濃さ(量の目安)。 */
    public static double fullAt() {
        return com.yayuy.overgrownvegetation.data.DesignData.sporeField().fullAt();
    }
}
