package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature;
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

    /** 封印木(シギラリア。腕の型と柱の型。2026-11-01)。{@code overgrown_tree} の random_selector から 25% で選ばれる(仮決め)。 */
    public static final DeferredHolder<Feature<?>, com.yayuy.overgrownvegetation.worldgen.SigillariaTreeFeature> SIGILLARIA_TREE =
            FEATURES.register("sigillaria_tree", () -> new com.yayuy.overgrownvegetation.worldgen.SigillariaTreeFeature(
                    com.yayuy.overgrownvegetation.worldgen.SigillariaTreeFeature.Config.CODEC));

    /** 木生シダ(プサロニウス。一本幹と株立ち。2026-11-01)。苗木の selector には入れず、{@code CorruptionSpreader} が苗木の代わりに直接置く(仮決め 15%)。 */
    public static final DeferredHolder<Feature<?>, com.yayuy.overgrownvegetation.worldgen.TreeFernFeature> TREE_FERN =
            FEATURES.register("tree_fern", () -> new com.yayuy.overgrownvegetation.worldgen.TreeFernFeature(
                    com.yayuy.overgrownvegetation.worldgen.TreeFernFeature.Config.CODEC));

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

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    private ModFeatures() {
    }
}
