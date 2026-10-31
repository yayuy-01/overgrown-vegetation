package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import com.yayuy.overgrownvegetation.worldgen.SaplingOfflineGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/**
 * 汚染された苗木。最終的に {@link ModBlocks} の汚染された原木・葉で構成される木へ育つ
 * (データパック側の設定は {@code data/overgrown_vegetation/worldgen/configured_feature/overgrown_tree.json} を参照)。
 *
 * <h2>環境ごとに違う木になる (2026-09-10)</h2>
 * <p>同じ苗木でも、植わっている位置のバイオームの環境({@link OvergrowthVariant})に応じて
 * 育つ木が変わる。密林なら従来の {@code overgrown_tree}、湿地なら木生シダ状の
 * {@code overgrown_tree_swamp}、菌湿地帯なら菌類の柱 {@code overgrown_tree_fungal_swamp}。
 * 環境が判定できない場所(繁茂系でないバイオーム、海)では従来の木になる。
 * バニラの {@link TreeGrower} はレベルを受け取らずバイオームを見られないので、
 * {@link #advanceTree} をここで上書きして configured_feature を選び直している。
 *
 * <p>バニラの {@link SaplingBlock} は「頭上の明るさが 9 以上」という条件を満たしたときだけ
 * ランダムティックで成長を試みるが、「広がる密林」のコンセプト上、洞窟の中や自分の作った
 * 建物の下でも侵食は止まらず木は育ってほしいので、この明るさ判定は完全に外してある
 * ({@link #randomTick} 参照)。さらに実際に育つ先の Feature
 * ({@code overgrown_vegetation:forced_tree}, {@link com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature})
 * 側も、幹・葉が必要な位置さえあれば既存のブロックの有無に関わらず必ず成長を成功させる
 * (バニラの {@code minecraft:tree} は必要な空間のどこか1箇所でも塞がっていると木全体の
 * 生成をまるごと諦めてしまうため、あえて使っていない)。
 *
 * <p>バニラの {@code SaplingBlock#mayPlaceOn} は {@code #minecraft:dirt} タグしか見ないため、
 * このままでは汚染された草・土({@link ModBlocks#OVERGROWN_GRASS_BLOCK} /
 * {@link ModBlocks#OVERGROWN_DIRT})の上に置けない。ここで上書きして許可する
 * (汚染された砂・湿地の泥も同様だったが、2026-10-31 に砂は削除・泥は parked へ退避)。
 *
 * <p>{@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が汚染直後の
 * 草・土の露出した上面に確率で直接植えるほか、{@code #minecraft:saplings} タグに一致する
 * バニラの苗木(オークの苗木など)が汚染された際の変換先にもなっている
 * ({@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry} 参照)。
 * 侵食が既存の森を飲み込むと、そこに生えていた苗木も汚染された木へ育っていく。
 *
 * <p>抽選で巨大樹の目(0.25%)が出ても、TD の侵攻の間は巨大樹にならず苗木のまま残り、次の抽選でふつうの木になる
 * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature#pausedByTd}。ユーザー指定 2026-09-26)。
 */
public class CorruptedSaplingBlock extends SaplingBlock {

