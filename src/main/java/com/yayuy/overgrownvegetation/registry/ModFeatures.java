package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.FungalPillarFeature;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.StructureTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.grammar.GrammarStructureFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODが追加する {@link Feature}(worldgen の「地形装飾パーツ」の型)の登録。
 *
 * <p>ブロック・アイテムと違い、Feature 自体は「配置のしかた」を定義するだけで、
 * 実際にどんな見た目になるかは {@code data/overgrown_vegetation/worldgen/configured_feature/}
 * 以下の JSON(configured_feature)で指定する。
 */
public final class ModFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, OvergrownVegetation.MODID);

    /**
     * Structure Block で保存した {@code .nbt} をそのまま貼り付けるだけの木。
     * 使い方は README の「木の形を自分で作る」を参照。
     */
    public static final DeferredHolder<Feature<?>, StructureTreeFeature> STRUCTURE_TREE =
            FEATURES.register("structure_tree", () -> new StructureTreeFeature(StructureTreeFeature.Config.CODEC));

    /**
     * 条件判定を一切行わず、幹と葉が必要な位置だけを強制的に置き換える木。
     * バニラの {@code minecraft:tree} と違い、頭上が塞がっていても明るさが足りなくても必ず育つ。
     */
    public static final DeferredHolder<Feature<?>, ForcedTreeFeature> FORCED_TREE =
            FEATURES.register("forced_tree", () -> new ForcedTreeFeature(ForcedTreeFeature.Config.CODEC));

    /**
     * ごく低確率(0.0003%)でのみ生える巨大樹。円錐状の根元 + 直径7マスの円柱の幹 +
     * 5本の太い根 + 20本以上の枝 + 通常の約2倍の大きさの葉、全高80ブロック。
     * 形の詳細・パラメータは {@link GiantTreeFeature} を参照。
     */
    public static final DeferredHolder<Feature<?>, GiantTreeFeature> GIANT_TREE =
            FEATURES.register("giant_tree", () -> new GiantTreeFeature(GiantTreeFeature.Config.CODEC));

    /**
     * {@code data/<ns>/grammar/<path>.json}(structure-grammar 文法)を解釈して配置する汎用 Feature。
     * ブラウザ製作ツール {@code tools/structure-grammar/} で形を詰めて書き出す。詳細は
     * {@link GrammarStructureFeature}。
     */
    public static final DeferredHolder<Feature<?>, GrammarStructureFeature> GRAMMAR_STRUCTURE =
            FEATURES.register("grammar_structure",
                    () -> new GrammarStructureFeature(GrammarStructureFeature.Config.CODEC));

    /**
     * 菌湿地帯の「菌類の柱」。先細りの円柱を積み上げるだけの Feature。苗木がその位置の環境に
     * 応じて {@code overgrown_tree_fungal_swamp} 経由で選ぶ。詳細は {@link FungalPillarFeature}。
     */
    public static final DeferredHolder<Feature<?>, FungalPillarFeature> FUNGAL_PILLAR =
            FEATURES.register("fungal_pillar", () -> new FungalPillarFeature(FungalPillarFeature.Config.CODEC));

    /** 地脈の線に沿って地表に地脈の花と結晶を生やす(2026-10-22。{@link com.yayuy.overgrownvegetation.worldgen.LeyVeinPlantsFeature})。 */
    public static final DeferredHolder<Feature<?>, com.yayuy.overgrownvegetation.worldgen.LeyVeinPlantsFeature> LEY_VEIN_PLANTS =
            FEATURES.register("ley_vein_plants", () -> new com.yayuy.overgrownvegetation.worldgen.LeyVeinPlantsFeature(
                    com.yayuy.overgrownvegetation.worldgen.LeyVeinPlantsFeature.Config.CODEC));

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    private ModFeatures() {
    }
}
