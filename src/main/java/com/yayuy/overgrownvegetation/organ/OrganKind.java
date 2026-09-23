package com.yayuy.overgrownvegetation.organ;

import net.minecraft.util.StringRepresentable;

/**
 * <b>器官の種類</b>(2026-09-23)。
 *
 * <p><b>形は種類ごとに違う</b>(要望)。三つ編みの塔は<b>胞子腺だけ</b>の姿であって、
 * 器官一般の姿ではない。だから「節を置いて、そこから生やす」という段取りだけを共通にし、
 * <b>何が生えるかは種類に委ねる</b>({@link OrganShape})。
 *
 * <p>台帳({@link OrganData})は節ごとに種類を覚えるので、
 * 種類が増えても保存の形は変わらない。
 */
public enum OrganKind implements StringRepresentable {

    /**
     * <b>胞子腺</b> —— 最初の器官。世界の胞子濃度に下限を作る。
     * 姿は地脈の上に立つ<b>三つ編みの塔</b>({@code shape/SporeGlandTower})で、
     * 先端から胞子を噴いている。
     */
    SPORE_GLAND("spore_gland");

    private final String name;

    OrganKind(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** 保存された名前から引く。読めなければ {@link #SPORE_GLAND}(古いセーブはこれしか無い)。 */
    public static OrganKind byName(String name) {
        for (OrganKind kind : values()) {
            if (kind.name.equals(name)) {
                return kind;
            }
        }
        return SPORE_GLAND;
    }
}
