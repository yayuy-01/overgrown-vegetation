package com.yayuy.overgrownvegetation.horde;

/**
 * 部隊の目的(2026-11-21 前線と部隊。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」の 3)。
 * {@link #MARCH} は前線を持たない古い群れ(大きな襲撃の仮想の群れ)。ほかは前線({@code front/Fronts})に属する部隊。
 */
public enum UnitPurpose {
    /** 前線の無い行進(大きな襲撃の仮想の群れ。区切り 3 で襲うへまとめる)。 */
    MARCH("行進", true),
    /** 守る: 前線の森の持ち場で動かない。プレイヤーが来たら森の住人として実物になる。 */
    DEFEND("守る", false),
    /** 広げる: 森のすぐ外の升へ行き、升の動物・村人を感染させる。食べ終えたら隣の升へ。 */
    EXPAND("広げる", false),
    /** 狩る: 見つかったプレイヤーへ向かう(区切り 3)。 */
    HUNT("狩る", true),
    /** 襲う: 拠点へ向かう(区切り 3)。 */
    RAID("襲う", true),
    /** 建てる: 巨大樹を建てる所へ集まり、体を木にする(区切り 4)。 */
    BUILD("建てる", false);

    public final String label;
    /** 実物にするとき行き先へ行進させるか(戦いに向かう部隊)。実物にする順も先。 */
    public final boolean marches;

    UnitPurpose(String label, boolean marches) {
        this.label = label;
        this.marches = marches;
    }

    public static UnitPurpose byName(String name) {
        for (UnitPurpose p : values()) if (p.name().equals(name)) return p;
        return MARCH;
    }
}
