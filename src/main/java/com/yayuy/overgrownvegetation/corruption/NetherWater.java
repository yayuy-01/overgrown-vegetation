package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.NetherOvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * <b>過繁茂したネザーでは水を置ける</b>(2026-10-02 ユーザー指定: 過繁茂後のバイオームで水が流れるようにしたい)。
 *
 * <p>バニラはネザー(次元の種類が {@code ultrawarm})で水を置くと蒸発させる。判定は 2 か所 ——
 * NeoForge の {@code FluidType#isVaporizedOnPlacement}(液体の型ごと)と、{@code BucketItem#emptyContents} の
 * {@code ultraWarm()} の直接の判定 —— なので、両方をミックスインでこの判定へつなぐ
 * ({@code mixin/FluidTypeNetherWaterMixin}・{@code mixin/BucketItemNetherWaterMixin})。
 * ディスペンサーもバケツの {@code emptyContents} を通るので同じ。氷が溶ける・濡れたスポンジが乾くのは今のまま(蒸発する)。
 */
public final class NetherWater {

    /** その場所はネザーの<b>過繁茂</b>(繁茂の段階は含まない)バイオームで、水を置いても蒸発しないか。 */
    public static boolean allowed(Level level, BlockPos pos) {
        if (!level.dimensionType().ultraWarm()) {
            return false;
        }
        Holder<Biome> biome = level.getBiome(pos);
        return ModBiomes.isFullyOvergrown(biome)
                && biome.unwrapKey().map(NetherOvergrowthVariant::fromKey).orElse(null) != null;
    }

    private NetherWater() {
    }
}
