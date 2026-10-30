package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import javax.annotation.Nullable;

/**
 * 繁茂系バイオームの「環境の種類」(密林 / 湿地 / 菌湿地帯)。
 *
 * <p>汚染がどのチャンクをどの環境に変えるかは、元のバニラバイオームとは無関係に
 * {@link com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap}(ワールドシードから決まる
 * 巨大な地域マップ)が決める。同じ地域に属するチャンクは必ず同じ環境になるので、
 * 「山だった場所が湿地に、平原だった場所が密林に」と一続きの巨大な環境として塗り替わる。
 *
 * <p>各環境は 進行度({@link Stage} 2 段)× 地形(陸 / 川)の 4 つのバイオームキーを持つ(極相は 2026-10-30 に消した)。
 * 海だけは環境に関係なく {@link ModBiomes#OVERGROWN_OCEAN} などに統一。
 * 苗木({@link com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock})はその位置の環境の
 * {@link #treeFeature()} を使って木を生やすので、同じ苗木でも環境ごとに違う木になる。
 */
public enum OvergrowthVariant {

    /** 密林。従来の過繁茂バイオーム。 */
    JUNGLE("overgrown", "overgrowth",
            "overgrown_river", "overgrowth_river",
            "overgrown_tree", false),

    /** 湿地。浅く水没した池・水路・棚田状の段々に水が張る。木は木生シダ状の専用ブロック。 */
    SWAMP("overgrown_swamp", "overgrowth_swamp",
            "overgrown_swamp_river", "overgrowth_swamp_river",
            "overgrown_tree_swamp", true),

    /** 菌湿地帯。湿地と同じ水環境に、先細りの巨大な菌類の柱が所々に立つ。 */
    FUNGAL_SWAMP("overgrown_fungal_swamp", "overgrowth_fungal_swamp",
            "overgrown_fungal_swamp_river", "overgrowth_fungal_swamp_river",
            "overgrown_tree_fungal_swamp", true);

    /**
     * 汚染の進行度。数字が大きいほど進んでいる({@code ordinal()} がそのまま
     * {@code OvergrownBiomeConverter#rankOf} の順位になる)。
     *
     * <p>段階は<b>戻らない</b>。繁茂 → 過繁茂 と一方向にだけ進む。
     */
    public enum Stage {
        /** 繁茂。胞子濃度が 100% に達しただけで、地表の汚染はまだ薄い。 */
        OVERGROWTH,
        /** 過繁茂。地表の汚染割合が閾値を超えた状態。 */
        OVERGROWN
    }

    private final ResourceKey<Biome> overgrown;
    private final ResourceKey<Biome> overgrowth;
    private final ResourceKey<Biome> overgrownRiver;
    private final ResourceKey<Biome> overgrowthRiver;
    private final ResourceKey<ConfiguredFeature<?, ?>> treeFeature;
    private final boolean swamp;

    OvergrowthVariant(String overgrown, String overgrowth,
                      String overgrownRiver, String overgrowthRiver,
                      String treeFeature, boolean swamp) {
        this.overgrown = biomeKey(overgrown);
        this.overgrowth = biomeKey(overgrowth);
        this.overgrownRiver = biomeKey(overgrownRiver);
        this.overgrowthRiver = biomeKey(overgrowthRiver);
        this.treeFeature = ResourceKey.create(Registries.CONFIGURED_FEATURE, OvergrownVegetation.id(treeFeature));
        this.swamp = swamp;
    }

    private static ResourceKey<Biome> biomeKey(String path) {
        return ResourceKey.create(Registries.BIOME, OvergrownVegetation.id(path));
    }

    /** 陸の過繁茂バイオーム。 */
    public ResourceKey<Biome> overgrown() {
        return overgrown;
    }

    /** 陸の繁茂バイオーム。 */
    public ResourceKey<Biome> overgrowth() {
        return overgrowth;
    }

    /** 川の過繁茂バイオーム。 */
    public ResourceKey<Biome> overgrownRiver() {
        return overgrownRiver;
    }

    /** 川の繁茂バイオーム。 */
    public ResourceKey<Biome> overgrowthRiver() {
        return overgrowthRiver;
    }

    /** 陸 / 川 × 進行度 のキーを選ぶ。 */
    public ResourceKey<Biome> key(boolean river, Stage stage) {
        return switch (stage) {
            case OVERGROWTH -> river ? overgrowthRiver : overgrowth;
            case OVERGROWN -> river ? overgrownRiver : overgrown;
        };
    }

    /** この環境で苗木から育つ木の configured_feature。 */
    public ResourceKey<ConfiguredFeature<?, ?>> treeFeature() {
        return treeFeature;
    }

    /** 湿地系(湿地 / 菌湿地帯)か。浸水・棚田処理({@code SwampFlooder})の対象。 */
    public boolean isSwamp() {
        return swamp;
    }

    /** このバイオームキーがこの環境の 4 キーのいずれかか。 */
    public boolean owns(ResourceKey<Biome> key) {
        return key == overgrown || key == overgrowth || key == overgrownRiver || key == overgrowthRiver;
    }

    /**
     * バイオームがどの環境に属するか。海(統一の過繁茂した海)や繁茂系でないバイオームは {@code null}。
     */
    @Nullable
    public static OvergrowthVariant fromBiome(Holder<Biome> biome) {
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        return key == null ? null : fromKey(key);
    }

    /** {@link #fromBiome(Holder)} の {@link ResourceKey} 版。 */
    @Nullable
    public static OvergrowthVariant fromKey(ResourceKey<Biome> key) {
        for (OvergrowthVariant variant : values()) {
            if (variant.owns(key)) {
                return variant;
            }
        }
        return null;
    }
}
