package com.yayuy.overgrownvegetation.client;

import com.mojang.logging.LogUtils;
import com.mojang.math.Transformation;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「覆われて動かなくなったブロック」({@code block/OvergrownHuskBlock})の模型(2026-09-22)。
 *
 * <p><b>元のブロックの模型をそのまま借りて</b>、その面をなぞった苔の面を足すだけ。
 * 他 MOD のかまどでも、<b>その形・そのテクスチャのまま</b>苔に覆われた姿になる。
 * 元のブロック状態は {@link OvergrownHuskBlockEntity#MIMIC} で {@link ModelData} 経由に届く。
 *
 * <h2>描画層を 1 つに寄せている理由</h2>
 * <p>{@link #getRenderTypes} は {@code cutoutMipped} だけを返す。元のブロックが
 * 不透明層と切り抜き層の両方を使っていても、まとめてここへ描く。こうしないと、
 * <b>苔をどの層に置くかで面の取り直しが 2 回必要になり</b>、
 * 乱数(模型の差し替え候補)が 2 回進んで<b>本体と苔で別の見た目を選んでしまう</b>。
 * 1 回だけ取って、その同じ面から苔を作れば必ず一致する。
 *
 * <p>元が半透明(色ガラスなど)だった場合は切り抜きとして描かれる(半透明が消える)が、
 * 覆われて死んだブロックなので気にしない。
 *
 * <h2>面が出てこないブロックの逃げ道(2026-09-23、報告)</h2>
 * <p>Create の歯車を汚染したら<b>見えなくなった</b>という報告。調べると
 * <b>{@code RenderShape.MODEL} は「模型を持っている」という意味ではない</b>。
 * 歯車のブロック模型({@code BracketedKineticBlockModel})は
 * <b>{@link ModelData} にブラケットの情報が入っていなければ面を 1 枚も返さない</b>作りで、
 * 見えている歯車そのものは描画器(と Flywheel)が毎フレーム描いている。回る物は静的な面では描けない。
 *
 * <p>そこで面が 1 枚も出てこなかったときは<b>持ち物の模型</b>へ回る({@link #findSource})。
 * 他 MOD の機械やパーツはたいてい {@code item/xxx.json} が {@code "parent": "<ブロック模型>"} で、
 * <b>ブロック本体の立体がそのまま入っている</b>(Create の歯車もこれ ——
 * {@code create:item/cogwheel} は {@code create:block/cogwheel} を指していて、歯が 7 要素そろっている)。
 * <b>MOD ごとの対応を書かずに</b>本来の姿を取り戻せる。
 *
 * <p>持ち物の模型には<b>blockstate の回転が掛かっていない</b>ので、こちらで向きを付け直す
 * ({@link #turnFor})。面を焼き直すのは 1 回だけで、あとは覚えておく ——
 * 毎回作り直すと面ごとに覚えている苔が際限なく増えた(2026-10-30 までの OvergrownLayerQuads)。
 *
 * <p>それでも駄目なら blockstate が指している普通の模型(苔の立方体)に落とす。
 * 見た目が立方体に化けても<b>元のブロックは壊せば返る</b>ので失うものは無い。
 * 他 MOD の模型を呼ぶところは全部 {@code try} で囲む ——
 * ここで例外が漏れるとチャンクの組み立てごと落ちる。
 */
public class OvergrownHuskModel implements IDynamicBakedModel {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 覆われたブロックはすべてこの層へ描く。 */
    private static final ChunkRenderTypeSet RENDER_TYPES = ChunkRenderTypeSet.of(RenderType.cutoutMipped());

    /** 元のブロック状態 → 面を借りる先。模型を焼き直すたびに捨てる。 */
    private static final Map<BlockState, Optional<Source>> SOURCES = new ConcurrentHashMap<>();

    /** 例外を投げた模型。ログを 1 回だけ出すため。 */
    private static final Map<BakedModel, Boolean> COMPLAINED = new ConcurrentHashMap<>();

    private final BakedModel fallback;

    public OvergrownHuskModel(BakedModel fallback) {
        this.fallback = fallback;
    }

    /** 模型を焼き直したら覚えていたことを捨てる({@code OvergrownVegetationClient} から)。 */
    public static void forget() {
        SOURCES.clear();
        COMPLAINED.clear();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                    ModelData data, RenderType renderType) {
        BlockState mimic = data.get(OvergrownHuskBlockEntity.MIMIC);
        Source source = mimic == null || mimic.isAir() ? null : source(mimic);
        if (source == null) {
            // mimic が空 = 元のブロックを足していた MOD を外した。
            // 面が出てこない = 描画器が丸ごと描くブロックで、持ち物の模型も無い。
            return fallback.getQuads(state, side, random, ModelData.EMPTY, renderType);
        }
        List<BakedQuad> borrowed = source.quads(side, random);
        if (borrowed.isEmpty()) {
            return borrowed;
        }
        // 覆いの絵を面全体に重ねる(2026-10-30 から。記録で覆ったブロックと同じ client/CoverRenderer の作り)。
        // 元の面と同じ cutoutMipped の後ろに積むので、押し出さずに重ねる。絵と回転は位置で選ぶ(位置が無ければ原点)。
        CoverSprites.Set sprites = CoverSprites.current();
        if (sprites == null) {
            return borrowed;
        }
        net.minecraft.core.BlockPos pos = data.get(OvergrownHuskBlockEntity.POS);
        int x = pos != null ? pos.getX() : 0;
        int y = pos != null ? pos.getY() : 0;
        int z = pos != null ? pos.getZ() : 0;
        com.yayuy.overgrownvegetation.data.CoverTable design = com.yayuy.overgrownvegetation.data.DesignData.cover();
        List<BakedQuad> out = new ArrayList<>(borrowed.size() * 2);
        for (BakedQuad quad : borrowed) {
            // 元の面そのものを暗くして描く(覆いと同じく、苔の間から見える元の面を置き換えブロックの絵と揃える。2026-10-31 夜)
            BakedQuad dark = CoverQuads.darkened(quad, net.minecraft.client.renderer.RenderType.cutoutMipped(), design);
            out.add(dark != null ? dark : quad);
        }
        for (BakedQuad quad : borrowed) {
            out.add(CoverQuads.overlay(quad, true, x, y, z, sprites, design));
        }
        return out;
    }

    // --- どの模型から借りるか ---

    @Nullable
    private static Source source(BlockState mimic) {
        Optional<Source> known = SOURCES.get(mimic);
        if (known == null) {
            known = Optional.ofNullable(findSource(mimic));
            SOURCES.put(mimic, known);
        }
        return known.orElse(null);
    }

    /**
     * 借りられる面を持っている模型を探す。
     *
     * <ol>
     *     <li><b>ブロックの模型</b> —— 普通はこれで済む。</li>
     *     <li><b>持ち物の模型</b> —— 描画器が描いているブロック(Create の歯車など)の逃げ道。
     *     平らな絵({@code item/generated})は除く({@link BakedModel#isGui3d})——
     *     世界の中に 1 枚の板が立つことになり、苔の立方体より悪い。</li>
     * </ol>
     */
    @Nullable
    private static Source findSource(BlockState mimic) {
        BakedModel block = Minecraft.getInstance().getBlockRenderer().getBlockModel(mimic);
        if (hasQuads(block, mimic)) {
            return new Source(block, mimic, null);
        }
        BakedModel item = itemModel(mimic);
        if (item != null && item.isGui3d() && hasQuads(item, null)) {
            return new Source(item, null, turnFor(mimic));
        }
        return null;
    }

    /** そのブロックの持ち物の模型。持ち物が無い・見つからなければ {@code null}。 */
    @Nullable
    private static BakedModel itemModel(BlockState mimic) {
        Item item = mimic.getBlock().asItem();
        if (item == Items.AIR) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        ModelManager models = Minecraft.getInstance().getModelManager();
        BakedModel model = models.getModel(ModelResourceLocation.inventory(id));
        return model == models.getMissingModel() ? null : model;
    }

    /**
     * 持ち物の模型に付け直す向き。
     *
     * <p>blockstate の回転は<b>焼き上がった模型からは取り出せない</b>ので、
     * ブロック状態の<b>向きの属性</b>から引き直す。バニラの
     * {@code BlockModelGenerators} が出す並び —— 柱は {@code axis=x → x90 y90} /
     * {@code axis=z → x90}、水平向きは {@code y = 向きの角度} —— は
     * ほとんどの MOD が同じ道具で作っているので、そのまま当てはまる。
     *
     * <p><b>この 2 つ以外では回さない。</b> 上下を向く {@code FACING} は
     * バニラ自身が模型を分けていて並びが決まっていないので、当てずっぽうに回すと
     * かえっておかしくなる。向きが付かなくても<b>形は正しい</b>ので、そこで止める。
     */
    @Nullable
    private static BlockModelRotation turnFor(BlockState mimic) {
        if (mimic.hasProperty(BlockStateProperties.AXIS)) {
            return switch (mimic.getValue(BlockStateProperties.AXIS)) {
                case X -> BlockModelRotation.X90_Y90;
                case Z -> BlockModelRotation.X90_Y0;
                default -> null;
            };
        }
        if (mimic.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            int y = (int) mimic.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
            return y == 0 ? null : BlockModelRotation.by(0, y);
        }
        return null;
    }

    /**
     * その模型に<b>借りられる面が 1 枚でもあるか</b>。
     *
     * <p>向きを指定した 6 通りと、向きを持たない面の 7 通りを見る ——
     * 1 つの向きだけ空なのは普通のこと(立方体でも隣に塞がれる向きがある)なので、
     * <b>全部空だったときだけ</b>「面を持たない」と判断する。
     */
    private static boolean hasQuads(BakedModel model, @Nullable BlockState state) {
        RandomSource probe = RandomSource.create();
        for (Direction side : Direction.values()) {
            probe.setSeed(42L);
            if (!borrow(model, state, side, probe).isEmpty()) {
                return true;
            }
        }
        probe.setSeed(42L);
        return !borrow(model, state, null, probe).isEmpty();
    }

    /**
     * 模型から面を借りる。
     *
     * <p>{@code renderType} に {@code null} を渡すと「その面の全部」が返る(NeoForge)。
     * 1 回で済ませるのが肝 —— {@link #getRenderTypes} の説明にある乱数の理由。
     */
    private static List<BakedQuad> borrow(BakedModel model, @Nullable BlockState state,
                                          @Nullable Direction side, RandomSource random) {
        try {
            return model.getQuads(state, side, random, ModelData.EMPTY, null);
        } catch (Throwable t) {
            if (COMPLAINED.putIfAbsent(model, Boolean.TRUE) == null) {
                LOGGER.warn("覆われたブロックが {} の模型を借りられなかった。苔の立方体で代用する。", state, t);
            }
            return List.of();
        }
    }

    // --- 面を回す ---

    /**
     * 面の並びを {@code turn} のぶんだけブロックの中心まわりに回す。
     *
     * <p>頂点の位置・法線・面の向きの 3 つを揃えて回すこと。
     * 面の向きを忘れると<b>陰の付き方が合わず</b>、苔の貼り方も狂う
     * ({@link CoverQuads} は面の向きで貼る軸を決めている)。
     */
    private static List<BakedQuad> rotate(List<BakedQuad> quads, BlockModelRotation turn) {
        Transformation transformation = turn.getRotation();
        Matrix4f matrix = transformation.getMatrix();
        List<BakedQuad> out = new ArrayList<>(quads.size());
        Vector4f point = new Vector4f();

        for (BakedQuad quad : quads) {
            int[] src = quad.getVertices();
            int[] copy = src.clone();
            int stride = copy.length / 4;
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                point.set(Float.intBitsToFloat(src[base]) - 0.5F,
                        Float.intBitsToFloat(src[base + 1]) - 0.5F,
                        Float.intBitsToFloat(src[base + 2]) - 0.5F, 1.0F);
                matrix.transform(point);
                copy[base] = Float.floatToRawIntBits(point.x() + 0.5F);
                copy[base + 1] = Float.floatToRawIntBits(point.y() + 0.5F);
                copy[base + 2] = Float.floatToRawIntBits(point.z() + 0.5F);
                copy[base + 7] = rotateNormal(src[base + 7], matrix, point);
            }
            out.add(new BakedQuad(copy, quad.getTintIndex(),
                    Direction.rotate(matrix, quad.getDirection()), quad.getSprite(),
                    quad.isShade(), quad.hasAmbientOcclusion()));
        }
        return List.copyOf(out);
    }

    /** 詰めてある法線(1 バイトずつ)を回して詰め直す。 */
    private static int rotateNormal(int packed, Matrix4f matrix, Vector4f scratch) {
        if (packed == 0) {
            return 0; // 法線を持たない面。そのまま。
        }
        scratch.set((byte) (packed & 0xFF) / 127.0F,
                (byte) (packed >> 8 & 0xFF) / 127.0F,
                (byte) (packed >> 16 & 0xFF) / 127.0F, 0.0F);
        matrix.transform(scratch);
        return (Math.round(scratch.x() * 127.0F) & 0xFF)
                | (Math.round(scratch.y() * 127.0F) & 0xFF) << 8
                | (Math.round(scratch.z() * 127.0F) & 0xFF) << 16;
    }

    /**
     * 面を借りる先 1 つぶん。
     *
     * <p>回した面は<b>覚えておく</b>。毎回作り直すと、面ごとに苔を覚えている
     * 表が際限なく増える(2026-10-30 までの OvergrownLayerQuads)。
     * 回さないとき(ブロックの模型を借りるとき)は覚えない ——
     * 模型が乱数で見た目を選ぶことがあり、固めてしまうと 1 種類に揃ってしまう。
     */
    private static final class Source {

        private final BakedModel model;
        @Nullable
        private final BlockState state;
        @Nullable
        private final BlockModelRotation turn;
        /** 向きごとの回した面。添字 6 は「向きを持たない面」。 */
        private final Map<Integer, List<BakedQuad>> turned = new ConcurrentHashMap<>();
        /**
         * {@link #quads} が同じ並びを返し続けるか(苔を足した出来上がりを覚えてよいか、2026-10-10)。
         * 回した面は覚えた並びを返す。回さないときは覚えない(2026-10-30 まで CoveredBlockModels.DressedLists で使い回していた。今は使っていない)。
         */
        final boolean stable;

        Source(BakedModel model, @Nullable BlockState state, @Nullable BlockModelRotation turn) {
            this.model = model;
            this.state = state;
            this.turn = turn;
            this.stable = turn != null;
        }

        List<BakedQuad> quads(@Nullable Direction side, RandomSource random) {
            if (turn == null) {
                return borrow(model, state, side, random);
            }
            int slot = side == null ? 6 : side.ordinal();
            List<BakedQuad> done = turned.get(slot);
            if (done != null) {
                return done;
            }
            done = rotate(borrow(model, state, side, random), turn);
            List<BakedQuad> raced = turned.putIfAbsent(slot, done);
            return raced != null ? raced : done;
        }

        TextureAtlasSprite particle() {
            return model.getParticleIcon(ModelData.EMPTY);
        }
    }

    // --- 残りは blockstate の模型に任せる ---

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return RENDER_TYPES;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        BlockState mimic = data.get(OvergrownHuskBlockEntity.MIMIC);
        Source source = mimic == null || mimic.isAir() ? null : source(mimic);
        if (source != null) {
            try {
                return source.particle();
            } catch (Throwable ignored) {
                // 下の予備へ落ちる。
            }
        }
        return fallback.getParticleIcon(ModelData.EMPTY);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public TextureAtlasSprite getParticleIcon() {
        return fallback.getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        return fallback.getTransforms();
    }
}
