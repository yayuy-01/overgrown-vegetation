package com.yayuy.overgrownvegetation.data;

import java.util.List;

/**
 * {@code variants.json} — 亜種(個体差)の設計値(2026-09-19)。
 *
 * <p>グループ1〜4 の動ける Mob は、湧いた瞬間に「通常 / 硬化 / 毒性 / 耐火」のどれかに抽選される
 * ({@code variant/MobVariants})。既定ではおよそ 7 割が通常個体。
 * <ul>
 *     <li><b>硬化個体</b>: 防御値が {@code hardened.armorMultiplier} 倍(既定 1.3)。テクスチャは灰色寄り。</li>
 *     <li><b>毒性個体</b>: 攻撃した相手に毒を {@code toxic.poisonTicks} tick(既定 40 = 2 秒)。紫寄り。</li>
 *     <li><b>耐火個体</b>: 炎のダメージが {@code fireproof.fireDamageMultiplier} 倍(既定 0.7)。赤み寄り。</li>
 * </ul>
 * <p>さらに<b>グループ1〜10 の過繫茂 Mob 全体</b>が炎を弱点とし、炎のダメージが
 * {@code fireWeaknessMultiplier} 倍(既定 1.5)になる。耐火個体はこの 1.5 倍に 0.7 を掛けた約 1.05 倍。
 *
 * <p>テクスチャは {@code textures/entity/<id>_<variant>.png}(無ければ通常のテクスチャ)。
 * 生成は {@code tools/variant-textures/VariantTex.java}。{@code /ov design reload} で即反映
 * (すでに湧いている個体の種別は変わらない)。
 */
public final class VariantTable {

    public static final class Json {
        /** 通常個体の重み(既定 0.7)。 */
        public Double normalWeight;
        public Variant hardened;
        public Variant toxic;
        public Variant fireproof;
        /** 亜種の抽選対象にする MobGroup 番号(既定 1〜4)。 */
        public List<Integer> eligibleGroups;
        /** 抽選から外す種の id(名前空間省略可)。動けない種(移動速度 0)は書かなくても外れる。 */
        public List<String> excluded;
        /** グループ1〜10 の炎ダメージ倍率(既定 1.5 = 炎が弱点)。 */
        public Double fireWeaknessMultiplier;
    }

    public static final class Variant {
        /** 抽選の重み。 */
        public Double weight;
        /** 硬化: 防御値の倍率。 */
        public Double armorMultiplier;
        /** 毒性: 攻撃した相手に付ける毒の持続(tick)。 */
        public Integer poisonTicks;
        /** 耐火: 炎ダメージの倍率(弱点の倍率に掛ける)。 */
        public Double fireDamageMultiplier;
    }

    private final double normalWeight;
    private final double hardenedWeight;
    private final double toxicWeight;
    private final double fireproofWeight;
    private final double hardenedArmorMultiplier;
    private final int toxicPoisonTicks;
    private final double fireproofFireMultiplier;
    private final List<Integer> eligibleGroups;
    private final List<String> excluded;
    private final double fireWeaknessMultiplier;

    private VariantTable(Json j) {
        Json src = j != null ? j : new Json();
        this.normalWeight = clampWeight(src.normalWeight, 0.7D);
        Variant h = src.hardened != null ? src.hardened : new Variant();
        Variant t = src.toxic != null ? src.toxic : new Variant();
        Variant f = src.fireproof != null ? src.fireproof : new Variant();
        this.hardenedWeight = clampWeight(h.weight, 0.1D);
        this.toxicWeight = clampWeight(t.weight, 0.1D);
        this.fireproofWeight = clampWeight(f.weight, 0.1D);
        this.hardenedArmorMultiplier = h.armorMultiplier != null ? Math.max(0.0D, h.armorMultiplier) : 1.3D;
        this.toxicPoisonTicks = t.poisonTicks != null ? Math.max(0, t.poisonTicks) : 40;
        this.fireproofFireMultiplier = f.fireDamageMultiplier != null ? Math.max(0.0D, f.fireDamageMultiplier) : 0.7D;
        this.eligibleGroups = src.eligibleGroups != null ? List.copyOf(src.eligibleGroups) : List.of(1, 2, 3, 4);
        this.excluded = src.excluded != null ? List.copyOf(src.excluded)
                : List.of("blight_fern", "overgrown_nexus");
        this.fireWeaknessMultiplier = src.fireWeaknessMultiplier != null
                ? Math.max(0.0D, src.fireWeaknessMultiplier) : 1.5D;
    }

    private static double clampWeight(Double v, double def) {
        return v != null ? Math.max(0.0D, v) : def;
    }

    public double normalWeight() {
        return normalWeight;
    }

    public double hardenedWeight() {
        return hardenedWeight;
    }

    public double toxicWeight() {
        return toxicWeight;
    }

    public double fireproofWeight() {
        return fireproofWeight;
    }

    public double hardenedArmorMultiplier() {
        return hardenedArmorMultiplier;
    }

    public int toxicPoisonTicks() {
        return toxicPoisonTicks;
    }

    public double fireproofFireMultiplier() {
        return fireproofFireMultiplier;
    }

    public List<Integer> eligibleGroups() {
        return eligibleGroups;
    }

    public List<String> excluded() {
        return excluded;
    }

    public double fireWeaknessMultiplier() {
        return fireWeaknessMultiplier;
    }

    static VariantTable fromJson(Json j) {
        return new VariantTable(j);
    }

    public static final VariantTable DEFAULT = new VariantTable(null);
}