    public static final MapCodec<CorruptedSaplingBlock> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(TreeGrower.CODEC.fieldOf("tree").forGetter(block -> block.treeGrower), propertiesCodec())
                    .apply(instance, CorruptedSaplingBlock::new));

    public CorruptedSaplingBlock(TreeGrower treeGrower, BlockBehaviour.Properties properties) {
        super(treeGrower, properties);
    }

    @Override
    public MapCodec<? extends SaplingBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || state.is(ModBlocks.OVERGROWN_DIRT.get())
                || isCoveredGround(level, pos, state);
    }

    /**
     * 汚染の対応表に無く、苔で覆っただけのブロック(コンクリート・鉄ブロックなど)で、上の面が固いか(2026-10-10、ユーザー指定
     * 「対応していないブロックを汚染した際にもその上に植物や苗木が生えるように」)。苗木はここにも植わる。
     * この MOD の木は根元の下を土に置き換えないので、育っても覆ったブロックはそのまま残る。
     */
    public static boolean isCoveredGround(BlockGetter level, BlockPos pos, BlockState state) {
        return state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)
                && com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, pos);
    }

    /**
     * バニラの {@code SaplingBlock#randomTick} から頭上の明るさ判定
     * ({@code getMaxLocalRawBrightness(pos.above()) >= 9})だけを取り除いたもの。
     * 抽選確率はバニラの 7 分の 1 に対して 7 分の 4(2026-09-11: 「他の苗木の倍の速さで育つ」要望で 7 分の 2、
     * 10-31 ユーザー「成長速度を上げてほしい」でさらに 2 倍。倍率は仮決め)。randomTickSpeed 3 で 1 マスは平均 68 秒に 1 回選ばれ、
     * 2 段(STAGE 0 → 1 → 木)なので、木になるまで平均 3.5 回 ≒ 4 分(前は 7 回 ≒ 8 分)。
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // この機能より前から生えていた苗木も、ランダムティックを受けた時点で設置時刻を記録する
        // (追いつき成長 SaplingOfflineGrowth 用。記録済みなら map の参照 1 回で終わる)。
        SaplingOfflineGrowth.ensureRecorded(level, pos);
        if (random.nextInt(7) < 4) {
            this.advanceTree(level, pos, state, random);
        }
    }

    /**
     * 追いつき成長({@link SaplingOfflineGrowth}): 読み込まれていない間に育つはずだった苗木を、
     * STAGE を飛ばして一気に最終成長させる。
     */
    public void growNow(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        this.advanceTree(level, pos, state.setValue(STAGE, 1), random);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        // 苗木が新しく置かれた(STAGE の書き換えなど同じブロック同士の更新は除く)→ 設置時刻を記録。
        if (!oldState.is(this) && level instanceof ServerLevel serverLevel) {
            SaplingOfflineGrowth.record(serverLevel, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // 壊された/育った → 記録を消す。
        if (!newState.is(this) && level instanceof ServerLevel serverLevel) {
            SaplingOfflineGrowth.forget(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * この苗木が「その位置の環境({@link OvergrowthVariant})に応じて育つ木を差し替える」対象か。
     * 既定は {@code true}(密林/湿地/菌湿地帯でそれぞれ違う木になる)。
     * {@link com.yayuy.overgrownvegetation.block.GiantOvergrownSaplingBlock 巨大樹の苗木} だけは
     * どの環境でも必ず巨大樹に育ってほしいので {@code false} を返して差し替えを止める。
     */
    protected boolean usesVariantTree() {
        return true;
    }

    /**
     * バニラの {@code SaplingBlock#advanceTree} → {@code TreeGrower#growTree} と同じ手順だが、
     * configured_feature をその位置の環境({@link OvergrowthVariant#fromBiome})から選ぶ。
     * 環境が判定できなければ従来どおり {@link TreeGrower} に任せる。
     */
    @Override
    public void advanceTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (state.getValue(STAGE) == 0) {
            level.setBlock(pos, state.cycle(STAGE), 4);
            return;
        }
        if (!usesVariantTree()) {
            super.advanceTree(level, pos, state, random);
            return;
        }
        OvergrowthVariant variant = OvergrowthRegionMap.blendedVariantAt(level, pos.getX(), pos.getZ(), 1);
        if (variant == null || variant == OvergrowthVariant.JUNGLE) {
            super.advanceTree(level, pos, state, random);
            return;
        }
        growVariantTree(level, pos, state, random, variant.treeFeature());
    }

    private static void growVariantTree(ServerLevel level, BlockPos pos, BlockState saplingState, RandomSource random,
                                        ResourceKey<ConfiguredFeature<?, ?>> featureKey) {
        Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolder(featureKey)
                .orElse(null);
        if (holder == null) {
            return;
        }
        // 苗木を消してから置く(失敗したら戻す)—— バニラの TreeGrower#growTree と同じ。
        BlockState replaced = level.getFluidState(pos).createLegacyBlock();
        level.setBlock(pos, replaced, 4);
        if (holder.value().place(level, level.getChunkSource().getGenerator(), random, pos)) {
            if (level.getBlockState(pos) == replaced) {
                level.sendBlockUpdated(pos, saplingState, replaced, 2);
            }
        } else {
            level.setBlock(pos, saplingState, 4);
        }
    }
}
