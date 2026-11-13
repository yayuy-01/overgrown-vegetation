package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.client.corpse.CorpseBursts;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.model.GeoModel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * G1 の死体ブロックのモデル(2026-11-08 に G1 段階 0 の組み込みで書き直した)。種ごとに
 * {@code geo/<種>_corpse.geo.json}・{@code animations/<種>_corpse.animation.json}(corpse_burst・corpse_idle)と、
 * 感染体と同じ絵 {@code textures/entity/<種>.png}。
 *
 * <p>絵の切り替え(11-05 ユーザー「突き破る前は体の欠損や血の跡が無いテクスチャを用意して、最初はそれを表示する」): 突き破り
 * (corpse_burst)を流し始めてから swapTick({@link CorpseBursts})までは無傷の絵 {@code <種>_intact.png}、それから傷のある絵。
 * 突き破りを流さなかった死体(遅れて読み込んだ)は初めから傷のある絵。
 */
public class CorruptedCorpseModel extends GeoModel<CorruptedCorpseBlockEntity> {

    private record Assets(String species, ResourceLocation geo, ResourceLocation animation, ResourceLocation texture,
                          ResourceLocation intact) {
    }

    private static final Map<G1Species, Assets> ASSETS = new EnumMap<>(G1Species.class);

    private static Assets assets(G1Species s) {
        return ASSETS.computeIfAbsent(s, k -> {
            String id = k.assetName();
            return new Assets(id, OvergrownVegetation.id("geo/" + id + "_corpse.geo.json"),
                    OvergrownVegetation.id("animations/" + id + "_corpse.animation.json"),
                    OvergrownVegetation.id("textures/entity/" + id + ".png"),
                    OvergrownVegetation.id("textures/entity/" + id + "_intact.png"));
        });
    }

    /** 死体の絵を全部(傷のある絵と無傷の絵。描画の溜め場所の登録に使う)。 */
    static List<ResourceLocation> textures() {
        List<ResourceLocation> out = new ArrayList<>();
        for (G1Species s : G1Species.values()) {
            out.add(assets(s).texture());
            out.add(assets(s).intact());
        }
        return out;
    }

    @Nullable
    private static Assets assetsFor(CorruptedCorpseBlockEntity be) {
        G1Species s = G1Species.ofCorpse(be.getBlockState().getBlock());
        return s == null ? null : assets(s);
    }

    /** この死体ブロックに geo モデルがあるか。 */
    public static boolean hasGeoModel(Block block) {
        return G1Species.ofCorpse(block) != null;
    }

    private static Assets fallback() {
        return assets(G1Species.values()[0]);
    }

    @Override
    public ResourceLocation getModelResource(CorruptedCorpseBlockEntity be) {
        Assets a = assetsFor(be);
        return (a != null ? a : fallback()).geo();
    }

    @Override
    public ResourceLocation getAnimationResource(CorruptedCorpseBlockEntity be) {
        Assets a = assetsFor(be);
        return (a != null ? a : fallback()).animation();
    }

    @Override
    public ResourceLocation getTextureResource(CorruptedCorpseBlockEntity be) {
        Assets a = assetsFor(be);
        if (a == null) {
            return fallback().texture();
        }
        return isIntact(be, a.species()) ? a.intact() : a.texture();
    }

    /** まだ突き破る前(無傷の絵を出す)か。 */
    public static boolean isIntact(CorruptedCorpseBlockEntity be, String species) {
        long start = be.burstStartClientTick();
        if (start == Long.MIN_VALUE || be.getLevel() == null) {
            return false;
        }
        return be.getLevel().getGameTime() - start < CorpseBursts.swapTick(species);
    }
}
