package com.yayuy.overgrownvegetation.base.plan;

import java.util.Locale;

/**
 * 基地の種類(得意分野。ユーザー指定 2026-10-01)と、種類ごとの広げ方(ユーザー指定 2026-10-02「大型防衛 = 輪、補給 = 大通り沿い、
 * 観測 = 出城、浄化 = 浄化圏、工廠 = 格子」でお願いします、同日夜「野戦病院は補給と同じで、研究は観測と同じ出城で」)。
 * <ul>
 *     <li>浄化 = 浄化圏がつながる({@link Growth#LOBES}、10-02 に作り直した形。この印より前の台帳の基地もこれ)。</li>
 *     <li>大型防衛 = 輪({@link Growth#RING}): いちばん短い向きへ継いで、丸く一回りずつ育つ。ほかより広い。</li>
 *     <li>補給・野戦病院 = 大通り沿い({@link Growth#SPINE}): 中心の東西の通りの延長に大通り、端で曲がり、レベル 4 で枝分かれ。</li>
 *     <li>工廠 = 格子({@link Growth#GRID}): 四角の浄化圏を、行と列のそろった格子の場所へ。</li>
 *     <li>観測・研究 = 出城({@link Growth#OUTPOST}): 離れた出城を、壁で挟んだ道(通路)で本拠とつなぐ。</li>
 * </ul>
 * 中心の浄化圏とその中身はどの種類も同じ(作り込んだ所なので)。
 *
 * <p>特別な施設(ユーザー指定 2026-10-02 夜「野戦病院は医療テント、研究は野外研究施設を、観測には観測施設を」「研究や観測は出城型なので
 * 離れた出城の場所にその特別な施設を置く」): 野戦病院は医療の区割りの浄化圏に医療テント、観測は出城(2 つ)に観測施設。
 * 研究は出城 1 つの真ん中に大きな研究所を 1 棟だけ(同日「何棟も置くものではなく一棟だけ大きな施設」)。施設はレベル 2 から(レベル 1 には置かない、同日ユーザー指定)。
 */
public enum BaseType {
    PURIFICATION("purification", "浄化", Growth.LOBES, null),
    DEFENSE("defense", "大型防衛", Growth.RING, null),
    SUPPLY("supply", "補給", Growth.SPINE, null),
    ARSENAL("arsenal", "工廠", Growth.GRID, null),
    OBSERVATION("observation", "観測", Growth.OUTPOST, Zone.OBSERVATION),
    HOSPITAL("hospital", "野戦病院", Growth.SPINE, Zone.MEDICAL),
    RESEARCH("research", "研究", Growth.OUTPOST, Zone.RESEARCH);

    /** 広げ方。 */
    public enum Growth {
        /** 浄化圏がつながる(区割りの好む向きと乱数)。 */
        LOBES,
        /** 輪(いちばん短い向きへ)。 */
        RING,
        /** 大通り沿い(東西の大通り → 端で曲がる → 枝分かれ)。 */
        SPINE,
        /** 格子(四角の浄化圏を格子の場所へ)。 */
        GRID,
        /** 出城(離れた出城を、壁で挟んだ道で本拠とつなぐ。2026-10-02 夜。後ろに足す)。 */
        OUTPOST
    }

    /** 保存とコマンドの名前(改名しない)。 */
    public final String id;
    public final String label;
    public final Growth growth;
    /** 特別な施設を置く区割り(無ければ null)。 */
    public final Zone special;

    BaseType(String id, String label, Growth growth, Zone special) {
        this.id = id;
        this.label = label;
        this.growth = growth;
        this.special = special;
    }

    /** 区割り {@code zone} の特別な施設(医療 = 医療テント・研究 = 野外研究施設・観測 = 観測施設)。ほかの区割りは null。 */
    public static BuildingKind facility(Zone zone) {
        return switch (zone) {
            case MEDICAL -> BuildingKind.MEDICAL_TENT;
            case RESEARCH -> BuildingKind.FIELD_LAB;
            case OBSERVATION -> BuildingKind.OBSERVATION_POST;
            default -> null;
        };
    }

    /** 出城の区割り(観測・研究)か。 */
    public static boolean outpostZone(Zone zone) {
        return zone == Zone.OBSERVATION || zone == Zone.RESEARCH;
    }

    /** 名前から(知らない名前・空なら浄化 = この印より前の台帳の基地)。 */
    public static BaseType byId(String id) {
        if (id != null) {
            String s = id.toLowerCase(Locale.ROOT);
            for (BaseType t : values()) {
                if (t.id.equals(s)) {
                    return t;
                }
            }
        }
        return PURIFICATION;
    }
}
