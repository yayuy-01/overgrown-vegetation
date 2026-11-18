package com.yayuy.overgrownvegetation.client.crowd;

/**
 * 遠くの群れ(本物になる前の仮想の群れ)の見た目(2026-11-18 大群の軽量化 C の (d)。画面側の設定 {@code hordeLook})。
 * ユーザーが絵を見て決めるまで、既定は {@link #COMBINED}【仮】。
 */
public enum HordeLook {
    /** 本物になる距離から {@code hordeModelFarBlocks} までは粗いモデル、その先は 2 次元の絵の板。 */
    COMBINED,
    /** 全部 2 次元の絵の板(8 方向。いちばん軽い)。 */
    SPRITE,
    /** 全部粗いモデル(まとめ描き。近くの本物と同じ見え方)。 */
    MODEL
}
