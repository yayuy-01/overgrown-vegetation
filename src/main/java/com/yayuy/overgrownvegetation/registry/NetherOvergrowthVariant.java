package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>ネザーの繁茂系バイオーム</b>(2026-10-02 ユーザー指定: オーバーワールドと同じようにネザーにも専用のバイオームをいくつか)。
 *
 * <p>バニラのネザーのバイオーム 5 種それぞれに、繁茂・過繁茂の 2 段階(計 10 種)。どれを選ぶかは<b>元のバイオーム</b>で決まる
 * (オーバーワールドの地域マップ {@link OvergrowthVariant} は使わない —— ネザーに湿地や密林は無い)。
 * 極相はオーバーワールド専用(極相の判定は座標だけの台帳で動く)なので作らず、極相の段階を求められたら過繁茂を返す。
 *
 * <p>{@link OvergrowthVariant} に足さないのは、{@code /ov region} がその全種類を地域マップの中から探すため
 * (ネザーの種類は地域マップに出てこないので、探し終わらなくなる)。
 */
public enum NetherOvergrowthVariant {
    WASTES("nether_wastes", Biomes.NETHER_WASTES),
    CRIMSON("crimson_forest", Biomes.CRIMSON_FOREST),
    WARPED("warped_forest", Biomes.WARPED_FOREST),
    SOUL_VALLEY("soul_sand_valley", Biomes.SOUL_SAND_VALLEY),
    BASALT("basalt_deltas", Biomes.BASALT_DELTAS);

    private final ResourceKey<Biome> vanilla;
    private final ResourceKey<Biome> overgrowth;
    private final ResourceKey<Biome> overgrown;

    NetherOvergrowthVariant(String suffix, ResourceKey<Biome> vanilla) {
        this.vanilla = vanilla;
        this.overgrowth = key("overgrowth_" + suffix);
        this.overgrown = key("overgrown_" + suffix);
    }

    private static ResourceKey<Biome> key(String path) {
        return ResourceKey.create(Registries.BIOME, OvergrownVegetation.id(path));
    }

    public ResourceKey<Biome> overgrowth() {
        return overgrowth;
    }

    public ResourceKey<Biome> overgrown() {
        return overgrown;
    }

    /** その段階のキー。極相は過繁茂と同じ(ネザーに極相は無い)。 */
    public ResourceKey<Biome> key(OvergrowthVariant.Stage stage) {
        return stage == OvergrowthVariant.Stage.OVERGROWTH ? overgrowth : overgrown;
    }

    /**
     * そのバイオームに対応する種類。ネザーの繁茂系ならその種類、バニラのネザーのバイオームなら対応する種類、
     * どれでもなければ(他 MOD のネザーのバイオームなど)荒地。
     */
    public static NetherOvergrowthVariant of(Holder<Biome> biome) {
        for (NetherOvergrowthVariant v : values()) {
            if (biome.is(v.vanilla) || biome.is(v.overgrowth) || biome.is(v.overgrown)) {
                return v;
            }
        }
        return WASTES;
    }

    /** ネザーの繁茂系のキーならその種類、そうでなければ null。 */
    @Nullable
    public static NetherOvergrowthVariant fromKey(ResourceKey<Biome> key) {
        for (NetherOvergrowthVariant v : values()) {
            if (key.equals(v.overgrowth) || key.equals(v.overgrown)) {
                return v;
            }
        }
        return null;
    }

    public static List<ResourceKey<Biome>> overgrownKeys() {
        List<ResourceKey<Biome>> out = new ArrayList<>();
        for (NetherOvergrowthVariant v : values()) {
            out.add(v.overgrown);
        }
        return out;
    }

    public static List<ResourceKey<Biome>> overgrowthKeys() {
        List<ResourceKey<Biome>> out = new ArrayList<>();
        for (NetherOvergrowthVariant v : values()) {
            out.add(v.overgrowth);
        }
        return out;
    }
}
