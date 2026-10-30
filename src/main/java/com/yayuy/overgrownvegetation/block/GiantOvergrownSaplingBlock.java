package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「巨大樹の苗木」。植えるとランダムティックで必ず巨大樹
 * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})に育つ、
 * クリエイティブタブからのみ入手できる特別な苗木。
 *
 * <p>侵食システム({@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} /
 * {@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry})からは一切参照されないので
 * 自然には生えない。対応アイテムは {@link com.yayuy.overgrownvegetation.registry.ModItems} 側で
 * 常時エンチャント発光({@code DataComponents.ENCHANTMENT_GLINT_OVERRIDE})にしてある。
 *
 * <p>{@link CorruptedSaplingBlock} と違い、汚染された土だけでなくバニラの土
 * ({@code #minecraft:dirt})・耕地の上にも植えられる(クリエイティブで好きな場所に置いて
 * 試せるように)。成長判定(明るさ無視・7分の2抽選)は {@link CorruptedSaplingBlock} から継承。
 *
 * <h2>「必ず巨大樹に育つ」ための上書き (2026-09-11)</h2>
 * <p>この苗木が育たなくなる原因が 2 つあったので、どちらもここで外している。
 * <ul>
 *   <li>{@link CorruptedSaplingBlock#advanceTree} は苗木の位置の環境
 *       ({@link com.yayuy.overgrownvegetation.registry.OvergrowthVariant})に応じて育つ木を
 *       差し替えるが、湿地・菌湿地帯の地域に植えるとこの苗木まで普通の湿地の木・菌類の柱に
 *       化けていた。{@link #usesVariantTree()} を {@code false} にして差し替えの対象から外す。</li>
 *   <li>{@link GiantTreeFeature} は「既存の巨大樹が {@code giantTreeMinSpacing}(既定 384)以内に
 *       ある」「空が見えない場所」のときは<b>何も言わずに</b>生成を中止するため、一度巨大樹を
 *       生やした場所の近くでは骨粉を何個使っても苗木のままだった。この苗木から育てる間だけ
 *       {@link GiantTreeFeature#beginSaplingGrowth()} でその判定を飛ばす
 *       (config {@code giantTreeSaplingForceGrowth} で従来どおりに戻せる)。</li>
 * </ul>
 *
 * <p>ただし <b>TD の侵攻の間(予告中も)は育たず</b>、苗木のまま侵攻が終わるのを待つ
 * ({@link GiantTreeFeature#pausedByTd}。ユーザー指定 2026-09-26「TD 時のみ苗木が巨大樹にならないように」)。
 */
public class GiantOvergrownSaplingBlock extends CorruptedSaplingBlock {

    public static final MapCodec<GiantOvergrownSaplingBlock> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(TreeGrower.CODEC.fieldOf("tree").forGetter(block -> block.treeGrower), propertiesCodec())
                    .apply(instance, GiantOvergrownSaplingBlock::new));

    public GiantOvergrownSaplingBlock(TreeGrower treeGrower, BlockBehaviour.Properties properties) {
        super(treeGrower, properties);
    }

    @Override
    public MapCodec<? extends SaplingBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return super.mayPlaceOn(state, level, pos)
                || state.is(BlockTags.DIRT)
                || state.is(Blocks.FARMLAND);
    }

    /** 環境ごとの木への差し替えはしない —— この苗木はどこに植えても巨大樹。 */
    @Override
    protected boolean usesVariantTree() {
        return false;
    }

    /**
     * {@link CorruptedSaplingBlock#advanceTree}(= この苗木では素のバニラ処理 → 巨大樹の
     * {@link TreeGrower})を、{@link GiantTreeFeature} の間隔・洞窟判定を飛ばした状態で呼ぶ。
     * それでも育たなかった場合(= ブロックがまだ苗木のまま)は理由が分かるようログに残す。
     */
    @Override
    public void advanceTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (state.getValue(STAGE) == 0) {
            // まだ 1 段目 —— STAGE を進めるだけ(巨大樹の生成には入らない)。
            super.advanceTree(level, pos, state, random);
            return;
        }
        if (GiantTreeFeature.pausedByTd(level)) {
            // TD の侵攻の間は育たずに苗木のまま待つ(ユーザー指定 2026-09-26)。終わってからの抽選・骨粉で育つ。
            // 育たなかった警告も出さない(理由は分かっている)。
            return;
        }
        GiantTreeFeature.beginSaplingGrowth();
        try {
            super.advanceTree(level, pos, state, random);
        } finally {
            GiantTreeFeature.endSaplingGrowth();
        }
        if (level.getBlockState(pos).is(this)) {
            OvergrownVegetation.LOGGER.warn("巨大樹の苗木 {} が育ちませんでした(苗木のまま)。"
                    + "configured_feature overgrown_vegetation:overgrown_tree_giant が読み込まれているか、"
                    + "ワールドの高さ上限までの余裕があるかを確認してください。", pos);
        }
    }
}
