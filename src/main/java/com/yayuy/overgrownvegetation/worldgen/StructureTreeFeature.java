package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/**
 * バニラの {@code minecraft:tree}(トランクプレイサー/フォリッジプレイサーの組み合わせで
 * 自動生成する木)とは違い、Structure Block で保存した {@code .nbt} をそのまま貼り付けるだけの木。
 *
 * <p>手作業で組んだ固定形状を、ランダム性なし(向きの回転だけ)でそのまま生やしたい場合に使う。
 * 苗木({@link com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock})が育つ位置(苗木があった
 * ブロック=地面の高さ)がそのまま構造物側のローカル座標 (0,0,0) になるので、Structure Block を
 * 「保存したい木の幹の一番下」に置いて保存するのが基本(詳しくは README の
 * 「木の形を自分で作る」を参照)。
 *
 * <p>{@link com.yayuy.overgrownvegetation.registry.ModBlocks#OVERGROWN_TREE_FEATURE} が指す
 * configured_feature の {@code type} をこのクラスの登録名({@code overgrown_vegetation:structure_tree})
 * に変えるだけで、苗木の成長先をバニラ風の自動生成木からこちらへ切り替えられる。
 */
public class StructureTreeFeature extends Feature<StructureTreeFeature.Config> {

    public StructureTreeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        ServerLevel level = context.level().getLevel();
        StructureTemplateManager templates = level.getStructureManager();
        Optional<StructureTemplate> template = templates.get(context.config().structure());
        if (template.isEmpty()) {
            // data/<namespace>/structure/<path>.nbt が見つからない。苗木は消えず、
            // 次のランダムティックでもう一度この場所から成長を試みる。
            return false;
        }

        RandomSource random = context.random();
        BlockPos pos = context.origin().offset(context.config().offset());

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                // 構造物側の「空気」はそのまま透過させ(既存の地形を消さない)、
                // Structure Block 自身の残骸も無視する。
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_AND_AIR);
        if (context.config().randomizeRotation()) {
            settings.setRotation(Rotation.getRandom(random));
        }

        return template.get().placeInWorld(level, pos, pos, settings, random, Block.UPDATE_ALL);
    }

    /**
     * @param structure          貼り付ける構造物の ID({@code data/<namespace>/structure/<path>.nbt}、
     *                           拡張子とフォルダ名 "structure" は書かない)
     * @param offset             構造物のローカル座標 (0,0,0) を苗木の位置からどれだけずらして置くか
     * @param randomizeRotation  貼り付けるたびに Y 軸まわりでランダムに回転させるか
     *                           (完全固定の形でも、向きだけはばらけさせて不自然な並びを防げる)
     */
    public record Config(ResourceLocation structure, BlockPos offset, boolean randomizeRotation)
            implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        ResourceLocation.CODEC.fieldOf("structure").forGetter(Config::structure),
                        BlockPos.CODEC.optionalFieldOf("offset", BlockPos.ZERO).forGetter(Config::offset),
                        Codec.BOOL.optionalFieldOf("randomize_rotation", true).forGetter(Config::randomizeRotation))
                .apply(instance, Config::new));
    }
}
