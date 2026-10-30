package com.yayuy.overgrownvegetation.block;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * 機関砲台の<b>射撃モード</b>(ユーザー要望 2026-09-26)。ブロックの状態として持つので、
 * 壊して置き直しても・チャンクを読み込み直しても残る。切り替えは<b>スニーク + 右クリック</b>。
 *
 * <ul>
 *   <li>{@link #DUAL 両用} — 今までどおり。空も地上も撃つ(優先するのは空)。</li>
 *   <li>{@link #AIR 防空} — ヴェロフィル・落下中の胞子爆弾・飛んでくる胞子弾・空を飛ぶ Mob だけ。
 *       <b>地上の目標は狙わない</b>。</li>
 *   <li>{@link #GROUND 対地} — 空の目標を<b>まったく見ない</b>。地上の Mob を狙い続ける。</li>
 * </ul>
 *
 * <p>「空の目標」の線引きは {@code defense/GunTurretTargeting#isAirTarget}。
 */
public enum GunTurretMode implements StringRepresentable {

    /** 両用。空を優先しつつ、居なければ地上を撃つ(2026-09-26 より前の唯一の動き)。 */
    DUAL("dual"),
    /** 防空。空の目標だけを撃つ。 */
    AIR("air"),
    /** 対地。地上の目標だけを撃つ。 */
    GROUND("ground");

    private final String name;

    GunTurretMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** スニーク + 右クリックで次のモードへ(両用 → 防空 → 対地 → 両用)。 */
    public GunTurretMode next() {
        return switch (this) {
            case DUAL -> AIR;
            case AIR -> GROUND;
            case GROUND -> DUAL;
        };
    }

    /** 空の目標(ヴェロフィル・胞子爆弾・胞子弾・飛ぶ Mob)を狙うか。 */
    public boolean engagesAir() {
        return this != GROUND;
    }

    /** 地上の目標を狙うか。 */
    public boolean engagesGround() {
        return this != AIR;
    }

    /** 画面に出す名前({@code gui.overgrown_vegetation.gun_turret.mode.*})。 */
    public Component label() {
        return Component.translatable("gui.overgrown_vegetation.gun_turret.mode." + this.name);
    }
}
