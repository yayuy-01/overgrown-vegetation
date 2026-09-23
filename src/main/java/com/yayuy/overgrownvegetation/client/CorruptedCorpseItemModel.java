package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.GeoCorpseBlockItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 死体アイテム({@link GeoCorpseBlockItem})用のモデル定義。ブロック版
 * ({@link CorruptedCorpseModel})と同じ geo/テクスチャを、アイテムが指すブロックから引く。
 */
public class CorruptedCorpseItemModel extends GeoModel<GeoCorpseBlockItem> {

    @Override
    public ResourceLocation getModelResource(GeoCorpseBlockItem animatable) {
        return CorruptedCorpseModel.geoFor(animatable.getBlock());
    }

    @Override
    public ResourceLocation getTextureResource(GeoCorpseBlockItem animatable) {
        return CorruptedCorpseModel.textureFor(animatable.getBlock());
    }

    @Override
    public ResourceLocation getAnimationResource(GeoCorpseBlockItem animatable) {
        return CorruptedCorpseModel.animation();
    }
}
