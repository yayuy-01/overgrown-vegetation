package com.yayuy.overgrownvegetation.horde;

import net.minecraft.world.entity.EntityType;

/**
 * 仮想の群れの並び(2026-11-18 大群の軽量化 A)。サーバーの本物化({@link VirtualHordes#materialize})と、クライアントの遠景の身代わり
 * (セッション C)が<b>同じ式</b>で一員の位置を引く(身代わりが消えた所に本物が出るように)。parked の TD の {@code HordeFormation} の簡単な形。
 *
 * <p>ひまわりの並び: 一員 {@code index} の半径 = 1.4 × 幅 × √(index + 0.5)、角度 = 群れの id から決まる始まり + index × 黄金角。
 * 幅は {@link EntityType#getWidth}(1 未満は 1)。向きは世界の向き(群れの進む向きでは回さない)。
 * {@code index} は、送る群れの firstIndex + 種ごとの頭数(counts)の並び順に数えた通し番号(本物にした分で詰めない)。
 */
public final class VirtualHordeLayout {

    private static final double GOLDEN_ANGLE = 2.3999631D;

    private VirtualHordeLayout() {
    }

    /** 群れの id から決まる、並びの始まりの角度(ラジアン、0〜2π)。 */
    public static double startAngle(long id) {
        return ((id * 0x9E3779B97F4A7C15L) >>> 40) % 6283L / 1000.0D;
    }

    /** 一員の、群れの真ん中からのずれ {x, z}(ブロック)。 */
    public static double[] offset(long id, int index, EntityType<?> type) {
        double r = 1.4D * Math.max(1.0D, type.getWidth()) * Math.sqrt(index + 0.5D);
        double a = startAngle(id) + index * GOLDEN_ANGLE;
        return new double[]{Math.cos(a) * r, Math.sin(a) * r};
    }
}
