package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.model.GeoModel;

import javax.annotation.Nullable;
import java.util.List;
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

    /**
     * geo モデルを持つ死体の種(2026-10-09 から: 死体は {@code geo/<種>_corpse.geo.json}、絵は感染体の {@code textures/entity/<種>.png})。
     * ブロックは {@code <種>_corpse}。ここに無いブロックは通常のブロックモデル描画。種を足すときはここに 1 つ足す(Map.of の 10 組の上限があったので名前の並びにした)。
     */
    private static final String[] GEO_SPECIES = {
            "overgrown_sheep", "overgrown_pig", "overgrown_creeper", "overgrown_spider", "overgrown_villager", "overgrown_cow",
            "overgrown_zombie", "overgrown_baby_zombie", "overgrown_skeleton", "overgrown_enderman", "overgrown_chicken",
            "overgrown_piglin", "overgrown_piglin_brute", "overgrown_zombified_piglin", "overgrown_wither_skeleton",
            "overgrown_hoglin", "overgrown_zoglin", "overgrown_strider",
            "overgrown_iron_golem", "overgrown_warden"};

    private static Map<Block, Assets> assetsMap;

    /** geo モデルを持つ死体ブロック → geo/テクスチャ(初めて使うときに作る。ブロックの登録より後)。 */
    private static Map<Block, Assets> assets() {
        if (assetsMap == null) {
            Map<Block, Assets> m = new java.util.HashMap<>();
            for (String id : GEO_SPECIES) {
                Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(OvergrownVegetation.id(id + "_corpse"));
                m.put(b, new Assets(OvergrownVegetation.id("geo/" + id + "_corpse.geo.json"), textureOf(id)));
            }
            assetsMap = m;
        }
        return assetsMap;
    }

    /** 種 {@code id} の死体の絵(= 同名の過繁茂した Mob のもの)。 */
    private static ResourceLocation textureOf(String id) {
        return OvergrownVegetation.id("textures/entity/" + id + ".png");
    }

    /**
     * geo モデルを持つ死体の絵を全部({@link #GEO_SPECIES} の順)。ブロックを引かずに名前だけで作るので、描画の溜め場所を
     * 登録するとき({@link CorruptedCorpseRenderer#BATCHED_TYPES})にも使える(2026-10-10)。
     */
    static List<ResourceLocation> textures() {
        List<ResourceLocation> out = new java.util.ArrayList<>(GEO_SPECIES.length);
        for (String id : GEO_SPECIES) {
            out.add(textureOf(id));
        }
        return out;
    }

    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/overgrown_corpse.animation.json");

    /** この死体ブロックに専用 geo モデルがあるか(= GeoBlockRenderer で描画すべきか)。 */
    public static boolean hasGeoModel(Block block) {
        return assets().containsKey(block);
    }

    /** {@code block} の geo モデル。専用モデルが無い場合は羊の geo(フォールバック)。 */
    public static ResourceLocation geoFor(Block block) {
        Assets assets = assets().get(block);
        return assets != null ? assets.geo() : assets().get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).geo();
    }

    /** {@code block} のテクスチャ(= 同名の過繁茂した Mob のもの)。無い場合は羊のもの。 */
    public static ResourceLocation textureFor(Block block) {
        Assets assets = assets().get(block);
        return assets != null ? assets.texture() : assets().get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).texture();
    }

    /** 死体アイテムのモデルも同じ空アニメーションを共用する。 */
    public static ResourceLocation animation() {
        return ANIMATION;
    }

    @Nullable
    private static Assets assetsFor(CorruptedCorpseBlockEntity animatable) {
        BlockState state = animatable.getBlockState();
        return assets().get(state.getBlock());
    }

    @Override
    public ResourceLocation getModelResource(CorruptedCorpseBlockEntity animatable) {
        Assets assets = assetsFor(animatable);
        return assets != null ? assets.geo() : assets().get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).geo();
    }

    @Override
    public ResourceLocation getTextureResource(CorruptedCorpseBlockEntity animatable) {
        Assets assets = assetsFor(animatable);
        return assets != null ? assets.texture() : assets().get(ModBlocks.OVERGROWN_SHEEP_CORPSE.get()).texture();
    }

    @Override
    public ResourceLocation getAnimationResource(CorruptedCorpseBlockEntity animatable) {
        return ANIMATION;
    }
}
