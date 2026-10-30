package com.yayuy.overgrownvegetation.base.plan;

/**
 * 基地の区画の役割(2026-10-01)。米軍の前線基地の区割りの写し:
 * 中央に司令部、正門から遠い側に居住区(兵舎)、正門の脇に補給(倉庫)と整備(工廠・車両)、
 * 弾薬は居住区から離した角に土手で囲って置く。プレビューの色分けにも使う。
 */
public enum Zone {
    CORE("司令部", 0xC8B560),
    LSA("居住区", 0x5B8DD9),
    SUPPORT("支援", 0x8AA8A0),
    LOGISTICS("補給", 0xD9963B),
    MAINTENANCE("整備", 0x9C7BC4),
    AMMO("弾薬", 0xD24B3E),
    AIR("航空", 0x4FB3B3),
    DEFENSE("防御", 0x707070),
    // ---- 2026-10-02 夜: 種類ごとの特別な施設の区割り(ユーザー指定「野戦病院は医療テント、研究は野外研究施設、観測には観測施設」)。
    // 後ろに足す(番号は計画の指紋と区画の種に入るので、前の区割りの番号を変えない) ----
    /** 野戦病院の医療テントの区割り(大通り沿いの浄化圏)。 */
    MEDICAL("医療", 0xE07A8F),
    /** 研究基地の出城(野外研究施設)。 */
    RESEARCH("研究", 0x6FBF73),
    /** 観測基地の出城(観測施設)。 */
    OBSERVATION("観測", 0x2F5A9E),
    /** 本拠と出城をつなぐ、壁で挟んだ道(中身は道だけ。除去塔も建物も置かない)。 */
    CORRIDOR("通路", 0x8C8C8C);

    public final String label;
    public final int color;

    Zone(String label, int color) {
        this.label = label;
        this.color = color;
    }
}
