package com.yayuy.overgrownvegetation.variant;

import javax.annotation.Nullable;

/**
 * 亜種(個体差)の種別(2026-09-19)。グループ1〜4 の動ける Mob は湧いた瞬間にどれかへ抽選される
 * ({@link MobVariants})。
 * <ul>
 *     <li>{@link #NORMAL} 通常個体(既定でおよそ 7 割)。</li>
 *     <li>{@link #HARDENED} 硬化個体 — 防御値 1.3 倍。灰色っぽい。</li>
 *     <li>{@link #TOXIC} 毒性個体 — 攻撃した相手に毒 2 秒。紫が強い。</li>
 *     <li>{@link #FIREPROOF} 耐火個体 — 炎のダメージ 0.7 倍。少し赤みがかる。</li>
 * </ul>
 * 数値は {@code variants.json}({@code data/VariantTable})。テクスチャは
 * {@code textures/entity/<id>_<suffix>.png}(通常個体は接尾辞なし)。
 */
public enum MobVariant {
    NORMAL("normal"),
    HARDENED("hardened"),
    TOXIC("toxic"),
    FIREPROOF("fireproof");

    private final String suffix;

    MobVariant(String suffix) {
        this.suffix = suffix;
    }

    /** テクスチャ名・翻訳キーに使う接尾辞。 */
    public String suffix() {
        return suffix;
    }

    /** 保存・同期用の番号(0 = 未抽選、1 以降 = {@code ordinal() + 1})。 */
    public int code() {
        return ordinal() + 1;
    }

    @Nullable
    public static MobVariant byCode(int code) {
        int index = code - 1;
        MobVariant[] values = values();
        return index >= 0 && index < values.length ? values[index] : null;
    }
}
