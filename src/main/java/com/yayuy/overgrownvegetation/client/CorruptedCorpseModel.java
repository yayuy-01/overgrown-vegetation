package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.model.GeoModel;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 種族専用の GeckoLib モデルを持つ汚染死体({@link com.yayuy.overgrownvegetation.block.GeoCorruptedCorpseBlock})の
 * モデル定義。ブロックの種類に応じて、対応する geo と「同名の過繁茂した Mob」のテクスチャを返す。
 *
 * <ul>
 *     <li>羊: {@code geo/overgrown_sheep_corpse.geo.json} + {@code textures/entity/overgrown_sheep.png}</li>
 *     <li>豚: {@code geo/overgrown_pig_corpse.geo.json} + {@code textures/entity/overgrown_pig.png}</li>
 *     <li>クリーパー: {@code geo/overgrown_creeper_corpse.geo.json} + {@code textures/entity/overgrown_creeper.png}</li>
 * </ul>
 *
 * <p>アニメーションはしないが、GeckoLib はアニメーションリソースの存在を前提にしているため
 * 中身が空の {@code animations/overgrown_corpse.animation.json} を共通で指す。
 */
public class CorruptedCorpseModel extends GeoModel<CorruptedCorpseBlockEntity> {

    private record Assets(ResourceLocation geo, ResourceLocation texture) {
    }

    /** geo モデルを持つ死体ブロック → geo/テクスチャ。ここに無いブロックは通常のブロックモデル描画。 */
    private static final Map<Block, Assets> ASSETS = Map.of(
            ModBlocks.OVERGROWN_SHEEP_CORPSE.get(), new Assets(
                    OvergrownVegetation.id("geo/overgrown_sheep_corpse.geo.json"),
                    OvergrownVegetation.id("textures/entity/overgrown_sheep.png")),
            ModBlocks.OVERGROWN_PIG_CORPSE.get(), new Assets(
                    OvergrownVegetation.id("geo/overgrown_pig_corpse.geo.json"),
                    OvergrownVegetation.id("textures/entity/overgrown_pig.png")),
            ModBlocks.OVERGROWN_CREEPER_CORPSE.get(), new Assets(
                    OvergrownVegetation.id("geo/overgrown_creeper_corpse.geo.json"),
                    OvergrownVegetation.id("textures/entity/overgrown_creeper.png")),
            ModBlocks.OVERGROWN_SPIDER_CORPSE.get(), new Assets(
                    OvergrownVegetation.id("geo/overgrown_spider_corpse.geo.json"),
                    OvergrownVegetation.id("textures/entity/overgrown_spider.png"))
    );

    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/overgrown_corpse.animation.json");

    /** この死体ブロックに専用 geo モデルがあるか(= GeoBlockRenderer で描画すべきか)。 */
    public static boolean hasGeoModel(Block block) {
        return ASSETS.containsKey(block);
    }

    /** {@code block} の geo モデル。専用モデルが無い場合は羊の geo(フォールバック)。 */
    public static ResourceLocation geoFor(Block block) {
        Assets assets = ASSETS.get(block);
        return assets != null ? assets.geo() : ASSETS.get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).geo();
    }

    /** {@code block} のテクスチャ(= 同名の過繁茂した Mob のもの)。無い場合は羊のもの。 */
    public static ResourceLocation textureFor(Block block) {
        Assets assets = ASSETS.get(block);
        return assets != null ? assets.texture() : ASSETS.get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).texture();
    }

    /** 死体アイテムのモデルも同じ空アニメーションを共用する。 */
    public static ResourceLocation animation() {
        return ANIMATION;
    }

    @Nullable
    private static Assets assetsFor(CorruptedCorpseBlockEntity animatable) {
        BlockState state = animatable.getBlockState();
        return ASSETS.get(state.getBlock());
    }

    @Override
    public ResourceLocation getModelResource(CorruptedCorpseBlockEntity animatable) {
        Assets assets = assetsFor(animatable);
        return assets != null ? assets.geo() : ASSETS.get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).geo();
    }

    @Override
    public ResourceLocation getTextureResource(CorruptedCorpseBlockEntity animatable) {
        Assets assets = assetsFor(animatable);
        return assets != null ? assets.texture() : ASSETS.get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).texture();
    }

    @Override
    public ResourceLocation getAnimationResource(CorruptedCorpseBlockEntity animatable) {
        return ANIMATION;
    }
}
