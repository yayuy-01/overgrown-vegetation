package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;

/**
 * 胞子装置の種類。{@link SporeDeviceBlock} / {@link SporeDeviceBlockEntity} を 1 組のクラスで共用し、
 * 種類ごとの違い(濃度への符号・燃料・胞子の産出/消費)はここで切り替える。
 */
public enum SporeDeviceKind {

    /** 胞子除去装置 — 周囲の胞子濃度を下げ、もともとの濃度に応じて「胞子」を産出する。燃料はレッドストーンダスト。 */
    PURIFIER("spore_purifier", -1),
    /** 胞子拡散装置 — 周囲の胞子濃度を上げる。燃料はレッドストーンダスト + 「胞子」。 */
    DIFFUSER("spore_diffuser", +1);

    private final String id;
    private final int sign;

    SporeDeviceKind(String id, int sign) {
        this.id = id;
        this.sign = sign;
    }

    /** ブロック / BlockItem の登録名。 */
    public String id() {
        return id;
    }

    /** 胞子濃度への符号(除去 = −1、拡散 = +1)。 */
    public int sign() {
        return sign;
    }

    public EffectTable.DeviceSpec spec() {
        return this == PURIFIER ? DesignData.effects().purifier() : DesignData.effects().diffuser();
    }

    public static SporeDeviceKind byOrdinal(int ordinal) {
        SporeDeviceKind[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }
}
