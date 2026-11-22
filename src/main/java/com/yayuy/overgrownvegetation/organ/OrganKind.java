package com.yayuy.overgrownvegetation.organ;

/** 森の器官の役割(2026-11-17 ユーザー決定の 5 つ)。 */
public enum OrganKind {
    /** フェーズを進める器官(植物の研究施設)。フェーズのポイントを作る。 */
    PHASE("phase", "フェーズを進める器官"),
    /** 胞子の塔。胞子を吹き上げ、風下へ汚染を広げる。 */
    TOWER("tower", "胞子の塔"),
    /** 蓄えの嚢。司令塔の予算の上限を上げる(ハイヴノードと同じ考え)。 */
    SAC("sac", "蓄えの嚢"),
    /** 苗床の群体。手先を産み続ける。 */
    NURSERY("nursery", "苗床の群体"),
    /** 地脈を吸う根。まわりの汲み上げ器を弱め、根晶が育つ。 */
    LEY("ley", "地脈を吸う根");

    public final String id;
    public final String label;

    OrganKind(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static OrganKind byId(String id) {
        for (OrganKind k : values()) if (k.id.equals(id)) return k;
        return null;
    }
}
