package com.yayuy.overgrownvegetation.nest;

import javax.annotation.Nullable;

/**
 * アルボラの巣の側室の種類(2026-09-19)。掘る形だけがここの担当で、中身(卵・貯蔵胞・女王など)は
 * {@code NestContents} が後から置く。
 *
 * <p>{@link #remoteness} は「どれくらい奥に置きたいか」(0 = 入口寄り、1 = 最奥)。
 * 値が 0.3 から始まっているのは、側室が {@code chamberGap} より内側には置かれないので
 * <b>実際に取りうる奥まり具合が 0.3 付近から</b>になるため。0.15 のような値を与えると
 * 「いちばん近い種類」に選ばれる機会がほとんど無く、その部屋だけ数が揃わない(2026-09-19 に判明)。
 * {@link NestLayout} が側室を掘るとき、その場所の奥まり具合にいちばん近い種類を選ぶので、
 * 浅いところに貯蔵室、深いところに噴気室、という並びが自然にできる。
 */
public enum NestChamber {

    /** 貯蔵室。アルボラの貯蔵胞が並ぶ。巣の中で死んだプレイヤーの持ち物もここへ移る。 */
    STORAGE("storage", 0.30D, 3, 3.6D),
    /** 根の間。巨大樹の根が壁を貫き、クロロフィライトと樹液結晶が露出する。 */
    ROOT("root", 0.45D, 3, 3.8D),
    /** 孵化室。ここが世界に 1 つでもあると亜種が解禁される。卵から孵る個体は必ず亜種。 */
    HATCHERY("hatchery", 0.62D, 2, 4.0D),
    /** 衛兵室。シールダー 1 体と、能力 1.3 倍のアルボラ 4 体が待機する。 */
    GUARD("guard", 0.79D, 2, 4.2D),
    /** 噴気室。胞子 node が置かれ、地上へ噴出孔が伸びる。1 つの巣に 1 室だけ。 */
    VENT("vent", 0.95D, 1, 4.4D, true),
    /** 女王室。最奥。女王個体と、その奥の node。側室の抽選には出ず、最後に別枠で置かれる。 */
    QUEEN("queen", 1.0D, 0, 6.5D, true);

    private final String id;
    private final double remoteness;
    private final int weight;
    private final double radius;
    private final boolean unique;

    NestChamber(String id, double remoteness, int weight, double radius) {
        this(id, remoteness, weight, radius, false);
    }

    NestChamber(String id, double remoteness, int weight, double radius, boolean unique) {
        this.id = id;
        this.remoteness = remoteness;
        this.weight = weight;
        this.radius = radius;
        this.unique = unique;
    }

    public String id() {
        return id;
    }

    /** 置きたい奥まり具合(0 = 入口寄り、1 = 最奥)。 */
    public double remoteness() {
        return remoteness;
    }

    /** 側室の総数をこの比で振り分ける。0 は抽選に出ない(女王室)。 */
    public int weight() {
        return weight;
    }

    /** 掘る球の半径(ブロック)。 */
    public double radius() {
        return radius;
    }

    /** 1 つの巣に 1 室だけか。 */
    public boolean unique() {
        return unique;
    }

    @Nullable
    public static NestChamber byId(String id) {
        for (NestChamber c : values()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }
}
