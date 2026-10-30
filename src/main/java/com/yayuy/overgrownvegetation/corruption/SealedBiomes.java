package com.yayuy.overgrownvegetation.corruption;

/**
 * <b>0.2.0 まで封印</b>しているバイオーム(2026-10-23 ユーザー決定)。
 *
 * <p>湿地・菌湿地(繁茂・過繁茂・極相の全段と川の版)と結晶地帯({@code engulfed_crystal})は 0.2.0 で作り直すので、
 * それまでゲーム内に出さない。封印中は地域マップ({@link OvergrowthRegionMap})が湿地・菌湿地を選ばず
 * (どの地域も密林)、結晶地帯の地域も選ばない —— 汚染された所は同じ段の普通の繁茂系
 * ({@code overgrown} / {@code overgrowth} / {@code engulfed}、川は川の版)になる。
 * 浸水・木の倒壊・菌の柱・洞窟の湿地の植物・結晶と鉱石の配置・湿地の湧きの枠は、どれも地域マップか
 * バイオームを入口にしているので、これだけで止まる。
 *
 * <p>バイオームの json・タグ・言語キー・ガイドブックの項は消していない。既存のセーブに既にあるそのバイオームは
 * 塗り替えずそのまま残る(次の段へ進むときに普通の繁茂系になる)。
 *
 * <p><b>戻し方(0.2.0)</b>: {@link #SEALED} を {@code false} にする。
 */
public final class SealedBiomes {

    /** {@code true} の間、湿地・菌湿地・結晶地帯が出ない。 */
    public static final boolean SEALED = true;

    /** 湿地・菌湿地・結晶地帯が封印中か。 */
    public static boolean swampAndCrystalSealed() {
        return SEALED;
    }

    private SealedBiomes() {
    }
}
