package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 同じ形の「見た目のバリエーション」を複数持ち、<b>どれを描くかをブロックの位置ごとに選ぶ</b>
 * ブロックモデル。巻きシダの品質 {@link com.yayuy.overgrownvegetation.config.CurledFernDetail#REDUCED}
 * で、6 本ある葉のうち 3 本だけを残すときに「どの 3 本か」を草ごとに変えるために使う
 * (組み合わせは {@link GeoStaticGeometry#bakeGroupCombos} が作る)。
 *
 * <h2>モデルファイルを増やさずにバリエーションを増やせる理由</h2>
 * <p>バニラは {@code getQuads} に渡す {@link RandomSource} を、描くブロックの<b>座標から決まる種</b>
 * ({@code BlockState#getSeed(BlockPos)})で毎回 {@code setSeed} してから呼ぶ
 * ({@code ModelBlockRenderer#tesselateBlock})。つまりこの乱数は
 * <b>同じ座標なら常に同じ値・違う座標なら違う値</b>を返す。
 * だから {@code getQuads} の中で引くだけで、
 * <ul>
 *     <li>草ごとに違う見た目になり、</li>
 *     <li>同じ草は<b>何度チャンクを焼き直しても同じ見た目</b>(ちらつかない)</li>
 * </ul>
 * が同時に成り立つ。blockstate の {@code variants} を組み合わせのぶん増やす必要はない
 * (巻きシダは既に 64 通りの variants があるので、8 通り掛けて 512 通りの見え方になる)。
 *
 * <p>blockstate 側の {@code variants} の抽選({@code WeightedBakedModel})も<b>同じ</b>
 * {@code RandomSource} を使い、{@code nextLong()} を 1 回消費してからこのモデルへ渡してくる。
 * ここで引く値はその続きなので、variants の選択とは独立にばらける。
 *
 * <h2>面数は増えない</h2>
 * <p>{@link #combos} の各要素は<b>同じ {@link BakedQuad} オブジェクトを参照するリスト</b>で、
 * 頂点データは組み合わせの数によらず 1 本ぶんしか焼かれていない。
 * 1 フレームに描かれるのも必ず 1 通りだけなので、REDUCED の面数(約 48 面)はそのまま。
 *
 * <h2>「シダをすべて表示する」の切り替え</h2>
 * <p>葉 6 本ぜんぶを並べた {@link #allFronds} も<b>同時に持っている</b>。
 * ビデオ設定の「シダをすべて表示する」({@link CurledFernOption})が ON のフレームはこちらを返す。
 * つまり<b>切り替えにモデルの焼き直しは要らず</b>、チャンクメッシュの焼き直しだけで反映される
 * (参照するのは既に焼いてある {@link BakedQuad} なので、両方を持っていても頂点データは 1 本ぶん)。
 *
 * <p>{@code getQuads} 以外はすべて {@link #delegate}(組み合わせの 1 つを詰めた
 * {@code SimpleBakedModel})へ丸投げする。陰影の設定・描画レイヤー・パーティクルの絵・
 * 手持ちの変換はどの組み合わせでも同じなので、委譲して構わない。
 */
public final class RandomBoneGroupModel implements BakedModel {

    private final BakedModel delegate;
    /** 葉を 1 本も省かない面の一覧({@link CurledFernDetail#FULL} 用)。 */
    private final List<BakedQuad> allFronds;
    /** 見た目のバリエーション。1 要素が「1 本の草の面の一覧」。2 つ以上あることが前提。 */
    private final List<List<BakedQuad>> combos;

    public RandomBoneGroupModel(BakedModel delegate, List<BakedQuad> allFronds, List<List<BakedQuad>> combos) {
        this.delegate = delegate;
        this.allFronds = allFronds;
        this.combos = combos;
    }

    private List<BakedQuad> pick(@Nullable Direction direction, RandomSource random) {
        // 面はすべて「カリング無し」側(direction == null)に入れてあるので、
        // 向きを指定して呼ばれたぶんには何も返さない(SimpleBakedModel と同じ振る舞い)。
        if (direction != null) {
            return List.of();
        }
        if (GeoStaticGeometry.showAllFronds()) {
            return allFronds;
        }
        return combos.get(random.nextInt(combos.size()));
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return pick(direction, random);
    }

    /**
     * NeoForge 側の拡張版。既定の実装は上の 3 引数版へ委譲するので上書きしなくても動くが、
     * 実際に呼ばれるのはこちらなので明示しておく。
     */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random,
                                    ModelData data, @Nullable RenderType renderType) {
        return pick(direction, random);
    }

    // --- 以下はすべて delegate へ丸投げ(組み合わせによって変わらないもの) ---

    @Override
    public boolean useAmbientOcclusion() {
        return delegate.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return delegate.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return delegate.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return delegate.isCustomRenderer();
    }

    @Override
    @SuppressWarnings("deprecation")
    public TextureAtlasSprite getParticleIcon() {
        return delegate.getParticleIcon();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return delegate.getParticleIcon(data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ItemTransforms getTransforms() {
        return delegate.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return delegate.getOverrides();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return delegate.getRenderTypes(state, random, data);
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
        return delegate.getRenderTypes(itemStack, fabulous);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        return delegate.getModelData(level, pos, state, modelData);
    }
}
